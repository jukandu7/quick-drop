package com.example.quickdrop.controller;

import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Admission happens before any servlet multipart parsing. */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class UploadProtection extends OncePerRequestFilter {
    private final ReceiveLinkService links;
    private final Path incoming;
    private final long requestLimit, fileLimit, budget, freeReserve;
    private final int concurrentLimit;
    private final Set<String> clients = new HashSet<>();
    private long reserved;

    public UploadProtection(ReceiveLinkService links, ShareLinkService storage,
            @Value("${spring.servlet.multipart.max-request-size:3073MB}") DataSize requestLimit,
            @Value("${spring.servlet.multipart.max-file-size:3GB}") DataSize fileLimit,
            @Value("${quickdrop.upload.temp-budget:7GB}") DataSize budget,
            @Value("${quickdrop.upload.minimum-free-space:512MB}") DataSize freeReserve,
            @Value("${quickdrop.upload.max-concurrent:2}") int concurrentLimit) throws IOException {
        this.links = links;
        this.requestLimit = requestLimit.toBytes();
        this.fileLimit = fileLimit.toBytes();
        this.budget = budget.toBytes();
        this.freeReserve = freeReserve.toBytes();
        this.concurrentLimit = concurrentLimit;
        if (this.requestLimit <= 0 || this.requestLimit > Long.MAX_VALUE / 2 || this.fileLimit <= 0
                || this.budget < 2 * this.requestLimit || this.freeReserve < 0 || concurrentLimit < 1)
            throw new IllegalArgumentException("Invalid upload resource limits");
        incoming = Files.createDirectories(storage.storageDirectory().toAbsolutePath().resolve(".incoming"));
    }

    @Bean
    MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement(incoming.toString(), fileLimit, requestLimit, 0);
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadTimeouts() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setProperty("disableUploadTimeout", "false");
            connector.setProperty("connectionUploadTimeout", "30000");
            connector.setProperty("maxSwallowSize", "0");
            connector.setProperty("continueResponseTiming", "onRead");
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                   FilterChain chain) throws IOException, ServletException {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        path = path.replaceAll(";[^/]*", "");
        if (path.equals("/send/upload")) { reject(response, 410, "This sender address is inactive."); return; }
        boolean guest = path.matches("/receive/[^/]+/upload");
        boolean upload = path.equals("/upload") || guest;
        String type = Optional.ofNullable(request.getContentType()).orElse("").split(";", 2)[0].trim();
        if (!upload) {
            if (type.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
                reject(response, 415, "Multipart requests are only accepted at upload addresses."); return;
            }
            chain.doFilter(request, response); return;
        }
        if (!request.getMethod().equals("POST")) { reject(response, 405, "Use POST to upload."); return; }
        if (guest && links.findActive(path.split("/")[2]).isEmpty()) {
            reject(response, 410, "This receiving link is inactive. Scan the current QR code."); return;
        }
        if (!type.equalsIgnoreCase("multipart/form-data")) { reject(response, 415, "Use multipart/form-data."); return; }
        long length = request.getContentLengthLong();
        if (length > requestLimit) { reject(response, 413, "Upload request is too large."); return; }
        // Unknown/chunked bodies reserve the full limit. Include both spool and staging copies.
        long reservation = 2 * (length < 0 ? requestLimit : length);
        String client = request.getRemoteAddr();
        synchronized (this) {
            if (clients.contains(client) || clients.size() >= concurrentLimit || reservation > budget - reserved) {
                reject(response, 429, "Uploads are busy. Please retry shortly."); return;
            }
            // Existing abandoned spool files also consume this budget until removed by the operator.
            long existing;
            try (var files = Files.list(incoming)) {
                existing = files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
            }
            long available = Files.getFileStore(incoming).getUsableSpace();
            if (reservation > budget - reserved - existing || available < freeReserve
                    || reservation > available - freeReserve - reserved) {
                reject(response, 507, "Insufficient temporary upload space."); return;
            }
            clients.add(client); reserved += reservation;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            synchronized (this) { clients.remove(client); reserved -= reservation; }
        }
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (status == 429) response.setHeader("Retry-After", "3");
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
