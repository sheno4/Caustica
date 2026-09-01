package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
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
import dev.comfyfluffy.caustica.engine.scene.RetainedInstanceTransform;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneContentSnapshot;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneGeometryDelta;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.RtAccel;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.renderer.raytracing.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Vulkan owner for immutable retained-scene publications. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final ArrayDeque<Publication> queued = new ArrayDeque<>();
    private final RetainedSceneProgressQueue<CompletedBuild> completed = new RetainedSceneProgressQueue<>();
    private final RtLatestInstanceTransforms latestTransforms = new RtLatestInstanceTransforms();
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResourceLease<SceneMotionHistory>> motionHistoryByScene =
            new IdentityHashMap<>();
    private PublishedSceneRevision published;
    private Throwable fatalFailure;
    private boolean closed;
    private boolean sessionClosing;
    private long nextPlacementOrdinal;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.neeAt = new RtNeeAtBackend(ctx);
    }

    @Override
    public synchronized void updateLatestInstanceTransforms(List<RetainedInstanceTransform> transforms) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
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

    /** Accepts a complete logical version. GPU publication remains ordered and atomic. */
    @Override
    public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable onPublished,
                                     Runnable previousRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        Objects.requireNonNull(previousRetired, "previousRetired");
        long tailRevision = tailRevision();
        if (snapshot.revision() <= tailRevision) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        Publication publication = new Publication(snapshot.revision(), onPublished, previousRetired);
        NativeVersion predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        try {
            publication.candidate = prepare(snapshot, predecessor);
            accept(publication);
            queued.addLast(publication);
            latestTransforms.acceptSnapshot(snapshot.instances());
        } catch (Throwable failure) {
            if (publication.candidate != null) {
                suppressCleanupFailure(failure, publication.candidate::releaseRejected);
            }
            throw failure;
        }
    }

    /** Accepts geometry without rematerializing or revalidating the unchanged logical world. */
    @Override
    public synchronized void publishGeometry(RetainedSceneGeometryDelta delta,
                                             Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                             Runnable onPublished, Runnable previousRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(fallbackSnapshot, "fallbackSnapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        Objects.requireNonNull(previousRetired, "previousRetired");
        if (delta.revision() <= tailRevision()) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        NativeVersion predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        if (predecessor == null) {
            throw new IllegalStateException("geometry delta needs a preceding scene publication");
        }
        Publication publication = new Publication(delta.revision(), onPublished, previousRetired);
        try {
            publication.candidate = prepare(delta, predecessor);
            accept(publication);
            queued.addLast(publication);
            latestTransforms.acceptMutations(delta.mutations());
        } catch (Throwable failure) {
            if (publication.candidate != null) {
                suppressCleanupFailure(failure, publication.candidate::releaseRejected);
            }
            throw failure;
        }
    }

    /** Accepts geometry and content into one native candidate and one revision queue slot. */
    @Override
    public synchronized void publishGeometryAndContent(
            RetainedSceneGeometryDelta geometry, RetainedSceneContentSnapshot content,
            Supplier<RetainedSceneSnapshot> fallbackSnapshot, Runnable onPublished,
            Runnable previousGeometryRetired, Runnable previousContentRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(fallbackSnapshot, "fallbackSnapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        Objects.requireNonNull(previousGeometryRetired, "previousGeometryRetired");
        Objects.requireNonNull(previousContentRetired, "previousContentRetired");
        if (geometry.revision() != content.revision()) {
            throw new IllegalArgumentException("geometry and content revisions must match");
        }
        if (geometry.revision() <= tailRevision()) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        NativeVersion predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        if (predecessor == null) {
            throw new IllegalStateException("combined publication needs a preceding scene publication");
        }
        Publication publication = new Publication(geometry.revision(), onPublished, () -> {
            Throwable failure = null;
            try {
                previousGeometryRetired.run();
            } catch (Throwable callbackFailure) {
                failure = callbackFailure;
            }
            try {
                previousContentRetired.run();
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("retained scene retirement failed", failure);
        });
        try {
            publication.candidate = prepare(geometry, predecessor,
                    assembleContent(content.scenes(), content.lights()));
            accept(publication);
            queued.addLast(publication);
            latestTransforms.acceptMutations(geometry.mutations());
        } catch (Throwable failure) {
            if (publication.candidate != null) {
                suppressCleanupFailure(failure, publication.candidate::releaseRejected);
            }
            throw failure;
        }
    }

    /** Accepts content in the same revision queue while sharing the preceding scene-layout generation. */
    @Override
    public synchronized void publishContent(RetainedSceneContentSnapshot snapshot, Runnable onPublished,
                                            Runnable previousRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(onPublished, "onPublished");
        Objects.requireNonNull(previousRetired, "previousRetired");
        if (snapshot.revision() <= tailRevision()) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        NativeVersion predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        if (predecessor == null) {
            throw new IllegalStateException("content publication needs preceding scene geometry");
        }
        Publication publication = new Publication(snapshot.revision(), onPublished, previousRetired);
        SharedResourceLease<SceneLayoutGeneration> geometry = null;
        try {
            geometry = predecessor.retainGeometry();
            publication.candidate = new PendingSceneRevision(snapshot.revision(), geometry,
                    assembleContent(snapshot.scenes(), snapshot.lights()));
            geometry = null;
            accept(publication);
            queued.addLast(publication);
        } catch (Throwable failure) {
            if (publication.candidate != null) {
                suppressCleanupFailure(failure, publication.candidate::releaseRejected);
            } else if (geometry != null) {
                SharedResourceLease<SceneLayoutGeneration> rejectedGeometry = geometry;
                suppressCleanupFailure(failure, rejectedGeometry::close);
            }
            throw failure;
        }
    }

    private long tailRevision() {
        return queued.isEmpty()
                ? published != null ? published.revision() : -1L
                : queued.getLast().revision;
    }

    @Override
    public synchronized void onProgressAvailable(Runnable wakeup) {
        completed.onProgressAvailable(wakeup);
    }

    /** Publishes completed snapshots in accepted revision order. */
    @Override
    public synchronized void progress() {
        requireOpen();
        CompletedBuild terminal;
        while ((terminal = completed.poll()) != null) {
            terminal.publication.complete(terminal.build, terminal.failure);
        }
        while (true) {
            Publication head = queued.peekFirst();
            if (head == null || !head.completed) return;
            finishBuild(head);
        }
    }

    @Override
    public synchronized void prepareForSessionClose() {
        requireOpen();
        if (sessionClosing) return;
        settleAndReleaseTerminalFrameRoots(ctx.gpuExecutor()::drainAndWaitIdle,
                inFlightFrames, motionHistoryByScene);
        sessionClosing = true;
    }

    public synchronized long publishedRevision() {
        requireOpen();
        return published == null ? -1L : published.revision();
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
        while (!queued.isEmpty()) {
            Publication publication = queued.removeFirst();
            try {
                publication.candidate.releaseAfterDeviceIdle();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
            try {
                publication.retirePrevious(null);
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
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

    private PendingSceneRevision prepare(RetainedSceneSnapshot snapshot, NativeVersion predecessor) {
        Map<Long, MeshGeneration> meshes = new LinkedHashMap<>();
        List<BlasBuildUse> buildUses = new ArrayList<>();
        try {
            for (RetainedSceneSnapshot.Mesh mesh : snapshot.meshes()) {
                MeshGeneration reused = predecessor == null ? null : predecessor.geometry().meshes.get(mesh.identity());
                if (reused != null && RtRetainedGeometryPlan.canReuseBlas(reused.logical.build(), mesh.build())) {
                    reused = reused.withLogical(mesh);
                    meshes.put(mesh.identity(), reused);
                    continue;
                }
                PreparedMesh prepared = reused != null && RtRetainedGeometryPlan.canRefitBlas(
                        reused.logical.build(), mesh.build())
                        ? prepareMeshRefit(mesh, reused)
                        : prepareMeshBuild(mesh);
                meshes.put(mesh.identity(), prepared.mesh());
                buildUses.add(prepared.buildUse());
            }
            PendingSceneRevision candidate = assemble(snapshot, meshes, predecessor);
            candidate.buildUses = List.copyOf(buildUses);
            return candidate;
        } catch (Throwable failure) {
            closeAll(buildUses, failure);
            closeAll(meshes.values(), failure);
            throw failure;
        }
    }

    private PendingSceneRevision prepare(RetainedSceneGeometryDelta delta, NativeVersion predecessor) {
        return prepare(delta, predecessor, predecessor.content());
    }

    private PendingSceneRevision prepare(RetainedSceneGeometryDelta delta, NativeVersion predecessor,
                               Map<SceneId, SceneContent> content) {
        Map<Long, MeshGeneration> meshes = new LinkedHashMap<>();
        List<BlasBuildUse> buildUses = new ArrayList<>();
        try {
            predecessor.geometry().meshes.forEach((identity, mesh) ->
                    meshes.put(identity, mesh.withLogical(mesh.logical)));
            Map<Long, RetainedSceneSnapshot.Mesh> finalMeshSets = new LinkedHashMap<>();
            Map<Long, Boolean> finalMeshDrops = new LinkedHashMap<>();
            for (RetainedSceneGeometryDelta.Mutation mutation : delta.mutations()) {
                if (mutation instanceof RetainedSceneGeometryDelta.SetMesh set) {
                    long identity = set.mesh().identity();
                    finalMeshDrops.remove(identity);
                    finalMeshSets.remove(identity);
                    finalMeshSets.put(identity, set.mesh());
                } else if (mutation instanceof RetainedSceneGeometryDelta.DropMesh drop) {
                    finalMeshSets.remove(drop.identity());
                    finalMeshDrops.remove(drop.identity());
                    finalMeshDrops.put(drop.identity(), Boolean.TRUE);
                }
            }
            for (long identity : finalMeshDrops.keySet()) {
                MeshGeneration removed = meshes.remove(identity);
                if (removed != null) removed.close();
            }
            for (RetainedSceneSnapshot.Mesh mesh : finalMeshSets.values()) {
                MeshGeneration previous = meshes.get(mesh.identity());
                if (previous != null && RtRetainedGeometryPlan.canReuseBlas(
                        previous.logical.build(), mesh.build())) {
                    MeshGeneration replacement = previous.withLogical(mesh);
                    meshes.put(mesh.identity(), replacement);
                    previous.close();
                    continue;
                }
                PreparedMesh prepared = previous != null && RtRetainedGeometryPlan.canRefitBlas(
                        previous.logical.build(), mesh.build())
                        ? prepareMeshRefit(mesh, previous)
                        : prepareMeshBuild(mesh);
                MeshGeneration replacement = prepared.mesh();
                buildUses.add(prepared.buildUse());
                MeshGeneration removed = meshes.put(mesh.identity(), replacement);
                if (removed != null) removed.close();
            }

            RtRetainedInstanceState instanceState = new RtRetainedInstanceState();
            predecessor.geometry().instances.values().forEach(values -> values.forEach(instance -> {
                instanceState.retain(instance.logical, instance.placementOrdinal);
            }));
            instanceState.apply(delta.mutations(), () -> ++nextPlacementOrdinal);

            Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
            Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
            predecessor.content().keySet().forEach(scene -> {
                instances.put(scene, new ArrayList<>());
                geometryBases.put(scene, 0);
            });
            instanceState.orderedInstances().forEach(instance -> {
                MeshGeneration mesh = meshes.get(instance.meshIdentity());
                int geometryBase = geometryBases.get(instance.scene());
                instances.get(instance.scene()).add(new NativeInstance(instance, mesh,
                        instanceState.previousTransform(instance),
                        geometryBase, Math.multiplyExact(geometryBase,
                                RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY),
                        instanceState.ordinal(instance.identity())));
                geometryBases.put(instance.scene(), Math.addExact(geometryBase,
                        mesh.logical.build().geometries().size()));
            });
            instances.replaceAll((ignored, value) -> List.copyOf(value));
            PendingSceneRevision candidate = new PendingSceneRevision(delta.revision(),
                    sharedLayout(meshes, instances), content);
            candidate.buildUses = List.copyOf(buildUses);
            return candidate;
        } catch (Throwable failure) {
            closeAll(buildUses, failure);
            closeAll(meshes.values(), failure);
            throw failure;
        }
    }

    private void accept(Publication publication) {
        PendingSceneRevision candidate = publication.candidate;
        if (candidate.buildUses.isEmpty()) {
            candidate.accepted = true;
            completeLater(publication, null, null);
            return;
        }
        List<RtAccel.PreparedBlas> operations = candidate.buildUses.stream()
                .map(BlasBuildUse::operation).toList();
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, operations),
                candidate::releaseBuildUses,
                (build, failure) -> completeLater(publication, build, failure));
        candidate.accepted = true;
    }

    private void completeLater(Publication publication, RtGpuExecutor.Build build, Throwable failure) {
        completed.add(new CompletedBuild(publication, build, failure));
    }

    private PreparedMesh prepareMeshBuild(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        ResourceLeaseSet inputs = ResourceLeaseSet.acquireRequired(List.of(
                build.positions().resource(), build.indices().resource()));
        try {
            RtAccel.PersistentBuild nativeBuild = RtAccel.prepareUpdateablePersistentBlasBuild(ctx,
                    build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                    build.indices().bytes().address(),
                    RtRetainedGeometryPlan.blasRanges(build), "retained mesh " + mesh.identity());
            BlasGeneration blas = new BlasGeneration(nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
            MeshGeneration generation = new MeshGeneration(mesh,
                    SharedResourceLease.owned(blas, BlasGeneration::destroy));
            BlasBuildUse buildUse = new BlasBuildUse(nativeBuild.op(), List.of(inputs));
            inputs = null;
            return new PreparedMesh(generation, buildUse);
        } finally {
            if (inputs != null) inputs.close();
        }
    }

    private PreparedMesh prepareMeshRefit(RetainedSceneSnapshot.Mesh mesh, MeshGeneration source) {
        SharedResourceLease<BlasGeneration> sourceBuild = source.blas.retain();
        ResourceLeaseSet inputs = null;
        try {
            inputs = ResourceLeaseSet.acquireRequired(List.of(
                    mesh.build().positions().resource(), mesh.build().indices().resource()));
            MeshBuild<?> build = mesh.build();
            RtAccel.PersistentBuild nativeBuild = RtAccel.preparePersistentBlasUpdate(ctx,
                    sourceBuild.get().buildOperation, build.positions().bytes().address(),
                    build.indices().bytes().address(), "retained mesh " + mesh.identity());
            BlasGeneration blas = new BlasGeneration(
                    nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
            MeshGeneration generation = new MeshGeneration(mesh,
                    SharedResourceLease.owned(blas, BlasGeneration::destroy));
            BlasBuildUse buildUse = new BlasBuildUse(nativeBuild.op(), List.of(sourceBuild, inputs));
            sourceBuild = null;
            inputs = null;
            return new PreparedMesh(generation, buildUse);
        } finally {
            if (sourceBuild != null) sourceBuild.close();
            if (inputs != null) inputs.close();
        }
    }

    private PendingSceneRevision assemble(RetainedSceneSnapshot snapshot, Map<Long, MeshGeneration> meshes,
                               NativeVersion predecessor) {
        Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
        Map<Long, NativeInstance> previousInstances = new LinkedHashMap<>();
        if (predecessor != null) {
            predecessor.geometry().instances.values().forEach(values -> values.forEach(
                    instance -> previousInstances.put(instance.logical.identity(), instance)));
        }
        Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
        for (RetainedSceneSnapshot.Scene scene : snapshot.scenes()) {
            instances.put(scene.id(), new ArrayList<>());
            geometryBases.put(scene.id(), 0);
        }
        for (RetainedSceneSnapshot.Instance instance : snapshot.instances()) {
            MeshGeneration mesh = meshes.get(instance.meshIdentity());
            int geometryBase = geometryBases.get(instance.scene());
            NativeInstance previous = previousInstances.get(instance.identity());
            long placementOrdinal = previous == null ? ++nextPlacementOrdinal : previous.placementOrdinal;
            instances.get(instance.scene()).add(new NativeInstance(instance, mesh,
                    previous == null ? instance.transform() : previous.logical.transform(), geometryBase,
                    Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY),
                    placementOrdinal));
            geometryBases.put(instance.scene(), Math.addExact(geometryBase,
                    mesh.logical.build().geometries().size()));
        }
        instances.replaceAll((ignored, value) -> List.copyOf(value));
        return new PendingSceneRevision(snapshot.revision(), sharedLayout(meshes, instances),
                assembleContent(snapshot.scenes(), snapshot.lights()));
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

    private void finishBuild(Publication publication) {
        if (queued.getFirst() != publication) {
            throw new IllegalStateException("retained scene publication order changed");
        }
        if (publication.failure != null) {
            fatalFailure = publication.failure;
            throw fatalException();
        }
        if (publication.build != null) {
            ctx.gpuExecutor().markPublished(publication.build);
        }
        queued.removeFirst();
        PublishedSceneRevision previous = published;
        published = publication.candidate.publish();
        pruneLatestTransforms();
        neeAt.retainScenes(published.content().keySet());
        retainRenderedScenes(published.content().keySet());
        Throwable failure = null;
        try {
            publication.published.run();
        } catch (Throwable callbackFailure) {
            failure = callbackFailure;
        }
        try {
            if (previous == null) publication.retirePrevious(null);
            else publication.retireDisplaced(previous);
        } catch (Throwable retirementFailure) {
            if (failure == null) failure = retirementFailure;
            else failure.addSuppressed(retirementFailure);
        }
        throwFailure(failure, "retained scene publication failed");
    }

    private void pruneLatestTransforms() {
        java.util.Set<Long> retained = new java.util.HashSet<>();
        if (published != null) collectInstanceIdentities(published.geometry(), retained);
        for (Publication publication : queued) {
            collectInstanceIdentities(publication.candidate.geometry(), retained);
        }
        latestTransforms.retainOnly(retained);
    }

    private void retainRenderedScenes(java.util.Set<SceneId> retained) {
        List<SharedResourceLease<SceneMotionHistory>> removed = new ArrayList<>();
        motionHistoryByScene.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            removed.add(entry.getValue());
            return true;
        });
        closeAll(removed, null);
    }

    private static void collectInstanceIdentities(SceneLayoutGeneration geometry, java.util.Set<Long> target) {
        geometry.instances.values().forEach(instances -> instances.forEach(
                instance -> target.add(instance.logical.identity())));
    }

    private PublishedSceneRevision requirePublishedScene(SceneId scene) {
        requireOpen();
        PublishedSceneRevision current = published;
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
        if (fatalFailure != null) throw fatalException();
    }

    private IllegalStateException fatalException() {
        return new IllegalStateException("accepted retained scene GPU work failed", fatalFailure);
    }

    private static SharedResourceLease<SceneLayoutGeneration> sharedLayout(
            Map<Long, MeshGeneration> meshes, Map<SceneId, List<NativeInstance>> instances) {
        return SharedResourceLease.owned(
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
        private SharedResourceLease<SceneRevisionRoot> root;
        private final Map<SceneId, List<LatchedInstance>> currentInstances;
        private final Map<SceneId, SceneContent> currentContent;
        private ResourceLeaseSet resources;
        private final Map<SceneId, FrameSceneSnapshot> scenes = new IdentityHashMap<>();

        FrameSnapshot(GraphicsUse graphicsUse, SharedResourceLease<SceneRevisionRoot> root) {
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
            SharedResourceLease<SceneMotionHistory> historyLease = motionHistoryByScene.get(scene);
            SharedResourceLease<SceneMotionHistory> retainedHistory = historyLease == null
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
            List<SharedResourceLease<SceneMotionHistory>> displaced = new ArrayList<>();
            synchronized (RtRetainedSceneBackend.this) {
                if (root == null) return;
                for (Map.Entry<SceneId, FrameSceneSnapshot> entry : scenes.entrySet()) {
                    if (!entry.getValue().traced()) continue;
                    List<ResourceRef> positions = entry.getValue().instances.stream().map(instance ->
                            instance.nativeInstance.mesh.logical.build().positions().resource()).toList();
                    ResourceLeaseSet positionResources = resources.retainOnly(positions);
                    SharedResourceLease<SceneRevisionRoot> legacyRoot = null;
                    SharedResourceLease<SceneMotionHistory> replacement = null;
                    try {
                        if (positions.stream().anyMatch(resource -> resource == ResourceRef.none())) {
                            // Legacy streams have no independent lease, so their publication retirement
                            // callback must remain deferred until this history generation is unused.
                            legacyRoot = root.retain();
                        }
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
                                positionResources, legacyRoot);
                        replacement = SharedResourceLease.owned(history, SceneMotionHistory::close);
                        positionResources = null;
                        legacyRoot = null;
                    } finally {
                        if (legacyRoot != null) legacyRoot.close();
                        if (positionResources != null) positionResources.close();
                    }
                    SharedResourceLease<SceneMotionHistory> previous =
                            motionHistoryByScene.put(entry.getKey(), replacement);
                    if (previous != null) displaced.add(previous);
                }
            }
            closeAll(displaced, null);
        }

        @Override
        public void close() {
            SharedResourceLease<SceneRevisionRoot> released;
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
        final SharedResourceLease<SceneMotionHistory> previousHistory;
        private boolean traced;

        FrameSceneSnapshot(SceneContent content, List<FrameInstanceSnapshot> instances,
                           SharedResourceLease<SceneMotionHistory> previousHistory) {
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
        final SharedResourceLease<SceneRevisionRoot> legacyRoot;

        SceneMotionHistory(Map<Long, MotionInstanceHistory> instances,
                           ResourceLeaseSet positionResources,
                           SharedResourceLease<SceneRevisionRoot> legacyRoot) {
            this.instances = instances;
            this.positionResources = positionResources;
            this.legacyRoot = legacyRoot;
        }

        @Override public void close() {
            Throwable failure = null;
            try {
                positionResources.close();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            if (legacyRoot != null) closeAll(List.of(legacyRoot), failure);
            throwFailure(failure, "motion history release failed");
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

    private static final class Publication {
        final long revision;
        final Runnable published;
        final Runnable previousRetired;
        PendingSceneRevision candidate;
        RtGpuExecutor.Build build;
        volatile boolean completed;
        volatile Throwable failure;
        private boolean previousSettled;
        Publication(long revision, Runnable published, Runnable previousRetired) {
            this.revision = revision;
            this.published = published;
            this.previousRetired = previousRetired;
        }
        void complete(RtGpuExecutor.Build build, Throwable failure) {
            this.build = build;
            this.failure = failure;
            completed = true;
        }
        synchronized void retirePrevious(Runnable release) {
            if (previousSettled) return;
            previousSettled = true;
            Throwable failure = null;
            if (release != null) {
                try {
                    release.run();
                } catch (Throwable releaseFailure) {
                    failure = releaseFailure;
                }
            }
            try {
                previousRetired.run();
            } catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            if (failure != null) throw new IllegalStateException("retained scene retirement failed", failure);
        }
        synchronized void retireDisplaced(PublishedSceneRevision previous) {
            if (previousSettled) return;
            previousSettled = true;
            previous.retire(previousRetired);
        }
    }
    private record NativeInstance(RetainedSceneSnapshot.Instance logical, MeshGeneration mesh,
                                  dev.comfyfluffy.caustica.api.geometry.GeometryTransform previousTransform,
                                  int geometryBase, int sbtRecordOffset, long placementOrdinal) { }
    private record CompletedBuild(Publication publication, RtGpuExecutor.Build build, Throwable failure) { }
    private record PreparedMesh(MeshGeneration mesh, BlasBuildUse buildUse) { }

    private interface NativeVersion {
        long revision();
        SceneLayoutGeneration geometry();
        SharedResourceLease<SceneLayoutGeneration> retainGeometry();
        Map<SceneId, SceneContent> content();
    }

    private static final class PendingSceneRevision implements NativeVersion {
        final long revision;
        final SharedResourceLease<SceneLayoutGeneration> geometry;
        final Map<SceneId, SceneContent> content;
        List<BlasBuildUse> buildUses = List.of();
        boolean accepted;
        volatile boolean buildResourcesReleased;
        PendingSceneRevision(long revision, SharedResourceLease<SceneLayoutGeneration> geometry,
                  Map<SceneId, SceneContent> content) {
            this.revision = revision;
            this.geometry = geometry;
            this.content = Map.copyOf(content);
        }
        @Override public long revision() { return revision; }
        @Override public SceneLayoutGeneration geometry() { return geometry.get(); }
        @Override public SharedResourceLease<SceneLayoutGeneration> retainGeometry() { return geometry.retain(); }
        @Override public Map<SceneId, SceneContent> content() { return content; }
        PublishedSceneRevision publish() {
            SharedResourceLease<SceneLayoutGeneration> rootGeometry = geometry.retain();
            SharedResourceLease<SceneRevisionRoot> rootLease = null;
            try {
                SceneRevisionRoot root = new SceneRevisionRoot(rootGeometry, content);
                rootLease = SharedResourceLease.owned(root, SceneRevisionRoot::destroy);
                rootGeometry = null;
                geometry.close();
                return new PublishedSceneRevision(revision, rootLease);
            } catch (Throwable failure) {
                if (rootLease != null) {
                    SharedResourceLease<SceneRevisionRoot> rejectedRoot = rootLease;
                    suppressCleanupFailure(failure, rejectedRoot::close);
                } else if (rootGeometry != null) {
                    SharedResourceLease<SceneLayoutGeneration> rejectedGeometry = rootGeometry;
                    suppressCleanupFailure(failure, rejectedGeometry::close);
                }
                throw failure;
            }
        }
        void release() { geometry.close(); }
        void releaseRejected() {
            if (accepted) return;
            Throwable failure = null;
            try {
                releaseBuildUses();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            try {
                release();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
            throwFailure(failure, "rejected retained scene release failed");
        }
        void releaseAfterDeviceIdle() {
            Throwable failure = null;
            try {
                releaseBuildUses();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            try {
                release();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
            throwFailure(failure, "terminal retained scene release failed");
        }
        synchronized void releaseBuildUses() {
            if (buildResourcesReleased) return;
            buildResourcesReleased = true;
            closeAll(buildUses, null);
        }
    }

    private static final class PublishedSceneRevision implements NativeVersion {
        final long revision;
        final SharedResourceLease<SceneRevisionRoot> root;
        PublishedSceneRevision(long revision, SharedResourceLease<SceneRevisionRoot> root) {
            this.revision = revision;
            this.root = root;
        }
        @Override public long revision() { return revision; }
        @Override public SceneLayoutGeneration geometry() { return root.get().geometry(); }
        @Override public SharedResourceLease<SceneLayoutGeneration> retainGeometry() {
            return root.get().geometry.retain();
        }
        @Override public Map<SceneId, SceneContent> content() { return root.get().content; }
        SharedResourceLease<SceneRevisionRoot> retainRoot() { return root.retain(); }
        void retire(Runnable retired) {
            root.get().retire(retired);
            root.close();
        }
        void release() { root.close(); }
    }

    private static final class SceneRevisionRoot {
        final SharedResourceLease<SceneLayoutGeneration> geometry;
        final Map<SceneId, SceneContent> content;
        private Runnable retired;
        private boolean retirementAssigned;
        SceneRevisionRoot(SharedResourceLease<SceneLayoutGeneration> geometry, Map<SceneId, SceneContent> content) {
            this.geometry = geometry;
            this.content = Map.copyOf(content);
        }
        SceneLayoutGeneration geometry() { return geometry.get(); }
        synchronized void retire(Runnable callback) {
            if (retirementAssigned) throw new IllegalStateException("snapshot retirement is already assigned");
            retirementAssigned = true;
            retired = Objects.requireNonNull(callback, "callback");
        }
        void destroy() {
            Throwable failure = null;
            try {
                geometry.close();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
            }
            Runnable callback;
            synchronized (this) {
                callback = retired;
                retired = null;
            }
            if (callback != null) {
                try {
                    callback.run();
                } catch (Throwable callbackFailure) {
                    if (failure == null) failure = callbackFailure;
                    else failure.addSuppressed(callbackFailure);
                }
            }
            throwFailure(failure, "retained scene snapshot retirement failed");
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
        final SharedResourceLease<BlasGeneration> blas;
        MeshGeneration(RetainedSceneSnapshot.Mesh logical, SharedResourceLease<BlasGeneration> blas) {
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
