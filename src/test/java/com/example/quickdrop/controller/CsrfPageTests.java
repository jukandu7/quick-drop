package com.example.quickdrop.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:csrf-pages")
class CsrfPageTests {
    @Autowired WebApplicationContext context;
    @Autowired CsrfProtectionFilter csrf;
    @Autowired OwnerAccessFilter access;
    @Autowired HomeController home;
    @MockitoBean OwnerPinService pins;
    @TempDir Path uploads;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(pins.isConfigured()).thenReturn(true);
        when(pins.authenticationVersion()).thenReturn("test-version");
        ReflectionTestUtils.setField(home, "uploadDirectory", uploads);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(access, csrf).build();
    }

    @Test
    void everyInteractivePageRendersSessionTokenAndPinChangeFormRequiresCurrentPin() throws Exception {
        for (String path : new String[]{"/", "/owner/setup", "/owner/login", "/receive/unknown"}) {
            var result = mvc.perform(get(path).with(request -> {
                if (path.equals("/owner/login")) request.setRemoteAddr("192.0.2.10");
                return request;
            })).andReturn();
            var token = (String) result.getRequest().getSession().getAttribute(CsrfProtectionFilter.SESSION_TOKEN);
            assertThat(token).hasSize(43);
            var html = result.getResponse().getContentAsString();
            assertThat(html).contains("content=\"" + token + "\"");
            if (!path.startsWith("/receive/")) {
                assertThat(html).contains("value=\"" + token + "\"");
            }
            if (path.equals("/owner/setup")) assertThat(html).contains("name=\"currentPin\"");
        }
    }

    @Test
    void forgedLocalPinChangeIsBlockedAndRealFormSubmissionSucceeds() throws Exception {
        var page = mvc.perform(get("/owner/setup")).andReturn();
        var session = (MockHttpSession) page.getRequest().getSession();
        var token = (String) session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN);
        mvc.perform(post("/owner/setup").session(session)
                .contentType("application/x-www-form-urlencoded")
                .param("pin", "9876").param("confirmPin", "9876").param("currentPin", "1234"))
                .andExpect(status().isForbidden());
        verify(pins, never()).updatePin(anyString(), anyString());
        mvc.perform(post("/owner/setup").session(session)
                .contentType("application/x-www-form-urlencoded")
                .header("Origin", "http://localhost").param("_csrf", token)
                .param("pin", "9876").param("confirmPin", "9876").param("currentPin", "1234"))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/"));
        verify(pins).updatePin("9876", "1234");
    }

    @Test
    void realUploadAndDownloadHandlersRemainReachableWithTokens() throws Exception {
        var page = mvc.perform(get("/owner/setup")).andReturn();
        var session = (MockHttpSession) page.getRequest().getSession();
        var token = (String) session.getAttribute(CsrfProtectionFilter.SESSION_TOKEN);
        mvc.perform(multipart("/upload").file("file", new byte[0]).session(session)
                        .header("X-CSRF-Token", token).header("Origin", "http://localhost"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please select a file."));
        mvc.perform(multipart("/receive/unknown/upload").file("file", new byte[0]).session(session)
                        .header("X-CSRF-Token", token).header("Origin", "http://localhost"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value("This receive link has expired or is invalid."));
        mvc.perform(post("/download/selected").session(session)
                        .contentType("application/x-www-form-urlencoded").param("_csrf", token))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Select at least one file to download."));
    }
}
