package dev.comfyfluffy.caustica.minecraft.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves repository sources independently of the loader test task's working directory. */
public final class TestProjectRoot {
    private static final Path ROOT = find();

    private TestProjectRoot() {
    }

    public static Path resolve(String relative) {
        return ROOT.resolve(relative);
    }

    private static Path find() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    || Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Cannot locate the Gradle project above " + System.getProperty("user.dir"));
    }
}
