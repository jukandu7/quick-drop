package com.example.quickdrop.share;

import com.example.quickdrop.events.FileEventService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            Instant expiresAt
    ) {
        SharedFile sharedFile = repository.findByFilename(filename)
                .orElseGet(() -> new SharedFile(
                        UUID.randomUUID().toString(), filename, expiresAt
                ));

        sharedFile.setExpiresAt(expiresAt);
        LinkDetails details = toDetails(repository.save(sharedFile));
        fileEventService.filesChangedAfterCommit();
        return details;
    }

    @Transactional
    public Optional<String> findFilename(String token) {
        Optional<SharedFile> match = repository.findById(token);

        if (match.isEmpty()) {
            return Optional.empty();
        }

        SharedFile sharedFile = match.get();

        if (isExpired(sharedFile, Instant.now())) {
            delete(sharedFile);
            return Optional.empty();
        }

        return Optional.of(sharedFile.getFilename());
    }

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
    public List<String> findBundleFilenames(String bundleToken) {
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
                .map(SharedFile::getFilename)
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

            Path filePath = uploadDirectory
                    .resolve(sharedFile.getFilename())
                    .normalize();

            if (filePath.startsWith(uploadDirectory)
                    && Files.isRegularFile(filePath)) {
                activeTokens.add(token);
            }
        }

        return activeTokens;
    }

    private void delete(SharedFile sharedFile) {
        Path filePath = uploadDirectory
                .resolve(sharedFile.getFilename())
                .normalize();

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
