package dev.comfyfluffy.caustica.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;

final class SourceDependencyArchitectureTest {
    private static final Path PROJECT_ROOT = findProjectRoot();
    private static final Path MAIN_JAVA = PROJECT_ROOT.resolve("src/main/java");
    private static final Path MINECRAFT = MAIN_JAVA.resolve("dev/comfyfluffy/caustica/minecraft");
    private static final Path API = PROJECT_ROOT.resolve("api/src/main/java/dev/comfyfluffy/caustica/api");

    @Test
    void minecraftSceneProducersDoNotImportRendererInternals() throws IOException {
        assertNoImports(List.of(
                        MINECRAFT.resolve("provider"),
                        MINECRAFT.resolve("terrain"),
                        MINECRAFT.resolve("entity"),
                        MINECRAFT.resolve("cloud")),
                List.of(
                        "dev.comfyfluffy.caustica.spi.host",
                        "dev.comfyfluffy.caustica.spi.vulkan",
                        "dev.comfyfluffy.caustica.rt"));
    }

    @Test
    void supportedApiDoesNotImportHostOrImplementationPackages() throws IOException {
        String rootPackage = "dev.comfyfluffy.caustica";
        String apiPackage = rootPackage + ".api";
        assertNoImportsMatching(List.of(API), imported ->
                (imported.equals(rootPackage) || imported.startsWith(rootPackage + "."))
                        && !(imported.equals(apiPackage) || imported.startsWith(apiPackage + ".")));
    }

    private static void assertNoImports(List<Path> sourceRoots, List<String> forbiddenPackages)
            throws IOException {
        assertNoImportsMatching(sourceRoots, imported -> forbiddenPackages.stream().anyMatch(
                forbidden -> imported.equals(forbidden) || imported.startsWith(forbidden + ".")));
    }

    private static void assertNoImportsMatching(List<Path> sourceRoots, Predicate<String> forbidden)
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var sources = Files.walk(sourceRoot)) {
                for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                    List<String> lines = Files.readAllLines(source);
                    for (int i = 0; i < lines.size(); i++) {
                        String imported = importedType(lines.get(i));
                        if (imported != null && forbidden.test(imported)) {
                            violations.add(PROJECT_ROOT.relativize(source) + ":" + (i + 1) + ": " + imported);
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Forbidden source dependencies:\n" + String.join("\n", violations));
        }
    }

    private static String importedType(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("import ") || !trimmed.endsWith(";")) {
            return null;
        }
        String imported = trimmed.substring("import ".length(), trimmed.length() - 1).trim();
        return imported.startsWith("static ") ? imported.substring("static ".length()).trim() : imported;
    }

    private static Path findProjectRoot() {
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
