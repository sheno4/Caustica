package dev.comfyfluffy.caustica.minecraft.material;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkExtent3D;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VK14;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.util.vma.Vma.vmaCreateBuffer;
import static org.lwjgl.util.vma.Vma.vmaCreateImage;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;

/** One-shot transfer pass for an immutable Minecraft material lookup. */
final class MinecraftMaterialUploadPass implements Pass<PassFrame> {
    private final GpuDevice gpu;
    private final MinecraftProgramResources resources;
    private final MinecraftMaterialLookup lookup;
    private final Consumer<MinecraftProgramResources.PublishedEpoch> published;
    private List<ImageUpload> uploads;
    private boolean recorded;

    MinecraftMaterialUploadPass(GpuDevice gpu, MinecraftProgramResources resources,
                                MinecraftMaterialLookup lookup,
                                Consumer<MinecraftProgramResources.PublishedEpoch> published) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        this.lookup = java.util.Objects.requireNonNull(lookup, "lookup");
        this.published = java.util.Objects.requireNonNull(published, "published");
        uploads = allocateUploads(lookup.textures());
    }

    @Override
    public void record(PassFrame frame) {
        if (recorded) return;
        recorded = true;
        recordCopies(frame);

        List<MinecraftProgramResources.UploadedImage> images = uploads.stream()
                .map(upload -> (MinecraftProgramResources.UploadedImage) upload.image()).toList();
        MinecraftProgramResources.Epoch epoch = resources.createEpoch(lookup, images);
        List<StagingBuffer> staging = uploads.stream().map(ImageUpload::staging).toList();
        uploads = List.of();
        frame.gpuUse().retire(() -> staging.forEach(StagingBuffer::destroy));
        try {
            published.accept(new MinecraftProgramResources.PublishedEpoch(lookup, epoch));
        } catch (RuntimeException | Error failure) {
            frame.gpuUse().retire(epoch.retirement());
            throw failure;
        }
    }

    private void recordCopies(PassFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer toTransfer = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Image image = uploads.get(index).image();
                toTransfer.get(index).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                        .srcAccessMask(VK_ACCESS_2_NONE)
                        .dstStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                        .dstAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                toTransfer.get(index).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(image.mipLevels()).baseArrayLayer(0).layerCount(1);
            }
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(toTransfer));

            for (ImageUpload upload : uploads) {
                List<MinecraftMaterialTexture.Mip> levels = upload.texture().levels();
                VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(levels.size(), stack);
                long[] offsets = mipOffsets(upload.texture());
                for (int level = 0; level < levels.size(); level++) {
                    MinecraftMaterialTexture.Mip mip = levels.get(level);
                    VkImageSubresourceLayers layers = copies.get(level).imageSubresource()
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level)
                            .baseArrayLayer(0).layerCount(1);
                    VkExtent3D extent = copies.get(level).imageExtent()
                            .width(mip.width()).height(mip.height()).depth(1);
                    copies.get(level).bufferOffset(offsets[level]).bufferRowLength(0).bufferImageHeight(0)
                            .imageSubresource(layers).imageOffset().set(0, 0, 0);
                    copies.get(level).imageExtent(extent);
                }
                vkCmdCopyBufferToImage(frame.commandBuffer(), upload.staging().buffer,
                        upload.image().image(), VK_IMAGE_LAYOUT_GENERAL, copies);
            }

            VkImageMemoryBarrier2.Buffer toRead = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Image image = uploads.get(index).image();
                toRead.get(index).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                        .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                        .dstAccessMask(VK_ACCESS_2_SHADER_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                toRead.get(index).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(image.mipLevels()).baseArrayLayer(0).layerCount(1);
            }
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(toRead));
        }
    }

    private List<ImageUpload> allocateUploads(List<MinecraftMaterialTexture> textures) {
        ArrayList<ImageUpload> result = new ArrayList<>(textures.size());
        try {
            for (MinecraftMaterialTexture texture : textures) {
                Image image = createImage(texture);
                try {
                    result.add(new ImageUpload(texture, image, createStaging(texture)));
                } catch (RuntimeException | Error failure) {
                    image.close();
                    throw failure;
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException | Error failure) {
            result.forEach(ImageUpload::destroy);
            throw failure;
        }
    }

    private Image createImage(MinecraftMaterialTexture texture) {
        MinecraftMaterialTexture.Mip base = texture.levels().getFirst();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(extent -> extent.width(base.width()).height(base.height()).depth(1))
                    .mipLevels(texture.levels().size()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            LongBuffer imageOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            int result = vmaCreateImage(gpu.vmaAllocator(), imageInfo, allocationInfo,
                    imageOut, allocationOut, null);
            if (result != VK_SUCCESS) throw new IllegalStateException("Minecraft material image allocation failed: " + result);
            return new Image(imageOut.get(0), allocationOut.get(0), texture.levels().size());
        }
    }

    private StagingBuffer createStaging(MinecraftMaterialTexture texture) {
        long size = byteSize(texture);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                    .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT
                            | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            LongBuffer bufferOut = stack.mallocLong(1);
            PointerBuffer allocationOut = stack.mallocPointer(1);
            VmaAllocationInfo allocationResult = VmaAllocationInfo.calloc(stack);
            int result = vmaCreateBuffer(gpu.vmaAllocator(), bufferInfo, allocationInfo,
                    bufferOut, allocationOut, allocationResult);
            if (result != VK_SUCCESS) throw new IllegalStateException("Minecraft material staging allocation failed: " + result);
            long buffer = bufferOut.get(0);
            long allocation = allocationOut.get(0);
            if (allocationResult.pMappedData() == 0L) {
                Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
                throw new IllegalStateException("Minecraft material staging buffer is not mapped");
            }
            ByteBuffer bytes = MemoryUtil.memByteBuffer(allocationResult.pMappedData(), Math.toIntExact(size));
            texture.levels().forEach(level -> bytes.put(level.rgba8()));
            Vma.vmaFlushAllocation(gpu.vmaAllocator(), allocation, 0, size);
            return new StagingBuffer(buffer, allocation);
        }
    }

    static long byteSize(MinecraftMaterialTexture texture) {
        long size = 0L;
        for (MinecraftMaterialTexture.Mip mip : texture.levels()) {
            size = Math.addExact(size, (long) mip.width() * mip.height() * 4L);
        }
        return size;
    }

    static long[] mipOffsets(MinecraftMaterialTexture texture) {
        long[] offsets = new long[texture.levels().size()];
        long offset = 0L;
        for (int level = 0; level < offsets.length; level++) {
            offsets[level] = offset;
            MinecraftMaterialTexture.Mip mip = texture.levels().get(level);
            offset = Math.addExact(offset, (long) mip.width() * mip.height() * 4L);
        }
        return offsets;
    }

    @Override
    public void close() {
        uploads.forEach(ImageUpload::destroy);
        uploads = List.of();
    }

    private record ImageUpload(MinecraftMaterialTexture texture, Image image, StagingBuffer staging) {
        void destroy() {
            staging.destroy();
            image.close();
        }
    }

    private final class Image implements MinecraftProgramResources.UploadedImage {
        private final long image;
        private final long allocation;
        private final int mipLevels;
        private boolean destroyed;

        private Image(long image, long allocation, int mipLevels) {
            this.image = image;
            this.allocation = allocation;
            this.mipLevels = mipLevels;
        }

        @Override public long image() { return image; }
        @Override public int format() { return VK_FORMAT_R8G8B8A8_UNORM; }
        @Override public int mipLevels() { return mipLevels; }
        @Override public void close() {
            if (destroyed) return;
            Vma.vmaDestroyImage(gpu.vmaAllocator(), image, allocation);
            destroyed = true;
        }
    }

    private final class StagingBuffer {
        private final long buffer;
        private final long allocation;
        private boolean destroyed;

        private StagingBuffer(long buffer, long allocation) {
            this.buffer = buffer;
            this.allocation = allocation;
        }

        private void destroy() {
            if (destroyed) return;
            Vma.vmaDestroyBuffer(gpu.vmaAllocator(), buffer, allocation);
            destroyed = true;
        }
    }
}
