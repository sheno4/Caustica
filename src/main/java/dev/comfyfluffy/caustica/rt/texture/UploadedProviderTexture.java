package dev.comfyfluffy.caustica.rt.texture;

import dev.comfyfluffy.caustica.rt.GpuBuffer;
import dev.comfyfluffy.caustica.api.provider.CpuTextureResource;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.util.ArrayList;
import java.util.List;

/** Renderer-owned sampled image uploaded from a neutral provider CPU texture. */
public final class UploadedProviderTexture implements ProviderTextureRegistry.UploadedTexture {
    private final GpuContext context;
    private final long image;
    private final long allocation;
    private final long view;
    private boolean destroyed;

    public UploadedProviderTexture(GpuContext context, CpuTextureResource source, String label) {
        this.context = context;
        List<CpuTextureResource.MipLevel> levels = source.mipLevels();
        List<UploadLevel> uploadLevels = uploadLevels(source);
        int format = source.encoding() == CpuTextureResource.Encoding.SRGB
                ? VK10.VK_FORMAT_R8G8B8A8_SRGB : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        long createdImage = 0L;
        long createdAllocation = 0L;
        long createdView = 0L;
        GpuBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format).mipLevels(levels.size()).arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(source.width(), source.height(), 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(context.vma(), imageInfo, allocationInfo, imageOut, allocationOut, null),
                    "vmaCreateImage(provider texture)");
            createdImage = imageOut.get(0);
            createdAllocation = allocationOut.get(0);
            RtDebugLabels.nameImage(context, createdImage, label);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(createdImage).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
            var viewOut = stack.mallocLong(1);
            check(VK10.vkCreateImageView(context.vk(), viewInfo, null, viewOut),
                    "vkCreateImageView(provider texture)");
            createdView = viewOut.get(0);
            RtDebugLabels.nameImageView(context, createdView, label + " view");

            int uploadBytes = Math.toIntExact(uploadLevels.getLast().endOffset());
            staging = context.createUploadBuffer(uploadBytes, label + " upload");
            var mapped = MemoryUtil.memByteBuffer(staging.mapped(), uploadBytes);
            for (CpuTextureResource.MipLevel level : levels) mapped.put(level.rgba8());
            staging.flush();
            long uploadImage = createdImage;
            long uploadBuffer = staging.handle();
            context.submitSync(commandBuffer -> {
                try (MemoryStack uploadStack = MemoryStack.stackPush()) {
                    VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, uploadStack);
                    toTransfer.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).srcAccessMask(0)
                            .dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toTransfer.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

                    VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(uploadLevels.size(), uploadStack);
                    for (int index = 0; index < uploadLevels.size(); index++) {
                        UploadLevel level = uploadLevels.get(index);
                        copy.get(index).bufferOffset(level.offset()).bufferRowLength(0).bufferImageHeight(0);
                        copy.get(index).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .mipLevel(index).baseArrayLayer(0).layerCount(1);
                        copy.get(index).imageOffset().set(0, 0, 0);
                        copy.get(index).imageExtent().set(level.width(), level.height(), 1);
                    }
                    VK10.vkCmdCopyBufferToImage(commandBuffer, uploadBuffer, uploadImage,
                            VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);

                    VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, uploadStack);
                    toGeneral.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                            .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                            .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                            .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                            .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                    toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
                    VK10.vkCmdPipelineBarrier(commandBuffer, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toGeneral);
                }
            });
        } catch (Throwable failure) {
            if (createdView != 0L) VK10.vkDestroyImageView(context.vk(), createdView, null);
            if (createdImage != 0L) Vma.vmaDestroyImage(context.vma(), createdImage, createdAllocation);
            throw failure;
        } finally {
            if (staging != null) staging.destroy();
        }
        image = createdImage;
        allocation = createdAllocation;
        view = createdView;
    }

    @Override public long imageView() { return view; }
    @Override public int imageLayout() { return VK10.VK_IMAGE_LAYOUT_GENERAL; }

    static List<UploadLevel> uploadLevels(CpuTextureResource source) {
        ArrayList<UploadLevel> result = new ArrayList<>(source.mipLevels().size());
        long offset = 0L;
        for (CpuTextureResource.MipLevel level : source.mipLevels()) {
            long size = Math.multiplyExact(Math.multiplyExact((long) level.width(), level.height()), 4L);
            result.add(new UploadLevel(offset, Math.addExact(offset, size), level.width(), level.height()));
            offset = Math.addExact(offset, size);
        }
        return List.copyOf(result);
    }

    record UploadLevel(long offset, long endOffset, int width, int height) {
    }

    @Override
    public void destroy() {
        if (destroyed) return;
        VK10.vkDestroyImageView(context.vk(), view, null);
        Vma.vmaDestroyImage(context.vma(), image, allocation);
        destroyed = true;
    }

    private static void check(int result, String operation) {
        if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed: " + result);
    }
}
