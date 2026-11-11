package com.example.quickdrop.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CsrfProtectionFilter extends OncePerRequestFilter {
    static final String SESSION_TOKEN = CsrfProtectionFilter.class.getName() + ".token";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        boolean safe = SAFE_METHODS.contains(request.getMethod());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean page = path.equals("/") || path.equals("/owner/login")
                || path.equals("/owner/setup") || path.matches("/receive/[^/]+") || path.equals("/send");
        HttpSession session = request.getSession(false);
        String expected = session == null ? null : (String) session.getAttribute(SESSION_TOKEN);

        if (!safe) {
            String supplied = request.getHeader("X-CSRF-Token");
            // Multipart clients must use the header: do not parse an upload to authenticate it.
            String contentType = request.getContentType();
            if (supplied == null && contentType != null
                    && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
                supplied = request.getParameter("_csrf");
            }
            if (!trustedOrigin(request) || expected == null || supplied == null
                    || supplied.length() != expected.length()
                    || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                             supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("Cache-Control", "no-store");
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"message\":\"Request verification failed. Refresh the page and try again.\"}");
                return;
            }
        }

        if (page) {
            session = request.getSession(true);
            synchronized (session) {
                expected = (String) session.getAttribute(SESSION_TOKEN);
                if (expected == null) {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    expected = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                    session.setAttribute(SESSION_TOKEN, expected);
                }
            }
            response.setHeader("Cache-Control", "no-store");
            request.setAttribute("csrfToken", expected);
        }
        chain.doFilter(request, response);
    }

    private boolean trustedOrigin(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) return false;
        String origin = request.getHeader("Origin");
        String source = origin != null ? origin : request.getHeader("Referer");
        // Non-browser clients may omit both; the session-bound token is still mandatory.
        if (source == null) return true;
        try {
            URI uri = URI.create(source);
            int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return uri.getUserInfo() == null && uri.getHost() != null
                    && request.getScheme().equalsIgnoreCase(uri.getScheme())
                    && request.getServerName().equalsIgnoreCase(uri.getHost())
                    && request.getServerPort() == port;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
