package com.example.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/owner")
public class OwnerController {

    private final OwnerAccessService ownerAccessService;
    private final OwnerPinService ownerPinService;

    public OwnerController(
            OwnerAccessService ownerAccessService,
            OwnerPinService ownerPinService
    ) {
        this.ownerAccessService = ownerAccessService;
        this.ownerPinService = ownerPinService;
    }

    @GetMapping("/setup")
    public String setupPage(
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) {
        boolean localRequest = ownerAccessService.isLocalRequest(request);

        if (!localRequest) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        }

        model.addAttribute("localRequest", localRequest);
        model.addAttribute("configured", ownerPinService.isConfigured());
        return "owner-setup";
    }

    @PostMapping("/setup")
    public String setup(
            @RequestParam String pin,
            @RequestParam String confirmPin,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes
    ) throws IOException {
        boolean configured = ownerPinService.isConfigured();
        model.addAttribute("configured", configured);

        if (!ownerAccessService.isLocalRequest(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            model.addAttribute("localRequest", false);
            return "owner-setup";
        }

        model.addAttribute("localRequest", true);

        if (!pin.equals(confirmPin)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("error", "The two PIN entries do not match.");
            return "owner-setup";
        }

        try {
            ownerPinService.save(pin);
        } catch (IllegalArgumentException exception) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("error", exception.getMessage());
            return "owner-setup";
        }

        redirectAttributes.addFlashAttribute(
                "pinNotice",
                configured
                        ? "Owner PIN changed successfully. Other devices will need to unlock with the new PIN."
                        : "Owner PIN created successfully."
        );
        return "redirect:/";
    }

    @GetMapping("/login")
    public String loginPage(
            HttpServletRequest request,
            Model model
    ) {
        if (ownerAccessService.isLocalRequest(request)
                || ownerAccessService.isRemoteAuthenticated(request)) {
            return "redirect:/";
        }

        model.addAttribute(
                "setupRequired",
                !ownerPinService.isConfigured()
        );
        return "owner-login";
    }

    @PostMapping("/login")
    public String login(
            @RequestParam String pin,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model
    ) {
        if (!ownerPinService.isConfigured()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            model.addAttribute("setupRequired", true);
            return "owner-login";
        }

        model.addAttribute("setupRequired", false);

        if (!ownerAccessService.matchesPin(pin)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            model.addAttribute("error", "That owner PIN is incorrect.");
            return "owner-login";
        }

        ownerAccessService.authenticate(request);
        return "redirect:/";
    }

    @PostMapping("/logout")
    public String logout(HttpServletRequest request) {
        ownerAccessService.signOut(request);
        return "redirect:/owner/login";
    }
}
