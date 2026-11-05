/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.api.vulkan;

import dev.comfyfluffy.cloudly.vulkan.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Local handles for immutable native views; dispatches copy these into ordinary descriptor sets. */
public final class GpuDescriptorHeap implements AutoCloseable, GpuDescriptorWriter {
    public record Entry(int type, long imageView, int layout, long sampler,
                        long buffer, long offset, long byteSize, long bufferView, Runnable destroy) { }
    private record Buffer(long address, long handle, long bytes) { }
    private final GpuDevice gpu;
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final NavigableMap<Long, Buffer> buffers = new TreeMap<>(Long::compareUnsigned);
    private int next = 1;
    GpuDescriptorHeap(GpuDevice gpu) { this.gpu = gpu; }
    public GpuDescriptorWriter writer() { return this; }
    public synchronized GpuDescriptorRange<GpuDescriptorIndex.Resource> allocateResources(int count) {
        return allocate(count, false);
    }
    public synchronized GpuDescriptorRange<GpuDescriptorIndex.Sampler> allocateSamplers(int count) {
        return allocate(count, true);
    }
    @SuppressWarnings("unchecked")
    private <I extends GpuDescriptorIndex> GpuDescriptorRange<I> allocate(int count, boolean sampler) {
        if (count <= 0) throw new IllegalArgumentException("Descriptor count must be positive");
        int first = next;
        next = Math.addExact(next, count);
        I index = (I) (sampler ? new GpuDescriptorIndex.Sampler(first) : new GpuDescriptorIndex.Resource(first));
        return new GpuDescriptorRange<>() {
            private boolean closed;
            public I firstIndex() { return index; }
            public int descriptorCount() { return count; }
            public void destroy() {
                synchronized (GpuDescriptorHeap.this) {
                    if (closed) return;
                    closed = true;
                    for (int slot = first; slot < first + count; slot++) {
                        Entry old = entries.remove(slot);
                        if (old != null) old.destroy().run();
                    }
                }
            }
        };
    }
    private int slot(GpuDescriptorRange<?> range, int offset) {
        if (offset < 0 || offset >= range.descriptorCount()) throw new IndexOutOfBoundsException(offset);
        return Math.addExact(range.firstIndex().value(), offset);
    }
    private synchronized void put(int slot, Entry entry) {
        if (entries.containsKey(slot)) {
            entry.destroy().run();
            throw new IllegalStateException("Cloudly descriptors are immutable until retirement");
        }
        entries.put(slot, entry);
    }
    public synchronized Entry resolve(GpuDescriptorIndex index) {
        Entry result = entries.get(index.value());
        if (result == null) throw new IllegalStateException("Uninitialized or retired Cloudly descriptor " + index);
        return result;
    }
    public long imageView(GpuDescriptorIndex.Resource index) { return resolve(index).imageView(); }
    public long samplerHandle(GpuDescriptorIndex.Sampler index) { return resolve(index).sampler(); }
    public synchronized void registerBuffer(long address, long handle, long bytes) {
        Buffer existing = buffers.putIfAbsent(address, new Buffer(address, handle, bytes));
        if (existing != null && existing.handle() != handle) throw new IllegalStateException("Duplicate buffer device address");
    }
    public synchronized void unregisterBuffer(long address, long handle) {
        Buffer existing = buffers.get(address);
        if (existing != null && existing.handle() == handle) buffers.remove(address);
    }
    private synchronized Buffer containing(long address, long bytes) {
        var floor = buffers.floorEntry(address);
        if (floor == null) throw new IllegalStateException("Unregistered Cloudly buffer device address");
        Buffer buffer = floor.getValue();
        long offset = address - buffer.address();
        if (offset < 0 || bytes <= 0 || offset > buffer.bytes() || bytes > buffer.bytes() - offset) {
            throw new IllegalArgumentException("Descriptor lies outside its registered buffer");
        }
        return buffer;
    }
    public void writeBorrowedImage(GpuDescriptorRange<GpuDescriptorIndex.Resource> range, int offset,
                                   GpuImageDescriptorKind kind, long view, int layout) {
        put(slot(range, offset), new Entry(kind == GpuImageDescriptorKind.STORAGE ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, view, layout, 0, 0, 0, 0, 0, () -> { }));
    }
    @Override public void writeSampler(GpuDescriptorRange<GpuDescriptorIndex.Sampler> range, int offset,
                                       VkSamplerCreateInfo info) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            VulkanChecks.check(vkCreateSampler(gpu.vk(), info, null, out), "vkCreateSampler(Cloudly)");
            long sampler = out.get(0);
            put(slot(range, offset), new Entry(VK_DESCRIPTOR_TYPE_SAMPLER, 0, 0, sampler, 0, 0, 0, 0,
                    () -> vkDestroySampler(gpu.vk(), sampler, null)));
        }
    }
    @Override public void writeSamplers(GpuDescriptorRange<GpuDescriptorIndex.Sampler> range, int offset,
                                        VkSamplerCreateInfo.Buffer infos) {
        for (int i = 0; i < infos.remaining(); i++) writeSampler(range, offset + i, infos.get(infos.position() + i));
    }
    private void writeImage(GpuDescriptorRange<GpuDescriptorIndex.Resource> range, int offset,
                            int type, ImageDescriptor info) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            VulkanChecks.check(vkCreateImageView(gpu.vk(), info.view(), null, out), "vkCreateImageView(Cloudly)");
            long view = out.get(0);
            put(slot(range, offset), new Entry(type, view, info.layout(), 0, 0, 0, 0, 0,
                    () -> vkDestroyImageView(gpu.vk(), view, null)));
        }
    }
    @Override public void writeImages(GpuDescriptorRange<GpuDescriptorIndex.Resource> range, int offset,
                                      List<ImageWrite> images) {
        for (int i = 0; i < images.size(); i++) {
            var image = images.get(i);
            writeImage(range, offset + i, image.kind() == GpuImageDescriptorKind.STORAGE
                    ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, image.descriptor());
        }
    }
    @Override public void writeResource(GpuDescriptorRange<GpuDescriptorIndex.Resource> range, int offset,
                                        ResourceDescriptor info) {
        int type = info.type();
        if (type == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE || type == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) {
            writeImage(range, offset, type, info.image());
            return;
        }
        if (type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            Buffer buffer = containing(info.address(), info.byteSize());
            put(slot(range, offset), new Entry(type, 0, 0, 0, buffer.handle(), info.address() - buffer.address(), info.byteSize(), 0, () -> { }));
            return;
        }
        if (type == VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER || type == VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER) {
            Buffer buffer = containing(info.address(), info.byteSize());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var ci = VkBufferViewCreateInfo.calloc(stack).sType$Default().buffer(buffer.handle()).format(info.format())
                        .offset(info.address() - buffer.address()).range(info.byteSize());
                var out = stack.mallocLong(1);
                VulkanChecks.check(vkCreateBufferView(gpu.vk(), ci, null, out), "vkCreateBufferView(Cloudly)");
                long view = out.get(0);
                put(slot(range, offset), new Entry(type, 0, 0, 0, buffer.handle(), ci.offset(), ci.range(), view,
                        () -> vkDestroyBufferView(gpu.vk(), view, null)));
            }
            return;
        }
        throw new UnsupportedOperationException("Unsupported Cloudly descriptor type " + type);
    }
    @Override public void writeAccelerationStructure(GpuDescriptorRange<GpuDescriptorIndex.Resource> range,
                                                     int offset, long accelerationStructure) {
        throw new UnsupportedOperationException("Cloudly native programs do not bind acceleration structures");
    }
    @Override public synchronized void close() {
        if (!entries.isEmpty() || !buffers.isEmpty()) throw new IllegalStateException("Cloudly resources remain live at device closure");
    }
}
