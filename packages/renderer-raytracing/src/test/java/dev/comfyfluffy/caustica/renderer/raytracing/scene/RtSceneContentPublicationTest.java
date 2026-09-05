package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtSceneContentPublicationTest {
    private static final SceneId SCENE = new SceneId() { };

    @Test
    void slowMeshGroupsLightPatchPreservesUnrelatedLaterLightUpdate() {
        var original = content(light(1, 1), light(2, 1));
        var acceptedMeshGroup = content(light(1, 2), light(2, 1));
        var acceptedUnrelated = content(light(1, 2), light(2, 3));
        var slow = RtRetainedSceneBackend.ContentPatch.between(original, acceptedMeshGroup);
        var fast = RtRetainedSceneBackend.ContentPatch.between(acceptedMeshGroup, acceptedUnrelated);

        var visible = fast.apply(original);
        assertEquals(List.of(light(1, 1), light(2, 3)), visible.get(SCENE).lights());
        visible = slow.apply(visible);
        assertEquals(List.of(light(1, 2), light(2, 3)), visible.get(SCENE).lights());
    }

    @Test
    void delayedEmitterRemovalDoesNotEraseAnUnrelatedNewEmitter() {
        var original = content(light(1, 1));
        var removed = content();
        var added = content(light(2, 2));
        var slow = RtRetainedSceneBackend.ContentPatch.between(original, removed);
        var fast = RtRetainedSceneBackend.ContentPatch.between(removed, added);
        assertEquals(List.of(light(2, 2)), slow.apply(fast.apply(original)).get(SCENE).lights());
    }

    private static Map<SceneId, RtRetainedSceneBackend.SceneContent> content(
            RtRetainedSceneBackend.SceneLight... lights) {
        return Map.of(SCENE, new RtRetainedSceneBackend.SceneContent(null, List.of(lights)));
    }

    private static RtRetainedSceneBackend.SceneLight light(long identity, double intensity) {
        return new RtRetainedSceneBackend.SceneLight(identity,
                new LightDescriptor.Distant(0, 1, 0, intensity, intensity, intensity, 0, false));
    }
}
