package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;


import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.IntConsumer;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Immutable scene snapshots consumed by frame-local TLAS, shader-table, and lighting work. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final RtFramePreparation framePreparation = new RtFramePreparation();
    private final RtTraceSlotPool<TraceSlot> traceSlots;
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResource<SceneMotionHistory>> motionHistoryByScene = new IdentityHashMap<>();
    private final FrameAssembly frameAssembly = new FrameAssembly(mesh ->
            new FrameMesh(mesh, (RtPreparedMesh) SceneDirectory.preparedResource(mesh.ready())));
    private List<RetainedSceneSnapshot.Scene> contentScenes;
    private List<RetainedSceneSnapshot.Light> contentLights;
    private Map<SceneId, SceneContent> retainedContent;
    private final RtLightPageAssembly lightAssembly = new RtLightPageAssembly();
    private final Map<SceneId, LightIndexRevision> lightIndicesByScene = new IdentityHashMap<>();
    private final Map<SceneId, TracePlanCache> tracePlansByScene = new IdentityHashMap<>();
    private final Map<SceneId, RtPackedLightPages> packedLightsByScene = new IdentityHashMap<>();
    private Supplier<SharedResource<RetainedSceneSnapshot>> capture;
    private boolean closed;
    private boolean sessionClosing;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx);
        this.neeAt = new RtNeeAtBackend(ctx);
        this.traceSlots = new RtTraceSlotPool<>(slot -> ctx.deferDestroy(slot::destroy));
    }

    @Override public synchronized void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture) {
        this.capture = Objects.requireNonNull(capture);
    }

    /** Prepares the global light distribution before the stable-plane build. */
    public synchronized PreparedLighting prepareLighting(SceneId scene, LightingFrame frame,
                                                          VkCommandBuffer commandBuffer,
                                                          GraphicsUse graphicsUse) {
        FrameSceneSnapshot current = frameScene(scene, graphicsUse);
        var input = new RtNeeAtBackend.FrameInput(frame.width(), frame.height(), frame.frameIndex(),
                frame.metersPerSceneUnit(), frame.historyContinuous());
        return new PreparedLighting(neeAt.prepare(scene, current.content.lights(), input,
                commandBuffer, graphicsUse));
    }

    /** Bakes the local distribution from BuildStablePlanes linear depth and pixel motion. */
    public synchronized void bakeLocal(SceneId scene, VkCommandBuffer commandBuffer,
                                       int currentLinearDepthIndex, int currentMotionIndex) {
        neeAt.bakeLocal(scene, commandBuffer, currentLinearDepthIndex, currentMotionIndex);
    }

    public synchronized void finishLighting(SceneId scene, PreparedLighting lighting,
                                             VkCommandBuffer commandBuffer, GraphicsUse graphicsUse) {
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (lighting.delegate.scene() != scene) {
            throw new IllegalArgumentException("prepared lighting belongs to another scene");
        }
        neeAt.finish(scene, lighting.delegate, commandBuffer, graphicsUse);
    }

    /** Invalidates feedback when a prepared frame cannot reach its trace completion point. */
    public synchronized void abandonLighting(SceneId scene, PreparedLighting lighting) {
        Objects.requireNonNull(lighting, "lighting");
        if (lighting.delegate.scene() != scene) {
            throw new IllegalArgumentException("prepared lighting belongs to another scene");
        }
        neeAt.abandon(scene, lighting.delegate);
    }

    @Override
    public void prepareForSessionClose() {
        synchronized (this) {
            requireOpen();
            if (sessionClosing) return;
            sessionClosing = true;
        }
        ctx.drainAndWaitIdle();
        synchronized (this) {
            releaseTerminalFrameRoots();
        }
    }

    @Override
    public void settleFrameUses() {
        synchronized (this) {
            requireOpen();
            if (inFlightFrames.isEmpty() && motionHistoryByScene.isEmpty() && frameAssembly.previousFrameMeshes.isEmpty()) return;
        }
        ctx.drainAndWaitIdle();
        synchronized (this) {
            releaseTerminalFrameRoots();
        }
    }

    /** Immutable light/environment view for one target scene. */
    public synchronized SceneContent content(SceneId scene, GraphicsUse graphicsUse) {
        return frameScene(scene, graphicsUse).content;
    }

    /** Prepares only the TLAS belonging to {@code scene}; no implicit root-scene global is used. */
    public synchronized TlasBuilder.Prepared prepareTlas(SceneId scene, SceneOrigin origin, GraphicsUse graphicsUse) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        FrameSceneSnapshot current = frameScene(scene, graphicsUse);
        return TlasBuilder.prepare(ctx, current.instances, tlasWriter(origin), graphicsUse);
    }

    private static TlasBuilder.InstanceWriter<FrameInstanceSnapshot> tlasWriter(SceneOrigin origin) {
        return (instance, target) -> {
            target.transform().matrix().put(instance.current.transform().relativeTo(origin.x(), origin.y(), origin.z()));
            target.instanceCustomIndex(instance.geometryBase)
                    .mask(instance.current.mask())
                    .instanceShaderBindingTableRecordOffset(instance.sbtRecordOffset)
                    .flags(org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(instance.nativeInstance.mesh.blas().accel.deviceAddress.value());
        };
    }

    /** Joins TLAS packing and trace preparation before exposing frame-owned geometry for recording. */
    public synchronized PreparedWorldGeometry prepareWorldGeometry(SceneId scene, SceneOrigin origin,
                                                                    RtPipeline pipeline, GraphicsUse graphicsUse) {
        FrameSceneSnapshot current = frameScene(scene, graphicsUse);
        TlasBuilder.Reserved reserved = RtFramePreparation.measure("tlas-reserve", current.instances.size(), 0,
                () -> TlasBuilder.reserve(ctx, current.instances.size(), graphicsUse));
        TlasBuilder.Prepared[] tlas = new TlasBuilder.Prepared[1];
        PendingTrace trace;
        try (var batch = framePreparation.batch()) {
            batch.submit(RtFramePreparation.measured("tlas-pack", current.instances.size(), 0,
                    () -> tlas[0] = TlasBuilder.pack(reserved, current.instances, tlasWriter(origin))));
            trace = prepareTraceGeometry(scene, current, origin, pipeline, graphicsUse);
        }
        return new PreparedWorldGeometry(tlas[0], trace);
    }

    public synchronized PreparedTrace finishTrace(PreparedWorldGeometry geometry, PreparedLighting lighting) {
        return finishTrace(geometry.trace, geometry.tlas.accel.handle, lighting.delegate);
    }

    /** Packs the GeometryIndex-addressed records for one scene in the same order as its TLAS hit bases. */
    public synchronized ByteBuffer geometryRecords(SceneId scene, SceneOrigin origin,
                                                    GraphicsUse graphicsUse) {
        Objects.requireNonNull(origin, "origin");
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (FrameInstanceSnapshot instance : frameScene(scene, graphicsUse).instances) {
            records.addAll(RtRetainedGeometryPlan.records(instance.resolvedMesh,
                    instance.resolvedPlacement, instance.current.transform(),
                    instance.nativeInstance.mesh.logical.build().positions()));
        }
        return RtRetainedGeometryPlan.pack(records, origin);
    }

    /** Hit-group handle selection in exact {@code geometry * rayType} SBT order. */
    public synchronized List<RtRetainedGeometryPlan.HitGroup> hitGroups(
            SceneId scene, GraphicsUse graphicsUse) {
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (FrameInstanceSnapshot instance : frameScene(scene, graphicsUse).instances) {
            records.addAll(RtRetainedGeometryPlan.records(instance.resolvedMesh,
                    instance.resolvedPlacement, instance.current.transform(),
                    instance.nativeInstance.mesh.logical.build().positions()));
        }
        return RtRetainedGeometryPlan.hitGroups(records);
    }

    /** Uploads this frame's rebased geometry records and pipeline-specific hit SBT into this scene's buffers. */
    public synchronized PreparedTrace prepareTrace(SceneId scene, SceneOrigin origin, RtPipeline pipeline,
                                                   long tlasHandle, GraphicsUse graphicsUse) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        FrameSceneSnapshot current = frameScene(scene, graphicsUse);
        PendingTrace trace = prepareTraceGeometry(scene, current, origin, pipeline, graphicsUse);
        RtNeeAtBackend.Prepared lighting = neeAt.active(scene);
        if (lighting == null) throw new IllegalStateException("prepareLighting must precede prepareTrace");
        return finishTrace(trace, tlasHandle, lighting);
    }

    private PendingTrace prepareTraceGeometry(SceneId scene, FrameSceneSnapshot current, SceneOrigin origin,
                                              RtPipeline pipeline, GraphicsUse graphicsUse) {
        List<SceneLight> sceneLights = current.content.lights();
        LightIndexRevision indexed = lightIndicesByScene.get(scene);
        boolean rebuildLightIndices = indexed == null || indexed.lights != sceneLights;
        int geometryCount = current.instances.isEmpty() ? 0 : current.instances.getLast().geometryBase
                + current.instances.getLast().resolvedMesh.geometries().size();
        List<List<FrameInstanceSnapshot>> pageInputs = SnapshotList.pagesOf(current.instances);
        LightIndexRevision[] preparedIndex = {indexed};
        TracePagePlan[] pages;
        try (var batch = framePreparation.batch()) {
            if (rebuildLightIndices) {
                batch.submit(RtFramePreparation.measured("light-index", 0, sceneLights.size(),
                        () -> preparedIndex[0] = indexLights(sceneLights)));
            }
            pages = tracePlansByScene.computeIfAbsent(scene, ignored -> new TracePlanCache())
                    .resolve(pageInputs, framePreparation);
        }
        LightIndexRevision lightIndexRevision = preparedIndex[0];
        if (rebuildLightIndices) lightIndicesByScene.put(scene, lightIndexRevision);
        Long2IntMap lightIndices = lightIndexRevision.indices;
        int emitterBytes = 0;
        int[] emitterBases = new int[pages.length];
        for (int index = 0; index < pages.length; index++) {
            emitterBases[index] = emitterBytes;
            emitterBytes = Math.addExact(emitterBytes, pages[index].emitterBytes);
        }
        int geometryBytes = Math.multiplyExact(geometryCount, RtRetainedGeometryPlan.RECORD_BYTES);
        int hitBytes = Math.multiplyExact(Math.multiplyExact(geometryCount,
                RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY), pipeline.retainedHitRecordStride());
        int lightBytes = Math.multiplyExact(sceneLights.size(), RtRetainedLightPlan.RECORD_BYTES);
        TraceSlot slot = acquireTraceSlot(geometryBytes, hitBytes, lightBytes, emitterBytes,
                pipeline, graphicsUse);
        List<TracePageWork> writes = new ArrayList<>();
        boolean flushGeometry = false, flushHits = false, flushEmitters = false;
        for (int index = 0; index < pages.length; index++) {
            TracePagePlan page = pages[index];
            TracePageResidency residency = slot.page(index);
            int emitterBase = emitterBases[index];
            long emitterAddress = page.emitterBytes == 0 ? 0L
                    : slot.emitters.deviceAddress().addBytes(emitterBase).value();
            int flags = 0;
            if (!residency.hasGeometry(page.identity, page.geometryBase, origin, emitterAddress)) {
                flags |= TracePageWork.GEOMETRY;
                flushGeometry = true;
            }
            if (!residency.hasHits(page.identity, page.hitBase(pipeline), pipeline)) {
                flags |= TracePageWork.HITS;
                flushHits = true;
            }
            if (!residency.hasEmitters(page.identity, emitterBase, lightIndexRevision)) {
                flags |= TracePageWork.EMITTERS;
                flushEmitters |= page.emitterBytes > 0;
            }
            if (flags != 0) writes.add(new TracePageWork(page, residency, slot, origin,
                    emitterBase, pipeline, lightIndexRevision, lightIndices, flags));
        }
        slot.trimPages(pages.length);
        if (!writes.isEmpty()) {
            List<List<TracePageWork>> writeChunks = RtFramePreparation.chunks(writes, TracePageWork::work);
            framePreparation.run("pack", writeChunks,
                    chunk -> chunk.stream().mapToInt(work -> work.page.instances.size()).sum(),
                    chunk -> chunk.forEach(TracePageWork::pack));
        }
        if (flushGeometry && geometryBytes > 0) slot.geometry.flush(0L, geometryBytes);
        if (flushHits && hitBytes > 0) slot.hits.flush(0L, hitBytes);
        if (flushEmitters && emitterBytes > 0) slot.emitters.flush(0L, emitterBytes);
        writes.forEach(TracePageWork::commit);
        BitSet linked = new BitSet(sceneLights.size());
        for (int index = 0; index < pages.length; index++) slot.page(index).addLinkedEmittersTo(linked);
        List<ByteBuffer> lightPages = packedLightsByScene.computeIfAbsent(scene, ignored -> new RtPackedLightPages())
                .resolve(sceneLights, origin, linked, framePreparation);
        List<ByteBuffer> previousLightPages = slot.lightPages;
        slot.lightPages = List.of();
        int lightOffset = 0, previousLightOffset = 0;
        boolean copiedLights = false;
        for (int index = 0; index < lightPages.size(); index++) {
            ByteBuffer source = lightPages.get(index);
            ByteBuffer previous = index < previousLightPages.size() ? previousLightPages.get(index) : null;
            if (source != previous || lightOffset != previousLightOffset) {
                MemoryUtil.memByteBuffer(slot.lights.mapped() + lightOffset, source.remaining())
                        .put(0, source, source.position(), source.remaining());
                copiedLights = true;
            }
            lightOffset += source.remaining();
            if (previous != null) previousLightOffset += previous.remaining();
        }
        if (copiedLights) slot.lights.flush(0L, lightBytes);
        slot.lightPages = lightPages;
        RtPipeline.HitTable hitTable = hitBytes > 0 ? new RtPipeline.HitTable(
                new VulkanDeviceAddressRange(slot.hits.deviceAddress(), hitBytes),
                pipeline.retainedHitRecordStride()) : null;
        return new PendingTrace(current, slot, hitTable);
    }

    private PreparedTrace finishTrace(PendingTrace trace, long tlasHandle, RtNeeAtBackend.Prepared lighting) {
        TraceSlot slot = trace.slot;
        ctx.descriptorHeap().writer().writeAccelerationStructure(slot.tlasDescriptor, 0, tlasHandle);
        lighting.bindLightTable(slot.lights.deviceAddress());
        PreparedTrace prepared = new PreparedTrace(slot.geometry.deviceAddress(), lighting.stateAddress(),
                slot.tlasDescriptor.firstIndex().value(), trace.hitTable);
        trace.current.markTraced();
        return prepared;
    }

    private record PendingTrace(FrameSceneSnapshot current, TraceSlot slot, RtPipeline.HitTable hitTable) {}

    /** Borrowed geometry ready for TLAS recording, with trace bindings finalized after lighting preparation. */
    public static final class PreparedWorldGeometry {
        private final TlasBuilder.Prepared tlas;
        private final PendingTrace trace;

        private PreparedWorldGeometry(TlasBuilder.Prepared tlas, PendingTrace trace) {
            this.tlas = tlas;
            this.trace = trace;
        }

        public TlasBuilder.Prepared tlas() { return tlas; }
    }

    static int emitterIndex(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                            int primitive, Long2IntMap lightIndices) {
        for (RetainedSceneSnapshot.PrimitiveEmitter range : ranges) {
            if (primitive < range.firstPrimitive()) break;
            if (primitive < range.firstPrimitive() + range.primitiveCount()) {
                return lightIndices.getOrDefault(range.lightIdentity(), -1);
            }
        }
        return -1;
    }

    static boolean hasEmitterMapping(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                     int firstPrimitive, int primitiveCount) {
        long end = Math.addExact((long) firstPrimitive, primitiveCount);
        int index = firstEmitterRange(ranges, firstPrimitive);
        return primitiveCount > 0 && index < ranges.size() && ranges.get(index).firstPrimitive() < end;
    }

    /** Sorted, disjoint ranges allow skipping every emitter before this geometry in logarithmic time. */
    private static int firstEmitterRange(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges, int primitive) {
        int low = 0, high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            var range = ranges.get(middle);
            if ((long) range.firstPrimitive() + range.primitiveCount() <= primitive) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Packs a consecutive primitive range in one forward pass over the sorted, disjoint emitter ranges. */
    static void putEmitterIndices(ByteBuffer output, int firstPrimitive, int primitiveCount,
                                  List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                  Long2IntMap lightIndices, boolean[] linkedEmitters) {
        putEmitterIndices(output, firstPrimitive, primitiveCount, ranges, lightIndices,
                index -> linkedEmitters[index] = true);
    }

    static void putEmitterIndices(ByteBuffer output, int firstPrimitive, int primitiveCount,
                                  List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                  Long2IntMap lightIndices, IntConsumer markLinked) {
        int primitive = firstPrimitive;
        int end = Math.addExact(firstPrimitive, primitiveCount);
        for (int index = firstEmitterRange(ranges, firstPrimitive); index < ranges.size(); index++) {
            var range = ranges.get(index);
            if (range.firstPrimitive() >= end) break;
            int rangeEnd = (int) Math.min((long) end, (long) range.firstPrimitive() + range.primitiveCount());
            if (rangeEnd <= primitive) continue;
            while (primitive < range.firstPrimitive()) {
                output.putInt(-1);
                primitive++;
            }
            int dense = lightIndices.getOrDefault(range.lightIdentity(), -1);
            if (dense >= 0) markLinked.accept(dense);
            while (primitive < rangeEnd) {
                output.putInt(dense);
                primitive++;
            }
        }
        while (primitive < end) {
            output.putInt(-1);
            primitive++;
        }
    }

    /** Releases all native state after the GPU executor has stopped and the device has been made idle. */
    public synchronized void shutdownAfterDeviceIdle() {
        if (closed) return;
        closed = true;
        framePreparation.close();
        releaseTerminalFrameRoots();
        capture = null;
        neeAt.destroyAfterDeviceIdle();
        traceSlots.close();
    }

    private void releaseTerminalFrameRoots() {
        frameAssembly.clear();
        contentScenes = null;
        contentLights = null;
        retainedContent = null;
        lightAssembly.clear();
        lightIndicesByScene.clear();
        tracePlansByScene.clear();
        packedLightsByScene.clear();
        releaseTerminalFrameRoots(inFlightFrames, motionHistoryByScene);
    }

    static void releaseTerminalFrameRoots(Map<?, ? extends AutoCloseable> frames,
                                          Map<?, ? extends AutoCloseable> histories) {
        List<AutoCloseable> roots = new ArrayList<>(frames.size() + histories.size());
        roots.addAll(frames.values());
        roots.addAll(histories.values());
        frames.clear();
        histories.clear();
        closeAll(roots, null);
    }

    static Map<SceneId, SceneContent> assembleContent(
            List<RetainedSceneSnapshot.Scene> scenes, List<RetainedSceneSnapshot.Light> lights) {
        return new RtLightPageAssembly().resolve(scenes, lights);
    }

    private void retainRenderedScenes(java.util.Set<SceneId> retained) {
        lightIndicesByScene.keySet().retainAll(retained);
        tracePlansByScene.keySet().retainAll(retained);
        packedLightsByScene.keySet().retainAll(retained);
        List<SharedResource<SceneMotionHistory>> removed = new ArrayList<>();
        motionHistoryByScene.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            removed.add(entry.getValue());
            return true;
        });
        closeAll(removed, null);
    }

    /** Capture retained scene revisions independently of command recording. */
    public synchronized SharedResource<RetainedSceneSnapshot> captureSnapshot() {
        requireOpen();
        return capture.get();
    }

    /** Attach captured inputs to one execution; later scene edits cannot change this frame. */
    public synchronized void beginFrame(SharedResource<RetainedSceneSnapshot> root, GraphicsUse graphicsUse) {
        requireOpen();
        FrameSnapshot frame = new FrameSnapshot(graphicsUse, root.retain());
        inFlightFrames.put(graphicsUse, frame);
        try {
            graphicsUse.whenSubmitted(frame::accept);
            graphicsUse.keepAlive(frame);
        } catch (Throwable failure) {
            frame.close();
            throw failure;
        }
    }

    private FrameSnapshot frameLease(SceneId scene, GraphicsUse graphicsUse) {
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        requireOpen();
        FrameSnapshot frame = Objects.requireNonNull(inFlightFrames.get(graphicsUse), "frame was not captured");
        if (!frame.currentContent.containsKey(scene)) {
            throw new IllegalArgumentException("scene is not in the frame's scene revision");
        }
        return frame;
    }

    static ResolvedFrameInput resolveFrameInput(RetainedSceneSnapshot.Mesh mesh,
                                               RetainedSceneSnapshot.Instance instance) {
        return new ResolvedFrameInput(resolveMesh(mesh),
                new RtRetainedGeometryPlan.ResolvedPlacement(instance.transform(), instance.instanceData().bits()));
    }

    private static RtRetainedGeometryPlan.ResolvedMesh resolveMesh(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        var geometries = new ArrayList<RtRetainedGeometryPlan.ResolvedGeometry>(build.geometries().size());
        for (int index = 0; index < build.geometries().size(); index++) {
            MeshBuild.Geometry<?> geometry = build.geometries().get(index);
            RetainedSceneSnapshot.GeometryPrograms programs = mesh.geometryPrograms().get(index);
            geometries.add(new RtRetainedGeometryPlan.ResolvedGeometry(geometry,
                    programs.surfaceImplementation(), programs.volumeImplementation(),
                    geometry.surface() == null ? 0L : geometry.surface().bindingData().bits(),
                    geometry.volume() == null ? 0L : geometry.volume().bindingData().bits()));
        }
        return new RtRetainedGeometryPlan.ResolvedMesh(build, geometries);
    }

    /** Reuses CPU values by immutable page identity; consuming frames retain their own scene roots. */
    static final class FrameAssembly {
        private final Map<Long, FrameMesh> previousFrameMeshes = new HashMap<>();
        private Map<List<RetainedSceneSnapshot.Mesh>, Boolean> previousMeshPages = new IdentityHashMap<>();
        private Map<List<RetainedSceneSnapshot.Instance>, Map<SceneId, InstancePage>> previousInstancePages = new IdentityHashMap<>();
        private List<RetainedSceneSnapshot.Mesh> assembledMeshes;
        private List<RetainedSceneSnapshot.Instance> assembledInstances;
        private List<RetainedSceneSnapshot.Scene> assembledScenes;
        private Map<SceneId, List<InstancePage>> assembledPages;
        private final java.util.function.Function<RetainedSceneSnapshot.Mesh, FrameMesh> resolveMesh;

        FrameAssembly(java.util.function.Function<RetainedSceneSnapshot.Mesh, FrameMesh> resolveMesh) {
            this.resolveMesh = resolveMesh;
        }

        void clear() {
            previousFrameMeshes.clear();
            previousMeshPages.clear();
            previousInstancePages.clear();
            assembledMeshes = null;
            assembledInstances = null;
            assembledScenes = null;
            assembledPages = null;
        }

        Map<SceneId, List<InstancePage>> resolve(RetainedSceneSnapshot snapshot) {
            boolean replacedPrograms = false;
            if (assembledMeshes != snapshot.meshes()) {
                var nextMeshPages = new IdentityHashMap<List<RetainedSceneSnapshot.Mesh>, Boolean>();
                var refreshedMeshes = new java.util.HashSet<Long>();
                for (var page : SnapshotList.pagesOf(snapshot.meshes())) {
                    nextMeshPages.put(page, Boolean.TRUE);
                    if (previousMeshPages.containsKey(page)) continue;
                    for (var mesh : page) {
                        refreshedMeshes.add(mesh.identity());
                        var previous = previousFrameMeshes.get(mesh.identity());
                        if (previous != null && previous.logical == mesh) continue;
                        if (previous != null) replacedPrograms = true;
                        previousFrameMeshes.put(mesh.identity(), resolveMesh.apply(mesh));
                    }
                }
                for (var page : previousMeshPages.keySet()) {
                    if (nextMeshPages.containsKey(page)) continue;
                    for (var mesh : page) {
                        var current = previousFrameMeshes.get(mesh.identity());
                        if (!refreshedMeshes.contains(mesh.identity()) && current != null && current.logical == mesh) {
                            previousFrameMeshes.remove(mesh.identity());
                        }
                    }
                }
                previousMeshPages = nextMeshPages;
                assembledMeshes = snapshot.meshes();
            }
            if (replacedPrograms) previousInstancePages.clear();
            if (!replacedPrograms && assembledInstances == snapshot.instances() && assembledScenes == snapshot.scenes()) {
                return assembledPages;
            }
            var nextPages = new IdentityHashMap<List<RetainedSceneSnapshot.Instance>, Map<SceneId, InstancePage>>();
            Map<SceneId, List<InstancePage>> resolved = new IdentityHashMap<>();
            Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
            for (var scene : snapshot.scenes()) {
                resolved.put(scene.id(), new ArrayList<>());
                geometryBases.put(scene.id(), 0);
            }
            for (var source : SnapshotList.pagesOf(snapshot.instances())) {
                var previous = previousInstancePages.get(source);
                Map<SceneId, InstancePage> translated = new IdentityHashMap<>();
                if (previous != null) {
                    previous.forEach((scene, page) -> {
                        int base = geometryBases.get(scene);
                        var current = page.geometryBase == base ? page : page.rebase(base);
                        translated.put(scene, current);
                        resolved.get(scene).add(current);
                        geometryBases.put(scene, base + current.geometryCount);
                    });
                } else {
                    Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
                    for (var instance : source) {
                        instances.computeIfAbsent(instance.scene(), ignored -> new ArrayList<>()).add(
                                new NativeInstance(instance, previousFrameMeshes.get(instance.meshIdentity()), instance.placementOrdinal()));
                    }
                    instances.forEach((scene, values) -> {
                        int base = geometryBases.get(scene);
                        var page = new InstancePage(values, base);
                        translated.put(scene, page);
                        resolved.get(scene).add(page);
                        geometryBases.put(scene, base + page.geometryCount);
                    });
                }
                nextPages.put(source, translated);
            }
            resolved.replaceAll((scene, pages) -> List.copyOf(pages));
            previousInstancePages = nextPages;
            assembledInstances = snapshot.instances();
            assembledScenes = snapshot.scenes();
            assembledPages = Collections.unmodifiableMap(resolved);
            return assembledPages;
        }

    }

    private FrameSceneSnapshot frameScene(SceneId scene, GraphicsUse graphicsUse) {
        return frameLease(scene, graphicsUse).scene(scene);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
    }

    static void closeAll(Iterable<? extends AutoCloseable> closeables, Throwable failure) {
        Throwable combined = failure;
        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Throwable closeFailure) {
                if (combined == null) combined = closeFailure;
                else if (combined != closeFailure) combined.addSuppressed(closeFailure);
            }
        }
        if (failure == null) throwFailure(combined, "retained scene resource release failed");
    }

    static void suppressCleanupFailure(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    private static void throwFailure(Throwable failure, String message) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(message, failure);
    }

    private final class FrameSnapshot implements AutoCloseable {
        private final GraphicsUse graphicsUse;
        private SharedResource<RetainedSceneSnapshot> root;
        private final Map<SceneId, List<InstancePage>> currentInstances;
        private final Map<SceneId, SceneContent> currentContent;
        private final Map<SceneId, FrameSceneSnapshot> scenes = new IdentityHashMap<>();

        FrameSnapshot(GraphicsUse graphicsUse, SharedResource<RetainedSceneSnapshot> root) {
            this.graphicsUse = graphicsUse;
            this.root = root;
            try {
                RetainedSceneSnapshot snapshot = root.get();
                if (contentScenes != snapshot.scenes() || contentLights != snapshot.lights()) {
                    retainedContent = lightAssembly.resolve(snapshot.scenes(), snapshot.lights());
                    contentScenes = snapshot.scenes();
                    contentLights = snapshot.lights();
                }
                currentContent = retainedContent;
                currentInstances = frameAssembly.resolve(snapshot);
                neeAt.retainScenes(currentContent.keySet());
                retainRenderedScenes(currentContent.keySet());
            } catch (Throwable failure) {
                this.root = null;
                suppressCleanupFailure(failure, root::close);
                throw failure;
            }
        }

        FrameSceneSnapshot scene(SceneId scene) {
            FrameSceneSnapshot existing = scenes.get(scene);
            if (existing != null) return existing;
            SharedResource<SceneMotionHistory> historyLease = motionHistoryByScene.get(scene);
            SharedResource<SceneMotionHistory> retainedHistory = historyLease == null
                    ? null : historyLease.retain();
            try {
                SceneMotionHistory history = retainedHistory == null ? null : retainedHistory.get();
                var pages = new ArrayList<List<FrameInstanceSnapshot>>();
                for (var page : currentInstances.get(scene)) pages.add(page.frame(history));
                FrameSceneSnapshot created = new FrameSceneSnapshot(
                        currentContent.get(scene), SnapshotList.ofPages(pages), retainedHistory);
                retainedHistory = null;
                scenes.put(scene, created);
                return created;
            } catch (Throwable failure) {
                if (retainedHistory != null) suppressCleanupFailure(failure, retainedHistory::close);
                throw failure;
            }
        }

        void accept() {
            List<SharedResource<SceneMotionHistory>> displaced = new ArrayList<>();
            synchronized (RtRetainedSceneBackend.this) {
                if (root == null) return;
                for (Map.Entry<SceneId, FrameSceneSnapshot> entry : scenes.entrySet()) {
                    if (!entry.getValue().traced()) continue;
                    SharedResource<RetainedSceneSnapshot> historyRoot = root.retain();
                    SharedResource<SceneMotionHistory> replacement = null;
                    try {
                        SceneMotionHistory history = new SceneMotionHistory(currentInstances.get(entry.getKey()),
                                historyRoot);
                        replacement = SharedResource.owned(history, SceneMotionHistory::close);
                        historyRoot = null;
                    } finally {
                        if (historyRoot != null) historyRoot.close();
                    }
                    SharedResource<SceneMotionHistory> previous =
                            motionHistoryByScene.put(entry.getKey(), replacement);
                    if (previous != null) displaced.add(previous);
                }
            }
            closeAll(displaced, null);
        }

        @Override
        public void close() {
            SharedResource<RetainedSceneSnapshot> released;
            List<FrameSceneSnapshot> releasedScenes;
            synchronized (RtRetainedSceneBackend.this) {
                if (root == null) return;
                inFlightFrames.remove(graphicsUse, this);
                released = root;
                root = null;
                releasedScenes = List.copyOf(scenes.values());
                scenes.clear();
            }
            Throwable failure = null;
            try {
                released.close();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            closeAll(releasedScenes, failure);
            throwFailure(failure, "frame snapshot release failed");
        }
    }

    private static final class FrameSceneSnapshot implements AutoCloseable {
        final SceneContent content;
        final List<FrameInstanceSnapshot> instances;
        final SharedResource<SceneMotionHistory> previousHistory;
        private boolean traced;

        FrameSceneSnapshot(SceneContent content, List<FrameInstanceSnapshot> instances,
                           SharedResource<SceneMotionHistory> previousHistory) {
            this.content = content;
            this.instances = instances;
            this.previousHistory = previousHistory;
        }

        void markTraced() { traced = true; }

        boolean traced() { return traced; }

        @Override public void close() {
            if (previousHistory != null) previousHistory.close();
        }
    }

    /** CPU plans follow immutable snapshot-page identity and borrow resources from the consuming frame. */
    static final class TracePlanCache {
        private IdentityHashMap<List<FrameInstanceSnapshot>, TracePagePlan> plans = new IdentityHashMap<>();

        TracePagePlan[] resolve(List<List<FrameInstanceSnapshot>> pages, RtFramePreparation preparation) {
            TracePagePlan[] resolved = new TracePagePlan[pages.size()];
            var missing = new ArrayList<TracePlanRequest>();
            for (int index = 0; index < pages.size(); index++) {
                List<FrameInstanceSnapshot> page = pages.get(index);
                TracePagePlan plan = plans.get(page);
                if (plan == null) missing.add(new TracePlanRequest(index, page));
                else resolved[index] = plan;
            }
            if (!missing.isEmpty()) {
                List<List<TracePlanRequest>> chunks = RtFramePreparation.chunks(missing, TracePlanRequest::work);
                preparation.run("plan", chunks,
                        chunk -> chunk.stream().mapToInt(TracePlanRequest::work).sum(),
                        chunk -> chunk.forEach(request -> resolved[request.index] =
                                new TracePagePlan(request.instances)));
                for (TracePlanRequest request : missing) plans.put(request.instances, resolved[request.index]);
            }
            if (plans.size() > pages.size() * 2 + 64) {
                var retained = new IdentityHashMap<List<FrameInstanceSnapshot>, TracePagePlan>();
                for (TracePagePlan plan : resolved) retained.put(plan.identity, plan);
                plans = retained;
            }
            return resolved;
        }
    }

    private record TracePlanRequest(int index, List<FrameInstanceSnapshot> instances) {
        int work() {
            int work = 0;
            for (FrameInstanceSnapshot instance : instances) {
                work = Math.addExact(work, Math.max(1, instance.geometryRecords.size()));
            }
            return Math.max(1, work);
        }
    }

    /** Immutable geometry, emitter layout, and pipeline SBT data for one motion-resolved page. */
    static final class TracePagePlan {
        final List<FrameInstanceSnapshot> identity;
        final List<FrameInstanceSnapshot> instances;
        final int geometryBase;
        final List<RtRetainedGeometryPlan.GeometryRecord> records;
        final List<EmitterSpan> emitterSpans;
        final int emitterBytes;
        private final List<RtRetainedGeometryPlan.HitGroup> hitGroups;
        private final IdentityHashMap<RtPipeline, ByteBuffer> hitsByPipeline = new IdentityHashMap<>();

        TracePagePlan(List<FrameInstanceSnapshot> instances) {
            identity = instances;
            this.instances = instances;
            geometryBase = instances.getFirst().geometryBase;
            int geometryCount = instances.getLast().geometryBase
                    + instances.getLast().resolvedMesh.geometries().size() - geometryBase;
            var pageRecords = new ArrayList<RtRetainedGeometryPlan.GeometryRecord>(geometryCount);
            var spans = new ArrayList<EmitterSpan>();
            int bytes = 0;
            for (FrameInstanceSnapshot frameInstance : instances) {
                NativeInstance instance = frameInstance.nativeInstance;
                pageRecords.addAll(frameInstance.geometryRecords);
                List<? extends MeshBuild.Geometry<?>> geometries = instance.mesh.logical.build().geometries();
                for (int index = 0; index < geometries.size(); index++) {
                    MeshBuild.Geometry<?> geometry = geometries.get(index);
                    if (!hasEmitterMapping(instance.logical.primitiveEmitters(), geometry.firstIndex() / 3,
                            geometry.triangleCount())) continue;
                    spans.add(new EmitterSpan(frameInstance.geometryBase - geometryBase + index, bytes,
                            geometry.firstIndex() / 3, geometry.triangleCount(), instance.logical.primitiveEmitters()));
                    bytes = Math.addExact(bytes, Math.multiplyExact(geometry.triangleCount(), Integer.BYTES));
                }
            }
            records = List.copyOf(pageRecords);
            emitterSpans = List.copyOf(spans);
            emitterBytes = bytes;
            hitGroups = RtRetainedGeometryPlan.hitGroups(records);
        }

        int hitBase(RtPipeline pipeline) {
            return Math.multiplyExact(Math.multiplyExact(geometryBase,
                    RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY), pipeline.retainedHitRecordStride());
        }

        private ByteBuffer hits(RtPipeline pipeline) {
            ByteBuffer hits = hitsByPipeline.get(pipeline);
            if (hits == null) {
                hits = pipeline.retainedHitRecords(hitGroups);
                hitsByPipeline.put(pipeline, hits);
            }
            return hits;
        }

        void packGeometry(TraceSlot slot, SceneOrigin origin, int emitterBase) {
            List<RtRetainedGeometryPlan.GeometryRecord> output = records;
            if (!emitterSpans.isEmpty()) {
                var addressed = new ArrayList<>(records);
                VulkanDeviceAddress emitters = slot.emitters.deviceAddress().addBytes(emitterBase);
                for (EmitterSpan span : emitterSpans) {
                    addressed.set(span.record, addressed.get(span.record)
                            .withEmitterIndex(emitters.addBytes(span.byteOffset), 0));
                }
                output = addressed;
            }
            int bytes = Math.multiplyExact(records.size(), RtRetainedGeometryPlan.RECORD_BYTES);
            ByteBuffer target = MemoryUtil.memByteBuffer(slot.geometry.mapped()
                    + (long) geometryBase * RtRetainedGeometryPlan.RECORD_BYTES, bytes)
                    .order(ByteOrder.nativeOrder());
            RtRetainedGeometryPlan.packInto(target, output, origin);
        }

        void packHits(TraceSlot slot, RtPipeline pipeline) {
            ByteBuffer source = hits(pipeline);
            ByteBuffer target = MemoryUtil.memByteBuffer(slot.hits.mapped() + hitBase(pipeline), source.remaining());
            target.put(0, source, source.position(), source.remaining());
        }

        void packEmitters(TraceSlot slot, int emitterBase, Long2IntMap lightIndices,
                          IntSet linkedEmitters) {
            linkedEmitters.clear();
            if (emitterBytes == 0) return;
            ByteBuffer target = MemoryUtil.memByteBuffer(slot.emitters.mapped() + emitterBase, emitterBytes)
                    .order(ByteOrder.nativeOrder());
            for (EmitterSpan span : emitterSpans) {
                target.position(span.byteOffset);
                putEmitterIndices(target, span.firstPrimitive, span.primitiveCount,
                        span.ranges, lightIndices, linkedEmitters::add);
            }
        }
    }

    private record EmitterSpan(int record, int byteOffset, int firstPrimitive, int primitiveCount,
                               List<RetainedSceneSnapshot.PrimitiveEmitter> ranges) { }

    /** Writes only the page regions whose contents differ from this completed slot. */
    private static final class TracePageWork {
        static final int GEOMETRY = 1;
        static final int HITS = 2;
        static final int EMITTERS = 4;
        final TracePagePlan page;
        final TracePageResidency residency;
        final TraceSlot slot;
        final SceneOrigin origin;
        final int emitterBase;
        final RtPipeline pipeline;
        final LightIndexRevision lightIndexRevision;
        final Long2IntMap lightIndices;
        final int flags;
        final IntSet linkedEmitters;

        TracePageWork(TracePagePlan page, TracePageResidency residency, TraceSlot slot, SceneOrigin origin,
                      int emitterBase, RtPipeline pipeline, LightIndexRevision lightIndexRevision,
                      Long2IntMap lightIndices, int flags) {
            this.page = page;
            this.residency = residency;
            this.slot = slot;
            this.origin = origin;
            this.emitterBase = emitterBase;
            this.pipeline = pipeline;
            this.lightIndexRevision = lightIndexRevision;
            this.lightIndices = lightIndices;
            this.flags = flags;
            linkedEmitters = (flags & EMITTERS) == 0 ? null : new IntOpenHashSet();
        }

        void pack() {
            if ((flags & GEOMETRY) != 0) {
                page.packGeometry(slot, origin, emitterBase);
            }
            if ((flags & HITS) != 0) {
                page.packHits(slot, pipeline);
            }
            if ((flags & EMITTERS) != 0) {
                page.packEmitters(slot, emitterBase, lightIndices, linkedEmitters);
            }
        }

        void commit() {
            long emitterAddress = page.emitterBytes == 0 ? 0L
                    : slot.emitters.deviceAddress().addBytes(emitterBase).value();
            if ((flags & GEOMETRY) != 0) {
                residency.geometryWritten(page.identity, page.geometryBase, origin, emitterAddress);
            }
            if ((flags & HITS) != 0) {
                residency.hitsWritten(page.identity, page.hitBase(pipeline), pipeline);
            }
            if ((flags & EMITTERS) != 0) {
                residency.linkedEmittersWritten(linkedEmitters);
                residency.emittersWritten(page.identity, emitterBase, lightIndexRevision);
            }
        }

        int work() {
            return Math.max(1, Math.addExact(page.records.size(), page.emitterBytes / Integer.BYTES));
        }

    }

    /** Independent residency stamps keep light-index edits from invalidating geometry or the SBT. */
    static final class TracePageResidency {
        private Object geometryPage;
        private int geometryBase;
        private SceneOrigin geometryOrigin;
        private long geometryEmitterAddress;
        private Object hitPage;
        private int hitBase;
        private Object hitPipeline;
        private Object emitterPage;
        private int emitterBase;
        private Object lightIndexRevision;
        // Dense indices can be far apart even when this page links only a handful of lights.
        int[] linkedEmitters = new int[0];

        void linkedEmittersWritten(IntSet indices) {
            linkedEmitters = indices.toIntArray();
        }

        void addLinkedEmittersTo(BitSet target) {
            for (int index : linkedEmitters) target.set(index);
        }

        boolean hasGeometry(Object page, int base, SceneOrigin origin, long emitterAddress) {
            return geometryPage == page && geometryBase == base && origin.equals(geometryOrigin)
                    && geometryEmitterAddress == emitterAddress;
        }

        void geometryWritten(Object page, int base, SceneOrigin origin, long emitterAddress) {
            geometryPage = page;
            geometryBase = base;
            geometryOrigin = origin;
            geometryEmitterAddress = emitterAddress;
        }

        boolean hasHits(Object page, int base, Object pipeline) {
            return hitPage == page && hitBase == base && hitPipeline == pipeline;
        }

        void hitsWritten(Object page, int base, Object pipeline) {
            hitPage = page;
            hitBase = base;
            hitPipeline = pipeline;
        }

        boolean hasEmitters(Object page, int base, Object revision) {
            return emitterPage == page && emitterBase == base && lightIndexRevision == revision;
        }

        void emittersWritten(Object page, int base, Object revision) {
            emitterPage = page;
            emitterBase = base;
            lightIndexRevision = revision;
        }
    }

    record FrameInstanceSnapshot(NativeInstance nativeInstance,
                                         RetainedSceneSnapshot.Instance current,
                                         RtRetainedGeometryPlan.ResolvedMesh resolvedMesh,
                                         RtRetainedGeometryPlan.ResolvedPlacement resolvedPlacement,
                                         GeometryTransform previousTransform,
                                         MeshBuild.Stream previousPositions,
                                         int geometryBase, int sbtRecordOffset,
                                         List<RtRetainedGeometryPlan.GeometryRecord> geometryRecords) { }

    record LatchedInstance(NativeInstance nativeInstance,
                                   RetainedSceneSnapshot.Instance current,
                                   RtRetainedGeometryPlan.ResolvedMesh resolvedMesh,
                                   RtRetainedGeometryPlan.ResolvedPlacement resolvedPlacement,
                                   int geometryBase, int sbtRecordOffset) {
    }

    record ResolvedFrameInput(RtRetainedGeometryPlan.ResolvedMesh mesh,
                              RtRetainedGeometryPlan.ResolvedPlacement placement) { }

    /** Instances are ordered by placement ordinal, with geometry offsets relative to their target scene. */
    static final class InstancePage {
        final Object contentIdentity;
        final List<LatchedInstance> instances;
        final Map<Long, LatchedInstance> identities;
        final List<FrameInstanceSnapshot> stationary;
        final int geometryBase;
        final int geometryCount;
        final long lastOrdinal;

        InstancePage(List<NativeInstance> values, int geometryBase) {
            contentIdentity = new Object();
            this.geometryBase = geometryBase;
            var latched = new ArrayList<LatchedInstance>(values.size());
            var byIdentity = new HashMap<Long, LatchedInstance>();
            var frames = new ArrayList<FrameInstanceSnapshot>(values.size());
            int base = geometryBase;
            for (var instance : values) {
                var placement = new RtRetainedGeometryPlan.ResolvedPlacement(
                        instance.logical.transform(), instance.logical.instanceData().bits());
                var value = new LatchedInstance(instance, instance.logical, instance.mesh.resolved, placement,
                        base, Math.multiplyExact(base, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY));
                latched.add(value);
                byIdentity.put(instance.logical.identity(), value);
                frames.add(frame(value, null));
                base += instance.mesh.logical.build().geometries().size();
            }
            instances = List.copyOf(latched);
            identities = Map.copyOf(byIdentity);
            stationary = List.copyOf(frames);
            geometryCount = base - geometryBase;
            lastOrdinal = values.getLast().placementOrdinal;
        }

        private InstancePage(InstancePage previous, int base) {
            contentIdentity = previous.contentIdentity;
            geometryBase = base;
            geometryCount = previous.geometryCount;
            lastOrdinal = previous.lastOrdinal;
            identities = previous.identities;
            int shift = base - previous.geometryBase;
            var latched = new ArrayList<LatchedInstance>(previous.instances.size());
            var frames = new ArrayList<FrameInstanceSnapshot>(previous.instances.size());
            for (int index = 0; index < previous.instances.size(); index++) {
                var value = previous.instances.get(index);
                int geometry = value.geometryBase + shift;
                int hit = Math.multiplyExact(geometry, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY);
                latched.add(new LatchedInstance(value.nativeInstance, value.current, value.resolvedMesh,
                        value.resolvedPlacement, geometry, hit));
                var frame = previous.stationary.get(index);
                frames.add(new FrameInstanceSnapshot(frame.nativeInstance, frame.current, frame.resolvedMesh,
                        frame.resolvedPlacement, frame.previousTransform, frame.previousPositions, geometry, hit,
                        frame.geometryRecords));
            }
            instances = List.copyOf(latched);
            stationary = List.copyOf(frames);
        }

        InstancePage rebase(int base) { return new InstancePage(this, base); }

        List<FrameInstanceSnapshot> frame(SceneMotionHistory history) {
            if (history == null) return stationary;
            var samePage = history.page(lastOrdinal);
            if (samePage != null && samePage.contentIdentity == contentIdentity) return stationary;
            var values = new ArrayList<FrameInstanceSnapshot>(instances.size());
            for (var instance : instances) {
                var page = history.page(instance.nativeInstance.placementOrdinal);
                var prior = page == null ? null : page.identities.get(instance.current.identity());
                values.add(frame(instance, prior));
            }
            return List.copyOf(values);
        }

        private static FrameInstanceSnapshot frame(LatchedInstance current, LatchedInstance prior) {
            NativeInstance instance = current.nativeInstance;
            GeometryTransform previousTransform = current.current.transform();
            MeshBuild.Stream previousPositions = instance.mesh.logical.build().positions();
            if (prior != null && prior.nativeInstance.placementOrdinal == instance.placementOrdinal) {
                previousTransform = prior.current.transform();
                var previousBuild = prior.nativeInstance.mesh.logical.build();
                if (previousBuild == instance.mesh.logical.build()
                        || RetainedSceneSnapshot.vertexTopologyCompatible(previousBuild, instance.mesh.logical.build())) {
                    previousPositions = previousBuild.positions();
                }
            }
            return new FrameInstanceSnapshot(instance, current.current, current.resolvedMesh, current.resolvedPlacement,
                    previousTransform, previousPositions, current.geometryBase, current.sbtRecordOffset,
                    RtRetainedGeometryPlan.records(current.resolvedMesh, current.resolvedPlacement,
                            previousTransform, previousPositions));
        }
    }

    static final class SceneMotionHistory implements AutoCloseable {
        final List<InstancePage> pages;
        // The captured revision owns every previous position stream, with one claim for the history.
        final SharedResource<RetainedSceneSnapshot> root;

        SceneMotionHistory(List<InstancePage> pages, SharedResource<RetainedSceneSnapshot> root) {
            this.pages = pages;
            this.root = root;
        }

        InstancePage page(long placementOrdinal) {
            int first = 0;
            int end = pages.size();
            while (first < end) {
                int middle = (first + end) >>> 1;
                if (pages.get(middle).lastOrdinal < placementOrdinal) first = middle + 1;
                else end = middle;
            }
            return first == pages.size() ? null : pages.get(first);
        }

        @Override public void close() { root.close(); }
    }

    public record SceneLight(long identity, LightDescriptor descriptor) {
        public SceneLight { Objects.requireNonNull(descriptor, "descriptor"); }
    }

    public record SceneContent(EnvironmentBinding<?> environment, List<SceneLight> lights) {
        public SceneContent {
            if (!(lights instanceof SnapshotList<?>)) lights = List.copyOf(lights);
        }
    }

    public record LightingFrame(int width, int height, long frameIndex, float metersPerSceneUnit,
                                boolean historyContinuous) { }

    public static final class PreparedLighting {
        private final RtNeeAtBackend.Prepared delegate;
        private PreparedLighting(RtNeeAtBackend.Prepared delegate) { this.delegate = delegate; }
        public boolean historyValid() { return delegate.historyValid(); }
    }

    public record PreparedTrace(VulkanDeviceAddress geometryRecordsAddress,
                                VulkanDeviceAddress neeAtStateAddress,
                                int tlasDescriptorIndex,
                                RtPipeline.HitTable hitTable) {
        /** Borrowed view of the frame-protected TLAS descriptor for native UI passes. */
        public GpuAccelerationStructureDescriptor tlasDescriptor() {
            GpuDescriptorIndex.Resource index = new GpuDescriptorIndex.Resource(tlasDescriptorIndex);
            return () -> index;
        }

        /** Writes the retained-scene roots without disturbing roots owned by the program or frame. */
        public void writeWorldRoots(ByteBuffer roots) {
            if (roots.remaining() != RtBindings.WORLD_PUSH_CONSTANT_SIZE) {
                throw new IllegalArgumentException("world binding root has the wrong size");
            }
            ByteBuffer target = roots.duplicate().order(ByteOrder.nativeOrder());
            int base = roots.position();
            target.putLong(base + RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET,
                    geometryRecordsAddress.value());
            target.putInt(base + RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET, tlasDescriptorIndex);
            target.putLong(base + RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET, neeAtStateAddress.value());
        }
    }

    /**
     * Completed slots can be rewritten for a new frame, including its TLAS descriptor. Graphics
     * completion returns the slot without taking the backend lock; allocation only sees returned slots.
     * Pipeline-specific hit strides affect packing, while the reused base address must remain aligned.
     */
    private TraceSlot acquireTraceSlot(int geometryBytes, int hitBytes, int lightBytes,
                                      int emitterBytes, RtPipeline pipeline, GraphicsUse graphicsUse) {
        TraceSlot slot = traceSlots.acquire(candidate ->
                        candidate.geometry.size() >= geometryBytes && candidate.hits.size() >= hitBytes
                                && candidate.lights.size() >= lightBytes && candidate.emitters.size() >= emitterBytes
                                && candidate.hits.deviceAddress().value() % pipeline.retainedHitTableAlignment() == 0,
                () -> createTraceSlot(ctx, geometryBytes, hitBytes, lightBytes, emitterBytes, pipeline));
        try {
            graphicsUse.whenComplete(() -> traceSlots.release(slot));
            return slot;
        } catch (Throwable failure) {
            traceSlots.release(slot);
            throw failure;
        }
    }

    private static TraceSlot createTraceSlot(VulkanDeviceContext ctx, int geometryBytes, int hitBytes,
                                             int lightBytes, int emitterBytes, RtPipeline pipeline) {
        GpuBuffer geometry = null;
        GpuBuffer hits = null;
        GpuBuffer lights = null;
        GpuBuffer emitters = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor = null;
        try {
            geometry = ctx.createMappedGpuUploadBuffer(
                    RtTraceSlotPool.capacity(Math.max(RtRetainedGeometryPlan.RECORD_BYTES, geometryBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained geometry records");
            hits = ctx.createMappedGpuUploadBuffer(
                    RtTraceSlotPool.capacity(Math.max(pipeline.retainedHitRecordStride(), hitBytes)),
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, "retained hit SBT",
                    pipeline.retainedHitTableAlignment());
            lights = ctx.createMappedGpuUploadBuffer(
                    RtTraceSlotPool.capacity(Math.max(RtRetainedLightPlan.RECORD_BYTES, lightBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained light records");
            emitters = ctx.createMappedGpuUploadBuffer(
                    RtTraceSlotPool.capacity(Math.max(Integer.BYTES, emitterBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained primitive-light indices");
            descriptor = ctx.descriptorHeap().allocateResources(1);
            return new TraceSlot(geometry, hits, lights, emitters, descriptor);
        } catch (Throwable failure) {
            if (descriptor != null) descriptor.destroy();
            if (emitters != null) emitters.destroy();
            if (lights != null) lights.destroy();
            if (hits != null) hits.destroy();
            if (geometry != null) geometry.destroy();
            throw failure;
        }
    }

    private static final class TraceSlot {
        List<ByteBuffer> lightPages = List.of();
        final ArrayList<TracePageResidency> pages = new ArrayList<>();
        final GpuBuffer geometry;
        final GpuBuffer hits;
        final GpuBuffer lights;
        final GpuBuffer emitters;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor;

        TraceSlot(GpuBuffer geometry, GpuBuffer hits, GpuBuffer lights, GpuBuffer emitters,
                  GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor) {
            this.geometry = geometry;
            this.hits = hits;
            this.lights = lights;
            this.emitters = emitters;
            this.tlasDescriptor = tlasDescriptor;
        }

        TracePageResidency page(int index) {
            while (pages.size() <= index) pages.add(new TracePageResidency());
            return pages.get(index);
        }

        void trimPages(int count) {
            if (pages.size() > count) pages.subList(count, pages.size()).clear();
        }

        void destroy() {
            geometry.destroy();
            hits.destroy();
            lights.destroy();
            emitters.destroy();
            tlasDescriptor.destroy();
        }
    }

    static LightIndexRevision indexLights(List<SceneLight> lights) {
        Long2IntMap indices = new Long2IntOpenHashMap(lights.size());
        int index = 0;
        for (List<SceneLight> page : SnapshotList.pagesOf(lights)) {
            for (SceneLight light : page) indices.put(light.identity(), index++);
        }
        return new LightIndexRevision(lights, indices);
    }

    record LightIndexRevision(List<SceneLight> lights, Long2IntMap indices) { }

    record NativeInstance(RetainedSceneSnapshot.Instance logical, FrameMesh mesh,
                                  long placementOrdinal) { }

    /**
     * Immutable CPU resolution reused only for the same captured mesh object. The last-frame cache
     * borrows these references; each consuming frame's root owns the mesh and program resources.
     */
    static final class FrameMesh {
        final RetainedSceneSnapshot.Mesh logical;
        final RtPreparedMesh nativeMesh;
        final RtRetainedGeometryPlan.ResolvedMesh resolved;
        FrameMesh(RetainedSceneSnapshot.Mesh logical, RtPreparedMesh nativeMesh) {
            this.logical = logical;
            this.nativeMesh = nativeMesh;
            resolved = resolveMesh(logical);
        }
        RtPreparedMesh.State blas() { return nativeMesh.value(); }
    }
}
