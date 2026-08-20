package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

/** Owns DLSS frame-generation captures, interpolation outputs, and temporal evaluation state. */
final class FrameGeneration {
    private RtFramePresenter.RenderedFrame renderedFrame;
    private GpuImage hudlessImage;
    private GpuImage hdrHudlessImage;
    private GpuImage interpolationImage;
    private int interpolationWidth = -1;
    private int interpolationHeight = -1;
    private int interpolationFormat = Integer.MIN_VALUE;
    private boolean reset = true;
    private final Matrix4f clipToPrevious = new Matrix4f();
    private final Matrix4f previousToClip = new Matrix4f();
    private final Matrix4f matrixScratch = new Matrix4f();

    void publish(RtFramePresenter.RenderedFrame frame) {
        renderedFrame = frame;
    }

    void invalidate() {
        renderedFrame = null;
    }

    void resetHistory() {
        renderedFrame = null;
        reset = true;
    }

    void captureHudless(long sourceImage, int width, int height, UiPresentationResources ui) {
        if (!RtDlssFg.enabled() || !ui.enabled() || sourceImage == 0L) {
            return;
        }
        GpuContext context = GpuContext.currentOrNull();
        if (context == null) {
            return;
        }
        if (hudlessImage == null || hudlessImage.width() != width || hudlessImage.height() != height) {
            if (hudlessImage != null) {
                hudlessImage.destroy();
            }
            hudlessImage = context.createStorageImage(
                    width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "FG hudless capture " + width + "x" + height);
        }
        GraphicsSubmission submission = context.backend().createGraphicsSubmission();
        VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            VK10.vkCmdCopyImage(commandBuffer, sourceImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    hudlessImage.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, width, height));
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
        }
        if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        submission.execute(commandBuffer);
    }

    void captureHdrHudless(VkCommandBuffer commandBuffer, MemoryStack stack, GpuImage source) {
        GpuContext context = GpuContext.currentOrNull();
        if (context == null) {
            return;
        }
        if (hdrHudlessImage == null
                || hdrHudlessImage.width() != source.width() || hdrHudlessImage.height() != source.height()) {
            if (hdrHudlessImage != null) {
                hdrHudlessImage.destroy();
            }
            hdrHudlessImage = context.createStorageImage(source.width(), source.height(),
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + source.width() + "x" + source.height());
        }
        VulkanBarriers.memoryBarrier(commandBuffer, stack);
        VK10.vkCmdCopyImage(commandBuffer, source.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                hdrHudlessImage.image(), VK10.VK_IMAGE_LAYOUT_GENERAL,
                copyRegion(stack, source.width(), source.height()));
        VulkanBarriers.memoryBarrier(commandBuffer, stack);
    }

    GpuImage interpolate(GraphicsSubmission submission, long backbufferView, long backbufferImage,
            int swapWidth, int swapHeight, boolean hdrBackbuffer,
            UiPresentationResources ui) {
        RtFramePresenter.RenderedFrame frame = renderedFrame;
        if (frame == null || frame.depth() == null || frame.motion() == null) {
            return null;
        }
        GpuContext context = GpuContext.currentOrNull();
        if (context == null) {
            return null;
        }
        int format = hdrBackbuffer
                ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (!ensureFeature(context, swapWidth, swapHeight,
                frame.renderWidth(), frame.renderHeight(), format)) {
            throw new IllegalStateException("DLSSG feature not ready (ensureFeature failed)");
        }
        ensureInterpolationImage(context, swapWidth, swapHeight, format);
        matrixScratch.set(frame.currentViewProjection()).invert();
        clipToPrevious.set(frame.previousViewProjection()).mul(matrixScratch);
        matrixScratch.set(frame.previousViewProjection()).invert();
        previousToClip.set(frame.currentViewProjection()).mul(matrixScratch);
        GpuImage hudless = hdrBackbuffer ? hdrHudlessImage : hudlessImage;
        boolean hudlessReady = hudless != null && hudless.width() == swapWidth && hudless.height() == swapHeight;
        int hudlessFormat = hdrBackbuffer
                ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = ui.width() == swapWidth && ui.height() == swapHeight
                && ui.colorView() != 0L && ui.colorImage() != 0L;

        VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
        boolean evaluated = RtDlssFg.INSTANCE.evaluate(commandBuffer.address(),
                backbufferView, backbufferImage, format,
                frame.depth().view(), frame.depth().image(), VK10.VK_FORMAT_R32_SFLOAT,
                frame.motion().view(), frame.motion().image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessReady ? hudless.view() : 0L, hudlessReady ? hudless.image() : 0L,
                hudlessReady ? hudlessFormat : 0,
                uiReady ? ui.colorView() : 0L, uiReady ? ui.colorImage() : 0L,
                uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                interpolationImage.view(), interpolationImage.image(), format,
                swapWidth, swapHeight, frame.renderWidth(), frame.renderHeight(), 1.0f, 1.0f,
                true, hdrBackbuffer, true, reset, clipToPrevious, previousToClip);
        if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        reset = false;
        if (!evaluated) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg_2x failed");
        }
        submission.execute(commandBuffer);
        return interpolationImage;
    }

    private boolean ensureFeature(GpuContext context, int width, int height,
            int renderWidth, int renderHeight, int format) {
        if (RtDlssFg.INSTANCE.featureReadyFor(width, height, renderWidth, renderHeight, format)) {
            return true;
        }
        context.submitSync(commandBuffer -> RtDlssFg.INSTANCE.ensureFeature(
                commandBuffer.address(), width, height, renderWidth, renderHeight, format));
        reset = true;
        return RtDlssFg.INSTANCE.featureReadyFor(width, height, renderWidth, renderHeight, format);
    }

    private void ensureInterpolationImage(GpuContext context, int width, int height, int format) {
        if (interpolationImage != null && interpolationWidth == width
                && interpolationHeight == height && interpolationFormat == format) {
            return;
        }
        destroyInterpolationImage();
        interpolationImage = context.createStorageImage(
                width, height, format, "FG interpolation " + width + "x" + height);
        interpolationWidth = width;
        interpolationHeight = height;
        interpolationFormat = format;
    }

    void destroy() {
        renderedFrame = null;
        if (hudlessImage != null) {
            hudlessImage.destroy();
            hudlessImage = null;
        }
        if (hdrHudlessImage != null) {
            hdrHudlessImage.destroy();
            hdrHudlessImage = null;
        }
        destroyInterpolationImage();
        interpolationWidth = -1;
        interpolationHeight = -1;
        interpolationFormat = Integer.MIN_VALUE;
        reset = true;
    }

    private void destroyInterpolationImage() {
        if (interpolationImage != null) {
            interpolationImage.destroy();
            interpolationImage = null;
        }
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }
}
