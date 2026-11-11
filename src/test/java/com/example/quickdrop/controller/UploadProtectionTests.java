package com.example.quickdrop.controller;

import com.example.quickdrop.receive.ReceiveLinkService;
import com.example.quickdrop.share.ShareLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import java.nio.file.*;
import java.util.Optional;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UploadProtectionTests {
    @TempDir Path root;
    UploadProtection guard;
    ReceiveLinkService links = mock(ReceiveLinkService.class);

    @BeforeEach void setup() throws Exception {
        ShareLinkService storage = mock(ShareLinkService.class);
        when(storage.storageDirectory()).thenReturn(root);
        guard = new UploadProtection(links, storage, DataSize.ofBytes(1000), DataSize.ofBytes(900),
                DataSize.ofBytes(2200), DataSize.ofBytes(0), 2);
    }

    MockHttpServletRequest request(String path, String ip, int size) {
        var request = spy(new MockHttpServletRequest("POST", path));
        request.setRemoteAddr(ip);
        request.setContentType("multipart/form-data; boundary=test");
        when(request.getContentLengthLong()).thenReturn((long) size);
        // Admission must never read or parse the body itself.
        try {
            doThrow(new AssertionError("body read during admission")).when(request).getInputStream();
            doThrow(new AssertionError("parts parsed during admission")).when(request).getParts();
        } catch (Exception e) { throw new RuntimeException(e); }
        return request;
    }

    void rejected(String path, int size, int expected) throws IOException, jakarta.servlet.ServletException {
        var response = new MockHttpServletResponse();
        guard.doFilter(request(path, "a", size), response, (req, res) -> fail("accepted rejected upload"));
        assertThat(response.getStatus()).isEqualTo(expected);
    }

    @Test void inactiveAndRetiredLinksRejectBeforeParsing() throws Exception {
        when(links.findActive("missing")).thenReturn(Optional.empty());
        rejected("/receive/missing/upload", 100, 410);
        rejected("/send/upload", 100, 410);
        rejected("/send/upload;session=test", 100, 410);
    }
    @Test void arbitraryRoutesCannotParseMultipart() throws Exception {
        rejected("/qr", 100, 415);
        rejected("/not-found", 100, 415);
    }
    @Test void oversizedBodyRejectedFromHeaders() throws Exception { rejected("/upload", 1001, 413); }

    @Test void perClientLimitAndReleaseOnFailure() throws Exception {
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> guard.doFilter(request("/upload", "a", 100), response, (req, res) -> {
            rejected("/upload", 100, 429);
            throw new IOException("disconnected");
        })).isInstanceOf(IOException.class);
        guard.doFilter(request("/upload", "a", 100), response, (req, res) -> res.setContentType("accepted"));
        assertThat(response.getContentType()).isEqualTo("accepted");
    }

    @Test void globalLimitAcrossDifferentClients() throws Exception {
        guard.doFilter(request("/upload", "a", 100), new MockHttpServletResponse(), (a, b) ->
            guard.doFilter(request("/upload", "b", 100), new MockHttpServletResponse(), (c, d) -> {
                var denied = new MockHttpServletResponse();
                guard.doFilter(request("/upload", "c", 100), denied, (e, f) -> fail("third admitted"));
                assertThat(denied.getStatus()).isEqualTo(429);
                assertThat(denied.getHeader("Retry-After")).isEqualTo("3");
            }));
    }

    @Test void chunkedRequestsReserveFullSizeAndStagingCopy() throws Exception {
        guard.doFilter(request("/upload", "a", -1), new MockHttpServletResponse(), (a, b) -> {
            var denied = new MockHttpServletResponse();
            guard.doFilter(request("/upload", "b", 101), denied, (c, d) -> fail("budget exceeded"));
            assertThat(denied.getStatus()).isEqualTo(429);
        });
    }

    @Test void abandonedSpoolConsumesBudget() throws Exception {
        Files.write(root.resolve(".incoming/abandoned.tmp"), new byte[2100]);
        rejected("/upload", 100, 507);
    }

    @Test void servletLimitsShareTheGuardConfiguration() {
        var config = guard.multipartConfigElement();
        assertThat(config.getMaxRequestSize()).isEqualTo(1000);
        assertThat(config.getMaxFileSize()).isEqualTo(900);
        assertThat(config.getFileSizeThreshold()).isZero();
        assertThat(Path.of(config.getLocation())).isEqualTo(root.resolve(".incoming"));
    }
}
