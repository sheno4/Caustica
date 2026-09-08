package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Immediate owner of a persistently mapped, device-addressable VMA buffer.
 *
 * <p>{@link #close()} destroys the allocation immediately and does not arrange GPU retirement. Callers
 * must drain or retire every GPU use before closing the owner.
 */
public final class VmaMappedBuffer implements AutoCloseable {
    private final long allocator;
    private final long buffer;
    private final long allocation;
    private final VulkanDeviceAddressRange deviceRange;
    private final ByteBuffer mapped;
    private boolean closed;

    private VmaMappedBuffer(long allocator, long buffer, long allocation,
                            VulkanDeviceAddressRange deviceRange, ByteBuffer mapped) {
        this.allocator = allocator;
        this.buffer = buffer;
        this.allocation = allocation;
        this.deviceRange = deviceRange;
        this.mapped = mapped;
    }

    /**
     * Allocates mapped sequential-write memory with exclusive queue sharing. The requested usage is
     * combined with {@code VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT}.
     */
    public static VmaMappedBuffer create(GpuDevice gpu, long byteSize, int usage, String label) {
        return create(gpu, byteSize, usage, 0L, label, false);
    }

    /** Allocates mapped memory shared by the graphics and renderer async-compute queue families. */
    public static VmaMappedBuffer createAsync(GpuDevice gpu, long byteSize, int usage, String label) {
        return create(gpu, byteSize, usage, 0L, label, true);
    }

    /**
     * Allocates the same buffer with an optional VMA allocation alignment. A zero alignment uses VMA's
     * ordinary buffer allocation path.
     */
    public static VmaMappedBuffer create(GpuDevice gpu, long byteSize, int usage,
                                         long allocationAlignment, String label) {
        return create(gpu, byteSize, usage, allocationAlignment, label, false);
    }

    private static VmaMappedBuffer create(GpuDevice gpu, long byteSize, int usage,
                                          long allocationAlignment, String label, boolean asyncShared) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(label, "label");
        if (byteSize <= 0L) throw new IllegalArgumentException("byteSize must be positive");
        if (byteSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mapped buffers cannot exceed ByteBuffer capacity");
        }
        if (allocationAlignment < 0L) {
            throw new IllegalArgumentException("allocationAlignment must be non-negative");
        }

        long buffer = 0L;
        long allocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(byteSize)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            if (asyncShared) {
                configureAsyncSharing(bufferInfo, stack, gpu.asyncBufferSharingQueueFamilies());
            }
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer bufferOut = stack.callocLong(1);
            PointerBuffer allocationOut = stack.callocPointer(1);
            VmaAllocationInfo resultInfo = VmaAllocationInfo.calloc(stack);
            int result = allocationAlignment == 0L
                    ? Vma.vmaCreateBuffer(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    bufferOut, allocationOut, resultInfo)
                    : Vma.vmaCreateBufferWithAlignment(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    allocationAlignment, bufferOut, allocationOut, resultInfo);
            buffer = bufferOut.get(0);
            allocation = allocationOut.get(0);
            VulkanChecks.check(result, allocationAlignment == 0L
                    ? "vmaCreateBuffer(" + label + ")"
                    : "vmaCreateBufferWithAlignment(" + label + ")");

            long address = VK12.vkGetBufferDeviceAddress(gpu.vk(),
                    VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
            long mappedAddress = resultInfo.pMappedData();
            if (address == 0L || mappedAddress == 0L) {
                throw new IllegalStateException(label + " buffer is not mapped and device-addressable");
            }
            ByteBuffer mapped = MemoryUtil.memByteBuffer(mappedAddress, Math.toIntExact(byteSize));
            return new VmaMappedBuffer(gpu.vmaAllocator(), buffer, allocation,
                    new VulkanDeviceAddressRange(new VulkanDeviceAddress(address), byteSize), mapped);
        } catch (RuntimeException | Error failure) {
            if (buffer != 0L) Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
            throw failure;
        }
    }

    static void configureAsyncSharing(VkBufferCreateInfo bufferInfo, MemoryStack stack, int[] queueFamilies) {
        if (queueFamilies.length == 0) {
            throw new IllegalArgumentException("async buffer needs at least one queue family");
        }
        // A single family needs no ownership transfers; concurrent sharing requires distinct families.
        if (queueFamilies.length > 1) {
            bufferInfo.sharingMode(VK10.VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(stack.ints(queueFamilies));
        }
    }

    /** Raw {@code VkBuffer} handle for Vulkan calls that consume the native non-dispatchable handle. */
    public long buffer() { return buffer; }

    public long byteSize() { return deviceRange.byteSize(); }

    public VulkanDeviceAddressRange deviceRange() { return deviceRange; }

    /** Returns the typed address at a byte offset contained by this buffer. */
    public VulkanDeviceAddress deviceAddressAt(long byteOffset) {
        if (byteOffset < 0L || byteOffset >= byteSize()) {
            throw new IllegalArgumentException("buffer offset is outside range");
        }
        return deviceRange.address().addBytes(byteOffset);
    }

    /** Returns a fresh view of the persistent mapping with independent position and limit. */
    public ByteBuffer mapped() { return mapped.duplicate(); }

    /** Flushes a non-empty byte range after host writes. */
    public void flush(long byteOffset, long byteLength) {
        if (byteOffset < 0L || byteLength <= 0L
                || Math.addExact(byteOffset, byteLength) > byteSize()) {
            throw new IllegalArgumentException("flush range is outside buffer");
        }
        Vma.vmaFlushAllocation(allocator, allocation, byteOffset, byteLength);
    }

    /** Destroys the buffer immediately. Every GPU use must already be drained or retired. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyBuffer(allocator, buffer, allocation);
    }
}
