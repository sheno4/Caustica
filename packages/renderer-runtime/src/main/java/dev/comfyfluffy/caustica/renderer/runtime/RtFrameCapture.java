package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkBufferImageCopy2;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCopyImageToBufferInfo2;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.io.IOException;
import java.nio.file.Path;

/** Reads completed reconstructed color and exposure for an EXR capture at the Look/LMT input. */
final class RtFrameCapture {
    private RtFrameCapture() {
    }

    /**
     * Multiplies pre-exposed RGB by residual exposure in float32 before quantizing to fp16.
     * Both exposure factors are retained as metadata so scene-linear values can be recovered.
     * The caller must be on the render thread with all frame commands submitted.
     */
    static void exportResidualExposureExr(VulkanDeviceContext context, GpuImage reconstructedColor,
            TraceExtent extent, RtExposure exposure, RtLookPackage look, long frameCounter,
            Path outputPath) throws IOException {
        long pixelCount = Math.multiplyExact((long) extent.displayWidth(), (long) extent.displayHeight());
        long rgbaBytes = Math.multiplyExact(pixelCount, 4L * Short.BYTES);
        long totalBytes = Math.addExact(rgbaBytes, Float.BYTES);
        if (pixelCount > Integer.MAX_VALUE / 4L) {
            throw new IllegalArgumentException("EXR capture is too large for a Java array: "
                    + extent.displayWidth() + "x" + extent.displayHeight());
        }

        // Drain submitted frame commands before copying so color and exposure describe the same completed frame.
        context.waitIdle();
        GpuBuffer readback = context.createReadbackBuffer(totalBytes, "residual-exposure EXR readback");
        try {
            context.submitSync(cmd -> recordExrReadback(context, cmd, reconstructedColor, extent,
                    exposure.image(), readback, rgbaBytes));
            readback.invalidate();

            float residualExposure = MemoryUtil.memGetFloat(readback.mapped() + rgbaBytes);
            RtExposure.CaptureMetadata exposureMetadata = exposure.captureMetadata(residualExposure);
            short[] exposedRgba = new short[Math.toIntExact(pixelCount * 4L)];
            for (int sample = 0; sample < exposedRgba.length; sample++) {
                short storedHalf = MemoryUtil.memGetShort(readback.mapped() + (long) sample * Short.BYTES);
                float value = Float.float16ToFloat(storedHalf);
                if ((sample & 3) != 3) {
                    value *= residualExposure;
                }
                // Residual exposure is expected to keep this seam comfortably centred in fp16. Clamp only
                // true outliers/infinities so a pathological light cannot poison a grading application.
                value = Math.clamp(value, -65504.0f, 65504.0f);
                exposedRgba[sample] = Float.floatToFloat16(value);
            }

            RtOpenExrWriter.write(outputPath, extent.displayWidth(), extent.displayHeight(), exposedRgba,
                    new RtOpenExrWriter.Metadata(
                            exposureMetadata.preExposure(),
                            exposureMetadata.residualExposure(),
                            exposureMetadata.absoluteExposure(),
                            exposureMetadata.mode(),
                            exposureMetadata.evScene(),
                            exposureMetadata.evTarget(),
                            exposureMetadata.evApplied(),
                            look.id() + "@" + look.packageVersion(),
                            frameCounter));
        } finally {
            readback.destroy();
        }
    }

