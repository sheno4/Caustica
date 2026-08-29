package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MinecraftMaterialLookupTest {
    @Test
    void alwaysPublishesTheLogicalWaterBoundaryMaterial() {
        MinecraftMaterialLookup lookup = MinecraftMaterialLookup.compile(
                new ResourcePackEpoch(7), List.of(), List.of(), MinecraftMaterialPageCompiler.compile(List.of()));

        MinecraftMaterialResolution water = lookup.resolve(MinecraftMaterialIds.WATER);

        assertEquals(1, water.materialIndex());
        assertEquals(MinecraftMaterialTopology.MEDIUM_BOUNDARY, water.topology());
        MinecraftMaterialRecord record = lookup.records().get(water.materialIndex());
        assertEquals(OpenPbrDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS, record.specularRoughness());
        assertEquals(OpenPbrDefaults.TRANSMISSIVE_SPECULAR_IOR, record.specularIor());
        assertEquals(1.0f, record.transmissionWeight());
    }
}
