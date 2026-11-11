package com.example.quickdrop.share;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Owns file publication and metadata commits for the single-host H2 application. */
@Service
public class ShareLinkService {
    private static final Logger log = LoggerFactory.getLogger(ShareLinkService.class);
    private final TransferLimits limits;
    private final SharedFileRepository repository;
    private final ShareBundleRepository bundleRepository;
    private final PendingFileDeletionRepository pendingDeletes;
    private final StorageFileOperations disk;
    private final FileEventService events;
    private final TransactionTemplate transaction;
    // Acquired before starting the transaction and released only after commit/rollback.
    private final ReentrantReadWriteLock access = new ReentrantReadWriteLock(true);
    private final ReentrantReadWriteLock.WriteLock lifecycle = access.writeLock();
    private final ThreadLocal<Boolean> notificationPending = new ThreadLocal<>();
    private final Set<Path> activeStages = new HashSet<>();
    private final Path uploadDirectory;

    public ShareLinkService(SharedFileRepository repository, ShareBundleRepository bundleRepository,
                            PendingFileDeletionRepository pendingDeletes, StorageFileOperations disk,
                            FileEventService events, PlatformTransactionManager transactionManager,
                            @Value("${quickdrop.storage-directory:uploads}") String directory, TransferLimits limits) {
        this.limits = limits;
        this.repository = repository;
        this.bundleRepository = bundleRepository;
        this.pendingDeletes = pendingDeletes;
        this.disk = disk;
        this.events = events;
        this.uploadDirectory = Path.of(directory).toAbsolutePath().normalize();
        this.transaction = new TransactionTemplate(transactionManager);
        // The lifecycle operation must own its commit, even if a caller has a transaction.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Path storageDirectory() { return uploadDirectory; }

    private <T> T read(Supplier<T> action) {
        access.readLock().lock();
        try { return transaction.execute(status -> action.get()); }
        finally { access.readLock().unlock(); }
    }

    private <T> T change(Supplier<T> action) {
        lifecycle.lock();
        try {
            T result = transaction.execute(status -> action.get());
            notificationPending.set(true);
            // Neither failed cleanup nor notification may turn a committed operation into a rollback.
            drainPendingDeletes();
            return result;
        } finally { releaseWriteLock(); }
    }

    private void releaseWriteLock() {
        // Upload and recovery can nest change(). Notify once after the outermost
        // lock is released, so a slow SSE client never holds up storage access.
        boolean outermost = access.getWriteHoldCount() == 1;
        boolean notify = outermost && Boolean.TRUE.equals(notificationPending.get());
        if (outermost) notificationPending.remove();
        lifecycle.unlock();
        if (notify) {
            try { events.filesChangedAfterCommit(); }
            catch (RuntimeException exception) { log.warn("File change notification failed", exception); }
        }
    }

    public StoredUpload storeUpload(MultipartFile file, Instant expiresAt, boolean overwrite,
                                    boolean renameDuplicate) throws IOException {
        if (file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank())
            throw new UploadRejected(400, "Please select a file.", null);
        if (file.getSize() > limits.fileSize().toBytes())
            throw new UploadRejected(413, "Upload rejected: files must be " + DisplayFormats.size(limits.fileSize().toBytes()) + " or smaller.", null);
        String originalName;
        try {
            originalName = Path.of(file.getOriginalFilename()).getFileName().toString();
            if (originalName.equals(".") || originalName.equals("..") || originalName.length() > 255)
                throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new UploadRejected(400, "Invalid filename.", null);
        }

        Path staged;
        lifecycle.lock();
        try {
            Files.createDirectories(uploadDirectory.resolve(".staging"));
            staged = Files.createTempFile(uploadDirectory.resolve(".staging"), "upload-", ".part");
            activeStages.add(staged);
        } finally { releaseWriteLock(); }
        try {
            // Slow copying is outside the lifecycle lock; recovery knows this stage is live.
            try (var input = file.getInputStream()) {
                Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(staged) != file.getSize()) throw new IOException("Upload size changed while staging.");
            try (var channel = java.nio.channels.FileChannel.open(staged, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            lifecycle.lock();
            try {
                deleteExpiredFiles();
                String key = UUID.randomUUID().toString();
                Path destination = uploadDirectory.resolve(".versions").resolve(key);
                try {
                    return change(() -> {
                        String name = renameDuplicate ? availableName(originalName) : originalName;
                        SharedFile old = repository.findByFilename(name).orElse(null);
                        // Unknown legacy files are preserved, not silently adopted or overwritten.
                        if (old == null && Files.exists(uploadDirectory.resolve(name)))
                            throw new UploadRejected(409, "An untracked file uses that name. Choose another filename.", null);
                        if (old != null && !overwrite)
                            throw new UploadRejected(409, "A file named " + name + " already exists.", name);
                        long previousSize = old != null && regular(pathFor(old)) ? size(pathFor(old)) : 0;
                        long projected = usedBytes() - previousSize + file.getSize();
                        if (projected > limits.storageQuota().toBytes())
                            throw new UploadRejected(507, "Upload rejected: Quick Drop's " + DisplayFormats.size(limits.storageQuota().toBytes()) + " storage limit would be exceeded.", null);
                        try {
                            Files.createDirectories(destination.getParent());
                            disk.move(staged, destination);
                        } catch (IOException exception) { throw new UncheckedIOException(exception); }
                        if (old != null) removeMetadata(old);
                        SharedFile next = new SharedFile(UUID.randomUUID().toString(), name, expiresAt);
                        next.setStorageKey(key);
                        repository.saveAndFlush(next);
                        return new StoredUpload(name, next.getToken(), expiresAt, projected);
                    });
                } catch (RuntimeException failure) {
                    // A commit failure can be ambiguous. Never delete bytes unless a fresh DB read
                    // proves they are not referenced. Recovery handles leftovers if the DB is down.
                    try {
                        if (!read(() -> repository.existsByStorageKey(key))) quietDelete(destination);
                    } catch (RuntimeException checkFailure) {
                        log.warn("Retaining upload for recovery because commit status is unknown", checkFailure);
                    }
                    if (failure instanceof UncheckedIOException io) throw io.getCause();
                    throw failure;
                }
            } finally { releaseWriteLock(); }
        } finally {
            lifecycle.lock();
            try { activeStages.remove(staged); quietDelete(staged); }
            finally { releaseWriteLock(); }
        }
    }

    public Optional<FileReference> findByFilename(String filename) {
        return read(() -> repository.findByFilename(filename).filter(this::available).map(this::reference));
    }

    public Optional<FileReference> findFile(String token) {
        return read(() -> repository.findById(token).filter(this::available).map(this::reference));
    }

    public StorageSnapshot snapshot() {
        return read(() -> new StorageSnapshot(repository.findAll().stream().filter(this::available)
                .sorted(Comparator.comparing(SharedFile::getFilename))
                .map(file -> new FileEntry(file.getFilename(), file.getToken(), file.getExpiresAt(), size(pathFor(file)))).toList(), usedBytes()));
    }

    public long usedStorage() { return read(this::usedBytes); }

    public List<String> revokeLinks(List<String> tokens) {
        return change(() -> {
            List<String> revoked = new ArrayList<>();
            for (String token : new LinkedHashSet<>(tokens)) {
                repository.findById(token).filter(this::available).ifPresent(previous -> {
                    SharedFile next = new SharedFile(UUID.randomUUID().toString(), previous.getFilename(), previous.getExpiresAt());
                    next.setStorageKey(previous.getStorageKey());
                    revokeBundles(List.of(token));
                    repository.delete(previous);
                    repository.flush();
                    repository.save(next);
                    revoked.add(token);
                });
            }
            return revoked;
        });
    }

    public List<String> deleteByTokens(List<String> tokens) {
        return change(() -> {
            List<String> deleted = new ArrayList<>();
            for (String token : new LinkedHashSet<>(tokens)) {
                repository.findById(token).ifPresent(file -> {
                    removeMetadata(file);
                    deleted.add(token);
                });
            }
            return deleted;
        });
    }

    public Optional<BundleDetails> createBundle(List<String> tokens) {
        return change(() -> {
            List<String> active = new LinkedHashSet<>(tokens).stream()
                    .filter(token -> repository.findById(token).filter(this::available).isPresent()).toList();
            if (active.size() < 2) return Optional.empty();
            String token = UUID.randomUUID().toString();
            bundleRepository.save(new ShareBundle(token, active));
            return Optional.of(new BundleDetails(token, active.size()));
        });
    }

    public List<FileReference> findBundleFiles(String token) {
        return read(() -> bundleRepository.findById(token).map(bundle -> {
            List<String> tokens = bundle.getFileTokens();
            int uniqueCount = new HashSet<>(tokens).size();
            if (uniqueCount < 2 || uniqueCount != tokens.size()) return List.<FileReference>of();
            List<FileReference> files = tokens.stream().map(repository::findById)
                    .flatMap(Optional::stream).filter(this::available).map(this::reference).toList();
            // A bundle promises the selected set. Never silently serve a partial set
            // while waiting for scheduled recovery to remove stale metadata.
            return files.size() == tokens.size() ? files : List.<FileReference>of();
        }).orElse(List.of()));
    }

    public void deleteExpiredFiles() {
        change(() -> { expireWithinTransaction(); return null; });
    }

    private void expireWithinTransaction() {
        repository.findAllByExpiresAtBefore(Instant.now()).forEach(this::removeMetadata);
    }

    private void removeMetadata(SharedFile file) {
        pendingDeletes.save(new PendingFileDeletion(relative(pathFor(file))));
        revokeBundles(List.of(file.getToken()));
        repository.delete(file);
        repository.flush();
    }

    private void revokeBundles(List<String> tokens) {
        bundleRepository.deleteAll(bundleRepository.findContainingTokens(tokens));
    }

    private void removeStaleBundles() {
        // Called inside the recovery transaction and write lock, after file cleanup.
        // Missing/expired files have already been removed there. Do not interpret
        // an indeterminate filesystem check as proof that a file is gone.
        Set<String> knownTokens = repository.findAll().stream().map(SharedFile::getToken)
                .collect(java.util.stream.Collectors.toSet());
        List<ShareBundle> stale = bundleRepository.findAll().stream().filter(bundle -> {
            Set<String> tokens = new HashSet<>(bundle.getFileTokens());
            return tokens.size() < 2 || tokens.size() != bundle.getFileTokens().size()
                    || !knownTokens.containsAll(tokens);
        }).toList();
        bundleRepository.deleteAll(stale);
    }

    /** Idempotent recovery runs on startup and periodically thereafter. */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStorage() {
        lifecycle.lock();
        try {
            Files.createDirectories(uploadDirectory);
            change(() -> {
                repository.findAll().stream().filter(file ->
                        (file.getExpiresAt() != null && !file.getExpiresAt().isAfter(Instant.now()))
                                || Files.notExists(pathFor(file), LinkOption.NOFOLLOW_LINKS))
                        .forEach(this::removeMetadata);
                removeStaleBundles();
                return null;
            });
            Set<Path> referenced = read(() -> repository.findAll().stream().map(this::pathFor)
                    .collect(java.util.stream.Collectors.toSet()));
            Set<Path> pending = read(() -> pendingDeletes.findAll().stream()
                    .map(job -> deletionPath(job.getRelativePath())).collect(java.util.stream.Collectors.toSet()));
            Path staging = uploadDirectory.resolve(".staging");
            if (Files.isDirectory(staging)) {
                try (Stream<Path> paths = Files.list(staging)) {
                    paths.filter(this::regular).filter(path -> !activeStages.contains(path)).forEach(this::quietDelete);
                }
            }
            Path versions = uploadDirectory.resolve(".versions");
            if (Files.isDirectory(versions)) {
                try (Stream<Path> paths = Files.list(versions)) {
                    for (Path path : paths.filter(this::regular).toList()) {
                        if (!referenced.contains(path) && !pending.contains(path)) {
                            Path recovery = uploadDirectory.resolve(".recovery");
                            Files.createDirectories(recovery);
                            disk.move(path, recovery.resolve(UUID.randomUUID() + "-" + path.getFileName()));
                            log.warn("Quarantined untracked upload {} for manual recovery", path.getFileName());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Storage recovery incomplete; it will retry in one minute", exception);
        } finally { releaseWriteLock(); }
    }

    private void drainPendingDeletes() {
        try {
            List<PendingFileDeletion> jobs = transaction.execute(status -> pendingDeletes.findAll());
            for (PendingFileDeletion job : jobs) {
                try {
                    Path path = deletionPath(job.getRelativePath());
                    boolean referenced = Boolean.TRUE.equals(transaction.execute(status -> repository.findAll().stream()
                            .anyMatch(file -> pathFor(file).equals(path))));
                    if (referenced) continue; // Never remove bytes belonging to committed metadata.
                    disk.delete(path);
                    transaction.executeWithoutResult(status -> pendingDeletes.deleteById(job.getRelativePath()));
                } catch (IOException | RuntimeException exception) {
                    log.warn("File cleanup deferred for {}", job.getRelativePath(), exception);
                }
            }
        } catch (RuntimeException exception) { log.warn("Pending cleanup will be retried", exception); }
    }

    private Path deletionPath(String relative) {
        Path path = uploadDirectory.resolve(relative).normalize();
        if (!uploadDirectory.equals(path.getParent()) && !uploadDirectory.resolve(".versions").equals(path.getParent()))
            throw new IllegalArgumentException("Invalid cleanup path");
        return path;
    }

    private Path pathFor(SharedFile file) {
        Path parent = file.getStorageKey() == null ? uploadDirectory : uploadDirectory.resolve(".versions");
        Path path = parent.resolve(file.getStorageKey() == null ? file.getFilename() : file.getStorageKey()).normalize();
        if (!parent.equals(path.getParent())) throw new IllegalArgumentException("Invalid stored file path");
        return path;
    }

    private boolean available(SharedFile file) {
        return (file.getExpiresAt() == null || file.getExpiresAt().isAfter(Instant.now())) && regular(pathFor(file));
    }
    private boolean regular(Path path) { return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS); }
    private String relative(Path path) { return uploadDirectory.relativize(path).toString().replace('\\', '/'); }
    private long size(Path path) {
        try { return Files.size(path); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private long usedBytes() {
        if (!Files.exists(uploadDirectory)) return 0;
        try (Stream<Path> paths = Files.walk(uploadDirectory, 2)) {
            return paths.filter(this::regular).filter(path -> !path.startsWith(uploadDirectory.resolve(".staging")))
                    .filter(path -> !path.startsWith(uploadDirectory.resolve(".incoming")))
                    .mapToLong(this::size).sum();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
    private void quietDelete(Path path) {
        try { disk.delete(path); }
        catch (IOException | RuntimeException exception) { log.warn("File retained for later cleanup: {}", path, exception); }
    }
    private String availableName(String filename) {
        if (!nameTaken(filename)) return filename;
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = base + " (" + i + ")" + extension;
            if (candidate.length() > 255) throw new UploadRejected(400, "Filename is too long to rename safely.", null);
            if (!nameTaken(candidate)) return candidate;
        }
    }
    private boolean nameTaken(String filename) {
        return repository.findByFilename(filename).isPresent() || Files.exists(uploadDirectory.resolve(filename));
    }
    private FileReference reference(SharedFile file) { return new FileReference(file.getFilename(), pathFor(file)); }

    public record FileReference(String filename, Path path) { }
    public record FileEntry(String filename, String token, Instant expiresAt, long sizeBytes) { }
    public record StorageSnapshot(List<FileEntry> files, long usedBytes) { }
    public record StoredUpload(String filename, String token, Instant expiresAt, long usedBytes) { }
    public record BundleDetails(String token, int fileCount) { }
    public static class UploadRejected extends RuntimeException {
        private final int status;
        private final String duplicateFilename;
        public UploadRejected(int status, String message, String duplicateFilename) {
            super(message); this.status = status; this.duplicateFilename = duplicateFilename;
        }
        public int status() { return status; }
        public String duplicateFilename() { return duplicateFilename; }
    }
}
