package dev.comfyfluffy.caustica.minecraft.client.architecture;

import dev.comfyfluffy.caustica.minecraft.client.TestProjectRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class SourceDependencyArchitectureTest {
    private static final Path PROJECT_ROOT = TestProjectRoot.resolve("");
    private static final Path MAIN_JAVA = PROJECT_ROOT.resolve("packages/minecraft-client/src/main/java");
    private static final Path MINECRAFT = MAIN_JAVA.resolve("dev/comfyfluffy/caustica/minecraft/client");
    private static final Path MINECRAFT_RENDERING = PROJECT_ROOT.resolve(
            "packages/minecraft-rendering/src/main/java/dev/comfyfluffy/caustica/minecraft/rendering");
    private static final Path API = PROJECT_ROOT.resolve(
            "packages/api/src/main/java/dev/comfyfluffy/caustica/api");

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
                        "dev.comfyfluffy.caustica.renderer.runtime"));
        assertNoImports(List.of(MINECRAFT_RENDERING.resolve("entity")),
                List.of("dev.comfyfluffy.caustica.spi.host", "dev.comfyfluffy.caustica.spi.vulkan",
                        "dev.comfyfluffy.caustica.renderer.runtime", "net.minecraft", "com.mojang"));
    }

    @Test
    void supportedApiDoesNotImportHostOrImplementationPackages() throws IOException {
        String rootPackage = "dev.comfyfluffy.caustica";
        String apiPackage = rootPackage + ".api";
        String settingsPackage = rootPackage + ".settings";
        assertNoImportsMatching(List.of(API), imported ->
                (imported.equals(rootPackage) || imported.startsWith(rootPackage + "."))
                        && !(imported.equals(apiPackage) || imported.startsWith(apiPackage + "."))
                        && !(imported.equals(settingsPackage) || imported.startsWith(settingsPackage + ".")));
    }

    @Test
    void minecraftFrameSelectionUsesOnlyEngineIssuedSceneIds() throws IOException {
        Path adapter = MINECRAFT.resolve("MinecraftFrameAdapter.java");
        Path selector = MINECRAFT_RENDERING.resolve("MinecraftFrameSelector.java");
        assertFalse(Files.readString(adapter).contains("new SceneId"),
                "MinecraftFrameAdapter must not fabricate a SceneId");
        assertFalse(Files.readString(selector).contains("static volatile"),
                "MinecraftFrameSelector must not own static epoch state");
    }

    @Test
    void minecraftSkyAndLightPublicationDoNotSampleClientState() throws IOException {
        for (Path source : List.of(MINECRAFT_RENDERING.resolve("sky/SkyLutPass.java"),
                MINECRAFT_RENDERING.resolve("provider/MinecraftLightProvider.java"))) {
            String text = Files.readString(source);
            assertFalse(text.contains("Minecraft.getInstance()"), source + " samples Minecraft during recording");
            assertFalse(text.contains("import net.minecraft"), source + " imports live Minecraft state");
        }
    }

    @Test
    void entityCaptureHasOneInjectedIngressAndNoEntitySingletons() throws IOException {
        Path entities = MINECRAFT.resolve("entity/RtEntities.java");
        Path textures = MINECRAFT.resolve("entity/RtEntityTextures.java");
        Path adapter = MINECRAFT.resolve("MinecraftFrameAdapter.java");
        String entitySource = Files.readString(entities);
        String textureSource = Files.readString(textures);
        String adapterSource = Files.readString(adapter);
        assertFalse(entitySource.contains("static final RtEntities"), "RtEntities must be session-owned");
        assertFalse(textureSource.contains("static final RtEntityTextures"),
                "RtEntityTextures must be client-owned");
        assertEquals(1, occurrences(adapterSource, "entities.submitFrame("),
                "MinecraftFrameAdapter must have exactly one entity publication ingress");
        assertFalse(readJavaSources(MAIN_JAVA).contains("RtEntities.INSTANCE"));
        assertFalse(readJavaSources(MAIN_JAVA).contains("RtEntityTextures.INSTANCE"));
    }

    @Test
    void extensionSettingsHaveNoProcessLocator() throws IOException {
        Path locator = PROJECT_ROOT.resolve(
                "packages/settings-api/src/main/java/dev/comfyfluffy/caustica/settings/CausticaSettings.java");
        String production = readJavaSources(PROJECT_ROOT.resolve("packages"))
                + readJavaSources(MAIN_JAVA);

        assertFalse(Files.exists(locator), "settings values must be supplied through process APIs");
        assertFalse(production.contains("CausticaOptions.installed()"));
        assertFalse(production.contains("CausticaSettings.getInstance()"));
    }

    @Test
    void roundedCloudProductionCodeAndResourcesStayExcluded() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(PROJECT_ROOT.resolve("packages"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String relative = PROJECT_ROOT.relativize(file).toString().replace('\\', '/');
                if (!isProductionPath(relative)) continue;
                String lowerPath = relative.toLowerCase(java.util.Locale.ROOT);
                if (lowerPath.contains("/cloud/") || lowerPath.contains("rounded_cloud")
                        || lowerPath.contains("roundedcloud") || lowerPath.contains("rtcloud")) {
                    violations.add(relative);
                    continue;
                }
                if (!isProductionText(file)) continue;
                String text = Files.readString(file);
                if (text.contains("RoundedCloud") || text.contains("roundedCloud")
                        || text.contains("rounded_cloud") || text.contains("RtCloud")
                        || text.contains("package dev.comfyfluffy.caustica.minecraft.cloud")) {
                    violations.add(relative);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Rounded-cloud production code and resources must stay excluded; found: "
                    + String.join(", ", violations));
        }
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        for (int offset = 0; (offset = text.indexOf(value, offset)) >= 0; offset += value.length()) count++;
        return count;
    }

    private static String readJavaSources(Path root) throws IOException {
        StringBuilder combined = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")
                    && path.toString().replace('\\', '/').contains("/src/main/java/")).toList()) {
                combined.append(Files.readString(file));
            }
        }
        return combined.toString();
    }

    private static boolean isProductionText(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".slang") || name.endsWith(".json")
                || name.endsWith(".toml") || name.endsWith(".properties") || name.endsWith(".mcmeta");
    }

    private static boolean isProductionPath(String relative) {
        return !relative.contains("/build/") && !relative.contains("/src/test/")
                && !relative.contains("/src/testFixtures/") && !relative.contains("/src/toolingTest/")
                && !relative.contains("/src/shaderReflection/")
                && !relative.contains("/src/shaderValidation/");
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
}
