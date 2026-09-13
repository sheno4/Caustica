package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;

import java.util.List;

/** Frame metrics authored by Minecraft runtime integration and scene sources. */
public final class MinecraftFrameMetrics {
    private static final MetricSchema SCHEMA = new MetricSchema(List.of(
            "runtime.tick",
            "runtime.frameSetup",
            "host.frameCapture",
            "host.fogCapture",
            "host.fogPrepare",
            "host.worldBegin",
            "host.worldMaintenance",
            "host.textureRetire",
            "ui.prepare",
            "ui.redirect",
            "ui.composite",
            "presentation.captureHudless",
            "presentation.hdr",
            "presentation.frameGeneration",
            "presentation.generatedPresent",
            "terrain.markDirty",
            "terrain.tick",
            "terrain.frame",
            "terrain.windowSync",
            "terrain.dirtyDrain",
            "terrain.drainCompletion",
            "terrain.publish",
            "terrain.lightScenePublish",
            "terrain.snapshotDispatch",
            "terrain.paletteCapture",
            "entity.capture",
            "entity.capture.extract",
            "entity.capture.submit",
            "entity.capture.submit.material",
            "entity.capture.submit.setupAnim",
            "entity.capture.submit.modelDraw",
            "entity.capture.submit.modelDraw.direct",
            "entity.capture.submit.modelDraw.fallback",
            "entity.capture.submit.bakedQuads",
            "entity.capture.submit.metrics",
            "entity.blockEntities",
            "entity.particles"), List.of(
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
