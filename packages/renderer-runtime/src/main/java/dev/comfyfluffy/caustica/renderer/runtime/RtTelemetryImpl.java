package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.runtime.geometry.RtGeometryProfiling;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Renderer-owned telemetry implementation. */
public final class RtTelemetryImpl implements RtTelemetry {
    private final RtFrameStats frameStats;
    private final RtGeometryProfiling geometryProfiling;

    public RtTelemetryImpl() {
        frameStats = new RtFrameStats();
        geometryProfiling = new RtGeometryProfiling(frameStats);
    }

    @Override
    public boolean enabled() {
        return RtFrameStats.enabled();
    }

    @Override
    public long frameSerial() {
        return frameStats.frameSerial();
    }

    @Override
    public Frame frame() {
        return frameStats.frame();
    }

    @Override
    public void beginRenderFrame() {
        frameStats.beginRenderFrame();
    }

    @Override
    public void beginFrameIfInactive() {
        frameStats.frame().beginIfInactive();
    }

    @Override
    public void endFrame() {
        frameStats.frame().end();
    }

    @Override
    public void configure(Path outputDirectory, MetricSchema minecraftMetrics) {
        frameStats.configureOutputDirectory(outputDirectory);
        frameStats.configureFrameMetrics(minecraftMetrics);
    }

    @Override
    public ExtractionStamp extraction(GeometrySource source, int geometryCount) {
        return geometryProfiling.extraction(switch (source) {
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
        geometryProfiling.published((RtGeometryProfiling.ExtractionStamp) stamp);
    }

    @Override
    public void afterPublicationVisible(LongConsumer action) {
        geometryProfiling.afterPublicationVisible(action);
    }
}
