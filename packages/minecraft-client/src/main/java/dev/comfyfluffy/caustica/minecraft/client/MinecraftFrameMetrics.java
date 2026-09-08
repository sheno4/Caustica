package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.StageMetric;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;

import java.util.List;

/** Frame metrics authored by Minecraft runtime integration and scene sources. */
public final class MinecraftFrameMetrics {
    private static final MetricSchema SCHEMA = new MetricSchema(List.of(
            new StageMetric("runtime.tick"),
            new StageMetric("runtime.frameSetup"),
            new StageMetric("host.frameCapture"),
            new StageMetric("host.worldBegin"),
            new StageMetric("host.worldMaintenance"),
            new StageMetric("host.textureRetire"),
            new StageMetric("ui.prepare"),
            new StageMetric("ui.redirect"),
            new StageMetric("ui.composite"),
            new StageMetric("presentation.captureHudless"),
            new StageMetric("presentation.hdr"),
            new StageMetric("presentation.frameGeneration"),
            new StageMetric("presentation.generatedPresent"),
            new StageMetric("terrain.markDirty"),
            new StageMetric("terrain.tick"),
            new StageMetric("terrain.frame"),
            new StageMetric("terrain.windowSync"),
            new StageMetric("terrain.dirtyDrain"),
            new StageMetric("terrain.drainCompletion"),
            new StageMetric("terrain.publish"),
            new StageMetric("terrain.lightScenePublish"),
            new StageMetric("terrain.snapshotDispatch"),
            new StageMetric("terrain.paletteCapture"),
            new StageMetric("entity.capture"),
            new StageMetric("entity.capture.extract"),
            new StageMetric("entity.capture.submit"),
            new StageMetric("entity.capture.submit.material"),
            new StageMetric("entity.capture.submit.setupAnim"),
            new StageMetric("entity.capture.submit.modelDraw"),
            new StageMetric("entity.capture.submit.modelDraw.direct"),
            new StageMetric("entity.capture.submit.modelDraw.fallback"),
            new StageMetric("entity.capture.submit.bakedQuads"),
            new StageMetric("entity.capture.submit.metrics"),
            new StageMetric("entity.blockEntities"),
            new StageMetric("entity.particles")), List.of(
            "sectionsSnapshotted", "sectionCopies",
            "terrainMaterialEpochRejects", "entitiesCaptured", "blockEntitiesCaptured",
            "blockEntityGeometrySubmissions", "blockEntityGeometryDeferred",
            "particlesCaptured", "entityModelSubmissions", "entityCuboids",
            "entityModelQuads", "entityModelVertices", "entityBakedQuads", "entityBakedVertices",
            "entityDirectSubmissions", "entityDirectFallbacks", "entityDirectQuads", "entityDirectVertices",
            "entitySpecializedCuboids", "entityGenericCuboids",
            "entityPlacementFreshnessEligible", "entityPlacementInitialSubmissions", "entityMeshOnlyUpdates"));

    private MinecraftFrameMetrics() {
    }

    public static MetricSchema schema() {
        return SCHEMA;
    }

    /** Several client ticks can contribute to one upcoming render frame without resetting its counters. */
    static RtTelemetry.Scope beginTick(RtTelemetry telemetry) {
        return stage(telemetry, "runtime.tick");
    }

    /** Producer edits before rendering and presentation after it share the same host frame profile. */
    static RtTelemetry.Scope stage(RtTelemetry telemetry, String name) {
        telemetry.beginFrameIfInactive();
        return telemetry.frame().stage(name);
    }

}
