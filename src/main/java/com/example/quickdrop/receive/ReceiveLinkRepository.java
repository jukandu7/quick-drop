package com.example.quickdrop.receive;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ReceiveLinkRepository
        extends JpaRepository<ReceiveLink, String> {

    long deleteAllByExpiresAtBefore(Instant currentTime);
}
