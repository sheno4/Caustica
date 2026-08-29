package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.geometry.RtGeometryProfiling;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Renderer-owned telemetry implementation. */
public final class RtTelemetryImpl implements RtTelemetry {
    public RtTelemetryImpl() {
    }

    @Override
    public boolean enabled() {
        return RtFrameStats.enabled();
    }

    @Override
    public long frameSerial() {
        return RtFrameStats.frameSerial();
    }

    @Override
    public Frame frame() {
        return RtFrameStats.FRAME;
    }

    @Override
    public void configure(Path outputDirectory, MetricSchema minecraftMetrics) {
        RtFrameStats.configureOutputDirectory(outputDirectory);
        RtFrameStats.configureFrameMetrics(minecraftMetrics);
    }

    @Override
    public ExtractionStamp extraction(GeometrySource source, int geometryCount) {
        return RtGeometryProfiling.extraction(switch (source) {
            case TERRAIN -> RtGeometryProfiling.SourceKind.TERRAIN;
            case TERRAIN_READY -> RtGeometryProfiling.SourceKind.TERRAIN_READY;
            case ENTITY -> RtGeometryProfiling.SourceKind.ENTITY;
            case ENTITY_PLACEMENT -> RtGeometryProfiling.SourceKind.ENTITY_PLACEMENT;
            case BLOCK_ENTITY -> RtGeometryProfiling.SourceKind.BLOCK_ENTITY;
            case PARTICLE -> RtGeometryProfiling.SourceKind.PARTICLE;
        }, geometryCount);
    }

    @Override
    public void published(ExtractionStamp stamp) {
        RtGeometryProfiling.published((RtGeometryProfiling.ExtractionStamp) stamp);
    }

    @Override
    public void afterPublicationVisible(LongConsumer action) {
        RtGeometryProfiling.afterPublicationVisible(action);
    }
}
