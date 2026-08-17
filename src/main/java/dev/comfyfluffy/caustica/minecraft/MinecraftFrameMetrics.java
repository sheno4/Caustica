package dev.comfyfluffy.caustica.minecraft;

import dev.comfyfluffy.caustica.spi.host.HostTelemetry.MetricSchema;
import dev.comfyfluffy.caustica.spi.host.HostTelemetry.StageMetric;

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
            accounted("entity.blockEntities"),
            accounted("entity.particles")), List.of(
            "sectionsSnapshotted", "sectionCopies",
            "terrainMaterialEpochRejects", "entitiesCaptured", "blockEntitiesCaptured",
            "blockEntityGeometrySubmissions", "blockEntityGeometryDeferred",
            "particlesCaptured", "entityModelSubmissions", "entityCuboids",
            "entityModelQuads", "entityModelVertices", "entityBakedQuads", "entityBakedVertices",
            "entityDirectSubmissions", "entityDirectFallbacks", "entityDirectQuads", "entityDirectVertices",
            "entitySpecializedCuboids", "entityGenericCuboids",
            "terrainDispatchVisibilitySamples", "terrainDispatchExtractionToVisibleFramesTotal",
            "terrainDispatchExtractionToVisibleFramesMax", "terrainDispatchExtractionToVisibleMicrosTotal",
            "terrainDispatchExtractionToVisibleMicrosMax", "terrainReadyVisibilitySamples",
            "terrainReadyExtractionToVisibleFramesTotal", "terrainReadyExtractionToVisibleFramesMax",
            "terrainReadyExtractionToVisibleMicrosTotal", "terrainReadyExtractionToVisibleMicrosMax",
            "terrainWorkerToPendingSamples",
            "terrainWorkerToPendingMicrosTotal", "terrainWorkerToPendingMicrosMax",
            "terrainPendingToSubmitSamples", "terrainPendingToSubmitMicrosTotal",
            "terrainPendingToSubmitMicrosMax", "terrainSubmitToPublicationSamples",
            "terrainSubmitToPublicationMicrosTotal", "terrainSubmitToPublicationMicrosMax",
            "terrainPendingGeometryGroups", "entityVisibilitySamples",
            "entityExtractionToVisibleFramesTotal", "entityExtractionToVisibleFramesMax",
            "entityExtractionToVisibleMicrosTotal", "entityExtractionToVisibleMicrosMax",
            "entityPlacementVisibilitySamples", "entityPlacementExtractionToVisibleFramesTotal",
            "entityPlacementExtractionToVisibleFramesMax", "entityPlacementExtractionToVisibleMicrosTotal",
            "entityPlacementExtractionToVisibleMicrosMax",
            "entityPlacementFreshnessEligible", "entityPlacementInitialSubmissions", "entityMeshOnlyUpdates",
            "entityMeshVisibilitySamples", "entityMeshRevisionsSkippedBetweenVisibility",
            "entityMeshVisibilityIntervalFramesTotal", "entityMeshVisibilityIntervalFramesSamples",
            "entityMeshVisibilityIntervalFramesMax", "entityMeshInitialUnavailableFramesTotal",
            "entityMeshInitialUnavailableFramesSamples", "entityMeshInitialUnavailableFramesMax",
            "entityMeshPriorRevisionInterveningFramesAtReplacementTotal",
            "entityMeshPriorRevisionInterveningFramesAtReplacementSamples",
            "entityMeshPriorRevisionInterveningFramesAtReplacementMax",
            "blockEntityVisibilitySamples", "blockEntityExtractionToVisibleFramesTotal",
            "blockEntityExtractionToVisibleFramesMax", "blockEntityExtractionToVisibleMicrosTotal",
            "blockEntityExtractionToVisibleMicrosMax", "particleVisibilitySamples",
            "particleExtractionToVisibleFramesTotal", "particleExtractionToVisibleFramesMax",
            "particleExtractionToVisibleMicrosTotal", "particleExtractionToVisibleMicrosMax"));

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
