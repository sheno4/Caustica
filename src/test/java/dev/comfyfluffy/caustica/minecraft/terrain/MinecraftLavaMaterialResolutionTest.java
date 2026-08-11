package dev.comfyfluffy.caustica.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftLavaMaterialResolutionTest {
    @Test
    void lavaUsesAnOrdinaryEmittingSurfaceVariantFromTheCapturedSnapshot() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "minecraft", "terrain", "RtTerrainMesher.java"));

        assertTrue(source.contains("materials.resolve(MinecraftMaterialSource.LAVA_MATERIAL, null,"));
        assertTrue(source.contains("OpenPbrMaterialProfile.MEDIUM_ROUGH_DIELECTRIC"));
        assertTrue(source.contains("MaterialTopology.SURFACE, true"));
        assertFalse(source.contains("defaultUniformEmissionId"));
    }
}
