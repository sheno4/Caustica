package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;

import java.nio.LongBuffer;
import java.util.Objects;

/** Immediate owner of one device-local {@code VkImage} and its VMA allocation. */
public final class VmaImageAllocation implements AutoCloseable {
    private final long allocator;
    private final long image;
    private final long allocation;
    private boolean closed;

    private VmaImageAllocation(long allocator, long image, long allocation) {
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
    }

    /**
     * Allocates the image described by {@code createInfo}. The create info is consumed synchronously and
     * may describe any Vulkan image dimensionality, mip chain, format, or usage combination.
     */
    public static VmaImageAllocation create(GpuDevice gpu, VkImageCreateInfo createInfo, String label) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(createInfo, "createInfo");
        Objects.requireNonNull(label, "label");
        long image = 0L;
        long allocation = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imageOut = stack.callocLong(1);
            PointerBuffer allocationOut = stack.callocPointer(1);
            int result = Vma.vmaCreateImage(gpu.vmaAllocator(), createInfo, allocationInfo,
                    imageOut, allocationOut, null);
            image = imageOut.get(0);
            allocation = allocationOut.get(0);
            VulkanChecks.check(result, "vmaCreateImage(" + label + ")");
            return new VmaImageAllocation(gpu.vmaAllocator(), image, allocation);
        } catch (RuntimeException | Error failure) {
            if (image != 0L) Vma.vmaDestroyImage(gpu.vmaAllocator(), image, allocation);
            throw failure;
        }
    }

    /** Raw non-dispatchable image handle consumed by Vulkan commands and descriptors. */
    public long image() { return image; }

    /** Destroys the image immediately after all GPU uses and dependent views have drained. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Vma.vmaDestroyImage(allocator, image, allocation);
    }
}
