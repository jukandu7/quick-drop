package com.example.quickdrop.controller;

import com.example.quickdrop.presentation.DisplayFormats;
import com.example.quickdrop.receive.ReceiveLinkService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.Duration;
import java.time.Instant;

import static com.example.quickdrop.controller.TransferResponses.*;

@Controller
public class ReceivingController {
    private final ReceiveLinkService receiveLinkService;

    public ReceivingController(ReceiveLinkService receiveLinkService) {
        this.receiveLinkService = receiveLinkService;
    }

    @PostMapping("/receive-links")
    public ResponseEntity<ReceiveLinkResult> startReceiving(
            @RequestBody ReceiveLinkRequest request
    ) {
        Duration lifetime;

        try {
            lifetime = calculateReceiveLifetime(request.expiration());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(new ReceiveLinkResult(
                    "Choose a valid receiving time.",
                    null,
                    null
            ));
        }

        ReceiveLinkService.LinkDetails link =
                receiveLinkService.start(lifetime);

        return ResponseEntity.ok(new ReceiveLinkResult(
                "Receiving started.",
                "/receive/" + link.token(),
                formatExpiration(link.expiresAt())
        ));
    }

    @DeleteMapping("/receive-links")
    public ResponseEntity<ReceiveLinkStatus> stopReceiving() {
        if (!receiveLinkService.stop()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ReceiveLinkStatus(
                            "Receiving is already stopped."
                    ));
        }

        return ResponseEntity.ok(new ReceiveLinkStatus(
                "Receiving stopped."
        ));
    }

    @GetMapping("/receive/{token}")
    public String showReceivePage(
            @PathVariable String token,
            Model model,
            HttpServletResponse response
    ) {
        ReceiveLinkService.LinkDetails link = receiveLinkService
                .findActive(token)
                .orElse(null);

        model.addAttribute("receiveToken", token);
        model.addAttribute("senderLabel", "Quick Drop sender");
        model.addAttribute("senderHeading", "Send files to Quick Drop");
        model.addAttribute(
                "senderDescription",
                "This page can only send files. It cannot view, download, "
                        + "share, or delete anything already stored."
        );
        model.addAttribute(
                "uploadAction",
                "/receive/" + token + "/upload"
        );

        if (link == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            model.addAttribute("active", false);
            model.addAttribute(
                    "inactiveHeading",
                    "Quick Drop is not receiving files"
            );
            model.addAttribute(
                    "inactiveDescription",
                    "This receiving session ended or expired. Ask the "
                            + "owner to start receiving again."
            );
            return "receive";
        }

        model.addAttribute("active", true);
        model.addAttribute(
                "expirationLabel",
                formatExpiration(link.expiresAt())
        );

        return "receive";
    }

    @GetMapping("/send")
    public String showRetiredSenderPage(
            Model model,
            HttpServletResponse response
    ) {
        response.setStatus(HttpServletResponse.SC_GONE);
        model.addAttribute("active", false);
        model.addAttribute("senderLabel", "Sender link replaced");
        model.addAttribute("senderHeading", "Ask for the current QR code");
        model.addAttribute(
                "senderDescription",
                "Quick Drop now uses a new link each time receiving starts."
        );
        model.addAttribute(
                "inactiveHeading",
                "This old sender address cannot accept files"
        );
        model.addAttribute(
                "inactiveDescription",
                "Ask the owner to start receiving and scan the new QR code."
        );
        return "receive";
    }

    private Duration calculateReceiveLifetime(String expiration) {
        return switch (expiration) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            default -> throw new IllegalArgumentException(
                    "Unknown receive-link expiration value"
            );
        };
    }

    private String formatExpiration(Instant expiresAt) {
        return DisplayFormats.expiration(expiresAt);
    }
}
