package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtOpacityMicromapLifetimeTest {
    @Test
    void reloadCancelsThenDrainsBeforeDestroyingEpochResources() throws Exception {
        String composite = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        int reload = composite.indexOf("public void onResourceReloadStart()");
        int cancel = composite.indexOf("ProviderManager.INSTANCE.onResourcePackClosing()", reload);
        int drain = composite.indexOf("ctx.gpuExecutor().drainAndWaitIdle()", cancel);
        int destroy = composite.indexOf("destroyOpacityMicromapPipeline()", drain);
        assertTrue(reload >= 0 && reload < cancel && cancel < drain && drain < destroy);
    }

    @Test
    void cancelledBuildCannotEnqueueItsCompactionPhase() throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/geometry/RtSceneGeometryManager.java"));
        int completedBuild = manager.indexOf("publicationState.completeBuild();");
        int cancelled = manager.indexOf("if (cancelled.getAsBoolean())", completedBuild);
        int compact = manager.indexOf("submitCompaction(ctx, cancelled, completion)", completedBuild);
        assertTrue(completedBuild >= 0 && completedBuild < cancelled && cancelled < compact);
    }
}
