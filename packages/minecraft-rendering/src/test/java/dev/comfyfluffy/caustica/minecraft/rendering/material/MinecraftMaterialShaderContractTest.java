package dev.comfyfluffy.caustica.minecraft.rendering.material;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialShaderContractTest {
    @Test
    void baseAtlasDoesNotUseTheSceneSpaceRayFootprintAsAMipLevel() throws Exception {
        String source = materialShader();

        assertTrue(source.contains("float4 texel = minecraftSampleBase(hit, hit.textureCoordinate, 0.0);"));
        assertFalse(source.contains("hit.textureCoordinate, input.textureFootprint"));
        assertTrue(source.contains("float pageLod = min(input.textureFootprint, data.maxLod);"));
        assertTrue(source.contains("hit.primitive.baseSampler"));
        assertTrue(source.contains("sampleTexture2DLod(texture, hit.implementation.samplerIndex"));
    }

    @Test
    void acceptedTranslucentHitsDoNotApplyCoverageAlphaAgain() throws Exception {
        String source = materialShader();

        assertTrue(source.contains("material.base_color *= sampled * geometryTint;"));
        assertFalse(source.contains("lerp(float3(1.0), sampled * geometryTint"));
    }

    @Test
    void coveragePreservesVertexAlphaWithAndWithoutATexture() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/resources/caustica/shaders/minecraft/surface/caustica_minecraft_coverage.slang"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("return clamp(hit.vertexColor.a, 0.0, 1.0);"));
        assertTrue(source.contains("minecraftSampleBase(hit, hit.textureCoordinate, 0.0).a\n"
                + "                * hit.vertexColor.a"));
    }

    @Test
    void baseColorEmissionDoesNotRetintPackedEmissionTextures() throws Exception {
        String source = materialShader();

        assertTrue(source.contains("MINECRAFT_MATERIAL_EMISSION_COLOR_BASE = 16u"));
        assertTrue(source.contains("(data.features & MINECRAFT_MATERIAL_EMISSION_COLOR_BASE) != 0u\n"
                + "            && (data.features & MINECRAFT_MATERIAL_EMISSION_MASK) == 0u"));
        assertTrue(source.contains("float3 emissionBaseColor = material.base_color;"));
        assertTrue(source.contains("material.emission_color *= emissionBaseColor;"));
    }

    private static String materialShader() throws Exception {
        return Files.readString(Path.of(
                "src/main/resources/caustica/shaders/minecraft/surface/caustica_minecraft_material.slang"))
                .replace("\r\n", "\n");
    }
}
