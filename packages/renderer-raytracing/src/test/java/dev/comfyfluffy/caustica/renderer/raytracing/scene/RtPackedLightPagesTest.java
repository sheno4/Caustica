package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RtPackedLightPagesTest {
    @Test void stablePagesReuseBytesAcrossReorderingAndMatchPublicWriter() {
        var first = List.of(light(1, 2), light(2, 3));
        var second = List.of(light(3, 4));
        var cache = new RtPackedLightPages();
        try (var preparation = new RtFramePreparation()) {
            var flags = new BitSet();
            flags.set(0);
            var packed = cache.resolve(SnapshotList.ofPages(List.of(first, second)), SceneOrigin.ZERO, flags, preparation);
            assertEquals(RtRetainedLightPlan.pack(first.stream().map(RtRetainedSceneBackend.SceneLight::descriptor).toList(),
                    SceneOrigin.ZERO, new boolean[]{true, false}), packed.getFirst());
            flags.clear();
            flags.set(1);
            var reordered = cache.resolve(SnapshotList.ofPages(List.of(second, first)), SceneOrigin.ZERO, flags, preparation);
            assertSame(packed.get(0), reordered.get(1));
            assertSame(packed.get(1), reordered.get(0));
            assertTrue(packed.getFirst().isReadOnly());
        }
    }

    @Test void changedFlagsDescriptorsAndOriginInvalidateOnlyDependentBytes() {
        var first = List.of(light(1, 2));
        var second = List.of(light(2, 3));
        var values = SnapshotList.ofPages(List.of(first, second));
        var cache = new RtPackedLightPages();
        try (var preparation = new RtFramePreparation()) {
            var flags = new BitSet();
            var initial = cache.resolve(values, SceneOrigin.ZERO, flags, preparation);
            flags.set(1);
            var linked = cache.resolve(values, SceneOrigin.ZERO, flags, preparation);
            assertSame(initial.getFirst(), linked.getFirst());
            assertNotEquals(initial.getLast(), linked.getLast());
            var changed = cache.resolve(SnapshotList.ofPages(List.of(List.of(light(1, 5)), second)),
                    SceneOrigin.ZERO, flags, preparation);
            assertNotEquals(linked.getFirst(), changed.getFirst());
            assertSame(linked.getLast(), changed.getLast());
            var rebased = cache.resolve(values, new SceneOrigin(128, 0, 0), flags, preparation);
            assertNotSame(initial.getFirst(), rebased.getFirst());
            assertNotSame(linked.getLast(), rebased.getLast());
            assertEquals(0, initial.getLast().getInt(4));
        }
    }

    private static RtRetainedSceneBackend.SceneLight light(long id, double red) {
        return new RtRetainedSceneBackend.SceneLight(id,
                new LightDescriptor.Distant(0, 1, 0, red, 1, 1, 0, false));
    }
}
