package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.OwnedCommandBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;

import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkBlitImageInfo2;
import org.lwjgl.vulkan.VkImageBlit2;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.KHRSwapchain;

import java.util.function.Supplier;

/** Composites the UI into a rendered PQ frame and submits its swapchain blit. */
final class HdrPresentation {
    private final VulkanDeviceContext context;
    private final FrameGeneration generation;
    private final Supplier<RtFramePresenter.Settings> settings;
    private RtHdrCompositePipeline pipeline;

    HdrPresentation(VulkanDeviceContext context, FrameGeneration generation,
            Supplier<RtFramePresenter.Settings> settings) {
        this.context = context;
        this.generation = generation;
        this.settings = settings;
    }

    void present(GraphicsSubmission submission, RtFramePresenter.RenderedFrame frame,
            AcquiredSwapchainTarget target, UiPresentationResources ui) {
        GpuImage source = frame.hdrDisplayImage();
        int copyWidth = Math.min(target.width(), source.width());
        int copyHeight = Math.min(target.height(), source.height());
        GraphicsUse use = context.graphics().beginGraphicsUse();
        try (OwnedCommandBuffer commands = context.beginGraphicsCommands("HDR UI and presentation", true);
             MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = commands.commandBuffer();
            VulkanBarriers.memoryBarrier(commandBuffer, stack);
            if (generation.enabled()) {
                generation.captureHdrHudless(commandBuffer, stack, source);
            }
            var overlay = ui.populated() ? ui.color() : null;
            if (overlay != null) {
                use.keepAlive(overlay.retain());
                ensurePipeline();
                VulkanBarriers.memoryBarrier(commandBuffer, stack);
                pipeline.dispatch(commandBuffer, source,
                        overlay.descriptor(dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind.SAMPLED).index(),
                        settings.get().uiNits());
            }
            recordSwapchainBlit(commandBuffer, stack, source.image(), target.image(), copyWidth, copyHeight);
            submission.waitSemaphore(target.acquireSemaphore(), 0L, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            commands.submit(submission, use);
            submission.signalSemaphore(target.presentSemaphore(), 0L, VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT);
        } finally {
            context.graphics().resolveGraphicsUse(submission, use);
        }
    }

    private void ensurePipeline() {
        if (pipeline != null) {
            return;
        }
        pipeline = RtHdrCompositePipeline.create(context);
    }

    static void recordSwapchainBlit(VkCommandBuffer commandBuffer, MemoryStack stack,
            long sourceImage, long swapchainImage, int copyWidth, int copyHeight) {
        VkImageMemoryBarrier2.Buffer toDestination = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        toDestination.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).srcAccessMask(VK13.VK_ACCESS_2_NONE)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_BLIT_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(swapchainImage);
        toDestination.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VkMemoryBarrier2.Buffer sourceVisibility = VkMemoryBarrier2.calloc(1, stack).sType$Default();
        sourceVisibility.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_MEMORY_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_BLIT_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT);
        VK14.vkCmdPipelineBarrier2(commandBuffer,
                VkDependencyInfo.calloc(stack).sType$Default()
                        .pImageMemoryBarriers(toDestination).pMemoryBarriers(sourceVisibility));

        VkImageBlit2.Buffer region = VkImageBlit2.calloc(1, stack);
        region.get(0).sType$Default();
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(copyWidth, copyHeight, 1);
        region.get(0).dstOffsets(0).set(0, copyHeight, 0);
        region.get(0).dstOffsets(1).set(copyWidth, 0, 1);
        VK13.vkCmdBlitImage2(commandBuffer, VkBlitImageInfo2.calloc(stack).sType$Default()
                .srcImage(sourceImage).srcImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .dstImage(swapchainImage).dstImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .filter(VK10.VK_FILTER_NEAREST).pRegions(region));

        VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
        toPresent.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_BLIT_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_NONE).dstAccessMask(VK13.VK_ACCESS_2_NONE)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(swapchainImage);
        toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack).sType$Default();
        memory.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_BLIT_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                        | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        VK14.vkCmdPipelineBarrier2(commandBuffer,
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
