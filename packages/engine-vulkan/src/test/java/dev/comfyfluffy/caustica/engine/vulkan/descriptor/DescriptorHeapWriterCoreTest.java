package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeapProperties;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DescriptorHeapWriterCoreTest {
    @Test
    void validatesOffsetsThenFlushesExactlyOneDescriptorSlot() {
        DescriptorHeapAllocationCore allocations = allocations();
        RecordingStorage resources = new RecordingStorage(DescriptorHeapKind.RESOURCE, 0x1000, 0x10000, 4096);
        RecordingStorage samplers = new RecordingStorage(DescriptorHeapKind.SAMPLER, 0x2000, 0x20000, 1024);
        RecordingWriter nativeWriter = new RecordingWriter();
        DescriptorHeapWriterCore writer = new DescriptorHeapWriterCore(
                allocations, resources, samplers, nativeWriter);
        var resourceRange = allocations.allocateResources(3);
        var samplerRange = allocations.allocateSamplers(2);

        writer.writeResource(resourceRange, 2, null);
        writer.writeSampler(samplerRange, 1, null);
        writer.writeAccelerationStructure(resourceRange, 0, 77L);

        assertEquals(List.of(0x10000L + 64 + 64, 0x20000L + 16 + 8, 0x10000L + 64),
                nativeWriter.destinations);
        assertEquals(List.of(new Flush(128, 32), new Flush(64, 32)), resources.flushes);
        assertEquals(List.of(new Flush(24, 8)), samplers.flushes);
    }

    @Test
    void rejectsRetiredAndForeignRangesBeforeNativeEncoding() {
        DescriptorHeapAllocationCore allocations = allocations();
        RecordingWriter nativeWriter = new RecordingWriter();
        DescriptorHeapWriterCore writer = new DescriptorHeapWriterCore(
                allocations,
                new RecordingStorage(DescriptorHeapKind.RESOURCE, 0x1000, 0x10000, 4096),
                new RecordingStorage(DescriptorHeapKind.SAMPLER, 0x2000, 0x20000, 1024),
                nativeWriter);
        var retired = allocations.allocateResources(1);
        retired.destroy();

        assertThrows(IllegalStateException.class, () -> writer.writeResource(retired, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> writer.writeAccelerationStructure(allocations.allocateResources(1), 0, 0L));
        assertEquals(List.of(), nativeWriter.destinations);
    }

    @Test
    void allocationCannotRetireAndReuseItsSlotDuringANativeWrite() throws Exception {
        DescriptorHeapAllocationCore allocations = allocations();
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        RecordingWriter nativeWriter = new RecordingWriter() {
            @Override public void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource) {
                writeStarted.countDown();
                await(releaseWrite);
                super.writeResource(destinationHostAddress, resource);
            }
        };
        DescriptorHeapWriterCore writer = new DescriptorHeapWriterCore(
                allocations,
                new RecordingStorage(DescriptorHeapKind.RESOURCE, 0x1000, 0x10000, 4096),
                new RecordingStorage(DescriptorHeapKind.SAMPLER, 0x2000, 0x20000, 1024),
                nativeWriter);
        var allocation = allocations.allocateResources(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = workers.submit(() -> writer.writeResource(allocation, 0, null));
            writeStarted.await();
            Future<?> retire = workers.submit(allocation::destroy);

            assertFalse(retire.isDone());
            releaseWrite.countDown();
            write.get(5, TimeUnit.SECONDS);
            retire.get(5, TimeUnit.SECONDS);
            assertEquals(allocation.firstIndex(), allocations.allocateResources(1).firstIndex());
        } finally {
            releaseWrite.countDown();
            workers.shutdownNow();
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

    private static DescriptorHeapAllocationCore allocations() {
        return new DescriptorHeapAllocationCore(
                new GpuDescriptorHeapProperties(32, 8, 16, 8, 8, 4, 256, 64),
                64, 16, 4096, 1024);
    }

    private record Flush(long offset, long size) {
    }

    private static final class RecordingStorage implements DescriptorHeapStorage {
        private final DescriptorHeapKind kind;
        private final long deviceAddress;
        private final long mappedAddress;
        private final long size;
        private final List<Flush> flushes = new ArrayList<>();

        private RecordingStorage(DescriptorHeapKind kind, long deviceAddress, long mappedAddress, long size) {
            this.kind = kind;
            this.deviceAddress = deviceAddress;
            this.mappedAddress = mappedAddress;
            this.size = size;
        }

        @Override public DescriptorHeapKind kind() { return kind; }
        @Override public long deviceAddress() { return deviceAddress; }
        @Override public long mappedAddress() { return mappedAddress; }
        @Override public long sizeBytes() { return size; }
        @Override public void flush(long byteOffset, long byteSize) { flushes.add(new Flush(byteOffset, byteSize)); }
        @Override public void close() { }
    }

    private static class RecordingWriter implements DescriptorHeapNativeWriter {
        private final List<Long> destinations = new ArrayList<>();

        @Override public void writeSampler(long destinationHostAddress, VkSamplerCreateInfo sampler) {
            destinations.add(destinationHostAddress);
        }

        @Override public void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource) {
            destinations.add(destinationHostAddress);
        }

        @Override public void writeAccelerationStructure(long destinationHostAddress, long accelerationStructure) {
            destinations.add(destinationHostAddress);
        }
    }
}
