package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.api.provider.SceneGeometrySink;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtOpacityMicromapPipeline;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
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
 * Owns retained scene geometry, its asynchronous BLAS builds, records, and exact graphics lifetime.
 */
public final class RtSceneGeometryManager {
    private static final int TABLE_RING = 4;
    private static final long MIN_BUFFER_BYTES = 256L;
    private static final int HISTORY_BYTES = 3 * 4 * Float.BYTES;

    private final RtGeometryMaterialResolver materialResolver;
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();
    private final GroupScheduler groupScheduler = new GroupScheduler();
    private final ConcurrentLinkedQueue<TerminalGroup> terminalGroups = new ConcurrentLinkedQueue<>();
    private final Map<InstanceKey, InstanceState> instanceStates = new HashMap<>();
    private final FailureLatch groupFailures = new FailureLatch();
    private final TableSlot[] tables = new TableSlot[TABLE_RING];
    private int tableCursor;
    private RtOpacityMicromapPipeline opacityMicromapPipeline;
    private boolean loggedOpacityMicromapBuild;

    public RtSceneGeometryManager(RtGeometryMaterialResolver materialResolver) {
        this.materialResolver = materialResolver;
    }

    /** Installs the immutable classifier resources for the current material epoch. */
    public void setOpacityMicromapPipeline(RtOpacityMicromapPipeline pipeline) {
        opacityMicromapPipeline = pipeline;
    }

    /** Stable owner identity for one independently published retained-geometry group. */
    public record GroupKey(ResourceId source, SceneGeometryKey key) {
        public GroupKey(ResourceId source, long key) { this(source, SceneGeometryKey.of(key)); }
    }

    /** Public mesh data packed into the renderer format when its atomic update starts. */
    public sealed interface GeometryPayload permits ProviderPayload { }
    public record ProviderPayload(SceneMesh mesh, SceneGeometrySink.BuildOptions buildOptions) implements GeometryPayload {
        public ProviderPayload(SceneMesh mesh) { this(mesh, SceneGeometrySink.BuildOptions.DEFAULT); }
    }

