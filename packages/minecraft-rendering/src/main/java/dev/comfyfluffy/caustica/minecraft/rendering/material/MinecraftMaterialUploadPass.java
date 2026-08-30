package dev.comfyfluffy.caustica.minecraft.rendering.material;

import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.minecraft.content.material.MinecraftMaterialTexture;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkCopyBufferToImageInfo2;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_HOST_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;

/** One-shot transfer pass for an immutable Minecraft material lookup. */
final class MinecraftMaterialUploadPass implements Pass<PassFrame> {
    private final GpuDevice gpu;
    private final MinecraftProgramResources resources;
    private final MinecraftMaterialLookup lookup;
    private final MinecraftProgramResources.Epoch epoch;
    private final Runnable submitted;
    private List<ImageUpload> uploads;
    private boolean recorded;

    MinecraftMaterialUploadPass(GpuDevice gpu, MinecraftProgramResources resources,
                                MinecraftMaterialLookup lookup,
                                Runnable submitted) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        this.lookup = java.util.Objects.requireNonNull(lookup, "lookup");
        this.submitted = java.util.Objects.requireNonNull(submitted, "submitted");
        List<ImageUpload> allocated = allocateUploads(lookup.textures());
        try {
            epoch = resources.createPreparedEpoch(lookup, allocated.stream()
                    .map(upload -> (MinecraftProgramResources.UploadedImage) upload.image()).toList());
            uploads = allocated;
        } catch (RuntimeException | Error failure) {
            allocated.forEach(ImageUpload::destroy);
            throw failure;
        }
    }

    MinecraftProgramResources.Epoch epoch() { return epoch; }

    @Override
    public void record(PassFrame frame) {
        if (recorded) return;
        recorded = true;
        recordCopies(frame);

        List<VmaMappedHostBuffer> staging = uploads.stream().map(ImageUpload::staging).toList();
        uploads = List.of();
        frame.gpuUse().whenComplete(() -> staging.forEach(VmaMappedHostBuffer::close));
        frame.gpuUse().whenSubmitted(() -> {
            resources.publishMaterialRecords(epoch, lookup);
            submitted.run();
        });
    }

    private void recordCopies(PassFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer toTransfer = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Image image = uploads.get(index).image();
                toTransfer.get(index).sType$Default()
                        .srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                        .srcAccessMask(VK_ACCESS_2_NONE)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
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
                VkBufferImageCopy2.Buffer copies = VkBufferImageCopy2.calloc(levels.size(), stack);
                long[] offsets = mipOffsets(upload.texture());
                for (int level = 0; level < levels.size(); level++) {
                    MinecraftMaterialTexture.Mip mip = levels.get(level);
                    copies.get(level).sType$Default();
                    copies.get(level).imageSubresource()
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level)
                            .baseArrayLayer(0).layerCount(1);
                    copies.get(level).bufferOffset(offsets[level]).bufferRowLength(0).bufferImageHeight(0)
                            .imageOffset().set(0, 0, 0);
                    copies.get(level).imageExtent().set(mip.width(), mip.height(), 1);
                }
                VK13.vkCmdCopyBufferToImage2(frame.commandBuffer(),
                        VkCopyBufferToImageInfo2.calloc(stack).sType$Default()
                                .srcBuffer(upload.staging().buffer())
                                .dstImage(upload.image().image())
                                .dstImageLayout(VK_IMAGE_LAYOUT_GENERAL)
                                .pRegions(copies));
            }

            VkImageMemoryBarrier2.Buffer toRead = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Image image = uploads.get(index).image();
                toRead.get(index).sType$Default()
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                        .srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(image.image());
                toRead.get(index).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(image.mipLevels()).baseArrayLayer(0).layerCount(1);
            }
            VkBufferMemoryBarrier2.Buffer tableToRead = VkBufferMemoryBarrier2.calloc(1, stack);
            tableToRead.get(0).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_HOST_BIT)
                    .srcAccessMask(VK_ACCESS_2_HOST_WRITE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                    .dstAccessMask(VK_ACCESS_2_SHADER_STORAGE_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(epoch.materialTableBuffer()).offset(0L).size(VK_WHOLE_SIZE);
            VK14.vkCmdPipelineBarrier2(frame.commandBuffer(), VkDependencyInfo.calloc(stack)
                    .sType$Default().pImageMemoryBarriers(toRead).pBufferMemoryBarriers(tableToRead));
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
            return new Image(VmaImageAllocation.create(gpu, imageInfo, "Minecraft material texture"),
                    texture.levels().size());
        }
    }

    private VmaMappedHostBuffer createStaging(MinecraftMaterialTexture texture) {
        long size = byteSize(texture);
        VmaMappedHostBuffer staging = VmaMappedHostBuffer.create(
                gpu, size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT, "Minecraft material staging");
        try {
            var bytes = staging.mapped();
            texture.levels().forEach(level -> bytes.put(level.rgba8()));
            staging.flush(0L, size);
            return staging;
        } catch (RuntimeException | Error failure) {
            staging.close();
            throw failure;
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
        uploads.forEach(ImageUpload::destroyStaging);
        uploads = List.of();
    }

    private record ImageUpload(MinecraftMaterialTexture texture, Image image, VmaMappedHostBuffer staging) {
        void destroyStaging() { staging.close(); }
        void destroy() {
            staging.close();
            image.close();
        }
    }

    private static final class Image implements MinecraftProgramResources.UploadedImage {
        private final VmaImageAllocation allocation;
        private final int mipLevels;

        private Image(VmaImageAllocation allocation, int mipLevels) {
            this.allocation = allocation;
            this.mipLevels = mipLevels;
        }

        @Override public long image() { return allocation.image(); }
        @Override public int format() { return VK_FORMAT_R8G8B8A8_UNORM; }
        @Override public int mipLevels() { return mipLevels; }
        @Override public void close() { allocation.close(); }
    }
}