    static void exportRaw(VulkanDeviceContext context, GpuImage image, Path output,
                          java.util.Map<String, String> metadata) throws IOException {
        int channels = switch (image.format()) {
            case VK10.VK_FORMAT_R16G16B16A16_SFLOAT -> 4;
            case VK10.VK_FORMAT_R16G16_SFLOAT -> 2;
            case VK10.VK_FORMAT_R16_SFLOAT, VK10.VK_FORMAT_R32_SFLOAT -> 1;
            default -> throw new IllegalArgumentException("Unsupported diagnostic image format: " + image.format());
        };
        boolean fullFloat = image.format() == VK10.VK_FORMAT_R32_SFLOAT;
        int sampleBytes = fullFloat ? Float.BYTES : Short.BYTES;
        int pixels = Math.multiplyExact(image.width(), image.height());
        context.waitIdle();
        GpuBuffer readback = context.createReadbackBuffer((long) pixels * channels * sampleBytes,
                "diagnostic image readback");
        try {
            context.submitSync(cmd -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack);
                    barrier.get(0).sType$Default()
                            .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                            .srcAccessMask(VK13.VK_ACCESS_2_MEMORY_WRITE_BIT)
                            .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
                    VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                            .pMemoryBarriers(barrier));
                    VkBufferImageCopy2.Buffer copy = VkBufferImageCopy2.calloc(1, stack);
                    copy.get(0).sType$Default();
                    copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                    copy.get(0).imageExtent().set(image.width(), image.height(), 1);
                    VK13.vkCmdCopyImageToBuffer2(cmd, VkCopyImageToBufferInfo2.calloc(stack).sType$Default()
                            .srcImage(image.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                            .dstBuffer(readback.handle()).pRegions(copy));
                    barrier.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                            .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                            .dstStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                            .dstAccessMask(VK13.VK_ACCESS_2_HOST_READ_BIT);
                    VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                            .pMemoryBarriers(barrier));
                }
            });
            readback.invalidate();
            float[] rgba = new float[Math.multiplyExact(pixels, 4)];
            for (int pixel = 0; pixel < pixels; pixel++) {
                rgba[pixel * 4 + 3] = 1;
                for (int channel = 0; channel < channels; channel++) {
                    long address = readback.mapped() + ((long) pixel * channels + channel) * sampleBytes;
                    rgba[pixel * 4 + channel] = fullFloat ? MemoryUtil.memGetFloat(address)
                            : Float.float16ToFloat(MemoryUtil.memGetShort(address));
                }
            }
            RtOpenExrWriter.writeRaw(output, image.width(), image.height(), rgba, metadata);
        } finally {
            readback.destroy();
        }
    }

    private static void recordExrReadback(VulkanDeviceContext ctx, VkCommandBuffer cmd,
            GpuImage reconstructedColor, TraceExtent extent, GpuImage exposureImage,
            GpuBuffer readback, long exposureOffset) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd,
                     "residual-exposure EXR readback")) {
            VkImageMemoryBarrier2.Buffer imageBarriers = VkImageMemoryBarrier2.calloc(2, stack);
            imageBarriers.get(0).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                            | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(reconstructedColor.image());
            imageBarriers.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            imageBarriers.get(1).sType$Default()
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                            | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(exposureImage.image());
            imageBarriers.get(1).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(1).layerCount(1);
            VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                    .pImageMemoryBarriers(imageBarriers));

            VkBufferImageCopy2.Buffer sceneCopy = VkBufferImageCopy2.calloc(1, stack);
            sceneCopy.get(0).sType$Default();
            sceneCopy.get(0).bufferOffset(0L);
            sceneCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            sceneCopy.get(0).imageExtent().set(extent.displayWidth(), extent.displayHeight(), 1);
            VK13.vkCmdCopyImageToBuffer2(cmd, VkCopyImageToBufferInfo2.calloc(stack).sType$Default()
                    .srcImage(reconstructedColor.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .dstBuffer(readback.handle()).pRegions(sceneCopy));

            VkBufferImageCopy2.Buffer exposureCopy = VkBufferImageCopy2.calloc(1, stack);
            exposureCopy.get(0).sType$Default();
            exposureCopy.get(0).bufferOffset(exposureOffset);
            exposureCopy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            exposureCopy.get(0).imageExtent().set(1, 1, 1);
            VK13.vkCmdCopyImageToBuffer2(cmd, VkCopyImageToBufferInfo2.calloc(stack).sType$Default()
                    .srcImage(exposureImage.image()).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .dstBuffer(readback.handle()).pRegions(exposureCopy));

            VkMemoryBarrier2.Buffer hostBarrier = VkMemoryBarrier2.calloc(1, stack);
            hostBarrier.get(0).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_HOST_READ_BIT);
            VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(hostBarrier));
        }
    }
}
