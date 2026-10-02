package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorWriter;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImageAllocation;
import dev.comfyfluffy.caustica.vulkan.VmaMappedHostBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyBufferToImageInfo2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkFormatProperties2;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageFormatProperties2;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceImageFormatInfo2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;
import static org.lwjgl.vulkan.VK10.*;

/** Prepares source BC1 samples as uncompressed numeric 3D textures and publishes only completed uploads. */
public final class CloudlyVolumeUpload {
    private static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int USAGE = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private CloudlyVolumeUpload() { }

    public sealed interface Completion permits Ready, Failed, Cancelled { }
    /** Ownership of this producer claim transfers to the completion recipient. */
    public record Ready(CloudlyCloudResources resources) implements Completion { }
    public record Failed(Throwable failure) implements Completion { }
    public record Cancelled() implements Completion { }

    /**
     * Decodes and allocates on the caller's worker, then submits copies to the async queue. Completion is
     * never inline and must not throw. Close a Ready revision when displaced; cancellation releases it
     * internally if recording has not begun. A thrown preparation error has accepted no job.
     */
    public static GpuComputeJob prepare(GpuDevice gpu, GpuComputeQueue compute,
                                       ResourceFactory resources, CloudlySourcePack source,
                                       Consumer<? super Completion> completion) throws IOException {
        Objects.requireNonNull(gpu, "gpu");
        Objects.requireNonNull(compute, "compute");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(completion, "completion");
        List<Upload> uploads = new ArrayList<>();
        GpuDescriptorRange<GpuDescriptorIndex.Resource> images = null;
        GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers = null;
        ResourceOwner owner = null;
        ResourceOwner jobOwner = null;
        try {
            for (CloudlySourcePack.Texture texture : source.textures()) {
                checkInterrupted();
                uploads.add(allocate(gpu, compute, texture));
            }
            images = gpu.descriptorHeap().allocateResources(uploads.size());
            samplers = gpu.descriptorHeap().allocateSamplers(1);
            writeDescriptors(gpu, images, samplers, uploads);
            var imageDescriptors = images;
            var samplerDescriptors = samplers;
            List<VmaImageAllocation> allocations = uploads.stream().map(Upload::image).toList();
            owner = resources.create(() -> new ResourceLifetime(imageDescriptors::destroy, samplerDescriptors::destroy,
                    () -> new ResourceLifetime(allocations.stream().<Runnable>map(image -> image::close)
                            .toArray(Runnable[]::new)).close()).close());
            Map<Integer, CloudlyCloudResources.Texture> indices = new LinkedHashMap<>();
            for (int index = 0; index < uploads.size(); index++) {
                CloudlySourcePack.Texture texture = uploads.get(index).texture();
                CloudlySourcePack.Mip base = texture.mips().getFirst();
                indices.put(texture.textureId(), new CloudlyCloudResources.Texture(texture.textureId(),
                        base.width(), base.height(), base.depth(), texture.mips().size(),
                        new GpuDescriptorIndex.Resource(Math.addExact(images.firstIndex().value(), index))));
            }
            CloudlyCloudResources revision = new CloudlyCloudResources(source, indices, samplers.firstIndex(), owner);
            ResourceLifetime staging = new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.staging()::close)
                    .toArray(Runnable[]::new));
            List<Upload> copies = List.copyOf(uploads);
            jobOwner = revision.retain();
            return compute.submit(commandBuffer -> record(commandBuffer, copies), List.of(jobOwner), result -> {
                GpuComputeCompletion retired = retireStaging(result, staging);
                if (retired instanceof GpuComputeCompletion.Succeeded) {
                    try { completion.accept(new Ready(revision)); }
                    catch (RuntimeException | Error failure) {
                        revision.close();
                        throw failure;
                    }
                } else {
                    revision.close();
                    completion.accept(retired instanceof GpuComputeCompletion.Failed failed
                            ? new Failed(failed.failure()) : new Cancelled());
                }
            });
        } catch (IOException | RuntimeException | Error failure) {
            ResourceOwner allocatedOwner = owner;
            ResourceOwner allocatedJobOwner = jobOwner;
            var allocatedImages = images;
            var allocatedSamplers = samplers;
            closeAfterFailure(failure,
                    () -> { if (allocatedJobOwner != null) allocatedJobOwner.close(); },
                    () -> {
                        if (allocatedOwner != null) allocatedOwner.close();
                        else new ResourceLifetime(
                                () -> { if (allocatedImages != null) allocatedImages.destroy(); },
                                () -> { if (allocatedSamplers != null) allocatedSamplers.destroy(); },
                                () -> new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.image()::close)
                                        .toArray(Runnable[]::new)).close()).close();
                    },
                    () -> new ResourceLifetime(uploads.stream().<Runnable>map(upload -> upload.staging()::close)
                            .toArray(Runnable[]::new)).close());
            throw failure;
        }
    }

    private static Upload allocate(GpuDevice gpu, GpuComputeQueue compute,
                                   CloudlySourcePack.Texture texture) throws IOException {
        CloudlySourcePack.Mip base = texture.mips().getFirst();
        validateImageSupport(gpu, texture);
        VmaImageAllocation image = null;
        VmaMappedHostBuffer staging = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo create = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_3D).format(FORMAT)
                    .mipLevels(texture.mips().size()).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(base.width(), base.height(), base.depth());
            int[] families = compute.sharedQueueFamilyIndices();
            if (families.length > 1) create.sharingMode(VK_SHARING_MODE_CONCURRENT).pQueueFamilyIndices(stack.ints(families));
            image = VmaImageAllocation.create(gpu, create, "Cloudly volume " + texture.textureId());
            staging = VmaMappedHostBuffer.create(gpu, texture.rgbaByteSize(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    "Cloudly volume " + texture.textureId() + " upload");
            ByteBuffer mapping = staging.mapped();
            long[] offsets = mipOffsets(texture);
            for (int level = 0; level < texture.mips().size(); level++) {
                checkInterrupted();
                CloudlySourcePack.Mip mip = texture.mips().get(level);
                mip.decodeRgba8(mapping.slice(Math.toIntExact(offsets[level]), Math.toIntExact(mip.rgbaByteSize())));
            }
            staging.flush(0, staging.byteSize());
            return new Upload(texture, image, staging, offsets);
        } catch (IOException | RuntimeException | Error failure) {
            VmaImageAllocation allocatedImage = image;
            VmaMappedHostBuffer allocatedStaging = staging;
            closeAfterFailure(failure, () -> { if (allocatedStaging != null) allocatedStaging.close(); },
                    () -> { if (allocatedImage != null) allocatedImage.close(); });
            throw failure;
        }
    }

    private static void validateImageSupport(GpuDevice gpu, CloudlySourcePack.Texture texture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties2 format = VkFormatProperties2.calloc(stack).sType$Default();
            VK11.vkGetPhysicalDeviceFormatProperties2(gpu.vk().getPhysicalDevice(), FORMAT, format);
            int required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                    | VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
            if ((format.formatProperties().optimalTilingFeatures() & required) != required) {
                throw new UnsupportedOperationException("Cloudly RGBA8 sampled volume format is unavailable");
            }
            VkPhysicalDeviceImageFormatInfo2 query = VkPhysicalDeviceImageFormatInfo2.calloc(stack).sType$Default()
                    .format(FORMAT).type(VK_IMAGE_TYPE_3D).tiling(VK_IMAGE_TILING_OPTIMAL).usage(USAGE).flags(0);
            VkImageFormatProperties2 properties = VkImageFormatProperties2.calloc(stack).sType$Default();
            int result = VK11.vkGetPhysicalDeviceImageFormatProperties2(gpu.vk().getPhysicalDevice(), query, properties);
            if (result == VK_ERROR_FORMAT_NOT_SUPPORTED) {
                throw new UnsupportedOperationException("Cloudly RGBA8 3D images are unavailable");
            }
            if (result != VK_SUCCESS) {
                throw new IllegalStateException("Cloudly 3D image format query failed: " + result);
            }
            CloudlySourcePack.Mip base = texture.mips().getFirst();
            var limits = properties.imageFormatProperties();
            if (base.width() > limits.maxExtent().width() || base.height() > limits.maxExtent().height()
                    || base.depth() > limits.maxExtent().depth() || texture.mips().size() > limits.maxMipLevels()) {
                throw new UnsupportedOperationException("Cloudly texture " + texture.textureId() + " exceeds 3D image limits");
            }
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cloudly preparation cancelled");
    }

    private static void writeDescriptors(GpuDevice gpu,
            GpuDescriptorRange<GpuDescriptorIndex.Resource> images,
            GpuDescriptorRange<GpuDescriptorIndex.Sampler> samplers, List<Upload> uploads) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            gpu.descriptorHeap().writer().writeSampler(samplers, 0,
                    VkSamplerCreateInfo.calloc(stack).sType$Default()
                            .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                            .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR)
                            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0).maxLod(Float.MAX_VALUE));
            VkImageViewCreateInfo.Buffer views = VkImageViewCreateInfo.calloc(uploads.size(), stack);
            VkImageDescriptorInfoEXT.Buffer descriptors = VkImageDescriptorInfoEXT.calloc(uploads.size(), stack);
            List<GpuDescriptorWriter.ImageWrite> writes = new ArrayList<>();
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                VkImageViewCreateInfo view = views.get(index).sType$Default().image(upload.image().image())
                        .viewType(VK_IMAGE_VIEW_TYPE_3D).format(FORMAT);
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(upload.texture().mips().size()).baseArrayLayer(0).layerCount(1);
                descriptors.get(index).sType$Default().pView(view).layout(VK_IMAGE_LAYOUT_GENERAL);
                writes.add(new GpuDescriptorWriter.ImageWrite(GpuImageDescriptorKind.SAMPLED, descriptors.get(index)));
            }
            gpu.descriptorHeap().writer().writeImages(images, 0, writes);
        }
    }

    private static void record(VkCommandBuffer commandBuffer, List<Upload> uploads) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer barriers = VkImageMemoryBarrier2.calloc(uploads.size(), stack);
            for (int index = 0; index < uploads.size(); index++) {
                Upload upload = uploads.get(index);
                var barrier = barriers.get(index).sType$Default().image(upload.image().image())
                        .srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT).dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(upload.texture().mips().size()).baseArrayLayer(0).layerCount(1);
            }
            VK14.vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
            for (Upload upload : uploads) {
                VkBufferImageCopy2.Buffer copies = VkBufferImageCopy2.calloc(upload.texture().mips().size(), stack);
                for (int level = 0; level < upload.texture().mips().size(); level++) {
                    CloudlySourcePack.Mip mip = upload.texture().mips().get(level);
                    var copy = copies.get(level).sType$Default().bufferOffset(upload.offsets()[level])
                            .bufferRowLength(0).bufferImageHeight(0);
                    copy.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).baseArrayLayer(0).layerCount(1);
                    copy.imageOffset().set(0, 0, 0);
                    copy.imageExtent().set(mip.width(), mip.height(), mip.depth());
                }
                VK13.vkCmdCopyBufferToImage2(commandBuffer, VkCopyBufferToImageInfo2.calloc(stack).sType$Default()
                        .srcBuffer(upload.staging().buffer()).dstImage(upload.image().image())
                        .dstImageLayout(VK_IMAGE_LAYOUT_GENERAL).pRegions(copies));
            }
            for (int index = 0; index < uploads.size(); index++) {
                barriers.get(index).srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                        .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                                | KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR)
                        .dstAccessMask(VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            VK14.vkCmdPipelineBarrier2(commandBuffer, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
    }

    static long[] mipOffsets(CloudlySourcePack.Texture texture) {
        long[] offsets = new long[texture.mips().size()];
        long cursor = 0;
        for (int level = 0; level < offsets.length; level++) {
            offsets[level] = cursor;
            cursor = Math.addExact(cursor, texture.mips().get(level).rgbaByteSize());
        }
        return offsets;
    }

    private static GpuComputeCompletion retireStaging(GpuComputeCompletion completion, ResourceLifetime staging) {
        try { staging.close(); return completion; }
        catch (RuntimeException | Error failure) {
            if (completion instanceof GpuComputeCompletion.Failed failed) {
                if (failed.failure() != failure) failed.failure().addSuppressed(failure);
                return completion;
            }
            return new GpuComputeCompletion.Failed(failure);
        }
    }

    private record Upload(CloudlySourcePack.Texture texture, VmaImageAllocation image,
                          VmaMappedHostBuffer staging, long[] offsets) { }
}
