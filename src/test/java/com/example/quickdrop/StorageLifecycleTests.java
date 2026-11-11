package com.example.quickdrop;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.share.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:storage-lifecycle")
class StorageLifecycleTests {
    @Autowired ShareLinkService shares;
    @Autowired com.example.quickdrop.config.TransferLimits limits;
    @Autowired ShareBundleRepository bundles;
    @Autowired PendingFileDeletionRepository pending;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean SharedFileRepository files;
    @MockitoSpyBean StorageFileOperations disk;
    @MockitoBean FileEventService events;
    @TempDir Path uploads;

    @BeforeEach
    void setUp() {
        bundles.deleteAll();
        pending.deleteAll();
        files.deleteAll();
        ReflectionTestUtils.setField(shares, "uploadDirectory", uploads);
    }

    private ShareLinkService.StoredUpload upload(String name, String text, boolean overwrite) throws Exception {
        return shares.storeUpload(new MockMultipartFile("file", name, "text/plain", text.getBytes()),
                Instant.now().plusSeconds(3600), overwrite, false);
    }

    @Test
    void deletionRollbackKeepsBytesMetadataAndLinks() throws Exception {
        var first = upload("keep.txt", "keep", false);
        var second = upload("other.txt", "other", false);
        var bundle = shares.createBundle(List.of(first.token(), second.token())).orElseThrow();
        Path path = shares.findFile(first.token()).orElseThrow().path();
        doThrow(new DataIntegrityViolationException("delete failed")).when(files).delete(any(SharedFile.class));
        assertThatThrownBy(() -> shares.deleteByTokens(List.of(first.token())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(Files.readString(path)).isEqualTo("keep");
        assertThat(shares.findFile(first.token())).isPresent();
        assertThat(shares.findBundleFiles(bundle.token())).hasSize(2);
        assertThat(pending.count()).isZero();
    }

    @Test
    void failedLegacyCleanupIsDurableAndCannotBeResurrectedByListing() throws Exception {
        Path legacy = uploads.resolve("legacy.txt");
        Files.writeString(legacy, "legacy");
        var link = files.saveAndFlush(new SharedFile("legacy-token", "legacy.txt", null));
        doThrow(new IOException("locked file")).when(disk).delete(legacy);
        assertThat(shares.deleteByTokens(List.of(link.getToken()))).containsExactly(link.getToken());
        assertThat(Files.exists(legacy)).isTrue();
        assertThat(pending.count()).isEqualTo(1);
        assertThat(shares.snapshot().files()).isEmpty();
        assertThat(shares.findByFilename("legacy.txt")).isEmpty();
        assertThat(shares.findFile(link.getToken())).isEmpty();
        assertThat(files.count()).isZero();
        doCallRealMethod().when(disk).delete(legacy);
        // A new service instance has no in-memory state from the interrupted process.
        var restarted = new ShareLinkService(files, bundles, pending, disk, events, transactions, uploads.toString(), limits);
        restarted.recoverStorage();
        assertThat(Files.exists(legacy)).isFalse();
        assertThat(pending.count()).isZero();
        restarted.recoverStorage();
        assertThat(restarted.snapshot().files()).isEmpty();
    }

    @Test
    void replacementCleanupFailureNeverRemovesTheNewVersion() throws Exception {
        var old = upload("report.txt", "old", false);
        Path oldPath = shares.findFile(old.token()).orElseThrow().path();
        doThrow(new IOException("busy")).when(disk).delete(oldPath);
        var next = upload("report.txt", "new", true);
        Path nextPath = shares.findFile(next.token()).orElseThrow().path();
        assertThat(Files.readString(nextPath)).isEqualTo("new");
        assertThat(pending.count()).isEqualTo(1);
        assertThat(shares.snapshot().files()).extracting(ShareLinkService.FileEntry::token).containsExactly(next.token());
        doCallRealMethod().when(disk).delete(oldPath);
        shares.recoverStorage();
        assertThat(Files.exists(oldPath)).isFalse();
        assertThat(Files.readString(nextPath)).isEqualTo("new");
        assertThat(pending.count()).isZero();
    }

    @Test
    void partialCopyFailureLeavesOriginalIntact() throws Exception {
        var old = upload("report.txt", "original", false);
        var broken = new MockMultipartFile("file", "report.txt", "text/plain", new byte[10]) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    int reads;
                    @Override public int read() throws IOException {
                        if (++reads > 3) throw new IOException("source disconnected");
                        return 42;
                    }
                };
            }
        };
        assertThatThrownBy(() -> shares.storeUpload(broken, null, true, false)).isInstanceOf(IOException.class);
        assertThat(Files.readString(shares.findFile(old.token()).orElseThrow().path())).isEqualTo("original");
        try (var paths = Files.list(uploads.resolve(".staging"))) { assertThat(paths.toList()).isEmpty(); }
        assertThat(files.count()).isEqualTo(1);
    }

    @Test
    void recoveryQuarantinesOrphansClearsPartialUploadsAndRemovesMissingMetadata() throws Exception {
        var keep = upload("keep.txt", "keep", false);
        var missing = upload("missing.txt", "missing", false);
        Files.delete(shares.findFile(missing.token()).orElseThrow().path());
        Path orphan = uploads.resolve(".versions/orphan-version");
        Files.writeString(orphan, "unpublished bytes");
        Path partial = uploads.resolve(".staging/interrupted.part");
        Files.writeString(partial, "partial");
        Files.writeString(uploads.resolve("untracked-legacy.txt"), "do not publish");
        var restarted = new ShareLinkService(files, bundles, pending, disk, events, transactions, uploads.toString(), limits);
        restarted.recoverStorage();
        assertThat(restarted.findFile(keep.token())).isPresent();
        assertThat(files.findById(missing.token())).isEmpty();
        assertThat(Files.exists(partial)).isFalse();
        assertThat(Files.exists(orphan)).isFalse();
        assertThat(restarted.snapshot().files()).hasSize(1);
        try (var paths = Files.list(uploads.resolve(".recovery"))) {
            var quarantined = paths.toList();
            assertThat(quarantined).hasSize(1);
            assertThat(Files.readString(quarantined.getFirst())).isEqualTo("unpublished bytes");
        }
        restarted.recoverStorage();
        assertThat(restarted.snapshot().files()).hasSize(1);
    }

    @Test
    void recoveryDoesNotRemoveAnActiveStagedUpload() throws Exception {
        CountDownLatch copying = new CountDownLatch(1), release = new CountDownLatch(1);
        var slow = new MockMultipartFile("file", "slow.txt", "text/plain", "slow".getBytes()) {
            @Override public InputStream getInputStream() throws IOException {
                copying.countDown();
                await(release);
                return super.getInputStream();
            }
        };
        try (var executor = Executors.newSingleThreadExecutor()) {
            var upload = executor.submit(() -> shares.storeUpload(slow, null, false, false));
            try {
                assertThat(copying.await(5, TimeUnit.SECONDS)).isTrue();
                shares.recoverStorage();
                try (var paths = Files.list(uploads.resolve(".staging"))) { assertThat(paths.toList()).hasSize(1); }
            } finally { release.countDown(); }
            assertThat(Files.readString(shares.findFile(upload.get(5, TimeUnit.SECONDS).token()).orElseThrow().path()))
                    .isEqualTo("slow");
        }
    }

    @Test
    void deletionWaitsForReplacementCommitThenCannotDeleteItsBytes() throws Exception {
        var old = upload("report.txt", "old", false);
        CountDownLatch saving = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            saving.countDown();
            await(release);
            return invocation.callRealMethod();
        }).when(disk).move(any(Path.class), any(Path.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var replacement = executor.submit(() -> upload("report.txt", "new", true));
            Future<List<String>> deletion;
            try {
                assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
                deletion = executor.submit(() -> shares.deleteByTokens(List.of(old.token())));
                assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            var next = replacement.get(5, TimeUnit.SECONDS);
            assertThat(deletion.get(5, TimeUnit.SECONDS)).isEmpty();
            assertThat(Files.readString(shares.findFile(next.token()).orElseThrow().path())).isEqualTo("new");
        }
    }

    @Test
    void expirationAndOverwriteAreSerializedAcrossTheDatabaseCommit() throws Exception {
        var old = upload("report.txt", "old", false);
        var row = files.findById(old.token()).orElseThrow();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        files.saveAndFlush(row);
        CountDownLatch selecting = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = files.findAll().stream()
                    .filter(file -> file.getExpiresAt() != null
                            && file.getExpiresAt().isBefore(invocation.getArgument(0, Instant.class))).toList();
            if (first.getAndSet(false)) { selecting.countDown(); await(release); }
            return result;
        }).when(files).findAllByExpiresAtBefore(any(Instant.class));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiration = executor.submit(shares::deleteExpiredFiles);
            Future<ShareLinkService.StoredUpload> replacement;
            try {
                assertThat(selecting.await(5, TimeUnit.SECONDS)).isTrue();
                replacement = executor.submit(() -> upload("report.txt", "new", true));
                assertThatThrownBy(() -> replacement.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            expiration.get(5, TimeUnit.SECONDS);
            var next = replacement.get(5, TimeUnit.SECONDS);
            assertThat(shares.findFile(old.token())).isEmpty();
            assertThat(Files.readString(shares.findFile(next.token()).orElseThrow().path())).isEqualTo("new");
        }
    }

    @Test
    void competingUploadsCannotBothClaimTheSameFilename() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> upload("same.txt", "a", false));
            var b = executor.submit(() -> upload("same.txt", "b", false));
            int success = 0, conflicts = 0;
            for (var task : List.of(a, b)) {
                try { task.get(5, TimeUnit.SECONDS); success++; }
                catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ShareLinkService.UploadRejected.class);
                    assertThat(((ShareLinkService.UploadRejected) exception.getCause()).status()).isEqualTo(409);
                    conflicts++;
                }
            }
            assertThat(success).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(shares.snapshot().files()).hasSize(1);
        }
    }

    @Test
    void notificationFailureDoesNotUndoSuccessfulPublication() throws Exception {
        doThrow(new IllegalStateException("event consumer failed")).when(events).filesChangedAfterCommit();
        var file = upload("keep.txt", "keep", false);
        assertThat(Files.readString(shares.findFile(file.token()).orElseThrow().path())).isEqualTo("keep");
        shares.recoverStorage();
        assertThat(shares.findFile(file.token())).isPresent();
    }

    @Test
    void recoveryRetriesAcknowledgementAfterBytesWereAlreadyDeleted() {
        pending.saveAndFlush(new PendingFileDeletion(".versions/already-deleted"));
        shares.recoverStorage();
        assertThat(pending.count()).isZero();
    }

    @Test
    void failedDatabaseReadDoesNotQuarantineOrDeleteFiles() throws Exception {
        var keep = upload("keep.txt", "keep", false);
        Path path = shares.findFile(keep.token()).orElseThrow().path();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"))
                .when(files).findAll();
        shares.recoverStorage();
        assertThat(Files.readString(path)).isEqualTo("keep");
        assertThat(Files.exists(uploads.resolve(".recovery"))).isFalse();
    }

    @Test
    void independentReadsCanProceedConcurrently() throws Exception {
        var file = upload("read.txt", "read", false);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            var result = files.findAll().stream().filter(row -> row.getToken().equals(file.token())).findFirst();
            reading.countDown();
            await(release);
            return result;
        }).when(files).findById(file.token());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var lookup = executor.submit(() -> shares.findFile(file.token()));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                var listing = executor.submit(shares::snapshot);
                assertThat(listing.get(2, TimeUnit.SECONDS).files()).hasSize(1);
            } finally { release.countDown(); }
            assertThat(lookup.get(5, TimeUnit.SECONDS)).isPresent();
        }
    }

    @Test
    void slowNotificationDoesNotBlockReadsOrAnotherUpload() throws Exception {
        CountDownLatch notifying = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) { notifying.countDown(); await(release); }
            return null;
        }).when(events).filesChangedAfterCommit();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var slow = executor.submit(() -> upload("first.txt", "first", false));
            try {
                assertThat(notifying.await(5, TimeUnit.SECONDS)).isTrue();
                var other = executor.submit(() -> {
                    // Notification observes a committed publication, outside all nested locks.
                    assertThat(shares.findByFilename("first.txt")).isPresent();
                    return upload("second.txt", "second", false);
                });
                assertThat(other.get(2, TimeUnit.SECONDS).filename()).isEqualTo("second.txt");
            } finally { release.countDown(); }
            slow.get(5, TimeUnit.SECONDS);
            assertThat(shares.snapshot().files()).hasSize(2);
        }
    }

    @Test
    void writerWaitsForActiveReaderAndThenCommitsSafely() throws Exception {
        var file = upload("read.txt", "read", false);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> {
            var result = files.findAll().stream().filter(row -> row.getToken().equals(file.token())).findFirst();
            reading.countDown();
            await(release);
            return result;
        }).when(files).findById(file.token());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var lookup = executor.submit(() -> shares.findFile(file.token()));
            Future<List<String>> deletion;
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                deletion = executor.submit(() -> shares.deleteByTokens(List.of(file.token())));
                assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            assertThat(lookup.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(deletion.get(5, TimeUnit.SECONDS)).containsExactly(file.token());
            assertThat(shares.snapshot().files()).isEmpty();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("Test latch timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
