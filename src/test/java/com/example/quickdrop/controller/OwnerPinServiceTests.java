package com.example.quickdrop.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerPinServiceTests {

    @TempDir
    Path temporaryDirectory;

    private Path pinFile;
    private OwnerPinService pinService;

    @BeforeEach
    void setUp() throws Exception {
        pinFile = temporaryDirectory.resolve("owner-auth.properties");
        pinService = new OwnerPinService(pinFile);
        pinService.load();
    }

    @Test
    void initialPinIsPersistedAndCanBeReloaded() throws Exception {
        assertThat(pinService.isConfigured()).isFalse();

        pinService.save("0123");

        assertThat(pinService.isConfigured()).isTrue();
        assertThat(pinService.matches("0123")).isTrue();
        assertThat(pinService.authenticationVersion()).isNotEmpty();
        OwnerPinService reloaded = new OwnerPinService(pinFile);
        reloaded.load();
        assertThat(reloaded.matches("0123")).isTrue();
        assertThat(reloaded.authenticationVersion())
                .isEqualTo(pinService.authenticationVersion());
    }

    @Test
    void samePinDoesNotRewriteFileRotateAuthenticationOrSignOutRemoteDevices() throws Exception {
        pinService.save("0123");
        byte[] originalFile = Files.readAllBytes(pinFile);
        String originalVersion = pinService.authenticationVersion();
        OwnerAccessService accessService = new OwnerAccessService(pinService);
        MockHttpServletRequest remoteRequest = remoteRequest();
        accessService.authenticate(remoteRequest);
        assertThat(accessService.isRemoteAuthenticated(remoteRequest)).isTrue();

        assertThatThrownBy(() -> pinService.save("0123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Your new PIN is the same as your current PIN. Choose a different PIN.");

        assertThat(Files.readAllBytes(pinFile)).isEqualTo(originalFile);
        assertThat(pinService.authenticationVersion()).isEqualTo(originalVersion);
        assertThat(pinService.matches("0123")).isTrue();
        assertThat(accessService.isRemoteAuthenticated(remoteRequest)).isTrue();
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files.toList()).containsExactly(pinFile);
        }
    }

    @Test
    void changedPinPersistsAndInvalidatesPreviouslyAuthenticatedRemoteSessions() throws Exception {
        pinService.save("0123");
        byte[] originalFile = Files.readAllBytes(pinFile);
        String originalVersion = pinService.authenticationVersion();
        OwnerAccessService accessService = new OwnerAccessService(pinService);
        MockHttpServletRequest remoteRequest = remoteRequest();
        accessService.authenticate(remoteRequest);

        pinService.save("9876");

        assertThat(Files.readAllBytes(pinFile)).isNotEqualTo(originalFile);
        assertThat(pinService.authenticationVersion()).isNotEqualTo(originalVersion);
        assertThat(pinService.matches("9876")).isTrue();
        assertThat(pinService.matches("0123")).isFalse();
        assertThat(accessService.isRemoteAuthenticated(remoteRequest)).isFalse();
        OwnerPinService reloaded = new OwnerPinService(pinFile);
        reloaded.load();
        assertThat(reloaded.matches("9876")).isTrue();
        assertThat(reloaded.matches("0123")).isFalse();
        assertThat(reloaded.authenticationVersion()).isEqualTo(pinService.authenticationVersion());
        accessService.authenticate(remoteRequest);
        assertThat(accessService.isRemoteAuthenticated(remoteRequest)).isTrue();
    }

    @Test
    void invalidPinDoesNotMutateExistingConfiguration() throws Exception {
        pinService.save("0123");
        byte[] originalFile = Files.readAllBytes(pinFile);
        String originalVersion = pinService.authenticationVersion();

        for (String invalid : new String[]{null, "", "123", "12345", "abcd", " 1234", "1234 "}) {
            assertThatThrownBy(() -> pinService.save(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Use exactly 4 numerical digits for the owner PIN.");
            assertThat(Files.readAllBytes(pinFile)).isEqualTo(originalFile);
            assertThat(pinService.authenticationVersion()).isEqualTo(originalVersion);
        }
        assertThat(pinService.matches("0123")).isTrue();
    }

    private MockHttpServletRequest remoteRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.25");
        request.setServerName("192.168.1.10");
        return request;
    }
}
