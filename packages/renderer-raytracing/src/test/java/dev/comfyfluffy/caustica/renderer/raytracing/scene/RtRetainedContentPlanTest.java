package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;


import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, 1, 3, emitters);
        var runs = builder.build();
        var output = ByteBuffer.allocate(3 * Integer.BYTES).order(ByteOrder.nativeOrder());
        runs.resolve(new Object(), Long2IntMaps.singleton(42L, 1));
        runs.pack(output);
        assertEquals(-1, output.getInt(0));
        assertEquals(1, output.getInt(4));
        assertEquals(1, output.getInt(8));
        runs.resolve(new Object(), Long2IntMaps.EMPTY_MAP);
        runs.pack(output);
        for (int offset = 0; offset < output.capacity(); offset += Integer.BYTES) {
            assertEquals(-1, output.getInt(offset));
        }
        assertArrayEquals(new int[0], runs.linked());
    }

    @Test
    void consecutiveEmitterIndicesUseSortedRangesAndTrackLinkedLights() {
        var ranges = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(2, 2, 42L),
                new RetainedSceneSnapshot.PrimitiveEmitter(5, 2, 84L));
        ByteBuffer output = ByteBuffer.allocate(7 * Integer.BYTES).order(ByteOrder.nativeOrder());

        var runs = pack(output, 0, 7, ranges,
                new Long2IntOpenHashMap(new long[]{42, 84}, new int[]{0, 1}));
        output.flip();

        assertEquals(List.of(-1, -1, 0, 0, -1, 1, 1),
                java.util.stream.IntStream.range(0, 7).map(ignored -> output.getInt()).boxed().toList());
        assertArrayEquals(new int[]{0, 1}, runs.linked());
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
        var runs = pack(output, first, 19, ranges, Long2IntMaps.singleton(42L, 0));
        output.flip();
        for (int primitive = first; primitive < first + 19; primitive++) {
            assertEquals(primitive % 4 < 2 ? 0 : -1, output.getInt());
            assertEquals(primitive % 4 < 2,
                    RtRetainedSceneBackend.hasEmitterMapping(ranges, primitive, 1));
        }
        assertArrayEquals(new int[]{0}, runs.linked());
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
                var runs = pack(output, first, count, ranges, indices);
                assertEquals(count * Integer.BYTES, output.position());
                output.flip();
                boolean expectedLinked = false;
                for (int primitive = first; primitive < first + count; primitive++) {
                    int expected = EmitterReference.index(primitive, ranges, indices);
                    assertEquals(expected, output.getInt());
                    expectedLinked |= expected >= 0;
                }
                assertArrayEquals(expectedLinked ? new int[]{0} : new int[0], runs.linked());
            }
        }
    }

    private static RtEmitterRuns pack(ByteBuffer output, int first, int count,
                                      List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                      Long2IntFunction indices) {
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, first, count, ranges);
        var runs = builder.build();
        runs.resolve(new Object(), indices);
        runs.pack(output);
        return runs;
    }
}
