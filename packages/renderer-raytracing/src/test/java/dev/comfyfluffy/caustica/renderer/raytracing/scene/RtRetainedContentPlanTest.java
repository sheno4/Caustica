package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;


import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertEquals(1, RtRetainedSceneBackend.emitterIndex(emitters, 3, Long2IntMaps.singleton(42L, 1)));
        assertEquals(-1, RtRetainedSceneBackend.emitterIndex(emitters, 3, Long2IntMaps.EMPTY_MAP));
        assertEquals(-1, RtRetainedSceneBackend.emitterIndex(emitters, 1, Long2IntMaps.singleton(42L, 1)));
    }

    @Test
    void consecutiveEmitterIndicesUseSortedRangesAndTrackLinkedLights() {
        var ranges = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(2, 2, 42L),
                new RetainedSceneSnapshot.PrimitiveEmitter(5, 2, 84L));
        ByteBuffer output = ByteBuffer.allocate(7 * Integer.BYTES).order(ByteOrder.nativeOrder());
        boolean[] linked = new boolean[2];

        RtRetainedSceneBackend.putEmitterIndices(output, 0, 7, ranges,
                new Long2IntOpenHashMap(new long[]{42, 84}, new int[]{0, 1}), linked);
        output.flip();

        assertEquals(List.of(-1, -1, 0, 0, -1, 1, 1),
                java.util.stream.IntStream.range(0, 7).map(ignored -> output.getInt()).boxed().toList());
        assertEquals(List.of(true, true), List.of(linked[0], linked[1]));
    }

    @Test
    void emitterTablesAreOmittedForGeometryOutsideEveryMappedRange() {
        var ranges = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(4, 2, 42L));

        assertFalse(RtRetainedSceneBackend.hasEmitterMapping(ranges, 0, 4));
        assertTrue(RtRetainedSceneBackend.hasEmitterMapping(ranges, 4, 1));
        assertTrue(RtRetainedSceneBackend.hasEmitterMapping(ranges, 3, 2));
        assertFalse(RtRetainedSceneBackend.hasEmitterMapping(ranges, 6, 3));
        assertFalse(RtRetainedSceneBackend.hasEmitterMapping(ranges, 5, 0));
    }

    @Test
    void emitterPackingSeeksLateRangesAndClipsAtBothGeometryBoundaries() {
        var ranges = java.util.stream.IntStream.range(0, 2048)
                .mapToObj(index -> new RetainedSceneSnapshot.PrimitiveEmitter(index * 4, 2, 42L)).toList();
        int first = 2040 * 4 + 1;
        var output = ByteBuffer.allocate(19 * Integer.BYTES).order(ByteOrder.nativeOrder());
        boolean[] linked = new boolean[1];
        RtRetainedSceneBackend.putEmitterIndices(output, first, 19, ranges, Long2IntMaps.singleton(42L, 0), linked);
        output.flip();
        for (int primitive = first; primitive < first + 19; primitive++) {
            assertEquals(primitive % 4 < 2 ? 0 : -1, output.getInt());
            assertEquals(primitive % 4 < 2,
                    RtRetainedSceneBackend.hasEmitterMapping(ranges, primitive, 1));
        }
        assertTrue(linked[0]);
        assertFalse(RtRetainedSceneBackend.hasEmitterMapping(ranges, 8190, 10));
    }

    @Test
    void emitterPackingClipsRangesAndPreservesMissingLightGaps() {
        var ranges = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(1, 3, 42L),
                new RetainedSceneSnapshot.PrimitiveEmitter(5, 2, 84L));
        var indices = Long2IntMaps.singleton(42L, 0);
        for (int first = 0; first < 9; first++) {
            for (int count = 0; count <= 9 - first; count++) {
                var output = ByteBuffer.allocate(count * Integer.BYTES).order(ByteOrder.nativeOrder());
                boolean[] linked = new boolean[1];
                RtRetainedSceneBackend.putEmitterIndices(output, first, count, ranges, indices, linked);
                assertEquals(count * Integer.BYTES, output.position());
                output.flip();
                boolean expectedLinked = false;
                for (int primitive = first; primitive < first + count; primitive++) {
                    int expected = RtRetainedSceneBackend.emitterIndex(ranges, primitive, indices);
                    assertEquals(expected, output.getInt());
                    expectedLinked |= expected >= 0;
                }
                assertEquals(expectedLinked, linked[0]);
            }
        }
    }
}
