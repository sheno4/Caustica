package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.runtime.geometry.RtGeometryProfiling;

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
    public FrameSnapshot latestFrame() { return frameStats.latestFrame(); }

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
        frameStats.endFrame();
    }

    @Override
    public void configure(MetricSchema minecraftMetrics) {
        frameStats.configureFrameMetrics(minecraftMetrics);
    }

    @Override
    public ExtractionStamp extraction(GeometrySource source, int geometryCount) {
        return geometryProfiling.extraction(source, geometryCount);
    }

    @Override
    public void published(ExtractionStamp stamp) {
        geometryProfiling.published((RtGeometryProfiling.ExtractionStamp) stamp);
    }

    @Override
    public void afterPublicationVisible(LongConsumer action) {
        geometryProfiling.afterPublicationVisible(action);
    }

    @Override
    public void afterPublicationVisible(Object identity, LongConsumer action) {
        geometryProfiling.afterPublicationVisible(identity, action);
    }

    @Override
    public long publicationCutoff() {
        return geometryProfiling.publicationCutoff();
    }

    @Override
    public void frameAssembled(long publicationCutoff) {
        geometryProfiling.frameAssembled(publicationCutoff);
    }

    @Override
    public void resetPublications() {
        geometryProfiling.resetPublications();
    }
}
