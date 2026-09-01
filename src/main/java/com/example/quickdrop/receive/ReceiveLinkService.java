package com.example.quickdrop.receive;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReceiveLinkService {

    private final ReceiveLinkRepository repository;

    public ReceiveLinkService(ReceiveLinkRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LinkDetails create(Duration lifetime) {
        Instant expiresAt = Instant.now().plus(lifetime);
        ReceiveLink link = repository.save(new ReceiveLink(
                UUID.randomUUID().toString(),
                expiresAt
        ));

        return new LinkDetails(link.getToken(), link.getExpiresAt());
    }

    @Transactional
    public Optional<LinkDetails> findActive(String token) {
        Optional<ReceiveLink> match = repository.findById(token);

        if (match.isEmpty()) {
            return Optional.empty();
        }

        ReceiveLink link = match.get();

        if (!link.getExpiresAt().isAfter(Instant.now())) {
            repository.delete(link);
            return Optional.empty();
        }

        return Optional.of(new LinkDetails(
                link.getToken(),
                link.getExpiresAt()
        ));
    }

    @Transactional
    public boolean delete(String token) {
        if (!repository.existsById(token)) {
            return false;
        }

        repository.deleteById(token);
        return true;
    }

    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void deleteExpiredLinks() {
        repository.deleteAllByExpiresAtBefore(Instant.now());
    }

    public record LinkDetails(String token, Instant expiresAt) {
    }
}
