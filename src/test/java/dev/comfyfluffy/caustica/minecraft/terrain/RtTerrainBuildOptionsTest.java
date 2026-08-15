package dev.comfyfluffy.caustica.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainBuildOptionsTest {
    private static final Path ROOT = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica",
            "minecraft").toAbsolutePath().normalize();

    @Test
    void terrainRequestsMemoryOptimizationAndFrameCadenceSourcesKeepTheDefault() throws IOException {
        String terrain = Files.readString(ROOT.resolve("terrain/RtTerrain.java"));
        assertTrue(terrain.contains("SceneGeometrySink.BuildOptions.MINIMIZE_MEMORY_AND_ACCELERATE_OPACITY"));

        for (String source : List.of("entity/RtEntities.java", "cloud/MinecraftCloudSceneProvider.java")) {
            String text = Files.readString(ROOT.resolve(source));
            assertTrue(text.contains("new SceneGeometrySink.Put("), source);
            assertFalse(text.contains("BuildOptions.MINIMIZE_MEMORY"), source);
            assertFalse(text.contains("ACCELERATE_OPACITY"), source);
        }
    }
}
