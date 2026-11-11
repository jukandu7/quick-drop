package com.example.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.BooleanSupplier;

@Service
public class OwnerAccessService {

    private static final String SESSION_ATTRIBUTE =
            OwnerAccessService.class.getName() + ".authenticated";

    private final OwnerPinService ownerPinService;

    public OwnerAccessService(OwnerPinService ownerPinService) {
        this.ownerPinService = ownerPinService;
    }

    public boolean canAccess(HttpServletRequest request) {
        return isLocalRequest(request) || isAuthenticated(request);
    }

    public boolean isRemoteAuthenticated(HttpServletRequest request) {
        return !isLocalRequest(request) && isAuthenticated(request);
    }

    /** Capture only the session and its credential version, never the async request. */
    public BooleanSupplier liveAccess(HttpServletRequest request) {
        if (isLocalRequest(request)) return () -> true;
        HttpSession session = request.getSession(false);
        String version = ownerPinService.authenticationVersion();
        long openedAt = System.currentTimeMillis();
        return () -> {
            if (session == null || version.isEmpty()) return false;
            try {
                int idleSeconds = session.getMaxInactiveInterval();
                long lastAccess = Math.max(openedAt, session.getLastAccessedTime());
                if (idleSeconds > 0 && System.currentTimeMillis() - lastAccess >= idleSeconds * 1000L)
                    return false;
                return ownerPinService.isConfigured()
                        && version.equals(ownerPinService.authenticationVersion())
                        && version.equals(session.getAttribute(SESSION_ATTRIBUTE));
            } catch (IllegalStateException invalidatedSession) {
                return false;
            }
        };
    }

    public boolean isLocalRequest(HttpServletRequest request) {
        try {
            boolean loopbackAddress = InetAddress
                    .getByName(request.getRemoteAddr())
                    .isLoopbackAddress();
            String host = request.getServerName();

            return loopbackAddress
                    && (host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1"));
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    public boolean isPinConfigured() {
        return ownerPinService.isConfigured();
    }

    public boolean matchesPin(String candidatePin) {
        return ownerPinService.matches(candidatePin);
    }

    public void authenticate(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(
                SESSION_ATTRIBUTE,
                ownerPinService.authenticationVersion()
        );
    }

    public void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session != null) {
            session.invalidate();
        }
    }

    private boolean isAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        return session != null
                && ownerPinService.authenticationVersion().equals(
                        session.getAttribute(SESSION_ATTRIBUTE)
                )
                && ownerPinService.isConfigured();
    }
}
