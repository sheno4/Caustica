package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;


import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class RtFramePreparationTest {
    @Test
    void partitionsRemainBoundedContiguousAndComplete() {
        for (int size : new int[]{0, 1, 255, 256, 257, 1027}) {
            List<Integer> inputs = IntStream.range(0, size).boxed().toList();
            List<List<Integer>> chunks = RtFramePreparation.chunks(inputs);
            assertTrue(chunks.size() >= 1 && chunks.size() <= 4);
            assertEquals(inputs, chunks.stream().flatMap(List::stream).toList());
        }
    }

    @Test
    void weightedPartitionsUsePageWorkInsteadOfPageCount() {
        List<Integer> inputs = List.of(400, 400, 400, 400);
        List<List<Integer>> chunks = RtFramePreparation.chunks(inputs, Integer::intValue);
        assertEquals(4, chunks.size());
        assertEquals(inputs, chunks.stream().flatMap(List::stream).toList());
    }

    @Test
    void largeWorkEstimatesRemainCappedByWorkerCount() {
        List<Integer> inputs = java.util.Collections.nCopies(257, Integer.MAX_VALUE);
        List<List<Integer>> chunks = RtFramePreparation.chunks(inputs, Integer::intValue);
        assertEquals(4, chunks.size());
        assertEquals(inputs, chunks.stream().flatMap(List::stream).toList());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.size() >= 64 && chunk.size() <= 65));
    }

    @Test
    void unevenWeightsLeaveEveryChunkNonempty() {
        for (List<Integer> inputs : List.of(List.of(4000, 0, 0, 0), List.of(0, 0, 0, 4000))) {
            List<List<Integer>> chunks = RtFramePreparation.chunks(inputs, Integer::intValue);
            assertEquals(4, chunks.size());
            assertTrue(chunks.stream().allMatch(chunk -> chunk.size() == 1));
            assertEquals(inputs, chunks.stream().flatMap(List::stream).toList());
        }
    }

    @Test
    void singleChunkRunsOnPreparationWorker() {
        String caller = Thread.currentThread().getName();
        String[] worker = new String[1];
        try (var preparation = new RtFramePreparation()) {
            preparation.run(List.of(1), ignored -> worker[0] = Thread.currentThread().getName());
        }
        assertNotEquals(caller, worker[0]);
        assertTrue(worker[0].startsWith("Caustica frame preparation-"));
    }

    @Test
    void parallelGeometryEmitterAndHitOrderMatchesSequentialPacking() {
        int count = 1027;
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int flags = RtRetainedGeometryPlan.HAS_SURFACE
                    | (index % 2 == 0 ? RtRetainedGeometryPlan.CUTOUT : RtRetainedGeometryPlan.HAS_VOLUME);
            records.add(new RtRetainedGeometryPlan.GeometryRecord(index, 2, 3, flags,
                    100, 200, 0.4f, index,
                    new VulkanDeviceAddress(0x2000), index * 21,
                    new VulkanDeviceAddress(0x3000 + index * 28L), 0));
        }
        var ranges = List.of(new RetainedSceneSnapshot.PrimitiveEmitter(1, 2, 42L),
                new RetainedSceneSnapshot.PrimitiveEmitter(4, 1, 84L),
                new RetainedSceneSnapshot.PrimitiveEmitter(5, 1, 99L));
        var indices = new Long2IntOpenHashMap(new long[]{42, 84}, new int[]{1, 0});
        ByteBuffer sequentialGeometry = RtRetainedGeometryPlan.pack(records);
        ByteBuffer sequentialEmitters = ByteBuffer.allocate(count * 28).order(ByteOrder.nativeOrder());
        for (int index = 0; index < count; index++) {
            EmitterReference.pack(sequentialEmitters, 0, 7, ranges, indices);
        }
        sequentialEmitters.flip();
        ByteBuffer parallelGeometry = ByteBuffer.allocateDirect(sequentialGeometry.remaining());
        ByteBuffer parallelEmitters = ByteBuffer.allocateDirect(sequentialEmitters.remaining());
        List<List<Integer>> chunks = RtFramePreparation.chunks(IntStream.range(0, count).boxed().toList());
        List<BitSet> linked = chunks.stream().map(ignored -> new BitSet()).toList();
        List<List<RtRetainedGeometryPlan.HitGroup>> hits = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) hits.add(null);
        try (var preparation = new RtFramePreparation()) {
            preparation.run(IntStream.range(0, chunks.size()).boxed().toList(), chunkIndex -> {
                List<Integer> chunk = chunks.get(chunkIndex);
                int first = chunk.getFirst();
                List<RtRetainedGeometryPlan.GeometryRecord> chunkRecords = records.subList(first, chunk.getLast() + 1);
                RtRetainedGeometryPlan.packInto(parallelGeometry.slice(first * RtRetainedGeometryPlan.RECORD_BYTES,
                        chunk.size() * RtRetainedGeometryPlan.RECORD_BYTES), chunkRecords);
                ByteBuffer emitterSlice = parallelEmitters.slice(first * 28, chunk.size() * 28)
                        .order(ByteOrder.nativeOrder());
                var builder = new RtEmitterRuns.Builder();
                for (int index = 0; index < chunk.size(); index++) {
                    builder.addSpan(index * 28, 0, 7, ranges);
                }
                var runs = builder.build();
                runs.resolve(new Object(), indices);
                runs.pack(emitterSlice);
                for (int light : runs.linked()) linked.get(chunkIndex).set(light);
                hits.set(chunkIndex, RtRetainedGeometryPlan.hitGroups(chunkRecords));
            });
        }
        assertEquals(sequentialGeometry, parallelGeometry);
        assertEquals(sequentialEmitters, parallelEmitters);
        assertEquals(RtRetainedGeometryPlan.hitGroups(records), hits.stream().flatMap(List::stream).toList());
        BitSet combined = new BitSet();
        linked.forEach(combined::or);
        assertEquals(BitSet.valueOf(new long[]{0b11}), combined);
    }

    @Test
    void failureWaitsForOtherAcceptedWritersBeforeReturning() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch failureStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        int[] written = {0};
        RuntimeException expected = new IllegalStateException("planning failure");
        try (var preparation = new RtFramePreparation(); var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> {
                Thread callingThread = Thread.currentThread();
                try (var batch = preparation.batch()) {
                    batch.submit(() -> {
                        assertNotSame(callingThread, Thread.currentThread());
                        await(writerStarted);
                        failureStarted.countDown();
                        throw expected;
                    });
                    batch.submit(() -> {
                        assertNotSame(callingThread, Thread.currentThread());
                        writerStarted.countDown();
                        await(releaseWriter);
                        written[0] = 73;
                    });
                }
            });
            try {
                assertTrue(failureStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            } finally {
                releaseWriter.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertSame(expected, failure.getCause());
            assertEquals(73, written[0]);
        }
    }

    @Test
    void batchPreservesCallerFailureAndAggregatesWorkerFailures() {
        RuntimeException callerFailure = new IllegalStateException("planning failure");
        RuntimeException firstWorkerFailure = new IllegalArgumentException("first worker failure");
        Error secondWorkerFailure = new AssertionError("second worker failure");
        try (var preparation = new RtFramePreparation()) {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> {
                try (var batch = preparation.batch()) {
                    batch.submit(() -> { throw firstWorkerFailure; });
                    batch.submit(() -> { throw secondWorkerFailure; });
                    throw callerFailure;
                }
            });
            assertSame(callerFailure, failure);
            assertArrayEquals(new Throwable[]{firstWorkerFailure}, failure.getSuppressed());
            assertArrayEquals(new Throwable[]{secondWorkerFailure}, firstWorkerFailure.getSuppressed());
        }
    }

    @Test
    void failedSubmissionStillDrainsAcceptedTasks() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch submissionFailed = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        int[] written = {0};
        try (var preparation = new RtFramePreparation(); var caller = Executors.newSingleThreadExecutor()) {
            var result = caller.submit(() -> {
                try (var batch = preparation.batch()) {
                    batch.submit(() -> {
                        writerStarted.countDown();
                        await(releaseWriter);
                        written[0] = 91;
                    });
                    await(writerStarted);
                    try {
                        batch.submit(null);
                    } catch (NullPointerException failure) {
                        submissionFailed.countDown();
                        throw failure;
                    }
                }
            });
            try {
                assertTrue(submissionFailed.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            } finally {
                releaseWriter.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(NullPointerException.class, failure.getCause());
            assertEquals(91, written[0]);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
