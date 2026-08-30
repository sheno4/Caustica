package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneGeometryDelta;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtRetainedInstanceStateTest {
    private static final SceneId SCENE = new SceneId() { };
    private static final ShaderDataType<Object> INSTANCE_DATA = ShaderDataType.create("test instance");

    @Test
    void replacementKeepsItsStablePosition() {
        RtRetainedInstanceState state = new RtRetainedInstanceState();
        state.retain(instance(10, 1, 1), 4);
        state.retain(instance(20, 1, 2), 8);

        state.apply(List.of(new RetainedSceneGeometryDelta.SetInstance(instance(10, 1, 3))),
                new AtomicLong(8)::incrementAndGet);

        assertEquals(List.of(10L, 20L), identities(state));
        assertEquals(4, state.ordinal(10));
        assertEquals(GeometryTransform.translation(1, 0, 0),
                state.previousTransform(state.orderedInstances().getFirst()));
    }

    @Test
    void dropThenReaddAppendsAndStartsFreshTransformHistory() {
        RtRetainedInstanceState state = new RtRetainedInstanceState();
        state.retain(instance(10, 1, 1), 4);
        state.retain(instance(20, 1, 2), 8);
        RetainedSceneSnapshot.Instance readded = instance(10, 1, 7);
        AtomicLong nextOrdinal = new AtomicLong(8);

        state.apply(List.of(new RetainedSceneGeometryDelta.DropInstance(10),
                        new RetainedSceneGeometryDelta.SetInstance(readded)),
                nextOrdinal::incrementAndGet);

        assertEquals(List.of(20L, 10L), identities(state));
        assertEquals(9, state.ordinal(10));
        assertEquals(readded.transform(), state.previousTransform(readded));
    }

    @Test
    void droppingAMeshRemovesItsPlacementsBeforeAReadd() {
        RtRetainedInstanceState state = new RtRetainedInstanceState();
        state.retain(instance(10, 1, 1), 4);
        state.retain(instance(20, 2, 2), 8);
        RetainedSceneSnapshot.Instance readded = instance(10, 1, 9);
        AtomicLong nextOrdinal = new AtomicLong(8);

        state.apply(List.of(new RetainedSceneGeometryDelta.DropMesh(1),
                        new RetainedSceneGeometryDelta.SetInstance(readded)),
                nextOrdinal::incrementAndGet);

        assertEquals(List.of(20L, 10L), identities(state));
        assertEquals(readded.transform(), state.previousTransform(readded));
    }

    private static List<Long> identities(RtRetainedInstanceState state) {
        return state.orderedInstances().stream().map(RetainedSceneSnapshot.Instance::identity).toList();
    }

    private static RetainedSceneSnapshot.Instance instance(long identity, long meshIdentity, double x) {
        return new RetainedSceneSnapshot.Instance(identity, SCENE, meshIdentity,
                GeometryTransform.translation(x, 0, 0), 0xff, INSTANCE_DATA.data(identity), List.of());
    }
}
