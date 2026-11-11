package com.example.quickdrop.presentation;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DisplayFormats {
    private static final DateTimeFormatter EXPIRATION = DateTimeFormatter
            .ofPattern("MMM d, yyyy 'at' h:mm:ss a z (XXX)", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private DisplayFormats() { }

    public static String expiration(Instant expiresAt) {
        return expiresAt == null ? "Never expires" : "Expires " + EXPIRATION.format(expiresAt);
    }

    public static String size(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
        return String.format(Locale.ENGLISH, value == Math.floor(value) ? "%.0f %s" : "%.2f %s", value, units[unit]);
    }

    public static String storage(long used, long quota) {
        return String.format(Locale.ENGLISH, "%.1f MB of %s used", used / (1024.0 * 1024), size(quota));
    }
}
