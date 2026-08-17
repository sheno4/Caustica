package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.geometry.RtGeometryProfiling;
import dev.comfyfluffy.caustica.spi.host.HostTelemetry;

import java.nio.file.Path;
import java.util.function.LongConsumer;

/** Renderer-owned implementation of the first-party host telemetry SPI. */
final class RtHostTelemetry implements HostTelemetry {
    static final RtHostTelemetry INSTANCE = new RtHostTelemetry();

    private RtHostTelemetry() {
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
    public void configure(Path outputDirectory, MetricSchema hostMetrics) {
        RtFrameStats.configureOutputDirectory(outputDirectory);
        RtFrameStats.configureFrameMetrics(hostMetrics);
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
