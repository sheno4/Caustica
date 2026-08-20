package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialEmission;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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
        assertEquals(first.resolve(key).definition().handle(), second.resolve(key).definition().handle());
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
        var material = new SceneMesh.NamedMaterial(resolved.definition().handle());

        assertFalse(published.resolve(material).emissive());
        assertNull(published.opacityMicromapRange(material));
    }

    @Test
    void resolverSelectorIntroducesGeometryCaseAndStoresItsCompleteResolution() {
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        java.util.concurrent.atomic.AtomicReference<MinecraftMaterialResolution> selected =
                new java.util.concurrent.atomic.AtomicReference<>();
        extensions.registerMaterialResolver(ResourceId.of("test", "block_resolver"), 0,
                List.of(new MinecraftMaterialSelector(MATERIAL, GEOMETRY)), request -> {
                    if (request.profile() != MinecraftMaterialProfile.MEDIUM_ROUGH_DIELECTRIC
                            || request.requestedTopology() != MaterialTopology.MEDIUM_BOUNDARY) return null;
                    MinecraftMaterialResolution resolution = new MinecraftMaterialResolution(
                            request.fallback().definition(),
                            new MinecraftMaterialEmission(42.0f, false, request.fallback().emission().footprint()),
                            new SceneMesh.OpacityMicromapRange(0.2f, 0.7f));
                    selected.set(resolution);
                    return resolution;
                });
        extensions.freeze();
        MinecraftResolvedMaterialCatalog catalog = catalog(List.of(), resource(20.0f), extensions);
        MinecraftMaterialKey key = new MinecraftMaterialKey(MATERIAL, GEOMETRY,
                MinecraftMaterialProfile.MEDIUM_ROUGH_DIELECTRIC, MaterialTopology.MEDIUM_BOUNDARY);

        assertEquals(20, catalog.definitions().size());
        assertSame(selected.get(), catalog.resolve(key));
        assertSame(catalog.resolve(key).definition(),
                catalog.definitions().stream().filter(definition ->
                        definition.handle().equals(catalog.resolve(key).definition().handle())).findFirst().orElseThrow());
        var published = new MinecraftMaterialSnapshot.Published(catalog, availableSnapshot());
        var consumerResolution = published.resolve(key, new SceneMesh.AtlasTexture(ResourceId.of("test", "atlas")));
        assertSame(selected.get().emission(), consumerResolution.emission());
        assertSame(selected.get().opacityMicromapRange(), consumerResolution.opacityMicromapRange());
        assertSame(selected.get().emission(), published.resolve(consumerResolution.material()));
        assertSame(selected.get().opacityMicromapRange(),
                published.opacityMicromapRange(consumerResolution.material()));
    }

    private static MinecraftResolvedMaterialCatalog catalog(List<MinecraftMaterialRule> rules,
                                                              MaterialTextureResource resource) {
        return catalog(rules, resource, emptyExtensions());
    }

    private static MinecraftResolvedMaterialCatalog catalog(List<MinecraftMaterialRule> rules,
                                                              MaterialTextureResource resource,
                                                              MinecraftExtensionRegistry extensions) {
        var compiled = new MinecraftMaterialPageCompiler.CompiledMaterial(MaterialProviderData.ZERO, 0);
        var pages = new MinecraftMaterialPageCompiler.Result(Map.of(MATERIAL, compiled), compiled);
        return MinecraftResolvedMaterialCatalog.build(List.of(), rules, List.of(resource), pages, SURFACE,
                ignored -> 1, extensions);
    }

    private static MinecraftExtensionRegistry emptyExtensions() {
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        extensions.freeze();
        return extensions;
    }

    private static MaterialSnapshot availableSnapshot() {
        return new MaterialSnapshot() {
            @Override public long epoch() { return 1; }
            @Override public boolean surfaceAvailable(ResourceId surface) { return true; }
        };
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
