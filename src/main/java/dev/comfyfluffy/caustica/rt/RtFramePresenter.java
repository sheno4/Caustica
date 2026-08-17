package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import it.unimi.dsi.fastutil.longs.LongList;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

/** Coordinates the presentation components owned by one renderer activation. */
final class RtFramePresenter {
    private final GeneratedFrameQueue generatedFrames = new GeneratedFrameQueue();
    private final FrameGeneration frameGeneration = new FrameGeneration();
    private final PresentationSampler presentationSampler = new PresentationSampler();
    private final HdrPresentation hdrPresentation =
            new HdrPresentation(presentationSampler, frameGeneration);
    private final SdrPqPresentation sdrPqPresentation = new SdrPqPresentation(presentationSampler);
    private RenderedFrame renderedFrame;

    record RenderedFrame(GpuImage hdrDisplayImage, GpuImage motion, GpuImage depth,
            int renderWidth, int renderHeight, Matrix4f currentViewProjection,
            Matrix4f previousViewProjection, boolean hdrReady) {
        RenderedFrame {
            currentViewProjection = new Matrix4f(currentViewProjection);
            previousViewProjection = new Matrix4f(previousViewProjection);
        }
    }

    RtFramePresenter() {
    }

    void beginFrame() {
        invalidateRenderedFrame();
    }

    void invalidateRenderedFrame() {
        renderedFrame = null;
        frameGeneration.invalidate();
    }

    void publish(RenderedFrame frame) {
        renderedFrame = frame;
        frameGeneration.publish(frame);
    }

    public boolean isActive(boolean sceneAvailable) {
        return !generatedFrames.failed() && RtDlssFg.enabled()
                && RtDlssFg.INSTANCE.isAvailable() && sceneAvailable;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, VkDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapWidth, int swapHeight,
            long backbufferView, long sourceImage,
            boolean hdrBackbuffer, UiPresentationResources ui) {
        generatedFrames.prepare(submission, device, swapchain, swapchainImages, presentSemaphores,
                swapWidth, swapHeight, backbufferView, sourceImage,
                hdrBackbuffer, ui, frameGeneration);
    }

    public void flushPendingPresent(long swapchain, VkQueue presentQueue) {
        generatedFrames.flush(swapchain, presentQueue);
    }

    public void destroy(VkDevice device) {
        destroyGpuResources();
        generatedFrames.destroy(device);
    }

    void destroyGpuResources() {
        renderedFrame = null;
        frameGeneration.destroy();
        hdrPresentation.destroy();
        sdrPqPresentation.destroy();
        presentationSampler.destroy();
    }

    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled() && renderedFrame != null
                && renderedFrame.hdrReady() && renderedFrame.hdrDisplayImage() != null;
    }

    public long hdrBackbufferView() {
        RenderedFrame frame = renderedFrame;
        return frame != null && frame.hdrDisplayImage() != null ? frame.hdrDisplayImage().view() : 0L;
    }

    public long hdrBackbufferImage() {
        RenderedFrame frame = renderedFrame;
        return frame != null && frame.hdrDisplayImage() != null ? frame.hdrDisplayImage().image() : 0L;
    }

    public void presentHdr(GraphicsSubmission submission, long swapchainImage, int swapWidth, int swapHeight,
            long acquireSemaphore, long presentSemaphore, UiPresentationResources ui) {
        RenderedFrame frame = renderedFrame;
        if (frame == null || !frame.hdrReady()) {
            throw new IllegalStateException("No rendered HDR frame is available for presentation");
        }
        hdrPresentation.present(submission, frame, swapchainImage, swapWidth, swapHeight,
                acquireSemaphore, presentSemaphore, ui);
    }

    public boolean isPqSdrPresentActive() {
        return CausticaConfig.Rt.Hdr.swapchainPqActive()
                && RtRuntime.hasSession() && !isHdrPresentActive();
    }

    public boolean presentSdrToPq(GraphicsSubmission submission, long swapchainImage,
            int swapWidth, int swapHeight, long sdrMainView,
            long acquireSemaphore, long presentSemaphore) {
        return sdrPqPresentation.present(submission, swapchainImage, swapWidth, swapHeight,
                sdrMainView, acquireSemaphore, presentSemaphore);
    }

    public void captureHudless(long sourceImage, int width, int height, UiPresentationResources ui) {
        frameGeneration.captureHudless(sourceImage, width, height, ui);
    }

}
