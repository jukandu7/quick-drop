package com.example.quickdrop.controller;

import com.example.quickdrop.events.FileEventService;
import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import com.example.quickdrop.network.PublicUrlService;
import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static com.example.quickdrop.controller.TransferResponses.*;

@Controller
public class HomeController {
    private final ShareLinkService shareLinkService;
    private final ReceiveLinkService receiveLinkService;
    private final FileEventService fileEventService;
    private final PublicUrlService publicUrls;
    private final OwnerAccessService ownerAccessService;
    private final OwnerPinService ownerPinService;
    private final TransferLimits limits;

    public HomeController(ShareLinkService shareLinkService, ReceiveLinkService receiveLinkService, FileEventService fileEventService, PublicUrlService publicUrls, OwnerAccessService ownerAccessService, OwnerPinService ownerPinService, TransferLimits limits) {
        this.shareLinkService = shareLinkService;
        this.receiveLinkService = receiveLinkService;
        this.fileEventService = fileEventService;
        this.publicUrls = publicUrls;
        this.ownerAccessService = ownerAccessService;
        this.ownerPinService = ownerPinService;
        this.limits = limits;
    }

    @GetMapping("/")
    public String home(
            Model model,
            HttpServletRequest request
    ) throws IOException {
        if (ownerAccessService.isLocalRequest(request)
                && !ownerPinService.isConfigured()) {
            return "redirect:/owner/setup";
        }

        FilesSnapshot snapshot = createFilesSnapshot();
        String shareBaseUrl = publicUrls.currentBaseUrl();
        ReceiveLinkService.LinkDetails receivingSession =
                receiveLinkService.findCurrentActive().orElse(null);
        String senderUrl = receivingSession == null || shareBaseUrl == null
                ? null
                : shareBaseUrl + "/receive/" + receivingSession.token();

        model.addAttribute("files", snapshot.files());
        model.addAttribute("storageLabel", snapshot.storageLabel());
        model.addAttribute("shareBaseUrl", shareBaseUrl);
        model.addAttribute("senderUrl", senderUrl);
        model.addAttribute("receivingActive", receivingSession != null);
        model.addAttribute(
                "receivingExpirationLabel",
                receivingSession == null
                        ? null
                        : formatExpiration(receivingSession.expiresAt())
        );
        model.addAttribute(
                "remoteOwnerSession",
                ownerAccessService.isRemoteAuthenticated(request)
        );
        model.addAttribute(
                "localOwner",
                ownerAccessService.isLocalRequest(request)
        );

        return "index";
    }

    @GetMapping(value = "/events/files",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter fileEvents(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return fileEventService.subscribe(ownerAccessService.liveAccess(request));
    }

    @GetMapping("/api/files")
    public ResponseEntity<FilesSnapshot> filesSnapshot()
            throws IOException {
        return ResponseEntity.ok(createFilesSnapshot());
    }

    private FilesSnapshot createFilesSnapshot() {
        ShareLinkService.StorageSnapshot snapshot = shareLinkService.snapshot();
        List<FileView> files = snapshot.files().stream()
                .map(file -> new FileView(file.filename(), file.token(), formatExpiration(file.expiresAt()), file.expiresAt(), file.sizeBytes(), DisplayFormats.size(file.sizeBytes())))
                .toList();
        return new FilesSnapshot(files, formatStorage(snapshot.usedBytes()));
    }

    private String formatExpiration(Instant expiresAt) {
        return DisplayFormats.expiration(expiresAt);
    }

    private String formatStorage(long bytes) {
        return DisplayFormats.storage(bytes, limits.storageQuota().toBytes());
    }
}
