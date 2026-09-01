package com.example.quickdrop.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "shared_files")
public class SharedFile {

    @Id
    private String token;

    @Column(nullable = false, unique = true)
    private String filename;

    private Instant expiresAt;

    protected SharedFile() {
    }

    public SharedFile(String token, String filename, Instant expiresAt) {
        this.token = token;
        this.filename = filename;
        this.expiresAt = expiresAt;
    }

    public String getToken() {
        return token;
    }

    public String getFilename() {
        return filename;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
