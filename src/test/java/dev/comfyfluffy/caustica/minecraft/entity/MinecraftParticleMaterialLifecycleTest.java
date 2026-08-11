package dev.comfyfluffy.caustica.minecraft.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftParticleMaterialLifecycleTest {
    @Test
    void particleBindingsComeFromOneCapturedMaterialEpoch() throws Exception {
        String source = Files.readString(Path.of("src", "main", "java", "dev", "comfyfluffy",
                "caustica", "minecraft", "entity", "RtEntities.java"));

        assertTrue(source.contains("final RtMaterialRegistry.Snapshot materials;"));
        assertTrue(source.contains("build.materials.bindingId(MinecraftMaterialSource.PARTICLE_BILLBOARD)"));
        assertTrue(source.contains("registry.withCutoutCoverage(build.materials, particleMaterial)"));
        assertTrue(source.contains("registry.withBaseColorTextureIndex(build.materials, bindingId, textureIndex)"));
        assertTrue(source.contains("capture.baseColorMaterialResolver = defaultMaterialResolver;"));
        assertFalse(source.contains("particleId("));
    }
}
