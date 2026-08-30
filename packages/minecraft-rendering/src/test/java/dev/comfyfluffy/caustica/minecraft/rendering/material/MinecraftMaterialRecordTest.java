package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MinecraftMaterialRecordTest {
    @Test
    void resolvesLogicalTextureOrdinalsIntoGeneratedDescriptorIndices() {
        MinecraftMaterialRecord record = new MinecraftMaterialRecord(
                MinecraftMaterialPageCompiler.FEATURE_SPEC, 3.0f,
                1, 3, 5, 7,
                new MaterialUv(0.25f, 0.5f, 0.125f, 0.25f),
                new MaterialUv(0.125f, 0.25f, 8.0f, 4.0f),
                MinecraftMaterialRecord.Color3.WHITE, 0.5f, 0.75f, 1.5f,
                0.0f, 0.0f, new MinecraftMaterialRecord.Color3(0.5f, 0.25f, 0.125f), 20.0f);

        var shader = record.shaderData(ordinal -> 100 + ordinal);

        assertEquals(101, shader.surface0Texture().value());
        assertEquals(103, shader.surface1Texture().value());
        assertEquals(105, shader.normalTexture().value());
        assertEquals(107, shader.emissionTexture().value());
        assertEquals(0.125f, shader.materialUv().z());
        assertEquals(8.0f, shader.baseColorUv().z());
        assertEquals(20.0f, shader.emissionLuminance());
    }

    @Test
    void rejectsValuesOutsideOpenPbrScalarDomains() {
        assertThrows(IllegalArgumentException.class, () -> MinecraftMaterialRecord.from(
                new MinecraftMaterialPageCompiler.CompiledMaterial(0, 0, 0, 0, 0, 0,
                        MaterialUv.IDENTITY, MaterialUv.IDENTITY),
                MinecraftMaterialRecord.Color3.WHITE, 0.0f, 1.1f, 1.5f,
                0.0f, 0.0f, MinecraftMaterialRecord.Color3.WHITE, 0.0f));
    }
}
