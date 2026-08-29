package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/** Owns extra swapchain-image acquisition and the generated-before-real deferred present queue. */
final class GeneratedFrameQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(GeneratedFrameQueue.class);
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
            long[] swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage,
            boolean hdrBackbuffer, UiPresentationResources ui, FrameGeneration generation) {
        pendingImageIndex = -1;
        pendingPresentSemaphore = 0L;
        if (failed || swapchain == 0L || srcImage == 0L) {
            return;
        }
        try {
            ensureCapacity(device, swapchainImages.length + 1);
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
            recordBlit(submission, blitSource, swapchainImages[pendingImageIndex],
                    copyWidth, copyHeight, acquireSemaphore, pendingPresentSemaphore);
        } catch (Throwable error) {
            failed = true;
            pendingImageIndex = -1;
            pendingPresentSemaphore = 0L;
            LOGGER.error("DLSS-FG present-record failed; frame generation disabled", error);
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
                LOGGER.error("DLSS-FG present failed; frame generation disabled", error);
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
            HdrPresentation.recordSwapchainBlit(cmd, stack, srcImage, dstImage, copyW, copyH);
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
