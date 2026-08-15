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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * Owns retained provider mesh uploads, BLASes, geometry records, and their exact graphics lifetime.
 * <p>Residency is explicit: a mesh stays uploaded across frames once retained, until it is released,
 * replaced by a new {@code retainMesh} call, or its owning provider stops. Nothing here compares mesh
 * bytes frame to frame.
 */
public final class RtSceneGeometryManager {
    private static final int TABLE_RING = 4;
    private static final long MIN_BUFFER_BYTES = 256L;
    private static final int HISTORY_BYTES = 3 * 4 * Float.BYTES;

    /** Immutable build policy selected by the producer when it submits a mesh update. */
    public enum BuildClass {
        STATIC,
        DEFORMING,
        REBUILT
    }

    private final RtGeometryMaterialResolver materialResolver;
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();
    private final Map<MeshKey, ResidentMesh> residents = new LinkedHashMap<>();
    private final GroupScheduler groupScheduler = new GroupScheduler();
    private final ConcurrentLinkedQueue<TerminalGroup> terminalGroups = new ConcurrentLinkedQueue<>();
    private final Map<InstanceKey, InstanceState> instanceStates = new HashMap<>();
    private final FailureLatch groupFailures = new FailureLatch();
    private final TableSlot[] tables = new TableSlot[TABLE_RING];
    private int tableCursor;
    private long materialEpoch;

    public RtSceneGeometryManager(RtGeometryMaterialResolver materialResolver) {
        this.materialResolver = materialResolver;
    }

    /** Stable owner identity for one independently published retained-geometry group. */
    public record GroupKey(ResourceId source, long key) { }

    /** Engine input selected by the manager's preparation path. */
    public sealed interface GeometryPayload permits IndexedPayload, RetainedPayload { }
    /** Indexed dynamic geometry. Its arrays must remain unchanged after submission. */
    public record IndexedPayload(PackedInput input) implements GeometryPayload { }
    /** Retained packed geometry with optional opacity-micromap build input. */
    public record RetainedPayload(RtPackedGeometry<?> geometry, RtAccel.OpacityMicromapInput opacityInput,
                                  boolean compactBlas, int semanticFlags) implements GeometryPayload {
        public RetainedPayload(RtPackedGeometry<?> geometry, RtAccel.OpacityMicromapInput opacityInput,
                               boolean compactBlas) {
            this(geometry, opacityInput, compactBlas, 0);
        }
    }

    /** Immutable operation belonging to one atomic geometry group. */
    public sealed interface GeometryOperation permits Put, Drop, Place, Remove { }
    public record Put(long residentKey, GeometryPayload payload) implements GeometryOperation { }
    public record Drop(long residentKey) implements GeometryOperation { }
    public record Place(long instanceKey, long residentKey, float[] transform, int mask, SceneOrigin origin)
            implements GeometryOperation {
        public Place(long instanceKey, long residentKey, float[] transform, int mask) {
            this(instanceKey, residentKey, transform, mask, SceneOrigin.ZERO);
        }
        public Place { transform = transform.clone(); }
        @Override public float[] transform() { return transform.clone(); }
    }
    public record Remove(long instanceKey) implements GeometryOperation { }

    /** A sealed source submission. A group either replaces its whole published snapshot or remains unchanged. */
    public record GeometryUpdateGroup(GroupKey key, long revision, List<GeometryOperation> operations) {
        public GeometryUpdateGroup { operations = List.copyOf(operations); }
    }

    /** Accept independent source groups; a new revision replaces a queued but not running revision. */
    public void submit(List<GeometryUpdateGroup> updates) {
        submit(updates, null);
    }

    /** One completed atomic group publication, including the exact final operations applied to the global maps. */
    public record PublicationAck(GroupKey key, long revision, List<GeometryOperation> operations) { }

    /** Optionally receives one render-thread acknowledgment after each successfully applied atomic barrier. */
    public void submit(List<GeometryUpdateGroup> updates, Consumer<PublicationAck> acknowledgment) {
        for (GeometryUpdateGroup update : updates) groupScheduler.submit(update, acknowledgment);
    }

