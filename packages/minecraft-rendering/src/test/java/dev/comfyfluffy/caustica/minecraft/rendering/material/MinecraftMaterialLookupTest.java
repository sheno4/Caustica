package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.minecraft.content.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.minecraft.content.material.OpenPbrTextureTexel;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialRule;
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
    void topologyOverridesChooseDefaultsBeforeParameterOverrides() {
        for (var topology : MinecraftMaterialTopology.values()) {
            var defaults = overriddenMaterial(new MinecraftMaterialRule.Parameters(
                    null, null, null, null, null, topology));
            var explicit = overriddenMaterial(new MinecraftMaterialRule.Parameters(
                    0.42f, 0.25f, 1.7f, 0.6f, null, topology));
            for (var sourceTopology : MinecraftMaterialTopology.values()) {
                var key = new MinecraftMaterialKey(ResourceId.of("test", "material"), null,
                        MinecraftMaterialProfile.CONDUCTOR, sourceTopology);
                var resolution = defaults.resolve(key);
                assertEquals(topology, resolution.topology());
                var record = defaults.records().get(resolution.materialIndex());
                boolean boundary = topology == MinecraftMaterialTopology.MEDIUM_BOUNDARY;
                assertEquals(boundary ? OpenPbrDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS : 0.3f,
                        record.specularRoughness());
                assertEquals(boundary ? 0.0f : 1.0f, record.baseMetalness());
                assertEquals(boundary ? 1.31f : OpenPbrDefaults.SPECULAR_IOR, record.specularIor());
                assertEquals(boundary ? 1.0f : 0.0f, record.transmissionWeight());

                var overridden = explicit.records().get(explicit.resolve(key).materialIndex());
                assertEquals(0.42f, overridden.specularRoughness());
                assertEquals(0.25f, overridden.baseMetalness());
                assertEquals(1.7f, overridden.specularIor());
                assertEquals(0.6f, overridden.transmissionWeight());
            }
        }
    }

    private static MinecraftMaterialLookup overriddenMaterial(MinecraftMaterialRule.Parameters parameters) {
        return overriddenMaterial(parameters, false);
    }

    @Test
    void authoredSurfaceWeightsHaveUnitMultipliers() {
        var parameters = new MinecraftMaterialRule.Parameters(null, null, null, null, null, null);
        var lookup = overriddenMaterial(parameters, true);
        var record = lookup.records().get(lookup.resolve(ResourceId.of("test", "material")).materialIndex());
        assertEquals(1.0f, record.specularRoughness());
        assertEquals(1.0f, record.baseMetalness());
        assertEquals(1.0f, record.subsurfaceWeight());
        var level = lookup.textures().get(record.surface0Texture()).levels().getFirst();
        int x = Math.round(record.materialUv().u() * level.width());
        int y = Math.round(record.materialUv().v() * level.height());
        float textureWeight = Byte.toUnsignedInt(level.rgba8()[(y * level.width() + x) * 4 + 3]) / 255.0f;
        assertEquals(0.6f, record.subsurfaceWeight() * textureWeight, 1.0f / 255.0f);

        var untextured = overriddenMaterial(parameters);
        assertEquals(0.0f, untextured.records().get(
                untextured.resolve(ResourceId.of("test", "material")).materialIndex()).subsurfaceWeight());
        assertEquals(0.0f, MinecraftMaterialRecord.waterBoundary().subsurfaceWeight());
        assertEquals(0.0f, MinecraftMaterialRecord.fallback().subsurfaceWeight());
    }

    private static MinecraftMaterialLookup overriddenMaterial(MinecraftMaterialRule.Parameters parameters,
                                                               boolean surfaceParameters) {
        var id = ResourceId.of("test", "material");
        var resource = new MaterialTextureResource(id, MaterialTextureKind.STANDALONE,
                new MaterialTextureAnalysisSource(1, 1, 1, () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xFFFFFFFF; }
                    @Override public int alphaArgb(int frame, int x, int y) { return 0xFFFFFFFF; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
                        out.subsurfaceWeight = 0.6f;
                    }
                    @Override public void close() { }
                }), MaterialUv.IDENTITY, surfaceParameters, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.PARAMETER_DEFAULT, 1.31f, 0.0f);
        return MinecraftMaterialLookup.compile(new ResourcePackEpoch(7),
                List.of(new MinecraftMaterialRule(id, id, null, parameters)), List.of(resource),
                MinecraftMaterialPageCompiler.compile(List.of(resource)));
    }

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
