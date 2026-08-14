package dev.comfyfluffy.caustica;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Java source roots that compile into {@code sourceSets.main}. Both loader roots contribute classes to
 * the same packages as {@code src/main}, so an import firewall that scans only {@code src/main} leaves
 * every loader-specific file unchecked.
 */
public final class SourceRoots {
    private static final List<Path> JAVA_ROOTS = List.of(
            Path.of("src", "main", "java"),
            Path.of("src", "fabric", "java"),
            Path.of("src", "neoforge", "java"));

    private SourceRoots() {
    }

    /** Every existing directory holding {@code dev.comfyfluffy.caustica.<segments>} across the roots. */
    public static List<Path> packageDirectories(String... segments) {
        List<Path> directories = new ArrayList<>();
        for (Path root : JAVA_ROOTS) {
            Path directory = root.resolve(Path.of("dev", "comfyfluffy", "caustica"));
            for (String segment : segments) {
                directory = directory.resolve(segment);
            }
            Path absolute = directory.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                directories.add(absolute);
            }
        }
        return directories;
    }

    /** Every {@code .java} file under {@link #packageDirectories}, across all source roots. */
    public static List<Path> javaSources(String... segments) throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path directory : packageDirectories(segments)) {
            try (var paths = Files.walk(directory)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(sources::add);
            }
        }
        return sources;
    }
}
