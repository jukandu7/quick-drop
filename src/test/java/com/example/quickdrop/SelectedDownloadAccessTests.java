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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectedDownloadAccessTests {

    private OwnerAccessService accessService;
    private OwnerAccessFilter filter;

    @BeforeEach
    void setUp() {
        OwnerPinService pinService = mock(OwnerPinService.class);
        when(pinService.isConfigured()).thenReturn(true);
        when(pinService.authenticationVersion()).thenReturn("test-pin-version");
        accessService = new OwnerAccessService(pinService);
        filter = new OwnerAccessFilter(accessService);
    }

    @Test
    void otherDevicesCannotDownloadSelectedFilesWithoutOwnerAccess()
            throws Exception {
        MockHttpServletRequest request = remoteRequest("/download/selected");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Owner PIN required");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void authenticatedOwnerCanDownloadSelectedFilesFromAnotherDevice()
            throws Exception {
        MockHttpServletRequest request = remoteRequest("/download/selected");
        accessService.authenticate(request);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void localhostCanDownloadSelectedFilesWithoutPin() throws Exception {
        MockHttpServletRequest request = remoteRequest("/download/selected");
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void sessionPathParameterCannotBypassDownloadProtection() throws Exception {
        MockHttpServletRequest request = remoteRequest(
                "/download/selected;jsessionid=untrusted"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    private MockHttpServletRequest remoteRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("192.168.1.25");
        request.setServerName("192.168.1.10");
        return request;
    }
}
