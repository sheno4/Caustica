package dev.comfyfluffy.caustica.spi.host;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.frame.SceneResources;
import dev.comfyfluffy.caustica.spi.vulkan.VulkanRendererBackend;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Host lifecycle and frame seams for driving one renderer runtime.
 * This first-party integration SPI is not part of the extension API.
 */
public interface RendererRuntimeController {
    void installVulkanBackend(VulkanRendererBackend backend);

    void installHost(RuntimeHost host);

    void configureShaderCache(Path cacheRoot);

    void startProcess();

    void observeResourcePackAvailable();

    ResourceReloadToken beginResourcePackReload();

    void trackResourcePackReload(ResourceReloadToken reload, CompletableFuture<?> future);

    void observeWorld(Object levelIdentity, long sceneId);

    void invalidateWorld();

    <T extends CausticaRenderPass> T renderPass(ResourceId id, Class<T> type);

    void tick(SceneResources sceneResources, boolean startupSceneReady, long sceneId,
              int displayWidth, int displayHeight, Runnable reconfigureSurface);

    void beginRenderFrame();

    void captureFrame(FrameSnapshot snapshot);

    void beginFrame();

    void recordOverlayPasses();

    void finishGraphicsUse();

    void endFrame();

    boolean composite(long nativeColorImage, int width, int height);

    void resetExposureHistory();

    void resetRendererFailure();

    void shutdown();

    RendererRuntimeStatus status();
}
