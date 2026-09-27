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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OwnerAccessFilter extends OncePerRequestFilter {

    private final OwnerAccessService ownerAccessService;

    public OwnerAccessFilter(OwnerAccessService ownerAccessService) {
        this.ownerAccessService = ownerAccessService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isProtectedPath(path(request));
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

    private boolean isProtectedPath(String requestPath) {
        return requestPath.equals("/")
                || requestPath.equals("/api/files")
                || requestPath.equals("/api/status")
                || requestPath.equals("/events/files")
                || requestPath.equals("/upload")
                || requestPath.equals("/download")
                || requestPath.equals("/download/selected")
                || requestPath.equals("/files")
                || requestPath.equals("/share/bundles")
                || requestPath.equals("/receive-links")
                || requestPath.equals("/h2-console")
                || requestPath.startsWith("/h2-console/");
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
