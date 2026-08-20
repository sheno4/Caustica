package dev.comfyfluffy.caustica.rt.geometry;

import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.api.provider.SceneMesh;
import dev.comfyfluffy.caustica.api.provider.SceneGeometryKey;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtOpacityMicromapPipeline;
import dev.comfyfluffy.caustica.rt.accel.TlasBuilder;
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
import java.util.function.Function;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static dev.comfyfluffy.caustica.rt.geometry.GeometryUpdates.*;
import static dev.comfyfluffy.caustica.rt.geometry.SceneMeshPacker.PackedInput;
import static dev.comfyfluffy.caustica.rt.geometry.SceneMeshPacker.PackedLayout;
import static dev.comfyfluffy.caustica.rt.geometry.SceneMeshPacker.Topology;

/**
 * Owns retained scene geometry, its asynchronous BLAS builds, records, and exact graphics lifetime.
 */
public final class RtSceneGeometryManager {
    public interface MaterialTables {
        long bindingTableAddress();
    }
    private static final int TABLE_RING = 4;
    private static final long MIN_BUFFER_BYTES = 256L;
    private static final int HISTORY_BYTES = 3 * 4 * Float.BYTES;

    private final Function<ResourceId, RtGeometryMaterialResolver> materialResolver;
    private MaterialTables materialTables;
    private final TlasBuilder.Ring tlasRing = new TlasBuilder.Ring();
    private final GeometryGroupScheduler groupScheduler;
    private final ConcurrentLinkedQueue<TerminalGroup> terminalGroups = new ConcurrentLinkedQueue<>();
    private final FramePublicationGate framePublicationGate = new FramePublicationGate();
    private final FailureLatch groupFailures = new FailureLatch();
    private final TableSlot[] tables = new TableSlot[TABLE_RING];
    private int tableCursor;
    private RtOpacityMicromapPipeline opacityMicromapPipeline;
    private boolean loggedOpacityMicromapBuild;

    RtSceneGeometryManager(RtGeometryMaterialResolver materialResolver, GeometryGroupScheduler groupScheduler) {
        this(ignored -> materialResolver, groupScheduler);
    }

    public RtSceneGeometryManager(Function<ResourceId, RtGeometryMaterialResolver> materialResolver) {
        this(materialResolver, new GeometryGroupScheduler());
    }

    private RtSceneGeometryManager(Function<ResourceId, RtGeometryMaterialResolver> materialResolver,
                                   GeometryGroupScheduler groupScheduler) {
        this.materialResolver = materialResolver;
        this.groupScheduler = groupScheduler;
    }

    /** Installs the immutable classifier resources for the current material epoch. */
    public void setOpacityMicromapPipeline(RtOpacityMicromapPipeline pipeline) {
        opacityMicromapPipeline = pipeline;
    }

    public void setMaterialTables(MaterialTables materialTables) {
        if (this.materialTables != null && materialTables != null) {
            throw new IllegalStateException("Material tables are already attached");
        }
        this.materialTables = materialTables;
    }

    /** Provider submissions can handle their own asynchronous failure without disabling other sources. */
    public void submit(List<Group> updates, Consumer<Publication> acknowledgment,
                       Consumer<Throwable> failureHandler) {
        RtFrameStats.FRAME.count("geometryGroupsSubmitted", updates.size());
        for (Group update : updates) {
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
        framePublicationGate.clearSource(source);
        Set<GroupResident> retired = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        retired.addAll(groupScheduler.clearSource(source));
        for (GroupResident resident : retired) {
            ctx.gpuExecutor().retireAfterGraphics(resident.graphicsUse(), resident::destroy);
        }
    }

    /** Build-ready TLAS view over the manager's published geometry snapshot. */
    public TlasBuilder.Prepared prepareTlas(GpuContext ctx, FrameUpdate update, GraphicsUse graphicsUse) {
        return TlasBuilder.prepare(ctx, update.instances, tlasRing, graphicsUse);
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
        publishReadyForFrame(ctx);
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
        completeFramePublicationBoundary();
        return update;
    }

    void completeFramePublicationBoundary() {
        framePublicationGate.frameCompleted();
    }

    void publishReadyForFrame(GpuContext ctx) {
        progress(ctx);
        try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("geometry.publishTerminal")) {
            publishTerminalGroups(ctx);
        }
        groupFailures.throwIfPresent();
    }

