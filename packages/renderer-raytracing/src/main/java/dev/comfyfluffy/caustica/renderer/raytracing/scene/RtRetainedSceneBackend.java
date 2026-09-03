package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.engine.scene.RetainedInstanceTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneContentSnapshot;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneGeometryDelta;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/**
 * Vulkan owner for the live retained scene and the immutable layouts frames read from it.
 *
 * <p>Mutations land on the live table immediately; acceleration-structure builds are asynchronous patches
 * against it. A mesh whose update is still building keeps rendering the generation it already has, so
 * publication never waits on the GPU and one slow build cannot delay unrelated instance, light, or content
 * changes. Builds that lose to a later update, or whose mesh was dropped, release instead of installing.
 *
 * <p>Frames read an immutable {@link SceneLayoutGeneration} rematerialized from the live table whenever it
 * has moved. A frame pins the layout it leased for as long as its {@link GraphicsUse} is outstanding, so
 * every resource a submitted frame can reach outlives it; nothing may reference scene geometry it did not
 * lease through that snapshot.
 */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final RetainedSceneProgressQueue<CompletedBatch> completed = new RetainedSceneProgressQueue<>();
    private final RtLatestInstanceTransforms latestTransforms = new RtLatestInstanceTransforms();
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResource<SceneMotionHistory>> motionHistoryByScene =
            new IdentityHashMap<>();
    /**
     * Live mesh table. An entry renders from its newest <em>coherent</em> generation - a logical mesh paired
     * with the acceleration structure actually built for it - while a newer update is still building. Pairing
     * a new logical mesh with an older structure would desynchronize geometry counts from hit records, so the
     * pair moves together or not at all.
     */
    private final Map<Long, MeshEntry> meshTable = new LinkedHashMap<>();
    private final RtRetainedInstanceState instanceState = new RtRetainedInstanceState();
    /** Build inputs of failed batches. The GPU may still hold them, so they wait for the idle boundary. */
    private final List<BlasBuildUse> unsettledBuildUses = new ArrayList<>();
    private Map<SceneId, SceneContent> liveContent = Map.of();
    private PublishedSceneRevision published;
    private long acceptedRevision = -1L;
    private boolean layoutDirty;
    private long nextBuildToken;
    private boolean closed;
    private boolean sessionClosing;
    private long nextPlacementOrdinal;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.neeAt = new RtNeeAtBackend(ctx);
    }

    @Override
    public synchronized void updateLatestInstanceTransforms(List<RetainedInstanceTransform> transforms) {
        requireOpen();
        latestTransforms.acceptLatest(List.copyOf(transforms));
    }

    public synchronized PreparedLighting prepareLighting(SceneId scene, LightingFrame frame,
                                                          VkCommandBuffer commandBuffer,
                                                          GraphicsUse graphicsUse) {
        FrameSceneSnapshot current = frameScene(scene, graphicsUse);
        var input = new RtNeeAtBackend.FrameInput(frame.width(), frame.height(), frame.frameIndex(),
                frame.metersPerSceneUnit(), frame.historyContinuous(), frame.localHistoryContinuous());
        return new PreparedLighting(neeAt.prepare(scene, current.content.lights(), input,
                commandBuffer, graphicsUse));
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

    /**
     * Accepts a complete logical version. Mesh planning happens before any table mutation, so a throw leaves
     * the live scene exactly as it was.
     */
    @Override
    public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable onPublished) {
        requireOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        requireIncreasingRevision(snapshot.revision());
        java.util.Set<Long> live = new java.util.LinkedHashSet<>();
        for (RetainedSceneSnapshot.Mesh mesh : snapshot.meshes()) live.add(mesh.identity());
        List<PlannedMesh> planned = planMeshes(snapshot.meshes());
        RtLatestInstanceTransforms.Update transformUpdate;
        try {
            transformUpdate = latestTransforms.prepareSnapshot(snapshot.instances());
        } catch (Throwable failure) {
            releasePlanned(planned, failure);
            throw failure;
        }
        List<Long> dropped = meshTable.keySet().stream()
                .filter(identity -> !live.contains(identity)).toList();
        dropped.forEach(this::dropMesh);
        installPlanned(planned);
        instanceState.replaceAll(snapshot.instances(), () -> ++nextPlacementOrdinal);
        acceptContent(assembleContent(snapshot.scenes(), snapshot.lights()), snapshot.revision());
        latestTransforms.apply(transformUpdate);
        pruneLatestTransforms();
        submitPlanned(planned, onPublished);
    }

    /** Accepts geometry without rematerializing or revalidating the unchanged logical world. */
    @Override
    public synchronized void publishGeometry(RetainedSceneGeometryDelta delta,
                                             Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                             Runnable onPublished) {
        requireOpen();
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(fallbackSnapshot, "fallbackSnapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        requireIncreasingRevision(delta.revision());
        applyGeometryDelta(delta, null, onPublished);
    }

    /** Accepts geometry and content as one logical revision of the live scene. */
    @Override
    public synchronized void publishGeometryAndContent(
            RetainedSceneGeometryDelta geometry, RetainedSceneContentSnapshot content,
            Supplier<RetainedSceneSnapshot> fallbackSnapshot, Runnable onPublished) {
        requireOpen();
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(fallbackSnapshot, "fallbackSnapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        if (geometry.revision() != content.revision()) {
            throw new IllegalArgumentException("geometry and content revisions must match");
        }
        requireIncreasingRevision(geometry.revision());
        applyGeometryDelta(geometry, assembleContent(content.scenes(), content.lights()), onPublished);
    }

    /** Accepts light and environment content. Geometry is untouched, so no build is submitted. */
    @Override
    public synchronized void publishContent(RetainedSceneContentSnapshot snapshot, Runnable onPublished) {
        requireOpen();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        requireIncreasingRevision(snapshot.revision());
        acceptContent(assembleContent(snapshot.scenes(), snapshot.lights()), snapshot.revision());
        submitPlanned(List.of(), onPublished);
    }

    /**
     * Applies one geometry delta to the live table. Every mesh needing GPU work is planned first, so a
     * preparation failure throws before any mutation becomes visible.
     */
    private void applyGeometryDelta(RetainedSceneGeometryDelta delta,
                                    Map<SceneId, SceneContent> content, Runnable onPublished) {
        Map<Long, RetainedSceneSnapshot.Mesh> sets = new LinkedHashMap<>();
        Map<Long, Boolean> drops = new LinkedHashMap<>();
        for (RetainedSceneGeometryDelta.Mutation mutation : delta.mutations()) {
            if (mutation instanceof RetainedSceneGeometryDelta.SetMesh set) {
                long identity = set.mesh().identity();
                drops.remove(identity);
                sets.remove(identity);
                sets.put(identity, set.mesh());
            } else if (mutation instanceof RetainedSceneGeometryDelta.DropMesh drop) {
                sets.remove(drop.identity());
                drops.remove(drop.identity());
                drops.put(drop.identity(), Boolean.TRUE);
            }
        }
        List<PlannedMesh> planned = planMeshes(sets.values());
        RtLatestInstanceTransforms.Update transformUpdate;
        try {
            transformUpdate = latestTransforms.prepareMutations(delta.mutations());
        } catch (Throwable failure) {
            releasePlanned(planned, failure);
            throw failure;
        }
        drops.keySet().forEach(this::dropMesh);
        installPlanned(planned);
        instanceState.apply(delta.mutations(), () -> ++nextPlacementOrdinal);
        acceptContent(content == null ? liveContent : content, delta.revision());
        latestTransforms.apply(transformUpdate);
        pruneLatestTransforms();
        submitPlanned(planned, onPublished);
    }

    private void requireIncreasingRevision(long revision) {
        if (revision <= acceptedRevision) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
    }

    private void acceptContent(Map<SceneId, SceneContent> content, long revision) {
        liveContent = Map.copyOf(content);
        acceptedRevision = revision;
        layoutDirty = true;
        neeAt.retainScenes(liveContent.keySet());
        retainRenderedScenes(liveContent.keySet());
    }

    @Override
    public synchronized void onProgressAvailable(Runnable wakeup) {
        completed.onProgressAvailable(wakeup);
    }

    /** Applies finished builds to the live mesh table and reports their batches. */
    @Override
    public synchronized void progress() {
        requireOpen();
        Throwable failure = null;
        CompletedBatch batch;
        while ((batch = completed.poll()) != null) {
            try {
                applyCompletedBatch(batch);
            } catch (Throwable batchFailure) {
                if (failure == null) failure = batchFailure;
                else failure.addSuppressed(batchFailure);
            }
        }
        throwFailure(failure, "retained scene publication failed");
    }

    @Override
    public synchronized void prepareForSessionClose() {
        requireOpen();
        if (sessionClosing) return;
        settleAndReleaseTerminalFrameRoots(ctx.gpuExecutor()::drainAndWaitIdle,
                inFlightFrames, motionHistoryByScene);
        sessionClosing = true;
    }

    @Override
    public synchronized void settleFrameUses() {
        requireOpen();
        if (inFlightFrames.isEmpty() && motionHistoryByScene.isEmpty()) return;
        settleAndReleaseTerminalFrameRoots(ctx.gpuExecutor()::drainAndWaitIdle,
                inFlightFrames, motionHistoryByScene);
    }

    /** The newest accepted revision. Individual meshes may still be rendering an older generation. */
    public synchronized long publishedRevision() {
        requireOpen();
        return acceptedRevision;
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
        TlasBuilder.InstanceBatch instances = new TlasBuilder.InstanceBatch();
        instances.reset(current.instances.size());
        for (FrameInstanceSnapshot instance : current.instances) {
            float[] transform = instance.current.transform().relativeTo(origin.x(), origin.y(), origin.z());
            instances.append(transform, 0, 0, 0, instance.nativeInstance.mesh.blas().accel.deviceAddress,
                    instance.geometryBase, instance.current.mask(), instance.sbtRecordOffset);
        }
        return TlasBuilder.prepare(ctx, instances, graphicsUse);
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
        List<SceneLight> sceneLights = current.content.lights();
        Map<Long, Integer> lightIndices = new LinkedHashMap<>();
        for (int index = 0; index < sceneLights.size(); index++) {
            lightIndices.put(sceneLights.get(index).identity(), index);
        }
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        List<Integer> emitterOffsets = new ArrayList<>();
        int emitterBytes = 0;
        for (FrameInstanceSnapshot frameInstance : current.instances) {
            NativeInstance instance = frameInstance.nativeInstance;
            List<RtRetainedGeometryPlan.GeometryRecord> instanceRecords = RtRetainedGeometryPlan.records(
                    frameInstance.resolvedMesh, frameInstance.resolvedPlacement,
                    frameInstance.previousTransform,
                    frameInstance.previousPositions);
            for (int geometryIndex = 0; geometryIndex < instanceRecords.size(); geometryIndex++) {
                records.add(instanceRecords.get(geometryIndex));
                MeshBuild.Geometry<?> geometry = instance.mesh.logical.build().geometries().get(geometryIndex);
                int primitiveBase = geometry.firstIndex() / 3;
                if (hasEmitterMapping(instance.logical.primitiveEmitters(), primitiveBase,
                        geometry.triangleCount())) {
                    emitterOffsets.add(emitterBytes);
                    emitterBytes = Math.addExact(emitterBytes,
                            Math.multiplyExact(geometry.triangleCount(), Integer.BYTES));
                } else {
                    emitterOffsets.add(-1);
                }
            }
        }
        List<RtRetainedGeometryPlan.HitGroup> groups = RtRetainedGeometryPlan.hitGroups(records);
        ByteBuffer hits = pipeline.retainedHitRecords(groups);
        int geometryBytes = Math.multiplyExact(records.size(), RtRetainedGeometryPlan.RECORD_BYTES);
        int lightBytes = Math.multiplyExact(sceneLights.size(), RtRetainedLightPlan.RECORD_BYTES);
        TraceSlot slot = createTraceSlot(ctx, geometryBytes, hits.remaining(), lightBytes, emitterBytes,
                pipeline, graphicsUse);
        List<RtRetainedGeometryPlan.GeometryRecord> addressedRecords = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            int emitterOffset = emitterOffsets.get(index);
            addressedRecords.add(emitterOffset < 0 ? records.get(index) : records.get(index).withEmitterIndex(
                    slot.emitters.deviceAddress().addBytes(emitterOffset), 0));
        }
        ByteBuffer geometry = RtRetainedGeometryPlan.pack(addressedRecords, origin);
        ByteBuffer emitters = ByteBuffer.allocate(emitterBytes).order(ByteOrder.nativeOrder());
        boolean[] linkedEmitters = new boolean[sceneLights.size()];
        for (FrameInstanceSnapshot frameInstance : current.instances) {
            NativeInstance instance = frameInstance.nativeInstance;
            for (MeshBuild.Geometry<?> meshGeometry : instance.mesh.logical.build().geometries()) {
                int primitiveBase = meshGeometry.firstIndex() / 3;
                if (hasEmitterMapping(instance.logical.primitiveEmitters(), primitiveBase,
                        meshGeometry.triangleCount())) {
                    putEmitterIndices(emitters, primitiveBase, meshGeometry.triangleCount(),
                            instance.logical.primitiveEmitters(), lightIndices, linkedEmitters);
                }
            }
        }
        emitters.flip();
        ByteBuffer lights = RtRetainedLightPlan.pack(
                sceneLights.stream().map(SceneLight::descriptor).toList(), origin, linkedEmitters);
        if (geometry.hasRemaining()) {
            MemoryUtil.memByteBuffer(slot.geometry.mapped(), geometry.remaining()).put(geometry.duplicate());
            slot.geometry.flush(0L, geometry.remaining());
        }
        if (hits.hasRemaining()) {
            MemoryUtil.memByteBuffer(slot.hits.mapped(), hits.remaining()).put(hits.duplicate());
            slot.hits.flush(0L, hits.remaining());
        }
        if (lights.hasRemaining()) {
            MemoryUtil.memByteBuffer(slot.lights.mapped(), lights.remaining()).put(lights.duplicate());
            slot.lights.flush(0L, lights.remaining());
        }
        if (emitters.hasRemaining()) {
            MemoryUtil.memByteBuffer(slot.emitters.mapped(), emitters.remaining()).put(emitters.duplicate());
            slot.emitters.flush(0L, emitters.remaining());
        }
        ctx.descriptorHeap().writer().writeAccelerationStructure(slot.tlasDescriptor, 0, tlasHandle);
        RtPipeline.HitTable hitTable = hits.hasRemaining() ? new RtPipeline.HitTable(
                new VulkanDeviceAddressRange(slot.hits.deviceAddress(), hits.remaining()),
                pipeline.retainedHitRecordStride()) : null;
        RtNeeAtBackend.Prepared lighting = neeAt.active(scene);
        if (lighting == null) throw new IllegalStateException("prepareLighting must precede prepareTrace");
        lighting.bindLightTable(slot.lights.deviceAddress());
        PreparedTrace prepared = new PreparedTrace(slot.geometry.deviceAddress(), lighting.stateAddress(),
                slot.tlasDescriptor.firstIndex().value(), hitTable);
        current.markTraced();
        return prepared;
    }

    static int emitterIndex(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                            int primitive, Map<Long, Integer> lightIndices) {
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
        for (RetainedSceneSnapshot.PrimitiveEmitter range : ranges) {
            long rangeEnd = Math.addExact((long) range.firstPrimitive(), range.primitiveCount());
            if (rangeEnd <= firstPrimitive) continue;
            return range.firstPrimitive() < end;
        }
        return false;
    }

    /** Packs a consecutive primitive range in one forward pass over the sorted, disjoint emitter ranges. */
    static void putEmitterIndices(ByteBuffer output, int firstPrimitive, int primitiveCount,
                                  List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                  Map<Long, Integer> lightIndices, boolean[] linkedEmitters) {
        int rangeIndex = 0;
        while (rangeIndex < ranges.size()) {
            RetainedSceneSnapshot.PrimitiveEmitter range = ranges.get(rangeIndex);
            if ((long) range.firstPrimitive() + range.primitiveCount() > firstPrimitive) break;
            rangeIndex++;
        }
        for (int primitive = firstPrimitive, end = Math.addExact(firstPrimitive, primitiveCount);
             primitive < end; primitive++) {
            while (rangeIndex < ranges.size()) {
                RetainedSceneSnapshot.PrimitiveEmitter range = ranges.get(rangeIndex);
                if ((long) range.firstPrimitive() + range.primitiveCount() > primitive) break;
                rangeIndex++;
            }
            int dense = -1;
            if (rangeIndex < ranges.size()) {
                RetainedSceneSnapshot.PrimitiveEmitter range = ranges.get(rangeIndex);
                if (primitive >= range.firstPrimitive()) {
                    dense = lightIndices.getOrDefault(range.lightIdentity(), -1);
                }
            }
            output.putInt(dense);
            if (dense >= 0) linkedEmitters[dense] = true;
        }
    }

    /** Releases all native state after the GPU executor has stopped and the device has been made idle. */
    public synchronized void shutdownAfterDeviceIdle() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        try {
            releaseTerminalFrameRoots();
        } catch (Throwable releaseFailure) {
            failure = releaseFailure;
        }
        if (published != null) {
            try {
                published.release();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
            published = null;
        }
        try {
            neeAt.destroyAfterDeviceIdle();
        } catch (Throwable releaseFailure) {
            if (failure == null) failure = releaseFailure;
            else failure.addSuppressed(releaseFailure);
        }
        List<AutoCloseable> terminal = new ArrayList<>(meshTable.values());
        terminal.addAll(unsettledBuildUses);
        meshTable.clear();
        unsettledBuildUses.clear();
        try {
            closeAll(terminal, null);
        } catch (Throwable releaseFailure) {
            if (failure == null) failure = releaseFailure;
            else failure.addSuppressed(releaseFailure);
        }
        latestTransforms.retainOnly(java.util.Set.of());
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("retained scene shutdown failed", failure);
    }

    private void releaseTerminalFrameRoots() {
        releaseTerminalFrameRoots(inFlightFrames, motionHistoryByScene);
    }

    static void settleAndReleaseTerminalFrameRoots(
            Runnable settleAcceptedWork, Map<?, ? extends AutoCloseable> frames,
            Map<?, ? extends AutoCloseable> histories) {
        settleAcceptedWork.run();
        releaseTerminalFrameRoots(frames, histories);
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

    static void releaseBuildUseResources(Runnable releaseScratch,
                                         List<? extends AutoCloseable> dependencies) {
        Throwable failure = null;
        try {
            releaseScratch.run();
        } catch (Throwable releaseFailure) {
            failure = releaseFailure;
        }
        closeAll(dependencies, failure);
        throwFailure(failure, "BLAS build-use release failed");
    }

    /**
     * Plans one mesh table update without touching the live table. A mesh whose geometry is unchanged is
     * paired with the structure it already has; anything else becomes a build, refit when the current
     * structure can serve as its update source.
     */
    private List<PlannedMesh> planMeshes(Iterable<RetainedSceneSnapshot.Mesh> meshes) {
        List<PlannedMesh> planned = new ArrayList<>();
        try {
            for (RetainedSceneSnapshot.Mesh mesh : meshes) {
                MeshEntry entry = meshTable.get(mesh.identity());
                MeshGeneration current = entry == null ? null : entry.current;
                if (current != null && RtRetainedGeometryPlan.canReuseBlas(
                        current.logical.build(), mesh.build())) {
                    planned.add(new PlannedMesh(mesh.identity(), current.withLogical(mesh), null));
                    continue;
                }
                // The refit source is whatever structure this mesh currently renders, never an in-flight
                // one: a build that has not landed has no coherent logical half to validate against.
                PreparedMesh prepared = current != null && RtRetainedGeometryPlan.canRefitBlas(
                        current.logical.build(), mesh.build())
                        ? prepareMeshRefit(mesh, current)
                        : prepareMeshBuild(mesh);
                planned.add(new PlannedMesh(mesh.identity(), null,
                        new PendingBuild(mesh.identity(), ++nextBuildToken,
                                prepared.mesh(), prepared.buildUse())));
            }
            return List.copyOf(planned);
        } catch (Throwable failure) {
            releasePlanned(planned, failure);
            throw failure;
        }
    }

    private void releasePlanned(List<PlannedMesh> planned, Throwable failure) {
        List<AutoCloseable> rejected = new ArrayList<>();
        for (PlannedMesh mesh : planned) {
            if (mesh.coherent() != null) rejected.add(mesh.coherent());
            if (mesh.build() != null) {
                rejected.add(mesh.build().generation());
                rejected.add(mesh.build().buildUse());
            }
        }
        suppressCleanupFailure(failure, () -> closeAll(rejected, null));
    }

    /** Installs planned updates. Reuse lands immediately; a build only claims the entry's newest token. */
    private void installPlanned(List<PlannedMesh> planned) {
        for (PlannedMesh mesh : planned) {
            MeshEntry entry = meshTable.computeIfAbsent(mesh.identity(), ignored -> new MeshEntry());
            if (mesh.coherent() != null) {
                MeshGeneration previous = entry.current;
                entry.current = mesh.coherent();
                entry.pendingToken = 0L;
                if (previous != null) previous.close();
            } else {
                entry.pendingToken = mesh.build().token();
            }
        }
        if (!planned.isEmpty()) layoutDirty = true;
    }

    private void dropMesh(long identity) {
        MeshEntry removed = meshTable.remove(identity);
        if (removed == null) return;
        layoutDirty = true;
        removed.close();
    }

    private void submitPlanned(List<PlannedMesh> planned, Runnable onPublished) {
        List<PendingBuild> builds = planned.stream()
                .map(PlannedMesh::build).filter(Objects::nonNull).toList();
        if (builds.isEmpty()) {
            // Never call back inline from a publish; batches always surface through progress().
            completed.add(new CompletedBatch(List.of(), onPublished, null));
            return;
        }
        List<RtAccel.PreparedBlas> operations = builds.stream()
                .map(build -> build.buildUse().operation()).toList();
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, operations),
                completion -> completeLater(builds, onPublished, completion));
    }

    /**
     * Applies one finished batch. A result whose token no longer matches lost to a newer update, and one
     * whose mesh is gone was dropped; both simply release. A failed batch leaves every mesh on the
     * generation it already renders.
     */
    private void applyCompletedBatch(CompletedBatch batch) {
        List<AutoCloseable> released = new ArrayList<>();
        for (PendingBuild build : batch.builds()) {
            MeshEntry entry = meshTable.get(build.meshIdentity());
            boolean newest = claimsMeshEntry(entry != null,
                    entry == null ? 0L : entry.pendingToken, build.token());
            if (newest) entry.pendingToken = 0L;
            if (newest && batch.failure() == null) {
                MeshGeneration previous = entry.current;
                entry.current = build.generation();
                layoutDirty = true;
                if (previous != null) released.add(previous);
            } else {
                released.add(build.generation());
            }
            if (batch.failure() != null) unsettledBuildUses.add(build.buildUse());
        }
        Throwable failure = null;
        try {
            closeAll(released, null);
        } catch (Throwable releaseFailure) {
            failure = releaseFailure;
        }
        try {
            batch.published().run();
        } catch (Throwable callbackFailure) {
            if (failure == null) failure = callbackFailure;
            else failure.addSuppressed(callbackFailure);
        }
        throwFailure(failure, "retained scene batch completion failed");
    }

    /**
     * Terminal callback for one batch, on the executor thread. Build inputs are released here rather than at
     * table update: the batch has already retired on the device, and holding scratch until the render thread
     * next makes progress would overlap it with the following batch.
     */
    private void completeLater(List<PendingBuild> builds, Runnable onPublished,
                               GpuComputeCompletion completion) {
        Throwable failure = switch (completion) {
            case GpuComputeCompletion.Succeeded ignored -> null;
            case GpuComputeCompletion.Cancelled ignored ->
                    new CancellationException("retained scene build was cancelled");
            case GpuComputeCompletion.Failed failed -> failed.failure();
        };
        if (failure == null) {
            try {
                closeAll(builds.stream().map(PendingBuild::buildUse).toList(), null);
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
        }
        completed.add(new CompletedBatch(builds, onPublished, failure));
    }

    private PreparedMesh prepareMeshBuild(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        ResourceLeaseSet inputs = ResourceLeaseSet.acquireRequired(List.of(
                build.positions().resource(), build.indices().resource()));
        RtAccel.PreparedBlas unownedOperation = null;
        try {
            RtAccel.PersistentBuild nativeBuild = RtAccel.prepareUpdateablePersistentBlasBuild(ctx,
                    build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                    build.indices().bytes().address(),
                    RtRetainedGeometryPlan.blasRanges(build), "retained mesh " + mesh.identity());
            unownedOperation = nativeBuild.op();
            BlasGeneration blas = new BlasGeneration(nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
            SharedResource<BlasGeneration> ownedBlas = handoffResource(
                    blas, BlasGeneration::destroy,
                    value -> SharedResource.owned(value, BlasGeneration::destroy));
            MeshGeneration generation = null;
            BlasBuildUse buildUse = null;
            try {
                generation = new MeshGeneration(mesh, ownedBlas);
                ownedBlas = null;
                buildUse = new BlasBuildUse(nativeBuild.op(), List.of(inputs));
                unownedOperation = null;
                inputs = null;
                PreparedMesh prepared = new PreparedMesh(generation, buildUse);
                generation = null;
                buildUse = null;
                return prepared;
            } catch (Throwable failure) {
                if (buildUse != null) {
                    BlasBuildUse rejectedUse = buildUse;
                    suppressCleanupFailure(failure, rejectedUse::close);
                } else if (unownedOperation != null) {
                    RtAccel.PreparedBlas rejectedOperation = unownedOperation;
                    unownedOperation = null;
                    suppressCleanupFailure(failure,
                            () -> RtAccel.freeBlasScratch(List.of(rejectedOperation)));
                }
                if (generation != null) {
                    MeshGeneration rejectedGeneration = generation;
                    suppressCleanupFailure(failure, rejectedGeneration::close);
                }
                if (ownedBlas != null) {
                    SharedResource<BlasGeneration> rejectedBlas = ownedBlas;
                    suppressCleanupFailure(failure, rejectedBlas::close);
                }
                throw failure;
            }
        } catch (Throwable failure) {
            if (unownedOperation != null) {
                RtAccel.PreparedBlas rejectedOperation = unownedOperation;
                suppressCleanupFailure(failure,
                        () -> RtAccel.freeBlasScratch(List.of(rejectedOperation)));
            }
            if (inputs != null) {
                ResourceLeaseSet rejectedInputs = inputs;
                suppressCleanupFailure(failure, rejectedInputs::close);
            }
            throw failure;
        }
    }

    private PreparedMesh prepareMeshRefit(RetainedSceneSnapshot.Mesh mesh, MeshGeneration source) {
        SharedResource<BlasGeneration> sourceBuild = source.blas.retain();
        ResourceLeaseSet inputs = null;
        RtAccel.PreparedBlas unownedOperation = null;
        try {
            inputs = ResourceLeaseSet.acquireRequired(List.of(
                    mesh.build().positions().resource(), mesh.build().indices().resource()));
            MeshBuild<?> build = mesh.build();
            RtAccel.PersistentBuild nativeBuild = RtAccel.preparePersistentBlasUpdate(ctx,
                    sourceBuild.get().buildOperation, build.positions().bytes().address(),
                    build.indices().bytes().address(), "retained mesh " + mesh.identity());
            unownedOperation = nativeBuild.op();
            BlasGeneration blas = new BlasGeneration(
                    nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
            SharedResource<BlasGeneration> ownedBlas = handoffResource(
                    blas, BlasGeneration::destroy,
                    value -> SharedResource.owned(value, BlasGeneration::destroy));
            MeshGeneration generation = null;
            BlasBuildUse buildUse = null;
            try {
                generation = new MeshGeneration(mesh, ownedBlas);
                ownedBlas = null;
                buildUse = new BlasBuildUse(nativeBuild.op(), List.of(sourceBuild, inputs));
                unownedOperation = null;
                sourceBuild = null;
                inputs = null;
                PreparedMesh prepared = new PreparedMesh(generation, buildUse);
                generation = null;
                buildUse = null;
                return prepared;
            } catch (Throwable failure) {
                if (buildUse != null) {
                    BlasBuildUse rejectedUse = buildUse;
                    suppressCleanupFailure(failure, rejectedUse::close);
                } else if (unownedOperation != null) {
                    RtAccel.PreparedBlas rejectedOperation = unownedOperation;
                    unownedOperation = null;
                    suppressCleanupFailure(failure,
                            () -> RtAccel.freeBlasScratch(List.of(rejectedOperation)));
                }
                if (generation != null) {
                    MeshGeneration rejectedGeneration = generation;
                    suppressCleanupFailure(failure, rejectedGeneration::close);
                }
                if (ownedBlas != null) {
                    SharedResource<BlasGeneration> rejectedBlas = ownedBlas;
                    suppressCleanupFailure(failure, rejectedBlas::close);
                }
                throw failure;
            }
        } catch (Throwable failure) {
            if (unownedOperation != null) {
                RtAccel.PreparedBlas rejectedOperation = unownedOperation;
                suppressCleanupFailure(failure,
                        () -> RtAccel.freeBlasScratch(List.of(rejectedOperation)));
            }
            if (sourceBuild != null) {
                SharedResource<BlasGeneration> rejectedSource = sourceBuild;
                suppressCleanupFailure(failure, rejectedSource::close);
            }
            if (inputs != null) {
                ResourceLeaseSet rejectedInputs = inputs;
                suppressCleanupFailure(failure, rejectedInputs::close);
            }
            throw failure;
        }
    }

    static <T, R> R handoffResource(T resource, Consumer<? super T> disposer,
                                    Function<? super T, ? extends R> owner) {
        try {
            return owner.apply(resource);
        } catch (Throwable failure) {
            suppressCleanupFailure(failure, () -> disposer.accept(resource));
            throw failure;
        }
    }

    static Map<SceneId, SceneContent> assembleContent(
            List<RetainedSceneSnapshot.Scene> scenes, List<RetainedSceneSnapshot.Light> lights) {
        Map<SceneId, MutableSceneContent> mutable = new IdentityHashMap<>();
        for (RetainedSceneSnapshot.Scene scene : scenes) {
            mutable.put(scene.id(), new MutableSceneContent(scene.environment()));
        }
        for (RetainedSceneSnapshot.Light light : lights) {
            MutableSceneContent content = mutable.get(light.scene());
            if (content == null) throw new IllegalArgumentException("light names an absent scene");
            content.lights.add(new SceneLight(light.identity(), light.descriptor()));
        }
        Map<SceneId, SceneContent> content = new IdentityHashMap<>();
        mutable.forEach((scene, value) -> content.put(scene,
                new SceneContent(value.environment, List.copyOf(value.lights))));
        return content;
    }

    /**
     * Rebuilds the immutable layout frames read, if the live table moved since the last one. Frames pin the
     * layout they lease, so an in-flight frame keeps rendering the generation it started with.
     */
    private PublishedSceneRevision materializeCurrent() {
        if (!layoutDirty && published != null) return published;
        if (liveContent.isEmpty()) return published;
        Map<Long, MeshGeneration> meshes = new LinkedHashMap<>();
        SharedResource<SceneLayoutGeneration> layout;
        try {
            meshTable.forEach((identity, entry) -> {
                if (entry.current != null) meshes.put(identity, entry.current.withLogical(entry.current.logical));
            });
            Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
            Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
            liveContent.keySet().forEach(scene -> {
                instances.put(scene, new ArrayList<>());
                geometryBases.put(scene, 0);
            });
            for (RetainedSceneSnapshot.Instance instance : instanceState.orderedInstances()) {
                MeshGeneration mesh = meshes.get(instance.meshIdentity());
                List<NativeInstance> target = instances.get(instance.scene());
                // A mesh awaiting its first build has no structure to trace, and an instance can name a
                // scene this content revision does not carry. Both simply do not render this layout.
                if (mesh == null || target == null) continue;
                int geometryBase = geometryBases.get(instance.scene());
                target.add(new NativeInstance(instance, mesh,
                        instanceState.previousTransform(instance), geometryBase,
                        Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY),
                        instanceState.ordinal(instance.identity())));
                geometryBases.put(instance.scene(), Math.addExact(geometryBase,
                        mesh.logical.build().geometries().size()));
            }
            instances.replaceAll((ignored, value) -> List.copyOf(value));
            layout = sharedLayout(meshes, instances);
        } catch (Throwable failure) {
            suppressCleanupFailure(failure, () -> closeAll(meshes.values(), null));
            throw failure;
        }
        PublishedSceneRevision next;
        try {
            SceneRevisionRoot root = new SceneRevisionRoot(layout, liveContent);
            next = new PublishedSceneRevision(acceptedRevision,
                    SharedResource.owned(root, SceneRevisionRoot::destroy));
        } catch (Throwable failure) {
            suppressCleanupFailure(failure, layout::close);
            throw failure;
        }
        PublishedSceneRevision previous = published;
        published = next;
        layoutDirty = false;
        instanceState.latchPreviousTransforms();
        if (previous != null) previous.release();
        return published;
    }

    /**
     * Whether a finished build is still the newest update for its mesh. A token that no longer matches lost
     * to a later update, and a missing entry means the mesh was dropped; neither may install its result.
     * Kept device-independent so the supersession rule is testable without a GPU.
     */
    static boolean claimsMeshEntry(boolean present, long entryToken, long buildToken) {
        return present && entryToken == buildToken;
    }

    private void pruneLatestTransforms() {
        latestTransforms.retainOnly(instanceState.identities());
    }

    private void retainRenderedScenes(java.util.Set<SceneId> retained) {
        List<SharedResource<SceneMotionHistory>> removed = new ArrayList<>();
        motionHistoryByScene.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            removed.add(entry.getValue());
            return true;
        });
        closeAll(removed, null);
    }

    private PublishedSceneRevision requirePublishedScene(SceneId scene) {
        requireOpen();
        PublishedSceneRevision current = materializeCurrent();
        if (current == null || !current.content().containsKey(scene)) {
            throw new IllegalArgumentException("scene is not in the published scene revision");
        }
        return current;
    }

    private FrameSnapshot frameLease(SceneId scene, GraphicsUse graphicsUse) {
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        requireOpen();
        boolean created = !inFlightFrames.containsKey(graphicsUse);
        FrameSnapshot frame = latchFrameRoot(inFlightFrames, graphicsUse, () -> {
            PublishedSceneRevision current = requirePublishedScene(scene);
            return new FrameSnapshot(graphicsUse, current.retainRoot());
        });
        if (created) {
            try {
                graphicsUse.whenSubmitted(frame::accept);
                graphicsUse.keepAlive(frame);
            } catch (Throwable failure) {
                frame.close();
                throw failure;
            }
        }
        SceneRevisionRoot root = frame.root();
        if (!root.content.containsKey(scene)) {
            throw new IllegalArgumentException("scene is not in the frame's scene revision");
        }
        return frame;
    }

    static <K, V> V latchFrameRoot(Map<K, V> frames, K graphicsUse, Supplier<V> currentRoot) {
        V latched = frames.get(graphicsUse);
        if (latched != null) return latched;
        V created = Objects.requireNonNull(currentRoot.get(), "currentRoot");
        frames.put(graphicsUse, created);
        return created;
    }

    static <K, S, T> Map<K, List<T>> captureFrameValues(
            Map<K, List<S>> source, Function<S, T> capture) {
        Map<K, List<T>> captured = new IdentityHashMap<>();
        source.forEach((scene, values) -> captured.put(scene, values.stream().map(capture).toList()));
        return Collections.unmodifiableMap(captured);
    }

    private static List<ResourceRef> frameResourceReferences(SceneRevisionRoot root,
                                                               Map<SceneId, List<LatchedInstance>> instances) {
        List<ResourceRef> references = new ArrayList<>();
        root.content.values().forEach(content -> {
            if (content.environment() != null) {
                references.add(content.environment().bindingData().resource());
            }
        });
        instances.values().forEach(values -> values.forEach(instance -> {
            MeshBuild<?> build = instance.nativeInstance.mesh.logical.build();
            references.add(build.positions().resource());
            references.add(build.indices().resource());
            build.geometries().forEach(geometry -> {
                if (geometry.surface() != null) {
                    references.add(geometry.surface().bindingData().resource());
                }
                if (geometry.volume() != null) {
                    references.add(geometry.volume().bindingData().resource());
                }
            });
            references.add(instance.current.instanceData().resource());
        }));
        return List.copyOf(references);
    }

    private static Map<SceneId, SceneContent> resolveFrameContent(
            Map<SceneId, SceneContent> content, ResourceLeaseSet resources) {
        Map<SceneId, SceneContent> resolved = new IdentityHashMap<>();
        content.forEach((scene, value) -> {
            EnvironmentBinding<?> environment = resolveFrameEnvironment(value.environment(), resources);
            resolved.put(scene, new SceneContent(environment, value.lights()));
        });
        return Collections.unmodifiableMap(resolved);
    }

    static EnvironmentBinding<?> resolveFrameEnvironment(EnvironmentBinding<?> environment,
                                                          ResourceLeaseSet resources) {
        return environment == null || resources.available(environment.bindingData().resource())
                ? environment : null;
    }

    static java.util.Optional<ResolvedFrameInput> resolveFrameInput(
            RetainedSceneSnapshot.Mesh mesh, RetainedSceneSnapshot.Instance instance,
            ResourceLeaseSet resources) {
        MeshBuild<?> build = mesh.build();
        if (!resources.available(build.positions().resource())
                || !resources.available(build.indices().resource())) return java.util.Optional.empty();
        boolean instanceAvailable = resources.available(instance.instanceData().resource());
        List<RtRetainedGeometryPlan.ResolvedGeometry> geometries = new ArrayList<>();
        for (int index = 0; index < build.geometries().size(); index++) {
            MeshBuild.Geometry<?> geometry = build.geometries().get(index);
            RetainedSceneSnapshot.GeometryPrograms programs = mesh.geometryPrograms().get(index);
            boolean surfaceAvailable = instanceAvailable && geometry.surface() != null
                    && resources.available(geometry.surface().bindingData().resource());
            boolean volumeAvailable = instanceAvailable && geometry.volume() != null
                    && resources.available(geometry.volume().bindingData().resource());
            geometries.add(new RtRetainedGeometryPlan.ResolvedGeometry(geometry,
                    surfaceAvailable ? programs.surfaceImplementation() : 0,
                    volumeAvailable ? programs.volumeImplementation() : 0,
                    surfaceAvailable ? geometry.surface().bindingData().bits() : 0L,
                    volumeAvailable ? geometry.volume().bindingData().bits() : 0L));
        }
        return java.util.Optional.of(new ResolvedFrameInput(
                new RtRetainedGeometryPlan.ResolvedMesh(build, geometries),
                new RtRetainedGeometryPlan.ResolvedPlacement(instance.transform(),
                        instanceAvailable ? instance.instanceData().bits() : 0L)));
    }

    private static Map<SceneId, List<LatchedInstance>> resolveFrameInstances(
            Map<SceneId, List<LatchedInstance>> captured, ResourceLeaseSet resources) {
        Map<SceneId, List<LatchedInstance>> resolved = new IdentityHashMap<>();
        captured.forEach((scene, values) -> {
            List<LatchedInstance> sceneInstances = new ArrayList<>();
            int geometryBase = 0;
            for (LatchedInstance instance : values) {
                RetainedSceneSnapshot.Mesh mesh = instance.nativeInstance.mesh.logical;
                java.util.Optional<ResolvedFrameInput> input =
                        resolveFrameInput(mesh, instance.current, resources);
                if (input.isEmpty()) continue;
                sceneInstances.add(instance.resolve(input.get().mesh(), input.get().placement(), geometryBase));
                geometryBase = Math.addExact(geometryBase, mesh.build().geometries().size());
            }
            resolved.put(scene, List.copyOf(sceneInstances));
        });
        return Collections.unmodifiableMap(resolved);
    }

    private FrameSceneSnapshot frameScene(SceneId scene, GraphicsUse graphicsUse) {
        return frameLease(scene, graphicsUse).scene(scene);
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
    }

    private static SharedResource<SceneLayoutGeneration> sharedLayout(
            Map<Long, MeshGeneration> meshes, Map<SceneId, List<NativeInstance>> instances) {
        return SharedResource.owned(
                new SceneLayoutGeneration(meshes, instances), SceneLayoutGeneration::close);
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
        private SharedResource<SceneRevisionRoot> root;
        private final Map<SceneId, List<LatchedInstance>> currentInstances;
        private final Map<SceneId, SceneContent> currentContent;
        private ResourceLeaseSet resources;
        private final Map<SceneId, FrameSceneSnapshot> scenes = new IdentityHashMap<>();

        FrameSnapshot(GraphicsUse graphicsUse, SharedResource<SceneRevisionRoot> root) {
            this.graphicsUse = graphicsUse;
            this.root = root;
            ResourceLeaseSet acquired = null;
            try {
                Map<SceneId, List<LatchedInstance>> captured = captureFrameValues(
                        root.get().geometry().instances,
                        instance -> new LatchedInstance(
                                instance, latestTransforms.resolve(instance.logical)));
                acquired = ResourceLeaseSet.capture(frameResourceReferences(root.get(), captured));
                this.currentInstances = resolveFrameInstances(captured, acquired);
                this.currentContent = resolveFrameContent(root.get().content, acquired);
                this.resources = acquired;
            } catch (Throwable failure) {
                this.root = null;
                if (acquired != null) suppressCleanupFailure(failure, acquired::close);
                suppressCleanupFailure(failure, root::close);
                throw failure;
            }
        }

        SceneRevisionRoot root() {
            return root.get();
        }

        FrameSceneSnapshot scene(SceneId scene) {
            FrameSceneSnapshot existing = scenes.get(scene);
            if (existing != null) return existing;
            SharedResource<SceneMotionHistory> historyLease = motionHistoryByScene.get(scene);
            SharedResource<SceneMotionHistory> retainedHistory = historyLease == null
                    ? null : historyLease.retain();
            try {
                SceneMotionHistory history = retainedHistory == null ? null : retainedHistory.get();
                List<FrameInstanceSnapshot> instances = new ArrayList<>();
                for (LatchedInstance frameCurrent : currentInstances.get(scene)) {
                    NativeInstance instance = frameCurrent.nativeInstance;
                    RetainedSceneSnapshot.Instance effective = frameCurrent.current;
                    GeometryTransform previousTransform = effective.transform();
                    MeshBuild.Stream previousPositions = instance.mesh.logical.build().positions();
                    MotionInstanceHistory prior = history == null
                            ? null : history.instances.get(instance.logical.identity());
                    if (prior != null
                            && prior.meshIdentity == instance.logical.meshIdentity()
                            && prior.placementOrdinal == instance.placementOrdinal) {
                        previousTransform = prior.transform;
                        if (prior.topology.compatibleWith(instance.mesh.logical.build())
                                && history.positionResources.available(
                                prior.positions.resource())) {
                            previousPositions = prior.positions;
                        }
                    }
                    instances.add(new FrameInstanceSnapshot(
                            instance, effective, frameCurrent.resolvedMesh,
                            frameCurrent.resolvedPlacement, previousTransform, previousPositions,
                            frameCurrent.geometryBase, frameCurrent.sbtRecordOffset));
                }
                FrameSceneSnapshot created = new FrameSceneSnapshot(
                        currentContent.get(scene), List.copyOf(instances), retainedHistory);
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
                    List<ResourceRef> positions = entry.getValue().instances.stream().map(instance ->
                            instance.nativeInstance.mesh.logical.build().positions().resource()).toList();
                    ResourceLeaseSet positionResources = resources.retainOnly(positions);
                    SharedResource<SceneMotionHistory> replacement = null;
                    try {
                        Map<Long, MotionInstanceHistory> historyInstances = new LinkedHashMap<>();
                        for (FrameInstanceSnapshot instance : entry.getValue().instances) {
                            NativeInstance nativeInstance = instance.nativeInstance;
                            MeshBuild<?> build = nativeInstance.mesh.logical.build();
                            historyInstances.put(nativeInstance.logical.identity(), new MotionInstanceHistory(
                                    nativeInstance.logical.meshIdentity(), nativeInstance.placementOrdinal,
                                    instance.current.transform(),
                                    MotionTopology.capture(build), build.positions()));
                        }
                        SceneMotionHistory history = new SceneMotionHistory(Map.copyOf(historyInstances),
                                positionResources);
                        replacement = SharedResource.owned(history, SceneMotionHistory::close);
                        positionResources = null;
                    } finally {
                        if (positionResources != null) positionResources.close();
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
            SharedResource<SceneRevisionRoot> released;
            ResourceLeaseSet releasedResources;
            List<FrameSceneSnapshot> releasedScenes;
            synchronized (RtRetainedSceneBackend.this) {
                if (root == null) return;
                inFlightFrames.remove(graphicsUse, this);
                released = root;
                root = null;
                releasedResources = resources;
                resources = null;
                releasedScenes = List.copyOf(scenes.values());
                scenes.clear();
            }
            Throwable failure = null;
            try {
                released.close();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            try {
                releasedResources.close();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
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

    private record FrameInstanceSnapshot(NativeInstance nativeInstance,
                                         RetainedSceneSnapshot.Instance current,
                                         RtRetainedGeometryPlan.ResolvedMesh resolvedMesh,
                                         RtRetainedGeometryPlan.ResolvedPlacement resolvedPlacement,
                                         GeometryTransform previousTransform,
                                         MeshBuild.Stream previousPositions,
                                         int geometryBase, int sbtRecordOffset) { }

    private record LatchedInstance(NativeInstance nativeInstance,
                                   RetainedSceneSnapshot.Instance current,
                                   RtRetainedGeometryPlan.ResolvedMesh resolvedMesh,
                                   RtRetainedGeometryPlan.ResolvedPlacement resolvedPlacement,
                                   int geometryBase, int sbtRecordOffset) {
        LatchedInstance(NativeInstance nativeInstance, RetainedSceneSnapshot.Instance current) {
            this(nativeInstance, current, null, null, 0, 0);
        }
        LatchedInstance resolve(RtRetainedGeometryPlan.ResolvedMesh mesh,
                                RtRetainedGeometryPlan.ResolvedPlacement placement, int geometryBase) {
            return new LatchedInstance(nativeInstance, current, mesh, placement, geometryBase,
                    Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY));
        }
    }

    record ResolvedFrameInput(RtRetainedGeometryPlan.ResolvedMesh mesh,
                              RtRetainedGeometryPlan.ResolvedPlacement placement) { }

    private record MotionInstanceHistory(long meshIdentity, long placementOrdinal,
                                         GeometryTransform transform, MotionTopology topology,
                                         MeshBuild.Stream positions) { }

    private record MotionTopology(int vertexCount, MeshBuild.IndexRevision indexRevision,
                                  List<IndexRange> geometries) {
        static MotionTopology capture(MeshBuild<?> build) {
            return new MotionTopology(build.vertexCount(), build.indexRevision(), build.geometries().stream()
                    .map(geometry -> new IndexRange(geometry.firstIndex(), geometry.indexCount())).toList());
        }

        boolean compatibleWith(MeshBuild<?> build) {
            if (vertexCount != build.vertexCount() || indexRevision == null
                    || !indexRevision.equals(build.indexRevision())
                    || geometries.size() != build.geometries().size()) return false;
            for (int index = 0; index < geometries.size(); index++) {
                IndexRange previous = geometries.get(index);
                MeshBuild.Geometry<?> current = build.geometries().get(index);
                if (previous.firstIndex != current.firstIndex()
                        || previous.indexCount != current.indexCount()) return false;
            }
            return true;
        }
    }

    private record IndexRange(int firstIndex, int indexCount) { }

    private static final class SceneMotionHistory implements AutoCloseable {
        final Map<Long, MotionInstanceHistory> instances;
        final ResourceLeaseSet positionResources;

        SceneMotionHistory(Map<Long, MotionInstanceHistory> instances,
                           ResourceLeaseSet positionResources) {
            this.instances = instances;
            this.positionResources = positionResources;
        }

        @Override public void close() {
            positionResources.close();
        }
    }

    public record SceneLight(long identity, LightDescriptor descriptor) {
        public SceneLight { Objects.requireNonNull(descriptor, "descriptor"); }
    }

    public record SceneContent(EnvironmentBinding<?> environment, List<SceneLight> lights) {
        public SceneContent {
            lights = List.copyOf(lights);
        }
    }

    public record LightingFrame(int width, int height, long frameIndex, float metersPerSceneUnit,
                                boolean historyContinuous, boolean localHistoryContinuous) { }

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
     * Allocate this frame's geometry, hit-SBT, light, and emitter uploads, sized to what it actually
     * publishes and retired once the frame that traced against them completes. Each buffer is at least
     * one record so a scene with no geometry, lights, or emitters still yields an addressable range.
     */
    private static TraceSlot createTraceSlot(VulkanDeviceContext ctx, int geometryBytes, int hitBytes,
                                             int lightBytes, int emitterBytes, RtPipeline pipeline,
                                             GraphicsUse graphicsUse) {
        GpuBuffer geometry = null;
        GpuBuffer hits = null;
        GpuBuffer lights = null;
        GpuBuffer emitters = null;
        GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor = null;
        try {
            geometry = ctx.createBuffer(Math.max(RtRetainedGeometryPlan.RECORD_BYTES, geometryBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "retained geometry records");
            hits = ctx.createAlignedBuffer(Math.max(pipeline.retainedHitRecordStride(), hitBytes),
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true, "retained hit SBT",
                    pipeline.retainedHitTableAlignment());
            lights = ctx.createBuffer(Math.max(RtRetainedLightPlan.RECORD_BYTES, lightBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "retained light records");
            emitters = ctx.createBuffer(Math.max(Integer.BYTES, emitterBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "retained primitive-light indices");
            descriptor = ctx.descriptorHeap().allocateResources(1);
            TraceSlot slot = new TraceSlot(geometry, hits, lights, emitters, descriptor,
                    pipeline.retainedHitRecordStride());
            graphicsUse.whenComplete(slot::destroy);
            return slot;
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
        final GpuBuffer geometry;
        final GpuBuffer hits;
        final GpuBuffer lights;
        final GpuBuffer emitters;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor;
        final int hitStride;

        TraceSlot(GpuBuffer geometry, GpuBuffer hits, GpuBuffer lights, GpuBuffer emitters,
                  GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor, int hitStride) {
            this.geometry = geometry;
            this.hits = hits;
            this.lights = lights;
            this.emitters = emitters;
            this.tlasDescriptor = tlasDescriptor;
            this.hitStride = hitStride;
        }

        void destroy() {
            geometry.destroy();
            hits.destroy();
            lights.destroy();
            emitters.destroy();
            tlasDescriptor.destroy();
        }
    }

    private static final class MutableSceneContent {
        final EnvironmentBinding<?> environment;
        final List<SceneLight> lights = new ArrayList<>();
        MutableSceneContent(EnvironmentBinding<?> environment) { this.environment = environment; }
    }

    /** One mesh in the live table: its coherent generation plus the token of any newer build in flight. */
    private static final class MeshEntry implements AutoCloseable {
        MeshGeneration current;
        long pendingToken;
        @Override public void close() {
            MeshGeneration released = current;
            current = null;
            pendingToken = 0L;
            if (released != null) released.close();
        }
    }

    private record PendingBuild(long meshIdentity, long token, MeshGeneration generation,
                                BlasBuildUse buildUse) { }

    /** Either an immediately coherent generation or a build to submit, never both. */
    private record PlannedMesh(long identity, MeshGeneration coherent, PendingBuild build) { }
    private record NativeInstance(RetainedSceneSnapshot.Instance logical, MeshGeneration mesh,
                                  dev.comfyfluffy.caustica.api.geometry.GeometryTransform previousTransform,
                                  int geometryBase, int sbtRecordOffset, long placementOrdinal) { }
    private record CompletedBatch(List<PendingBuild> builds, Runnable published, Throwable failure) { }
    private record PreparedMesh(MeshGeneration mesh, BlasBuildUse buildUse) { }

    private static final class PublishedSceneRevision {
        final long revision;
        final SharedResource<SceneRevisionRoot> root;
        PublishedSceneRevision(long revision, SharedResource<SceneRevisionRoot> root) {
            this.revision = revision;
            this.root = root;
        }
        long revision() { return revision; }
        Map<SceneId, SceneContent> content() { return root.get().content; }
        SharedResource<SceneRevisionRoot> retainRoot() { return root.retain(); }
        void release() { root.close(); }
    }

    private static final class SceneRevisionRoot {
        final SharedResource<SceneLayoutGeneration> geometry;
        final Map<SceneId, SceneContent> content;
        SceneRevisionRoot(SharedResource<SceneLayoutGeneration> geometry, Map<SceneId, SceneContent> content) {
            this.geometry = geometry;
            this.content = Map.copyOf(content);
        }
        SceneLayoutGeneration geometry() { return geometry.get(); }
        void destroy() {
            geometry.close();
        }
    }

    private static final class SceneLayoutGeneration {
        final Map<Long, MeshGeneration> meshes;
        final Map<SceneId, List<NativeInstance>> instances;

        SceneLayoutGeneration(Map<Long, MeshGeneration> meshes, Map<SceneId, List<NativeInstance>> instances) {
            this.meshes = Map.copyOf(meshes);
            this.instances = Map.copyOf(instances);
        }
        void close() { closeAll(meshes.values(), null); }
    }

    private static final class MeshGeneration implements AutoCloseable {
        final RetainedSceneSnapshot.Mesh logical;
        final SharedResource<BlasGeneration> blas;
        MeshGeneration(RetainedSceneSnapshot.Mesh logical, SharedResource<BlasGeneration> blas) {
            this.logical = logical;
            this.blas = blas;
        }
        BlasGeneration blas() { return blas.get(); }
        MeshGeneration withLogical(RetainedSceneSnapshot.Mesh next) {
            return new MeshGeneration(next, blas.retain());
        }
        @Override public void close() { blas.close(); }
    }

    /** Scratch plus every source, position, and index generation read by one BUILD or UPDATE. */
    private static final class BlasBuildUse implements AutoCloseable {
        final RtAccel.PreparedBlas operation;
        final List<? extends AutoCloseable> dependencies;
        private boolean released;
        BlasBuildUse(RtAccel.PreparedBlas operation, List<? extends AutoCloseable> dependencies) {
            this.operation = operation;
            this.dependencies = List.copyOf(dependencies);
        }
        RtAccel.PreparedBlas operation() { return operation; }
        @Override public synchronized void close() {
            if (released) return;
            released = true;
            releaseBuildUseResources(
                    () -> RtAccel.freeBlasScratch(List.of(operation)), dependencies);
        }
    }

    private static final class BlasGeneration {
        final RtAccel.PreparedBlas buildOperation;
        final RtAccel accel;
        final GpuBuffer backing;
        BlasGeneration(RtAccel.PreparedBlas buildOperation, RtAccel accel, GpuBuffer backing) {
            this.buildOperation = buildOperation;
            this.accel = accel;
            this.backing = backing;
        }
        synchronized void destroy() {
            RtAccel.destroyCallerOwnedAccel(accel, backing);
        }
    }
}
