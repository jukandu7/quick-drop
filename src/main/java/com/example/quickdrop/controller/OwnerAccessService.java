package com.example.quickdrop.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
