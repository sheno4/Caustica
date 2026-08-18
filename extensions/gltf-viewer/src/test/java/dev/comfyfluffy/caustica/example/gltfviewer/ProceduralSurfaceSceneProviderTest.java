package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.provider.SceneCamera;
import dev.comfyfluffy.caustica.api.provider.SceneFrameContext;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
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

        provider.prepareFrame();
        provider.submitGeometry(frame(capture));

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
        provider.submitGeometry(frame(capture));
        assertEquals(3, capture.last.size());
        assertEquals(2, capture.last.stream().filter(SceneGeometrySink.Remove.class::isInstance).count());
        assertEquals(1, capture.last.stream().filter(SceneGeometrySink.Drop.class::isInstance).count());
    }

    private static SceneFrameContext frame(Capture capture) {
        return new SceneFrameContext(capture, 0, 0, 0, 0, SceneCamera.IDENTITY);
    }

    private static final class Capture implements SceneGeometrySink {
        private List<Operation> last = List.of();

        @Override
        public void submit(SceneGeometryKey groupKey, List<Operation> operations, Runnable onPublished) {
            last = new ArrayList<>(operations);
            onPublished.run();
        }
    }
}
