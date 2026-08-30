package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.util.function.Supplier;

/** Converts an SDR host frame to PQ and submits its swapchain blit. */
final class SdrPqPresentation {
    private final VulkanDeviceContext context;
    private final Supplier<RtFramePresenter.Settings> settings;
    private RtSdrPresentPipeline pipeline;
    private GpuImage image;

    SdrPqPresentation(VulkanDeviceContext context, Supplier<RtFramePresenter.Settings> settings) {
        this.context = context;
        this.settings = settings;
    }

    boolean present(GraphicsSubmission submission, AcquiredSwapchainTarget target,
            dev.comfyfluffy.caustica.api.vulkan.GpuImage source) {
        if (source == null) {
            return false;
        }
        if (pipeline == null) {
            pipeline = RtSdrPresentPipeline.create(context);
        }
        if (image == null || image.width() != target.width() || image.height() != target.height()) {
            if (image != null) {
                image.destroy();
            }
            image = context.createStorageImage(target.width(), target.height(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + target.width() + "x" + target.height());
        }
        int copyWidth = Math.min(target.width(), image.width());
        int copyHeight = Math.min(target.height(), image.height());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer commandBuffer = submission.beginTransientCommandBuffer();
            context.bindDescriptorHeaps(commandBuffer);
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                    .dstAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT
                            | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            VK14.vkCmdPipelineBarrier2(commandBuffer,
                    VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre));

            pipeline.dispatch(commandBuffer, image,
                    source.descriptor(dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind.SAMPLED).index(),
                    settings.get().uiNits());
            HdrPresentation.recordSwapchainBlit(
                    commandBuffer, stack, image.image(), target.image(), copyWidth, copyHeight);
            if (VK10.vkEndCommandBuffer(commandBuffer) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            GeneratedFrameQueue.enqueuePresent(
                    submission, commandBuffer, target.acquireSemaphore(), target.presentSemaphore());
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
