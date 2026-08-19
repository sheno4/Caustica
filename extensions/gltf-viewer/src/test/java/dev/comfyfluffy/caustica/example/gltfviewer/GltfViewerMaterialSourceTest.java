package dev.comfyfluffy.caustica.example.gltfviewer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GltfViewerMaterialSourceTest {
    @Test
    void materialSourceOwnsRepositoryReloadAndClearLifecycle() {
        AtomicInteger loads = new AtomicInteger();
        GltfViewerScene scene = new GltfViewerScene(List.of(), List.of(), List.of(), List.of());
        GltfViewerAssetRepository assets = new GltfViewerAssetRepository(() -> {
            loads.incrementAndGet();
            return scene;
        });
        GltfViewerMaterialSource source = new GltfViewerMaterialSource(assets);

        assertThrows(IllegalStateException.class, () -> source.submitMaterials(ignoredSink()));
        source.onResourcePackApplied();
        source.submitMaterials(ignoredSink());
        assertEquals(1, loads.get());

        source.onResourcePackClosing();
        assertThrows(IllegalStateException.class, () -> source.submitMaterials(ignoredSink()));
    }

    private static dev.comfyfluffy.caustica.api.provider.MaterialSink ignoredSink() {
        return new dev.comfyfluffy.caustica.api.provider.MaterialSink() {
            @Override public void define(dev.comfyfluffy.caustica.api.provider.MaterialDefinition definition) { }
            @Override public void submit(dev.comfyfluffy.caustica.api.provider.MaterialRule rule) { }
            @Override public void submitAsset(dev.comfyfluffy.caustica.api.provider.MaterialTextureAsset asset) { }
        };
    }
}
