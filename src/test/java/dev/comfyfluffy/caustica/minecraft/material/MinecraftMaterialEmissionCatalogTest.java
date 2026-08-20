package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureImage;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureKind;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.MaterialUv;
import dev.comfyfluffy.caustica.api.provider.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.api.provider.OpenPbrTextureTexel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialEmissionCatalogTest {
    private static final ResourceId MATERIAL = id("material");
    private static final ResourceId GEOMETRY = id("geometry");

    @Test
    void authoredAndDerivedMasksUseTheSamePrimitiveGatedEquation() throws Exception {
        MaterialTextureResource authored = resource(id("authored"), 0xFF804020, true, 100.0f,
                texel -> texel.emissionWeight = 0.25f);
        MaterialTextureResource derived = resource(id("derived"), 0xFF804020, true, 100.0f,
                texel -> texel.emissionWeight = 0.25f);

        MinecraftMaterialEmissionAnalyzer.Scan authoredScan = MinecraftMaterialEmissionAnalyzer.scan(authored);
        MinecraftMaterialEmissionAnalyzer.Scan derivedScan = MinecraftMaterialEmissionAnalyzer.scan(derived);
        assertNotNull(authoredScan.masked());
        assertEquals(authoredScan.masked().r(0, 0), derivedScan.masked().r(0, 0));
        assertEquals(0.25f, authoredScan.masked().weight(0, 0));
        assertEquals(linear(0x80) * 0.25f, authoredScan.masked().r(0, 0), 1.0e-6f);

        MinecraftMaterialEmissionSnapshot snapshot = MinecraftMaterialEmissionSnapshot.build(
                List.of(), List.of(), List.of(authored, derived));
        assertTrue(snapshot.resolve(authored.material(), null, true, ignored -> true).usesPrimitiveEmission());
        assertTrue(snapshot.resolve(derived.material(), null, true, ignored -> true).usesPrimitiveEmission());
        assertFalse(snapshot.resolve(authored.material(), null, false, ignored -> true).emissive());
    }

    @Test
    void uniformScanUsesLinearBaseColorAndCoverage() throws Exception {
        MaterialTextureResource resource = resource(MATERIAL, 0x80804020, false, 40.0f, ignored -> { });
        MinecraftEmissionFootprint footprint = MinecraftMaterialEmissionAnalyzer.scan(resource).uniform();

        assertNotNull(footprint);
        assertEquals(0x80 / 255.0f, footprint.weight(0, 0), 1.0e-6f);
        assertEquals(linear(0x80) * 0x80 / 255.0f, footprint.r(0, 0), 1.0e-6f);
    }

    @Test
    void namedUniformEmissionIgnoresPrimitiveStateWithOrWithoutATextureMask() {
        MaterialDefinition uniform = definition(id("uniform"), 80.0f, null, null);
        MaterialTextureResource maskedResource = resource(id("masked_named"), 0xFFFFFFFF,
                true, 0.0f, texel -> texel.emissionWeight = 1.0f);
        MaterialDefinition masked = definition(maskedResource.material(), 90.0f, null, maskedResource);
        MinecraftMaterialEmissionSnapshot snapshot = MinecraftMaterialEmissionSnapshot.build(
                List.of(uniform, masked), List.of(), List.of(maskedResource));

        assertFalse(snapshot.resolve(uniform.id(), null, true, ignored -> true).usesPrimitiveEmission());
        assertFalse(snapshot.resolve(masked.id(), null, true, ignored -> true).usesPrimitiveEmission());
    }

    @Test
    void geometrySpecificRuleWinsThenFirstMaterialWideRuleAndCanEnableEmission() {
        MaterialTextureResource resource = resource(MATERIAL, 0xFFFFFFFF, false, 0.0f, ignored -> { });
        MaterialRule wide = rule("wide", MATERIAL, null, 20.0f, null);
        MaterialRule specific = rule("specific", MATERIAL, GEOMETRY, 30.0f, null);
        MaterialRule laterSpecific = rule("later", MATERIAL, GEOMETRY, 40.0f, null);
        MinecraftMaterialEmissionSnapshot snapshot = MinecraftMaterialEmissionSnapshot.build(
                List.of(), List.of(wide, specific, laterSpecific), List.of(resource));

        var exact = snapshot.resolve(MATERIAL, GEOMETRY, true, ignored -> true);
        var general = snapshot.resolve(MATERIAL, id("other"), true, ignored -> true);
        assertEquals(30.0f, exact.luminanceCdM2());
        assertEquals(20.0f, general.luminanceCdM2());
        assertTrue(exact.usesPrimitiveEmission());
        assertTrue(exact.emissive());
    }

    @Test
    void resolvedRuleSurfaceIsCheckedWithoutEmbeddingRendererSemantics() {
        ResourceId baseSurface = id("base_surface");
        ResourceId overrideSurface = id("override_surface");
        MaterialDefinition definition = definition(MATERIAL, 50.0f, baseSurface, null);
        MaterialRule rule = rule("surface", MATERIAL, GEOMETRY, null, overrideSurface);
        MinecraftMaterialEmissionSnapshot snapshot = MinecraftMaterialEmissionSnapshot.build(
                List.of(definition), List.of(rule), List.of());

        assertFalse(snapshot.resolve(MATERIAL, GEOMETRY, true,
                surface -> !surface.equals(overrideSurface)).emissive());
        assertFalse(snapshot.resolve(MATERIAL, id("other"), true, surface -> false).emissive());
        assertTrue(snapshot.resolve(MATERIAL, id("other"), true,
                surface -> surface.equals(baseSurface)).emissive());
    }

    private static MaterialTextureResource resource(ResourceId id, int albedo, boolean emissionMask,
                                                    float luminance, Consumer<OpenPbrTextureTexel> semantic) {
        MaterialTextureAnalysisSource source = new MaterialTextureAnalysisSource(1, 1, 1,
                () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return albedo; }
                    @Override public int alphaArgb(int frame, int x, int y) { return albedo; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) {
                        semantic.accept(out);
                    }
                    @Override public void close() { }
                });
        return new MaterialTextureResource(id, MaterialTextureKind.STANDALONE, source,
                MaterialUv.IDENTITY, false, false, emissionMask,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.BASE_COLOR,
                OpenPbrMaterialDefaults.DEFAULT_SPECULAR_IOR, luminance);
    }

    private static MaterialDefinition definition(ResourceId id, float luminance, ResourceId surface,
                                                 MaterialTextureResource resource) {
        return new MaterialDefinition(new MaterialHandle(id), 1, 1, 1, 1, 0, 1.5f, 0,
                1, 1, 1, 0, 0.8f, 0.8f, 0.8f, 0,
                0.2f, 0.4f, 0.6f, luminance, MaterialTopology.SURFACE, surface, 0.5f, resource);
    }

    private static MaterialRule rule(String name, ResourceId material, ResourceId geometry,
                                     Float luminance, ResourceId surface) {
        return new MaterialRule(id(name), new MaterialRule.Match(material, geometry),
                new MaterialRule.Parameters(null, null, null, null, luminance, surface, null));
    }

    private static float linear(int channel) {
        float value = channel / 255.0f;
        return value <= 0.04045f ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }
}
