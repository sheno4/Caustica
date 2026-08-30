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

final class ShowcaseDescriptorTableTest {
    @Test
    void replacementWritesFreshTypedSlotsAndRetiresThePublishedGeneration() {
        Device device = new Device();
        ShowcaseDescriptorTable table = new ShowcaseDescriptorTable(device);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var resource = VkResourceDescriptorInfoEXT.calloc(stack);
            var sampler = VkSamplerCreateInfo.calloc(stack).sType$Default();
            var first = table.replace(resource, sampler);
            var second = table.replace(resource, sampler);

            assertEquals(0, first.texture().value());
            assertEquals(0, first.sampler().value());
            assertEquals(1, second.texture().value());
            assertEquals(1, second.sampler().value());
            assertEquals(List.of("resource:0", "sampler:0", "resource:1", "sampler:1"),
                    device.heap.writes);
            assertFalse(device.heap.resources.getFirst().destroyed);
            device.retirements.removeFirst().run();
            assertTrue(device.heap.resources.getFirst().destroyed);
            assertTrue(device.heap.samplers.getFirst().destroyed);
        }

        table.close();
        assertEquals(1, device.retirements.size());
        device.retirements.removeFirst().run();
        assertTrue(device.heap.resources.getLast().destroyed);
        assertTrue(device.heap.samplers.getLast().destroyed);
    }

    private static final class Device implements GpuDevice {
        private final Heap heap = new Heap();
        private final List<Runnable> retirements = new ArrayList<>();
        @Override public VkDevice vk() { throw new AssertionError(); }
        @Override public long vmaAllocator() { throw new AssertionError(); }
        @Override public GpuDescriptorHeap descriptorHeap() { return heap; }
        @Override public void retireAfterUse(Runnable cleanup) { retirements.add(cleanup); }
    }

    private static final class Heap implements GpuDescriptorHeap, GpuDescriptorWriter {
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
            var range = new Range<>(new GpuDescriptorIndex.Sampler(samplers.size()), count);
            samplers.add(range);
            return range;
        }
        @Override public GpuDescriptorWriter writer() { return this; }
        @Override public void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> destination,
                                           int relativeIndex, VkSamplerCreateInfo sampler) {
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
        private Range(I first, int count) { this.first = first; this.count = count; }
        @Override public I firstIndex() { return first; }
        @Override public int descriptorCount() { return count; }
        @Override public void destroy() { destroyed = true; }
    }
}
