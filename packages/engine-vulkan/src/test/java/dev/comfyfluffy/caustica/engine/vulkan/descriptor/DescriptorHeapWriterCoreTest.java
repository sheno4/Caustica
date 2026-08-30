package dev.comfyfluffy.caustica.engine.vulkan.descriptor;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeapProperties;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
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
        RecordingStorage resources = resourceStorage();
        RecordingStorage samplers = samplerStorage();
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
                resourceStorage(),
                samplerStorage(),
                nativeWriter);
        var retired = allocations.allocateResources(1);
        retired.destroy();

        assertThrows(IllegalStateException.class, () -> writer.writeResource(retired, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> writer.writeAccelerationStructure(allocations.allocateResources(1), 0, 0L));
        assertEquals(List.of(), nativeWriter.destinations);
    }

    @Test
    void contiguousBatchesUseOneNativeCallAndOneFlush() {
        DescriptorHeapAllocationCore allocations = allocations();
        RecordingStorage resources = resourceStorage();
        RecordingStorage samplers = samplerStorage();
        RecordingWriter nativeWriter = new RecordingWriter();
        DescriptorHeapWriterCore writer = new DescriptorHeapWriterCore(
                allocations, resources, samplers, nativeWriter);
        var resourceRange = allocations.allocateResources(4);
        var samplerRange = allocations.allocateSamplers(3);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo.Buffer views = VkImageViewCreateInfo.calloc(2, stack);
            views.get(0).sType$Default().image(101L);
            views.get(1).sType$Default().image(202L);
            VkImageDescriptorInfoEXT.Buffer images = VkImageDescriptorInfoEXT.calloc(2, stack);
            images.get(0).sType$Default().pView(views.get(0)).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            images.get(1).sType$Default().pView(views.get(1)).layout(VK13.VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL);
            writer.writeImages(resourceRange, 1, List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.STORAGE, images.get(0)),
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, images.get(1))));

            VkSamplerCreateInfo.Buffer samplerInfos = VkSamplerCreateInfo.calloc(2, stack);
            samplerInfos.get(0).sType$Default().flags(11);
            samplerInfos.get(1).sType$Default().flags(22);
            writer.writeSamplers(samplerRange, 1, samplerInfos);
        }

        assertEquals(List.of(0x10000L + 96, 0x20000L + 24), nativeWriter.destinations);
        assertEquals(List.of(new Flush(96, 64)), resources.flushes);
        assertEquals(List.of(new Flush(24, 16)), samplers.flushes);
        assertEquals(List.of(List.of(GpuImageDescriptorKind.STORAGE, GpuImageDescriptorKind.SAMPLED)),
                nativeWriter.imageKinds);
        assertEquals(List.of(List.of(101L, 202L)), nativeWriter.imageViews);
        assertEquals(List.of(List.of(VK10.VK_IMAGE_LAYOUT_GENERAL, VK13.VK_IMAGE_LAYOUT_READ_ONLY_OPTIMAL)),
                nativeWriter.imageLayouts);
        assertEquals(List.of(List.of(11, 22)), nativeWriter.samplerFlags);
    }

    @Test
    void batchesRejectEmptyAndOutOfBoundsWritesBeforeNativeEncoding() {
        DescriptorHeapAllocationCore allocations = allocations();
        RecordingWriter nativeWriter = new RecordingWriter();
        DescriptorHeapWriterCore writer = new DescriptorHeapWriterCore(
                allocations,
                resourceStorage(),
                samplerStorage(),
                nativeWriter);
        var resources = allocations.allocateResources(2);
        var samplers = allocations.allocateSamplers(2);

        assertThrows(IllegalArgumentException.class, () -> writer.writeImages(resources, 0, List.of()));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default();
            List<GpuDescriptorWriter.ImageWrite> two = List.of(
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, image),
                    new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, image));
            assertThrows(IndexOutOfBoundsException.class, () -> writer.writeImages(resources, 1, two));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> writer.writeSamplers(samplers, 1, VkSamplerCreateInfo.calloc(2, stack)));
        }
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
                resourceStorage(),
                samplerStorage(),
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
                new GpuDescriptorHeapProperties(32, 8, 4),
                8, 16, 8, 256, 64, 64, 16, 4096, 1024);
    }

    private static RecordingStorage resourceStorage() {
        return new RecordingStorage(DescriptorHeapKind.RESOURCE,
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x1000), 4096), 0x10000);
    }

    private static RecordingStorage samplerStorage() {
        return new RecordingStorage(DescriptorHeapKind.SAMPLER,
                new VulkanDeviceAddressRange(new VulkanDeviceAddress(0x2000), 1024), 0x20000);
    }

    private record Flush(long offset, long size) {
    }

    private static final class RecordingStorage implements DescriptorHeapStorage {
        private final DescriptorHeapKind kind;
        private final VulkanDeviceAddressRange deviceRange;
        private final long mappedAddress;
        private final List<Flush> flushes = new ArrayList<>();

        private RecordingStorage(DescriptorHeapKind kind, VulkanDeviceAddressRange deviceRange,
                                 long mappedAddress) {
            this.kind = kind;
            this.deviceRange = deviceRange;
            this.mappedAddress = mappedAddress;
        }

        @Override public DescriptorHeapKind kind() { return kind; }
        @Override public VulkanDeviceAddressRange deviceRange() { return deviceRange; }
        @Override public long mappedAddress() { return mappedAddress; }
        @Override public void flush(long byteOffset, long byteSize) { flushes.add(new Flush(byteOffset, byteSize)); }
        @Override public void close() { }
    }

    private static class RecordingWriter implements DescriptorHeapNativeWriter {
        private final List<Long> destinations = new ArrayList<>();
        private final List<List<GpuImageDescriptorKind>> imageKinds = new ArrayList<>();
        private final List<List<Long>> imageViews = new ArrayList<>();
        private final List<List<Integer>> imageLayouts = new ArrayList<>();
        private final List<List<Integer>> samplerFlags = new ArrayList<>();

        @Override public void writeSampler(long destinationHostAddress, VkSamplerCreateInfo sampler) {
            destinations.add(destinationHostAddress);
        }

        @Override public void writeSamplers(long destinationHostAddress, VkSamplerCreateInfo.Buffer samplers) {
            destinations.add(destinationHostAddress);
            List<Integer> flags = new ArrayList<>();
            for (int index = samplers.position(); index < samplers.limit(); index++) {
                flags.add(samplers.get(index).flags());
            }
            samplerFlags.add(List.copyOf(flags));
        }

        @Override public void writeImages(long destinationHostAddress, List<GpuDescriptorWriter.ImageWrite> images) {
            destinations.add(destinationHostAddress);
            imageKinds.add(images.stream().map(GpuDescriptorWriter.ImageWrite::kind).toList());
            imageViews.add(images.stream().map(image -> image.descriptor().pView().image()).toList());
            imageLayouts.add(images.stream().map(image -> image.descriptor().layout()).toList());
        }

        @Override public void writeResource(long destinationHostAddress, VkResourceDescriptorInfoEXT resource) {
            destinations.add(destinationHostAddress);
        }

        @Override public void writeAccelerationStructure(long destinationHostAddress, long accelerationStructure) {
            destinations.add(destinationHostAddress);
        }
    }
}
