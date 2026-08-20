package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.MaterialDefinition;
import dev.comfyfluffy.caustica.api.provider.MaterialHandle;
import dev.comfyfluffy.caustica.api.provider.MaterialSink;
import dev.comfyfluffy.caustica.api.provider.MaterialTopology;
import dev.comfyfluffy.caustica.api.provider.TextureResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GltfViewerMaterialSourceTest {
    @Test
    void ownsRepositoryReloadAndClearLifecycle() {
        AtomicInteger loads = new AtomicInteger();
        GltfViewerScene scene = new GltfViewerScene(List.of(), List.of(), List.of(), List.of());
        GltfViewerAssetRepository assets = new GltfViewerAssetRepository(() -> {
            loads.incrementAndGet();
            return scene;
        });
        GltfViewerMaterialSource source = new GltfViewerMaterialSource(assets);
        Capture sink = new Capture();

        assertThrows(IllegalStateException.class, () -> source.submitMaterials(sink));
        source.onResourcePackApplied();
        source.submitMaterials(sink);
        assertEquals(1, loads.get());

        source.onResourcePackClosing();
        assertThrows(IllegalStateException.class, () -> source.submitMaterials(sink));
    }

    @Test
    void registersSemanticTexturesAndPublishesTheirSlotsAsOpaqueProviderData() {
        CpuTextureResource metallicRoughness = texture(CpuTextureResource.Encoding.LINEAR, 1);
        CpuTextureResource normal = texture(CpuTextureResource.Encoding.LINEAR, 2);
        CpuTextureResource emissive = texture(CpuTextureResource.Encoding.SRGB, 3);
        MaterialDefinition definition = new MaterialDefinition(
                new MaterialHandle(ResourceId.of("test", "material")),
                0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 1.5f, 0.1f,
                MaterialTopology.SURFACE, GltfViewerExtension.MATERIAL_SURFACE);
        GltfViewerScene scene = new GltfViewerScene(List.of(), List.of(),
                List.of(new GltfViewerScene.Material(
                        definition, metallicRoughness, normal, emissive, 0.75f)), List.of());
        GltfViewerAssetRepository repository = new GltfViewerAssetRepository(() -> scene);
        repository.reload();
        Capture sink = new Capture();

        new GltfViewerMaterialSource(repository).submitMaterials(sink);

        assertEquals(List.of(metallicRoughness, normal, emissive), sink.textures);
        assertEquals(1, sink.definitions.size());
        MaterialDefinition published = sink.definitions.getFirst();
        assertEquals(GltfViewerExtension.MATERIAL_SURFACE, published.surface());
        assertEquals(GltfViewerMaterialSource.METALLIC_ROUGHNESS_PRESENT
                        | GltfViewerMaterialSource.NORMAL_PRESENT
                        | GltfViewerMaterialSource.EMISSIVE_PRESENT,
                published.providerData().word(0));
        assertEquals(17, published.providerData().word(1));
        assertEquals(18, published.providerData().word(2));
        assertEquals(19, published.providerData().word(3));
        assertEquals(Float.floatToRawIntBits(0.75f), published.providerData().word(4));
        for (int index = 5; index < 12; index++) {
            assertEquals(0, published.providerData().word(index));
        }
    }

    @Test
    void leavesAbsentSemanticTextureSlotsUnregisteredAndClear() {
        MaterialDefinition definition = new MaterialDefinition(
                new MaterialHandle(ResourceId.of("test", "plain")),
                1, 1, 1, 1, 0, 1.5f, 0,
                MaterialTopology.SURFACE, GltfViewerExtension.MATERIAL_SURFACE);
        GltfViewerScene scene = new GltfViewerScene(List.of(), List.of(),
                List.of(new GltfViewerScene.Material(definition, null, null, null, 1.0f)), List.of());
        GltfViewerAssetRepository repository = new GltfViewerAssetRepository(() -> scene);
        repository.reload();
        Capture sink = new Capture();

        new GltfViewerMaterialSource(repository).submitMaterials(sink);

        assertEquals(List.of(), sink.textures);
        assertEquals(0, sink.definitions.getFirst().providerData().word(0));
        assertEquals(0, sink.definitions.getFirst().providerData().word(1));
        assertEquals(0, sink.definitions.getFirst().providerData().word(2));
        assertEquals(0, sink.definitions.getFirst().providerData().word(3));
        assertEquals(Float.floatToRawIntBits(1.0f),
                sink.definitions.getFirst().providerData().word(4));
    }

    private static CpuTextureResource texture(CpuTextureResource.Encoding encoding, int value) {
        return new CpuTextureResource(1, 1, encoding,
                new byte[]{(byte) value, 0, 0, (byte) 255});
    }

    private static final class Capture implements MaterialSink {
        private final List<TextureResource> textures = new ArrayList<>();
        private final List<MaterialDefinition> definitions = new ArrayList<>();

        @Override
        public int register(TextureResource resource) {
            textures.add(resource);
            return 17 + textures.size() - 1;
        }

        @Override
        public void define(MaterialDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public void onCommit(Runnable action) {
        }
    }
}
