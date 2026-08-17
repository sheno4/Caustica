package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Composites the UI into a rendered PQ frame and submits its swapchain blit. */
final class HdrPresentation {
    private final PresentationSampler sampler;
    private final FrameGeneration generation;
    private RtHdrCompositePipeline pipeline;

    HdrPresentation(PresentationSampler sampler, FrameGeneration generation) {
        this.sampler = sampler;
        this.generation = generation;
    }

    void present(GraphicsSubmission submission, RtFramePresenter.RenderedFrame frame,
            long swapchainImage, int swapWidth, int swapHeight,
            long acquireSemaphore, long presentSemaphore, UiPresentationResources ui) {
        GpuImage source = frame.hdrDisplayImage();
        int copyWidth = Math.min(swapWidth, source.width());
        int copyHeight = Math.min(swapHeight, source.height());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
            if (RtDlssFg.enabled()) {
                generation.captureHdrHudless(commandBuffer, stack, source);
            }
            long overlayView = ui.populated() ? ui.colorView() : 0L;
            if (overlayView != 0L) {
                ensurePipeline();
                if (pipeline != null) {
                    VkMemoryBarrier2.Buffer barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    barrier.get(0).srcStageMask(65536L).srcAccessMask(65536L)
                            .dstStageMask(2048L).dstAccessMask(98304L);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                            VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
                    pipeline.setImages(source.view(), overlayView, sampler.handle());
                    pipeline.dispatch(commandBuffer, source.width(), source.height(), CausticaConfig.Rt.Hdr.uiNits());
                }
            }
            recordSwapchainBlit(commandBuffer, stack, source.image(), swapchainImage, copyWidth, copyHeight);
            if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            GeneratedFrameQueue.enqueuePresent(
                    submission, commandBuffer, acquireSemaphore, presentSemaphore);
        }
    }

    private void ensurePipeline() {
        if (pipeline != null) {
            return;
        }
        GpuContext context = GpuContext.get();
        if (context == null || sampler.ensure(context) == 0L) {
            return;
        }
        pipeline = RtHdrCompositePipeline.create(context);
    }

    static void recordSwapchainBlit(VkCommandBuffer commandBuffer, MemoryStack stack,
            long sourceImage, long swapchainImage, int copyWidth, int copyHeight) {
        VkImageMemoryBarrier2.Buffer toDestination = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        toDestination.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
        toDestination.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VkMemoryBarrier2.Buffer sourceVisibility = VkMemoryBarrier2.calloc(1, stack).sType$Default();
        sourceVisibility.get(0).srcStageMask(65536L).srcAccessMask(65536L)
                .dstStageMask(4096L).dstAccessMask(2048L);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default()
                        .pImageMemoryBarriers(toDestination).pMemoryBarriers(sourceVisibility));

        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(copyWidth, copyHeight, 1);
        region.get(0).dstOffsets(0).set(0, copyHeight, 0);
        region.get(0).dstOffsets(1).set(copyWidth, 0, 1);
        VK10.vkCmdBlitImage(commandBuffer, sourceImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                swapchainImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

        VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
        toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack).sType$Default();
        memory.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
        KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default()
                        .pImageMemoryBarriers(toPresent).pMemoryBarriers(memory));
    }

    void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
    }
}
