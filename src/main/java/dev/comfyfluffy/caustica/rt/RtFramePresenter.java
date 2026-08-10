package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.backend.GraphicsSubmission;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;

import it.unimi.dsi.fastutil.longs.LongList;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation present engine (slice 2). Shows more than one image per rendered frame: the
 * generated frame(s), then the real frame.
 *
 * <p>The host calls {@link #prepareExtraFrames} before flushing its deferred graphics submission, then calls
 * {@link #flushPendingPresents} after that submit and before presenting the real frame. This makes the
 * binary present semaphores part of the host submission and gives display order generated-then-real.
 *
 * <p>The generated frame is {@link RtDlssFg}'s real DLSSG-interpolated output (via
 * {@link RtComposite#fgInterpolate}); when there's simply no captured RT frame this tick (menu/loading/
 * transition — routine) it falls back to duplicating the real frame for just that one frame (see
 * {@code interpFallbackDuplicate} in the present-rate log), but a genuine FG failure is fatal (see that
 * method's docs) rather than silently duplicating forever. Called from both present paths — the normal SDR
 * {@code blitFromTexture} TAIL, and (via {@code hdrBackbuffer=true}) explicitly from inside the HDR present
 * hook, since the HDR/PQ path cancels {@code blitFromTexture} at HEAD and never reaches the TAIL inject.
 * Gated by {@code caustica.rt.fg} (default off).
 */
public final class RtFramePresenter {
    public static final RtFramePresenter INSTANCE = new RtFramePresenter();

    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private boolean failed;

    // Frames acquired and recorded this frame, awaiting the host's post-submit present seam.
    private int[] pendingImageIndex = new int[0];
    private long[] pendingPresentSem = new long[0];
    private int pendingCount;

    // Present-rate diagnostics: real vs generated vkQueuePresentKHR calls per second, independent of MC's own
    // fps counter (which only counts rendered/simulated frames, not our extra presents).
    private long logWindowStartNs;
    private int realFramesInWindow;
    private int generatedFramesInWindow;
    private int interpOkInWindow;
    private int interpFallbackInWindow;

    private RtFramePresenter() {
    }

    /** Whether FG extra-present should run this frame for a host scene that is ready to present. */
    public boolean isActive(boolean sceneAvailable) {
        return !failed && RtDlssFg.enabled() && RtDlssFg.INSTANCE.isAvailable()
                && sceneAvailable;
    }

    /**
     * Acquire {@code generatedCount} extra swapchain images and record a Y-flipped blit of {@code srcImage}
     * (the final rendered frame, GENERAL layout) into each through the host's deferred submission. The
     * presents happen later in {@link #flushPendingPresents}.
     * Blits DLSSG's real interpolated output per generated frame, or a duplicate of the real frame when RT
     * simply isn't producing frames this tick (routine). A genuine DLSSG failure latches FG off for the
     * session — see {@link RtComposite#fgInterpolate}.
     *
     * @param hdrBackbuffer whether {@code backbufferView}/{@code srcImage} is the PQ HDR backbuffer
     *     ({@link RtComposite#hdrBackbufferView()}, already UI-composited) rather than the SDR main target —
     *     selects DLSSG's HDR backbuffer format/flag in {@link RtComposite#fgInterpolate}.
     */
    public void prepareExtraFrames(GraphicsSubmission submission, VkDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage, int srcW, int srcH, int generatedCount,
            boolean hdrBackbuffer, UiPresentationResources ui) {
        pendingCount = 0;
        if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
            return;
        }
        try {
            ensureCapacity(device, swapchainImages.size() + 1, generatedCount);
            for (int i = 0; i < generatedCount; i++) {
                // null = no captured RT frame this tick (menu/loading/transition — routine, not a bug): fall
                // back to duplicating the real frame for just this one frame. A genuine FG failure instead
                // throws, caught below, which disables FG for the session.
                GpuImage interp = RtComposite.INSTANCE.fgInterpolate(submission, backbufferView, srcImage,
                        swapW, swapH, i + 1, generatedCount, hdrBackbuffer, ui);
                if (interp != null) {
                    interpOkInWindow++;
                } else {
                    interpFallbackInWindow++;
                }
                long blitSrc = interp != null ? interp.image : srcImage;
                int copyW = Math.min(swapW, interp != null ? interp.width : srcW);
                int copyH = Math.min(swapH, interp != null ? interp.height : srcH);

                long acquireSem = acquireSemaphores[acquireCursor];
                acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

                int imageIndex;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer pIndex = stack.callocInt(1);
                    int r = KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, ACQUIRE_TIMEOUT_NS, acquireSem, 0L, pIndex);
                    if (r != VK10.VK_SUCCESS && r != 1000001003 /* SUBOPTIMAL */) {
                        return; // out-of-date/timeout: present what we have, let MC recover
                    }
                    imageIndex = pIndex.get(0);
                }
                long dstImage = swapchainImages.getLong(imageIndex);
                long presentSem = presentSemaphores[imageIndex];
                recordBlit(submission, blitSrc, dstImage, copyW, copyH, acquireSem, presentSem);

                pendingImageIndex[pendingCount] = imageIndex;
                pendingPresentSem[pendingCount] = presentSem;
                pendingCount++;
            }
        } catch (Throwable t) {
            failed = true;
            pendingCount = 0;
            CausticaMod.LOGGER.error("DLSS-FG present-record failed; frame generation disabled", t);
        }
    }

    /**
     * Present the frames acquired in {@link #prepareExtraFrames} (call at {@code present()} HEAD, after MC's
     * {@code submit()} has flushed — so the present semaphores are signaled — and before MC presents the real
     * frame, giving generated-then-real order).
     */
    public void flushPendingPresents(long swapchain, VkQueue presentQueue) {
        int presentedThisFrame = 0;
        if (!failed && pendingCount != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int i = 0; i < pendingCount; i++) {
                    VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                    present.pWaitSemaphores(stack.longs(pendingPresentSem[i]));
                    present.swapchainCount(1);
                    present.pSwapchains(stack.longs(swapchain));
                    present.pImageIndices(stack.ints(pendingImageIndex[i]));
                    KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    presentedThisFrame++;
                }
            } catch (Throwable t) {
                failed = true;
                CausticaMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", t);
            } finally {
                pendingCount = 0;
            }
        }
        if (RtDlssFg.enabled()) {
            logPresentRate(presentedThisFrame);
        }
    }

    /**
     * Debug diagnostic (2026-07-01): tracks real vs generated {@code vkQueuePresentKHR} calls per second,
     * separate from MC's own fps counter — that counter only reflects simulated/rendered frames
     * (blitFromTexture calls), so it would NOT show an increase from FG's extra presents even if the display
     * is genuinely receiving more frames. Logged only when {@code caustica.rt.fg} is enabled.
     */
    private void logPresentRate(int generatedThisFrame) {
        realFramesInWindow++;
        generatedFramesInWindow += generatedThisFrame;
        long now = System.nanoTime();
        if (logWindowStartNs == 0L) {
            logWindowStartNs = now;
            return;
        }
        long elapsed = now - logWindowStartNs;
        if (elapsed < LOG_INTERVAL_NS) {
            return;
        }
        double seconds = elapsed / 1.0e9;
        double realFps = realFramesInWindow / seconds;
        double totalFps = (realFramesInWindow + generatedFramesInWindow) / seconds;
        CausticaMod.LOGGER.info(
                "[FG present-rate] real={} gen={} realFps={} totalPresentFps={} configuredMultiFrameCount={} "
                        + "interpOk={} interpFallbackDuplicate={}",
                realFramesInWindow, generatedFramesInWindow,
                String.format("%.1f", realFps), String.format("%.1f", totalFps),
                RtDlssFg.INSTANCE.effectiveMultiFrameCount(), interpOkInWindow, interpFallbackInWindow);
        logWindowStartNs = now;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }

    private void recordBlit(GraphicsSubmission submission, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = submission.beginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Swapchain UNDEFINED -> TRANSFER_DST (stage/access values mirror MC's blitFromTexture).
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make the source's prior writes visible to the blit read — the world render (duplicate path) or
            // the DLSSG evaluate that wrote the interp image earlier in this same submit (interp path).
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like the host path.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        // Preserve the host present order: wait on acquire, run the blit, then signal the present semaphore.
        // The deferred host submit is what actually signals the binary semaphore.
        enqueuePresent(submission, cmd, acquireSem, presentSem);
    }

    static void enqueuePresent(GraphicsSubmission submission, VkCommandBuffer commandBuffer,
                               long acquireSemaphore, long presentSemaphore) {
        submission.waitSemaphore(acquireSemaphore, 0L, 65536L);
        submission.execute(commandBuffer);
        submission.signalSemaphore(presentSemaphore, 0L, 4096L);
    }

    private void ensureCapacity(VkDevice device, int semaphoreCount, int generatedCount) {
        if (pendingImageIndex.length < generatedCount) {
            pendingImageIndex = new int[generatedCount];
            pendingPresentSem = new long[generatedCount];
        }
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        destroy(device);
        acquireSemaphores = new long[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device, sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = p.get(0);
            }
        }
        acquireCursor = 0;
    }

    /** Destroy the acquire-semaphore pool (device teardown). */
    public void destroy(VkDevice device) {
        for (long sem : acquireSemaphores) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device, sem, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingCount = 0;
        failed = false;
        logWindowStartNs = 0L;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
    }
}
