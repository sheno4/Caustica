package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedInstanceRecordData;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.RetainedInstanceRecordData.Float4;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Immutable current-instance metadata, indexed identically by CPU geometry packing and GPU history lookup.
 * The containing prepared revision owns the source meshes and the dependencies borrowed by these records.
 */
final class RtInstanceTablePlan {
    static final int RECORD_BYTES = RetainedInstanceRecordData.BYTE_SIZE;
    private static final Float4 ZERO_ROW = new Float4(0, 0, 0, 0);
    private static final RetainedInstanceRecordData EMPTY = new RetainedInstanceRecordData(
            0, 0, 0, 0, 0, 0, 0, 0, ZERO_ROW, ZERO_ROW, ZERO_ROW);

    private final InstanceRecord[] records;

    private RtInstanceTablePlan(InstanceRecord[] records) {
        this.records = records;
    }

    int capacity() { return records.length; }
    int mask() { return records.length - 1; }
    int byteSize() { return Math.multiplyExact(records.length, RECORD_BYTES); }
    InstanceRecord record(int slot) { return records[slot]; }

    /** Unchanged assignments let every geometry batch skip individual instance-index validation. */
    boolean sameSlotAssignments(RtInstanceTablePlan other) {
        if (this == other) return true;
        if (other == null || records.length != other.records.length) return false;
        for (int slot = 0; slot < records.length; slot++) {
            InstanceRecord current = records[slot];
            InstanceRecord previous = other.records[slot];
            if (current == previous) continue;
            if (current == null || previous == null || current.identity() != previous.identity()
                    || current.placementOrdinal() != previous.placementOrdinal()) return false;
        }
        return true;
    }

    /** Identity zero marks an empty GPU slot; live identities are nonzero and placement ordinals start at zero. */
    int slot(long identity, long placementOrdinal) {
        int slot = hash(identity, placementOrdinal) & mask();
        while (records[slot] != null) {
            InstanceRecord record = records[slot];
            if (record.identity() == identity && record.placementOrdinal() == placementOrdinal) return slot;
            slot = (slot + 1) & mask();
        }
        return -1;
    }

    /** The unsigned 64-bit finalizer and linear probing are shared with the shader instance lookup. */
    static int hash(long identity, long placementOrdinal) {
        long value = identity + 0x9e3779b97f4a7c15L * (placementOrdinal + 1);
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return (int) (value ^ (value >>> 31));
    }

    /** Writes every slot, including empty-slot bytes, into revision-owned upload storage. */
    void write(ByteBuffer destination, SceneOrigin origin) {
        write(destination, origin, 0, records.length);
    }

    void write(ByteBuffer destination, SceneOrigin origin, int firstSlot, int count) {
        for (int slot = firstSlot; slot < firstSlot + count; slot++) {
            InstanceRecord record = records[slot];
            int base = destination.position();
            RetainedInstanceRecordData data = record == null ? EMPTY : record.data(origin);
            data.write(destination.slice(base, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN));
            destination.position(base + RECORD_BYTES);
        }
    }

    private static Float4 row(float[] rows, int offset) {
        return new Float4(rows[offset], rows[offset + 1], rows[offset + 2], rows[offset + 3]);
    }

    record Input(long identity, long placementOrdinal, MeshBuild<?> mesh,
                 GeometryTransform transform, long instanceData) {
        Input {
            if (identity == 0) throw new IllegalArgumentException("instance identity must be nonzero");
            if (placementOrdinal < 0) throw new IllegalArgumentException("placement ordinal must be non-negative");
        }
    }

    static final class InstanceRecord {
        private final Input input;
        private final MeshTokens tokens;

        private InstanceRecord(Input input, MeshTokens tokens) {
            this.input = input;
            this.tokens = tokens;
        }

        long identity() { return input.identity(); }
        long placementOrdinal() { return input.placementOrdinal(); }
        long meshRevision() { return tokens.revision; }
        long topologyToken() { return tokens.topology == null ? 0 : tokens.topology.value; }
        long positionAddress() { return input.mesh().positions().bytes().address().value(); }
        int positionStride() { return input.mesh().positions().byteStride(); }
        long instanceData() { return input.instanceData(); }
        GeometryTransform transform() { return input.transform(); }

        private RetainedInstanceRecordData data(SceneOrigin origin) {
            float[] rows = transform().relativeTo(origin.x(), origin.y(), origin.z());
            return new RetainedInstanceRecordData(identity(), placementOrdinal(), meshRevision(),
                    topologyToken(), positionAddress(), instanceData(), positionStride(), 0,
                    row(rows, 0), row(rows, 4), row(rows, 8));
        }

        private boolean matches(long identity, long placementOrdinal, MeshBuild<?> mesh,
                                GeometryTransform transform, long instanceData) {
            return input.identity() == identity && input.placementOrdinal() == placementOrdinal
                    && input.mesh() == mesh && input.transform().equals(transform)
                    && input.instanceData() == instanceData;
        }
    }

