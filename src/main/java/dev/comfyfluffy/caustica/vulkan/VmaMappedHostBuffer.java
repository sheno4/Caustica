package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/** Immediate owner of a persistently mapped VMA buffer that does not require a device address. */
public final class VmaMappedHostBuffer implements AutoCloseable {
    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final ByteBuffer mapped;
    private final long byteSize;
    private boolean closed;

    private VmaMappedHostBuffer(long allocator, long buffer, long allocation,
                                ByteBuffer mapped, long byteSize) {
        this.allocator = allocator;
        this.buffer = buffer;
        this.allocation = allocation;
        this.mapped = mapped;
        this.byteSize = byteSize;
    }

    /** Allocates mapped sequential-write memory with exactly the requested Vulkan usage flags. */
    public static VmaMappedHostBuffer create(GpuDevice gpu, long byteSize, int usage, String label) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(label, "label");
        if (byteSize <= 0L) throw new IllegalArgumentException("byteSize must be positive");
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mapped buffers cannot exceed ByteBuffer capacity");
        }

        long buffer = 0L;
        long allocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(byteSize).usage(usage).sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer bufferOut = stack.callocLong(1);
            PointerBuffer allocationOut = stack.callocPointer(1);
            VmaAllocationInfo resultInfo = VmaAllocationInfo.calloc(stack);
            int result = Vma.vmaCreateBuffer(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    bufferOut, allocationOut, resultInfo);
            buffer = bufferOut.get(0);
            allocation = allocationOut.get(0);
            VulkanChecks.check(result, "vmaCreateBuffer(" + label + ")");
            if (resultInfo.pMappedData() == 0L) {
                throw new IllegalStateException(label + " buffer is not mapped");
            }
            return new VmaMappedHostBuffer(gpu.vmaAllocator(), buffer, allocation,
                    MemoryUtil.memByteBuffer(resultInfo.pMappedData(), Math.toIntExact(byteSize)), byteSize);
        } catch (RuntimeException | Error failure) {
            if (buffer != 0L) Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
            throw failure;
        }
    }

    /** Raw {@code VkBuffer} handle for transfer, vertex-input, and other native commands. */
    public long buffer() { return buffer; }

    public long byteSize() { return byteSize; }

    /** Returns a fresh view of the persistent mapping with independent position and limit. */
    public ByteBuffer mapped() { return mapped.duplicate(); }

    /** Flushes a non-empty range after host writes. */
    public void flush(long byteOffset, long byteLength) {
        if (byteOffset < 0L || byteLength <= 0L
                || Math.addExact(byteOffset, byteLength) > byteSize) {
            throw new IllegalArgumentException("flush range is outside buffer");
        }
        Vma.vmaFlushAllocation(allocator, allocation, byteOffset, byteLength);
    }

    /** Destroys the allocation immediately after all GPU uses have drained. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyBuffer(allocator, buffer, allocation);
    }
}
