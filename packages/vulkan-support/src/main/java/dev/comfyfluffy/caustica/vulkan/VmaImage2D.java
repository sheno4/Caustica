package dev.comfyfluffy.caustica.vulkan;

import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.gpu.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;

import java.nio.LongBuffer;
import java.util.Objects;

/**
 * Extension-owned VMA 2D image with one storage and one sampled descriptor in the renderer's resource
 * heap. The image starts in {@code VK_IMAGE_LAYOUT_UNDEFINED}; record
 * {@link ComputeSynchronization#initializeImages} before its first shader access.
 */
public final class VmaImage2D implements AutoCloseable {
    private final long allocator;
    private final long image;
    private final long allocation;
    private final long view;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
    private final int width;
    private final int height;
    private final int format;
    private final ResourceLifetime lifetime;

    private VmaImage2D(VkDevice device, long allocator, long image, long allocation, long view,
                       GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors,
                       int width, int height, int format) {
        this.allocator = allocator;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.descriptors = descriptors;
        this.width = width;
        this.height = height;
        this.format = format;
        this.lifetime = new ResourceLifetime(descriptors::destroy,
                () -> VK10.vkDestroyImageView(device, view, null),
                () -> Vma.vmaDestroyImage(allocator, image, allocation));
    }

    /** Allocate a single-mip image usable for both sampled reads and storage reads/writes. */
    public static VmaImage2D create(GpuDevice gpu, int width, int height, int format, String label) {
        return create(gpu, width, height, format, 0, label);
    }

    /** Allocate a sampled/storage image with additional attachment or transfer usage. */
    public static VmaImage2D create(GpuDevice gpu, int width, int height, int format,
                                    int additionalUsage, String label) {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(label, "label");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("image extent must be positive");

        long image = 0L;
        long allocation = 0L;
        long viewHandle = 0L;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format).mipLevels(1).arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_STORAGE_BIT | additionalUsage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(width, height, 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VulkanChecks.check(Vma.vmaCreateImage(gpu.vmaAllocator(), imageInfo, allocationInfo,
                    imageOut, allocationOut, null), "vmaCreateImage(" + label + ")");
            image = imageOut.get(0);
            allocation = allocationOut.get(0);

            descriptors = gpu.descriptorHeap().allocateResources(2, label);
            VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            view.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer viewOut = stack.mallocLong(1);
            VulkanChecks.check(VK10.vkCreateImageView(gpu.vk(), view, null, viewOut),
                    "vkCreateImageView(" + label + ")");
            viewHandle = viewOut.get(0);
            VkImageDescriptorInfoEXT imageDescriptor = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                    .pView(view).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            GpuDescriptorWriter writer = gpu.descriptorHeap().writer();
            writer.writeResource(descriptors, 0, resource(stack, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    imageDescriptor));
            writer.writeResource(descriptors, 1, resource(stack, VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
                    imageDescriptor));
            return new VmaImage2D(gpu.vk(), gpu.vmaAllocator(), image, allocation, viewHandle,
                    descriptors, width, height, format);
        } catch (RuntimeException | Error failure) {
            if (descriptors != null) descriptors.destroy();
            if (viewHandle != 0L) VK10.vkDestroyImageView(gpu.vk(), viewHandle, null);
            if (image != 0L) Vma.vmaDestroyImage(gpu.vmaAllocator(), image, allocation);
            throw failure;
        }
    }

    private static VkResourceDescriptorInfoEXT resource(MemoryStack stack, int type,
                                                         VkImageDescriptorInfoEXT image) {
        return VkResourceDescriptorInfoEXT.calloc(stack).sType$Default().type(type)
                .data(data -> data.pImage(image));
    }

    public long image() { return image; }
    public long view() { return view; }
    public int width() { return width; }
    public int height() { return height; }
    public int format() { return format; }
    public GpuDescriptorIndex.Resource storageIndex() { return descriptors.firstIndex(); }
    public GpuDescriptorIndex.Resource sampledIndex() {
        return new GpuDescriptorIndex.Resource(descriptors.firstIndex().value() + 1);
    }

    /** Release the heap range before freeing the image it describes. Every GPU use must already be drained. */
    @Override
    public void close() {
        lifetime.close();
    }
}
