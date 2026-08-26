package dev.comfyfluffy.caustica.support;

import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.BorrowedVulkanTexture;
import dev.comfyfluffy.caustica.api.material.TextureRegistrar;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

/**
 * Turns CPU pixels into an image a provider owns and the renderer can borrow.
 *
 * <p>The renderer never uploads: {@link TextureRegistrar} binds a provider-owned view and nothing else.
 * That keeps residency, format choice, and lifetime with whoever produced the content, but it means a
 * provider holding an RGBA8 array has to get it onto the GPU itself. This does that for the ordinary
 * case — a 2D RGBA8 image with an optional mip chain.
 *
 * <p>Nothing here is privileged. It allocates from {@link GpuDevice#vmaAllocator()} and submits through
 * {@link GpuDevice#submitImmediate}, which is exactly what an extension would write; a provider wanting
 * an array layer, a 3D image, or a compressed upload should write its own against the same two calls
 * rather than bend this one.
 */
public final class TextureUploader {
    private TextureUploader() {
    }

    /**
     * Upload {@code content} into a new image and return it as a borrowable texture in
     * {@code VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}. The returned texture owns the image: its
     * {@link BorrowedVulkanTexture#retired()} callback destroys the view and the allocation, so hand it
     * straight to {@link TextureRegistrar#register} and let the epoch drive its lifetime.
     */
    public static BorrowedVulkanTexture upload(GpuDevice device, CpuTextureResource content, String label) {
        List<CpuTextureResource.MipLevel> levels = content.mipLevels();
        int format = content.encoding() == CpuTextureResource.Encoding.SRGB
                ? VK10.VK_FORMAT_R8G8B8A8_SRGB : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        long allocator = device.vmaAllocator();

        long image = 0L;
        long allocation = 0L;
        long view = 0L;
        GpuBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                    .mipLevels(levels.size()).arrayLayers(1)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.extent().set(content.width(), content.height(), 1);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            check(Vma.vmaCreateImage(allocator, imageInfo, allocationInfo, imageOut, allocationOut, null),
                    "vmaCreateImage(" + label + ")");
            image = imageOut.get(0);
            allocation = allocationOut.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(image).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D).format(format);
            viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(levels.size()).baseArrayLayer(0).layerCount(1);
            LongBuffer viewOut = stack.mallocLong(1);
            check(VK10.vkCreateImageView(device.vk(), viewInfo, null, viewOut),
                    "vkCreateImageView(" + label + ")");
            view = viewOut.get(0);

            long stagingBytes = 0L;
            for (CpuTextureResource.MipLevel level : levels) {
                stagingBytes += level.rgba8().length;
            }
            staging = device.createBuffer(stagingBytes, VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true, label + " upload");
            ByteBuffer mapped = MemoryUtil.memByteBuffer(staging.mapped(), Math.toIntExact(stagingBytes));
            for (CpuTextureResource.MipLevel level : levels) {
                mapped.put(level.rgba8());
            }
            staging.flush();

            long uploadImage = image;
            long uploadBuffer = staging.handle();
            device.submitImmediate(commandBuffer -> record(commandBuffer, uploadBuffer, uploadImage, levels));
        } catch (RuntimeException | Error failure) {
            if (view != 0L) VK10.vkDestroyImageView(device.vk(), view, null);
            if (image != 0L) Vma.vmaDestroyImage(allocator, image, allocation);
            throw failure;
        } finally {
            // submitImmediate has completed by the time it returns, so the staging copy is done with.
            if (staging != null) staging.destroy();
        }

        long retiredView = view;
        long retiredImage = image;
        long retiredAllocation = allocation;
        return new BorrowedVulkanTexture(view, VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, () -> {
            VK10.vkDestroyImageView(device.vk(), retiredView, null);
            Vma.vmaDestroyImage(allocator, retiredImage, retiredAllocation);
        });
    }

    /** Upload {@code content} and register it in one step, returning its epoch-local bindless slot. */
    public static int uploadAndRegister(GpuDevice device, TextureRegistrar textures,
                                        CpuTextureResource content, String label) {
        return textures.register(upload(device, content, label));
    }

    private static void record(VkCommandBuffer commandBuffer, long staging, long image,
                               List<CpuTextureResource.MipLevel> levels) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transition(stack, commandBuffer, image, levels.size(),
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(levels.size(), stack);
            long offset = 0L;
            for (int index = 0; index < levels.size(); index++) {
                CpuTextureResource.MipLevel level = levels.get(index);
                VkBufferImageCopy copy = copies.get(index);
                copy.bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
                copy.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(index).baseArrayLayer(0).layerCount(1);
                copy.imageOffset().set(0, 0, 0);
                copy.imageExtent().set(level.width(), level.height(), 1);
                offset += level.rgba8().length;
            }
            VK10.vkCmdCopyBufferToImage(commandBuffer, staging, image,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);

            transition(stack, commandBuffer, image, levels.size(),
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK10.VK_ACCESS_TRANSFER_WRITE_BIT, VK10.VK_ACCESS_SHADER_READ_BIT,
                    VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
        }
    }

    private static void transition(MemoryStack stack, VkCommandBuffer commandBuffer, long image,
                                   int mipLevels, int oldLayout, int newLayout,
                                   int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(1);
        VK10.vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void check(int result, String what) {
        if (result != VK10.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: " + result);
        }
    }
}
