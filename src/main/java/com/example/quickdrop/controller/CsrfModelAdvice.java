package com.example.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class CsrfModelAdvice {
    @ModelAttribute("csrfToken")
    public String csrfToken(HttpServletRequest request) {
        return (String) request.getAttribute("csrfToken");
    }
}
