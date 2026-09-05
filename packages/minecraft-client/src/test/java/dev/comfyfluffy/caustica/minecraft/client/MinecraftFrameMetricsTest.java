package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftFrameMetricsTest {
    @Test
    void schemaIncludesCurrentTerrainAndEntityMetrics() {
        MetricSchema schema = MinecraftFrameMetrics.schema();
        assertTrue(schema.stages().stream().anyMatch(stage -> stage.name().equals("terrain.lightScenePublish")));
        assertTrue(schema.counters().contains("terrainMaterialEpochRejects"));
        assertTrue(schema.counters().contains("entityPlacementFreshnessEligible"));
        assertTrue(schema.counters().contains("entityPlacementInitialSubmissions"));
        assertTrue(schema.counters().contains("blockEntityGeometrySubmissions"));
        assertTrue(schema.counters().contains("blockEntityGeometryDeferred"));
        assertTrue(schema.counters().contains("entitiesCaptured"));
    }

}
