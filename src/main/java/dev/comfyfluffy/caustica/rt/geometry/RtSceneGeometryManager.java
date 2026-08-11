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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Owns retained provider mesh uploads, BLASes, geometry records, and their exact graphics lifetime.
 * Caller-supplied retained geometry enters as a prefix until it is migrated to the same CPU mesh API.
 */
public final class RtSceneGeometryManager {
    private static final int TABLE_RING = 4;
    private static final long MIN_BUFFER_BYTES = 256L;

    private final RtGeometryMaterialResolver materialResolver;
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();
    private final Map<MeshKey, ResidentMesh> residents = new LinkedHashMap<>();
    private final TableSlot[] tables = new TableSlot[TABLE_RING];
    private int tableCursor;
    private long materialEpoch;

    public RtSceneGeometryManager(RtGeometryMaterialResolver materialResolver) {
        this.materialResolver = materialResolver;
    }

    public Capture beginCapture() {
        return new Capture();
    }

    public void invalidateMaterials() {
        materialEpoch++;
    }

    public FrameGeometry finishFrame(GpuContext ctx, Capture capture,
                                     List<RtAccel.Instance> retainedInstances,
                                     RtGeometryAbi.TablePrefix retainedTable, SceneOrigin origin) {
        LinkedHashMap<MeshKey, Integer> recordIndices = new LinkedHashMap<>();
        for (DesiredInstance instance : capture.instances.values()) {
            MeshKey meshKey = new MeshKey(instance.provider, instance.meshKey);
            if (!capture.meshes.containsKey(meshKey)) {
                throw new IllegalArgumentException("geometry instance references an unsubmitted mesh " + meshKey);
            }
            recordIndices.putIfAbsent(meshKey, retainedTable.recordCount() + recordIndices.size());
        }
        reconcile(ctx, capture.meshes, recordIndices.keySet());
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        List<BuildUse> buildUses = new ArrayList<>();
        for (MeshKey key : recordIndices.keySet()) {
            ResidentMesh resident = residents.get(key);
            if (resident.pendingBuild != null) {
                builds.add(resident.pendingBuild);
                buildUses.add(new BuildUse(resident, resident.pendingBuild, resident.scratch));
            }
        }
        int recordCount = RtGeometryAbi.checkedRecordCount(retainedTable.recordCount(), recordIndices.size());
        TableSlot table = selectTable(ctx, recordCount);
        long retainedBytes = (long) retainedTable.recordCount() * RtGeometryAbi.RECORD_BYTES;
        if (retainedBytes != 0L) {
            MemoryUtil.memCopy(retainedTable.mappedAddress(), table.buffer.mapped, retainedBytes);
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

        List<RtAccel.Instance> instances = new ArrayList<>(retainedInstances.size() + capture.instances.size());
        instances.addAll(retainedInstances);
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

    /** Build-ready TLAS view over the generic scene plus per-frame dynamic instances. */
    public RtAccel.PreparedTlas prepareTlas(GpuContext ctx, FrameGeometry frame,
                                            List<RtAccel.Instance> dynamicInstances, GraphicsUse graphicsUse) {
        return RtAccel.prepareTlas(ctx, frame.instances, dynamicInstances, tlasRing, graphicsUse);
    }

    /** Teardown after the device is idle. */
    public void shutdown() {
        tlasRing.destroy();
        for (ResidentMesh mesh : residents.values()) {
            mesh.destroy();
        }
        residents.clear();
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) {
                tables[i].buffer.destroy();
                tables[i] = null;
            }
        }
    }

    private void reconcile(GpuContext ctx, Map<MeshKey, TriangleMesh> desired, java.util.Set<MeshKey> used) {
        var iterator = residents.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MeshKey, ResidentMesh> entry = iterator.next();
            TriangleMesh mesh = desired.get(entry.getKey());
            ResidentMesh resident = entry.getValue();
            if (mesh == null || !mesh.equals(resident.source) || resident.materialEpoch != materialEpoch) {
                ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse, resident::destroy);
                iterator.remove();
            }
        }
        for (MeshKey key : used) {
            if (!residents.containsKey(key)) {
                residents.put(key, upload(ctx, key, desired.get(key)));
            }
        }
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
        private final Map<MeshKey, TriangleMesh> meshes = new LinkedHashMap<>();
        private final Map<InstanceKey, DesiredInstance> instances = new LinkedHashMap<>();

        public SceneGeometrySink sink(ResourceId provider) {
            return new SceneGeometrySink() {
                @Override
                public void retainMesh(long key, TriangleMesh mesh) {
                    MeshKey scoped = new MeshKey(provider, key);
                    if (meshes.putIfAbsent(scoped, mesh) != null) {
                        throw new IllegalArgumentException("duplicate retained mesh key " + scoped);
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

    private record MeshKey(ResourceId provider, long key) {
    }

    private record InstanceKey(ResourceId provider, long key) {
    }

    private record DesiredInstance(ResourceId provider, long meshKey, GeometryTransform transform) {
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
        final GpuBuffer buffer;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        TableSlot(GpuBuffer buffer) {
            this.buffer = buffer;
        }
    }
}
