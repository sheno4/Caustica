package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.support.SharedResource;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.api.vulkan.OwnedGpuImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;

/** Shared Minecraft texture lease with an independently owned image view and sampled descriptor. */
public final class MinecraftVulkanImage implements OwnedGpuImage {
    private final VulkanGpuTextureView view;
    private final VulkanGpuTexture texture;
    private final VulkanDeviceContext gpu;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
    private final int width, height, format;
    private final long nativeView;
    private final SharedResource<GpuDescriptorRange<GpuDescriptorIndex.Resource>> owner;

    private MinecraftVulkanImage(VulkanDeviceContext gpu, VulkanGpuTextureView view, VulkanGpuTexture texture,
                                       GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor,
                                       int width, int height, int format, long nativeView) {
        this.gpu = gpu; this.view = view; this.texture = texture; this.descriptor = descriptor;
        this.width = width; this.height = height; this.format = format;
        this.nativeView = nativeView;
        owner = SharedResource.owned(descriptor,
                range -> gpu.deferDestroy(() -> {
                    try {
                        range.destroy();
                        VK10.vkDestroyImageView(gpu.vk(), nativeView, null);
                    } finally { MinecraftTextureLifetime.release(texture); }
                }));
    }

    public static MinecraftVulkanImage sampled(VulkanDeviceContext gpu, VulkanGpuTextureView view,
                                                      int width, int height, int format) {
        VulkanGpuTexture texture = view.texture();
        MinecraftTextureLifetime.retain(texture);
        GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
        long nativeView = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            range = gpu.descriptorHeap().allocateResources(1);
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(texture.vkImage()).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(view.baseMipLevel()).levelCount(view.mipLevels())
                    .baseArrayLayer(0).layerCount(1);
            var viewOut = stack.mallocLong(1);
            int result = VK10.vkCreateImageView(gpu.vk(), viewInfo, null, viewOut);
            if (result != VK10.VK_SUCCESS) throw new IllegalStateException("Minecraft image borrow view: " + result);
            nativeView = viewOut.get(0);
            VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                    .pView(viewInfo).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                    .type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image));
            gpu.descriptorHeap().writer().writeResource(range, 0, resource);
            return new MinecraftVulkanImage(gpu, view, texture, range, width, height, format, nativeView);
        } catch (RuntimeException | Error failure) {
            if (range != null) range.destroy();
            if (nativeView != 0L) VK10.vkDestroyImageView(gpu.vk(), nativeView, null);
            MinecraftTextureLifetime.release(texture);
            throw failure;
        }
    }

    public boolean wraps(VulkanDeviceContext candidateGpu, VulkanGpuTextureView candidate,
                         int candidateWidth, int candidateHeight) {
        return gpu == candidateGpu && view == candidate
                && width == candidateWidth && height == candidateHeight;
    }
    @Override public long image() { return texture.vkImage(); }
    @Override public long view() { return nativeView; }
    @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
        if (kind != GpuImageDescriptorKind.SAMPLED) throw new IllegalArgumentException("sampled borrow has no storage descriptor");
        return new Descriptor(descriptor.firstIndex());
    }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int format() { return format; }
    @Override public void close() {
        owner.close();
    }
    @Override public MinecraftVulkanImage retain() { return new MinecraftVulkanImage(this); }

    private MinecraftVulkanImage(MinecraftVulkanImage source) {
        owner = source.owner.retain();
        gpu = source.gpu;
        view = source.view;
        texture = source.texture;
        descriptor = source.descriptor;
        width = source.width;
        height = source.height;
        format = source.format;
        nativeView = source.nativeView;
    }
    private record Descriptor(GpuDescriptorIndex.Resource index) implements GpuImageDescriptor {
        @Override public GpuImageDescriptorKind kind() { return GpuImageDescriptorKind.SAMPLED; }
    }
}
