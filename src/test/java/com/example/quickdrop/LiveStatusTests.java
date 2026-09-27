package com.example.quickdrop;

import com.example.quickdrop.controller.OwnerAccessFilter;
import com.example.quickdrop.controller.OwnerAccessService;
import com.example.quickdrop.controller.OwnerPinService;
import com.example.quickdrop.controller.StatusController;
import com.example.quickdrop.network.NetworkAddressService;
import com.example.quickdrop.receive.ReceiveLink;
import com.example.quickdrop.receive.ReceiveLinkRepository;
import com.example.quickdrop.receive.ReceiveLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class LiveStatusTests {

    private static final String TOKEN = "current-receiving-token";
    private final JsonMapper json = JsonMapper.builder().build();
    private ReceiveLinkService receiveLinkService;
    private NetworkAddressService networkAddressService;
    private OwnerAccessService ownerAccessService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        receiveLinkService = mock(ReceiveLinkService.class);
        networkAddressService = mock(NetworkAddressService.class);
        when(networkAddressService.findLocalIpv4Address()).thenReturn("192.168.1.10");
        when(receiveLinkService.findCurrentActive()).thenReturn(Optional.empty());
        OwnerPinService pinService = mock(OwnerPinService.class);
        when(pinService.isConfigured()).thenReturn(true);
        when(pinService.authenticationVersion()).thenReturn("test-pin-version");
        ownerAccessService = new OwnerAccessService(pinService);
        mvc = statusMvc(receiveLinkService);
    }

    @Test
    void ownerStatusIncludesActiveReceivingAndServerTime() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(receiveLinkService.findCurrentActive()).thenReturn(Optional.of(
                new ReceiveLinkService.LinkDetails(TOKEN, expiresAt)
        ));
        Instant before = Instant.now();

        JsonNode body = successfulJson(localGet("/api/status"));

        assertThat(Instant.parse(body.get("serverTime").asString()))
                .isBetween(before, Instant.now());
        assertThat(body.get("shareBaseUrl").asString())
                .isEqualTo("http://192.168.1.10:8181");
        JsonNode receiving = body.get("receiving");
        assertThat(receiving.get("active").asBoolean()).isTrue();
        assertThat(receiving.get("receivePath").asString())
                .isEqualTo("/receive/" + TOKEN);
        assertThat(Instant.parse(receiving.get("expiresAt").asString()))
                .isEqualTo(expiresAt);
        assertThat(receiving.get("expirationLabel").asString()).startsWith("Expires ");
        verify(receiveLinkService).findCurrentActive();
        verifyNoMoreInteractions(receiveLinkService);
    }

    @Test
    void ownerStatusHidesReceivingDetailsWhenOff() throws Exception {
        JsonNode receiving = successfulJson(localGet("/api/status")).get("receiving");

        assertReceivingOff(receiving);
    }

    @Test
    void ownerStatusUsesTheNewNetworkAddressOnEveryRequest() throws Exception {
        when(networkAddressService.findLocalIpv4Address())
                .thenReturn("192.168.1.10", "10.0.0.24", "127.0.0.1", "192.168.5.30");

        assertThat(successfulJson(localGet("/api/status")).get("shareBaseUrl").asString())
                .isEqualTo("http://192.168.1.10:8181");
        assertThat(successfulJson(localGet("/api/status")).get("shareBaseUrl").asString())
                .isEqualTo("http://10.0.0.24:8181");
        assertThat(successfulJson(localGet("/api/status")).get("shareBaseUrl").isNull())
                .isTrue();
        assertThat(successfulJson(localGet("/api/status")).get("shareBaseUrl").asString())
                .isEqualTo("http://192.168.5.30:8181");
        verify(networkAddressService, times(4)).findLocalIpv4Address();
    }

    @Test
    void loopbackOrAbsentNetworkHasNoShareableAddress() throws Exception {
        for (String address : new String[]{"127.0.0.1", "127.1.2.3", "0.0.0.0", "", null}) {
            when(networkAddressService.findLocalIpv4Address()).thenReturn(address);

            assertThat(successfulJson(localGet("/api/status")).get("shareBaseUrl").isNull())
                    .isTrue();
        }
    }

    @Test
    void expiredReceivingIsInactiveBeforeScheduledCleanupRuns() throws Exception {
        ReceiveLinkRepository repository = mock(ReceiveLinkRepository.class);
        when(repository.findAllByOrderByExpiresAtDesc()).thenAnswer(invocation ->
                new ArrayList<>(List.of(new ReceiveLink(TOKEN, Instant.now().minusSeconds(1))))
        );
        mvc = statusMvc(new ReceiveLinkService(repository));

        assertReceivingOff(successfulJson(localGet("/api/status")).get("receiving"));
        assertInactiveSender(successfulJson(remoteGet("/receive/" + TOKEN + "/status")));
        verify(repository, times(2)).deleteAllByExpiresAtBefore(any(Instant.class));
    }

    @Test
    void stoppingReceivingIsVisibleToOwnerAndSenderOnTheNextPoll() throws Exception {
        ReceiveLinkRepository repository = mock(ReceiveLinkRepository.class);
        List<ReceiveLink> links = new ArrayList<>(List.of(
                new ReceiveLink(TOKEN, Instant.now().plusSeconds(3600))
        ));
        when(repository.findAllByOrderByExpiresAtDesc())
                .thenAnswer(invocation -> new ArrayList<>(links));
        doAnswer(invocation -> {
            links.clear();
            return null;
        }).when(repository).deleteAllInBatch();
        ReceiveLinkService service = new ReceiveLinkService(repository);
        mvc = statusMvc(service);

        assertThat(successfulJson(localGet("/api/status"))
                .get("receiving").get("active").asBoolean()).isTrue();
        assertThat(successfulJson(remoteGet("/receive/" + TOKEN + "/status"))
                .get("active").asBoolean()).isTrue();

        assertThat(service.stop()).isTrue();

        assertReceivingOff(successfulJson(localGet("/api/status")).get("receiving"));
        assertInactiveSender(successfulJson(remoteGet("/receive/" + TOKEN + "/status")));
    }

    @Test
    void publicSenderStatusContainsOnlyTheRequestedSessionsState() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(3600);
        when(receiveLinkService.findActive(TOKEN)).thenReturn(Optional.of(
                new ReceiveLinkService.LinkDetails(TOKEN, expiresAt)
        ));

        JsonNode body = successfulJson(remoteGet("/receive/" + TOKEN + "/status"));

        assertThat(body.size()).isEqualTo(3);
        assertThat(body.has("serverTime")).isTrue();
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(Instant.parse(body.get("expiresAt").asString())).isEqualTo(expiresAt);
        verify(receiveLinkService).findActive(TOKEN);
        verify(receiveLinkService, never()).findCurrentActive();
        verifyNoMoreInteractions(receiveLinkService);
        verifyNoInteractions(networkAddressService);
    }

    @Test
    void invalidOrReplacedSenderTokenDoesNotRevealTheCurrentSession() throws Exception {
        ReceiveLinkRepository repository = mock(ReceiveLinkRepository.class);
        when(repository.findAllByOrderByExpiresAtDesc()).thenAnswer(invocation ->
                new ArrayList<>(List.of(new ReceiveLink(
                        "private-replacement-token", Instant.now().plusSeconds(3600)
                )))
        );
        mvc = statusMvc(new ReceiveLinkService(repository));

        assertInactiveSender(successfulJson(remoteGet("/receive/invalid-token/status")));
        assertInactiveSender(successfulJson(remoteGet("/receive/" + TOKEN + "/status")));
        verifyNoInteractions(networkAddressService);
    }

    @Test
    void remoteOwnerStatusRequiresAuthenticationAndReturnsJson401() throws Exception {
        MockHttpServletResponse response = mvc.perform(remoteGet("/api/status"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getRedirectedUrl()).isNull();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("Owner PIN required");
        verifyNoInteractions(receiveLinkService, networkAddressService);
    }

    @Test
    void authenticatedRemoteOwnerCanReadStatus() throws Exception {
        JsonNode body = successfulJson(remoteGet("/api/status").with(request -> {
            ownerAccessService.authenticate(request);
            return request;
        }));

        assertThat(body.get("shareBaseUrl").asString()).isEqualTo("http://192.168.1.10:8181");
    }

    @Test
    void publicHostnameCannotUseLoopbackToReadOwnerStatus() throws Exception {
        MockHttpServletResponse response = mvc.perform(localGet("/api/status").with(request -> {
            request.setServerName("quickdrop.example.com");
            return request;
        })).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(receiveLinkService, networkAddressService);
    }

    @Test
    void pathParametersCannotBypassStatusAuthentication() throws Exception {
        MockHttpServletResponse response = mvc.perform(remoteGet(
                "/api/status;jsessionid=untrusted"
        )).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(receiveLinkService, networkAddressService);
    }

    private MockMvc statusMvc(ReceiveLinkService service) {
        return MockMvcBuilders.standaloneSetup(new StatusController(
                        service, networkAddressService, 8181
                ))
                .addFilters(new OwnerAccessFilter(ownerAccessService))
                .build();
    }

    private JsonNode successfulJson(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        return json.readTree(response.getContentAsString());
    }

    private void assertReceivingOff(JsonNode receiving) {
        assertThat(receiving.get("active").asBoolean()).isFalse();
        assertThat(receiving.get("receivePath").isNull()).isTrue();
        assertThat(receiving.get("expiresAt").isNull()).isTrue();
        assertThat(receiving.get("expirationLabel").isNull()).isTrue();
    }

    private void assertInactiveSender(JsonNode body) {
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.has("serverTime")).isTrue();
        assertThat(body.get("active").asBoolean()).isFalse();
        assertThat(body.get("expiresAt").isNull()).isTrue();
    }

    private MockHttpServletRequestBuilder localGet(String path) {
        return get(path).with(request -> {
            request.setRemoteAddr("127.0.0.1");
            request.setServerName("localhost");
            return request;
        });
    }

    private MockHttpServletRequestBuilder remoteGet(String path) {
        return get(path).with(request -> {
            request.setRemoteAddr("192.168.1.25");
            request.setServerName("192.168.1.10");
            return request;
        });
    }
}
