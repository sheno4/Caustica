package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RtLightPageAssemblyTest {
    @Test void changedPagePreservesOtherSceneAndOldCapturedDescriptors() {
        var first = new SceneId() { };
        var second = new SceneId() { };
        var scenes = List.of(new RetainedSceneSnapshot.Scene(first, null), new RetainedSceneSnapshot.Scene(second, null));
        var stablePage = List.of(light(first, 1, 4));
        var editedPage = List.of(light(second, 2, 8));
        var assembly = new RtLightPageAssembly();
        var old = assembly.resolve(scenes, SnapshotList.ofPages(List.of(stablePage, editedPage)));
        var next = assembly.resolve(scenes, SnapshotList.ofPages(List.of(stablePage, List.of(light(second, 2, 16)))));
        assertSame(old.get(first), next.get(first));
        assertNotSame(old.get(second), next.get(second));
        assertEquals(editedPage.getFirst().descriptor(), old.get(second).lights().getFirst().descriptor());
        assertEquals(16, ((LightDescriptor.Distant) next.get(second).lights().getFirst().descriptor()).illuminanceRedLux());
        var removed = assembly.resolve(scenes, SnapshotList.ofPages(List.of(stablePage)));
        assertSame(next.get(first), removed.get(first));
        assertTrue(removed.get(second).lights().isEmpty());
        assertEquals(1, old.get(second).lights().size());
    }

    @Test void sharedPageKeepsPerSceneOrderingWithoutFlatteningUnchangedPages() {
        var scene = new SceneId() { };
        var scenes = List.of(new RetainedSceneSnapshot.Scene(scene, null));
        var page = List.of(light(scene, 1, 1), light(scene, 2, 2));
        var assembly = new RtLightPageAssembly();
        var first = assembly.resolve(scenes, SnapshotList.ofPages(List.of(page))).get(scene);
        var next = assembly.resolve(scenes, SnapshotList.ofPages(List.of(page, List.of(light(scene, 3, 3))))).get(scene);
        assertSame(SnapshotList.pagesOf(first.lights()).getFirst(), SnapshotList.pagesOf(next.lights()).getFirst());
        assertEquals(List.of(1L, 2L, 3L), next.lights().stream().map(RtRetainedSceneBackend.SceneLight::identity).toList());
    }

    private static RetainedSceneSnapshot.Light light(SceneId scene, long identity, double red) {
        return new RetainedSceneSnapshot.Light(identity, scene, new LightDescriptor.Distant(0, 1, 0, red, 1, 1, 0, false));
    }
}
