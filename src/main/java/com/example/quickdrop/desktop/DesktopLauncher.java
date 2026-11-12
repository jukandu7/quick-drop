package com.example.quickdrop.desktop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in desktop behavior; ordinary server and development launches are unchanged. */
public final class DesktopLauncher {
    private DesktopLauncher() { }

    static Path dataHome(String override, String localAppData, String userHome) {
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        Path parent = localAppData == null || localAppData.isBlank()
                ? Path.of(userHome, "AppData", "Local") : Path.of(localAppData);
        return parent.resolve("Quick Drop").toAbsolutePath().normalize();
    }

    public static void configure(SpringApplication application) {
        Path home = dataHome(System.getProperty("quickdrop.data-home", System.getenv("QUICKDROP_DATA_HOME")),
                System.getenv("LOCALAPPDATA"), System.getProperty("user.home"));
        try {
            Files.createDirectories(home.resolve("data"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot create Quick Drop's data folder: " + home, exception);
        }
        System.setProperty("quickdrop.data-home", home.toString().replace('\\', '/'));
        application.setAdditionalProfiles("desktop");
        application.setHeadless(false);
        application.addListeners((ApplicationListener<ApplicationReadyEvent>) event -> {
            var environment = event.getApplicationContext().getEnvironment();
            String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
            String address = scheme + "://localhost:" + environment.getRequiredProperty("local.server.port");
            System.out.println("\nQuick Drop is ready: " + address);
            System.out.println("Saved files and settings: " + home);
            System.out.println("Keep this window open while transferring. Close it to stop Quick Drop.\n");
            if (!environment.getProperty("quickdrop.open-browser", Boolean.class, true)) return;
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(address));
                } else {
                    System.out.println("Open the address above in your browser.");
                }
            } catch (Exception exception) {
                System.out.println("Could not open your browser. Open the address above manually.");
            }
        });
    }
}
