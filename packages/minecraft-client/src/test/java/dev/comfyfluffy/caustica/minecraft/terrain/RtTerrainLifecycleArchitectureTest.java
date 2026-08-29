package dev.comfyfluffy.caustica.minecraft.terrain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainLifecycleArchitectureTest {
    @Test
    void frameAdapterDrivesOneTickAndOneRenderPass() throws IOException {
        String source = source("minecraft/MinecraftFrameAdapter.java");

        assertEquals(1, occurrences(source, "terrain.update();"));
        assertEquals(1, occurrences(source, "terrain.frame();"));
    }

    @Test
    void tickFallbackPublishesGeometryBeforeFrameCaptureCanStart() throws IOException {
        String source = source("minecraft/terrain/RtTerrain.java");
        int fallback = source.indexOf("System.nanoTime() - lastFrameStreamNanos > STREAM_FALLBACK_AFTER_NANOS");
        int stream = source.indexOf("stream();", fallback);
        int submit = source.indexOf("submitPendingGeometry();", stream);
        int fallbackEnd = source.indexOf("\n        }", stream);

        assertTrue(fallback >= 0 && stream > fallback && submit > stream && submit < fallbackEnd);
    }

    @Test
    void pendingGeometryDropsStaleMaterialEpochsBeforeRequiringABoundScene() throws IOException {
        String source = source("minecraft/terrain/RtTerrain.java");
        int submit = source.indexOf("private void submitPendingGeometry()");
        int discard = source.indexOf("discardStalePendingGeometry(lookup.epoch());", submit);
        int geometryGuard = source.indexOf("retainedGeometry == null", submit);
        int methodEnd = source.indexOf("\n    }", submit);

        assertTrue(submit >= 0 && discard > submit && geometryGuard > discard && geometryGuard < methodEnd);
    }

    @Test
    void framePublishesPendingTerrainAsOneGroupBeforeAcknowledgingMembers() throws IOException {
        String source = source("minecraft/terrain/RtTerrain.java");
        int submit = source.indexOf("retainedGeometry.submitGroup(");
        int acknowledge = source.indexOf("acknowledgePublication(", submit);
        int remove = source.indexOf("pendingGeometryGroups.removeIf", acknowledge);

        assertTrue(submit >= 0 && acknowledge > submit && remove > acknowledge);
    }

    @Test
    void worldStopJoinsTerrainWorkersBeforeRuntimeGpuDrain() throws IOException {
        String session = source("minecraft/program/MinecraftProgramSession.java");
        int stop = session.indexOf("@Override public synchronized void stop()");
        int producers = session.indexOf("active.stopSceneProducers();", stop);
        int shutdown = session.indexOf("terrain.shutdown();", stop);
        assertTrue(stop >= 0 && producers > stop && shutdown > producers);
        assertEquals(1, occurrences(session, "terrain.shutdown();"));

        String runtime = Files.readString(Path.of(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica/minecraft/MinecraftRtRuntime.java"));
        int worldClose = runtime.indexOf("world.close()");
        int gpuDrain = runtime.indexOf("gpuExecutor().drainAndWaitIdle()", worldClose);
        assertTrue(worldClose >= 0 && gpuDrain > worldClose);
    }

    private static String source(String relative) throws IOException {
        return Files.readString(Path.of(
                "packages/minecraft-client/src/main/java/dev/comfyfluffy/caustica").resolve(relative));
    }

    private static int occurrences(String source, String needle) {
        return (source.length() - source.replace(needle, "").length()) / needle.length();
    }
}
