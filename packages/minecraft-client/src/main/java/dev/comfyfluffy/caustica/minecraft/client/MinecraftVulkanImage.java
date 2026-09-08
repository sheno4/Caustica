package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
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
    private final Image image;
    private final SharedResource<Image> owner;

    private MinecraftVulkanImage(SharedResource<Image> owner) {
        this.owner = owner;
        image = owner.get();
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
            var ownedImage = new Image(gpu, view, texture, range, width, height, format, nativeView);
            return new MinecraftVulkanImage(SharedResource.owned(ownedImage, Image::retire));
        } catch (RuntimeException | Error failure) {
            var allocatedRange = range;
            long allocatedView = nativeView;
            var lifetime = new ResourceLifetime(
                    () -> { if (allocatedRange != null) allocatedRange.destroy(); },
                    () -> { if (allocatedView != 0L) VK10.vkDestroyImageView(gpu.vk(), allocatedView, null); },
                    () -> MinecraftTextureLifetime.release(texture));
            try {
                lifetime.close();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public boolean wraps(VulkanDeviceContext candidateGpu, VulkanGpuTextureView candidate,
                         int candidateWidth, int candidateHeight) {
        return image.gpu == candidateGpu && image.sourceView == candidate
                && image.width == candidateWidth && image.height == candidateHeight;
    }

    @Override public long image() { return image.texture.vkImage(); }
    @Override public long view() { return image.nativeView; }
    @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
        if (kind != GpuImageDescriptorKind.SAMPLED) throw new IllegalArgumentException("sampled borrow has no storage descriptor");
        return new Descriptor(image.descriptor.firstIndex());
    }
    @Override public int width() { return image.width; }
    @Override public int height() { return image.height; }
    @Override public int format() { return image.format; }
    @Override public void close() { owner.close(); }
    @Override public MinecraftVulkanImage retain() { return new MinecraftVulkanImage(owner.retain()); }

    private record Image(VulkanDeviceContext gpu, VulkanGpuTextureView sourceView, VulkanGpuTexture texture,
                         GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor,
                         int width, int height, int format, long nativeView) {
        private void retire() {
            var lifetime = new ResourceLifetime(descriptor::destroy,
                    () -> VK10.vkDestroyImageView(gpu.vk(), nativeView, null),
                    () -> MinecraftTextureLifetime.release(texture));
            gpu.deferDestroy(lifetime::close);
        }
    }

    private record Descriptor(GpuDescriptorIndex.Resource index) implements GpuImageDescriptor {
        @Override public GpuImageDescriptorKind kind() { return GpuImageDescriptorKind.SAMPLED; }
    }
}
