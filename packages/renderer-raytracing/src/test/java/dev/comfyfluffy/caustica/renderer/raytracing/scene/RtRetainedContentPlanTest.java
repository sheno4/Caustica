package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtRetainedContentPlanTest {
    @Test
    void contentIsPartitionedBySceneWithoutGeometryInput() {
        SceneId first = new SceneId() { };
        SceneId second = new SceneId() { };
        var firstLight = new LightDescriptor.Spot(1, 2, 3, 0, -1, 0, 10, 0.5, 4, 5, 6);
        var secondLight = new LightDescriptor.Spot(8, 9, 10, 0, -1, 0, 20, 0.5, 11, 12, 13);

        var content = RtRetainedSceneBackend.assembleContent(
                List.of(new RetainedSceneSnapshot.Scene(first, null),
                        new RetainedSceneSnapshot.Scene(second, null)),
                List.of(new RetainedSceneSnapshot.Light(10L, first, firstLight),
                        new RetainedSceneSnapshot.Light(20L, second, secondLight)));

        assertEquals(2, content.size());
        assertSame(firstLight, content.get(first).lights().getFirst().descriptor());
        assertSame(secondLight, content.get(second).lights().getFirst().descriptor());
    }

    @Test
    void primitiveEmitterIdentityResolvesAgainstEachContentRevision() {
        var emitters = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(2, 3, 42L));

        assertEquals(1, RtRetainedSceneBackend.emitterIndex(emitters, 3, Map.of(42L, 1)));
        assertEquals(-1, RtRetainedSceneBackend.emitterIndex(emitters, 3, Map.of()));
        assertEquals(-1, RtRetainedSceneBackend.emitterIndex(emitters, 1, Map.of(42L, 1)));
    }
}
