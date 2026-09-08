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
