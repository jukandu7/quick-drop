package com.example.quickdrop;

import com.example.quickdrop.controller.OwnerAccessFilter;
import com.example.quickdrop.controller.OwnerAccessService;
import com.example.quickdrop.controller.OwnerPinService;
import com.example.quickdrop.events.FileEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:file-event-access")
class FileEventAccessTests {
    @Autowired WebApplicationContext context;
    @Autowired OwnerAccessFilter filter;
    @Autowired OwnerAccessService access;
    @Autowired FileEventService events;
    @MockitoBean OwnerPinService pins;

    @Test void realControllerStopsSendingFilenamesAfterLogout() throws Exception {
        when(pins.isConfigured()).thenReturn(true);
        when(pins.authenticationVersion()).thenReturn("test-version");
        var mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filter).build();
        var result = mvc.perform(get("/events/files").with(request -> {
            request.setRemoteAddr("192.0.2.1");
            access.authenticate(request);
            return request;
        })).andReturn();
        assertThat(result.getRequest().isAsyncStarted()).isTrue();
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        events.fileUploaded("visible.txt");
        assertThat(result.getResponse().getContentAsString()).contains("visible.txt");
        access.signOut(result.getRequest());
        events.fileReceived("secret.txt");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret.txt");
        // Completion was dispatched; a new stream must pass authentication again.
        assertThat(result.getAsyncResult(1000)).isNull();
        var denied = mvc.perform(get("/events/files").with(request -> {
            request.setRemoteAddr("192.0.2.1");
            return request;
        })).andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(401);
    }
}
