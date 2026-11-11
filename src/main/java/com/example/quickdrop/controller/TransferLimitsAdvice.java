package com.example.quickdrop.controller;

import com.example.quickdrop.config.TransferLimits;
import com.example.quickdrop.presentation.DisplayFormats;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

@ControllerAdvice
public class TransferLimitsAdvice {
    private final TransferLimits limits;
    public TransferLimitsAdvice(TransferLimits limits) { this.limits = limits; }

    @ModelAttribute
    public void addLimits(Model model) {
        model.addAttribute("maxFileSizeBytes", limits.fileSize().toBytes());
        model.addAttribute("maxFileSizeLabel", DisplayFormats.size(limits.fileSize().toBytes()));
    }
}
