package dev.comfyfluffy.caustica.minecraft.api;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialProviderData;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftExtensionRegistryTest {
    @Test
    void resolvesByPriorityThenIdWithExplicitFallthrough() {
        MinecraftExtensionRegistry registry = new MinecraftExtensionRegistry();
        List<String> calls = new ArrayList<>();
        registry.registerMaterialResolver(id("highest"), 20, selectors(null, null), request -> {
            calls.add("highest");
            return null;
        });
        registry.registerMaterialResolver(id("z"), 10, selectors(null, null), request -> {
            calls.add("z");
            return resolution(request.fallback().definition(), 0.8f);
        });
        registry.registerMaterialResolver(id("a"), 10, selectors(null, null), request -> {
            calls.add("a");
            return resolution(request.fallback().definition(), 0.2f);
        });
        registry.freeze();

        MinecraftMaterialResolution resolved = registry.resolve(request());

        assertEquals(List.of("highest", "a"), calls);
        assertEquals(0.2f, resolved.emission().luminanceCdM2());
    }

    @Test
    void resolverFailureAndInvalidHandleContinueToDefault() {
        MinecraftExtensionRegistry registry = new MinecraftExtensionRegistry();
        registry.registerMaterialResolver(id("throws"), 2, selectors(null, null), request -> {
            throw new IllegalStateException("broken resolver");
        });
        registry.registerMaterialResolver(id("wrong_handle"), 1, selectors(null, null), request ->
                resolution(definition("other"), 1.0f));
        registry.freeze();
        MinecraftMaterialRequest request = request();

        assertSame(request.fallback(), registry.resolve(request));
    }

    @Test
    void rejectsDuplicatesAndMutationOutsideTheBootstrapWindow() {
        MinecraftExtensionRegistry registry = new MinecraftExtensionRegistry();
        registry.registerMaterialResolver(id("resolver"), 0, selectors(null, null), request -> null);
        assertThrows(IllegalStateException.class,
                () -> registry.registerMaterialResolver(id("resolver"), 1, selectors(null, null), request -> null));
        assertThrows(IllegalStateException.class, () -> registry.resolve(request()));

        registry.freeze();
        assertTrue(registry.frozen());
        assertThrows(IllegalStateException.class,
                () -> registry.registerMaterialResolver(id("late"), 0, selectors(null, null), request -> null));
        assertThrows(IllegalStateException.class, registry::freeze);
    }

    @Test
    void resolverCanRegisterAnEpochTextureAndPublishItsSlotInProviderData() {
        MinecraftExtensionRegistry registry = new MinecraftExtensionRegistry();
        registry.registerMaterialResolver(id("textured"), 0, selectors(null, null), request -> {
            int slot = request.textures().register(new dev.comfyfluffy.caustica.api.provider.TextureResource() { });
            int[] words = request.fallback().definition().providerData().words();
            words[0] = slot;
            return new MinecraftMaterialResolution(withProviderData(
                    request.fallback().definition(), new MaterialProviderData(words)),
                    request.fallback().emission(), request.fallback().opacityMicromapRange());
        });
        registry.freeze();

        assertEquals(7, registry.resolve(request()).definition().providerData().word(0));
    }

    @Test
    void selectorsFilterInvocationAndDeclareFiniteGeometryCases() {
        MinecraftExtensionRegistry registry = new MinecraftExtensionRegistry();
        List<String> calls = new ArrayList<>();
        registry.registerMaterialResolver(id("block"), 0,
                List.of(new MinecraftMaterialSelector(null, id("special_block"))), request -> {
                    calls.add("block");
                    return null;
                });
        registry.registerMaterialResolver(id("texture"), 0,
                List.of(new MinecraftMaterialSelector(id("other_material"), null)), request -> {
                    calls.add("texture");
                    return null;
                });
        registry.freeze();

        assertEquals(java.util.Set.of(id("special_block")), registry.geometryCases(id("material")));
        registry.resolve(request());
        assertEquals(List.of(), calls);

        MinecraftMaterialRequest blockRequest = new MinecraftMaterialRequest(id("material"), id("special_block"),
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE,
                resource -> 7, request().fallback());
        registry.resolve(blockRequest);
        assertEquals(List.of("block"), calls);
    }

    private static MinecraftMaterialRequest request() {
        MaterialDefinition definition = definition("default");
        return new MinecraftMaterialRequest(id("material"), id("geometry"),
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE,
                resource -> 7, resolution(definition, 0.0f));
    }

    private static MinecraftMaterialResolution resolution(MaterialDefinition definition, float luminance) {
        return new MinecraftMaterialResolution(definition,
                new MinecraftMaterialEmission(luminance, true, luminance > 0.0f ? FOOTPRINT : null),
                new SceneMesh.OpacityMicromapRange(0.25f, 0.75f));
    }

    private static MaterialDefinition withProviderData(MaterialDefinition source, MaterialProviderData data) {
        return new MaterialDefinition(source.handle(),
                source.baseColorR(), source.baseColorG(), source.baseColorB(),
                source.specularRoughness(), source.baseMetalness(), source.specularIor(),
                source.transmissionWeight(), source.transmissionColorR(), source.transmissionColorG(),
                source.transmissionColorB(), source.subsurfaceWeight(), source.subsurfaceColorR(),
                source.subsurfaceColorG(), source.subsurfaceColorB(), source.subsurfaceScatterAnisotropy(),
                source.emissionColorR(), source.emissionColorG(), source.emissionColorB(),
                source.emissionLuminanceCdM2(), source.topology(), source.surface(), source.alphaCutoff(), data);
    }

    private static MaterialDefinition definition(String path) {
        return new MaterialDefinition(new MaterialHandle(id(path)),
                1, 1, 1, 0.5f, 0, 1.5f, 0,
                MaterialTopology.SURFACE, id("surface"));
    }

    private static ResourceId id(String path) {
        return ResourceId.of("test", path);
    }

    private static List<MinecraftMaterialSelector> selectors(ResourceId material, ResourceId geometry) {
        return List.of(new MinecraftMaterialSelector(material, geometry));
    }

    private static final MinecraftEmissionFootprint FOOTPRINT = new MinecraftEmissionFootprint() {
        @Override public int resolution() { return 1; }
        @Override public int sampleIndex(float coordinate) { return 0; }
        @Override public float r(int x, int y) { return 1; }
        @Override public float g(int x, int y) { return 1; }
        @Override public float b(int x, int y) { return 1; }
        @Override public float weight(int x, int y) { return 1; }
    };
}
