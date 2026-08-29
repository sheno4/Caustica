package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

/** Converts an SDR host frame to PQ and submits its swapchain blit. */
final class SdrPqPresentation {
    private final PresentationSampler sampler;
    private RtSdrPresentPipeline pipeline;
    private GpuImage image;

    SdrPqPresentation(PresentationSampler sampler) {
        this.sampler = sampler;
    }

    boolean present(GraphicsSubmission submission, long swapchainImage, int swapWidth, int swapHeight,
            long sdrMainView, long acquireSemaphore, long presentSemaphore) {
        if (!RtRuntime.hasSession() || sdrMainView == 0L) {
            return false;
        }
        GpuContext context = GpuContext.get();
        long samplerHandle = context != null ? sampler.ensure(context) : 0L;
        if (context == null || samplerHandle == 0L) {
            return false;
        }
        if (pipeline == null) {
            pipeline = RtSdrPresentPipeline.create(context);
        }
        if (image == null || image.width() != swapWidth || image.height() != swapHeight) {
            if (image != null) {
                image.destroy();
            }
            image = context.createStorageImage(swapWidth, swapHeight, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapWidth + "x" + swapHeight);
        }
        int copyWidth = Math.min(swapWidth, image.width());
        int copyHeight = Math.min(swapHeight, image.height());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L)
                    .dstStageMask(2048L).dstAccessMask(98304L);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre));

            pipeline.setImages(image.view(), sdrMainView, samplerHandle);
            pipeline.dispatch(commandBuffer, image.width(), image.height(), CausticaConfig.Rt.Hdr.uiNits());
            HdrPresentation.recordSwapchainBlit(
                    commandBuffer, stack, image.image(), swapchainImage, copyWidth, copyHeight);
            if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            GeneratedFrameQueue.enqueuePresent(
                    submission, commandBuffer, acquireSemaphore, presentSemaphore);
        }
        return true;
    }

    void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        if (image != null) {
            image.destroy();
            image = null;
        }
    }
}
