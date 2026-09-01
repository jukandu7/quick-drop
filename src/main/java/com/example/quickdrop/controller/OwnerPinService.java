package com.example.quickdrop.controller;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

@Service
public class OwnerPinService {

    private static final int MINIMUM_PIN_LENGTH = 6;
    private static final int MAXIMUM_PIN_LENGTH = 128;
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private final Path pinFile = Path.of("data", "owner-auth.properties")
            .toAbsolutePath()
            .normalize();
    private final SecureRandom secureRandom = new SecureRandom();

    private byte[] salt;
    private byte[] pinHash;

    @PostConstruct
    public synchronized void load() throws IOException {
        if (!Files.isRegularFile(pinFile)) {
            return;
        }

        Properties properties = new Properties();

        try (InputStream inputStream = Files.newInputStream(pinFile)) {
            properties.load(inputStream);
        }

        try {
            salt = Base64.getDecoder().decode(
                    properties.getProperty("salt")
            );
            pinHash = Base64.getDecoder().decode(
                    properties.getProperty("hash")
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IOException(
                    "Quick Drop's saved owner PIN is invalid.",
                    exception
            );
        }
    }

    public synchronized boolean isConfigured() {
        return salt != null && pinHash != null;
    }

    public synchronized String authenticationVersion() {
        if (!isConfigured()) {
            return "";
        }

        return Base64.getEncoder().encodeToString(pinHash);
    }

    public synchronized boolean matches(String candidatePin) {
        if (!isConfigured() || candidatePin == null) {
            return false;
        }

        byte[] candidateHash = deriveHash(candidatePin, salt);
        return MessageDigest.isEqual(pinHash, candidateHash);
    }

    public synchronized void save(String pin) throws IOException {
        validate(pin);

        byte[] newSalt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(newSalt);
        byte[] newHash = deriveHash(pin, newSalt);

        Files.createDirectories(pinFile.getParent());
        Path temporaryFile = Files.createTempFile(
                pinFile.getParent(),
                "owner-auth-",
                ".tmp"
        );

        Properties properties = new Properties();
        properties.setProperty(
                "salt",
                Base64.getEncoder().encodeToString(newSalt)
        );
        properties.setProperty(
                "hash",
                Base64.getEncoder().encodeToString(newHash)
        );

        try {
            try (OutputStream outputStream =
                         Files.newOutputStream(temporaryFile)) {
                properties.store(
                        outputStream,
                        "Quick Drop owner authentication"
                );
            }

            try {
                Files.move(
                        temporaryFile,
                        pinFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporaryFile,
                        pinFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }

        salt = newSalt;
        pinHash = newHash;
    }

    public void validate(String pin) {
        if (pin == null || pin.length() < MINIMUM_PIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Use at least 6 characters for the owner PIN."
            );
        }

        if (pin.length() > MAXIMUM_PIN_LENGTH) {
            throw new IllegalArgumentException(
                    "The owner PIN must be 128 characters or fewer."
            );
        }
    }

    private byte[] deriveHash(String pin, byte[] pinSalt) {
        PBEKeySpec specification = new PBEKeySpec(
                pin.toCharArray(),
                pinSalt,
                ITERATIONS,
                KEY_LENGTH_BITS
        );

        try {
            return SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Owner PIN protection is unavailable.",
                    exception
            );
        } finally {
            specification.clearPassword();
        }
    }
}
