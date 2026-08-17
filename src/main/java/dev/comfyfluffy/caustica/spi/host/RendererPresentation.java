package dev.comfyfluffy.caustica.spi.host;

import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * Vulkan presentation seams consumed by the host swapchain adapter.
 * This first-party integration SPI is not part of the extension API.
 */
public interface RendererPresentation {
    boolean presentHdr(GraphicsSubmission submission, long swapchainImage, int width, int height,
                       long acquireSemaphore, long presentSemaphore, UiPresentationResources ui);

    boolean presentSdrToPq(GraphicsSubmission submission, long swapchainImage, int width, int height,
                           long sourceView, long acquireSemaphore, long presentSemaphore);

    boolean frameGenerationActive(boolean sceneAvailable);

    int generatedFrameCount();

    long hdrBackbufferView();

    long hdrBackbufferImage();

    void prepareGeneratedFrames(GraphicsSubmission submission, VkDevice device, long swapchain,
                                LongList swapchainImages, long[] presentSemaphores,
                                int swapWidth, int swapHeight, long backbufferView,
                                long sourceImage, int sourceWidth, int sourceHeight,
                                int generatedCount, boolean hdrBackbuffer,
                                UiPresentationResources ui);

    void flushGeneratedPresents(long swapchain, VkQueue presentQueue);

    void captureHudless(long sourceImage, int width, int height, UiPresentationResources ui);
}
