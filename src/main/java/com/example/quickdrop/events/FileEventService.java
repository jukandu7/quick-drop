package com.example.quickdrop.events;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

@Service
public class FileEventService {

    private final CopyOnWriteArrayList<Subscription> emitters =
            new CopyOnWriteArrayList<>();

    public SseEmitter subscribe(BooleanSupplier authorized) {
        SseEmitter emitter = createEmitter();
        Subscription subscription = new Subscription(emitter, authorized);
        emitters.add(subscription);

        emitter.onCompletion(subscription::remove);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.remove());

        subscription.send("connected", "connected");

        return emitter;
    }

    SseEmitter createEmitter() {
        // EventSource reconnects through the normal owner-access filter.
        return new SseEmitter(300_000L);
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        // Recheck idle streams too, and detect disconnected clients through writes.
        broadcast("heartbeat", "alive");
    }

    public void filesChangedAfterCommit() {
        if (TransactionSynchronizationManager
                .isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcastFilesChanged();
                        }
                    }
            );
            return;
        }

        broadcastFilesChanged();
    }

    public void fileReceived(String filename) {
        broadcast("file-received", filename);
    }

    public void fileUploaded(String filename) {
        broadcast("file-uploaded", filename);
    }

    private void broadcastFilesChanged() {
        broadcast("files-changed", "refresh");
    }

    private void broadcast(String eventName, String data) {
        for (Subscription subscription : emitters) {
            subscription.send(eventName, data);
        }
    }

    private final class Subscription {
        private final SseEmitter emitter;
        private final BooleanSupplier authorized;
        private boolean closed;

        private Subscription(SseEmitter emitter, BooleanSupplier authorized) {
            this.emitter = emitter;
            this.authorized = authorized;
        }

        synchronized void send(String eventName, String data) {
            if (closed) return;
            try {
                if (!authorized.getAsBoolean()) {
                    close();
                    return;
                }
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(data));
            } catch (IOException | RuntimeException exception) {
                close();
            }
        }

        synchronized void remove() {
            closed = true;
            emitters.remove(this);
        }

        synchronized void close() {
            if (closed) return;
            remove();
            emitter.complete();
        }
    }
}
