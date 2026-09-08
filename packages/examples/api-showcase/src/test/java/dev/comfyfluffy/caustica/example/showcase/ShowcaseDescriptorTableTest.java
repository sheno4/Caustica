package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeap;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorHeapProperties;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ShowcaseDescriptorTableTest {
    @Test
    void failedReplacementReleasesPartialAllocationAndKeepsPublishedGeneration() {
        for (String stage : List.of("sampler allocation", "resource write", "sampler write", "factory")) {
            Device device = new Device();
            var failure = new IllegalStateException(stage);
            try (var directory = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(
                    cleanup -> { throw new AssertionError(cleanup); });
                 MemoryStack stack = MemoryStack.stackPush()) {
                var factory = directory.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1));
                try (var table = new ShowcaseDescriptorTable(device, retired -> {
                    if ("factory".equals(device.heap.failureStage)) throw failure;
                    return factory.create(retired);
                })) {
                    var resource = VkResourceDescriptorInfoEXT.calloc(stack);
                    var sampler = VkSamplerCreateInfo.calloc(stack).sType$Default();
                    var first = table.replace(resource, sampler);
                    device.heap.failureStage = stage;
                    device.heap.failure = failure;
                    assertSame(failure, assertThrows(IllegalStateException.class,
                            () -> table.replace(resource, sampler)));
                    assertFalse(device.heap.resources.getFirst().destroyed);
                    assertTrue(device.heap.resources.getLast().destroyed);
                    assertEquals(stage.equals("sampler allocation") ? 1 : 2, device.heap.samplers.size());
                    if (!stage.equals("sampler allocation")) assertTrue(device.heap.samplers.getLast().destroyed);
                    Frame frame = new Frame();
                    assertEquals(first, table.capture(frame));
                    frame.complete();
                }
                directory.awaitRetirements();
                assertTrue(device.heap.resources.getFirst().destroyed);
                assertTrue(device.heap.samplers.getFirst().destroyed);
            }
        }
    }

    @Test
    void retiringGenerationDestroysBothRangesEvenWhenBothThrowTheSameFailure() {
        Device device = new Device();
        var failures = new ArrayList<Throwable>();
        var failure = new IllegalStateException("destruction");
        try (var directory = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(failures::add);
             var table = new ShowcaseDescriptorTable(device,
                     directory.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1)));
             MemoryStack stack = MemoryStack.stackPush()) {
            table.replace(VkResourceDescriptorInfoEXT.calloc(stack), VkSamplerCreateInfo.calloc(stack));
            device.heap.resources.getFirst().destroyFailure = failure;
            device.heap.samplers.getFirst().destroyFailure = failure;
            table.close();
            table.close();
            directory.awaitRetirements();
            assertEquals(1, device.heap.resources.getFirst().destroyCalls);
            assertEquals(1, device.heap.samplers.getFirst().destroyCalls);
            assertEquals(List.of(failure), failures);
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test
    void replacementRetainsEachGenerationUntilItsLastFrameCompletes() {
        Device device = new Device();
        try (var directory = new dev.comfyfluffy.caustica.engine.resource.ResourceDirectory(
                failure -> { throw new AssertionError(failure); });
             ShowcaseDescriptorTable table = new ShowcaseDescriptorTable(device,
                     directory.openFactory(new dev.comfyfluffy.caustica.engine.session.ContributionOwner(1)));
             MemoryStack stack = MemoryStack.stackPush()) {
            var resource = VkResourceDescriptorInfoEXT.calloc(stack);
            var sampler = VkSamplerCreateInfo.calloc(stack).sType$Default();
            table.replace(resource, sampler);
            Frame firstFrame = new Frame();
            var first = table.capture(firstFrame);
            Frame abandoned = new Frame();
            table.capture(abandoned);
            var second = table.replace(resource, sampler);
            Frame secondFrame = new Frame();
            table.capture(secondFrame);
            table.close();
            abandoned.complete();
            directory.awaitRetirements();
            assertFalse(device.heap.resources.getFirst().destroyed);
            assertFalse(device.heap.resources.getLast().destroyed);
            assertEquals(0, first.texture().value());
            assertEquals(1, second.texture().value());
            assertEquals(List.of("resource:0", "sampler:0", "resource:1", "sampler:1"), device.heap.writes);
            secondFrame.complete();
            directory.awaitRetirements();
            assertFalse(device.heap.resources.getFirst().destroyed);
            assertTrue(device.heap.resources.getLast().destroyed);
            firstFrame.complete();
            directory.awaitRetirements();
            assertTrue(device.heap.resources.getFirst().destroyed);
            assertTrue(device.heap.samplers.getFirst().destroyed);
            assertTrue(device.heap.samplers.getLast().destroyed);
        }
    }

    private static final class Frame implements dev.comfyfluffy.caustica.api.resource.FrameResources {
        final dev.comfyfluffy.caustica.engine.resource.ResourceOwners resources =
                new dev.comfyfluffy.caustica.engine.resource.ResourceOwners();
        @Override public void retain(dev.comfyfluffy.caustica.api.resource.ResourceOwner resource) { resources.retain(resource); }
        void complete() { resources.close(); }
    }

    private static final class Device implements GpuDevice {
        private final Heap heap = new Heap();
        @Override public VkDevice vk() { throw new AssertionError(); }
        @Override public long vmaAllocator() { throw new AssertionError(); }
        @Override public int[] asyncBufferSharingQueueFamilies() { throw new AssertionError(); }
        @Override public GpuDescriptorHeap descriptorHeap() { return heap; }
    }

    private static final class Heap implements GpuDescriptorHeap, GpuDescriptorWriter {
        private String failureStage;
        private RuntimeException failure;
        private final List<Range<GpuDescriptorIndex.Resource>> resources = new ArrayList<>();
        private final List<Range<GpuDescriptorIndex.Sampler>> samplers = new ArrayList<>();
        private final List<String> writes = new ArrayList<>();
        @Override public GpuDescriptorHeapProperties properties() { throw new AssertionError(); }
        @Override public GpuDescriptorRange<GpuDescriptorIndex.Resource> allocateResources(int count) {
            var range = new Range<>(new GpuDescriptorIndex.Resource(resources.size()), count);
            resources.add(range);
            return range;
        }
        @Override public GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocateSamplers(int count) {
            if ("sampler allocation".equals(failureStage)) throw failure;
            var range = new Range<>(new GpuDescriptorIndex.Sampler(samplers.size()), count);
            samplers.add(range);
            return range;
        }
        @Override public GpuDescriptorWriter writer() { return this; }
        @Override public void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination,
                                           int relativeIndex, VkSamplerCreateInfo sampler) {
            if ("sampler write".equals(failureStage)) throw failure;
            writes.add("sampler:" + destination.firstIndex().value());
        }
        @Override public void writeSamplers(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination,
                                            int relativeIndex, VkSamplerCreateInfo.Buffer samplers) {
            throw new AssertionError();
        }
        @Override public void writeImages(GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
                                          int relativeIndex, List<ImageWrite> images) {
            throw new AssertionError();
        }
        @Override public void writeResource(GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
                                            int relativeIndex, VkResourceDescriptorInfoEXT resource) {
            if ("resource write".equals(failureStage)) throw failure;
            writes.add("resource:" + destination.firstIndex().value());
        }
        @Override public void writeAccelerationStructure(
                GpuDescriptorRange<GpuDescriptorIndex.Resource> destination,
                int relativeIndex, long accelerationStructure) { throw new AssertionError(); }
    }

    private static final class Range<I extends dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex>
            implements GpuDescriptorRange<I> {
        private final I first;
        private final int count;
        private boolean destroyed;
        private int destroyCalls;
        private RuntimeException destroyFailure;
        private Range(I first, int count) { this.first = first; this.count = count; }
        @Override public I firstIndex() { return first; }
        @Override public int descriptorCount() { return count; }
        @Override public void destroy() {
            destroyed = true;
            destroyCalls++;
            if (destroyFailure != null) throw destroyFailure;
        }
    }
}
