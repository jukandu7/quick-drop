package com.example.quickdrop.desktop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class DesktopLauncherTests {
    @TempDir Path temporary;

    @Test void packagedDataLivesOutsideTheExtractedRelease() {
        Path expected = temporary.resolve("Quick Drop").toAbsolutePath();
        assertThat(DesktopLauncher.dataHome(null, temporary.toString(), "unused")).isEqualTo(expected);
    }

    @Test void anExplicitDataDirectorySupportsIsolatedTestingAndMigration() {
        Path custom = temporary.resolve("custom data");
        assertThat(DesktopLauncher.dataHome(custom.toString(), "unused", "unused")).isEqualTo(custom.toAbsolutePath());
    }

    @Test void missingLocalAppDataFallsBackToTheUserProfile() {
        assertThat(DesktopLauncher.dataHome("", "", temporary.toString()))
                .isEqualTo(temporary.resolve("AppData/Local/Quick Drop").toAbsolutePath());
    }
}
