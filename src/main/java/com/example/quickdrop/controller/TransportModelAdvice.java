package com.example.quickdrop.controller;

import com.example.quickdrop.network.PublicUrlService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class TransportModelAdvice {
    private final PublicUrlService urls;

    public TransportModelAdvice(PublicUrlService urls) { this.urls = urls; }

    @ModelAttribute("localSetupUrl")
    public String localSetupUrl() { return urls.localBaseUrl() + "/owner/setup"; }

    @ModelAttribute("transportSecure")
    public boolean transportSecure(HttpServletRequest request) { return request.isSecure(); }
}
