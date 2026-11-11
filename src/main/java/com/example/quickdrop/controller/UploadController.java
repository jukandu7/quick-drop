package com.example.quickdrop.controller;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.example.quickdrop.controller.TransferResponses.*;

@Controller
public class UploadController {
    private final ShareLinkService shareLinkService;
    private final ReceiveLinkService receiveLinkService;
    private final FileEventService fileEventService;
    private final TransferLimits limits;

    public UploadController(ShareLinkService shareLinkService, ReceiveLinkService receiveLinkService, FileEventService fileEventService, TransferLimits limits) {
        this.shareLinkService = shareLinkService;
        this.receiveLinkService = receiveLinkService;
        this.fileEventService = fileEventService;
        this.limits = limits;
    }

    @PostMapping("/send/upload")
    public ResponseEntity<ReceiveUploadResult> senderUpload() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ReceiveUploadResult(
                        "This old sender address is inactive. Scan the "
                                + "current QR code.",
                        null
                ));
    }

    @PostMapping("/receive/{token}/upload")
    public ResponseEntity<ReceiveUploadResult> receiveUpload(
            @PathVariable String token,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        if (receiveLinkService.findActive(token).isEmpty()) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(new ReceiveUploadResult(
                            "This receive link has expired or is invalid.",
                            null
                    ));
        }

        return storeReceivedUpload(file);
    }

    private ResponseEntity<ReceiveUploadResult> storeReceivedUpload(
            MultipartFile file
    ) throws IOException {
        ResponseEntity<UploadResult> uploadResult = storeUpload(
                file,
                "24h",
                false,
                true
        );
        UploadResult result = uploadResult.getBody();

        if (result == null) {
            return ResponseEntity.status(uploadResult.getStatusCode())
                    .body(new ReceiveUploadResult(
                            "The file could not be received.",
                            null
                    ));
        }

        String message = uploadResult.getStatusCode().is2xxSuccessful()
                ? result.filename() + " received successfully."
                : result.message();

        if (uploadResult.getStatusCode().is2xxSuccessful()) {
            fileEventService.fileReceived(result.filename());
        }

        return ResponseEntity.status(uploadResult.getStatusCode())
                .body(new ReceiveUploadResult(message, result.filename()));
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "24h") String expiration,
            @RequestParam(defaultValue = "false") boolean overwrite
    ) throws IOException {
        ResponseEntity<UploadResult> uploadResult = storeUpload(
                file,
                expiration,
                overwrite,
                false
        );
        UploadResult result = uploadResult.getBody();

        if (uploadResult.getStatusCode().is2xxSuccessful()
                && result != null) {
            fileEventService.fileUploaded(result.filename());
        }

        return uploadResult;
    }



    private ResponseEntity<UploadResult> storeUpload(
            MultipartFile file, String expiration, boolean overwrite, boolean renameDuplicate
    ) throws IOException {
        Instant expiresAt;
        try {
            expiresAt = calculateExpiration(expiration);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(UploadResult.error("Choose a valid expiration time."));
        }
        try {
            ShareLinkService.StoredUpload stored = shareLinkService.storeUpload(file, expiresAt, overwrite, renameDuplicate);
            return ResponseEntity.ok(new UploadResult(stored.filename() + " uploaded successfully!",
                    stored.filename(), "/share/" + stored.token(), stored.token(),
                    formatExpiration(stored.expiresAt()), formatStorage(stored.usedBytes()), false));
        } catch (ShareLinkService.UploadRejected rejection) {
            UploadResult result = rejection.duplicateFilename() == null
                    ? UploadResult.error(rejection.getMessage())
                    : UploadResult.duplicate(rejection.duplicateFilename());
            return ResponseEntity.status(rejection.status()).body(result);
        }
    }

    private FilesSnapshot createFilesSnapshot() {
        ShareLinkService.StorageSnapshot snapshot = shareLinkService.snapshot();
        List<FileView> files = snapshot.files().stream()
                .map(file -> new FileView(file.filename(), file.token(), formatExpiration(file.expiresAt()), file.expiresAt(), file.sizeBytes(), DisplayFormats.size(file.sizeBytes())))
                .toList();
        return new FilesSnapshot(files, formatStorage(snapshot.usedBytes()));
    }
    private Instant calculateExpiration(String expiration) {
        return switch (expiration) {
            case "1h" -> Instant.now().plus(Duration.ofHours(1));
            case "24h" -> Instant.now().plus(Duration.ofHours(24));
            case "7d" -> Instant.now().plus(Duration.ofDays(7));
            case "never" -> null;
            default -> throw new IllegalArgumentException(
                    "Unknown expiration value"
            );
        };
    }

    private String formatExpiration(Instant expiresAt) {
        return DisplayFormats.expiration(expiresAt);
    }

    private String formatStorage(long bytes) {
        return DisplayFormats.storage(bytes, limits.storageQuota().toBytes());
    }
}
