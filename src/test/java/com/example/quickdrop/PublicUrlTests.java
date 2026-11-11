package com.example.quickdrop;

import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.network.PublicUrlService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicUrlTests {
    @Test
    void automaticAddressesMatchNativeTlsAndLocalSetupPort() {
        var network = mock(NetworkAddressService.class);
        when(network.findLocalIpv4Address()).thenReturn("192.168.1.20");
        var http = new PublicUrlService(network, "", false, 8080);
        assertThat(http.currentBaseUrl()).isEqualTo("http://192.168.1.20:8080");
        var https = new PublicUrlService(network, "", true, 8443);
        assertThat(https.currentBaseUrl()).isEqualTo("https://192.168.1.20:8443");
        assertThat(https.localBaseUrl()).isEqualTo("https://localhost:8443");
        assertThat(new PublicUrlService(network, "", true, 443).localBaseUrl()).isEqualTo("https://localhost");
        when(network.findLocalIpv4Address()).thenReturn("127.0.0.1");
        assertThat(https.currentBaseUrl()).isNull();
    }

    @Test
    void publicAddressOverridesNetworkDetectionAndNormalizesRootAndDefaultPort() {
        var network = mock(NetworkAddressService.class);
        assertThat(new PublicUrlService(network, "https://QuickDrop.Example:443/", true, 8443).currentBaseUrl())
                .isEqualTo("https://quickdrop.example");
        assertThat(new PublicUrlService(network, "https://[::1]:8443/", true, 8443).currentBaseUrl())
                .isEqualTo("https://[::1]:8443");
        verifyNoInteractions(network);
    }

    @Test
    void unsafeAndMisleadingPublicAddressesFailAtStartup() {
        var network = mock(NetworkAddressService.class);
        for (String url : new String[]{"http://host:8443", "//host", "javascript:alert(1)", "https://user:secret@host",
                "https://host/path", "https://host/?secret=x", "https://host/#fragment", "https://host:0",
                "https://host:65536", "https://host:", "https:///missing-host"}) {
            assertThatThrownBy(() -> new PublicUrlService(network, url, true, 8443))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new PublicUrlService(network, "https://host", false, 8080))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
