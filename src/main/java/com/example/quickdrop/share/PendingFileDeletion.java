package com.example.quickdrop.share;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Written in the same transaction that removes a file's metadata. */
@Entity
@Table(name = "pending_file_deletions")
public class PendingFileDeletion {
    @Id
    private String relativePath;

    protected PendingFileDeletion() { }

    public PendingFileDeletion(String relativePath) { this.relativePath = relativePath; }

    public String getRelativePath() { return relativePath; }
}
