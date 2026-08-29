package dev.comfyfluffy.caustica.example.gltfviewer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class GltfViewerAssetRepositoryTest {
    @Test
    void reloadReplacesOneResourceEpochAndClearReleasesCpuSnapshot() {
        AtomicInteger loads = new AtomicInteger();
        GltfViewerScene scene = new GltfViewerScene(List.of(), List.of());
        GltfViewerAssetRepository repository = new GltfViewerAssetRepository(() -> {
            loads.incrementAndGet();
            return scene;
        });

        assertThrows(IllegalStateException.class, repository::current);
        repository.reload();
        assertEquals(scene, repository.current());
        assertEquals(1, loads.get());
        repository.clear();
        assertThrows(IllegalStateException.class, repository::current);
    }
}
