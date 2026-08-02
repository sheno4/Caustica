package dev.comfyfluffy.caustica.rt.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RayPackDiscoveryTest {
    private static final String MANIFEST_TEMPLATE = """
            {
              "format": 1,
              "id": "%s",
              "version": "0.1.0",
              "api": {"major": 0, "minor": 1},
              "slang": {"module": "test_pack", "type": "TestPack", "sourceRoot": "shaders"},
              "lookPackage": "%s"
            }
            """;

    @Test
    void discoversTheBundledDefault() {
        RayPackManifest manifest = RayPackDiscovery.discoverBundled();
        assertEquals(new RayPackId("caustica", "default"), manifest.id());
    }

    @Test
    void returnsEmptyForAMissingDirectory() throws IOException {
        RayPackDiscovery.Result result = RayPackDiscovery.scanDirectory(
                Path.of("does-not-exist-" + System.nanoTime()));
        assertTrue(result.manifests().isEmpty());
        assertTrue(result.duplicateConflicts().isEmpty());
    }

    @Test
    void scansSubdirectoriesInOrderAndKeepsTheFirstOfADuplicateId(@TempDir Path root) throws IOException {
        writeManifest(root, "a-first", "test:shared");
        writeManifest(root, "b-second", "test:shared");
        writeManifest(root, "c-unique", "test:unique");

        RayPackDiscovery.Result result = RayPackDiscovery.scanDirectory(root);
        assertEquals(2, result.manifests().size());
        assertEquals(new RayPackId("test", "shared"), result.manifests().get(0).id());
        assertEquals(new RayPackId("test", "unique"), result.manifests().get(1).id());
        assertEquals(1, result.duplicateConflicts().size());
        assertTrue(result.duplicateConflicts().get(0).contains("b-second"));
        assertTrue(result.duplicateConflicts().get(0).contains("test:shared"));
    }

    @Test
    void reportsAnUnparsableManifestAsAConflictWithoutAbortingTheScan(@TempDir Path root) throws IOException {
        Path badDirectory = Files.createDirectory(root.resolve("broken"));
        Files.writeString(badDirectory.resolve("pack.json"), "{ not json");
        writeManifest(root, "good", "test:good");

        RayPackDiscovery.Result result = RayPackDiscovery.scanDirectory(root);
        assertEquals(1, result.manifests().size());
        assertEquals(new RayPackId("test", "good"), result.manifests().get(0).id());
        assertEquals(1, result.duplicateConflicts().size());
        assertTrue(result.duplicateConflicts().get(0).contains("broken"));
    }

    private static void writeManifest(Path root, String directoryName, String id) throws IOException {
        Path directory = Files.createDirectory(root.resolve(directoryName));
        Files.writeString(directory.resolve("pack.json"), MANIFEST_TEMPLATE.formatted(id, id));
    }
}