    /**
     * One persistent builder belongs to each backend and is confined to its scene-preparation worker.
     * Published tables retain canonical token claims; token values are local to this builder.
     */
    static final class Builder {
        private final Map<BuildIdentity, WeakReference<MeshTokens>> meshes = new WeakHashMap<>();
        private final Map<TopologySignature, WeakReference<TopologyToken>> topologies = new WeakHashMap<>();
        private long nextMeshRevision;
        private long nextTopologyToken;
        private InstanceRecord[] reusableSlots;

        RtInstanceTablePlan build(List<Input> inputs) {
            return build(inputs, null);
        }

        /** The previous table belongs to this builder's token namespace and the same retained scene. */
        RtInstanceTablePlan build(List<Input> inputs, RtInstanceTablePlan previous) {
            var assembly = begin(inputs.size(), previous);
            for (Input input : inputs) {
                assembly.add(input.identity(), input.placementOrdinal(), input.mesh(),
                        input.transform(), input.instanceData());
            }
            return assembly.finish();
        }

        /** The caller adds the declared number of instances in their snapshot traversal order. */
        Assembly begin(int instanceCount, RtInstanceTablePlan previous) {
            int capacity = 1;
            int minimum = Math.multiplyExact(instanceCount, 2);
            while (capacity < minimum) capacity = Math.multiplyExact(capacity, 2);
            InstanceRecord[] slots = reusableSlots;
            reusableSlots = null;
            if (slots == null || slots.length != capacity) slots = new InstanceRecord[capacity];
            return new Assembly(slots, previous);
        }

        /** Single-use assembly; finish publishes changed slots or returns cleared scratch to the builder. */
        final class Assembly {
            private final InstanceRecord[] records;
            private final RtInstanceTablePlan previous;

            private Assembly(InstanceRecord[] records, RtInstanceTablePlan previous) {
                this.records = records;
                this.previous = previous;
            }

            void add(long identity, long placementOrdinal, MeshBuild<?> mesh,
                     GeometryTransform transform, long instanceData) {
                if (identity == 0) throw new IllegalArgumentException("instance identity must be nonzero");
                if (placementOrdinal < 0) throw new IllegalArgumentException("placement ordinal must be non-negative");
                int slot = hash(identity, placementOrdinal) & (records.length - 1);
                while (records[slot] != null) {
                    InstanceRecord present = records[slot];
                    if (present.identity() == identity && present.placementOrdinal() == placementOrdinal) {
                        throw new IllegalArgumentException("duplicate instance placement");
                    }
                    slot = (slot + 1) & (records.length - 1);
                }
                int previousSlot = previous == null ? -1 : previous.slot(identity, placementOrdinal);
                InstanceRecord prior = previousSlot < 0 ? null : previous.records[previousSlot];
                records[slot] = prior != null && prior.matches(identity, placementOrdinal, mesh, transform, instanceData)
                        ? prior : new InstanceRecord(new Input(identity, placementOrdinal, mesh, transform, instanceData),
                                tokens(mesh));
            }

            RtInstanceTablePlan finish() {
                if (previous != null && Arrays.equals(previous.records, records)) {
                    // Only unpublished storage is reusable; clearing releases its borrowed mesh references.
                    Arrays.fill(records, null);
                    reusableSlots = records;
                    return previous;
                }
                return new RtInstanceTablePlan(records);
            }
        }

        private MeshTokens tokens(MeshBuild<?> mesh) {
            BuildIdentity identity = new BuildIdentity(mesh);
            WeakReference<MeshTokens> existing = meshes.get(identity);
            MeshTokens tokens = existing == null ? null : existing.get();
            if (tokens == null) {
                tokens = new MeshTokens(identity, ++nextMeshRevision, topology(mesh));
                // WeakHashMap keeps the original equal key on replacement; install the key owned by this claim.
                meshes.remove(identity);
                meshes.put(identity, new WeakReference<>(tokens));
            }
            return tokens;
        }

        private TopologyToken topology(MeshBuild<?> mesh) {
            if (mesh.indexRevision() == null) return null;
            TopologySignature signature = new TopologySignature(mesh.vertexCount(), mesh.indexRevision(),
                    mesh.geometries().stream().map(geometry ->
                            new IndexSlice(geometry.firstIndex(), geometry.indexCount())).toList());
            WeakReference<TopologyToken> existing = topologies.get(signature);
            TopologyToken token = existing == null ? null : existing.get();
            if (token == null) {
                token = new TopologyToken(signature, ++nextTopologyToken);
                topologies.remove(signature);
                topologies.put(signature, new WeakReference<>(token));
            }
            return token;
        }
    }

    private static final class BuildIdentity {
        private final MeshBuild<?> mesh;

        private BuildIdentity(MeshBuild<?> mesh) { this.mesh = mesh; }

        @Override public int hashCode() { return System.identityHashCode(mesh); }
        @Override public boolean equals(Object other) {
            return other instanceof BuildIdentity identity && mesh == identity.mesh;
        }
    }

    /** Keeping the map keys reachable here keeps equal tokens canonical while any table can compare them. */
    private record MeshTokens(BuildIdentity identity, long revision, TopologyToken topology) { }
    private record TopologyToken(TopologySignature signature, long value) { }
    private record TopologySignature(int vertexCount, MeshBuild.IndexRevision revision, List<IndexSlice> slices) { }
    private record IndexSlice(int firstIndex, int indexCount) { }
}
