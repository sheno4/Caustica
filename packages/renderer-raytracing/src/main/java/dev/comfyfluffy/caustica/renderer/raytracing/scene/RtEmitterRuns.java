package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stable primitive runs resolve their unique light identities once per light revision. */
final class RtEmitterRuns {
    private record Run(int byteOffset, int primitiveCount, int lightOrdinal) { }

    private final List<Run> runs;
    private final long[] identities;
    private final int[] dense;
    private Object revision;
    private Object generation;
    private int[] linked = new int[0];

    private RtEmitterRuns(List<Run> runs, long[] identities) {
        this.runs = runs;
        this.identities = identities;
        dense = new int[identities.length];
        Arrays.fill(dense, -1);
    }

    static RtEmitterRuns empty() { return new RtEmitterRuns(List.of(), new long[0]); }

    boolean hasRevision(Object revision) { return generation != null && this.revision == revision; }
    Object generation() { return generation; }
    /** Immutable by ownership: later resolutions replace this array instead of modifying it. */
    int[] linked() { return linked; }

    /** One joined worker updates this vector; completed slots retain only the stamp and immutable linked array. */
    void resolve(Object revision, Long2IntFunction indices) {
        if (hasRevision(revision)) return;
        boolean changed = generation == null;
        for (int index = 0; index < identities.length; index++) {
            int value = indices.getOrDefault(identities[index], -1);
            changed |= dense[index] != value;
            dense[index] = value;
        }
        if (changed) {
            linked = Arrays.stream(dense).filter(value -> value >= 0).toArray();
            generation = new Object();
        }
        this.revision = revision;
    }

    void pack(ByteBuffer target) {
        for (Run run : runs) {
            target.position(run.byteOffset);
            int value = run.lightOrdinal < 0 ? -1 : dense[run.lightOrdinal];
            for (int index = 0; index < run.primitiveCount; index++) target.putInt(value);
        }
    }

    /** Reusable worker scratch; builds retain immutable runs and copy the light identities. */
    static final class Builder {
        private final List<Run> runs = new ArrayList<>();
        private final Long2IntOpenHashMap ordinals = new Long2IntOpenHashMap();
        private final LongArrayList identities = new LongArrayList();

        Builder() { ordinals.defaultReturnValue(-1); }

        void reset() {
            runs.clear();
            ordinals.clear();
            identities.clear();
        }

        private void addRun(int byteOffset, int count, int ordinal) {
            runs.add(new Run(byteOffset, count, ordinal));
        }

        void addSpan(int byteOffset, int firstPrimitive, int primitiveCount,
                     List<RetainedSceneSnapshot.PrimitiveEmitter> ranges) {
            int primitive = firstPrimitive;
            int end = Math.addExact(firstPrimitive, primitiveCount);
            for (int index = RtRetainedSceneBackend.firstEmitterRange(ranges, firstPrimitive); index < ranges.size(); index++) {
                var range = ranges.get(index);
                if (range.firstPrimitive() >= end) break;
                int rangeEnd = (int) Math.min((long) end, (long) range.firstPrimitive() + range.primitiveCount());
                if (rangeEnd <= primitive) continue;
                int start = Math.max(primitive, range.firstPrimitive());
                if (start > primitive) addRun(byteOffset + (primitive - firstPrimitive) * Integer.BYTES,
                        start - primitive, -1);
                int ordinal = ordinals.get(range.lightIdentity());
                if (ordinal < 0) {
                    ordinal = identities.size();
                    identities.add(range.lightIdentity());
                    ordinals.put(range.lightIdentity(), ordinal);
                }
                addRun(byteOffset + (start - firstPrimitive) * Integer.BYTES, rangeEnd - start, ordinal);
                primitive = rangeEnd;
            }
            if (primitive < end) addRun(byteOffset + (primitive - firstPrimitive) * Integer.BYTES,
                    end - primitive, -1);
        }

        RtEmitterRuns build() { return new RtEmitterRuns(List.copyOf(runs), identities.toLongArray()); }
    }
}