    /**
     * Removes one source's published snapshot and cancels all of its pending candidate groups.
     * Candidate allocations, and any BLAS read by a cancelled update, remain owned by its terminal
     * completion and are retired only after that completion.
     */
    public void clearSource(GpuContext ctx, ResourceId source) {
        Set<GroupResident> retired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        retired.addAll(groupScheduler.clearSource(source));
        instanceStates.entrySet().removeIf(entry -> entry.getKey().provider.equals(source));
        for (GroupResident resident : retired) {
            ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse(), resident::destroy);
        }
    }

    public Capture beginCapture() {
        return new Capture();
    }

    public void invalidateMaterials() {
        materialEpoch++;
    }

    public FrameGeometry finishFrame(GpuContext ctx, Capture capture, SceneOrigin origin,
                                     Set<ResourceId> liveProviders) {
        reconcile(ctx, capture, liveProviders);
        LinkedHashMap<InstanceKey, Integer> recordIndices = new LinkedHashMap<>();
        for (DesiredInstance instance : capture.instances.values()) {
            MeshKey meshKey = new MeshKey(instance.provider, instance.meshKey);
            if (!residents.containsKey(meshKey)) {
                throw new IllegalArgumentException("geometry instance references an unretained mesh " + meshKey);
            }
            recordIndices.put(instance.key, recordIndices.size());
        }
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        List<BuildUse> buildUses = new ArrayList<>();
        List<ResidentMesh> pendingBuildResidents = new ArrayList<>();
        for (DesiredInstance instance : capture.instances.values()) {
            ResidentMesh resident = residents.get(new MeshKey(instance.provider, instance.meshKey));
            if (resident.pendingBuild != null) {
                pendingBuildResidents.add(resident);
            }
        }
        for (ResidentMesh resident : uniqueByIdentity(pendingBuildResidents)) {
            builds.add(resident.pendingBuild);
            buildUses.add(new BuildUse(resident, resident.pendingBuild, resident.scratch));
        }
        int recordCount = RtGeometryAbi.checkedRecordCount(0, recordIndices.size());
        TableSlot table = selectTable(ctx, recordCount);
        for (DesiredInstance instance : capture.instances.values()) {
            ResidentMesh resident = residents.get(new MeshKey(instance.provider, instance.meshKey));
            int[] classes = resident.packed.classTris();
            long address = table.buffer.mapped + (long) recordIndices.get(instance.key) * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, resident.primitives.deviceAddress, resident.indices.deviceAddress,
                    resident.texCoords.deviceAddress, 0L,
                    0, classes[0], classes[0] + classes[1],
                    RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES);
        }
        if (recordCount != 0) {
            table.buffer.flush(0L, (long) recordCount * RtGeometryAbi.RECORD_BYTES);
        }

        List<RtAccel.Instance> instances = new ArrayList<>(capture.instances.size());
        List<ResidentMesh> usedMeshes = new ArrayList<>(recordIndices.size());
        for (DesiredInstance instance : capture.instances.values()) {
            usedMeshes.add(residents.get(new MeshKey(instance.provider, instance.meshKey)));
        }
        for (DesiredInstance instance : capture.instances.values()) {
            MeshKey meshKey = new MeshKey(instance.provider, instance.meshKey);
            ResidentMesh resident = residents.get(meshKey);
            float[] transform = instance.transform.relativeTo(origin.x(), origin.y(), origin.z());
            instances.add(new RtAccel.Instance(transform, resident.accel.deviceAddress,
                    recordIndices.get(instance.key)));
        }
        for (DesiredInstance instance : capture.instances.values()) {
            int record = recordIndices.get(instance.key);
            RtAccel.Instance placed = instances.stream().filter(candidate -> candidate.customIndex() == record)
                    .findFirst().orElseThrow();
            writeHistory(table, record, previousTransform(instance.key, placed.transform3x4(), origin));
            instanceStates.put(instance.key, new InstanceState(placed.transform3x4().clone(), origin));
        }
        if (recordCount != 0) {
            table.history.flush(0L, (long) recordCount * HISTORY_BYTES);
        }
        return new FrameGeometry(List.copyOf(instances), new RtGeometryAbi.TablePrefix(table.buffer.mapped, recordCount), origin,
                List.copyOf(builds), new FrameUse(table, List.copyOf(usedMeshes), List.copyOf(buildUses)));
    }

    static <T> List<T> uniqueByIdentity(Iterable<T> values) {
        Set<T> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ArrayList<T> result = new ArrayList<>();
        for (T value : values) {
            if (seen.add(value)) result.add(value);
        }
        return result;
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
    public boolean recordBlasBuilds(GpuContext ctx, VkCommandBuffer commandBuffer, FrameGeometry frame) {
        if (frame.blasBuilds.isEmpty()) return false;
        RtAccel.recordBlasBuilds(ctx, commandBuffer, frame.blasBuilds);
        return true;
    }

    /** Build-ready TLAS view over every manager-owned instance segment. */
    public RtAccel.PreparedTlas prepareTlas(GpuContext ctx, FrameGeometry frame,
                                            FrameUpdate update, GraphicsUse graphicsUse) {
        return RtAccel.prepareTlas(ctx, frame.instances, update.instances, tlasRing, graphicsUse);
    }

    /**
     * Advances retained geometry independently of frame assembly so startup can publish terrain before
     * the first RT frame is eligible to trace it.
     */
    public void progress(GpuContext ctx) {
        publishTerminalGroups(ctx);
        groupFailures.throwIfPresent();
        startGroupCandidates(ctx);
    }

    /** Publishes completed group barriers and appends the resulting retained scene after provider geometry. */
    public FrameUpdate beginUpdate(GpuContext ctx, FrameGeometry frame) {
        progress(ctx);
        FrameUpdate update = new FrameUpdate(ctx, frame);
        appendPublishedGroups(update);
        update.finish();
        return update;
    }

    /** Associates all resources published by an update with the graphics submission that traces them. */
    public void markGraphicsUse(FrameUpdate update, GraphicsUse graphicsUse) {
        update.markGraphicsUse(graphicsUse);
    }

    /** Teardown after the device is idle. */
    public void shutdown() {
        Set<PreparedGroup> destroyedGroups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<GroupResident> destroyedCancelledSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        TerminalGroup terminal;
        while ((terminal = terminalGroups.poll()) != null) {
            terminal.applyRetainedCompletions();
            destroyPreparedGroupAfterDeviceIdle(terminal.prepared, destroyedGroups);
            destroyCancelledSourcesAfterDeviceIdle(terminal.prepared, destroyedCancelledSources);
        }
        groupScheduler.destroyAfterDeviceIdle(destroyedGroups, (prepared, destroyed) -> {
            destroyPreparedGroupAfterDeviceIdle(prepared, destroyed);
            destroyCancelledSourcesAfterDeviceIdle(prepared, destroyedCancelledSources);
        });
        tlasRing.destroy();
        for (ResidentMesh mesh : residents.values()) {
            mesh.destroy();
        }
        residents.clear();
        instanceStates.clear();
        for (int i = 0; i < tables.length; i++) {
            if (tables[i] != null) {
                tables[i].buffer.destroy();
                tables[i].history.destroy();
                tables[i] = null;
            }
        }
    }

    private void destroyPreparedGroupAfterDeviceIdle(PreparedGroup prepared, Set<PreparedGroup> destroyed) {
        if (!destroyed.add(prepared)) return;
        for (GroupCandidate candidate : prepared.candidates) candidate.destroyAfterDeviceIdle();
    }

    private void destroyCancelledSourcesAfterDeviceIdle(PreparedGroup prepared, Set<GroupResident> destroyed) {
        for (GroupCandidate candidate : prepared.candidates) {
            if (candidate.retireSourceAfterTerminal && destroyed.add(candidate.source)) {
                candidate.source.destroy();
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

    private void startGroupCandidates(GpuContext ctx) {
        for (GroupRun run : groupScheduler.startable()) {
            PreparedGroup prepared = run.prepared;
            try {
                prepareGroupCandidates(ctx, prepared);
            } catch (Throwable failure) {
                terminalGroups.add(new TerminalGroup(run.key, prepared, failure, null));
            }
        }
    }

    private void prepareGroupCandidates(GpuContext ctx, PreparedGroup prepared) {
        ArrayList<GroupCandidate> candidates = new ArrayList<>();
        try {
        for (Map.Entry<Long, GeometryPayload> entry : prepared.diff.puts.entrySet()) {
            GroupResident source = groupScheduler.publishedResident(new ResidentId(prepared.key.source(), entry.getKey()));
            if (entry.getValue() instanceof RetainedPayload retained) {
                RetainedGroupResident candidate = new RetainedGroupResident(RtRetainedGeometryBuilds.prepare(ctx,
                        retained.geometry(), retained.opacityInput(), retained.compactBlas(), entry.getKey(),
                        0, 0, 0), retained.semanticFlags());
                candidates.add(new GroupCandidate(entry.getKey(), candidate, source, null, true));
                continue;
            }
            IndexedPayload indexed = (IndexedPayload) entry.getValue();
            DynamicResident candidate = new DynamicResident();
            PackedInput input = indexed.input();
            writeDynamic(ctx, candidate, input);
            candidate.topologyVersion = input.topologyVersion;
            candidate.vertexCount = input.positions.length / 3;
            RtAccel.PreparedBlas operation;
            DynamicResident dynamicSource = source instanceof DynamicResident resident ? resident : null;
            boolean previousIndexed = dynamicSource != null;
            long previousTopologyVersion = previousIndexed ? dynamicSource.topologyVersion : Long.MIN_VALUE;
            int previousVertexCount = previousIndexed ? dynamicSource.vertexCount : -1;
            boolean retainPreviousPositions = indexedMotionCompatible(input.buildClass(), previousIndexed,
                    previousTopologyVersion, previousVertexCount,
                    candidate.topologyVersion, candidate.vertexCount);
            if (input.buildClass() == BuildClass.DEFORMING && dynamicSource != null && dynamicSource.updatable
                    && dynamicSource.updatesSinceBuild < 120
                    && retainPreviousPositions) {
                RtAccel.UpdatableBuild update = RtAccel.prepareOutOfPlaceUpdate(ctx, dynamicSource.accel,
                        candidate.positionAddress, candidate.vertexCount, candidate.indexAddress,
                        candidate.classTriangles, "scene group BLAS update");
                candidate.accel = update.accel();
                candidate.backing = update.backing();
                candidate.updatable = true;
                candidate.updatesSinceBuild = dynamicSource.updatesSinceBuild + 1;
                operation = update.op();
            } else if (input.buildClass() == BuildClass.DEFORMING) {
                RtAccel.UpdatableBuild build = RtAccel.prepareUpdatableBlasBuild(ctx, candidate.positionAddress,
                        candidate.vertexCount, candidate.indexAddress, candidate.classTriangles, "scene group BLAS");
                candidate.accel = build.accel();
                candidate.backing = build.backing();
                candidate.updatable = true;
                operation = build.op();
            } else if (input.buildClass() == BuildClass.STATIC) {
                RtAccel.PersistentBuild build = RtAccel.preparePersistentBlasBuild(ctx, candidate.positionAddress,
                        candidate.vertexCount, candidate.indexAddress, candidate.classTriangles, "scene group BLAS");
                candidate.accel = build.accel();
                candidate.backing = build.backing();
                operation = build.op();
            } else {
                RtAccel.PreparedBlas build = RtAccel.prepareTransientBlas(ctx, candidate.positionAddress,
                        candidate.vertexCount, candidate.indexAddress, candidate.classTriangles,
                        "scene group rebuilt BLAS", true);
                candidate.accel = build.accel;
                candidate.backing = build.externalBacking();
                operation = build;
            }
            candidates.add(new GroupCandidate(entry.getKey(), candidate, source, operation, !retainPreviousPositions));
        }
        } catch (Throwable failure) {
            for (GroupCandidate candidate : candidates) {
                candidate.releaseUnsubmittedScratch();
                candidate.destroyUnpublished(ctx);
            }
            throw failure;
        }
        prepared.candidates = List.copyOf(candidates);
        if (candidates.isEmpty()) {
            terminalGroups.add(new TerminalGroup(prepared.key, prepared, null, null));
            return;
        }
        AtomicInteger remaining = new AtomicInteger(candidates.size());
        List<CandidateTerminal> terminals = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        for (GroupCandidate candidate : candidates) {
            candidate.submit(ctx, () -> prepared.barrier.cancelled, terminal -> {
                terminals.add(terminal);
                if (terminal.failure != null) failure.compareAndSet(null, terminal.failure);
                if (remaining.decrementAndGet() == 0) {
                    terminalGroups.add(new TerminalGroup(prepared.key, prepared, failure.get(), List.copyOf(terminals)));
                }
            });
        }
    }

    private void publishTerminalGroups(GpuContext ctx) {
        TerminalGroup terminal;
        while ((terminal = terminalGroups.poll()) != null) {
            terminal.applyRetainedCompletions();
            boolean cancelled = groupScheduler.cancelled(terminal.prepared.barrier);
            if (!groupScheduler.running(terminal.prepared.barrier) || cancelled || terminal.failure != null) {
                terminal.prepared.destroyCandidates(ctx);
                retireCancelledSources(ctx, terminal.prepared);
                groupScheduler.complete(terminal.prepared.barrier, false);
                if (!cancelled && terminal.failure != null) groupFailures.record(terminal.failure);
                continue;
            }
            for (CandidateTerminal candidate : terminal.candidates) {
                if (candidate.build != null) ctx.gpuExecutor().markPublished(candidate.build);
            }
            for (GroupCandidate candidate : terminal.prepared.candidates) {
                ResidentId residentId = new ResidentId(terminal.prepared.key.source(), candidate.key);
                GroupResident previous = groupScheduler.putPublishedResident(residentId, candidate.resident);
                if (previous != null && !candidate.resetMotion) {
                    GroupResident superseded = groupScheduler.putPreviousResident(residentId, previous);
                    if (superseded != null) ctx.gpuExecutor().retireAfterGraphics(superseded.graphicsUse(), superseded::destroy);
                }
                else if (previous != null) ctx.gpuExecutor().retireAfterGraphics(previous.graphicsUse(), previous::destroy);
            }
            for (long drop : terminal.prepared.diff.drops) {
                GroupResident previous = groupScheduler.removePublishedResident(
                        new ResidentId(terminal.prepared.key.source(), drop));
                if (previous != null) ctx.gpuExecutor().retireAfterGraphics(previous.graphicsUse(), previous::destroy);
            }
            for (Map.Entry<Long, Placement> placement : terminal.prepared.diff.placements.entrySet()) {
                groupScheduler.putPublishedPlacement(new InstanceId(terminal.prepared.key.source(), placement.getKey()),
                        placement.getValue());
            }
            for (long remove : terminal.prepared.diff.removes) {
                groupScheduler.removePublishedPlacement(new InstanceId(terminal.prepared.key.source(), remove));
                instanceStates.remove(new InstanceKey(terminal.prepared.key.source(), remove));
            }
            groupScheduler.complete(terminal.prepared.barrier, true);
            if (terminal.prepared.barrier.acknowledgment != null) {
                terminal.prepared.barrier.acknowledgment.accept(new PublicationAck(terminal.prepared.key,
                        terminal.prepared.revision, terminal.prepared.barrier.operations()));
            }
        }
    }

    private void appendPublishedGroups(FrameUpdate update) {
        for (Map.Entry<InstanceId, Placement> placementEntry : groupScheduler.publishedPlacements()) {
            Placement placement = placementEntry.getValue();
            InstanceId instanceId = placementEntry.getKey();
            ResidentId residentId = new ResidentId(instanceId.source, placement.residentKey);
            GroupResident resident = groupScheduler.publishedResident(residentId);
            if (resident == null) continue;
            GroupResident previous = groupScheduler.previousResident(residentId);
            resident.append(update, placement.transformFor(update.base.origin), placement.mask,
                    new InstanceKey(instanceId.source, instanceId.key), previous, previous == null);
            if (previous != null) update.historyUses.add(previous);
        }
        for (GroupResident previous : groupScheduler.previousResidents()) update.historyRetire.add(previous);
        groupScheduler.clearPreviousResidents();
    }

    private void retireCancelledSources(GpuContext ctx, PreparedGroup prepared) {
        Set<GroupResident> retired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (GroupCandidate candidate : prepared.candidates) {
            if (candidate.retireSourceAfterTerminal && retired.add(candidate.source)) {
                ctx.gpuExecutor().retireAfterGraphics(candidate.source.graphicsUse(), candidate.source::destroy);
            }
        }
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
                    "scene geometry table"), ctx.createBuffer(Math.max(MIN_BUFFER_BYTES,
                    (bytes / RtGeometryAbi.RECORD_BYTES) * HISTORY_BYTES), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true, "scene instance history"));
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
                    if (instances.putIfAbsent(scoped, new DesiredInstance(scoped, provider, meshKey, transform)) != null) {
                        throw new IllegalArgumentException("duplicate geometry instance key " + scoped);
                    }
                }
            };
        }
    }

    public record FrameGeometry(List<RtAccel.Instance> instances, RtGeometryAbi.TablePrefix tablePrefix, SceneOrigin origin,
                                List<RtAccel.PreparedBlas> blasBuilds, FrameUse use) {
    }

    public record FrameUse(TableSlot table, List<ResidentMesh> meshes, List<BuildUse> builds) {
    }

    public record BuildUse(ResidentMesh mesh, RtAccel.PreparedBlas operation, GpuBuffer scratch) {
    }

    /**
     * Engine-owned geometry segment appended after provider records. Group residents supply every record
     * and instance; allocation, indexing, flushing, and table lifetime remain private to the manager.
     */
    public final class FrameUpdate {
        private final GpuContext ctx;
        private final FrameGeometry base;
        private final ArrayList<RtAccel.Instance> instances = new ArrayList<>();
        private final ArrayList<GroupResident> persistentUses = new ArrayList<>();
        private final Set<GroupResident> historyUses = new LinkedHashSet<>();
        private final Set<GroupResident> historyRetire = new LinkedHashSet<>();
        private int count;

        private FrameUpdate(GpuContext ctx, FrameGeometry base) {
            this.ctx = ctx;
            this.base = base;
        }

        private int appendRecord(long primitiveAddress, long indexAddress, long textureCoordinateAddress,
                                 long previousPositionAddress,
                                 int triangleBase, int[] classTriangles, int semanticFlags) {
            if (classTriangles == null || classTriangles.length != RtAccel.SBT_CLASSES) {
                throw new IllegalArgumentException("missing acceleration-structure class counts");
            }
            return appendRawRecord(primitiveAddress, indexAddress, textureCoordinateAddress, previousPositionAddress,
                    triangleBase, classTriangles[0], triangleBase + classTriangles[0] + classTriangles[1],
                    semanticFlags);
        }

        private int appendRawRecord(long primitiveAddress, long indexAddress, long textureCoordinateAddress,
                                    long previousPositionAddress, int triangleBase0, int triangleBase1,
                                    int triangleBase2, int semanticFlags) {
            int record = RtGeometryAbi.checkedIndex(base.tablePrefix.recordCount(), count);
            ensureDynamicCapacity(record + 1);
            long address = base.use.table.buffer.mapped + (long) record * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, primitiveAddress, indexAddress, textureCoordinateAddress,
                    previousPositionAddress, triangleBase0, triangleBase1, triangleBase2, semanticFlags);
            count++;
            return record;
        }

        private void appendInstance(float[] transform, long accelAddress, int record, int mask, InstanceKey key,
                                    boolean resetMotion) {
            instances.add(new RtAccel.Instance(transform, accelAddress, record, mask));
            writeHistory(base.use.table, record, resetMotion ? transform : previousTransform(key, transform, base.origin));
            if (key != null) instanceStates.put(key, new InstanceState(transform.clone(), base.origin));
        }

        private void appendResident(DynamicResident resident, float[] transform, int mask, InstanceKey key,
                                    long previousPositionAddress, boolean resetMotion) {
            int record = appendRecord(resident.primitiveAddress, resident.indexAddress, resident.textureCoordinateAddress,
                    previousPositionAddress, 0, resident.classTriangles,
                    resident.semanticFlags);
            appendInstance(transform, resident.accel.deviceAddress, record, mask, key, resetMotion);
            persistentUses.add(resident);
        }

        private void markGraphicsUse(GraphicsUse graphicsUse) {
            if (persistentUses.isEmpty() && historyUses.isEmpty() && historyRetire.isEmpty()) {
                return;
            }
            for (GroupResident resident : persistentUses) resident.graphicsUse().mark(graphicsUse);
            for (GroupResident resident : historyUses) resident.graphicsUse().mark(graphicsUse);
            for (GroupResident resident : historyRetire) {
                ctx.gpuExecutor().retireAfterGraphics(graphicsUse, resident::destroy);
            }
            persistentUses.clear();
            historyUses.clear();
            historyRetire.clear();
        }

        private void finish() {
            int records = RtGeometryAbi.checkedRecordCount(base.tablePrefix.recordCount(), count);
            if (records != 0) {
                base.use.table.buffer.flush(0L, (long) records * RtGeometryAbi.RECORD_BYTES);
                base.use.table.history.flush(0L, (long) records * HISTORY_BYTES);
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
            GpuBuffer historyReplacement = ctx.createBuffer(Math.max(MIN_BUFFER_BYTES,
                    (grown / RtGeometryAbi.RECORD_BYTES) * HISTORY_BYTES), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true, "scene instance history");
            int existing = RtGeometryAbi.checkedRecordCount(base.tablePrefix.recordCount(), count);
            if (existing != 0) {
                MemoryUtil.memCopy(table.buffer.mapped, replacement.mapped,
                        (long) existing * RtGeometryAbi.RECORD_BYTES);
                MemoryUtil.memCopy(table.history.mapped, historyReplacement.mapped,
                        (long) existing * HISTORY_BYTES);
            }
            table.buffer.destroy();
            table.history.destroy();
            table.buffer = replacement;
            table.history = historyReplacement;
        }
    }

    static boolean deformingTopologyMatches(long previousVersion, int previousVertexCount,
                                            long incomingVersion, int incomingVertexCount) {
        return previousVersion == incomingVersion && previousVertexCount == incomingVertexCount;
    }

    static boolean indexedMotionCompatible(BuildClass buildClass, boolean previousIndexed,
                                           long previousVersion, int previousVertexCount,
                                           long incomingVersion, int incomingVertexCount) {
        return buildClass != BuildClass.REBUILT && previousIndexed
                && deformingTopologyMatches(previousVersion, previousVertexCount,
                incomingVersion, incomingVertexCount);
    }

    /** Geometry-table address used only by renderer orchestration after the frame update is finished. */
    public long geometryTableAddress(FrameUpdate frame) {
        return frame.base.use.table.buffer.deviceAddress;
    }

    /** Per-instance transform history table used by hit shaders alongside the geometry table. */
    public long instanceHistoryAddress(FrameUpdate frame) {
        return frame.base.use.table.history.deviceAddress;
    }

    private static void writeHistory(TableSlot table, int index, float[] transform) {
        long address = table.history.mapped + (long) index * HISTORY_BYTES;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 4; column++) {
                MemoryUtil.memPutFloat(address + (long) (row * 4 + column) * Float.BYTES,
                        transform[row * 4 + column]);
            }
        }
    }

    private float[] previousTransform(InstanceKey key, float[] current, SceneOrigin currentOrigin) {
        if (key == null) return current;
        InstanceState previous = instanceStates.get(key);
        if (previous == null) return current;
        float[] transform = previous.transform.clone();
        transform[3] += (float) (previous.origin.x() - currentOrigin.x());
        transform[7] += (float) (previous.origin.y() - currentOrigin.y());
        transform[11] += (float) (previous.origin.z() - currentOrigin.z());
        return transform;
    }

    private void writeDynamic(GpuContext ctx, DynamicResident resident, PackedInput input) {
        PackedLayout layout = PackedLayout.create(input.positions.length, input.indices.length,
                input.textureCoordinates.length, input.primitives.length);
        long required = Math.max(MIN_BUFFER_BYTES, layout.totalBytes + 15L);
        int usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (resident.geometry == null || resident.geometry.size < required) {
            GpuBuffer previous = resident.geometry;
            resident.geometry = ctx.createAsyncBuffer(required, usage, true, "scene dynamic geometry");
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

    private record InstanceState(float[] transform, SceneOrigin origin) {
    }

    private record DesiredInstance(InstanceKey key, ResourceId provider, long meshKey, GeometryTransform transform) {
    }

    /** Private common lifetime for every resident published through an atomic group. */
    private interface GroupResident {
        TrackedGraphicsUse graphicsUse();

        void append(FrameUpdate update, float[] transform, int mask, InstanceKey key, GroupResident previous,
                    boolean resetVertexMotion);

        void destroy();
    }

    /** One engine-owned GPU resident for indexed scene captures. */
    private static final class DynamicResident implements GroupResident {
        GpuBuffer geometry;
        RtAccel accel;
        GpuBuffer backing;
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
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        @Override
        public TrackedGraphicsUse graphicsUse() {
            return graphicsUse;
        }

        @Override
        public void append(FrameUpdate update, float[] transform, int mask, InstanceKey key, GroupResident previous,
                           boolean resetVertexMotion) {
            DynamicResident previousDynamic = previous instanceof DynamicResident resident ? resident : null;
            update.appendResident(this, transform, mask, key,
                    previousDynamic == null ? 0L : previousDynamic.positionAddress, resetVertexMotion);
        }

        void destroyAccel() {
            if (accel != null) RtAccel.destroyCallerOwnedAccel(accel, backing);
            accel = null;
            backing = null;
        }

        @Override
        public void destroy() {
            destroyAccel();
            if (geometry != null) geometry.destroy();
            geometry = null;
        }
    }

    /** Published retained packed geometry retains only resources needed by tracing and shading. */
    private static final class RetainedGroupResident implements GroupResident {
        private RtRetainedGeometryBuilds.Prepared<?> prepared;
        private final int semanticFlags;
        private final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        RetainedGroupResident(RtRetainedGeometryBuilds.Prepared<?> prepared, int semanticFlags) {
            this.prepared = prepared;
            this.semanticFlags = semanticFlags | RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES;
        }

        void complete(RtRetainedGeometryBuilds.Prepared<?> terminal) {
            prepared = terminal;
        }

        @Override
        public TrackedGraphicsUse graphicsUse() {
            return graphicsUse;
        }

        @Override
        public void append(FrameUpdate update, float[] transform, int mask, InstanceKey key, GroupResident previous,
                           boolean resetVertexMotion) {
            int[] bases = prepared.triangleBases();
            int record = update.appendRawRecord(prepared.primitives().deviceAddress, 0L,
                    prepared.textureCoordinates().deviceAddress, 0L, bases[0], bases[1], bases[2], semanticFlags);
            update.appendInstance(transform, prepared.blas().accel.deviceAddress, record, mask, key, false);
            update.persistentUses.add(this);
        }

        @Override
        public void destroy() {
            prepared.blas().accel.destroy();
            prepared.primitives().destroy();
            prepared.textureCoordinates().destroy();
        }
    }

    /** Source-qualified identity remains stable even when a source regroups its atomic updates. */
    private record ResidentId(ResourceId source, long key) { }
    private record InstanceId(ResourceId source, long key) { }

    /** Payload-level delta captured by one atomic barrier. */
    static final class GroupDiff {
        final Map<Long, GeometryPayload> puts;
        final Set<Long> drops;
        final Map<Long, Placement> placements;
        final Set<Long> removes;

        private GroupDiff(Map<Long, GeometryPayload> puts, Set<Long> drops, Map<Long, Placement> placements,
                          Set<Long> removes) {
            this.puts = puts;
            this.drops = drops;
            this.placements = placements;
            this.removes = removes;
        }
    }

    /** Prepared, but not yet published, candidate set for one independently atomic source group. */
    static final class PreparedGroup {
        final GroupKey key;
        final long revision;
        final Barrier barrier;
        final GroupDiff diff;
        List<GroupCandidate> candidates = List.of();

        PreparedGroup(Barrier barrier) {
            this.key = barrier.key;
            this.revision = barrier.revision;
            this.barrier = barrier;
            this.diff = barrier.diff;
        }

        void destroyCandidates(GpuContext ctx) {
            for (GroupCandidate candidate : candidates) candidate.destroyUnpublished(ctx);
        }
    }

    private static final class GroupCandidate {
        final long key;
        final GroupResident resident;
        final GroupResident source;
        final RtAccel.PreparedBlas operation;
        final boolean resetMotion;
        boolean retainedTerminal;
        boolean retireSourceAfterTerminal;

        GroupCandidate(long key, GroupResident resident, GroupResident source, RtAccel.PreparedBlas operation,
                       boolean resetMotion) {
            this.key = key;
            this.resident = resident;
            this.source = source;
            this.operation = operation;
            this.resetMotion = resetMotion;
        }

        void submit(GpuContext ctx, java.util.function.BooleanSupplier cancelled,
                    java.util.function.Consumer<CandidateTerminal> completion) {
            try {
                if (resident instanceof RetainedGroupResident retained) {
                    RtRetainedGeometryBuilds.submit(ctx, retained.prepared, cancelled, terminal -> {
                        completion.accept(new CandidateTerminal(this, terminal.build(), terminal.failure(),
                                terminal.prepared()));
                    });
                    return;
                }
                Runnable completedBuild = () -> RtAccel.freeBlasScratch(List.of(operation));
                java.util.function.BiConsumer<dev.comfyfluffy.caustica.rt.RtGpuExecutor.Build, Throwable> finished =
                        (build, failure) -> {
                            if (failure != null) RtAccel.freeBlasScratch(List.of(operation));
                            completion.accept(new CandidateTerminal(this, build, failure, null));
                        };
                if (source == null) {
                    ctx.gpuExecutor().submit(cancelled, command -> RtAccel.recordBlasBuilds(ctx, command,
                            List.of(operation)), completedBuild, finished);
                } else {
                    ctx.gpuExecutor().submitAfterGraphics(source.graphicsUse(), cancelled,
                            command -> RtAccel.recordBlasBuilds(ctx, command, List.of(operation)), completedBuild, finished);
                }
            } catch (Throwable failure) {
                releaseUnsubmittedScratch();
                completion.accept(new CandidateTerminal(this, null, failure, null));
            }
        }

        void releaseUnsubmittedScratch() {
            if (operation != null) RtAccel.freeBlasScratch(List.of(operation));
        }

        void completeRetained(RtRetainedGeometryBuilds.Prepared<?> prepared) {
            if (resident instanceof RetainedGroupResident retained && prepared != null) {
                retained.complete(prepared);
                retainedTerminal = true;
            }
        }

        void destroyUnpublished(GpuContext ctx) {
            if (resident instanceof RetainedGroupResident retained) {
                if (retainedTerminal) {
                    ctx.gpuExecutor().retireUnpublished(retained::destroy);
                } else {
                    ctx.gpuExecutor().retireUnpublished(() -> RtRetainedGeometryBuilds.destroy(retained.prepared));
                }
            } else {
                ctx.gpuExecutor().retireUnpublished(resident::destroy);
            }
        }

        void destroyAfterDeviceIdle() {
            if (resident instanceof RetainedGroupResident retained && !retainedTerminal) {
                RtRetainedGeometryBuilds.destroy(retained.prepared);
            } else {
                resident.destroy();
            }
        }

        void deferSourceRetirement() {
            if (source != null) retireSourceAfterTerminal = true;
        }
    }

    private record CandidateTerminal(GroupCandidate candidate, dev.comfyfluffy.caustica.rt.RtGpuExecutor.Build build,
                                     Throwable failure, RtRetainedGeometryBuilds.Prepared<?> retainedPrepared) { }

    static record TerminalGroup(GroupKey key, PreparedGroup prepared, Throwable failure,
                                List<CandidateTerminal> candidates) {
        TerminalGroup {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        void applyRetainedCompletions() {
            for (CandidateTerminal terminal : candidates) {
                if (terminal.failure == null) terminal.candidate.completeRetained(terminal.retainedPrepared);
            }
        }
    }

    static final class Placement {
        final long residentKey;
        final float[] transform;
        final int mask;
        final SceneOrigin origin;

        Placement(long residentKey, float[] transform, int mask, SceneOrigin origin) {
            this.residentKey = residentKey;
            this.transform = transform.clone();
            this.mask = mask;
            this.origin = origin;
        }

        Placement(long residentKey, float[] transform, int mask) {
            this(residentKey, transform, mask, SceneOrigin.ZERO);
        }

        float[] transformFor(SceneOrigin targetOrigin) {
            float[] result = transform.clone();
            result[3] += (float) (origin.x() - targetOrigin.x());
            result[7] += (float) (origin.y() - targetOrigin.y());
            result[11] += (float) (origin.z() - targetOrigin.z());
            return result;
        }
    }

    /** Keeps asynchronous group failures visible to the render thread after their resources are released. */
    static final class FailureLatch {
        private Throwable failure;

        void record(Throwable candidate) {
            if (failure == null) {
                failure = candidate;
            } else if (failure != candidate) {
                failure.addSuppressed(candidate);
            }
        }

        void throwIfPresent() {
            if (failure != null) {
                throw new IllegalStateException("retained geometry group build failed", failure);
            }
        }
    }

    /** Global resident ownership with transient group barriers and conflict reservations. */
    static final class GroupScheduler {
        private final Map<ResidentId, GroupResident> publishedResidents = new LinkedHashMap<>();
        private final Map<InstanceId, Placement> publishedPlacements = new LinkedHashMap<>();
        private final Map<ResidentId, GroupResident> previousResidents = new LinkedHashMap<>();
        private final List<Barrier> pending = new ArrayList<>();
        private final Set<ResidentId> reservedResidents = new LinkedHashSet<>();
        private final Set<InstanceId> reservedInstances = new LinkedHashSet<>();
        private final Set<Barrier> running = new LinkedHashSet<>();

        void submit(GeometryUpdateGroup update) {
            submit(update, null);
        }

        void submit(GeometryUpdateGroup update, Consumer<PublicationAck> acknowledgment) {
            Map<Long, GeometryPayload> puts = new LinkedHashMap<>();
            Set<Long> drops = new LinkedHashSet<>();
            Map<Long, Placement> places = new LinkedHashMap<>();
            Set<Long> removes = new LinkedHashSet<>();
            for (GeometryOperation operation : update.operations) {
                switch (operation) {
                    case Put put -> {
                        puts.put(put.residentKey, put.payload); drops.remove(put.residentKey);
                    }
                    case Drop drop -> {
                        puts.remove(drop.residentKey); drops.add(drop.residentKey);
                    }
                    case Place place -> {
                        Placement value = new Placement(place.residentKey, place.transform, place.mask, place.origin);
                        places.put(place.instanceKey, value); removes.remove(place.instanceKey);
                    }
                    case Remove remove -> {
                        places.remove(remove.instanceKey); removes.add(remove.instanceKey);
                    }
                }
            }
            Barrier barrier = new Barrier(update.key, update.revision,
                    new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places), Set.copyOf(removes)),
                    acknowledgment);
            mergePending(barrier);
        }

        private void mergePending(Barrier barrier) {
            ArrayList<Barrier> merged = new ArrayList<>();
            boolean changed;
            do {
                changed = false;
                for (Barrier other : pending) {
                    if (!merged.contains(other) && (other.key.equals(barrier.key) || other.overlaps(barrier))) {
                        barrier = other.merge(barrier);
                        merged.add(other);
                        changed = true;
                    }
                }
            } while (changed);
            validateBarrier(barrier);
            pending.removeAll(merged);
            pending.add(barrier);
        }

        private void validateBarrier(Barrier barrier) {
            for (Placement placement : barrier.diff.placements.values()) {
                ResidentId target = new ResidentId(barrier.key.source, placement.residentKey);
                if (barrier.diff.drops.contains(placement.residentKey)
                        || (!publishedResidents.containsKey(target) && !barrier.diff.puts.containsKey(placement.residentKey))) {
                    throw new IllegalArgumentException("geometry placement references a resident absent from its final barrier state "
                            + placement.residentKey);
                }
            }
            for (long drop : barrier.diff.drops) {
                for (Map.Entry<InstanceId, Placement> published : publishedPlacements.entrySet()) {
                    if (!published.getKey().source.equals(barrier.key.source) || published.getValue().residentKey != drop) continue;
                    Placement replacement = barrier.diff.placements.get(published.getKey().key);
                    if (!barrier.diff.removes.contains(published.getKey().key)
                            && (replacement == null || replacement.residentKey == drop)) {
                        throw new IllegalArgumentException("geometry drop leaves a published placement referencing resident " + drop);
                    }
                }
            }
        }

        List<GroupRun> startable() {
            List<GroupRun> result = new ArrayList<>();
            for (int i = 0; i < pending.size(); ) {
                Barrier barrier = pending.get(i);
                if (reservedResidents.stream().anyMatch(barrier.residents::contains)
                        || reservedInstances.stream().anyMatch(barrier.instances::contains)) {
                    i++; continue;
                }
                try {
                    validateBarrier(barrier);
                } catch (IllegalArgumentException invalid) {
                    pending.remove(i);
                    throw invalid;
                }
                pending.remove(i);
                reservedResidents.addAll(barrier.residents);
                reservedInstances.addAll(barrier.instances);
                running.add(barrier);
                PreparedGroup prepared = new PreparedGroup(barrier);
                barrier.prepared = prepared;
                result.add(new GroupRun(barrier.key, prepared));
            }
            return result;
        }

        boolean running(Barrier barrier) { return running.contains(barrier); }
        boolean cancelled(Barrier barrier) { return barrier.cancelled; }

        List<GroupResident> clearSource(ResourceId source) {
            pending.removeIf(barrier -> barrier.key.source.equals(source));
            Set<GroupResident> deferred = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Barrier barrier : running) {
                if (!barrier.key.source.equals(source)) continue;
                barrier.cancelled = true;
                if (barrier.prepared != null) {
                    for (GroupCandidate candidate : barrier.prepared.candidates) {
                        if (candidate.source != null) {
                            candidate.deferSourceRetirement();
                            deferred.add(candidate.source);
                        }
                    }
                }
            }
            ArrayList<GroupResident> retired = new ArrayList<>();
            var residents = publishedResidents.entrySet().iterator();
            while (residents.hasNext()) {
                Map.Entry<ResidentId, GroupResident> entry = residents.next();
                if (entry.getKey().source.equals(source)) {
                    retired.add(entry.getValue());
                    residents.remove();
                }
            }
            publishedPlacements.entrySet().removeIf(entry -> entry.getKey().source.equals(source));
            var previous = previousResidents.entrySet().iterator();
            while (previous.hasNext()) {
                Map.Entry<ResidentId, GroupResident> entry = previous.next();
                if (entry.getKey().source.equals(source)) {
                    retired.add(entry.getValue());
                    previous.remove();
                }
            }
            retired.removeIf(deferred::contains);
            return retired;
        }

        void complete(Barrier barrier, boolean success) {
            if (!running.remove(barrier)) return;
            reservedResidents.removeAll(barrier.residents);
            reservedInstances.removeAll(barrier.instances);
        }

        GroupResident publishedResident(ResidentId id) { return publishedResidents.get(id); }
        GroupResident putPublishedResident(ResidentId id, GroupResident resident) { return publishedResidents.put(id, resident); }
        GroupResident removePublishedResident(ResidentId id) { return publishedResidents.remove(id); }
        GroupResident putPreviousResident(ResidentId id, GroupResident resident) { return previousResidents.put(id, resident); }
        GroupResident previousResident(ResidentId id) { return previousResidents.get(id); }
        java.util.Collection<GroupResident> previousResidents() { return List.copyOf(previousResidents.values()); }
        void clearPreviousResidents() { previousResidents.clear(); }
        void putPublishedPlacement(InstanceId id, Placement placement) { publishedPlacements.put(id, placement); }
        void removePublishedPlacement(InstanceId id) { publishedPlacements.remove(id); }
        Set<Map.Entry<InstanceId, Placement>> publishedPlacements() { return Set.copyOf(publishedPlacements.entrySet()); }

        void destroyAfterDeviceIdle(Set<PreparedGroup> destroyed,
                                    java.util.function.BiConsumer<PreparedGroup, Set<PreparedGroup>> destroyPrepared) {
            for (Barrier barrier : running) {
                if (barrier.prepared != null) destroyPrepared.accept(barrier.prepared, destroyed);
            }
            for (GroupResident resident : publishedResidents.values()) resident.destroy();
            for (GroupResident resident : previousResidents.values()) resident.destroy();
            publishedResidents.clear();
            publishedPlacements.clear();
            previousResidents.clear();
            pending.clear();
            reservedResidents.clear();
            reservedInstances.clear();
            running.clear();
        }

    }

    private static final class Barrier {
        final GroupKey key;
        final long revision;
        final GroupDiff diff;
        final Set<ResidentId> residents;
        final Set<InstanceId> instances;
        final Consumer<PublicationAck> acknowledgment;
        PreparedGroup prepared;
        boolean cancelled;

        Barrier(GroupKey key, long revision, GroupDiff diff, Consumer<PublicationAck> acknowledgment) {
            this.key = key; this.revision = revision; this.diff = diff; this.acknowledgment = acknowledgment;
            residents = new LinkedHashSet<>();
            diff.puts.keySet().forEach(value -> residents.add(new ResidentId(key.source, value)));
            diff.drops.forEach(value -> residents.add(new ResidentId(key.source, value)));
            diff.placements.values().forEach(value -> residents.add(new ResidentId(key.source, value.residentKey)));
            instances = new LinkedHashSet<>();
            diff.placements.keySet().forEach(value -> instances.add(new InstanceId(key.source, value)));
            diff.removes.forEach(value -> instances.add(new InstanceId(key.source, value)));
        }

        boolean overlaps(Barrier other) {
            return residents.stream().anyMatch(other.residents::contains) || instances.stream().anyMatch(other.instances::contains);
        }

        Barrier merge(Barrier newer) {
            if (!key.source.equals(newer.key.source)) throw new IllegalArgumentException("cannot merge different sources");
            Map<Long, GeometryPayload> puts = new LinkedHashMap<>(diff.puts);
            Set<Long> drops = new LinkedHashSet<>(diff.drops);
            newer.diff.puts.forEach((id, payload) -> { puts.put(id, payload); drops.remove(id); });
            newer.diff.drops.forEach(id -> { puts.remove(id); drops.add(id); });
            Map<Long, Placement> places = new LinkedHashMap<>(diff.placements);
            Set<Long> removes = new LinkedHashSet<>(diff.removes);
            newer.diff.placements.forEach((id, placement) -> { places.put(id, placement); removes.remove(id); });
            newer.diff.removes.forEach(id -> { places.remove(id); removes.add(id); });
            return new Barrier(newer.key, newer.revision,
                    new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places), Set.copyOf(removes)),
                    newer.acknowledgment != null ? newer.acknowledgment : acknowledgment);
        }

        List<GeometryOperation> operations() {
            ArrayList<GeometryOperation> result = new ArrayList<>();
            diff.puts.forEach((id, payload) -> result.add(new Put(id, payload)));
            diff.drops.forEach(id -> result.add(new Drop(id)));
            diff.placements.forEach((id, placement) -> result.add(new Place(id, placement.residentKey,
                    placement.transform, placement.mask, placement.origin)));
            diff.removes.forEach(id -> result.add(new Remove(id)));
            return List.copyOf(result);
        }
    }

    static record GroupRun(GroupKey key, PreparedGroup prepared) { }

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
        GpuBuffer history;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        TableSlot(GpuBuffer buffer, GpuBuffer history) {
            this.buffer = buffer;
            this.history = history;
        }
    }
}
