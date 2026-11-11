package com.example.quickdrop.controller;

import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.network.PublicUrlService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class NetworkSettingsController {
    private final NetworkAddressService network;
    private final PublicUrlService urls;

    public NetworkSettingsController(NetworkAddressService network, PublicUrlService urls) {
        this.network = network;
        this.urls = urls;
    }

    @GetMapping("/owner/network")
    public String settings(Model model) {
        var choices = network.availableAddresses();
        String selected = network.selectedAddress();
        model.addAttribute("addresses", choices);
        model.addAttribute("selectedAddress", selected);
        model.addAttribute("selectionMissing", !selected.isBlank()
                && choices.stream().noneMatch(a -> a.address().equals(selected)));
        model.addAttribute("publicOverride", urls.hasPublicOverride());
        model.addAttribute("currentUrl", urls.currentBaseUrl());
        return "network-settings";
    }

    @PostMapping("/owner/network")
    public String select(@RequestParam String address, Model model, HttpServletResponse response) {
        try {
            if (urls.hasPublicOverride())
                throw new IllegalArgumentException("A configured public base URL is active. Change that setting and restart to use an adapter address.");
            network.selectAddress(address);
            model.addAttribute("notice", "Address updated. Newly generated links and QR codes will use it.");
        } catch (IllegalArgumentException exception) {
            response.setStatus(400);
            model.addAttribute("error", exception.getMessage());
        }
        return settings(model);
    }
}
