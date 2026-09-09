package dev.comfyfluffy.caustica.renderer.runtime.geometry;

import dev.comfyfluffy.caustica.renderer.runtime.RtFrameStats;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry;
import dev.comfyfluffy.caustica.renderer.runtime.RtTelemetry.GeometrySource;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Enabled;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** CPU telemetry for retained geometry moving from extraction through publication to frame assembly. */
public final class RtGeometryProfiling {
    public static final class ExtractionStamp implements RtTelemetry.ExtractionStamp {
        private final long sampleId;
        private final GeometrySource source;
        private final long frame;
        private final long nanos;
        private final int geometryCount;

        ExtractionStamp(GeometrySource source, long frame, long nanos, int geometryCount) {
            this.sampleId = NEXT_SAMPLE.incrementAndGet();
            this.source = source;
            this.frame = frame;
            this.nanos = nanos;
            this.geometryCount = geometryCount;
        }
    }

    private static final AtomicLong NEXT_SAMPLE = new AtomicLong();
    private static final EventType VISIBILITY_EVENT = EventType.getEventType(GeometryVisibilityEvent.class);
    private final RtFrameStats frameStats;
    private final ArrayDeque<Publication> publications = new ArrayDeque<>();
    private long publicationSequence;

    private record Publication(long sequence, Object identity, LongConsumer assembled) { }

    public RtGeometryProfiling(RtFrameStats frameStats) {
        this.frameStats = Objects.requireNonNull(frameStats, "frameStats");
    }

    public ExtractionStamp extraction(GeometrySource source, int geometryCount) {
        if (!VISIBILITY_EVENT.isEnabled()) {
            return null;
        }
        return new ExtractionStamp(source, frameStats.frameSerial(), System.nanoTime(), geometryCount);
    }

    /** Called by a source acknowledgment after the retained maps contain this geometry. */
    public synchronized void published(ExtractionStamp stamp) {
        if (stamp == null) {
            return;
        }
        long publishedNanos = System.nanoTime();
        publications.addLast(new Publication(++publicationSequence, null,
                frame -> recordAssembled(stamp, publishedNanos, frame)));
    }

    /** Captured immediately before the renderer acquires its retained scene revision. */
    public synchronized long publicationCutoff() {
        return publicationSequence;
    }

    /** Only acknowledgments present at the capture boundary belong to this assembled frame. */
    public synchronized void frameAssembled(long cutoff) {
        long frame = frameStats.frameSerial();
        if (publications.isEmpty()) return;
        var latest = new IdentityHashMap<Object, Publication>();
        for (var publication : publications) {
            if (publication.sequence > cutoff) break;
            if (publication.identity != null) latest.put(publication.identity, publication);
        }
        while (!publications.isEmpty() && publications.getFirst().sequence <= cutoff) {
            var publication = publications.removeFirst();
            if (publication.identity == null || latest.get(publication.identity) == publication) {
                publication.assembled.accept(frame);
            }
        }
    }

    public synchronized void afterPublicationVisible(LongConsumer action) {
        if (action != null) publications.addLast(new Publication(++publicationSequence, null, action));
    }

    public synchronized void afterPublicationVisible(Object identity, LongConsumer action) {
        publications.addLast(new Publication(++publicationSequence,
                Objects.requireNonNull(identity), Objects.requireNonNull(action)));
    }

    public synchronized void resetPublications() {
        publications.clear();
    }

    private void recordAssembled(ExtractionStamp stamp, long publishedNanos, long frame) {
        long now = System.nanoTime();
        if (VISIBILITY_EVENT.isEnabled()) {
            GeometryVisibilityEvent event = new GeometryVisibilityEvent();
            event.sampleId = stamp.sampleId;
            event.source = switch (stamp.source) {
                case TERRAIN -> "terrainDispatch";
                case TERRAIN_READY -> "terrainReady";
                case ENTITY -> "entity";
                case ENTITY_PLACEMENT -> "entityPlacement";
                case BLOCK_ENTITY -> "blockEntity";
                case PARTICLE -> "particle";
            };
            event.geometryCount = stamp.geometryCount;
            event.extractionFrameId = stamp.frame;
            event.frameId = frame;
            event.extractedNanos = stamp.nanos;
            event.publishedNanos = publishedNanos;
            event.assembledNanos = now;
            event.commit();
        }
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

}
