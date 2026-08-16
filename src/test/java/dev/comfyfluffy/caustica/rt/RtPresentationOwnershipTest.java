package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtPresentationOwnershipTest {
    private static final Path RT = Path.of("src", "main", "java", "dev", "comfyfluffy", "caustica", "rt");

    @Test
    void renderedFrameIsPublishedOnlyAfterSubmissionAndClearedOnFailure() throws IOException {
        String renderer = Files.readString(RT.resolve("RtFrameRenderer.java"));
        int publish = renderer.indexOf("RtFramePresenter.INSTANCE.publish");
        int execute = renderer.lastIndexOf("submission.execute(cmd);", publish);
        int lifetimeMark = renderer.indexOf("framePushSlot.graphicsUse.mark", publish);
        assertTrue(execute >= 0 && execute < publish && publish < lifetimeMark,
                "presentation must publish after execute and before successful-use owners are marked");

        int isolatedFallback = renderer.indexOf("catch (ProviderManager.SceneSourceUnavailableException unavailable)");
        int failure = renderer.indexOf("catch (Throwable t)", isolatedFallback);
        int invalidate = renderer.indexOf("invalidateRenderedFrame()", failure);
        int latch = renderer.indexOf("failed = true", invalidate);
        assertTrue(failure >= 0 && failure < invalidate && invalidate < latch,
                "a failed frame must invalidate presentation before latching renderer failure");
    }

    @Test
    void resizeInvalidatesPresentationBeforeWaitingAndDestroyingImages() throws IOException {
        String resources = Files.readString(RT.resolve("RtFrameResources.java"));
        int ensure = resources.indexOf("boolean ensureSized(");
        int invalidate = resources.indexOf("invalidateRenderedFrame()", ensure);
        int wait = resources.indexOf("context.waitIdle()", invalidate);
        int destroy = resources.indexOf("destroySizedImages()", wait);
        assertTrue(ensure >= 0 && ensure < invalidate && invalidate < wait && wait < destroy,
                "resize must detach the published frame before draining and destroying its images");
    }

    @Test
    void presenterOwnsPresentationWithoutCallingBackIntoRendererOrComposite() throws IOException {
        String presenter = Files.readString(RT.resolve("RtFramePresenter.java"));
        assertTrue(presenter.contains("void beginFrame()") && presenter.contains("renderedFrame = null"));
        assertTrue(presenter.contains("void destroyGpuResources()"));
        assertFalse(presenter.contains("RtFrameRenderer"));
        assertFalse(presenter.contains("RtComposite"));
    }

    @Test
    void semaphorePoolGrowthDoesNotDestroyDeferredPresentationResources() throws IOException {
        String presenter = Files.readString(RT.resolve("RtFramePresenter.java"));
        int ensure = presenter.indexOf("private void ensureCapacity(");
        int destroy = presenter.indexOf("public void destroy(VkDevice device)", ensure);
        assertTrue(ensure >= 0 && destroy > ensure);
        String capacityBody = presenter.substring(ensure, destroy);

        assertTrue(capacityBody.contains("destroyAcquireSemaphores(device)"));
        assertFalse(capacityBody.contains("destroy(device)"),
                "growing the semaphore pool must not run full presenter teardown");
        assertFalse(capacityBody.contains("destroyGpuResources"),
                "deferred present commands still own the current images and pipelines");
        assertFalse(capacityBody.contains("renderedFrame = null"));
    }
}
