package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import dev.comfyfluffy.caustica.minecraft.api.MinecraftSceneReset;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class ProceduralSurfaceSceneProviderTest {
    @Test
    void retainsOneCubeAndUsesBlocksOnlyAsWorldTransforms() {
        AtomicReference<Set<BlockPos>> anchors = new AtomicReference<>(Set.of(
                new BlockPos(4, 5, 6), new BlockPos(-3, 2, 9)));
        ProceduralSurfaceSceneProvider provider = new ProceduralSurfaceSceneProvider(anchors::get);
        Capture capture = new Capture();
        provider.onSessionStart(capture);

        provider.prepareFrame();

        assertEquals(3, capture.last.size());
        SceneGeometrySink.Put put = assertInstanceOf(SceneGeometrySink.Put.class, capture.last.getFirst());
        assertEquals(12, put.mesh().triangleCount());
        List<SceneGeometrySink.Place> places = capture.last.stream()
                .filter(SceneGeometrySink.Place.class::isInstance)
                .map(SceneGeometrySink.Place.class::cast)
                .toList();
        assertEquals(2, places.size());
        assertEquals(Set.of(4.0, -3.0), places.stream()
                .map(place -> place.transform().translationX()).collect(java.util.stream.Collectors.toSet()));

        anchors.set(Set.of());
        provider.prepareFrame();
        assertEquals(3, capture.last.size());
        assertEquals(2, capture.last.stream().filter(SceneGeometrySink.Remove.class::isInstance).count());
        assertEquals(1, capture.last.stream().filter(SceneGeometrySink.Drop.class::isInstance).count());

        anchors.set(Set.of(new BlockPos(4, 5, 6)));
        MinecraftSceneReset.request();
        provider.prepareFrame();
        assertEquals(1, capture.resetRequests);
        assertInstanceOf(SceneGeometrySink.Put.class, capture.last.getFirst());
        provider.stop();
    }

    private static final class Capture implements SceneScope {
        private List<Operation> last = List.of();
        private int resetRequests;

        @Override
        public void submit(SceneGeometryKey groupKey, List<Operation> operations, Runnable onPublished) {
            last = new ArrayList<>(operations);
            onPublished.run();
        }

        @Override
        public void requestSceneReset() {
            resetRequests++;
        }
    }
}
