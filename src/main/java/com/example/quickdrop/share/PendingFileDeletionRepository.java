package com.example.quickdrop.share;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PendingFileDeletionRepository extends JpaRepository<PendingFileDeletion, String> { }
