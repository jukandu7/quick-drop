package com.example.quickdrop;

import com.example.quickdrop.controller.OwnerAccessFilter;
import com.example.quickdrop.controller.OwnerAccessService;
import com.example.quickdrop.controller.OwnerPinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerAccessFilterTests {

    private OwnerAccessFilter filter;

    @BeforeEach
    void setUp() {
        OwnerPinService pinService = new OwnerPinService();
        OwnerAccessService accessService =
                new OwnerAccessService(pinService);
        filter = new OwnerAccessFilter(accessService);
    }

    @Test
    void ownerPinAcceptsExactlyFourDigits() {
        OwnerPinService pinService = new OwnerPinService();

        assertThatCode(() -> pinService.validate("4827"))
                .doesNotThrowAnyException();
    }

    @Test
    void ownerPinRejectsOtherFormats() {
        OwnerPinService pinService = new OwnerPinService();

        assertThatThrownBy(() -> pinService.validate("123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pinService.validate("12345"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pinService.validate("12a4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void localDashboardDoesNotRequirePin() throws Exception {
        MockHttpServletRequest request = request("GET", "/", "127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void publicHostnameCannotUseTheLocalhostException() throws Exception {
        MockHttpServletRequest request = request(
                "GET",
                "/",
                "127.0.0.1"
        );
        request.setServerName("quickdrop.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner/login");
    }

    @Test
    void remoteDashboardRedirectsToOwnerLogin() throws Exception {
        MockHttpServletRequest request =
                request("GET", "/", "192.168.1.25");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner/login");
    }

    @Test
    void sessionIdInUrlCannotBypassOwnerLogin() throws Exception {
        MockHttpServletRequest request = request(
                "GET",
                "/;jsessionid=example",
                "192.168.1.25"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/owner/login");
    }

    @Test
    void remoteManagementRequestIsRejected() throws Exception {
        MockHttpServletRequest request =
                request("POST", "/upload", "192.168.1.25");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Owner PIN required");
    }

    @Test
    void receiveLinksRemainPublic() throws Exception {
        MockHttpServletRequest request = request(
                "GET",
                "/receive/example-token",
                "192.168.1.25"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void shareLinksRemainPublic() throws Exception {
        MockHttpServletRequest request = request(
                "GET",
                "/share/example-token",
                "192.168.1.25"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void senderSessionPageAndUploadsRemainPublic() throws Exception {
        MockHttpServletRequest pageRequest = request(
                "GET",
                "/receive/example-token",
                "192.168.1.25"
        );
        MockFilterChain pageChain = new MockFilterChain();

        filter.doFilter(
                pageRequest,
                new MockHttpServletResponse(),
                pageChain
        );

        assertThat(pageChain.getRequest()).isSameAs(pageRequest);

        MockHttpServletRequest uploadRequest = request(
                "POST",
                "/receive/example-token/upload",
                "192.168.1.25"
        );
        MockFilterChain uploadChain = new MockFilterChain();

        filter.doFilter(
                uploadRequest,
                new MockHttpServletResponse(),
                uploadChain
        );

        assertThat(uploadChain.getRequest()).isSameAs(uploadRequest);
    }

    @Test
    void unlistedRoutesAndAssetsRequireOwnerAccess() throws Exception {
        for (String path : new String[]{"/api/future-admin", "/new-feature", "/qr",
                "/css/private-export", "/js/new-script.js", "/fonts/private", "/error",
                "/receive/token/admin", "/share/bundle/token/delete"}) {
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            filter.doFilter(request("GET", path, "192.168.1.25"), response, chain);
            assertThat(response.getStatus()).as(path).isEqualTo(401);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(chain.getRequest()).as(path).isNull();
        }
    }

    @Test
    void publicPagesAndAssetsAllowGetAndHead() throws Exception {
        for (String method : new String[]{"GET", "HEAD"}) {
            for (String path : new String[]{"/owner/login", "/owner/setup", "/send",
                    "/receive/token", "/receive/token/status", "/share/token", "/share/bundle/token",
                    "/css/theme.css", "/js/upload-batch.js", "/js/live-status.js",
                    "/js/transfer-modes.js", "/js/receive.js", "/fonts/Roboto-Variable.ttf", "/fonts/OFL.txt"}) {
                var request = request(method, path, "192.168.1.25");
                var chain = new MockFilterChain();
                filter.doFilter(request, new MockHttpServletResponse(), chain);
                assertThat(chain.getRequest()).as(method + " " + path).isSameAs(request);
            }
        }
    }

    @Test
    void guestMutationsAreExplicitlyAllowed() throws Exception {
        for (String path : new String[]{"/owner/login", "/owner/setup", "/owner/logout",
                "/send/upload", "/receive/token/upload"}) {
            var request = request("POST", path, "192.168.1.25");
            var chain = new MockFilterChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(path).isSameAs(request);
        }
    }

    @Test
    void publicPathsDoNotAllowUnlistedMethods() throws Exception {
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE"}) {
            for (String path : new String[]{"/css/theme.css", "/share/token", "/receive/token/status"}) {
                var response = new MockHttpServletResponse();
                var chain = new MockFilterChain();
                filter.doFilter(request(method, path, "192.168.1.25"), response, chain);
                assertThat(response.getStatus()).as(method + " " + path).isEqualTo(401);
                assertThat(chain.getRequest()).isNull();
            }
        }
    }

    @Test
    void contextAndPathParametersDoNotBypassDefaultProtection() throws Exception {
        var request = request("GET", "/quickdrop/api/new;jsessionid=test", "192.168.1.25");
        request.setContextPath("/quickdrop");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);

        request = request("GET", "/quickdrop/owner/login;jsessionid=test", "192.168.1.25");
        request.setContextPath("/quickdrop");
        request.setServletPath("/owner/login;jsessionid=test");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void authenticatedOwnersAndLocalhostCanUseNewRoutes() throws Exception {
        var pins = org.mockito.Mockito.mock(OwnerPinService.class);
        org.mockito.Mockito.when(pins.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(pins.authenticationVersion()).thenReturn("version");
        var access = new OwnerAccessService(pins);
        var ownerFilter = new OwnerAccessFilter(access);
        var remote = request("GET", "/api/new", "192.168.1.25");
        access.authenticate(remote);
        var remoteChain = new MockFilterChain();
        ownerFilter.doFilter(remote, new MockHttpServletResponse(), remoteChain);
        assertThat(remoteChain.getRequest()).isSameAs(remote);
        var local = request("GET", "/api/new", "127.0.0.1");
        var localChain = new MockFilterChain();
        ownerFilter.doFilter(local, new MockHttpServletResponse(), localChain);
        assertThat(localChain.getRequest()).isSameAs(local);
    }

    private MockHttpServletRequest request(
            String method,
            String path,
            String remoteAddress
    ) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, path);
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
