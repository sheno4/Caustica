package dev.comfyfluffy.caustica.engine.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class EngineMaterialVocabularyFirewallTest {
    private static final Pattern HOST_VOCABULARY = Pattern.compile(
            "(?i)\\b(block|entity|minecraft|vanilla)\\b");

    @Test
    void neutralMaterialContractDoesNotPublishHostCategories() throws IOException {
        Path sources = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
                "engine", "material").toAbsolutePath().normalize();
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(sources)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                var matcher = HOST_VOCABULARY.matcher(Files.readString(source));
                if (matcher.find()) {
                    violations.add(sources.relativize(source) + ": " + matcher.group());
                }
            }
        }
        assertTrue(violations.isEmpty(), "neutral material vocabulary leaked host categories:\n"
                + String.join("\n", violations));
    }

    @Test
    void neutralDefaultsDoNotPublishASourceSpecificLiquid() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "engine", "material", "OpenPbrMaterialDefaults.java"));
        assertTrue(!source.toLowerCase(java.util.Locale.ROOT).contains("water"));
        assertTrue(!source.contains("REFERENCE_LIQUID_IOR"));
    }
}
