package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialIds;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialPageCompiler;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTopology;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrDefaults;
import dev.comfyfluffy.caustica.minecraft.api.ResourcePackEpoch;
import dev.comfyfluffy.caustica.settings.ResourceId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void captureSurfacesUseTheNeutralMaterialRecord() {
        MinecraftMaterialLookup lookup = MinecraftMaterialLookup.compile(
                new ResourcePackEpoch(7), List.of(), List.of(), MinecraftMaterialPageCompiler.compile(List.of()));

        for (var material : MinecraftMaterialIds.CAPTURE_SURFACES) {
            MinecraftMaterialResolution byId = lookup.resolve(material);
            MinecraftMaterialResolution byKey = lookup.resolve(new MinecraftMaterialKey(material, null,
                    MinecraftMaterialProfile.ROUGH_DIELECTRIC, MinecraftMaterialTopology.SURFACE));

            assertEquals(byId, byKey);
            assertEquals(0, byId.materialIndex());
            assertEquals(material, byId.material());
            assertEquals(MinecraftMaterialTopology.SURFACE, byId.topology());
            assertEquals(MinecraftMaterialEmission.NONE, byId.emission());
            assertEquals(MinecraftMaterialRecord.fallback(), lookup.records().get(byId.materialIndex()));
        }
    }

    @Test
    void entityFallbackIsExplicitAndDoesNotWeakenStrictLookup() {
        MinecraftMaterialLookup lookup = MinecraftMaterialLookup.compile(
                new ResourcePackEpoch(7), List.of(), List.of(), MinecraftMaterialPageCompiler.compile(List.of()));
        var key = new MinecraftMaterialKey(ResourceId.of("minecraft", "entity/zombie/zombie"), null,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MinecraftMaterialTopology.SURFACE);

        assertThrows(IllegalArgumentException.class, () -> lookup.resolve(key));
        MinecraftMaterialResolution fallback = lookup.resolveEntityOrFallback(key);
        assertEquals(0, fallback.materialIndex());
        assertEquals(key.material(), fallback.material());
        assertEquals(MinecraftMaterialEmission.NONE, fallback.emission());
    }
}
