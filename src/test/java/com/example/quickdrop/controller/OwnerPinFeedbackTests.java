package com.example.quickdrop.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.FlashMap;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class OwnerPinFeedbackTests {

    private static final String SAME_PIN_MESSAGE =
            "Your new PIN is the same as your current PIN. Choose a different PIN.";

    private OwnerPinService pinService;
    private OwnerAccessService accessService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        pinService = mock(OwnerPinService.class);
        accessService = mock(OwnerAccessService.class);
        when(accessService.isLocalRequest(any())).thenReturn(true);
        when(pinService.isConfigured()).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new OwnerController(accessService, pinService)).build();
    }

    @Test
    void changedPinRedirectsWithOneTimeSuccessNotice() throws Exception {
        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "9876").param("confirmPin", "9876")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(result.getFlashMap().get("pinNotice")).isEqualTo(
                "Owner PIN changed successfully. Other devices will need to unlock with the new PIN.");
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("9876");
        verify(pinService).save("9876");
    }

    @Test
    void initialSetupUsesCreatedNoticeRatherThanChangedNotice() throws Exception {
        when(pinService.isConfigured()).thenReturn(false);

        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "0123").param("confirmPin", "0123")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(result.getFlashMap().get("pinNotice")).isEqualTo("Owner PIN created successfully.");
        verify(pinService).save("0123");
    }

    @Test
    void samePinShowsSpecificErrorWithoutSuccessNotice() throws Exception {
        doThrow(new IllegalArgumentException(SAME_PIN_MESSAGE)).when(pinService).save("0123");

        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "0123").param("confirmPin", "0123")).andReturn();

        assertSetupError(result, 400, SAME_PIN_MESSAGE);
        assertThat(result.getModelAndView().getModel()).containsEntry("configured", true);
        verify(pinService).save("0123");
    }

    @Test
    void mismatchedConfirmationDoesNotSaveOrShowSuccess() throws Exception {
        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "0123").param("confirmPin", "9876")).andReturn();

        assertSetupError(result, 400, "The two PIN entries do not match.");
        verify(pinService, never()).save(anyString());
    }

    @Test
    void invalidFormatShowsValidationErrorWithoutSuccess() throws Exception {
        String validationMessage = "Use exactly 4 numerical digits for the owner PIN.";
        doThrow(new IllegalArgumentException(validationMessage)).when(pinService).save("123");

        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "123").param("confirmPin", "123")).andReturn();

        assertSetupError(result, 400, validationMessage);
    }

    @Test
    void remoteDeviceCannotChangePinOrReceiveSuccessNotice() throws Exception {
        when(accessService.isLocalRequest(any())).thenReturn(false);

        MvcResult result = mvc.perform(post("/owner/setup")
                .param("pin", "0123").param("confirmPin", "0123")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("owner-setup");
        assertThat(result.getModelAndView().getModel()).containsEntry("localRequest", false);
        assertThat(result.getFlashMap().containsKey("pinNotice")).isFalse();
        verify(pinService, never()).save(anyString());
    }

    @Test
    void failedDiskSaveCannotProduceSuccessFlashNotice() throws Exception {
        doThrow(new IOException("Test disk write failure")).when(pinService).save("9876");
        AtomicReference<MockHttpServletRequest> request = new AtomicReference<>();

        assertThatThrownBy(() -> mvc.perform(post("/owner/setup")
                .param("pin", "9876").param("confirmPin", "9876")
                .with(value -> {
                    request.set(value);
                    return value;
                })))
                .isInstanceOf(IOException.class)
                .hasMessage("Test disk write failure");

        FlashMap outputFlash = (FlashMap) request.get()
                .getAttribute(DispatcherServlet.OUTPUT_FLASH_MAP_ATTRIBUTE);
        assertThat((Object) outputFlash).isNotNull();
        assertThat(outputFlash.containsKey("pinNotice")).isFalse();
    }

    private void assertSetupError(MvcResult result, int status, String message) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getModelAndView()).isNotNull();
        assertThat(result.getModelAndView().getViewName()).isEqualTo("owner-setup");
        assertThat(result.getModelAndView().getModel()).containsEntry("error", message);
        assertThat(result.getFlashMap().containsKey("pinNotice")).isFalse();
    }
}
