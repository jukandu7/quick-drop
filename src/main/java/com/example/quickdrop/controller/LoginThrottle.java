package com.example.quickdrop.controller;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** In-memory limits for this single-host app. Reservations precede PIN hashing. */
@Service
public class LoginThrottle {
    private static final long MINUTE = Duration.ofMinutes(1).toNanos();
    private static final long IDLE_RESET = Duration.ofMinutes(30).toNanos();
    private final LongSupplier ticker;
    private final Map<String, Client> clients = new HashMap<>();
    private final ArrayDeque<Long> globalAttempts = new ArrayDeque<>();
    private int inFlight;

    public LoginThrottle() {
        this(System::nanoTime);
    }

    LoginThrottle(LongSupplier ticker) {
        this.ticker = ticker;
    }

    /** Zero reserves an attempt; a positive value is Retry-After in seconds. */
    public synchronized long acquire(String address) {
        long now = ticker.getAsLong();
        clients.values().removeIf(client -> now - client.lastAttempt >= IDLE_RESET);
        while (!globalAttempts.isEmpty() && now - globalAttempts.peekFirst() >= MINUTE) {
            globalAttempts.removeFirst();
        }
        Client client = clients.get(address);
        if (client != null && client.cooling && now - client.blockedAt < client.delay) {
            return seconds(client.delay - (now - client.blockedAt));
        }
        if (globalAttempts.size() >= 30) {
            return seconds(MINUTE - (now - globalAttempts.peekFirst()));
        }
        if (inFlight >= 2) {
            return 1;
        }
        // Reject new clients at capacity rather than evicting active cooldowns.
        if (client == null) {
            if (clients.size() >= 4096) {
                return 60;
            }
            client = new Client();
            clients.put(address, client);
        }
        client.lastAttempt = now;
        client.cooling = false;
        if (++client.attempts == 5) {
            client.attempts = 0;
            client.delay = client.delay == 0 ? MINUTE : Math.min(client.delay * 2, 15 * MINUTE);
            client.blockedAt = now;
            client.cooling = true;
        }
        globalAttempts.addLast(now);
        inFlight++;
        return 0;
    }

    public synchronized void complete() {
        inFlight--;
    }

    private static long seconds(long nanos) {
        return Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
    }

    private static class Client {
        int attempts;
        long lastAttempt;
        long blockedAt;
        long delay;
        boolean cooling;
    }
}
