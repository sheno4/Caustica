/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly.vulkan;

import dev.comfyfluffy.cloudly.api.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** Addressable mapped VMA allocation registered for buffer and texel-buffer descriptors. */
public final class VmaMappedBuffer implements AutoCloseable {
    private final GpuDevice gpu;
    private final long handle, allocation, bytes, address, mapping;
    private boolean closed;
    private VmaMappedBuffer(GpuDevice gpu, long handle, long allocation, long bytes, long address, long mapping) {
        this.gpu = gpu; this.handle = handle; this.allocation = allocation;
        this.bytes = bytes; this.address = address; this.mapping = mapping;
        gpu.descriptorHeap().registerBuffer(address, handle, bytes);
    }
    public static VmaMappedBuffer create(GpuDevice gpu, long bytes, int usage, String label) {
        return create(gpu, bytes, usage, 0L, label);
    }
    public static VmaMappedBuffer create(GpuDevice gpu, long bytes, int usage, long alignment, String label) {
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid mapped buffer size");
        if (alignment < 0 || (alignment != 0 && (alignment & (alignment - 1)) != 0)) {
            throw new IllegalArgumentException("Buffer alignment must be zero or a power of two");
        }
        long handle = 0, allocation = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var memory = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            var out = stack.callocLong(1);
            var allocationOut = stack.callocPointer(1);
            var result = VmaAllocationInfo.calloc(stack);
            int status = alignment == 0 ? Vma.vmaCreateBuffer(gpu.vmaAllocator(), info, memory, out, allocationOut, result)
                    : Vma.vmaCreateBufferWithAlignment(gpu.vmaAllocator(), info, memory, alignment, out, allocationOut, result);
            handle = out.get(0); allocation = allocationOut.get(0);
            VulkanChecks.check(status, "vmaCreateBuffer(" + label + ")");
            if (result.pMappedData() == 0) throw new IllegalStateException("VMA buffer is not mapped: " + label);
            long address = VK12.vkGetBufferDeviceAddress(gpu.vk(), VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(handle));
            if (address == 0) throw new IllegalStateException("VMA buffer has no device address: " + label);
            Vma.vmaSetAllocationName(gpu.vmaAllocator(), allocation, label);
            return new VmaMappedBuffer(gpu, handle, allocation, bytes, address, result.pMappedData());
        } catch (RuntimeException | Error failure) {
            if (handle != 0) Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
            throw failure;
        }
    }
    public static VmaMappedBuffer createAsync(GpuDevice gpu, long bytes, int usage, String label) {
        return create(gpu, bytes, usage, label);
    }
    public long buffer() { return handle; }
    public long byteSize() { return bytes; }
    public VulkanDeviceAddressRange deviceRange() { return new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), bytes); }
    public VulkanDeviceAddress deviceAddressAt(long offset) {
        if (offset < 0 || offset >= bytes) throw new IndexOutOfBoundsException("Buffer device-address offset");
        return new VulkanDeviceAddress(address + offset);
    }
    public ByteBuffer mapped() {
        if (closed) throw new IllegalStateException("Buffer is closed");
        return MemoryUtil.memByteBuffer(mapping, Math.toIntExact(bytes));
    }
    private void checkRange(long offset, long count) {
        if (closed) throw new IllegalStateException("Buffer is closed");
        if (offset < 0 || count <= 0 || offset > bytes || count > bytes - offset) throw new IndexOutOfBoundsException("Mapped range");
    }
    public void flush(long offset, long count) {
        checkRange(offset, count);
        Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, offset, count);
    }
    public void invalidate(long offset, long count) {
        checkRange(offset, count);
        Vma.vmaInvalidateAllocation(gpu.vmaAllocator(), allocation, offset, count);
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        gpu.descriptorHeap().unregisterBuffer(address, handle);
        Vma.vmaDestroyBuffer(gpu.vmaAllocator(), handle, allocation);
    }
}
