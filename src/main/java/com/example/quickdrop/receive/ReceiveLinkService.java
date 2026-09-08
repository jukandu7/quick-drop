package com.example.quickdrop.receive;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiveLinkService {

    private final ReceiveLinkRepository repository;

    public ReceiveLinkService(ReceiveLinkRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LinkDetails start(Duration lifetime) {
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException(
                    "Receiving time must be greater than zero."
            );
        }

        repository.deleteAllInBatch();
        Instant expiresAt = Instant.now().plus(lifetime);
        ReceiveLink link = repository.save(new ReceiveLink(
                UUID.randomUUID().toString(),
                expiresAt
        ));

        return new LinkDetails(link.getToken(), link.getExpiresAt());
    }

    @Transactional
    public Optional<LinkDetails> findCurrentActive() {
        Instant currentTime = Instant.now();
        repository.deleteAllByExpiresAtBefore(currentTime);

        List<ReceiveLink> links = repository
                .findAllByOrderByExpiresAtDesc();

        links.removeIf(link ->
                !link.getExpiresAt().isAfter(currentTime)
        );

        if (links.isEmpty()) {
            return Optional.empty();
        }

        ReceiveLink current = links.getFirst();

        if (links.size() > 1) {
            repository.deleteAll(links.subList(1, links.size()));
        }

        return Optional.of(toDetails(current));
    }

    @Transactional
    public Optional<LinkDetails> findActive(String token) {
        return findCurrentActive()
                .filter(link -> link.token().equals(token));
    }

    @Transactional
    public boolean stop() {
        boolean wasActive = findCurrentActive().isPresent();
        repository.deleteAllInBatch();
        return wasActive;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void deleteExpiredLinks() {
        repository.deleteAllByExpiresAtBefore(Instant.now());
    }

    private LinkDetails toDetails(ReceiveLink link) {
        return new LinkDetails(link.getToken(), link.getExpiresAt());
    }

    public record LinkDetails(String token, Instant expiresAt) {
    }
}
