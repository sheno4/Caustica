package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot.PrimitiveEmitter;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RtEmitterRunsTest {
    @Test void reusedBuilderDoesNotChangePreviouslyBuiltTopology() {
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, 0, 2, List.of(new PrimitiveEmitter(0, 2, 10)));
        var first = builder.build();
        builder.reset();
        builder.addSpan(0, 0, 3, List.of(new PrimitiveEmitter(0, 3, 20)));
        var second = builder.build();
        second.resolve(new Object(), identity -> {
            assertEquals(20, identity);
            return 3;
        });
        first.resolve(new Object(), identity -> {
            assertEquals(10, identity);
            return 7;
        });
        var firstBytes = buffer(8);
        first.pack(firstBytes);
        assertEquals(7, firstBytes.getInt(0));
        assertEquals(7, firstBytes.getInt(4));
        var secondBytes = buffer(12);
        second.pack(secondBytes);
        assertEquals(3, secondBytes.getInt(8));
    }

    @Test void clippedRunsGapsAndRepeatedIdentitiesMatchExistingWriter() {
        var ranges = List.of(new PrimitiveEmitter(0, 3, 10), new PrimitiveEmitter(4, 2, 20),
                new PrimitiveEmitter(7, 2, 10), new PrimitiveEmitter(10, 2, 30));
        var indices = new Long2IntOpenHashMap(new long[]{10, 20}, new int[]{1_000_000, 2});
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(4, 1, 12, ranges);
        builder.addSpan(52, 5, 3, ranges);
        var runs = builder.build();
        runs.resolve(new Object(), indices);
        var expected = buffer(68);
        var actual = buffer(68);
        for (int index = 0; index < 68; index++) {
            expected.put(index, (byte) 0x5a);
            actual.put(index, (byte) 0x5a);
        }
        expected.position(4);
        RtRetainedSceneBackend.putEmitterIndices(expected, 1, 12, ranges, indices, ignored -> {});
        expected.position(52);
        RtRetainedSceneBackend.putEmitterIndices(expected, 5, 3, ranges, indices, ignored -> {});
        runs.pack(actual);
        expected.clear();
        actual.clear();
        assertEquals(expected, actual);
        assertArrayEquals(new int[]{1_000_000, 2}, runs.linked());
    }

    @Test void eachRevisionResolvesOnlyUniqueIdentitiesAndEqualValuesKeepGeneration() {
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, 0, 5, List.of(new PrimitiveEmitter(0, 2, 10),
                new PrimitiveEmitter(2, 1, 10), new PrimitiveEmitter(3, 2, 20)));
        var runs = builder.build();
        var calls = new AtomicInteger();
        Long2IntFunction indices = identity -> {
            calls.incrementAndGet();
            return identity == 10 ? 3 : -1;
        };
        Object revision = new Object();
        runs.resolve(revision, indices);
        Object generation = runs.generation();
        int[] linked = runs.linked();
        assertEquals(2, calls.get());
        runs.resolve(revision, indices);
        assertEquals(2, calls.get());
        runs.resolve(new Object(), indices);
        assertEquals(4, calls.get());
        assertSame(generation, runs.generation());
        assertSame(linked, runs.linked());
        assertArrayEquals(new int[]{3}, linked);
    }

    @Test void changedDenseValuesPreserveOldSlotBytesAndLinkedArray() {
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, 0, 2, List.of(new PrimitiveEmitter(0, 1, 10), new PrimitiveEmitter(1, 1, 20)));
        var runs = builder.build();
        runs.resolve(new Object(), identity -> identity == 10 ? 7 : -1);
        Object oldGeneration = runs.generation();
        int[] oldLinked = runs.linked();
        var oldSlot = buffer(8);
        runs.pack(oldSlot);
        var residency = new RtRetainedSceneBackend.TracePageResidency();
        Object range = new Object();
        residency.emittersWritten(range, 0, oldGeneration);
        residency.linkedEmittersWritten(oldLinked);

        runs.resolve(new Object(), identity -> identity == 20 ? 1_000_000 : -1);
        assertNotSame(oldGeneration, runs.generation());
        assertFalse(residency.hasEmitters(range, 0, runs.generation()));
        assertArrayEquals(new int[]{7}, oldLinked);
        assertArrayEquals(new int[]{7}, residency.linkedEmitters);
        assertArrayEquals(new int[]{1_000_000}, runs.linked());
        assertEquals(7, oldSlot.getInt(0));
        assertEquals(-1, oldSlot.getInt(4));
        var nextSlot = buffer(8);
        runs.pack(nextSlot);
        assertEquals(-1, nextSlot.getInt(0));
        assertEquals(1_000_000, nextSlot.getInt(4));
    }

    @Test void emptyAndGapOnlyPagesNeverResolveLightIdentities() {
        var builder = new RtEmitterRuns.Builder();
        builder.addSpan(0, 0, 3, List.of());
        var runs = builder.build();
        runs.resolve(new Object(), identity -> { throw new AssertionError("no references"); });
        var target = buffer(12);
        runs.pack(target);
        assertEquals(-1, target.getInt(0));
        assertEquals(-1, target.getInt(4));
        assertEquals(-1, target.getInt(8));
        assertEquals(0, runs.linked().length);
        var empty = new RtEmitterRuns.Builder().build();
        empty.resolve(new Object(), identity -> { throw new AssertionError("empty page"); });
        empty.pack(buffer(0));
        assertNotNull(empty.generation());
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder()); }
}
