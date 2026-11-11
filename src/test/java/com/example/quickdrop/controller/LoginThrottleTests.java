package com.example.quickdrop.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LoginThrottleTests {
    private final AtomicLong now = new AtomicLong();
    private final LoginThrottle throttle = new LoginThrottle(now::get);

    private void burst(String address, int count) {
        for (int i = 0; i < count; i++) {
            assertThat(throttle.acquire(address)).isZero();
            throttle.complete();
        }
    }

    @Test
    void cooldownEscalatesCapsAndResetsAfterIdle() {
        long delay = 60;
        for (int i = 0; i < 7; i++) {
            burst("device", 5);
            assertThat(throttle.acquire("device")).isEqualTo(delay);
            now.addAndGet(Duration.ofSeconds(delay).toNanos());
            delay = Math.min(delay * 2, 900);
        }
        now.addAndGet(Duration.ofMinutes(30).toNanos());
        burst("device", 5);
        assertThat(throttle.acquire("device")).isEqualTo(60);
    }

    @Test
    void blockedRequestsDoNotExtendCooldownAndOtherDevicesCanLogin() {
        burst("device", 5);
        now.addAndGet(Duration.ofSeconds(59).toNanos());
        assertThat(throttle.acquire("device")).isEqualTo(1);
        burst("other", 1);
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        burst("device", 1);
    }

    @Test
    void globalLimitAppliesAcrossAddressesAndExpiresAtBoundary() {
        for (int i = 0; i < 30; i++) {
            burst("device-" + i, 1);
        }
        assertThat(throttle.acquire("new-device")).isEqualTo(60);
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        burst("new-device", 1);
    }

    @Test
    void concurrentRequestsCannotExceedTwoReservations() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .<java.util.concurrent.Callable<Long>>mapToObj(i -> () -> throttle.acquire("device-" + i))
                    .toList();
            int admitted = 0;
            for (var result : executor.invokeAll(tasks)) {
                if (result.get() == 0) admitted++;
            }
            assertThat(admitted).isEqualTo(2);
        }
        throttle.complete();
        throttle.complete();
        burst("next", 1);
    }

    @Test
    void loginRejectsMalformedPinsAndSpoofedHeadersCannotBypassCooldown() throws Exception {
        OwnerPinService pins = mock(OwnerPinService.class);
        OwnerAccessService access = mock(OwnerAccessService.class);
        when(pins.isConfigured()).thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new OwnerController(access, pins, throttle)).build();
        for (String pin : new String[]{"", "123", "12345", "abcd", "１２３４"}) {
            mvc.perform(post("/owner/login").param("pin", pin))
                    .andExpect(status().isUnauthorized());
        }
        verify(access, never()).matchesPin(anyString());
        mvc.perform(post("/owner/login").param("pin", "1234")
                        .header("X-Forwarded-For", "192.0.2.99"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(model().attributeExists("error"));
        verify(access, never()).authenticate(any());
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        when(access.matchesPin("1234")).thenReturn(true);
        mvc.perform(post("/owner/login").param("pin", "1234"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        verify(access).authenticate(any());
    }

    @Test
    void failedVerificationReleasesReservationEvenOnException() {
        OwnerPinService pins = mock(OwnerPinService.class);
        OwnerAccessService access = mock(OwnerAccessService.class);
        when(pins.isConfigured()).thenReturn(true);
        when(access.matchesPin("1234")).thenThrow(new IllegalStateException("test"));
        OwnerController controller = new OwnerController(access, pins, throttle);
        for (int i = 0; i < 3; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.login("1234",
                    new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(),
                    new org.springframework.ui.ExtendedModelMap()))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(access, times(3)).matchesPin("1234");
    }
}
