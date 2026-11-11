package com.example.quickdrop.share;

import com.example.quickdrop.events.FileEventService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ShareLinkService {
    private static final Logger log = LoggerFactory.getLogger(ShareLinkService.class);

    private final SharedFileRepository repository;
    private final ShareBundleRepository bundleRepository;
    private final FileEventService fileEventService;
    private final Path uploadDirectory =
            Path.of("uploads").toAbsolutePath().normalize();

    public ShareLinkService(
            SharedFileRepository repository,
            ShareBundleRepository bundleRepository,
            FileEventService fileEventService
    ) {
        this.repository = repository;
        this.bundleRepository = bundleRepository;
        this.fileEventService = fileEventService;
    }

    @Transactional
    public LinkDetails getOrCreateLink(String filename) {
        SharedFile sharedFile = repository.findByFilename(filename)
                .orElseGet(() -> repository.save(new SharedFile(
                        UUID.randomUUID().toString(), filename, null
                )));

        return toDetails(sharedFile);
    }

    @Transactional
    public LinkDetails createOrUpdateLink(
            String filename,
            Instant expiresAt,
            String storageKey
    ) {
        repository.findByFilename(filename).ifPresent(previous -> {
            revokeBundles(List.of(previous.getToken()));
            repository.delete(previous);
            repository.flush(); // Free the unique filename before inserting the new version.
            removeAfterCommit(pathFor(previous));
        });
        SharedFile sharedFile = new SharedFile(UUID.randomUUID().toString(), filename, expiresAt);
        sharedFile.setStorageKey(storageKey);
        LinkDetails details = toDetails(repository.save(sharedFile));
        fileEventService.filesChangedAfterCommit();
        return details;
    }

    @Transactional
    public List<String> revokeLinks(List<String> tokens) {
        List<String> revoked = new ArrayList<>();
        for (String token : new LinkedHashSet<>(tokens)) {
            repository.findById(token).ifPresent(previous -> {
                SharedFile replacement = new SharedFile(UUID.randomUUID().toString(),
                        previous.getFilename(), previous.getExpiresAt());
                replacement.setStorageKey(previous.getStorageKey());
                revokeBundles(List.of(token));
                repository.delete(previous);
                repository.flush();
                repository.save(replacement);
                revoked.add(token);
            });
        }
        if (!revoked.isEmpty()) fileEventService.filesChangedAfterCommit();
        return revoked;
    }

    private void revokeBundles(List<String> tokens) {
        bundleRepository.deleteAll(bundleRepository.findContainingTokens(tokens));
    }

    private void removeAfterCommit(Path oldPath) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { Files.deleteIfExists(oldPath); }
                        catch (IOException exception) {
                            log.warn("Could not remove superseded file {}", oldPath, exception);
                        }
                    }
                });
    }

    private Path pathFor(SharedFile file) {
        Path path = file.getStorageKey() == null
                ? uploadDirectory.resolve(file.getFilename()).normalize()
                : uploadDirectory.resolve(".versions").resolve(file.getStorageKey()).normalize();
        Path parent = file.getStorageKey() == null ? uploadDirectory : uploadDirectory.resolve(".versions");
        if (!parent.equals(path.getParent())) throw new IllegalArgumentException("Invalid stored file path");
        return path;
    }

    @Transactional(readOnly = true)
    public Optional<FileReference> findByFilename(String filename) {
        return repository.findByFilename(filename).map(this::reference);
    }

    @Transactional(readOnly = true)
    public List<FileReference> storedFiles() {
        return repository.findAll().stream().map(this::reference).toList();
    }

    @Transactional
    public Optional<FileReference> findFile(String token) {
        Optional<SharedFile> match = repository.findById(token);
        if (match.isEmpty()) return Optional.empty();
        if (isExpired(match.get(), Instant.now())) {
            delete(match.get());
            return Optional.empty();
        }
        return match.map(this::reference);
    }

    private FileReference reference(SharedFile file) {
        return new FileReference(file.getFilename(), pathFor(file));
    }

    public record FileReference(String filename, Path path) { }

    @Transactional
    public List<String> deleteByTokens(List<String> tokens) {
        List<String> deletedTokens = new ArrayList<>();

        for (String token : new LinkedHashSet<>(tokens)) {
            Optional<SharedFile> match = repository.findById(token);

            if (match.isPresent()) {
                delete(match.get());
                deletedTokens.add(token);
            }
        }

        return deletedTokens;
    }

    @Transactional
    public Optional<BundleDetails> createBundle(List<String> tokens) {
        List<String> activeTokens = findActiveTokens(tokens);

        if (activeTokens.size() < 2) {
            return Optional.empty();
        }

        String bundleToken = UUID.randomUUID().toString();
        bundleRepository.save(new ShareBundle(
                bundleToken,
                activeTokens
        ));

        return Optional.of(new BundleDetails(
                bundleToken,
                activeTokens.size()
        ));
    }

    @Transactional
    public List<FileReference> findBundleFiles(String bundleToken) {
        Optional<ShareBundle> match = bundleRepository
                .findById(bundleToken);

        if (match.isEmpty()) {
            return List.of();
        }

        ShareBundle bundle = match.get();
        List<String> activeTokens = findActiveTokens(
                bundle.getFileTokens()
        );

        if (activeTokens.isEmpty()) {
            bundleRepository.delete(bundle);
            return List.of();
        }

        if (activeTokens.size() != bundle.getFileTokens().size()) {
            bundle.replaceFileTokens(activeTokens);
            bundleRepository.save(bundle);
        }

        return activeTokens.stream()
                .map(repository::findById)
                .flatMap(Optional::stream)
                .map(this::reference)
                .toList();
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void deleteExpiredFiles() {
        repository.findAllByExpiresAtBefore(Instant.now())
                .forEach(this::delete);
    }

    private boolean isExpired(SharedFile sharedFile, Instant currentTime) {
        return sharedFile.getExpiresAt() != null
                && sharedFile.getExpiresAt().isBefore(currentTime);
    }

    private List<String> findActiveTokens(List<String> tokens) {
        Instant currentTime = Instant.now();
        List<String> activeTokens = new ArrayList<>();

        for (String token : new LinkedHashSet<>(tokens)) {
            Optional<SharedFile> match = repository.findById(token);

            if (match.isEmpty()) {
                continue;
            }

            SharedFile sharedFile = match.get();

            if (isExpired(sharedFile, currentTime)) {
                delete(sharedFile);
                continue;
            }

            Path filePath = pathFor(sharedFile);

            if (filePath.startsWith(uploadDirectory)
                    && Files.isRegularFile(filePath)) {
                activeTokens.add(token);
            }
        }

        return activeTokens;
    }

    private void delete(SharedFile sharedFile) {
        Path filePath = pathFor(sharedFile);

        try {
            if (filePath.startsWith(uploadDirectory)) {
                Files.deleteIfExists(filePath);
            }
            repository.delete(sharedFile);
            fileEventService.filesChangedAfterCommit();
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Could not delete expired file "
                            + sharedFile.getFilename(),
                    exception
            );
        }
    }

    private LinkDetails toDetails(SharedFile sharedFile) {
        return new LinkDetails(
                sharedFile.getToken(),
                sharedFile.getExpiresAt()
        );
    }

    public record LinkDetails(String token, Instant expiresAt) {
    }

    public record BundleDetails(String token, int fileCount) {
    }

}