    /** Immutable operation belonging to one atomic geometry group. */
    public sealed interface GeometryOperation permits Put, Drop, Place, Remove { }
    public record Put(SceneGeometryKey residentKey, GeometryPayload payload) implements GeometryOperation {
        public Put(long residentKey, GeometryPayload payload) { this(SceneGeometryKey.of(residentKey), payload); }
    }
    public record Drop(SceneGeometryKey residentKey) implements GeometryOperation {
        public Drop(long residentKey) { this(SceneGeometryKey.of(residentKey)); }
    }
    public record Place(SceneGeometryKey instanceKey, SceneGeometryKey residentKey, float[] transform, int mask, SceneOrigin origin)
            implements GeometryOperation {
        public Place(long instanceKey, long residentKey, float[] transform, int mask, SceneOrigin origin) {
            this(SceneGeometryKey.of(instanceKey), SceneGeometryKey.of(residentKey), transform, mask, origin);
        }
        public Place(long instanceKey, long residentKey, float[] transform, int mask) {
            this(SceneGeometryKey.of(instanceKey), SceneGeometryKey.of(residentKey), transform, mask, SceneOrigin.ZERO);
        }
        public Place { transform = transform.clone(); }
        @Override public float[] transform() { return transform.clone(); }
    }
    public record Remove(SceneGeometryKey instanceKey) implements GeometryOperation {
        public Remove(long instanceKey) { this(SceneGeometryKey.of(instanceKey)); }
    }

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
        submit(updates, acknowledgment, null);
    }

    /** Provider submissions can handle their own asynchronous failure without disabling other sources. */
    public void submit(List<GeometryUpdateGroup> updates, Consumer<PublicationAck> acknowledgment,
                       Consumer<Throwable> failureHandler) {
        RtFrameStats.FRAME.count("geometryGroupsSubmitted", updates.size());
        for (GeometryUpdateGroup update : updates) {
            for (GeometryOperation operation : update.operations()) {
                if (operation instanceof Put put) {
                    RtFrameStats.FRAME.count("geometryPutsSubmitted", 1);
                    RtFrameStats.FRAME.count("geometryTrianglesSubmitted",
                            ((ProviderPayload) put.payload()).mesh().triangleCount());
                }
            }
        }
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.schedulerSubmit")) {
            groupScheduler.submitAll(updates, acknowledgment, failureHandler);
        }
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

    /** Build-ready TLAS view over the manager's published geometry snapshot. */
    public RtAccel.PreparedTlas prepareTlas(GpuContext ctx, FrameUpdate update, GraphicsUse graphicsUse) {
        return RtAccel.prepareTlas(ctx, List.of(), update.instances, tlasRing, graphicsUse);
    }

    /**
     * Advances retained geometry independently of frame assembly so startup can publish terrain before
     * the first RT frame is eligible to trace it.
     */
    public void progress(GpuContext ctx) {
        RtFrameStats.FRAME.max("geometryPendingGroups", groupScheduler.pendingCount());
        RtFrameStats.FRAME.max("geometryRunningGroups", groupScheduler.runningCount());
        RtFrameStats.FRAME.max("geometryTerminalGroups", terminalGroups.size());
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.publishTerminal")) {
            publishTerminalGroups(ctx);
        }
        groupFailures.throwIfPresent();
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.prepareCandidates")) {
            startGroupCandidates(ctx);
        }
    }

    /** Publishes completed groups and snapshots their geometry for this frame. */
    public FrameUpdate beginUpdate(GpuContext ctx, SceneOrigin origin) {
        progress(ctx);
        int placementCount = groupScheduler.publishedPlacementCount();
        TableSlot table = selectTable(ctx, placementCount);
        FrameUpdate update = new FrameUpdate(ctx, table, origin, placementCount);
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.snapshotAppend")) {
            appendPublishedGroups(update);
            update.finish();
        }
        RtFrameStats.FRAME.set("geometryInstancesVisible", update.instances.size());
        RtFrameStats.FRAME.set("geometryPublishedResidents", groupScheduler.publishedResidentCount());
        RtFrameStats.FRAME.set("geometryPublishedPlacements", groupScheduler.publishedPlacementCount());
        RtGeometryProfiling.frameVisible();
        return update;
    }

    /** Associates all resources published by an update with the graphics submission that traces them. */
    public void markGraphicsUse(FrameUpdate update, GraphicsUse graphicsUse) {
        update.markGraphicsUse(graphicsUse);
    }

    /** Teardown after the device is idle. */
    public void shutdown() {
        RtGeometryProfiling.resetPublications();
        Set<PreparedGroup> destroyedGroups = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<GroupResident> destroyedCancelledSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        TerminalGroup terminal;
        while ((terminal = terminalGroups.poll()) != null) {
            destroyPreparedGroupAfterDeviceIdle(terminal.prepared, destroyedGroups);
            destroyCancelledSourcesAfterDeviceIdle(terminal.prepared, destroyedCancelledSources);
        }
        groupScheduler.destroyAfterDeviceIdle(destroyedGroups, (prepared, destroyed) -> {
            destroyPreparedGroupAfterDeviceIdle(prepared, destroyed);
            destroyCancelledSourcesAfterDeviceIdle(prepared, destroyedCancelledSources);
        });
        tlasRing.destroy();
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
        DynamicResident preparingCandidate = null;
        try {
        for (Map.Entry<SceneGeometryKey, GeometryPayload> entry : prepared.diff.puts.entrySet()) {
            GroupResident source = groupScheduler.publishedResident(new ResidentId(prepared.key.source(), entry.getKey()));
            ProviderPayload provider = (ProviderPayload) entry.getValue();
            DynamicResident candidate = new DynamicResident();
            preparingCandidate = candidate;
            PackedInput input;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.packMaterial")) {
                input = providerInput(provider.mesh());
            }
            writeDynamic(ctx, candidate, input);
            candidate.vertexCount = input.positions.length / 3;
            candidate.topology = Topology.of(input);
            RtAccel.PreparedBlas operation;
            DynamicResident dynamicSource = source instanceof DynamicResident resident ? resident : null;
            boolean previousIndexed = dynamicSource != null;
            boolean retainPreviousPositions = previousIndexed && dynamicSource.topology != null
                    && dynamicSource.topology.matches(candidate.topology);
            boolean minimizeMemory = provider.buildOptions().minimizeMemory();
            boolean opacityAcceleration = provider.buildOptions().opacityAcceleration()
                    && RtDeviceBringup.ommEnabled() && opacityMicromapPipeline != null
                    && candidate.classTriangles[RtAccel.CLASS_MASKED] > 0
                    && hasEligibleOpacityBinding(input);
            if (opacityAcceleration) {
                int subdivisionLevel = Math.min(4, Math.max(0,
                        RtDeviceBringup.maxOpacity4StateSubdivisionLevel()));
                int microTriangles = 1 << (subdivisionLevel * 2);
                int bytesPerTriangle = Math.max(1, (microTriangles * 2 + 7) >>> 3);
                int maskedBase = candidate.classTriangles[RtAccel.CLASS_OPAQUE];
                RtOpacityMicromapPipeline classifier = opacityMicromapPipeline;
                RtAccel.OpacityMicromapGpuInput opacityInput = new RtAccel.OpacityMicromapGpuInput(
                        candidate.classTriangles[RtAccel.CLASS_MASKED], subdivisionLevel, bytesPerTriangle,
                        (command, dataAddress, triangleAddress, dataStride) -> classifier.record(command,
                                candidate.indexAddress, candidate.textureCoordinateAddress,
                                candidate.primitiveAddress, RtMaterialRegistry.INSTANCE.bindingTableAddress(),
                                RtMaterialRegistry.INSTANCE.surfaceTableAddress(), dataAddress, triangleAddress,
                                maskedBase, candidate.classTriangles[RtAccel.CLASS_MASKED], candidate.semanticFlags,
                                subdivisionLevel, dataStride));
                if (!loggedOpacityMicromapBuild) {
                    loggedOpacityMicromapBuild = true;
                    CausticaMod.LOGGER.info("RT opacity micromap build active: maskedTriangles={}, subdivisionLevel={}, compact={}",
                            candidate.classTriangles[RtAccel.CLASS_MASKED], subdivisionLevel, minimizeMemory);
                }
                RtAccel.CompactableBuild build = RtAccel.prepareOpacityMicromapBlasBuild(ctx,
                        candidate.positionAddress, candidate.vertexCount, candidate.indexAddress,
                        candidate.classTriangles, opacityInput, minimizeMemory, "scene group BLAS");
                candidate.accel = build.accel();
                candidate.backing = build.backing();
                candidate.updatable = false;
                operation = build.op();
            } else if (!minimizeMemory && dynamicSource != null && dynamicSource.updatable
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
            } else if (!minimizeMemory) {
                RtAccel.UpdatableBuild build = RtAccel.prepareUpdatableBlasBuild(ctx, candidate.positionAddress,
                        candidate.vertexCount, candidate.indexAddress, candidate.classTriangles, "scene group BLAS");
                candidate.accel = build.accel();
                candidate.backing = build.backing();
                candidate.updatable = true;
                operation = build.op();
            } else {
                RtAccel.CompactableBuild build = RtAccel.prepareCompactableBlasBuild(ctx,
                        candidate.positionAddress, candidate.vertexCount, candidate.indexAddress,
                        candidate.classTriangles, "scene group BLAS");
                candidate.accel = build.accel();
                candidate.backing = build.backing();
                candidate.updatable = false;
                operation = build.op();
            }
            candidates.add(new GroupCandidate(entry.getKey(), candidate, source, operation, !retainPreviousPositions));
            RtFrameStats.FRAME.count("geometryBlasCandidates", 1);
            preparingCandidate = null;
        }
        } catch (Throwable failure) {
            if (preparingCandidate != null) preparingCandidate.destroy();
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

    PackedInput providerInput(SceneMesh mesh) {
        RtGeometryMeshPacking.PackedMesh packed = RtGeometryMeshPacking.pack(mesh, materialResolver);
        return new PackedInput(packed.positions(), packed.indices(), packed.textureCoordinates(), packed.primitives(),
                packed.classTriangles(), packed.flags());
    }

    boolean providerTopologyMatches(SceneMesh first, SceneMesh second) {
        return Topology.of(providerInput(first)).matches(Topology.of(providerInput(second)));
    }

    private static boolean hasEligibleOpacityBinding(PackedInput input) {
        int first = input.classTriangles[RtAccel.CLASS_OPAQUE];
        int end = first + input.classTriangles[RtAccel.CLASS_MASKED];
        for (int triangle = first; triangle < end; triangle++) {
            int materialId = Float.floatToRawIntBits(input.primitives[triangle * 12 + 8]);
            if (RtMaterialRegistry.INSTANCE.opacityMicromapEligible(materialId)) return true;
        }
        return false;
    }

    private void publishTerminalGroups(GpuContext ctx) {
        TerminalGroup terminal;
        while ((terminal = terminalGroups.poll()) != null) {
            boolean cancelled = groupScheduler.cancelled(terminal.prepared.barrier);
            Throwable terminalFailure = terminal.failure;
            if (terminalFailure == null && terminal.prepared.candidates.stream()
                    .anyMatch(candidate -> !candidate.publicationState.publishable())) {
                terminalFailure = new IllegalStateException("geometry candidate reached publication before terminal build phase");
            }
            if (!groupScheduler.running(terminal.prepared.barrier) || cancelled || terminalFailure != null) {
                terminal.prepared.destroyCandidates(ctx);
                retireCancelledSources(ctx, terminal.prepared);
                groupScheduler.complete(terminal.prepared.barrier, false);
                if (!cancelled && terminalFailure != null) {
                    if (terminal.prepared.barrier.failureHandler != null) {
                        terminal.prepared.barrier.failureHandler.accept(terminalFailure);
                    } else {
                        groupFailures.record(terminalFailure);
                    }
                }
                continue;
            }
            for (CandidateTerminal candidate : terminal.candidates) {
                if (candidate.build != null) ctx.gpuExecutor().markPublished(candidate.build);
            }
            for (GroupCandidate candidate : terminal.prepared.candidates) {
                ResidentId residentId = new ResidentId(terminal.prepared.key.source(), candidate.key);
                for (GroupResident retired : groupScheduler.putPublishedResident(
                        residentId, candidate.resident, !candidate.resetMotion)) {
                    ctx.gpuExecutor().retireAfterGraphics(retired.graphicsUse(), retired::destroy);
                }
            }
            RtFrameStats.FRAME.count("geometryGroupsPublished", 1);
            RtFrameStats.FRAME.count("geometryPutsPublished", terminal.prepared.candidates.size());
            for (Map.Entry<SceneGeometryKey, Placement> placement : terminal.prepared.diff.placements.entrySet()) {
                groupScheduler.putPublishedPlacement(new InstanceId(terminal.prepared.key.source(), placement.getKey()),
                        placement.getValue());
            }
            for (SceneGeometryKey remove : terminal.prepared.diff.removes) {
                groupScheduler.removePublishedPlacement(new InstanceId(terminal.prepared.key.source(), remove));
                instanceStates.remove(new InstanceKey(terminal.prepared.key.source(), remove));
            }
            for (SceneGeometryKey drop : terminal.prepared.diff.drops) {
                for (GroupResident retired : groupScheduler.removePublishedResident(
                        new ResidentId(terminal.prepared.key.source(), drop))) {
                    ctx.gpuExecutor().retireAfterGraphics(retired.graphicsUse(), retired::destroy);
                }
            }
            groupScheduler.complete(terminal.prepared.barrier, true);
            if (terminal.prepared.barrier.acknowledgment != null) {
                terminal.prepared.barrier.acknowledgment.accept(new PublicationAck(terminal.prepared.key,
                        terminal.prepared.revision, terminal.prepared.barrier.operations()));
            }
        }
    }

    private void appendPublishedGroups(FrameUpdate update) {
        for (PublishedPlacement published : groupScheduler.publishedPlacements()) {
            PublishedResidentSlot slot = published.resident;
            Placement placement = published.placement;
            slot.current.append(update, placement.transformFor(update.origin), placement.mask, published.historyKey,
                    slot.previous, resetTransformMotion(instanceStates.containsKey(published.historyKey)));
            if (slot.previous != null) update.historyUses.add(slot.previous);
        }
        groupScheduler.drainPreviousResidents(update.historyRetire::add);
    }

    private void retireCancelledSources(GpuContext ctx, PreparedGroup prepared) {
        Set<GroupResident> retired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (GroupCandidate candidate : prepared.candidates) {
            if (candidate.retireSourceAfterTerminal && retired.add(candidate.source)) {
                ctx.gpuExecutor().retireAfterGraphics(candidate.source.graphicsUse(), candidate.source::destroy);
            }
        }
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

    /** Engine-owned published-scene snapshot for one graphics submission. */
    public final class FrameUpdate {
        private final GpuContext ctx;
        private final TableSlot table;
        private final SceneOrigin origin;
        private final ArrayList<RtAccel.Instance> instances;
        private final ArrayList<GroupResident> persistentUses;
        private final Set<GroupResident> historyUses = new LinkedHashSet<>();
        private final Set<GroupResident> historyRetire = new LinkedHashSet<>();
        private int count;

        private FrameUpdate(GpuContext ctx, TableSlot table, SceneOrigin origin, int placementCount) {
            this.ctx = ctx;
            this.table = table;
            this.origin = origin;
            this.instances = new ArrayList<>(placementCount);
            this.persistentUses = new ArrayList<>(placementCount);
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
            int record = RtGeometryAbi.checkedIndex(0, count);
            ensureDynamicCapacity(record + 1);
            long address = table.buffer.mapped + (long) record * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, primitiveAddress, indexAddress, textureCoordinateAddress,
                    previousPositionAddress, triangleBase0, triangleBase1, triangleBase2, semanticFlags);
            count++;
            return record;
        }

        private void appendInstance(float[] transform, long accelAddress, int record, int mask, InstanceKey key,
                                    boolean resetMotion) {
            instances.add(new RtAccel.Instance(transform, accelAddress, record, mask));
            writeHistory(table, record, resetMotion ? transform : previousTransform(key, transform, origin));
            if (key != null) instanceStates.put(key, new InstanceState(transform.clone(), origin));
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
            table.graphicsUse.mark(graphicsUse);
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
            int records = RtGeometryAbi.checkedRecordCount(0, count);
            if (records != 0) {
                table.buffer.flush(0L, (long) records * RtGeometryAbi.RECORD_BYTES);
                table.history.flush(0L, (long) records * HISTORY_BYTES);
            }
        }

        private void ensureDynamicCapacity(int records) {
            long required = Math.max(MIN_BUFFER_BYTES, (long) records * RtGeometryAbi.RECORD_BYTES);
            if (table.buffer.size >= required) {
                return;
            }
            long grown = Math.max(required, table.buffer.size + table.buffer.size / 2L);
            GpuBuffer replacement = ctx.createBuffer(grown, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "scene geometry table");
            GpuBuffer historyReplacement = ctx.createBuffer(Math.max(MIN_BUFFER_BYTES,
                    (grown / RtGeometryAbi.RECORD_BYTES) * HISTORY_BYTES), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true, "scene instance history");
            int existing = RtGeometryAbi.checkedRecordCount(0, count);
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

    /** Geometry-table address used only by renderer orchestration after the frame update is finished. */
    public long geometryTableAddress(FrameUpdate frame) {
        return frame.table.buffer.deviceAddress;
    }

    /** Per-instance transform history table used by hit shaders alongside the geometry table. */
    public long instanceHistoryAddress(FrameUpdate frame) {
        return frame.table.history.deviceAddress;
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
        return rebasePreviousTransform(previous.transform, previous.origin, currentOrigin);
    }

    static boolean resetTransformMotion(boolean hasHistory) {
        return !hasHistory;
    }

    static float[] rebasePreviousTransform(float[] previous, SceneOrigin previousOrigin, SceneOrigin currentOrigin) {
        float[] transform = previous.clone();
        transform[3] += (float) (previousOrigin.x() - currentOrigin.x());
        transform[7] += (float) (previousOrigin.y() - currentOrigin.y());
        transform[11] += (float) (previousOrigin.z() - currentOrigin.z());
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

    /** Renderer-private packed representation of a neutral scene mesh. */
    static record PackedInput(float[] positions, int[] indices, float[] textureCoordinates, float[] primitives,
                              int[] classTriangles, int semanticFlags) {
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
            int uvFlags = semanticFlags & (RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    | RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES);
            if (uvFlags != RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    && uvFlags != RtGeometryAbi.FLAG_TRIANGLE_CORNER_TEXTURE_COORDINATES) {
                throw new IllegalArgumentException("packed geometry must declare exactly one texture-coordinate layout");
            }
            int expectedTextureCoordinates = uvFlags == RtGeometryAbi.FLAG_INDEXED_TEXTURE_COORDINATES
                    ? vertexCount * 2 : triangleCount * 6;
            if (textureCoordinates.length != expectedTextureCoordinates) {
                throw new IllegalArgumentException("packed geometry texture coordinates do not match their declared layout");
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

    private static final class Topology {
        final int vertexCount;
        final int[] indices;
        final int[] classTriangles;
        final int semanticFlags;

        private Topology(int vertexCount, int[] indices, int[] classTriangles, int semanticFlags) {
            this.vertexCount = vertexCount;
            this.indices = indices.clone();
            this.classTriangles = classTriangles.clone();
            this.semanticFlags = semanticFlags;
        }

        static Topology of(PackedInput input) {
            return new Topology(input.positions.length / 3, input.indices, input.classTriangles, input.semanticFlags);
        }

        boolean matches(Topology other) {
            return vertexCount == other.vertexCount && semanticFlags == other.semanticFlags
                    && java.util.Arrays.equals(indices, other.indices)
                    && java.util.Arrays.equals(classTriangles, other.classTriangles);
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

    record InstanceKey(ResourceId provider, SceneGeometryKey key) {
    }

    private record InstanceState(float[] transform, SceneOrigin origin) {
    }

    /** Private common lifetime for every resident published through an atomic group. */
    interface GroupResident {
        TrackedGraphicsUse graphicsUse();

        void append(FrameUpdate update, float[] transform, int mask, InstanceKey key, GroupResident previous,
                    boolean resetTransformMotion);

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
        int vertexCount = -1;
        Topology topology;
        boolean updatable;
        int updatesSinceBuild;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        @Override
        public TrackedGraphicsUse graphicsUse() {
            return graphicsUse;
        }

        @Override
        public void append(FrameUpdate update, float[] transform, int mask, InstanceKey key, GroupResident previous,
                           boolean resetTransformMotion) {
            DynamicResident previousDynamic = previous instanceof DynamicResident resident ? resident : null;
            update.appendResident(this, transform, mask, key,
                    previousDynamic == null ? 0L : previousDynamic.positionAddress, resetTransformMotion);
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

    /** Source-qualified identity remains stable even when a source regroups its atomic updates. */
    record ResidentId(ResourceId source, SceneGeometryKey key) { }
    record InstanceId(ResourceId source, SceneGeometryKey key) { }

    static final class PublishedResidentSlot {
        final ResidentId id;
        final Set<PublishedPlacement> placements = new LinkedHashSet<>();
        GroupResident current;
        GroupResident previous;

        PublishedResidentSlot(ResidentId id, GroupResident current) {
            this.id = id;
            this.current = current;
        }
    }

    static final class PublishedPlacement {
        final InstanceId id;
        final InstanceKey historyKey;
        Placement placement;
        PublishedResidentSlot resident;

        PublishedPlacement(InstanceId id, Placement placement, PublishedResidentSlot resident) {
            this.id = id;
            this.historyKey = new InstanceKey(id.source, id.key);
            this.placement = placement;
            this.resident = resident;
        }
    }

    /** Payload-level delta captured by one atomic barrier. */
    static final class GroupDiff {
        final Map<SceneGeometryKey, GeometryPayload> puts;
        final Set<SceneGeometryKey> drops;
        final Map<SceneGeometryKey, Placement> placements;
        final Set<SceneGeometryKey> removes;

        private GroupDiff(Map<SceneGeometryKey, GeometryPayload> puts, Set<SceneGeometryKey> drops,
                          Map<SceneGeometryKey, Placement> placements, Set<SceneGeometryKey> removes) {
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
        final SceneGeometryKey key;
        final GroupResident resident;
        final GroupResident source;
        final RtAccel.PreparedBlas operation;
        final boolean resetMotion;
        boolean retireSourceAfterTerminal;
        final CompactionPublicationState publicationState;

        GroupCandidate(SceneGeometryKey key, GroupResident resident, GroupResident source, RtAccel.PreparedBlas operation,
                       boolean resetMotion) {
            this.key = key;
            this.resident = resident;
            this.source = source;
            this.operation = operation;
            this.resetMotion = resetMotion;
            this.publicationState = new CompactionPublicationState(operation.requestsCompaction());
        }

        void submit(GpuContext ctx, java.util.function.BooleanSupplier cancelled,
                    java.util.function.Consumer<CandidateTerminal> completion) {
            long buildStarted = RtGeometryProfiling.beginBuild();
            int triangles = buildStarted == 0L ? 0
                    : triangleCount(((DynamicResident) resident).classTriangles);
            boolean update = operation.updateMode();
            boolean compaction = operation.requestsCompaction();
            try {
                Runnable completedBuild = () -> RtAccel.freeBlasScratch(List.of(operation));
                java.util.function.BiConsumer<dev.comfyfluffy.caustica.rt.RtGpuExecutor.Build, Throwable> finished =
                        (build, failure) -> {
                            if (failure != null) RtAccel.freeBlasScratch(List.of(operation));
                            if (failure != null || !operation.requestsCompaction()) {
                                if (failure != null) publicationState.fail();
                                else publicationState.completeBuild();
                                RtGeometryProfiling.finishBuild(buildStarted, triangles, update, compaction, failure);
                                completion.accept(new CandidateTerminal(this, build, failure));
                            } else {
                                publicationState.completeBuild();
                                if (cancelled.getAsBoolean()) {
                                    publicationState.fail();
                                    var cancellation = new java.util.concurrent.CancellationException(
                                            "geometry group cancelled before BLAS compaction");
                                    RtGeometryProfiling.finishBuild(buildStarted, triangles, update, compaction,
                                            cancellation);
                                    completion.accept(new CandidateTerminal(this, build, cancellation));
                                } else {
                                    submitCompaction(ctx, cancelled, completion, buildStarted, triangles, update,
                                            compaction);
                                }
                            }
                        };
                if (source == null) {
                    ctx.gpuExecutor().submit(cancelled, command -> {
                        long started = RtGeometryProfiling.beginCommandRecord();
                        try {
                            RtAccel.recordBlasBuilds(ctx, command, List.of(operation));
                        } finally {
                            RtGeometryProfiling.endCommandRecord(started);
                        }
                    }, completedBuild, finished);
                } else {
                    ctx.gpuExecutor().submitAfterGraphics(source.graphicsUse(), cancelled,
                            command -> {
                                long started = RtGeometryProfiling.beginCommandRecord();
                                try {
                                    RtAccel.recordBlasBuilds(ctx, command, List.of(operation));
                                } finally {
                                    RtGeometryProfiling.endCommandRecord(started);
                                }
                            }, completedBuild, finished);
                }
            } catch (Throwable failure) {
                releaseUnsubmittedScratch();
                RtGeometryProfiling.finishBuild(buildStarted, triangles, update, compaction, failure);
                completion.accept(new CandidateTerminal(this, null, failure));
            }
        }

        private static int triangleCount(int[] classTriangles) {
            int total = 0;
            for (int count : classTriangles) {
                total += count;
            }
            return total;
        }

        private void submitCompaction(GpuContext ctx, java.util.function.BooleanSupplier cancelled,
                                      java.util.function.Consumer<CandidateTerminal> completion,
                                      long buildStarted, int triangles, boolean update, boolean requestsCompaction) {
            RtAccel.PreparedBlasCompaction compaction;
            try {
                compaction = RtAccel.prepareBlasCompaction(ctx, operation);
            } catch (Throwable failure) {
                publicationState.fail();
                RtGeometryProfiling.finishBuild(buildStarted, triangles, update, requestsCompaction, failure);
                completion.accept(new CandidateTerminal(this, null, failure));
                return;
            }
            try {
                ctx.gpuExecutor().submit(cancelled,
                        command -> {
                            long started = RtGeometryProfiling.beginCommandRecord();
                            try {
                                RtAccel.recordBlasCompaction(ctx, command, compaction);
                            } finally {
                                RtGeometryProfiling.endCommandRecord(started);
                            }
                        },
                        () -> completeCompaction(compaction),
                        (build, failure) -> {
                            if (failure != null) failCompaction(compaction, failure);
                            RtGeometryProfiling.finishBuild(buildStarted, triangles, update, requestsCompaction,
                                    failure);
                            completion.accept(new CandidateTerminal(this, build, failure));
                        });
            } catch (Throwable failure) {
                failCompaction(compaction, failure);
                RtGeometryProfiling.finishBuild(buildStarted, triangles, update, requestsCompaction, failure);
                completion.accept(new CandidateTerminal(this, null, failure));
            }
        }

        private void completeCompaction(RtAccel.PreparedBlasCompaction compaction) {
            RtAccel.finishBlasCompaction(compaction);
            DynamicResident dynamic = (DynamicResident) resident;
            dynamic.accel = compaction.compactedAccel();
            dynamic.backing = compaction.compactedBacking();
            publicationState.completeCompaction();
        }

        private void failCompaction(RtAccel.PreparedBlasCompaction compaction, Throwable failure) {
            try {
                RtAccel.destroyBlasCompaction(compaction);
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            DynamicResident dynamic = (DynamicResident) resident;
            dynamic.accel = null;
            dynamic.backing = null;
            publicationState.fail();
        }

        void releaseUnsubmittedScratch() {
            if (operation != null) RtAccel.freeBlasScratch(List.of(operation));
        }

        void destroyUnpublished(GpuContext ctx) {
            ctx.gpuExecutor().retireUnpublished(resident::destroy);
        }

        void destroyAfterDeviceIdle() {
            resident.destroy();
        }

        void deferSourceRetirement() {
            if (source != null) retireSourceAfterTerminal = true;
        }
    }

    static final class CompactionPublicationState {
        enum Phase { BUILD, COMPACT, TERMINAL }

        private final boolean compactionRequested;
        private Phase phase = Phase.BUILD;
        private boolean publishable;

        CompactionPublicationState(boolean compactionRequested) {
            this.compactionRequested = compactionRequested;
        }

        void completeBuild() {
            require(Phase.BUILD);
            if (compactionRequested) {
                phase = Phase.COMPACT;
            } else {
                phase = Phase.TERMINAL;
                publishable = true;
            }
        }

        void completeCompaction() {
            require(Phase.COMPACT);
            phase = Phase.TERMINAL;
            publishable = true;
        }

        void fail() {
            if (phase == Phase.TERMINAL) return;
            phase = Phase.TERMINAL;
            publishable = false;
        }

        Phase phase() { return phase; }
        boolean publishable() { return publishable; }

        private void require(Phase expected) {
            if (phase != expected) throw new IllegalStateException("expected " + expected + " but was " + phase);
        }
    }

    private record CandidateTerminal(GroupCandidate candidate, dev.comfyfluffy.caustica.rt.RtGpuExecutor.Build build,
                                     Throwable failure) { }

    static record TerminalGroup(GroupKey key, PreparedGroup prepared, Throwable failure,
                                List<CandidateTerminal> candidates) {
        TerminalGroup {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

    }

    static final class Placement {
        final SceneGeometryKey residentKey;
        final float[] transform;
        final int mask;
        final SceneOrigin origin;

        Placement(SceneGeometryKey residentKey, float[] transform, int mask, SceneOrigin origin) {
            this.residentKey = residentKey;
            this.transform = transform.clone();
            this.mask = mask;
            this.origin = origin;
        }

        Placement(long residentKey, float[] transform, int mask, SceneOrigin origin) {
            this(SceneGeometryKey.of(residentKey), transform, mask, origin);
        }

        Placement(SceneGeometryKey residentKey, float[] transform, int mask) {
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
        private final Map<ResidentId, PublishedResidentSlot> publishedResidents = new LinkedHashMap<>();
        private final Map<InstanceId, PublishedPlacement> publishedPlacements = new LinkedHashMap<>();
        private final Set<PublishedResidentSlot> previousResidentSlots = new LinkedHashSet<>();
        private final Map<GroupKey, Barrier> pending = new LinkedHashMap<>();
        private final Set<ResidentId> reservedResidents = new LinkedHashSet<>();
        private final Set<InstanceId> reservedInstances = new LinkedHashSet<>();
        private final Set<GroupKey> reservedGroups = new LinkedHashSet<>();
        private final Set<Barrier> running = new LinkedHashSet<>();
        private final Map<GroupKey, Long> latestAccepted = new HashMap<>();

        void submit(GeometryUpdateGroup update) {
            submit(update, null);
        }

        void submit(GeometryUpdateGroup update, Consumer<PublicationAck> acknowledgment) {
            submit(update, acknowledgment, null);
        }

        void submit(GeometryUpdateGroup update, Consumer<PublicationAck> acknowledgment,
                    Consumer<Throwable> failureHandler) {
            Long previous = latestAccepted.get(update.key);
            if (previous != null && update.revision <= previous) {
                return;
            }
            Barrier barrier = barrier(update, acknowledgment, failureHandler);
            mergePending(pending, barrier);
            latestAccepted.put(update.key, update.revision);
        }

        private static Barrier barrier(GeometryUpdateGroup update, Consumer<PublicationAck> acknowledgment,
                                       Consumer<Throwable> failureHandler) {
            Map<SceneGeometryKey, GeometryPayload> puts = new LinkedHashMap<>();
            Set<SceneGeometryKey> drops = new LinkedHashSet<>();
            Map<SceneGeometryKey, Placement> places = new LinkedHashMap<>();
            Set<SceneGeometryKey> removes = new LinkedHashSet<>();
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
            return new Barrier(update.key, update.revision,
                    new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places), Set.copyOf(removes)),
                    acknowledgment, failureHandler);
        }

        void submitAll(List<GeometryUpdateGroup> updates, Consumer<PublicationAck> acknowledgment,
                       Consumer<Throwable> failureHandler) {
            if (updates.isEmpty()) return;
            if (updates.size() == 1) {
                submit(updates.getFirst(), acknowledgment, failureHandler);
                return;
            }
            LinkedHashMap<GroupKey, Barrier> stagedPending = LinkedHashMap.newLinkedHashMap(updates.size());
            for (GeometryUpdateGroup update : updates) {
                Barrier staged = stagedPending.get(update.key);
                Long previous = staged != null ? Long.valueOf(staged.revision) : latestAccepted.get(update.key);
                if (previous != null && update.revision <= previous) {
                    continue;
                }
                Barrier next = barrier(update, acknowledgment, failureHandler);
                Barrier queued = staged != null ? staged : pending.get(update.key);
                Barrier merged = validateMerged(queued, next);
                stagedPending.remove(update.key);
                stagedPending.put(update.key, merged);
            }
            pending.keySet().removeAll(stagedPending.keySet());
            pending.putAll(stagedPending);
            stagedPending.forEach((key, barrier) -> latestAccepted.put(key, barrier.revision));
        }

        private void mergePending(Map<GroupKey, Barrier> target, Barrier barrier) {
            Barrier merged = validateMerged(target.get(barrier.key), barrier);
            target.remove(barrier.key);
            target.put(barrier.key, merged);
        }

        private Barrier validateMerged(Barrier queued, Barrier next) {
            Barrier merged = queued != null ? queued.merge(next) : next;
            validateBarrier(merged);
            return merged;
        }

        private void validateBarrier(Barrier barrier) {
            long profilingStart = RtFrameStats.FRAME.startStage();
            try {
                for (Placement placement : barrier.diff.placements.values()) {
                    ResidentId target = new ResidentId(barrier.key.source, placement.residentKey);
                    if (barrier.diff.drops.contains(placement.residentKey)
                            || (!publishedResidents.containsKey(target)
                            && !barrier.diff.puts.containsKey(placement.residentKey))) {
                        throw new IllegalArgumentException(
                                "geometry placement references a resident absent from its final barrier state "
                                        + placement.residentKey);
                    }
                }
                for (SceneGeometryKey drop : barrier.diff.drops) {
                    PublishedResidentSlot slot = publishedResidents.get(new ResidentId(barrier.key.source, drop));
                    if (slot == null) continue;
                    for (PublishedPlacement published : slot.placements) {
                        Placement replacement = barrier.diff.placements.get(published.id.key);
                        if (!barrier.diff.removes.contains(published.id.key)
                                && (replacement == null || replacement.residentKey.equals(drop))) {
                            throw new IllegalArgumentException(
                                    "geometry drop leaves a published placement referencing resident " + drop);
                        }
                    }
                }
            } finally {
                RtFrameStats.FRAME.endStage("geometry.schedulerValidate", profilingStart);
            }
        }

        List<GroupRun> startable() {
            List<GroupRun> result = new ArrayList<>();
            var iterator = pending.values().iterator();
            while (iterator.hasNext()) {
                Barrier barrier = iterator.next();
                if (reservedGroups.contains(barrier.key)
                        || intersects(barrier.residents, reservedResidents)
                        || intersects(barrier.instances, reservedInstances)) {
                    continue;
                }
                try {
                    validateBarrier(barrier);
                } catch (IllegalArgumentException invalid) {
                    iterator.remove();
                    throw invalid;
                }
                iterator.remove();
                reservedResidents.addAll(barrier.residents);
                reservedInstances.addAll(barrier.instances);
                reservedGroups.add(barrier.key);
                running.add(barrier);
                PreparedGroup prepared = new PreparedGroup(barrier);
                barrier.prepared = prepared;
                result.add(new GroupRun(barrier.key, prepared));
            }
            return result;
        }

        private static <T> boolean intersects(Set<T> first, Set<T> second) {
            Set<T> smaller = first.size() <= second.size() ? first : second;
            Set<T> larger = smaller == first ? second : first;
            for (T value : smaller) {
                if (larger.contains(value)) return true;
            }
            return false;
        }

        boolean running(Barrier barrier) { return running.contains(barrier); }
        boolean cancelled(Barrier barrier) { return barrier.cancelled; }
        int pendingCount() { return pending.size(); }
        int runningCount() { return running.size(); }
        int publishedResidentCount() { return publishedResidents.size(); }
        int publishedPlacementCount() { return publishedPlacements.size(); }

        List<GroupResident> clearSource(ResourceId source) {
            pending.keySet().removeIf(key -> key.source.equals(source));
            latestAccepted.keySet().removeIf(key -> key.source.equals(source));
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
            var placements = publishedPlacements.values().iterator();
            while (placements.hasNext()) {
                PublishedPlacement placement = placements.next();
                if (placement.id.source.equals(source)) {
                    placement.resident.placements.remove(placement);
                    placements.remove();
                }
            }
            var residents = publishedResidents.values().iterator();
            while (residents.hasNext()) {
                PublishedResidentSlot slot = residents.next();
                if (slot.id.source.equals(source)) {
                    retired.add(slot.current);
                    if (slot.previous != null) retired.add(slot.previous);
                    previousResidentSlots.remove(slot);
                    residents.remove();
                }
            }
            retired.removeIf(deferred::contains);
            return retired;
        }

        void complete(Barrier barrier, boolean success) {
            if (!running.remove(barrier)) return;
            reservedResidents.removeAll(barrier.residents);
            reservedInstances.removeAll(barrier.instances);
            reservedGroups.remove(barrier.key);
        }

        GroupResident publishedResident(ResidentId id) {
            PublishedResidentSlot slot = publishedResidents.get(id);
            return slot == null ? null : slot.current;
        }

        List<GroupResident> putPublishedResident(ResidentId id, GroupResident resident, boolean retainPrevious) {
            PublishedResidentSlot slot = publishedResidents.get(id);
            if (slot == null) {
                publishedResidents.put(id, new PublishedResidentSlot(id, resident));
                return List.of();
            }
            if (retainPrevious) {
                GroupResident retired = slot.previous;
                slot.previous = slot.current;
                slot.current = resident;
                previousResidentSlots.add(slot);
                return retired == null ? List.of() : List.of(retired);
            } else {
                GroupResident current = slot.current;
                GroupResident previous = slot.previous;
                slot.previous = null;
                slot.current = resident;
                previousResidentSlots.remove(slot);
                return previous == null ? List.of(current) : List.of(current, previous);
            }
        }

        List<GroupResident> removePublishedResident(ResidentId id) {
            PublishedResidentSlot slot = publishedResidents.get(id);
            if (slot == null) return List.of();
            if (!slot.placements.isEmpty()) {
                throw new IllegalStateException("published resident still has placements " + id);
            }
            publishedResidents.remove(id);
            previousResidentSlots.remove(slot);
            return slot.previous == null ? List.of(slot.current) : List.of(slot.current, slot.previous);
        }

        void putPublishedPlacement(InstanceId id, Placement placement) {
            PublishedResidentSlot target = publishedResidents.get(new ResidentId(id.source, placement.residentKey));
            if (target == null) throw new IllegalStateException("published placement target is absent " + id);
            PublishedPlacement published = publishedPlacements.get(id);
            if (published == null) {
                published = new PublishedPlacement(id, placement, target);
                publishedPlacements.put(id, published);
                target.placements.add(published);
                return;
            }
            if (published.resident != target) {
                published.resident.placements.remove(published);
                target.placements.add(published);
                published.resident = target;
            }
            published.placement = placement;
        }

        void removePublishedPlacement(InstanceId id) {
            PublishedPlacement published = publishedPlacements.remove(id);
            if (published != null) published.resident.placements.remove(published);
        }

        java.util.Collection<PublishedPlacement> publishedPlacements() { return publishedPlacements.values(); }

        void drainPreviousResidents(Consumer<GroupResident> consumer) {
            for (PublishedResidentSlot slot : previousResidentSlots) {
                consumer.accept(slot.previous);
                slot.previous = null;
            }
            previousResidentSlots.clear();
        }

        PublishedResidentSlot publishedSlot(ResidentId id) { return publishedResidents.get(id); }
        PublishedPlacement publishedPlacement(InstanceId id) { return publishedPlacements.get(id); }
        int previousResidentSlotCount() { return previousResidentSlots.size(); }

        /** Full index audit for focused tests; production mutation paths maintain these links directly. */
        void assertPublishedIndexConsistent() {
            for (Map.Entry<ResidentId, PublishedResidentSlot> entry : publishedResidents.entrySet()) {
                PublishedResidentSlot slot = entry.getValue();
                if (!entry.getKey().equals(slot.id)
                        || (slot.previous != null) != previousResidentSlots.contains(slot)) {
                    throw new IllegalStateException("inconsistent published resident index");
                }
            }
            for (PublishedResidentSlot slot : previousResidentSlots) {
                if (slot.previous == null || publishedResidents.get(slot.id) != slot) {
                    throw new IllegalStateException("inconsistent previous resident index");
                }
            }
            for (Map.Entry<InstanceId, PublishedPlacement> entry : publishedPlacements.entrySet()) {
                PublishedPlacement placement = entry.getValue();
                if (!entry.getKey().equals(placement.id)
                        || !placement.id.source.equals(placement.resident.id.source)
                        || !placement.placement.residentKey.equals(placement.resident.id.key)
                        || publishedResidents.get(placement.resident.id) != placement.resident
                        || !placement.resident.placements.contains(placement)) {
                    throw new IllegalStateException("inconsistent published placement index");
                }
            }
            for (PublishedResidentSlot slot : publishedResidents.values()) {
                for (PublishedPlacement placement : slot.placements) {
                    if (placement.resident != slot || publishedPlacements.get(placement.id) != placement) {
                        throw new IllegalStateException("inconsistent resident placement membership");
                    }
                }
            }
        }

        void destroyAfterDeviceIdle(Set<PreparedGroup> destroyed,
                                    java.util.function.BiConsumer<PreparedGroup, Set<PreparedGroup>> destroyPrepared) {
            for (Barrier barrier : running) {
                if (barrier.prepared != null) destroyPrepared.accept(barrier.prepared, destroyed);
            }
            Set<GroupResident> destroyedResidents = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (PublishedResidentSlot slot : publishedResidents.values()) {
                if (destroyedResidents.add(slot.current)) slot.current.destroy();
                if (slot.previous != null && destroyedResidents.add(slot.previous)) slot.previous.destroy();
            }
            publishedResidents.clear();
            publishedPlacements.clear();
            previousResidentSlots.clear();
            pending.clear();
            reservedResidents.clear();
            reservedInstances.clear();
            reservedGroups.clear();
            running.clear();
            latestAccepted.clear();
        }

    }

    private static final class Barrier {
        final GroupKey key;
        final long revision;
        final GroupDiff diff;
        final Set<ResidentId> residents;
        final Set<InstanceId> instances;
        final Consumer<PublicationAck> acknowledgment;
        final Consumer<Throwable> failureHandler;
        PreparedGroup prepared;
        boolean cancelled;

        Barrier(GroupKey key, long revision, GroupDiff diff, Consumer<PublicationAck> acknowledgment,
                Consumer<Throwable> failureHandler) {
            this.key = key; this.revision = revision; this.diff = diff; this.acknowledgment = acknowledgment;
            this.failureHandler = failureHandler;
            residents = new LinkedHashSet<>();
            diff.puts.keySet().forEach(value -> residents.add(new ResidentId(key.source, value)));
            diff.drops.forEach(value -> residents.add(new ResidentId(key.source, value)));
            diff.placements.values().forEach(value -> residents.add(new ResidentId(key.source, value.residentKey)));
            instances = new LinkedHashSet<>();
            diff.placements.keySet().forEach(value -> instances.add(new InstanceId(key.source, value)));
            diff.removes.forEach(value -> instances.add(new InstanceId(key.source, value)));
        }

        Barrier merge(Barrier newer) {
            if (!key.source.equals(newer.key.source)) throw new IllegalArgumentException("cannot merge different sources");
            Map<SceneGeometryKey, GeometryPayload> puts = new LinkedHashMap<>(diff.puts);
            Set<SceneGeometryKey> drops = new LinkedHashSet<>(diff.drops);
            newer.diff.puts.forEach((id, payload) -> { puts.put(id, payload); drops.remove(id); });
            newer.diff.drops.forEach(id -> { puts.remove(id); drops.add(id); });
            Map<SceneGeometryKey, Placement> places = new LinkedHashMap<>(diff.placements);
            Set<SceneGeometryKey> removes = new LinkedHashSet<>(diff.removes);
            newer.diff.placements.forEach((id, placement) -> { places.put(id, placement); removes.remove(id); });
            newer.diff.removes.forEach(id -> { places.remove(id); removes.add(id); });
            return new Barrier(newer.key, newer.revision,
                    new GroupDiff(Map.copyOf(puts), Set.copyOf(drops), Map.copyOf(places), Set.copyOf(removes)),
                    newer.acknowledgment != null ? newer.acknowledgment : acknowledgment,
                    newer.failureHandler != null ? newer.failureHandler : failureHandler);
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
