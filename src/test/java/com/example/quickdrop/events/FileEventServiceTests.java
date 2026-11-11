package com.example.quickdrop.events;

import com.example.quickdrop.controller.OwnerAccessService;
import com.example.quickdrop.controller.OwnerPinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FileEventServiceTests {
    OwnerPinService pins;
    OwnerAccessService access;
    FileEventService events;
    SseEmitter emitter;

    @BeforeEach void setup() {
        pins = mock(OwnerPinService.class);
        when(pins.isConfigured()).thenReturn(true);
        when(pins.authenticationVersion()).thenReturn("first-version");
        access = new OwnerAccessService(pins);
        emitter = mock(SseEmitter.class);
        events = new FileEventService() {
            @Override SseEmitter createEmitter() { return emitter; }
        };
    }

    MockHttpServletRequest remoteOwner() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        access.authenticate(request);
        return request;
    }

    @Test void logoutStopsFileEventsAndRemovesSubscription() throws Exception {
        var request = remoteOwner();
        events.subscribe(access.liveAccess(request));
        events.fileUploaded("before.txt");
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        access.signOut(request);
        events.fileReceived("private.txt");
        events.filesChangedAfterCommit();
        events.heartbeat();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, times(1)).complete();
    }

    @Test void pinChangeClosesIdleStreamAndRejectsReconnection() throws Exception {
        var request = remoteOwner();
        events.subscribe(access.liveAccess(request));
        when(pins.authenticationVersion()).thenReturn("second-version");
        events.heartbeat();
        events.fileUploaded("private.txt");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertThat(access.canAccess(request)).isFalse();
        assertThat(access.liveAccess(request).getAsBoolean()).isFalse();
    }

    @Test void reauthenticationDoesNotReviveAnOldStream() {
        var request = remoteOwner();
        BooleanSupplier oldStream = access.liveAccess(request);
        when(pins.authenticationVersion()).thenReturn("second-version");
        access.authenticate(request);
        assertThat(oldStream.getAsBoolean()).isFalse();
        assertThat(access.liveAccess(request).getAsBoolean()).isTrue();
    }

    @Test void expiredSessionClosesBeforeNextFilename() throws Exception {
        var request = remoteOwner();
        events.subscribe(access.liveAccess(request));
        request.getSession().invalidate();
        events.fileUploaded("private.txt");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test void unauthenticatedAndSpoofedLocalhostStreamsSendNothing() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("public.example");
        events.subscribe(access.liveAccess(request));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test void localhostAccessSurvivesPinChanges() throws Exception {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        events.subscribe(access.liveAccess(request));
        when(pins.authenticationVersion()).thenReturn("second-version");
        events.fileUploaded("local.txt");
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test void disconnectedEmitterIsRemoved() throws Exception {
        events.subscribe(() -> true);
        doThrow(new IOException("disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        events.heartbeat();
        events.fileUploaded("later.txt");
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test void completionRemovesStreams() throws Exception {
        var completion = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        events.subscribe(() -> true);
        verify(emitter).onCompletion(completion.capture());
        completion.getValue().run();
        events.fileUploaded("later.txt");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test void timeoutClosesAndRemovesStreams() throws Exception {
        var timeout = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        events.subscribe(() -> true);
        verify(emitter).onTimeout(timeout.capture());
        timeout.getValue().run();
        events.fileUploaded("later.txt");
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test void revocationIsCheckedForEveryEventType() throws Exception {
        var valid = new AtomicBoolean(true);
        events.subscribe(valid::get);
        valid.set(false);
        events.filesChangedAfterCommit();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
    }

    @Test void streamsHaveFiniteLifetime() {
        assertThat(new FileEventService().createEmitter().getTimeout()).isEqualTo(300_000L);
    }
}
