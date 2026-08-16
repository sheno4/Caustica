package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.rt.RtFrameStats.MetricSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameMetricsTest {
    @Test
    void schemaIncludesCurrentTerrainAndEntityMetrics() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("terrain.lightScenePublish")));
        assertTrue(schema.counters().contains("terrainMaterialEpochRejects"));
        assertTrue(schema.counters().contains("terrainReadyExtractionToVisibleFramesMax"));
        assertTrue(schema.counters().contains("terrainPendingToSubmitMicrosMax"));
        assertTrue(schema.counters().contains("terrainSubmitToPublicationMicrosMax"));
        assertTrue(schema.counters().contains("entityExtractionToVisibleFramesMax"));
        assertTrue(schema.counters().contains("entityPlacementExtractionToVisibleFramesMax"));
        assertTrue(schema.counters().contains("entityPlacementFreshnessEligible"));
        assertTrue(schema.counters().contains("entityMeshRevisionsSkippedBetweenVisibility"));
        assertTrue(schema.counters().contains("entityMeshVisibilityIntervalFramesMax"));
        assertTrue(schema.counters().contains("entityMeshInitialUnavailableFramesMax"));
        assertTrue(schema.counters().contains("entityMeshPriorPoseLastRenderedAgeFramesMax"));
        assertTrue(schema.counters().contains("entitiesCaptured"));
    }

    @Test
    void nestedEntityCaptureDetailsDoNotContributeToAccountedTime() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().stream()
                .filter(stage -> stage.name().equals("entity.capture"))
                .allMatch(stage -> stage.contributesToAccountedTime()));
        assertTrue(schema.stages().stream()
                .filter(stage -> stage.name().startsWith("entity.capture."))
                .noneMatch(stage -> stage.contributesToAccountedTime()));
    }

}
