package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftParticleMaterialLifecycleTest {
    @Test
    void particlesSubmitNeutralMaterialAndTextureIdentities() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "minecraft", "entity", "RtEntities.java"));

        assertTrue(source.contains("new SceneMesh.NamedMaterial("));
        assertTrue(source.contains("new SceneMesh.AtlasTexture(ResourceId.of("));
        assertTrue(source.contains("capture.currentCoverage = SceneMesh.Coverage.CUTOUT"));
        assertFalse(source.contains("RtMaterialRegistry"));
        assertFalse(source.contains("particleId("));
    }
}
