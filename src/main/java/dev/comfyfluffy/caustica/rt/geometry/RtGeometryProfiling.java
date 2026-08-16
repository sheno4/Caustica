package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.rt.RtFrameStats;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Low-overhead CPU telemetry for retained geometry moving from extraction to frame visibility. */
public final class RtGeometryProfiling {
    public enum SourceKind {
        TERRAIN("terrainDispatch"), TERRAIN_READY("terrainReady"), ENTITY("entity"),
        BLOCK_ENTITY("blockEntity"), PARTICLE("particle");

        final String metricPrefix;

        SourceKind(String metricPrefix) {
            this.metricPrefix = metricPrefix;
        }
    }

    public static final class ExtractionStamp {
        private final SourceKind kind;
        private final long frame;
        private final long nanos;
        private final int geometryCount;
        private long publishedNanos;

        ExtractionStamp(SourceKind kind, long frame, long nanos, int geometryCount) {
            this.kind = kind;
            this.frame = frame;
            this.nanos = nanos;
            this.geometryCount = geometryCount;
        }
    }

    private static final EventType VISIBILITY_EVENT = EventType.getEventType(GeometryVisibilityEvent.class);
    private static final EventType BUILD_READY_EVENT = EventType.getEventType(GeometryBuildReadyLatencyEvent.class);
    private static final EventType COMMAND_RECORD_EVENT = EventType.getEventType(BlasCommandRecordEvent.class);
    private static final ConcurrentLinkedQueue<ExtractionStamp> PUBLISHED = new ConcurrentLinkedQueue<>();

    private RtGeometryProfiling() {
    }

    public static ExtractionStamp extraction(SourceKind kind, int geometryCount) {
        if (!RtFrameStats.enabled() && !VISIBILITY_EVENT.isEnabled()) {
            return null;
        }
        return new ExtractionStamp(kind, RtFrameStats.frameSerial(), System.nanoTime(), geometryCount);
    }

    /** Called by a source acknowledgment after the retained maps contain this geometry. */
    public static void published(ExtractionStamp stamp) {
        if (stamp == null) {
            return;
        }
        stamp.publishedNanos = System.nanoTime();
        PUBLISHED.add(stamp);
    }

    /** Completes publication samples after the first FrameUpdate containing them has been assembled. */
    public static void frameVisible() {
        ExtractionStamp stamp;
        while ((stamp = PUBLISHED.poll()) != null) {
            recordVisible(stamp);
        }
    }

    public static void resetPublications() {
        PUBLISHED.clear();
    }

    private static void recordVisible(ExtractionStamp stamp) {
        long frame = RtFrameStats.frameSerial();
        long now = System.nanoTime();
        long frames = Math.max(0L, frame - stamp.frame);
        long micros = Math.max(0L, now - stamp.nanos) / 1_000L;
        String prefix = stamp.kind.metricPrefix;
        RtFrameStats.FRAME.count(prefix + "VisibilitySamples", stamp.geometryCount);
        RtFrameStats.FRAME.count(prefix + "ExtractionToVisibleFramesTotal", frames * stamp.geometryCount);
        RtFrameStats.FRAME.max(prefix + "ExtractionToVisibleFramesMax", frames);
        RtFrameStats.FRAME.count(prefix + "ExtractionToVisibleMicrosTotal", micros * stamp.geometryCount);
        RtFrameStats.FRAME.max(prefix + "ExtractionToVisibleMicrosMax", micros);
        if (VISIBILITY_EVENT.isEnabled() && frames > 1L) {
            GeometryVisibilityEvent event = new GeometryVisibilityEvent();
            event.source = prefix;
            event.geometryCount = stamp.geometryCount;
            event.frames = frames;
            event.micros = micros;
            event.publicationToFrameMicros = Math.max(0L, now - stamp.publishedNanos) / 1_000L;
            event.commit();
        }
    }

    static long beginBuild() {
        return BUILD_READY_EVENT.isEnabled() ? System.nanoTime() : 0L;
    }

    static void finishBuild(long startedNanos, int triangles, boolean update, boolean compaction,
                            Throwable failure) {
        if (startedNanos == 0L) {
            return;
        }
        long micros = Math.max(0L, System.nanoTime() - startedNanos) / 1_000L;
        if (failure == null && micros < 1_000L) {
            return;
        }
        GeometryBuildReadyLatencyEvent event = new GeometryBuildReadyLatencyEvent();
        event.triangles = triangles;
        event.update = update;
        event.compaction = compaction;
        event.micros = micros;
        event.failed = failure != null;
        event.commit();
    }

    static long beginCommandRecord() {
        return COMMAND_RECORD_EVENT.isEnabled() ? System.nanoTime() : 0L;
    }

    static void endCommandRecord(long startedNanos) {
        if (startedNanos == 0L) {
            return;
        }
        long micros = Math.max(0L, System.nanoTime() - startedNanos) / 1_000L;
        if (micros < 100L) {
            return;
        }
        BlasCommandRecordEvent event = new BlasCommandRecordEvent();
        event.micros = micros;
        event.commit();
    }

    @Name("dev.comfyfluffy.caustica.GeometryVisibility")
    @Label("Geometry extraction to visibility")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    static final class GeometryVisibilityEvent extends Event {
        @Label("Source") String source;
        @Label("Geometry count") int geometryCount;
        @Label("Frames") long frames;
        @Label("Microseconds") long micros;
        @Label("Publication to frame microseconds") long publicationToFrameMicros;
    }

    @Name("dev.comfyfluffy.caustica.GeometryBuildReadyLatency")
    @Label("Geometry submit-to-ready wall latency")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    static final class GeometryBuildReadyLatencyEvent extends Event {
        @Label("Triangles") int triangles;
        @Label("Update") boolean update;
        @Label("Compaction") boolean compaction;
        @Label("Microseconds") long micros;
        @Label("Failed") boolean failed;
    }

    @Name("dev.comfyfluffy.caustica.BlasCommandRecord")
    @Label("BLAS CPU command recording")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    static final class BlasCommandRecordEvent extends Event {
        @Label("Microseconds") long micros;
    }
}
