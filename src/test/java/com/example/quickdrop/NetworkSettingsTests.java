package com.example.quickdrop;

import com.example.quickdrop.controller.CsrfProtectionFilter;
import com.example.quickdrop.controller.OwnerAccessFilter;
import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.network.NetworkAddressService.AddressChoice;
import com.example.quickdrop.network.PublicUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:network-settings")
class NetworkSettingsTests {
    @Autowired WebApplicationContext context;
    @Autowired OwnerAccessFilter owner;
    @Autowired CsrfProtectionFilter csrf;
    @Autowired PublicUrlService urls;
    @MockitoSpyBean NetworkAddressService network;
    MockMvc mvc;
    static final List<AddressChoice> CHOICES = List.of(
            new AddressChoice("192.168.1.20", "wifi", "Wi-Fi", false, true),
            new AddressChoice("100.64.1.2", "vpn", "VPN", true, false));

    @BeforeEach void setup() {
        doReturn(CHOICES).when(network).availableAddresses();
        network.selectAddress("");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(owner, csrf).build();
    }

    @Test void selectedAdapterControlsSharedUrlAndDisappearanceDoesNotFallback() {
        network.selectAddress("100.64.1.2");
        assertThat(urls.currentBaseUrl()).isEqualTo("http://100.64.1.2:8080");
        doReturn(List.of(CHOICES.getFirst())).when(network).availableAddresses();
        assertThat(urls.currentBaseUrl()).isNull();
        network.selectAddress("");
        assertThat(urls.currentBaseUrl()).isEqualTo("http://192.168.1.20:8080");
    }

    @Test void unavailableOrArbitraryAddressesAreRejectedWithoutChangingSelection() {
        network.selectAddress("192.168.1.20");
        for (String invalid : new String[]{"evil.example", "127.0.0.1", "192.168.99.1", "https://evil.example"}) {
            assertThatThrownBy(() -> network.selectAddress(invalid)).isInstanceOf(IllegalArgumentException.class);
            assertThat(network.selectedAddress()).isEqualTo("192.168.1.20");
        }
    }

    @Test void rendersAdapterChoicesAndAcceptsProtectedFormSubmission() throws Exception {
        var page = mvc.perform(get("/owner/network")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        String html = page.getResponse().getContentAsString();
        assertThat(html).contains("Wi-Fi", "100.64.1.2", "VPN", "name=\"_csrf\"");
        var matcher = Pattern.compile("id=\"csrf-token\"[^>]*content=\"([^\"]+)\"").matcher(html);
        assertThat(matcher.find()).isTrue();
        var result = mvc.perform(post("/owner/network")
                .session((MockHttpSession) page.getRequest().getSession())
                .contentType("application/x-www-form-urlencoded")
                .param("_csrf", matcher.group(1)).param("address", "100.64.1.2")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("http://100.64.1.2:8080");
        assertThat(network.selectedAddress()).isEqualTo("100.64.1.2");
    }

    @Test void remoteGuestsAndForgedRequestsCannotChangeNetwork() throws Exception {
        var denied = mvc.perform(get("/owner/network").with(request -> {
            request.setRemoteAddr("192.0.2.1"); return request;
        })).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(401);
        var forged = mvc.perform(post("/owner/network").param("address", "100.64.1.2")).andReturn();
        assertThat(forged.getResponse().getStatus()).isEqualTo(403);
        assertThat(network.selectedAddress()).isEmpty();
    }

    @Test void fixedPublicUrlKeepsPrecedenceOverAdapterChoice() {
        var fixed = new PublicUrlService(network, "https://quickdrop.example:8443", true, 8443);
        network.selectAddress("100.64.1.2");
        assertThat(fixed.hasPublicOverride()).isTrue();
        assertThat(fixed.currentBaseUrl()).isEqualTo("https://quickdrop.example:8443");
    }

    @Test void unavailableSelectionIsRenderedClearly() throws Exception {
        network.selectAddress("100.64.1.2");
        doReturn(List.of()).when(network).availableAddresses();
        var page = mvc.perform(get("/owner/network")).andReturn();
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getResponse().getContentAsString()).contains("No available address", "100.64.1.2 — unavailable");
    }
}
