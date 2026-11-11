package com.example.quickdrop.controller;

import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import com.example.quickdrop.share.ShareLinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static com.example.quickdrop.controller.TransferResponses.*;

@Controller
public class FileManagementController {
    private final ShareLinkService shareLinkService;
    private final TransferLimits limits;

    public FileManagementController(ShareLinkService shareLinkService, TransferLimits limits) {
        this.shareLinkService = shareLinkService;
        this.limits = limits;
    }

    @PostMapping("/share/bundles")
    public ResponseEntity<BundleResult> createShareBundle(
            @RequestBody ShareRequest request
    ) {
        if (request.tokens() == null || request.tokens().size() < 2) {
            return ResponseEntity.badRequest().body(new BundleResult(
                    "Select at least two files for a combined link.",
                    null,
                    0
            ));
        }

        return shareLinkService.createBundle(request.tokens())
                .map(bundle -> ResponseEntity.ok(new BundleResult(
                        "Combined link created.",
                        "/share/bundle/" + bundle.token(),
                        bundle.fileCount()
                )))
                .orElseGet(() -> ResponseEntity.badRequest().body(
                        new BundleResult(
                                "At least two selected files must still "
                                        + "be available.",
                                null,
                                0
                        )
                ));
    }

    @DeleteMapping("/share-links")
    public ResponseEntity<RevokeResult> revokeShareLinks(@RequestBody ShareRequest request) {
        if (request.tokens() == null || request.tokens().isEmpty() || request.tokens().size() > 1000
                || request.tokens().stream().anyMatch(token -> token == null || token.isBlank())) {
            return ResponseEntity.badRequest().body(new RevokeResult("Select between 1 and 1000 files.", List.of()));
        }
        List<String> revoked = shareLinkService.revokeLinks(request.tokens());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new RevokeResult("Existing download links disabled for " + revoked.size()
                        + " file(s). Files kept; create new links when needed.", revoked));
    }

    @DeleteMapping("/files")
    public ResponseEntity<DeleteResult> deleteFiles(
            @RequestBody DeleteRequest request
    ) throws IOException {

        if (request.tokens() == null || request.tokens().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new DeleteResult(
                            "Select at least one file.",
                            formatStorage(shareLinkService.usedStorage()),
                            List.of()
                    ));
        }

        List<String> deletedTokens = shareLinkService
                .deleteByTokens(request.tokens());
        int deletedCount = deletedTokens.size();
        String noun = deletedCount == 1 ? "file" : "files";

        return ResponseEntity.ok(new DeleteResult(
                deletedCount + " " + noun + " deleted.",
                formatStorage(shareLinkService.usedStorage()),
                deletedTokens
        ));
    }

    private String formatStorage(long bytes) {
        return DisplayFormats.storage(bytes, limits.storageQuota().toBytes());
    }
}
