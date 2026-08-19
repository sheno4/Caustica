package dev.comfyfluffy.caustica.example.gltfviewer;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneScope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

final class GltfViewerSceneProviderTest {
    @Test
    void retainsPrimitivesOnceAndPlacesEveryAuthoredNodeAtEveryAnchor() {
        AtomicReference<Set<BlockPos>> anchors = new AtomicReference<>(Set.of(
                new BlockPos(10, 20, 30), new BlockPos(-4, 5, -6)));
        GltfViewerSceneProvider provider = new GltfViewerSceneProvider(scene(), anchors::get);
        Capture capture = new Capture();
        provider.onSessionStart(capture);

        provider.prepareFrame();

        assertEquals(5, capture.last.size());
        assertInstanceOf(SceneGeometrySink.Put.class, capture.last.getFirst());
        List<SceneGeometrySink.Place> places = capture.last.stream()
                .filter(SceneGeometrySink.Place.class::isInstance)
                .map(SceneGeometrySink.Place.class::cast)
                .toList();
        assertEquals(4, places.size());
        assertEquals(Set.of(SceneGeometryKey.of(7)), places.stream()
                .map(SceneGeometrySink.Place::residentKey).collect(java.util.stream.Collectors.toSet()));

        SceneGeometrySink.Place authored = places.stream()
                .filter(place -> place.transform().translationX() == 11.0
                        && place.transform().translationY() == 22.0
                        && place.transform().translationZ() == 33.0)
                .findFirst().orElseThrow();
        assertEquals(2.0f, authored.transform().m00());
        assertEquals(3.0f, authored.transform().m11());
        assertEquals(4.0f, authored.transform().m22());

        anchors.set(Set.of(new BlockPos(10, 20, 30)));
        provider.prepareFrame();
        assertEquals(2, capture.last.size());
        capture.last.forEach(operation -> assertInstanceOf(SceneGeometrySink.Remove.class, operation));

        anchors.set(Set.of());
        provider.prepareFrame();
        assertEquals(3, capture.last.size());
        assertEquals(2, capture.last.stream().filter(SceneGeometrySink.Remove.class::isInstance).count());
        assertEquals(1, capture.last.stream().filter(SceneGeometrySink.Drop.class::isInstance).count());
    }

    @Test
    void resubmitsTexturesOnlyForANewResourceEpoch() {
        GltfViewerSceneProvider provider = new GltfViewerSceneProvider(scene(), Set::of);
        List<SceneMesh.TextureReference> submitted = new ArrayList<>();

        provider.submitTextures((reference, content) -> submitted.add(reference));
        provider.submitTextures((reference, content) -> submitted.add(reference));
        assertEquals(1, submitted.size());

        provider.onResourcePackClosing();
        provider.submitTextures((reference, content) -> submitted.add(reference));
        assertEquals(2, submitted.size());
    }

    private static GltfViewerScene scene() {
        SceneGeometryKey resident = SceneGeometryKey.of(7);
        float[] identity = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
        float[] authored = {
                2, 0, 0, 0,
                0, 3, 0, 0,
                0, 0, 4, 0,
                1, 2, 3, 1
        };
        SceneMesh.TextureReference texture = new SceneMesh.StandaloneTexture(
                ResourceId.of("test", "base_color"));
        return new GltfViewerScene(
                List.of(new GltfViewerScene.Resident(resident, triangle())),
                List.of(new GltfViewerScene.Placement(resident, identity),
                        new GltfViewerScene.Placement(resident, authored)),
                List.of(),
                List.of(new GltfViewerScene.Texture(texture,
                        new CpuTextureResource(1, 1, CpuTextureResource.Encoding.SRGB,
                                new byte[]{1, 2, 3, 4}))));
    }

    private static SceneMesh triangle() {
        return new SceneMesh(
                new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[]{0, 1, 2},
                SceneMesh.UvLayout.PER_VERTEX,
                new float[]{0, 0, 1, 0, 0, 1},
                List.of(new SceneMesh.TriangleSurface(new SceneMesh.FallbackMaterial(null),
                        SceneMesh.Coverage.OPAQUE, Float.NaN, Float.NaN, Float.NaN,
                        0, 1, 1, 1)));
    }

    private static final class Capture implements SceneScope {
        private List<Operation> last = List.of();

        @Override
        public void submit(SceneGeometryKey groupKey, List<Operation> operations, Runnable onPublished) {
            last = List.copyOf(operations);
        }
    }
}
