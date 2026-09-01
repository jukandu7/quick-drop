package com.example.quickdrop.share;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SharedFileRepository extends JpaRepository<SharedFile, String> {

    Optional<SharedFile> findByFilename(String filename);

    List<SharedFile> findAllByExpiresAtBefore(Instant currentTime);
}
