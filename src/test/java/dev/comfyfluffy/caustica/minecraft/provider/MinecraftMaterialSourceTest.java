package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.minecraft.material.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.minecraft.material.MaterialTextureImage;
import dev.comfyfluffy.caustica.minecraft.material.MaterialTextureKind;
import dev.comfyfluffy.caustica.minecraft.material.MaterialTextureResource;
import dev.comfyfluffy.caustica.minecraft.material.MaterialUv;
import dev.comfyfluffy.caustica.minecraft.material.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.minecraft.material.OpenPbrTextureTexel;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialKey;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialProfile;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftExtensionRegistry;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialResolution;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftMaterialSelector;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class MinecraftMaterialSourceTest {
    @Test
    void fixedMaterialsSelectMinecraftOwnedSurfaces() {
        assertEquals(MinecraftProvidersExtension.MATERIAL_SURFACE,
                MinecraftMaterialSource.cloudDefinition().surface());
        MaterialDefinition water = MinecraftMaterialSource.waterDefinition();
        assertEquals(OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS, water.specularRoughness());
        assertEquals(MinecraftMaterialClassifier.WATER_IOR, water.specularIor());
        assertEquals(MinecraftProvidersExtension.WATER_SURFACE, water.surface());
        assertEquals(MinecraftProvidersExtension.MATERIAL_SURFACE,
                MinecraftMaterialSource.particleBillboardDefinition().surface());
        assertEquals(MinecraftProvidersExtension.END_PORTAL_SURFACE,
                MinecraftMaterialSource.endPortalDefinition().surface());
        assertEquals(MinecraftProvidersExtension.MATERIAL_SURFACE,
                MinecraftMaterialSource.vertexColorDefinition().surface());
    }

    @Test
    void compilesFiniteNamedSupersetAndPublishesOnlyOnCommit() {
        MinecraftMaterialState state = new MinecraftMaterialState();
        MinecraftMaterialSource source = new MinecraftMaterialSource(state, emptyExtensions());
        MaterialTextureResource resource = resource(75.0f);
        RecordingSink sink = new RecordingSink();
        var previous = state.snapshot();

        source.stageEpoch(sink, List.of(), List.of(), List.of(resource));

        assertSame(previous, state.snapshot());
        assertEquals(10, sink.definitions.size());
        sink.commit();
        var key = new MinecraftMaterialKey(resource.material(), null,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE);
        assertEquals(75.0f, state.snapshot().resolve(key).emission().luminanceCdM2());
        assertSame(state.snapshot(), source.materialSnapshot());
    }

    @Test
    void abandonedSourceTransactionKeepsPriorSnapshot() {
        MinecraftMaterialState state = new MinecraftMaterialState();
        MinecraftMaterialSource source = new MinecraftMaterialSource(state, emptyExtensions());
        RecordingSink first = new RecordingSink();
        source.stageEpoch(first, List.of(), List.of(), List.of(resource(25.0f)));
        first.commit();
        var published = state.snapshot();

        source.stageEpoch(new RecordingSink(), List.of(), List.of(), List.of(resource(50.0f)));

        assertSame(published, state.snapshot());
    }

    @Test
    void resolverTextureSurfaceAndProviderDataReachTheSubmittedDefinition() {
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        ResourceId customSurface = ResourceId.of("test", "custom_surface");
        java.util.concurrent.atomic.AtomicInteger registeredSlot = new java.util.concurrent.atomic.AtomicInteger();
        extensions.registerMaterialResolver(ResourceId.of("test", "custom"), 0,
                List.of(new MinecraftMaterialSelector(ResourceId.of("test", "emissive"), null)), request -> {
                    if (request.profile() != MinecraftMaterialProfile.ROUGH_DIELECTRIC
                            || request.requestedTopology() != MaterialTopology.SURFACE) return null;
                    int slot = request.textures().register(new CpuTextureResource(1, 1,
                            CpuTextureResource.Encoding.LINEAR, new byte[]{1, 2, 3, 4}));
                    registeredSlot.set(slot);
                    int[] words = request.fallback().definition().providerData().words();
                    words[11] = slot;
                    MaterialDefinition definition = withSurfaceAndData(request.fallback().definition(),
                            customSurface, new dev.comfyfluffy.caustica.api.provider.MaterialProviderData(words));
                    return new MinecraftMaterialResolution(definition, request.fallback().emission(),
                            request.fallback().opacityMicromapRange());
                });
        extensions.freeze();
        MinecraftMaterialState state = new MinecraftMaterialState();
        MinecraftMaterialSource source = new MinecraftMaterialSource(state, extensions);
        RecordingSink sink = new RecordingSink();
        source.stageEpoch(sink, List.of(), List.of(), List.of(resource(25.0f)));
        var key = new MinecraftMaterialKey(ResourceId.of("test", "emissive"), null,
                MinecraftMaterialProfile.ROUGH_DIELECTRIC, MaterialTopology.SURFACE);
        sink.commit();
        var resolved = source.materialSnapshot().resolve(key);
        MaterialDefinition submitted = sink.definitions.stream()
                .filter(definition -> definition.handle().equals(resolved.definition().handle()))
                .findFirst().orElseThrow();

        assertEquals(customSurface, submitted.surface());
        assertEquals(registeredSlot.get(), submitted.providerData().word(11));
        assertSame(submitted, resolved.definition());
    }

    private static MaterialDefinition withSurfaceAndData(MaterialDefinition source, ResourceId surface,
            dev.comfyfluffy.caustica.api.provider.MaterialProviderData data) {
        return new MaterialDefinition(source.handle(), source.baseColorR(), source.baseColorG(), source.baseColorB(),
                source.specularRoughness(), source.baseMetalness(), source.specularIor(), source.transmissionWeight(),
                source.transmissionColorR(), source.transmissionColorG(), source.transmissionColorB(),
                source.subsurfaceWeight(), source.subsurfaceColorR(), source.subsurfaceColorG(),
                source.subsurfaceColorB(), source.subsurfaceScatterAnisotropy(), source.emissionColorR(),
                source.emissionColorG(), source.emissionColorB(), source.emissionLuminanceCdM2(), source.topology(),
                surface, source.alphaCutoff(), data);
    }

    private static MinecraftExtensionRegistry emptyExtensions() {
        MinecraftExtensionRegistry extensions = new MinecraftExtensionRegistry();
        extensions.freeze();
        return extensions;
    }

    private static MaterialTextureResource resource(float luminance) {
        MaterialTextureAnalysisSource analysis = new MaterialTextureAnalysisSource(1, 1, 1,
                () -> new MaterialTextureImage() {
                    @Override public int width() { return 1; }
                    @Override public int height() { return 1; }
                    @Override public int albedoArgb(int x, int y) { return 0xFFFFFFFF; }
                    @Override public int alphaArgb(int frame, int x, int y) { return 0xFFFFFFFF; }
                    @Override public void readOpenPbr(int x, int y, OpenPbrTextureTexel out) { }
                    @Override public void close() { }
                });
        return new MaterialTextureResource(ResourceId.of("test", "emissive"),
                MaterialTextureKind.STANDALONE, analysis, MaterialUv.IDENTITY,
                false, false, false, OpenPbrColorBinding.PARAMETER_DEFAULT,
                OpenPbrColorBinding.BASE_COLOR, 1.5f, luminance);
    }

    private static final class RecordingSink implements MaterialSink {
        private final List<MaterialDefinition> definitions = new ArrayList<>();
        private final List<Runnable> commits = new ArrayList<>();
        private int nextSlot = 1;
        @Override public int register(TextureResource resource) {
            if (!(resource instanceof CpuTextureResource)) throw new AssertionError(resource);
            return nextSlot++;
        }
        @Override public void define(MaterialDefinition definition) { definitions.add(definition); }
        @Override public void onCommit(Runnable action) { commits.add(action); }
        void commit() { commits.forEach(Runnable::run); }
    }
}
