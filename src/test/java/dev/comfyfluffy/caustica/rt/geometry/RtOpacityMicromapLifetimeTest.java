package dev.comfyfluffy.caustica.rt.geometry;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtOpacityMicromapLifetimeTest {
    @Test
    void reloadCancelsThenDrainsBeforeDestroyingEpochResources() throws Exception {
        String composite = Files.readString(Path.of("src/main/java/dev/comfyfluffy/caustica/rt/RtWorldResources.java"));
        int reload = composite.indexOf("void beginReload(");
        int cancel = composite.indexOf("materialEpoch.beginReload()", reload);
        int drain = composite.indexOf("context.gpuExecutor().drainAndWaitIdle()", cancel);
        int descriptors = composite.indexOf("pipeline.destroy()", drain);
        int destroy = composite.indexOf("materialEpoch.destroyPublishedEpoch()", descriptors);
        String epoch = Files.readString(Path.of(
                "src/main/java/dev/comfyfluffy/caustica/rt/material/RtMaterialEpoch.java"));
        int providerClose = epoch.indexOf("ProviderManager.INSTANCE.onResourcePackClosing()",
                epoch.indexOf("public void beginReload()"));
        assertTrue(reload >= 0 && reload < cancel && cancel < drain && drain < descriptors
                && descriptors < destroy && providerClose >= 0);
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
