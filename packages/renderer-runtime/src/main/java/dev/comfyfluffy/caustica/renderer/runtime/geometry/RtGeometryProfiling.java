package dev.comfyfluffy.caustica.renderer.runtime.geometry;

import dev.comfyfluffy.caustica.renderer.runtime.RtFrameStats;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Enabled;
import jdk.jfr.Timespan;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayDeque;
import java.util.function.LongConsumer;

/** Low-overhead CPU telemetry for retained geometry moving from extraction to frame visibility. */
public final class RtGeometryProfiling {
    public enum SourceKind {
        TERRAIN("terrainDispatch"), TERRAIN_READY("terrainReady"), ENTITY("entity"),
        ENTITY_PLACEMENT("entityPlacement"),
        BLOCK_ENTITY("blockEntity"), PARTICLE("particle");

        final String metricPrefix;

        SourceKind(String metricPrefix) {
            this.metricPrefix = metricPrefix;
        }
    }

    public static final class ExtractionStamp implements RtTelemetry.ExtractionStamp {
        private final long sampleId;
        private final SourceKind kind;
        private final long frame;
        private final long nanos;
        private final int geometryCount;
        private long publishedNanos;

        ExtractionStamp(SourceKind kind, long frame, long nanos, int geometryCount) {
            this.sampleId = NEXT_SAMPLE.incrementAndGet();
            this.kind = kind;
            this.frame = frame;
            this.nanos = nanos;
            this.geometryCount = geometryCount;
        }
    }

    private static final AtomicLong NEXT_SAMPLE = new AtomicLong();
    private static final EventType VISIBILITY_EVENT = EventType.getEventType(GeometryVisibilityEvent.class);
    private static final EventType BUILD_READY_EVENT = EventType.getEventType(GeometryBuildReadyLatencyEvent.class);
    private static final EventType COMMAND_RECORD_EVENT = EventType.getEventType(BlasCommandRecordEvent.class);
    private final RtFrameStats frameStats;
    private final ArrayDeque<Publication> publications = new ArrayDeque<>();
    private long publicationSequence;

    private record Publication(long sequence, LongConsumer assembled) { }

    public RtGeometryProfiling(RtFrameStats frameStats) {
        this.frameStats = java.util.Objects.requireNonNull(frameStats, "frameStats");
    }

    public ExtractionStamp extraction(SourceKind kind, int geometryCount) {
        if (!VISIBILITY_EVENT.isEnabled()) {
            return null;
        }
        return new ExtractionStamp(kind, frameStats.frameSerial(), System.nanoTime(), geometryCount);
    }

    /** Called by a source acknowledgment after the retained maps contain this geometry. */
    public synchronized void published(ExtractionStamp stamp) {
        if (stamp == null) {
            return;
        }
        stamp.publishedNanos = System.nanoTime();
        publications.addLast(new Publication(++publicationSequence, frame -> recordVisible(stamp, frame)));
    }

    /** Captured immediately before the renderer acquires its retained scene revision. */
    public synchronized long publicationCutoff() {
        return publicationSequence;
    }

    /** Only acknowledgments present at the capture boundary belong to this assembled frame. */
    public synchronized void frameVisible(long cutoff) {
        long frame = frameStats.frameSerial();
        while (!publications.isEmpty() && publications.getFirst().sequence <= cutoff) {
            publications.removeFirst().assembled.accept(frame);
        }
    }

    public synchronized void afterPublicationVisible(LongConsumer action) {
        if (action != null) publications.addLast(new Publication(++publicationSequence, action));
    }

    public synchronized void resetPublications() {
        publications.clear();
    }

    private void recordVisible(ExtractionStamp stamp, long frame) {
        long now = System.nanoTime();
        if (VISIBILITY_EVENT.isEnabled()) {
            GeometryVisibilityEvent event = new GeometryVisibilityEvent();
            event.sampleId = stamp.sampleId;
            event.source = stamp.kind.metricPrefix;
            event.geometryCount = stamp.geometryCount;
            event.extractionFrameId = stamp.frame;
            event.frameId = frame;
            event.extractedNanos = stamp.nanos;
            event.publishedNanos = stamp.publishedNanos;
            event.assembledNanos = now;
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
        BlasCommandRecordEvent event = new BlasCommandRecordEvent();
        event.micros = micros;
        event.commit();
    }

    @Name("dev.comfyfluffy.caustica.GeometryVisibility")
    @Label("Geometry extraction to frame assembly")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    @Enabled(false)
    static final class GeometryVisibilityEvent extends Event {
        @Label("Source") String source;
        @Label("Geometry count") int geometryCount;
        long sampleId;
        long extractionFrameId;
        long frameId;
        @Label("System.nanoTime extraction") long extractedNanos;
        @Label("System.nanoTime publication") long publishedNanos;
        @Label("System.nanoTime frame assembly") long assembledNanos;
    }

    @Name("dev.comfyfluffy.caustica.GeometryBuildReadyLatency")
    @Label("Geometry submit-to-ready wall latency")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    @Enabled(false)
    static final class GeometryBuildReadyLatencyEvent extends Event {
        @Label("Triangles") int triangles;
        @Label("Update") boolean update;
        @Label("Compaction") boolean compaction;
        @Timespan(Timespan.MICROSECONDS) long micros;
        @Label("Failed") boolean failed;
    }

    @Name("dev.comfyfluffy.caustica.BlasCommandRecord")
    @Label("BLAS CPU command recording")
    @Category({"Caustica", "Geometry"})
    @StackTrace(false)
    @Enabled(false)
    static final class BlasCommandRecordEvent extends Event {
        @Timespan(Timespan.MICROSECONDS) long micros;
    }
}
