package com.example.quickdrop.controller;

import com.example.quickdrop.network.PublicUrlService;
import com.example.quickdrop.receive.ReceiveLinkService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@RestController
public class StatusController {

    private static final DateTimeFormatter EXPIRATION_FORMATTER =
            DateTimeFormatter
                    .ofPattern("MMM d, yyyy 'at' h:mm a")
                    .withZone(ZoneId.systemDefault());

    private final ReceiveLinkService receiveLinkService;
    private final PublicUrlService publicUrls;

    public StatusController(
            ReceiveLinkService receiveLinkService,
            PublicUrlService publicUrls
    ) {
        this.receiveLinkService = receiveLinkService;
        this.publicUrls = publicUrls;
    }

    @GetMapping(value = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OwnerStatus> ownerStatus() {
        ReceiveLinkService.LinkDetails link =
                receiveLinkService.findCurrentActive().orElse(null);
        Instant serverTime = Instant.now();
        ReceivingStatus receiving = link == null
                || !link.expiresAt().isAfter(serverTime)
                ? new ReceivingStatus(false, null, null, null)
                : new ReceivingStatus(
                        true,
                        "/receive/" + link.token(),
                        link.expiresAt(),
                        "Expires " + EXPIRATION_FORMATTER.format(link.expiresAt())
                );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new OwnerStatus(serverTime, publicUrls.currentBaseUrl(), receiving));
    }

    @GetMapping(
            value = "/receive/{token}/status",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<SenderStatus> senderStatus(@PathVariable String token) {
        ReceiveLinkService.LinkDetails link =
                receiveLinkService.findActive(token).orElse(null);
        Instant serverTime = Instant.now();
        boolean active = link != null && link.expiresAt().isAfter(serverTime);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SenderStatus(
                        serverTime,
                        active,
                        active ? link.expiresAt() : null
                ));
    }

    public record OwnerStatus(
            Instant serverTime,
            String shareBaseUrl,
            ReceivingStatus receiving
    ) {
    }

    public record ReceivingStatus(
            boolean active,
            String receivePath,
            Instant expiresAt,
            String expirationLabel
    ) {
    }

    public record SenderStatus(Instant serverTime, boolean active, Instant expiresAt) {
    }
}
