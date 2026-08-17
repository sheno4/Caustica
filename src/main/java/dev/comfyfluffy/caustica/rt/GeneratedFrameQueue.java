package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/** Owns extra swapchain-image acquisition and the generated-before-real deferred present queue. */
final class GeneratedFrameQueue {
    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private int pendingImageIndex = -1;
    private long pendingPresentSemaphore;
    private boolean failed;

    boolean failed() {
        return failed;
    }

    void prepare(GraphicsSubmission submission, VkDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage,
            boolean hdrBackbuffer, UiPresentationResources ui, FrameGeneration generation) {
        pendingImageIndex = -1;
        pendingPresentSemaphore = 0L;
        if (failed || swapchain == 0L || srcImage == 0L) {
            return;
        }
        try {
            ensureCapacity(device, swapchainImages.size() + 1);
            GpuImage interpolation = generation.interpolate(submission, backbufferView, srcImage,
                    swapW, swapH, hdrBackbuffer, ui);
            if (interpolation == null) {
                return;
            }
            long blitSource = interpolation.image();
            int copyWidth = Math.min(swapW, interpolation.width());
            int copyHeight = Math.min(swapH, interpolation.height());
            long acquireSemaphore = acquireSemaphores[acquireCursor];
            acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer imageIndex = stack.callocInt(1);
                int result = KHRSwapchain.vkAcquireNextImageKHR(
                        device, swapchain, ACQUIRE_TIMEOUT_NS, acquireSemaphore, 0L, imageIndex);
                if (result != VK10.VK_SUCCESS && result != 1000001003) {
                    return;
                }
                pendingImageIndex = imageIndex.get(0);
            }
            pendingPresentSemaphore = presentSemaphores[pendingImageIndex];
            recordBlit(submission, blitSource, swapchainImages.getLong(pendingImageIndex),
                    copyWidth, copyHeight, acquireSemaphore, pendingPresentSemaphore);
        } catch (Throwable error) {
            failed = true;
            pendingImageIndex = -1;
            pendingPresentSemaphore = 0L;
            CausticaMod.LOGGER.error("DLSS-FG present-record failed; frame generation disabled", error);
        }
    }

    void flush(long swapchain, VkQueue presentQueue) {
        if (!failed && pendingImageIndex >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                present.pWaitSemaphores(stack.longs(pendingPresentSemaphore));
                present.swapchainCount(1);
                present.pSwapchains(stack.longs(swapchain));
                present.pImageIndices(stack.ints(pendingImageIndex));
                KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
            } catch (Throwable error) {
                failed = true;
                CausticaMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", error);
            } finally {
                pendingImageIndex = -1;
                pendingPresentSemaphore = 0L;
            }
        }
    }

    private void recordBlit(GraphicsSubmission submission, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis));

            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1);
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd,
                    VkDependencyInfo.calloc(stack).sType$Default()
                            .pImageMemoryBarriers(toPresent).pMemoryBarriers(mem));
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        enqueuePresent(submission, cmd, acquireSem, presentSem);
    }

    static void enqueuePresent(GraphicsSubmission submission, VkCommandBuffer commandBuffer,
            long acquireSemaphore, long presentSemaphore) {
        submission.waitSemaphore(acquireSemaphore, 0L, 65536L);
        submission.execute(commandBuffer);
        submission.signalSemaphore(presentSemaphore, 0L, 4096L);
    }

    private void ensureCapacity(VkDevice device, int semaphoreCount) {
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        destroyAcquireSemaphores(device);
        acquireSemaphores = new long[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer semaphore = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device, info, null, semaphore) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = semaphore.get(0);
            }
        }
        acquireCursor = 0;
    }

    void destroy(VkDevice device) {
        destroyAcquireSemaphores(device);
        pendingImageIndex = -1;
        pendingPresentSemaphore = 0L;
        failed = false;
    }

    private void destroyAcquireSemaphores(VkDevice device) {
        for (long semaphore : acquireSemaphores) {
            if (semaphore != 0L) {
                VK10.vkDestroySemaphore(device, semaphore, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
    }

}
