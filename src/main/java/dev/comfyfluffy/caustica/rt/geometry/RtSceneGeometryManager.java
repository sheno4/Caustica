package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.provider.GeometryTransform;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.api.provider.TriangleMesh;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Owns retained provider mesh uploads, BLASes, geometry records, and their exact graphics lifetime.
 * Caller-supplied retained geometry occupies a prefix before provider-owned geometry records.
 *
 * <p>Residency is explicit: a mesh stays uploaded across frames once retained, until it is released,
 * replaced by a new {@code retainMesh} call, or its owning provider stops. Nothing here compares mesh
 * bytes frame to frame.
 */
public final class RtSceneGeometryManager {
    private static final int TABLE_RING = 4;
    private static final long MIN_BUFFER_BYTES = 256L;

    /** Immutable build policy selected by the producer when it submits a mesh update. */
    public enum BuildClass {
        STATIC,
        DEFORMING,
        REBUILT
    }

    private final RtGeometryMaterialResolver materialResolver;
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();
    private RtRetainedGeometryCoordinator<?> packedGeometry;
    private final Map<MeshKey, ResidentMesh> residents = new LinkedHashMap<>();
    /** Keyed residents used by in-engine captures rather than public providers. */
    private final Map<Long, DynamicResident> cachedResidents = new HashMap<>();
    private final Map<Long, DeformingResident> deformingResidents = new HashMap<>();
    private final TableSlot[] tables = new TableSlot[TABLE_RING];
    private int tableCursor;
    private long materialEpoch;
    private long dynamicResidentGeneration;
    private boolean dynamicMaterialsInvalid;

    public RtSceneGeometryManager(RtGeometryMaterialResolver materialResolver) {
        this.materialResolver = materialResolver;
    }

    /**
     * Registers the one packed retained stream that contributes to this scene's geometry index space.
     * The stream remains engine-owned even when a producer supplies its packed candidates asynchronously.
     */
    @SuppressWarnings("unchecked")
    public synchronized <M> RtRetainedGeometryCoordinator<M> acquirePackedCoordinator(
            IntSupplier initialCapacity, int geometrySemanticFlags) {
        if (packedGeometry == null) {
            packedGeometry = new RtRetainedGeometryCoordinator<>(initialCapacity, geometrySemanticFlags);
        }
        return (RtRetainedGeometryCoordinator<M>) packedGeometry;
    }

    /** Detach an idle packed stream at renderer-session teardown. */
    public synchronized void releasePackedCoordinator() {
        packedGeometry = null;
    }

    public Capture beginCapture() {
        return new Capture();
    }

    public void invalidateMaterials() {
        materialEpoch++;
        dynamicMaterialsInvalid = true;
    }

    public FrameGeometry finishFrame(GpuContext ctx, Capture capture, SceneOrigin origin,
                                     Set<ResourceId> liveProviders) {
        reconcile(ctx, capture, liveProviders);
        reconcileDynamicMaterials(ctx);
        RtRetainedGeometryCoordinator<?> packed = packedGeometry;
        int packedRecords = packed == null || !packed.ready() ? 0 : packed.tablePrefix().recordCount();
        LinkedHashMap<MeshKey, Integer> recordIndices = new LinkedHashMap<>();
        for (DesiredInstance instance : capture.instances.values()) {
            MeshKey meshKey = new MeshKey(instance.provider, instance.meshKey);
            if (!residents.containsKey(meshKey)) {
                throw new IllegalArgumentException("geometry instance references an unretained mesh " + meshKey);
            }
            recordIndices.putIfAbsent(meshKey, packedRecords + recordIndices.size());
        }
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        List<BuildUse> buildUses = new ArrayList<>();
        for (MeshKey key : recordIndices.keySet()) {
            ResidentMesh resident = residents.get(key);
            if (resident.pendingBuild != null) {
                builds.add(resident.pendingBuild);
                buildUses.add(new BuildUse(resident, resident.pendingBuild, resident.scratch));
            }
        }
        int recordCount = RtGeometryAbi.checkedRecordCount(packedRecords, recordIndices.size());
        TableSlot table = selectTable(ctx, recordCount);
        if (packedRecords != 0) {
            RtGeometryAbi.TablePrefix packedTable = packed.tablePrefix();
            MemoryUtil.memCopy(packedTable.mappedAddress(), table.buffer.mapped,
                    (long) packedRecords * RtGeometryAbi.RECORD_BYTES);
        }
        for (Map.Entry<MeshKey, Integer> entry : recordIndices.entrySet()) {
            ResidentMesh resident = residents.get(entry.getKey());
            int[] classes = resident.packed.classTris();
            long address = table.buffer.mapped + (long) entry.getValue() * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, resident.primitives.deviceAddress, resident.indices.deviceAddress,
                    resident.texCoords.deviceAddress, 0L, 0f, 0f, 0f,
                    0, classes[0], classes[0] + classes[1],
                    RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES);
        }
        if (recordCount != 0) {
            table.buffer.flush(0L, (long) recordCount * RtGeometryAbi.RECORD_BYTES);
        }

