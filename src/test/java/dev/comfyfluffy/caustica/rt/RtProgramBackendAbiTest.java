package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.engine.program.ProgramKey;
import dev.comfyfluffy.caustica.rt.pipeline.RtBindings;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtProgramBackendAbiTest {
    @Test
    void publishedProgramWritesOnlyCompositionAddressField() {
        long address = 0x1234_5678_9abc_def0L;
        RtProgramBackend.Published program = published(address);
        ByteBuffer roots = MemoryUtil.memAlloc(RtBindings.WORLD_PUSH_CONSTANT_SIZE)
                .order(ByteOrder.nativeOrder());
        try {
            for (int index = 0; index < roots.capacity(); index++) roots.put(index, (byte) 0x5a);
            program.writeCompositionDataAddress(roots);
            assertEquals(address, roots.getLong(RtBindings.WORLD_COMPOSITION_DATA_ADDRESS_OFFSET));
            assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_PUSH_ADDRESS_OFFSET));
            assertEquals((byte) 0x5a, roots.get(RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET));
        } finally {
            MemoryUtil.memFree(roots);
        }
    }

    @Test
    void publishedProgramRejectsPartialRoot() {
        ByteBuffer roots = MemoryUtil.memAlloc(RtBindings.WORLD_PUSH_CONSTANT_SIZE - Integer.BYTES);
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> published(1L).writeCompositionDataAddress(roots));
        } finally {
            MemoryUtil.memFree(roots);
        }
    }

    @Test
    void shutdownWaitsForCompilerWorkAndRestoresInterruption() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService compiler = Executors.newSingleThreadExecutor();
        compiler.submit(() -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        compiler.shutdown();

        ExecutorService waiter = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result = waiter.submit(() -> {
                Thread.currentThread().interrupt();
                RtProgramBackend.awaitTerminationUninterruptibly(compiler);
                return Thread.currentThread().isInterrupted();
            });
            assertFalse(result.isDone());
            release.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            waiter.shutdownNow();
            compiler.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static RtProgramBackend.Published published(long address) {
        return new RtProgramBackend.Published() {
            @Override public RtPipeline pipeline() { return null; }
            @Override public long compositionDataAddress() { return address; }
            @Override public int implementationIndex(ProgramKey key) { return 0; }
            @Override public void close() { }
        };
    }
}
