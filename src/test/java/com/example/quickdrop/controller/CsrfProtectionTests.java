package com.example.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfProtectionTests {
    private final CsrfProtectionFilter filter = new CsrfProtectionFilter();

    private MockHttpSession pageSession() throws Exception {
        var request = new MockHttpServletRequest("GET", "/owner/setup");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(request.getAttribute("csrfToken")).isNotNull();
        return (MockHttpSession) request.getSession();
    }

    private boolean passes(MockHttpServletRequest request, int status) throws Exception {
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(status);
        return called.get();
    }

    @Test
    void localhostNeedsTokenForEveryMutationAndTokensAreSessionBound() throws Exception {
        var session = pageSession();
        for (String path : new String[]{"/owner/setup", "/owner/login", "/owner/logout", "/upload",
                "/receive/token/upload", "/download/selected", "/receive-links", "/share/bundles", "/files"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("127.0.0.1");
            request.setSession(session);
            assertThat(passes(request, 403)).isFalse();
            request.addHeader("X-CSRF-Token", pageSession().getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
            assertThat(passes(request, 403)).isFalse();
            request.removeHeader("X-CSRF-Token");
            request.addHeader("X-CSRF-Token", session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
            assertThat(passes(request, 200)).isTrue();
        }
    }

    @Test
    void rejectsForeignOriginsEvenWithValidTokenAndIgnoresForwardedHeaders() throws Exception {
        var session = pageSession();
        for (String origin : new String[]{"https://evil.example", "http://localhost:8081", "null",
                "https://localhost", "http://localhost.evil.example", "http://user@localhost"}) {
            var request = new MockHttpServletRequest("DELETE", "/files");
            request.setSession(session);
            request.addHeader("X-CSRF-Token", session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
            request.addHeader("Origin", origin);
            request.addHeader("X-Forwarded-Host", "evil.example");
            assertThat(passes(request, 403)).isFalse();
        }
    }

    @Test
    void acceptsSameOriginFormAndRejectsCrossSiteMetadataAndForeignReferrer() throws Exception {
        var session = pageSession();
        var request = new MockHttpServletRequest("POST", "/owner/setup");
        request.setSession(session);
        request.setContentType("application/x-www-form-urlencoded;charset=UTF-8");
        request.addParameter("_csrf", (String) session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
        request.addHeader("Origin", "http://localhost");
        assertThat(passes(request, 200)).isTrue();
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(passes(request, 403)).isFalse();
        request.removeHeader("Sec-Fetch-Site");
        request.removeHeader("Origin");
        request.addHeader("Referer", "http://evil.example/page");
        assertThat(passes(request, 403)).isFalse();
        request.removeHeader("Referer");
        request.addHeader("Referer", "http://localhost/page");
        assertThat(passes(request, 200)).isTrue();
    }

    @Test
    void multipartRequiresHeaderAndSafeDownloadsNeedNoSession() throws Exception {
        var session = pageSession();
        var request = new MockHttpServletRequest("POST", "/receive/token/upload");
        request.setSession(session);
        request.setContentType("multipart/form-data; boundary=test");
        request.addParameter("_csrf", (String) session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
        assertThat(passes(request, 403)).isFalse();
        request.addHeader("X-CSRF-Token", session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN));
        assertThat(passes(request, 200)).isTrue();
        var download = new MockHttpServletRequest("GET", "/share/token");
        assertThat(passes(download, 200)).isTrue();
        assertThat(download.getSession(false)).isNull();
    }
}
