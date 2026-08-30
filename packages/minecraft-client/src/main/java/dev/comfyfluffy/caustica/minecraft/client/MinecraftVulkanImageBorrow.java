package dev.comfyfluffy.caustica.minecraft.client;

import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;

/** Stable borrow of a Minecraft image view and its sampled descriptor-heap entry. */
public final class MinecraftVulkanImageBorrow implements GpuImage {
    private final VulkanGpuTextureView view;
    private final VulkanGpuTexture texture;
    private final VulkanDeviceContext gpu;
    private final GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor;
    private final int width, height, format;
    private boolean destroyed;

    private MinecraftVulkanImageBorrow(VulkanDeviceContext gpu, VulkanGpuTextureView view, VulkanGpuTexture texture,
                                       GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor,
                                       int width, int height, int format) {
        this.gpu = gpu; this.view = view; this.texture = texture; this.descriptor = descriptor;
        this.width = width; this.height = height; this.format = format;
    }

    public static MinecraftVulkanImageBorrow sampled(VulkanDeviceContext gpu, VulkanGpuTextureView view,
                                                      int width, int height, int format) {
        VulkanGpuTexture texture = view.texture();
        texture.addViews();
        GpuDescriptorRange<GpuDescriptorIndex.Resource> range = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            range = gpu.descriptorHeap().allocateResources(1);
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(texture.vkImage()).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(view.baseMipLevel()).levelCount(view.mipLevels())
                    .baseArrayLayer(0).layerCount(1);
            VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                    .pView(viewInfo).layout(VK10.VK_IMAGE_LAYOUT_GENERAL);
            VkResourceDescriptorInfoEXT resource = VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                    .type(VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image));
            gpu.descriptorHeap().writer().writeResource(range, 0, resource);
            return new MinecraftVulkanImageBorrow(gpu, view, texture, range, width, height, format);
        } catch (RuntimeException | Error failure) {
            if (range != null) range.destroy();
            texture.removeViews();
            throw failure;
        }
    }

    public boolean wraps(VulkanDeviceContext candidateGpu, VulkanGpuTextureView candidate,
                         int candidateWidth, int candidateHeight) {
        return gpu == candidateGpu && view == candidate
                && width == candidateWidth && height == candidateHeight;
    }
    @Override public long image() { return texture.vkImage(); }
    @Override public long view() { return view.vkImageView(); }
    @Override public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
        if (kind != GpuImageDescriptorKind.SAMPLED) throw new IllegalArgumentException("sampled borrow has no storage descriptor");
        return new Descriptor(descriptor.firstIndex());
    }
    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int format() { return format; }
    @Override public void destroy() {
        if (destroyed) return;
        destroyed = true;
        try { descriptor.destroy(); } finally { texture.removeViews(); }
    }
    private record Descriptor(GpuDescriptorIndex.Resource index) implements GpuImageDescriptor {
        @Override public GpuImageDescriptorKind kind() { return GpuImageDescriptorKind.SAMPLED; }
    }
}
