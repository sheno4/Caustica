package dev.comfyfluffy.caustica.minecraft.client;

import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.StageMetric;

import java.util.List;

/** Frame metrics authored by the Minecraft terrain and entity scene sources. */
public final class MinecraftFrameMetrics {
    private static final MetricSchema SCHEMA = new MetricSchema(List.of(
            new StageMetric("terrain.windowSync"),
            new StageMetric("terrain.dirtyDrain"),
            new StageMetric("terrain.drainCompletion"),
            new StageMetric("terrain.publish"),
            new StageMetric("terrain.lightScenePublish"),
            new StageMetric("terrain.snapshotDispatch"),
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

}
