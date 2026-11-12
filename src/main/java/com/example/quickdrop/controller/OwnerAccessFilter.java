package com.example.quickdrop.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OwnerAccessFilter extends OncePerRequestFilter {

    // Exact assets, not directory prefixes: adding a controller or resource must
    // never silently expose it to unauthenticated remote clients.
    private static final Set<String> PUBLIC_ASSETS = Set.of(
            "/css/theme.css", "/fonts/Roboto-Variable.ttf", "/fonts/OFL.txt",
            "/js/upload-batch.js", "/js/live-status.js", "/js/receive.js"
    );

    private final OwnerAccessService ownerAccessService;

    public OwnerAccessFilter(OwnerAccessService ownerAccessService) {
        this.ownerAccessService = ownerAccessService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return isPublicRequest(request.getMethod(), path(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (ownerAccessService.canAccess(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        if (shouldRedirectToLogin(request)) {
            response.sendRedirect(
                    request.getContextPath() + "/owner/login"
            );
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"message\":\"Owner PIN required.\"}"
        );
    }

    private boolean shouldRedirectToLogin(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }

        String requestPath = path(request);

        return requestPath.equals("/")
                || requestPath.equals("/download")
                || requestPath.startsWith("/h2-console");
    }

    private boolean isPublicRequest(String method, String path) {
        if (method.equals("GET") || method.equals("HEAD")) {
            return PUBLIC_ASSETS.contains(path)
                    || path.equals("/owner/login")
                    // Setup retains its localhost-only checks in OwnerController.
                    || path.equals("/owner/setup")
                    || path.equals("/send")
                    || path.matches("/receive/[^/]+")
                    || path.matches("/receive/[^/]+/status")
                    || path.matches("/share/[^/]+")
                    || path.matches("/share/bundle/[^/]+");
        }
        if (method.equals("POST")) {
            return path.equals("/owner/login")
                    || path.equals("/owner/setup")
                    || path.equals("/owner/logout")
                    || path.equals("/send/upload") // Retired route returns 410.
                    || path.matches("/receive/[^/]+/upload");
        }
        // Everything else requires owner access, including future routes and
        // new HTTP methods on an otherwise public path. CSRF remains separate.
        return false;
    }

    private String path(HttpServletRequest request) {
        String servletPath = request.getServletPath();

        if (servletPath != null && !servletPath.isBlank()) {
            return stripPathParameters(servletPath);
        }

        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (!contextPath.isEmpty() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }

        return stripPathParameters(requestUri);
    }

    private String stripPathParameters(String path) {
        return path.replaceAll(";[^/]*", "");
    }
}