    /** Associates all resources published by an update with the graphics submission that traces them. */
    public void markGraphicsUse(FrameUpdate update, GraphicsUse graphicsUse) {
        update.markGraphicsUse(graphicsUse);
    }

    /** Teardown after the device is idle. */
    public void shutdown() {
        RtGeometryProfiling.resetPublications();
        framePublicationGate.clear();
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
            PreparedGroup prepared = run.prepared();
            try {
                prepareGroupCandidates(ctx, prepared);
            } catch (Throwable failure) {
                terminalGroups.add(new TerminalGroup(run.key(), prepared, failure, null));
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
                input = providerInput(prepared.key.source(), provider.mesh());
            }
            writeDynamic(ctx, candidate, input);
            candidate.vertexCount = input.positions().length / 3;
            candidate.topology = Topology.of(input);
            RtAccel.PreparedBlas operation;
            DynamicResident dynamicSource = source instanceof DynamicResident resident ? resident : null;
            boolean previousIndexed = dynamicSource != null;
            boolean retainPreviousPositions = previousIndexed && dynamicSource.topology != null
                    && dynamicSource.topology.matches(candidate.topology);
            boolean minimizeMemory = provider.buildPolicy() == GeometryUpdates.BuildPolicy.STATIC;
            boolean opacityAcceleration = minimizeMemory
                    && ctx.backend().capabilities().opacityMicromaps() && opacityMicromapPipeline != null
                    && candidate.classTriangles[RtAccel.CLASS_MASKED] > 0
                    && hasOpacityMicromapRange(input);
            if (opacityAcceleration) {
                int subdivisionLevel = 0;
                int microTriangles = 1 << (subdivisionLevel * 2);
                int bytesPerTriangle = Math.max(1, (microTriangles * 2 + 7) >>> 3);
                int maskedBase = candidate.classTriangles[RtAccel.CLASS_OPAQUE];
                RtOpacityMicromapPipeline classifier = opacityMicromapPipeline;
                RtAccel.OpacityMicromapGpuInput opacityInput = new RtAccel.OpacityMicromapGpuInput(
                        candidate.classTriangles[RtAccel.CLASS_MASKED], subdivisionLevel, bytesPerTriangle,
                        (command, dataAddress, triangleAddress, dataStride) -> classifier.record(command,
                                candidate.primitiveAddress, materialTables.bindingTableAddress(),
                                dataAddress, triangleAddress, maskedBase,
                                candidate.classTriangles[RtAccel.CLASS_MASKED], dataStride));
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
            candidates.add(new GroupCandidate(
                    new ResidentId(prepared.key.source(), entry.getKey()), candidate, source, operation,
                    !retainPreviousPositions));
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

    PackedInput providerInput(ResourceId source, SceneMesh mesh) {
        return SceneMeshPacker.pack(mesh, materialResolver.apply(source));
    }

    private static boolean hasOpacityMicromapRange(PackedInput input) {
        int first = input.classTriangles()[RtAccel.CLASS_OPAQUE];
        int end = first + input.classTriangles()[RtAccel.CLASS_MASKED];
        for (int triangle = first; triangle < end; triangle++) {
            int range = Float.floatToRawIntBits(input.primitives()[triangle * 12 + 11]);
            if ((range & RtGeometryMeshPacking.OMM_RANGE_ABSENT) == 0) return true;
        }
        return false;
    }

    private void publishTerminalGroups(GpuContext ctx) {
        int available = terminalGroups.size();
        ArrayList<TerminalGroup> deferred = null;
        try {
            for (int i = 0; i < available; i++) {
                TerminalGroup terminal = terminalGroups.poll();
                if (terminal == null) break;
                boolean cancelled = groupScheduler.cancelled(terminal.prepared.barrier);
                Throwable terminalFailure = terminal.failure;
                if (terminalFailure == null && terminal.prepared.candidates.stream()
                        .anyMatch(candidate -> !candidate.publicationState.publishable())) {
                    terminalFailure = new IllegalStateException(
                            "geometry candidate reached publication before terminal build phase");
                }
                boolean running = groupScheduler.running(terminal.prepared.barrier);
                boolean residentBlocked = running && !cancelled && terminalFailure == null
                        && framePublicationGate.blocks(terminal.prepared.candidates);
                if (deferSuccessfulTerminal(running, cancelled, terminalFailure, residentBlocked)) {
                    if (deferred == null) deferred = new ArrayList<>();
                    deferred.add(terminal);
                    continue;
                }
                if (!running || cancelled || terminalFailure != null) {
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
                    for (GroupResident retired : groupScheduler.putPublishedResident(
                            candidate.id, candidate.resident, !candidate.resetMotion)) {
                        ctx.gpuExecutor().retireAfterGraphics(retired.graphicsUse(), retired::destroy);
                    }
                }
                RtFrameStats.FRAME.count("geometryGroupsPublished", 1);
                RtFrameStats.FRAME.count("geometryPutsPublished", terminal.prepared.candidates.size());
                for (Map.Entry<SceneGeometryKey, Placement> placement : terminal.prepared.diff.placements.entrySet()) {
                    groupScheduler.putPublishedPlacement(
                            new InstanceId(terminal.prepared.key.source(), placement.getKey()), placement.getValue());
                }
                for (Map.Entry<SceneGeometryKey, PlacementUpdate> update
                        : terminal.prepared.diff.placementUpdates.entrySet()) {
                    groupScheduler.updatePublishedPlacement(
                            new InstanceId(terminal.prepared.key.source(), update.getKey()), update.getValue());
                    RtFrameStats.FRAME.count("geometryPlacementFreshnessApplied", 1);
                }
                for (SceneGeometryKey remove : terminal.prepared.diff.removes) {
                    groupScheduler.removePublishedPlacement(new InstanceId(terminal.prepared.key.source(), remove));
                }
                for (SceneGeometryKey drop : terminal.prepared.diff.drops) {
                    for (GroupResident retired : groupScheduler.removePublishedResident(
                            new ResidentId(terminal.prepared.key.source(), drop))) {
                        ctx.gpuExecutor().retireAfterGraphics(retired.graphicsUse(), retired::destroy);
                    }
                }
                framePublicationGate.published(terminal.prepared.candidates);
                groupScheduler.complete(terminal.prepared.barrier, true);
                if (terminal.prepared.barrier.acknowledgment != null) {
                    terminal.prepared.barrier.acknowledgment.accept(new Publication(terminal.prepared.key,
                            terminal.prepared.revision, terminal.prepared.barrier.operations()));
                }
            }
        } finally {
            if (deferred != null) terminalGroups.addAll(deferred);
        }
    }

    static boolean deferSuccessfulTerminal(boolean running, boolean cancelled, Throwable failure,
                                            boolean residentBlocked) {
        return running && !cancelled && failure == null && residentBlocked;
    }

    private void appendPublishedGroups(FrameUpdate update) {
        for (PublishedPlacement published : groupScheduler.publishedPlacements()) {
            PublishedResidentSlot slot = published.resident;
            Placement placement = published.placement;
            slot.current.append(update, placement, published, slot.previous);
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
        if (table == null || table.buffer.size() < bytes) {
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
        private final TlasBuilder.InstanceBatch instances;
        private final ArrayList<GroupResident> persistentUses;
        private final Set<GroupResident> historyUses;
        private final Set<GroupResident> historyRetire;
        private int count;

        private FrameUpdate(GpuContext ctx, TableSlot table, SceneOrigin origin, int placementCount) {
            this.ctx = ctx;
            this.table = table;
            this.origin = origin;
            table.beginFrame(placementCount);
            this.instances = table.instances;
            this.persistentUses = table.persistentUses;
            this.historyUses = table.historyUses;
            this.historyRetire = table.historyRetire;
        }

        private int appendRecord(long primitiveAddress, long indexAddress, long textureCoordinateAddress,
                                 long previousPositionAddress, long vertexNormalAddress, long vertexColorAddress,
                                 int triangleBase, int[] classTriangles, int semanticFlags) {
            if (classTriangles == null || classTriangles.length != RtAccel.SBT_CLASSES) {
                throw new IllegalArgumentException("missing acceleration-structure class counts");
            }
            return appendRawRecord(primitiveAddress, indexAddress, textureCoordinateAddress, previousPositionAddress,
                    vertexNormalAddress, vertexColorAddress, triangleBase, classTriangles[0],
                    triangleBase + classTriangles[0] + classTriangles[1], semanticFlags);
        }

        private int appendRawRecord(long primitiveAddress, long indexAddress, long textureCoordinateAddress,
                                    long previousPositionAddress, long vertexNormalAddress, long vertexColorAddress,
                                    int triangleBase0, int triangleBase1, int triangleBase2, int semanticFlags) {
            int record = RtGeometryAbi.checkedIndex(0, count);
            ensureDynamicCapacity(record + 1);
            long address = table.buffer.mapped() + (long) record * RtGeometryAbi.RECORD_BYTES;
            RtGeometryAbi.writeRecord(address, primitiveAddress, indexAddress, textureCoordinateAddress,
                    previousPositionAddress, vertexNormalAddress, vertexColorAddress,
                    triangleBase0, triangleBase1, triangleBase2, semanticFlags);
            count++;
            return record;
        }

        private void appendResident(DynamicResident resident, Placement placement, PublishedPlacement published,
                                    long previousPositionAddress) {
            int record = appendRecord(resident.primitiveAddress, resident.indexAddress, resident.textureCoordinateAddress,
                    previousPositionAddress, resident.vertexNormalAddress, resident.vertexColorAddress,
                    0, resident.classTriangles,
                    resident.semanticFlags);
            instances.append(placement.transform,
                    placement.translationXFor(origin), placement.translationYFor(origin), placement.translationZFor(origin),
                    resident.accel.deviceAddress, record, placement.mask, 0);
            Placement history = published.historyPlacement();
            writeHistory(table.history.mapped() + (long) record * HISTORY_BYTES, history, origin);
            published.completeFrameSnapshot();
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
            if (table.buffer.size() >= required) {
                return;
            }
            long grown = Math.max(required, table.buffer.size() + table.buffer.size() / 2L);
            GpuBuffer replacement = ctx.createBuffer(grown, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true,
                    "scene geometry table");
            GpuBuffer historyReplacement = ctx.createBuffer(Math.max(MIN_BUFFER_BYTES,
                    (grown / RtGeometryAbi.RECORD_BYTES) * HISTORY_BYTES), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    true, "scene instance history");
            int existing = RtGeometryAbi.checkedRecordCount(0, count);
            if (existing != 0) {
                MemoryUtil.memCopy(table.buffer.mapped(), replacement.mapped(),
                        (long) existing * RtGeometryAbi.RECORD_BYTES);
                MemoryUtil.memCopy(table.history.mapped(), historyReplacement.mapped(),
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
        return frame.table.buffer.deviceAddress();
    }

    /** Per-instance transform history table used by hit shaders alongside the geometry table. */
    public long instanceHistoryAddress(FrameUpdate frame) {
        return frame.table.history.deviceAddress();
    }

    static void writeHistory(long address, Placement history, SceneOrigin origin) {
        MemoryUtil.memCopy(history.transform, address);
        MemoryUtil.memPutFloat(address + 3L * Float.BYTES,
                history.transform[3] + history.translationXFor(origin));
        MemoryUtil.memPutFloat(address + 7L * Float.BYTES,
                history.transform[7] + history.translationYFor(origin));
        MemoryUtil.memPutFloat(address + 11L * Float.BYTES,
                history.transform[11] + history.translationZFor(origin));
    }

    private void writeDynamic(GpuContext ctx, DynamicResident resident, PackedInput input) {
        PackedLayout layout = PackedLayout.create(input.positions().length, input.indices().length,
                input.textureCoordinates().length, input.vertexNormals().length, input.vertexColors().length,
                input.primitives().length);
        long required = Math.max(MIN_BUFFER_BYTES, layout.totalBytes() + 15L);
        int usage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (resident.geometry == null || resident.geometry.size() < required) {
            GpuBuffer previous = resident.geometry;
            resident.geometry = ctx.createAsyncBuffer(required, usage, true, "scene dynamic geometry");
            if (previous != null) previous.destroy();
        }
        layout = layout.shifted((-resident.geometry.deviceAddress()) & 15L);
        MemoryUtil.memFloatBuffer(resident.geometry.mapped() + layout.positionOffset(), input.positions().length).put(input.positions());
        MemoryUtil.memIntBuffer(resident.geometry.mapped() + layout.indexOffset(), input.indices().length).put(input.indices());
        MemoryUtil.memFloatBuffer(resident.geometry.mapped() + layout.textureCoordinateOffset(), input.textureCoordinates().length).put(input.textureCoordinates());
        if (input.vertexNormals().length != 0) {
            MemoryUtil.memFloatBuffer(resident.geometry.mapped() + layout.vertexNormalOffset(), input.vertexNormals().length)
                    .put(input.vertexNormals());
        }
        if (input.vertexColors().length != 0) {
            MemoryUtil.memFloatBuffer(resident.geometry.mapped() + layout.vertexColorOffset(), input.vertexColors().length)
                    .put(input.vertexColors());
        }
        MemoryUtil.memFloatBuffer(resident.geometry.mapped() + layout.primitiveOffset(), input.primitives().length).put(input.primitives());
        resident.geometry.flush(layout.positionOffset(), layout.totalBytes() - layout.positionOffset());
        resident.positionAddress = resident.geometry.deviceAddress() + layout.positionOffset();
        resident.indexAddress = resident.geometry.deviceAddress() + layout.indexOffset();
        resident.textureCoordinateAddress = resident.geometry.deviceAddress() + layout.textureCoordinateOffset();
        resident.vertexNormalAddress = input.vertexNormals().length == 0 ? 0L
                : resident.geometry.deviceAddress() + layout.vertexNormalOffset();
        resident.vertexColorAddress = input.vertexColors().length == 0 ? 0L
                : resident.geometry.deviceAddress() + layout.vertexColorOffset();
        resident.primitiveAddress = resident.geometry.deviceAddress() + layout.primitiveOffset();
        resident.classTriangles = input.classTriangles().clone();
        resident.semanticFlags = input.semanticFlags();
    }

    /** Private common lifetime for every resident published through an atomic group. */
    interface GroupResident {
        TrackedGraphicsUse graphicsUse();

        void append(FrameUpdate update, Placement placement, PublishedPlacement published, GroupResident previous);

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
        long vertexNormalAddress;
        long vertexColorAddress;
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
        public void append(FrameUpdate update, Placement placement, PublishedPlacement published,
                           GroupResident previous) {
            DynamicResident previousDynamic = previous instanceof DynamicResident resident ? resident : null;
            update.appendResident(this, placement, published,
                    previousDynamic == null ? 0L : previousDynamic.positionAddress);
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

    /** Prevents a resident from being replaced twice before a completed frame can snapshot it. */
    static final class FramePublicationGate {
        private final Set<ResidentId> publishedSinceFrame = new LinkedHashSet<>();
        private boolean active;

        boolean blocks(List<GroupCandidate> candidates) {
            if (!active) return false;
            for (GroupCandidate candidate : candidates) {
                if (publishedSinceFrame.contains(candidate.id)) return true;
            }
            return false;
        }

        void published(List<GroupCandidate> candidates) {
            if (!active) return;
            for (GroupCandidate candidate : candidates) publishedSinceFrame.add(candidate.id);
        }

        boolean blocks(ResidentId resident) {
            return active && publishedSinceFrame.contains(resident);
        }

        void published(ResidentId resident) {
            if (active) publishedSinceFrame.add(resident);
        }

        void frameCompleted() {
            active = true;
            publishedSinceFrame.clear();
        }

        void clearSource(ResourceId source) {
            publishedSinceFrame.removeIf(resident -> resident.source.equals(source));
        }

        void clear() {
            active = false;
            publishedSinceFrame.clear();
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
        Placement placement;
        Placement previousPlacement;
        PublishedResidentSlot resident;

        PublishedPlacement(InstanceId id, Placement placement, PublishedResidentSlot resident) {
            this.id = id;
            this.placement = placement;
            this.resident = resident;
        }

        Placement historyPlacement() {
            return previousPlacement != null ? previousPlacement : placement;
        }

        void completeFrameSnapshot() {
            previousPlacement = placement;
        }
    }

    /** Payload-level delta captured by one atomic barrier. */
    static final class GroupDiff {
        final Map<SceneGeometryKey, GeometryPayload> puts;
        final Set<SceneGeometryKey> drops;
        final Map<SceneGeometryKey, Placement> placements;
        final Map<SceneGeometryKey, PlacementUpdate> placementUpdates;
        final Set<SceneGeometryKey> removes;

        GroupDiff(Map<SceneGeometryKey, GeometryPayload> puts, Set<SceneGeometryKey> drops,
                          Map<SceneGeometryKey, Placement> placements,
                          Map<SceneGeometryKey, PlacementUpdate> placementUpdates,
                          Set<SceneGeometryKey> removes) {
            this.puts = puts;
            this.drops = drops;
            this.placements = placements;
            this.placementUpdates = placementUpdates;
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

    static final class GroupCandidate {
        final ResidentId id;
        final GroupResident resident;
        final GroupResident source;
        final RtAccel.PreparedBlas operation;
        final boolean resetMotion;
        boolean retireSourceAfterTerminal;
        final CompactionPublicationState publicationState;

        GroupCandidate(ResidentId id, GroupResident resident, GroupResident source, RtAccel.PreparedBlas operation,
                       boolean resetMotion) {
            this.id = id;
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
            if (transform.length != 12) {
                throw new IllegalArgumentException("geometry transform must contain 12 floats");
            }
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

        float translationXFor(SceneOrigin targetOrigin) {
            return (float) (origin.x() - targetOrigin.x());
        }

        float translationYFor(SceneOrigin targetOrigin) {
            return (float) (origin.y() - targetOrigin.y());
        }

        float translationZFor(SceneOrigin targetOrigin) {
            return (float) (origin.z() - targetOrigin.z());
        }

        Placement updated(PlacementUpdate update) {
            return new Placement(residentKey, update.transform, update.mask, update.origin);
        }
    }

    static final class PlacementUpdate {
        final float[] transform;
        final int mask;
        final SceneOrigin origin;

        PlacementUpdate(float[] transform, int mask, SceneOrigin origin) {
            if (transform.length != 12) {
                throw new IllegalArgumentException("geometry transform must contain 12 floats");
            }
            this.transform = transform.clone();
            this.mask = mask;
            this.origin = origin;
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

    public static final class TableSlot {
        GpuBuffer buffer;
        GpuBuffer history;
        final TlasBuilder.InstanceBatch instances = new TlasBuilder.InstanceBatch();
        final ArrayList<GroupResident> persistentUses = new ArrayList<>();
        final Set<GroupResident> historyUses = new LinkedHashSet<>();
        final Set<GroupResident> historyRetire = new LinkedHashSet<>();
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

        TableSlot(GpuBuffer buffer, GpuBuffer history) {
            this.buffer = buffer;
            this.history = history;
        }

        void beginFrame(int placementCount) {
            instances.reset(placementCount);
            persistentUses.clear();
            persistentUses.ensureCapacity(placementCount);
            historyUses.clear();
            historyRetire.clear();
        }
    }
}
