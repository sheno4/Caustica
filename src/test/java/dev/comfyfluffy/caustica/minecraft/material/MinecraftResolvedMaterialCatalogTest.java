package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class MinecraftResolvedMaterialCatalogTest {
    private static final ResourceId MATERIAL = ResourceId.of("test", "material");
    private static final ResourceId GEOMETRY = ResourceId.of("test", "geometry");
    private static final ResourceId SURFACE = ResourceId.of("caustica", "minecraft_material");

    @Test
    void generatesFiniteSupersetWithoutEmissionAxisAndStableHandles() {
        MaterialTextureResource resource = resource(20.0f);
        MinecraftResolvedMaterialCatalog first = catalog(List.of(), resource);
        MinecraftResolvedMaterialCatalog second = catalog(List.of(), resource);
        MinecraftMaterialKey key = new MinecraftMaterialKey(MATERIAL, null,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE);

        assertEquals(10, first.definitions().size());
        assertEquals(first.resolve(key).handle(), second.resolve(key).handle());
        assertEquals(20.0f, first.resolve(key).emission().luminanceCdM2());
    }

    @Test
    void geometryCasesUseFirstMatchingRuleAndAddTenDefinitionsEach() {
        var first = rule("first", 0.2f);
        var second = rule("second", 0.8f);
        MinecraftResolvedMaterialCatalog catalog = catalog(List.of(first, second), resource(0.0f));
        MinecraftMaterialKey key = new MinecraftMaterialKey(MATERIAL, GEOMETRY,
                MinecraftMaterialProfile.CONDUCTOR, MaterialTopology.SURFACE);

        assertEquals(20, catalog.definitions().size());
        assertEquals(0.2f, catalog.resolve(key).definition().specularRoughness());
    }

    @Test
    void unavailableSelectedSurfaceSuppressesLocalEmissionAndOmm() {
        MinecraftResolvedMaterialCatalog catalog = catalog(List.of(), resource(20.0f));
        MinecraftMaterialKey key = new MinecraftMaterialKey(MATERIAL, null,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE);
        var resolved = catalog.resolve(key);
        var published = new MinecraftMaterialSnapshot.Published(catalog, new MaterialSnapshot() {
            @Override public long epoch() { return 7; }
            @Override public boolean surfaceAvailable(ResourceId surface) { return false; }
        });
        var material = new SceneMesh.NamedMaterial(resolved.handle());

        assertFalse(published.resolve(material).emissive());
        assertNull(published.opacityMicromapRange(material));
    }

    private static MinecraftResolvedMaterialCatalog catalog(List<MinecraftMaterialRule> rules,
                                                              MaterialTextureResource resource) {
        var compiled = new MinecraftMaterialPageCompiler.CompiledMaterial(MaterialProviderData.ZERO, 0);
        var pages = new MinecraftMaterialPageCompiler.Result(Map.of(MATERIAL, compiled), compiled);
        return MinecraftResolvedMaterialCatalog.build(List.of(), rules, List.of(resource), pages, SURFACE);
    }

    private static MinecraftMaterialRule rule(String id, float roughness) {
        return new MinecraftMaterialRule(ResourceId.of("test", id), MATERIAL, GEOMETRY,
                new MinecraftMaterialRule.Parameters(roughness, null, null, null, null, null, null));
    }

    private static MaterialTextureResource resource(float luminance) {
        return new MaterialTextureResource(MATERIAL, MaterialTextureKind.STANDALONE,
                new MaterialTextureAnalysisSource(1, 1, 1, () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xffffffff; }
                    @Override public int alphaArgb(int frame, int x, int y) { return 0xffffffff; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
                    @Override public void close() { }
                }), MaterialUv.IDENTITY, false, false, false,
                OpenPbrColorBinding.PARAMETER_DEFAULT, OpenPbrColorBinding.BASE_COLOR, 1.5f, luminance);
    }
}
