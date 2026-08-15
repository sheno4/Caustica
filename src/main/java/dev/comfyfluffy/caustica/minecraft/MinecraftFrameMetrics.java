package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.rt.RtFrameStats.MetricSchema;
import dev.comfyfluffy.caustica.rt.RtFrameStats.StageMetric;

import java.util.List;

/** Frame metrics authored by the Minecraft terrain and entity scene sources. */
public final class MinecraftFrameMetrics {
    private static final MetricSchema SCHEMA = new MetricSchema(List.of(
            accounted("terrain.windowSync"),
            accounted("terrain.dirtyDrain"),
            accounted("terrain.drainCompletion"),
            accounted("terrain.publish"),
            accounted("terrain.lightScenePublish"),
            accounted("terrain.snapshotDispatch"),
            accounted("entity.capture"),
            detail("entity.capture.extract"),
            detail("entity.capture.submit"),
            detail("entity.capture.submit.material"),
            detail("entity.capture.submit.setupAnim"),
            detail("entity.capture.submit.modelDraw"),
            detail("entity.capture.submit.modelDraw.direct"),
            detail("entity.capture.submit.modelDraw.fallback"),
            detail("entity.capture.submit.bakedQuads"),
            detail("entity.capture.submit.metrics"),
            detail("entity.capture.submit.parity"),
            accounted("entity.blockEntities"),
            accounted("entity.particles")), List.of(
            "sectionsSnapshotted", "sectionCopies",
            "terrainMaterialEpochRejects", "entitiesCaptured", "blockEntitiesCaptured",
            "particlesCaptured", "entityModelSubmissions", "entityCuboids",
            "entityModelQuads", "entityModelVertices", "entityBakedQuads", "entityBakedVertices",
            "entityDirectSubmissions", "entityDirectFallbacks", "entityDirectQuads", "entityDirectVertices",
            "entitySpecializedCuboids", "entityGenericCuboids", "entityParityChecks"));

    private MinecraftFrameMetrics() {
    }

    public static MetricSchema schema() {
        return SCHEMA;
    }

    private static StageMetric accounted(String name) {
        return new StageMetric(name, true);
    }

    private static StageMetric detail(String name) {
        return new StageMetric(name, false);
    }
}
