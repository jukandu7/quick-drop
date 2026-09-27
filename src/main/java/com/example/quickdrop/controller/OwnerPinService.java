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

    private static final String PIN_FORMAT = "four-digit-v1";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private final Path pinFile;
    private final SecureRandom secureRandom = new SecureRandom();

    private byte[] salt;
    private byte[] pinHash;

    public OwnerPinService() {
        this(Path.of("data", "owner-auth.properties"));
    }

    OwnerPinService(Path pinFile) {
        this.pinFile = pinFile.toAbsolutePath().normalize();
    }

    @PostConstruct
    public synchronized void load() throws IOException {
        salt = null;
        pinHash = null;

        if (!Files.isRegularFile(pinFile)) {
            return;
        }

        Properties properties = new Properties();

        try (InputStream inputStream = Files.newInputStream(pinFile)) {
            properties.load(inputStream);
        }

        if (!PIN_FORMAT.equals(properties.getProperty("format"))) {
            return;
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

        if (matches(pin)) {
            throw new IllegalArgumentException(
                    "Your new PIN is the same as your current PIN. Choose a different PIN."
            );
        }

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
        properties.setProperty("format", PIN_FORMAT);
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
        }
        finally {
            Files.deleteIfExists(temporaryFile);
        }

        salt = newSalt;
        pinHash = newHash;
    }

    public void validate(String pin) {
        if (pin == null || !pin.matches("[0-9]{4}")) {
            throw new IllegalArgumentException(
                    "Use exactly 4 numerical digits for the owner PIN."
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
        }
        finally {
            specification.clearPassword();
        }
    }
}
