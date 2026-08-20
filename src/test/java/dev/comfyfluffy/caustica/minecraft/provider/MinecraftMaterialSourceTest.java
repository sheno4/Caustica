package dev.comfyfluffy.caustica.minecraft.provider;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialRule;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialSnapshot;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureAnalysisSource;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureImage;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureKind;
import dev.comfyfluffy.caustica.api.provider.MaterialTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.MaterialUv;
import dev.comfyfluffy.caustica.api.provider.OpenPbrColorBinding;
import dev.comfyfluffy.caustica.api.provider.OpenPbrMaterialDefaults;
import dev.comfyfluffy.caustica.api.provider.OpenPbrTextureTexel;
import dev.comfyfluffy.caustica.minecraft.MinecraftProvidersExtension;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialClassifier;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialEmissionState;
import dev.comfyfluffy.caustica.minecraft.material.MinecraftMaterialEmissionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftMaterialSourceTest {
    @Test
    void namedWaterMaterialSelectsTheMinecraftProceduralSurface() {
        MaterialDefinition material = MinecraftMaterialSource.waterDefinition();

        assertEquals(MinecraftMaterialSource.WATER, material.handle().id());
        assertEquals(1.0f, material.baseColorR());
        assertEquals(1.0f, material.baseColorG());
        assertEquals(1.0f, material.baseColorB());
        assertEquals(OpenPbrMaterialDefaults.TRANSMISSIVE_SPECULAR_ROUGHNESS,
                material.specularRoughness());
        assertEquals(MinecraftMaterialClassifier.WATER_IOR, material.specularIor());
        assertEquals(1.0f, material.transmissionWeight());
        assertEquals(MinecraftProvidersExtension.WATER_SURFACE, material.surface());
    }

    @Test
    void namedParticleBillboardOwnsTheThinSurfaceCalibration() {
        MaterialDefinition material = MinecraftMaterialSource.particleBillboardDefinition();

        assertEquals(MinecraftMaterialSource.PARTICLE_BILLBOARD, material.handle().id());
        assertEquals(MaterialTopology.SURFACE, material.topology());
        assertEquals(1.0f, material.specularRoughness());
        assertEquals(0.0f, material.baseMetalness());
        assertEquals(1.0f, material.specularIor());
        assertEquals(0.5f, material.transmissionWeight());
        org.junit.jupiter.api.Assertions.assertNull(material.surface());
    }

    @Test
    void namedEndPortalMaterialSelectsTheProceduralSurface() {
        MaterialDefinition material = MinecraftMaterialSource.endPortalDefinition();

        assertEquals(MinecraftMaterialSource.END_PORTAL, material.handle().id());
        assertEquals(MaterialTopology.SURFACE, material.topology());
        assertEquals(0.0f, material.transmissionWeight());
        assertEquals(MinecraftProvidersExtension.END_PORTAL_SURFACE, material.surface());
    }

    @Test
    void stagesGpuResourcesAndPublishesTheirMatchingEmissionCatalog() {
        MinecraftMaterialEmissionState state = new MinecraftMaterialEmissionState();
        MinecraftMaterialSource source = new MinecraftMaterialSource(state);
        MaterialTextureResource resource = resource(75.0f);
        RecordingSink sink = new RecordingSink();

        source.stageEpoch(sink, List.of(), List.of(), List.of(resource));

        assertEquals(List.of(resource), sink.resources);
        assertTrue(state.snapshot().resolve(resource.material(), null, true, ignored -> true).emissive());
        assertSame(state.snapshot(), source.emissionSnapshot());
    }

    @Test
    void failedSubmissionKeepsTheLastCatalogPairableWithTheNextEngineEpoch() {
        MinecraftMaterialEmissionState state = new MinecraftMaterialEmissionState();
        MinecraftMaterialSource source = new MinecraftMaterialSource(state);
        source.stageEpoch(new RecordingSink(), List.of(), List.of(), List.of(resource(25.0f)));
        var published = state.snapshot();

        assertThrows(IllegalStateException.class, () -> source.stageEpoch(new RecordingSink() {
            @Override
            public void submitResource(MaterialTextureResource resource) {
                throw new IllegalStateException("submission failed");
            }
        }, List.of(), List.of(), List.of(resource(50.0f))));

        assertSame(published, state.snapshot());

        MaterialSnapshot compiled = new MaterialSnapshot() {
            @Override public long epoch() { return 37; }
            @Override public boolean surfaceAvailable(ResourceId surface) { return true; }
        };
        MinecraftMaterialEmissionSnapshot.Published paired =
                new MinecraftMaterialEmissionSnapshot.Published(state.snapshot(), compiled);
        assertEquals(37, paired.epoch());
        assertSame(published, paired.semantics());
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

    private static class RecordingSink implements MaterialSink {
        private final List<MaterialDefinition> definitions = new ArrayList<>();
        private final List<MaterialRule> rules = new ArrayList<>();
        private final List<MaterialTextureResource> resources = new ArrayList<>();

        @Override
        public int register(dev.comfyfluffy.caustica.api.provider.TextureResource resource) {
            return 1;
        }

        @Override
        public void define(MaterialDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public void submit(MaterialRule rule) {
            rules.add(rule);
        }

        @Override
        public void submitResource(MaterialTextureResource resource) {
            resources.add(resource);
        }
    }
}