        List<RtAccel.Instance> instances = new ArrayList<>(
                (packed == null || !packed.ready() ? 0 : packed.instances().size()) + capture.instances.size());
        if (packed != null && packed.ready()) {
            instances.addAll(packed.instances());
        }
        List<ResidentMesh> usedMeshes = new ArrayList<>(recordIndices.size());
        for (Map.Entry<MeshKey, Integer> entry : recordIndices.entrySet()) {
            usedMeshes.add(residents.get(entry.getKey()));
        }
        for (DesiredInstance instance : capture.instances.values()) {
            MeshKey meshKey = new MeshKey(instance.provider, instance.meshKey);
            ResidentMesh resident = residents.get(meshKey);
            instances.add(new RtAccel.Instance(instance.transform.relativeTo(
                    origin.x(), origin.y(), origin.z()), resident.accel.deviceAddress,
                    recordIndices.get(meshKey)));
        }
        return new FrameGeometry(List.copyOf(instances), new RtGeometryAbi.TablePrefix(table.buffer.mapped, recordCount),
                List.copyOf(builds), new FrameUse(table, List.copyOf(usedMeshes), List.copyOf(buildUses)));
    }

    public void markGraphicsUse(FrameGeometry frame, GpuContext ctx, GraphicsUse graphicsUse) {
        frame.use.table.graphicsUse.mark(graphicsUse);
        for (ResidentMesh mesh : frame.use.meshes) {
            mesh.graphicsUse.mark(graphicsUse);
        }
        for (BuildUse build : frame.use.builds) {
            if (build.mesh.pendingBuild == build.operation) {
                build.mesh.pendingBuild = null;
                build.mesh.scratch = null;
            }
            ctx.gpuExecutor().retireAfterGraphics(graphicsUse, build.scratch::destroy);
        }
    }

    /** Records every manager-owned BLAS operation needed before the frame TLAS. */
    public boolean recordBlasBuilds(GpuContext ctx, VkCommandBuffer commandBuffer,
                                    FrameGeometry frame, DynamicFrame dynamic) {
        if (frame.blasBuilds.isEmpty() && dynamic.blasBuilds.isEmpty()) return false;
        RtAccel.recordBlasBuilds(ctx, commandBuffer, frame.blasBuilds);
        RtAccel.recordBlasBuilds(ctx, commandBuffer, dynamic.blasBuilds);
        return true;
    }

    /** Build-ready TLAS view over every manager-owned instance segment. */
    public RtAccel.PreparedTlas prepareTlas(GpuContext ctx, FrameGeometry frame,
                                            DynamicFrame dynamic, GraphicsUse graphicsUse) {
        return RtAccel.prepareTlas(ctx, frame.instances, dynamic.instances, tlasRing, graphicsUse);
    }

    /** Opens the manager-owned dynamic suffix of this frame's one geometry table and instance stream. */
    public DynamicFrame beginDynamicFrame(GpuContext ctx, FrameGeometry frame) {
        reconcileDynamicMaterials(ctx);
        return new DynamicFrame(ctx, frame);
    }

    /** Completes the manager-owned dynamic table suffix before renderer submission. */
    public void finishDynamicFrame(DynamicFrame frame) {
        frame.finish();
    }

    /** Associates all dynamic resources with the graphics submission that traces them. */
    public void markGraphicsUse(DynamicFrame frame, GraphicsUse graphicsUse) {
        frame.markGraphicsUse(graphicsUse);
    }

    /** Teardown after the device is idle. */
    public void shutdown() {
        tlasRing.destroy();
        for (ResidentMesh mesh : residents.values()) {
            mesh.destroy();
        }
        residents.clear();
        for (DynamicResident resident : cachedResidents.values()) resident.destroy();
        cachedResidents.clear();
        for (DeformingResident resident : deformingResidents.values()) resident.destroy();
        deformingResidents.clear();
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) {
                tables[i].buffer.destroy();
                tables[i] = null;
            }
        }
    }

    /**
     * Applies this frame's explicit release/retain declarations, plus two engine-owned invalidations:
     * a mesh whose provider is no longer live is a safety net against a provider that stopped without
     * releasing its own meshes; a mesh with a stale {@code materialEpoch} is repacked from its own
     * stored source rather than requiring the provider to resubmit unrelated mesh data.
     */
    private void reconcile(GpuContext ctx, Capture capture, Set<ResourceId> liveProviders) {
        List<Map.Entry<MeshKey, TriangleMesh>> repack = new ArrayList<>();
        var iterator = residents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MeshKey, ResidentMesh> entry = iterator.next();
            MeshKey key = entry.getKey();
            ResidentMesh resident = entry.getValue();
            ReconcileAction action = reconcileAction(capture.retains.containsKey(key),
                    capture.releases.contains(key), liveProviders.contains(key.provider()),
                    resident.materialEpoch != materialEpoch);
            if (action == ReconcileAction.KEEP) {
                continue;
            }
            if (action == ReconcileAction.REPACK) {
                repack.add(Map.entry(key, resident.source));
            }
            ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse, resident::destroy);
            iterator.remove();
        }
        for (Map.Entry<MeshKey, TriangleMesh> entry : capture.retains.entrySet()) {
            residents.put(entry.getKey(), upload(ctx, entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<MeshKey, TriangleMesh> entry : repack) {
            residents.put(entry.getKey(), upload(ctx, entry.getKey(), entry.getValue()));
        }
    }

    private void reconcileDynamicMaterials(GpuContext ctx) {
        if (!dynamicMaterialsInvalid) return;
        dynamicMaterialsInvalid = false;
        dynamicResidentGeneration = nextDynamicResidentGeneration(dynamicResidentGeneration);
        for (DynamicResident resident : cachedResidents.values()) {
            ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse, resident::destroy);
        }
        cachedResidents.clear();
        for (DeformingResident resident : deformingResidents.values()) {
            resident.retire(ctx);
        }
        deformingResidents.clear();
    }

    enum ReconcileAction {
        /** Stays resident unchanged. */
        KEEP,
        /** Destroyed; a fresh upload from {@code capture.retains} takes the same key this frame. */
        REPLACE,
        /** Destroyed and not replaced: explicitly released, or its provider is no longer live. */
        RELEASE,
        /** Destroyed and re-uploaded from its own stored source (material epoch invalidation only). */
        REPACK
    }

    /**
     * Precedence, most to least authoritative: a retain this frame always replaces (even if the same
     * key was also released this frame — a contradictory pair of calls resolves as "replace"); absent
     * a retain, an explicit release or a dead provider drops the mesh; absent either of those, a stale
     * material epoch repacks in place; otherwise the mesh is untouched.
     */
    static ReconcileAction reconcileAction(boolean retainedThisFrame, boolean releasedThisFrame,
                                           boolean providerLive, boolean materialStale) {
        if (retainedThisFrame) {
            return ReconcileAction.REPLACE;
        }
        if (releasedThisFrame || !providerLive) {
            return ReconcileAction.RELEASE;
        }
        if (materialStale) {
            return ReconcileAction.REPACK;
        }
        return ReconcileAction.KEEP;
    }

    private ResidentMesh upload(GpuContext ctx, MeshKey key, TriangleMesh mesh) {
        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, materialResolver);
        int inputAndStorage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        GpuBuffer positions = buffer(ctx, (long) packed.positions().length * Float.BYTES,
                inputAndStorage, "scene geometry " + key + " positions");
        GpuBuffer indices = buffer(ctx, (long) packed.indices().length * Integer.BYTES,
                inputAndStorage, "scene geometry " + key + " indices");
        GpuBuffer texCoords = buffer(ctx, (long) packed.texCoords().length * Float.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "scene geometry " + key + " texcoords");
        GpuBuffer primitives = buffer(ctx, (long) packed.primitives().length * Float.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "scene geometry " + key + " primitives");
        MemoryUtil.memFloatBuffer(positions.mapped, packed.positions().length).put(packed.positions());
        MemoryUtil.memIntBuffer(indices.mapped, packed.indices().length).put(packed.indices());
        MemoryUtil.memFloatBuffer(texCoords.mapped, packed.texCoords().length).put(packed.texCoords());
        MemoryUtil.memFloatBuffer(primitives.mapped, packed.primitives().length).put(packed.primitives());
        positions.flush(0L, (long) packed.positions().length * Float.BYTES);
        indices.flush(0L, (long) packed.indices().length * Integer.BYTES);
        texCoords.flush(0L, (long) packed.texCoords().length * Float.BYTES);
        primitives.flush(0L, (long) packed.primitives().length * Float.BYTES);
        RtAccel.PersistentBuild build = RtAccel.preparePersistentBlasBuild(ctx,
                positions.deviceAddress, packed.vertexCount(), indices.deviceAddress, packed.classTris(),
                "scene geometry " + key + " BLAS");
        return new ResidentMesh(mesh, packed, positions, indices, texCoords, primitives,
                build.accel(), build.backing(), build.op(), build.scratch(), materialEpoch);
    }

    private static GpuBuffer buffer(GpuContext ctx, long bytes, int usage, String label) {
        return ctx.createBuffer(Math.max(MIN_BUFFER_BYTES, bytes), usage, true, label);
    }

    private TableSlot selectTable(GpuContext ctx, int records) {
        int selected = tableCursor;
        tableCursor = (tableCursor + 1) % tables.length;
        TableSlot table = tables[selected];
        long bytes = Math.max(MIN_BUFFER_BYTES, (long) records * RtGeometryAbi.RECORD_BYTES);
        if (table != null) {
            ctx.gpuExecutor().graphicsUseWaiter().await(table.graphicsUse);
        }
        if (table == null || table.buffer.size < bytes) {
            if (table != null) {
                table.buffer.destroy();
            }
            table = new TableSlot(ctx.createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "scene geometry table"));
            tables[selected] = table;
        }
        return table;
    }

    public final class Capture {
        private final Map<MeshKey, TriangleMesh> retains = new LinkedHashMap<>();
        private final Set<MeshKey> releases = new LinkedHashSet<>();
        private final Map<InstanceKey, DesiredInstance> instances = new LinkedHashMap<>();

        public SceneGeometrySink sink(ResourceId provider) {
            return new SceneGeometrySink() {
                @Override
                public void retainMesh(long key, TriangleMesh mesh) {
                    MeshKey scoped = new MeshKey(provider, key);
                    if (retains.putIfAbsent(scoped, mesh) != null) {
                        throw new IllegalArgumentException("duplicate retained mesh key " + scoped);
                    }
                }

                @Override
                public void releaseMesh(long key) {
                    MeshKey scoped = new MeshKey(provider, key);
                    if (!releases.add(scoped)) {
                        throw new IllegalArgumentException("duplicate released mesh key " + scoped);
                    }
                }

                @Override
                public void instance(long key, long meshKey, GeometryTransform transform) {
                    InstanceKey scoped = new InstanceKey(provider, key);
                    if (instances.putIfAbsent(scoped, new DesiredInstance(provider, meshKey, transform)) != null) {
                        throw new IllegalArgumentException("duplicate geometry instance key " + scoped);
                    }
                }
            };
        }
    }

    public record FrameGeometry(List<RtAccel.Instance> instances, RtGeometryAbi.TablePrefix tablePrefix,
                                List<RtAccel.PreparedBlas> blasBuilds, FrameUse use) {
    }

    public record FrameUse(TableSlot table, List<ResidentMesh> meshes, List<BuildUse> builds) {
    }

    public record BuildUse(ResidentMesh mesh, RtAccel.PreparedBlas operation, GpuBuffer scratch) {
    }

    /**
     * Mutable engine-owned suffix for geometry captured after retained residents. Sources provide record
     * contents and instances; allocation, record indexing, flushing, and table lifetime stay here.
     */
    public final class DynamicFrame {
        private final GpuContext ctx;
        private final FrameGeometry base;
        private final ArrayList<RtAccel.Instance> instances = new ArrayList<>();
        private final ArrayList<RtAccel.PreparedBlas> blasBuilds = new ArrayList<>();
        private final ArrayList<RtAccel.PreparedBlas> transientBlas = new ArrayList<>();
        private final ArrayList<GpuBuffer> transientBuffers = new ArrayList<>();
        private final ArrayList<DynamicResident> persistentUses = new ArrayList<>();
        private final ArrayList<PendingBuild> persistentBuilds = new ArrayList<>();
        private int count;

        private DynamicFrame(GpuContext ctx, FrameGeometry base) {
            this.ctx = ctx;
            this.base = base;
        }

        private int appendRecord(long primitiveAddress, long indexAddress, long textureCoordinateAddress,
                                 long motionAddress, float rigidX, float rigidY, float rigidZ,
                                 int triangleBase, int[] classTriangles, int semanticFlags) {
            if (classTriangles == null || classTriangles.length != RtAccel.SBT_CLASSES) {
                throw new IllegalArgumentException("missing acceleration-structure class counts");
            }
            int record = RtGeometryAbi.checkedIndex(base.tablePrefix.recordCount(), count);
            ensureDynamicCapacity(record + 1);
            long address = base.use.table.buffer.mapped + (long) record * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, primitiveAddress, indexAddress, textureCoordinateAddress,
                    motionAddress, rigidX, rigidY, rigidZ, triangleBase, classTriangles[0],
                    classTriangles[0] + classTriangles[1], semanticFlags);
            count++;
            return record;
        }

        private void appendInstance(float[] transform, long accelAddress, int record, int mask) {
            instances.add(new RtAccel.Instance(transform, accelAddress, record, mask));
        }

        /** Captures one frame's source-provided motion data without exposing its GPU address. */
        public MotionInput motion(float[] values, int count, float rigidX, float rigidY, float rigidZ) {
            if (count == 0) return new MotionInput(0L, rigidX, rigidY, rigidZ);
            GpuBuffer buffer = ctx.createBuffer(Math.max(MIN_BUFFER_BYTES, (long) count * Float.BYTES),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "scene motion");
            MemoryUtil.memFloatBuffer(buffer.mapped, count).put(values, 0, count);
            buffer.flush(0L, (long) count * Float.BYTES);
            transientBuffers.add(buffer);
            return new MotionInput(buffer.deviceAddress, rigidX, rigidY, rigidZ);
        }

        /**
         * Uploads and builds a mesh declared {@link BuildClass#REBUILT}, then appends its one-frame
         * record and instance. The manager owns every resulting resource through graphics completion.
         */
        public void appendRebuilt(PackedInput input, MotionInput motion, float[] transform, int mask) {
            if (input.buildClass != BuildClass.REBUILT) {
                throw new IllegalArgumentException("one-frame submission requires REBUILT build class");
            }
            PackedLayout layout = PackedLayout.create(input.positions.length, input.indices.length,
                    input.textureCoordinates.length, input.primitives.length);
            int usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                    | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            GpuBuffer geometry = ctx.createBuffer(Math.max(MIN_BUFFER_BYTES, layout.totalBytes + 15L), usage,
                    true, "scene rebuilt geometry");
            layout = layout.shifted((-geometry.deviceAddress) & 15L);
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.positionOffset, input.positions.length)
                    .put(input.positions);
            MemoryUtil.memIntBuffer(geometry.mapped + layout.indexOffset, input.indices.length)
                    .put(input.indices);
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.textureCoordinateOffset, input.textureCoordinates.length)
                    .put(input.textureCoordinates);
            MemoryUtil.memFloatBuffer(geometry.mapped + layout.primitiveOffset, input.primitives.length)
                    .put(input.primitives);
            geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);
            RtAccel.PreparedBlas blas = RtAccel.prepareTransientBlas(ctx,
                    geometry.deviceAddress + layout.positionOffset, input.positions.length / 3,
                    geometry.deviceAddress + layout.indexOffset, input.classTriangles,
                    "scene rebuilt BLAS", true);
            int record = appendRecord(geometry.deviceAddress + layout.primitiveOffset,
                    geometry.deviceAddress + layout.indexOffset, geometry.deviceAddress + layout.textureCoordinateOffset,
                    motion.address, motion.rigidX, motion.rigidY, motion.rigidZ, input.triangleBase,
                    input.classTriangles, input.semanticFlags);
            appendInstance(transform, blas.accel.deviceAddress, record, mask);
            blasBuilds.add(blas);
            transientBlas.add(blas);
            transientBuffers.add(geometry);
        }

        /**
         * Replaces a keyed static resident. The key is source-owned identity only; allocation, BLAS
         * construction and old-resource retirement remain in this manager.
         */
        public void replaceCached(long key, PackedInput input) {
            if (input.buildClass != BuildClass.STATIC) {
                throw new IllegalArgumentException("cached resident requires STATIC build class");
            }
            DynamicResident old = cachedResidents.put(key, uploadDynamic(ctx, input, "scene cached " + key,
                    false));
            if (old != null) {
                ctx.gpuExecutor().retireAfterGraphics(old.graphicsUse, old::destroy);
            }
        }

        /** Appends one instance of a cached replacement resident to the manager-owned suffix. */
        public void appendCached(long key, MotionInput motion, float[] transform, int mask) {
            DynamicResident resident = cachedResidents.get(key);
            if (resident == null) throw new IllegalArgumentException("unknown cached resident " + key);
            appendResident(resident, motion, transform, mask);
        }

        /** Releases a cached resident against its exact final graphics use. */
        public void releaseCached(long key) {
            DynamicResident resident = cachedResidents.remove(key);
            if (resident != null) ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse, resident::destroy);
        }

        /**
         * Uploads a deforming resident into the manager-owned in-flight ring. Topology version is the
         * producer's declaration that indexed topology is unchanged; all refit policy and GPU state are
         * internal to the manager.
         */
        public DeformingReference appendDeforming(long key, PackedInput input, MotionInput motion,
                                                  float[] transform, int mask, boolean refitEnabled) {
            if (input.buildClass != BuildClass.DEFORMING) {
                throw new IllegalArgumentException("persistent animated resident requires DEFORMING build class");
            }
            DeformingResident owner = deformingResidents.computeIfAbsent(key, unused -> new DeformingResident());
            DynamicResident slot = owner.next(ctx);
            writeDynamic(ctx, slot, input);
            boolean sameTopology = deformingTopologyMatches(slot.topologyVersion, slot.vertexCount,
                    input.topologyVersion, input.positions.length / 3);
            RtAccel.RefitDecision decision = RtAccel.refitDecision(refitEnabled, slot.accel != null,
                    slot.updatable, sameTopology, slot.updatesSinceBuild, 120);
            if (decision == RtAccel.RefitDecision.REFIT) {
                if (slot.updateScratch == null || slot.updateScratch.size < slot.updateScratchSize) {
                    if (slot.updateScratch != null) slot.updateScratch.destroy();
                    slot.updateScratch = ctx.createAlignedBuffer(Math.max(MIN_BUFFER_BYTES, slot.updateScratchSize),
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, "scene deforming refit scratch",
                            ctx.accelerationStructureScratchAlignment());
                }
                RtAccel.PreparedBlas op = RtAccel.refitUpdate(slot.accel, slot.updateScratch,
                        slot.positionAddress, slot.indexAddress, input.positions.length / 3, input.classTriangles,
                        "scene deforming BLAS refit");
                blasBuilds.add(op);
                slot.updatesSinceBuild++;
            } else {
                slot.destroyAccel();
                if (!refitEnabled && slot.updateScratch != null) {
                    slot.updateScratch.destroy();
                    slot.updateScratch = null;
                }
                if (refitEnabled) {
                    RtAccel.UpdatableBuild build = RtAccel.prepareUpdatableBlasBuild(ctx, slot.positionAddress,
                            input.positions.length / 3, slot.indexAddress, input.classTriangles,
                            "scene deforming BLAS");
                    slot.accel = build.accel();
                    slot.backing = build.backing();
                    slot.updateScratchSize = build.updateScratchSize();
                    slot.updatable = true;
                    blasBuilds.add(build.op());
                    persistentBuilds.add(new PendingBuild(slot, build.op(), build.scratch()));
                } else {
                    RtAccel.PersistentBuild build = RtAccel.preparePersistentBlasBuild(ctx, slot.positionAddress,
                            input.positions.length / 3, slot.indexAddress, input.classTriangles,
                            "scene deforming BLAS");
                    slot.accel = build.accel();
                    slot.backing = build.backing();
                    slot.updateScratchSize = 0L;
                    slot.updatable = false;
                    blasBuilds.add(build.op());
                    persistentBuilds.add(new PendingBuild(slot, build.op(), build.scratch()));
                }
                slot.topologyVersion = input.topologyVersion;
                slot.vertexCount = input.positions.length / 3;
                slot.updatesSinceBuild = 0;
            }
            appendResident(slot, motion, transform, mask);
            return new DeformingReference(key, owner, slot, dynamicResidentGeneration);
        }

        /** Reuses a manager-owned animated resident without exposing its GPU handles. */
        public void appendDeformingReference(DeformingReference reference, MotionInput motion,
                                             float[] transform, int mask) {
            if (!deformingReferenceIsCurrent(reference.generation, dynamicResidentGeneration,
                    deformingResidents.get(reference.key) == reference.owner)) {
                throw new IllegalArgumentException("deforming resident reference is no longer live");
            }
            appendResident(reference.slot, motion, transform, mask);
        }

        /** Retires every ring slot for a stale animated identity. */
        public void releaseDeforming(long key) {
            DeformingResident resident = deformingResidents.remove(key);
            if (resident != null) resident.retire(ctx);
        }

        private void appendResident(DynamicResident resident, MotionInput motion, float[] transform, int mask) {
            if (resident.pendingBuild != null && !persistentBuilds.stream()
                    .anyMatch(build -> build.operation == resident.pendingBuild)) {
                blasBuilds.add(resident.pendingBuild);
                persistentBuilds.add(new PendingBuild(resident, resident.pendingBuild, resident.pendingScratch));
            }
            int record = appendRecord(resident.primitiveAddress, resident.indexAddress, resident.textureCoordinateAddress,
                    motion.address, motion.rigidX, motion.rigidY, motion.rigidZ, 0, resident.classTriangles,
                    resident.semanticFlags);
            appendInstance(transform, resident.accel.deviceAddress, record, mask);
            persistentUses.add(resident);
        }

        /** Retire one-frame rebuilt resources after the graphics submission that traces them. */
        private void markGraphicsUse(GraphicsUse graphicsUse) {
            if (transientBlas.isEmpty() && transientBuffers.isEmpty()
                    && persistentUses.isEmpty() && persistentBuilds.isEmpty()) {
                return;
            }
            List<RtAccel.PreparedBlas> blas = List.copyOf(transientBlas);
            List<GpuBuffer> buffers = List.copyOf(transientBuffers);
            transientBlas.clear();
            transientBuffers.clear();
            if (!blas.isEmpty() || !buffers.isEmpty()) {
                ctx.gpuExecutor().retireAfterGraphics(graphicsUse, () -> {
                    for (RtAccel.PreparedBlas build : blas) RtAccel.releaseTransientBlas(build);
                    for (GpuBuffer buffer : buffers) buffer.destroy();
                });
            }
            for (DynamicResident resident : persistentUses) resident.graphicsUse.mark(graphicsUse);
            for (PendingBuild build : persistentBuilds) {
                if (build.owner.pendingBuild == build.operation) {
                    build.owner.pendingBuild = null;
                    build.owner.pendingScratch = null;
                }
                ctx.gpuExecutor().retireAfterGraphics(graphicsUse, build.scratch::destroy);
            }
            persistentUses.clear();
            persistentBuilds.clear();
        }

        private void finish() {
            int records = RtGeometryAbi.checkedRecordCount(base.tablePrefix.recordCount(), count);
            if (records != 0) {
                base.use.table.buffer.flush(0L, (long) records * RtGeometryAbi.RECORD_BYTES);
            }
        }

        private void ensureDynamicCapacity(int records) {
            long required = Math.max(MIN_BUFFER_BYTES, (long) records * RtGeometryAbi.RECORD_BYTES);
            TableSlot table = base.use.table;
            if (table.buffer.size >= required) {
                return;
            }
            long grown = Math.max(required, table.buffer.size + table.buffer.size / 2L);
            GpuBuffer replacement = ctx.createBuffer(grown, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "scene geometry table");
            int existing = RtGeometryAbi.checkedRecordCount(base.tablePrefix.recordCount(), count);
            if (existing != 0) {
                MemoryUtil.memCopy(table.buffer.mapped, replacement.mapped,
                        (long) existing * RtGeometryAbi.RECORD_BYTES);
            }
            table.buffer.destroy();
            table.buffer = replacement;
        }
    }

    /** Opaque source-held reference to a specific manager-owned deforming ring slot. */
    public static final class DeformingReference {
        private final long key;
        private final DeformingResident owner;
        private final DynamicResident slot;
        private final long generation;

        private DeformingReference(long key, DeformingResident owner, DynamicResident slot, long generation) {
            this.key = key;
            this.owner = owner;
            this.slot = slot;
            this.generation = generation;
        }
    }

    static long nextDynamicResidentGeneration(long generation) {
        return Math.incrementExact(generation);
    }

    static boolean referenceIsCurrent(long referenceGeneration, long residentGeneration) {
        return referenceGeneration == residentGeneration;
    }

    static boolean deformingReferenceIsCurrent(long referenceGeneration, long residentGeneration,
                                                boolean ownerIsCurrent) {
        return ownerIsCurrent && referenceIsCurrent(referenceGeneration, residentGeneration);
    }

    static boolean deformingTopologyMatches(long previousVersion, int previousVertexCount,
                                            long incomingVersion, int incomingVertexCount) {
        return previousVersion == incomingVersion && previousVertexCount == incomingVertexCount;
    }

    /** Geometry-table address used only by renderer orchestration after the dynamic frame is finished. */
    public long geometryTableAddress(DynamicFrame frame) {
        return frame.base.use.table.buffer.deviceAddress;
    }

    private DynamicResident uploadDynamic(GpuContext ctx, PackedInput input, String label, boolean updatable) {
        DynamicResident resident = new DynamicResident();
        writeDynamic(ctx, resident, input);
        if (updatable) {
            RtAccel.UpdatableBuild build = RtAccel.prepareUpdatableBlasBuild(ctx, resident.positionAddress,
                    input.positions.length / 3, resident.indexAddress, input.classTriangles, label + " BLAS");
            resident.accel = build.accel();
            resident.backing = build.backing();
            resident.pendingBuild = build.op();
            resident.pendingScratch = build.scratch();
            resident.updateScratchSize = build.updateScratchSize();
            resident.updatable = true;
        } else {
            RtAccel.PersistentBuild build = RtAccel.preparePersistentBlasBuild(ctx, resident.positionAddress,
                    input.positions.length / 3, resident.indexAddress, input.classTriangles, label + " BLAS");
            resident.accel = build.accel();
            resident.backing = build.backing();
            resident.pendingBuild = build.op();
            resident.pendingScratch = build.scratch();
        }
        return resident;
    }

    private void writeDynamic(GpuContext ctx, DynamicResident resident, PackedInput input) {
        PackedLayout layout = PackedLayout.create(input.positions.length, input.indices.length,
                input.textureCoordinates.length, input.primitives.length);
        long required = Math.max(MIN_BUFFER_BYTES, layout.totalBytes + 15L);
        int usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (resident.geometry == null || resident.geometry.size < required) {
            GpuBuffer previous = resident.geometry;
            resident.geometry = ctx.createBuffer(required, usage, true, "scene dynamic geometry");
            if (previous != null) previous.destroy();
        }
        layout = layout.shifted((-resident.geometry.deviceAddress) & 15L);
        MemoryUtil.memFloatBuffer(resident.geometry.mapped + layout.positionOffset, input.positions.length).put(input.positions);
        MemoryUtil.memIntBuffer(resident.geometry.mapped + layout.indexOffset, input.indices.length).put(input.indices);
        MemoryUtil.memFloatBuffer(resident.geometry.mapped + layout.textureCoordinateOffset, input.textureCoordinates.length).put(input.textureCoordinates);
        MemoryUtil.memFloatBuffer(resident.geometry.mapped + layout.primitiveOffset, input.primitives.length).put(input.primitives);
        resident.geometry.flush(layout.positionOffset, layout.totalBytes - layout.positionOffset);
        resident.positionAddress = resident.geometry.deviceAddress + layout.positionOffset;
        resident.indexAddress = resident.geometry.deviceAddress + layout.indexOffset;
        resident.textureCoordinateAddress = resident.geometry.deviceAddress + layout.textureCoordinateOffset;
        resident.primitiveAddress = resident.geometry.deviceAddress + layout.primitiveOffset;
        resident.classTriangles = input.classTriangles.clone();
        resident.semanticFlags = input.semanticFlags;
    }

    /** Canonical packed data submitted by a source without exposing source-specific capture types. */
    public record PackedInput(float[] positions, int[] indices, float[] textureCoordinates, float[] primitives,
                              int[] classTriangles, int triangleBase, long topologyVersion,
                              BuildClass buildClass, int semanticFlags) {
        public PackedInput {
            if (positions.length == 0 || positions.length % 3 != 0 || indices.length == 0 || indices.length % 3 != 0) {
                throw new IllegalArgumentException("packed geometry must contain complete vertices and triangles");
            }
            if (classTriangles.length != RtAccel.SBT_CLASSES) {
                throw new IllegalArgumentException("packed geometry must provide every acceleration-structure class");
            }
            int vertexCount = positions.length / 3;
            int triangleCount = indices.length / 3;
            int classTriangleCount = 0;
            for (int count : classTriangles) {
                if (count < 0) throw new IllegalArgumentException("packed geometry class count must be non-negative");
                classTriangleCount = Math.addExact(classTriangleCount, count);
            }
            if (classTriangleCount != triangleCount) {
                throw new IllegalArgumentException("packed geometry class counts must cover every triangle");
            }
            if (textureCoordinates.length != vertexCount * 2) {
                throw new IllegalArgumentException("packed geometry must provide two texture coordinates per vertex");
            }
            if (primitives.length != triangleCount * 12) {
                throw new IllegalArgumentException("packed geometry must provide one primitive record per triangle");
            }
            for (int index : indices) {
                if (index < 0 || index >= vertexCount) {
                    throw new IllegalArgumentException("packed geometry index is outside its vertex range");
                }
            }
        }
    }

    /** Opaque motion input interpreted by the engine-owned geometry ABI. */
    public static final class MotionInput {
        private final long address;
        private final float rigidX;
        private final float rigidY;
        private final float rigidZ;

        private MotionInput(long address, float rigidX, float rigidY, float rigidZ) {
            this.address = address;
            this.rigidX = rigidX;
            this.rigidY = rigidY;
            this.rigidZ = rigidZ;
        }
    }

    private record PackedLayout(long positionOffset, long indexOffset, long textureCoordinateOffset,
                                long primitiveOffset, long totalBytes) {
        static PackedLayout create(int positionFloats, int indexInts, int textureCoordinateFloats, int primitiveFloats) {
            long positionBytes = (long) positionFloats * Float.BYTES;
            long indexOffset = align(positionBytes);
            long textureOffset = align(indexOffset + (long) indexInts * Integer.BYTES);
            long primitiveOffset = align(textureOffset + (long) textureCoordinateFloats * Float.BYTES);
            return new PackedLayout(0L, indexOffset, textureOffset, primitiveOffset,
                    align(primitiveOffset + (long) primitiveFloats * Float.BYTES));
        }

        PackedLayout shifted(long base) {
            return new PackedLayout(positionOffset + base, indexOffset + base, textureCoordinateOffset + base,
                    primitiveOffset + base, totalBytes + base);
        }

        private static long align(long value) {
            return (value + 15L) & -16L;
        }
    }

    private record MeshKey(ResourceId provider, long key) {
    }

    private record InstanceKey(ResourceId provider, long key) {
    }

    private record DesiredInstance(ResourceId provider, long meshKey, GeometryTransform transform) {
    }

    private record PendingBuild(DynamicResident owner, RtAccel.PreparedBlas operation, GpuBuffer scratch) {
    }

    /** One engine-owned GPU resident used by cached or deforming scene captures. */
    private static final class DynamicResident {
        GpuBuffer geometry;
        RtAccel accel;
        GpuBuffer backing;
        GpuBuffer updateScratch;
        long updateScratchSize;
        long positionAddress;
        long indexAddress;
        long textureCoordinateAddress;
        long primitiveAddress;
        int[] classTriangles;
        int semanticFlags;
        long topologyVersion = Long.MIN_VALUE;
        int vertexCount = -1;
        boolean updatable;
        int updatesSinceBuild;
        RtAccel.PreparedBlas pendingBuild;
        GpuBuffer pendingScratch;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        void destroyAccel() {
            if (accel != null) RtAccel.destroyCallerOwnedAccel(accel, backing);
            accel = null;
            backing = null;
        }

        void destroy() {
            destroyAccel();
            if (geometry != null) geometry.destroy();
            if (updateScratch != null) updateScratch.destroy();
            if (pendingScratch != null) pendingScratch.destroy();
            geometry = null;
            updateScratch = null;
            pendingScratch = null;
            pendingBuild = null;
        }
    }

    /** The manager's fixed in-flight ring for one animated identity. */
    private static final class DeformingResident {
        private static final int RING_SIZE = 4;
        final DynamicResident[] ring = new DynamicResident[RING_SIZE];
        int cursor;

        DynamicResident next(GpuContext ctx) {
            int index = cursor;
            cursor = (cursor + 1) % ring.length;
            DynamicResident slot = ring[index];
            if (slot == null) {
                slot = new DynamicResident();
                ring[index] = slot;
            } else {
                ctx.gpuExecutor().graphicsUseWaiter().await(slot.graphicsUse);
            }
            return slot;
        }

        void retire(GpuContext ctx) {
            for (DynamicResident slot : ring) {
                if (slot != null) ctx.gpuExecutor().retireAfterGraphics(slot.graphicsUse, slot::destroy);
            }
        }

        void destroy() {
            for (DynamicResident slot : ring) if (slot != null) slot.destroy();
        }
    }

    public static final class ResidentMesh {
        final TriangleMesh source;
        final RtGeometryMeshPacking.PackedMesh packed;
        final GpuBuffer positions;
        final GpuBuffer indices;
        final GpuBuffer texCoords;
        final GpuBuffer primitives;
        final RtAccel accel;
        final GpuBuffer backing;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();
        final long materialEpoch;
        RtAccel.PreparedBlas pendingBuild;
        GpuBuffer scratch;

        ResidentMesh(TriangleMesh source, RtGeometryMeshPacking.PackedMesh packed,
                     GpuBuffer positions, GpuBuffer indices, GpuBuffer texCoords, GpuBuffer primitives,
                     RtAccel accel, GpuBuffer backing, RtAccel.PreparedBlas pendingBuild, GpuBuffer scratch,
                     long materialEpoch) {
            this.source = source;
            this.packed = packed;
            this.positions = positions;
            this.indices = indices;
            this.texCoords = texCoords;
            this.primitives = primitives;
            this.accel = accel;
            this.backing = backing;
            this.pendingBuild = pendingBuild;
            this.scratch = scratch;
            this.materialEpoch = materialEpoch;
        }

        void destroy() {
            RtAccel.destroyCallerOwnedAccel(accel, backing);
            positions.destroy();
            indices.destroy();
            texCoords.destroy();
            primitives.destroy();
            if (scratch != null) {
                scratch.destroy();
            }
        }
    }

    public static final class TableSlot {
        GpuBuffer buffer;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        TableSlot(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
