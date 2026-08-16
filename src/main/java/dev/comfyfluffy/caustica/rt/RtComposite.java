package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.rt.geometry.RtSceneGeometryManager;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Host-facing coordinator for the ray-traced frame lifecycle.
 *
 * <p>GPU state, command recording, and frame policy live in lifecycle-specific renderer owners. This facade
 * preserves the stable host boundary and access surface independently of those implementation details.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    private final RtFrameRenderer renderer = RtFrameRenderer.INSTANCE;

    private RtComposite() {
    }

    public static boolean enabled() {
        return RtFrameRenderer.enabled();
    }

    public static long frameCounter() {
        return RtFrameRenderer.frameCounter();
    }

    public long currentTlasHandle() {
        return renderer.currentTlasHandle();
    }

    public RtSceneGeometryManager sceneGeometry() {
        return renderer.sceneGeometry();
    }

    public boolean hasFailed() {
        return renderer.hasFailed();
    }

    public RtExposure exposure() {
        return renderer.exposure();
    }

    public boolean exportLatestResidualExposureExr(Path outputPath) throws IOException {
        return renderer.exportLatestResidualExposureExr(outputPath);
    }

    public boolean requiresSourceWorldFallback() {
        return renderer.requiresSourceWorldFallback();
    }

    public boolean completeStartupBoundary() {
        return renderer.completeStartupBoundary();
    }

    public void resetFailureLatch() {
        renderer.resetFailureLatch();
    }

    public void captureFrame(FrameSnapshot snapshot) {
        renderer.captureFrame(snapshot);
    }

    public void resetExposureHistory() {
        renderer.resetExposureHistory();
    }

    public Matrix4fc currentViewProjection() {
        return renderer.currentViewProjection();
    }

    public void beginFrame() {
        renderer.beginFrame();
    }

    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        return renderer.currentGraphicsUse();
    }

    public void recordOverlayPasses() {
        renderer.recordOverlayPasses();
    }

    public void finishGraphicsUse() {
        renderer.finishGraphicsUse();
    }

    public void endFrame() {
        renderer.endFrame();
    }

    public boolean composite(long nativeColorImage, int width, int height) {
        return renderer.composite(nativeColorImage, width, height);
    }

    public boolean ensurePresentationResourcesReady(GpuContext context, long sceneId, int width, int height) {
        return renderer.ensurePresentationResourcesReady(context, sceneId, width, height);
    }

    public boolean ensureResourcesReady(GpuContext context, SceneResources resources) {
        return renderer.ensureResourcesReady(context, resources);
    }

    public void onResourceReloadStart() {
        renderer.onResourceReloadStart();
    }

    public void onResourceReloadFailed() {
        renderer.onResourceReloadFailed();
    }

    public void onResourcePackApplied() {
        renderer.onResourcePackApplied();
    }

    public void onWorldChanged() {
        renderer.onWorldChanged();
    }

    public void destroy() {
        renderer.destroy();
    }
}
