package com.example.quickdrop.receive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "receive_links")
public class ReceiveLink {

    @Id
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    protected ReceiveLink() {
    }

    public ReceiveLink(String token, Instant expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
