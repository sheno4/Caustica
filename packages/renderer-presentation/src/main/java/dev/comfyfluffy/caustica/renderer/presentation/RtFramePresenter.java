package dev.comfyfluffy.caustica.renderer.presentation;


import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.engine.frame.UiPresentationResources;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssFrameGeneration;
import dev.comfyfluffy.caustica.spi.vulkan.GraphicsSubmission;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

import java.util.Objects;
import java.util.function.Supplier;

/** Coordinates the presentation components owned by one renderer activation. */
public final class RtFramePresenter {
    private final DlssFrameGeneration dlssFrameGeneration;
    private final Supplier<Settings> settings;
    private final GeneratedFrameQueue generatedFrames = new GeneratedFrameQueue();
    private final FrameGeneration frameGeneration;
    private final HdrPresentation hdrPresentation;
    private final SdrPqPresentation sdrPqPresentation;
    private RenderedFrame renderedFrame;

    public record Settings(boolean hdrEnabled, boolean pqSwapchainActive, float uiNits) {}

    public record RenderedFrame(GpuImage hdrDisplayImage, GpuImage motion, GpuImage depth,
            int renderWidth, int renderHeight, Matrix4f currentViewProjection,
            Matrix4f previousViewProjection, boolean hdrReady) {
        public RenderedFrame {
            currentViewProjection = new Matrix4f(currentViewProjection);
            previousViewProjection = new Matrix4f(previousViewProjection);
        }
    }

    public RtFramePresenter(VulkanDeviceContext context, DlssFrameGeneration dlssFrameGeneration,
            Supplier<Settings> settings) {
        this.dlssFrameGeneration = Objects.requireNonNull(dlssFrameGeneration, "dlssFrameGeneration");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.frameGeneration = new FrameGeneration(context, dlssFrameGeneration);
        this.hdrPresentation = new HdrPresentation(context, frameGeneration, this.settings);
        this.sdrPqPresentation = new SdrPqPresentation(context, this.settings);
    }

    public void beginFrame() {
        invalidateRenderedFrame();
    }

    public void invalidateRenderedFrame() {
        renderedFrame = null;
        frameGeneration.invalidate();
    }

    public void resetSceneHistory() {
        renderedFrame = null;
        frameGeneration.resetHistory();
    }

    public void publish(RenderedFrame frame) {
        renderedFrame = frame;
        frameGeneration.publish(frame);
    }

    public boolean isActive(boolean sceneAvailable) {
        return !generatedFrames.failed() && dlssFrameGeneration.enabled()
                && dlssFrameGeneration.isAvailable() && sceneAvailable;
    }

    public void prepareGeneratedFrame(GraphicsSubmission submission, VkDevice device, long swapchain,
            long[] swapchainImages, long[] presentSemaphores, int swapWidth, int swapHeight,
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
        dlssFrameGeneration.destroyAfterDeviceIdle();
    }

    private void destroyGpuResources() {
        renderedFrame = null;
        frameGeneration.destroy();
        hdrPresentation.destroy();
        sdrPqPresentation.destroy();
    }

    public boolean isHdrPresentActive() {
        return isHdrPresentActive(settings.get());
    }

    private boolean isHdrPresentActive(Settings current) {
        return current.hdrEnabled() && renderedFrame != null
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
        Settings current = settings.get();
        return current.pqSwapchainActive() && !isHdrPresentActive(current);
    }

    public boolean presentSdrToPq(GraphicsSubmission submission, long swapchainImage,
            int swapWidth, int swapHeight, GpuImage source,
            long acquireSemaphore, long presentSemaphore) {
        return sdrPqPresentation.present(submission, swapchainImage, swapWidth, swapHeight,
                source, acquireSemaphore, presentSemaphore);
    }

    public void captureHudless(long sourceImage, int width, int height, UiPresentationResources ui) {
        frameGeneration.captureHudless(sourceImage, width, height, ui);
    }

}
