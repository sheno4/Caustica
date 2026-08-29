package dev.comfyfluffy.caustica.engine.vulkan.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;

final class VmaGpuImage implements GpuImage {
    private final long vma;
    private final VkDevice vk;
    private final long image;
    private final long allocation;
    private final long view;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptors;
    private final GpuImageDescriptor storageDescriptor;
    private final GpuImageDescriptor sampledDescriptor;
    private final int width;
    private final int height;
    private final int format;
    private boolean destroyed;

    VmaGpuImage(long vma, VkDevice vk, VulkanDescriptorHeap heap, long image, long allocation, long view,
                int width, int height, int format, int usage, String label) {
        this.vma = vma;
        this.vk = vk;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
        this.format = format;
        boolean storage = (usage & VK10.VK_IMAGE_USAGE_STORAGE_BIT) != 0;
        boolean sampled = (usage & VK10.VK_IMAGE_USAGE_SAMPLED_BIT) != 0;
        int descriptorCount = (storage ? 1 : 0) + (sampled ? 1 : 0);
        descriptors = descriptorCount == 0 ? null : heap.allocateResources(descriptorCount, label);
        GpuImageDescriptor createdStorage;
        GpuImageDescriptor createdSampled;
        try {
            int slot = 0;
            createdStorage = storage ? writeDescriptor(heap, slot++, GpuImageDescriptorKind.STORAGE,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) : null;
            createdSampled = sampled ? writeDescriptor(heap, slot, GpuImageDescriptorKind.SAMPLED,
                    VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE) : null;
        } catch (Throwable failure) {
            if (descriptors != null) descriptors.destroy();
            throw failure;
        }
        storageDescriptor = createdStorage;
        sampledDescriptor = createdSampled;
    }

    @Override public long image() { return image; }
    @Override public long view() { return view; }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int format() { return format; }
    @Override
    public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
        GpuImageDescriptor descriptor = switch (kind) {
            case STORAGE -> storageDescriptor;
            case SAMPLED -> sampledDescriptor;
        };
        if (descriptor == null) throw new IllegalArgumentException("Image was not created for " + kind);
        return descriptor;
    }

    private GpuImageDescriptor writeDescriptor(VulkanDescriptorHeap heap, int slot,
                                               GpuImageDescriptorKind kind, int descriptorType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkImageDescriptorInfoEXT imageInfo = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                    .pView(viewInfo).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                    .type(descriptorType).data(data -> data.pImage(imageInfo));
            heap.writer().writeResource(descriptors, slot, resource);
        }
        GpuDescriptorIndex.Resource index = new GpuDescriptorIndex.Resource(
                descriptors.firstIndex().value() + slot);
        return new ImageDescriptor(index, kind);
    }

    private record ImageDescriptor(GpuDescriptorIndex.Resource index,
                                   GpuImageDescriptorKind kind) implements GpuImageDescriptor {}

    @Override
    public void destroy() {
        if (destroyed) return;
        if (descriptors != null) descriptors.destroy();
        if (view != 0L) VK10.vkDestroyImageView(vk, view, null);
        if (image != 0L) Vma.vmaDestroyImage(vma, image, allocation);
        destroyed = true;
    }
}
