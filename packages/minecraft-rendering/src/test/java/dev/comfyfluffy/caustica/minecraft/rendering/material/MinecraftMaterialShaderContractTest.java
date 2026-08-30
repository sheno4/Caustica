package dev.comfyfluffy.caustica.minecraft.rendering.material;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialShaderContractTest {
    @Test
    void baseAtlasDoesNotUseTheSceneSpaceRayFootprintAsAMipLevel() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/resources/caustica/shaders/minecraft/surface/caustica_minecraft_material.slang"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("float4 texel = minecraftSample(hit, hit.primitive.baseTexture,\n"
                + "                hit.textureCoordinate, 0.0);"));
        assertFalse(source.contains("hit.textureCoordinate, input.textureFootprint"));
        assertTrue(source.contains("float pageLod = min(input.textureFootprint, data.maxLod);"));
    }
}
