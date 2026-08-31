package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddressRange;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
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
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.TrackedGraphicsUse;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Vulkan owner for immutable retained-scene publications. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final Map<SceneId, TlasBuilder.Buffers> tlasBuffers = new IdentityHashMap<>();
    private final Map<SceneId, TraceBuffers> traceBuffers = new IdentityHashMap<>();
    private final ArrayDeque<Publication> queued = new ArrayDeque<>();
    private final RetainedSceneProgressQueue<CompletedBuild> completed = new RetainedSceneProgressQueue<>();
    private final RtLatestInstanceTransforms latestTransforms = new RtLatestInstanceTransforms();
    private final Map<SceneId, Map<Long, RtLatestInstanceTransforms.Resolved>> frameTransforms =
            new IdentityHashMap<>();
    private NativeSnapshot published;
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
        NativeSnapshot current = requirePublishedScene(scene);
        current.graphicsUse.mark(graphicsUse);
        SceneContent content = current.content.get(scene);
        var input = new RtNeeAtBackend.FrameInput(frame.width(), frame.height(), frame.frameIndex(),
                frame.metersPerSceneUnit(), frame.historyContinuous(), frame.localHistoryContinuous());
        return new PreparedLighting(neeAt.prepare(scene, content.lights(), input,
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
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        try {
            publication.candidate = prepare(snapshot, predecessor);
            accept(publication);
            queued.addLast(publication);
            latestTransforms.acceptSnapshot(snapshot.instances());
        } catch (Throwable failure) {
            if (publication.candidate != null) publication.candidate.releaseRejected();
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
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
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
            if (publication.candidate != null) publication.candidate.releaseRejected();
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
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
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
            if (publication.candidate != null) publication.candidate.releaseRejected();
            throw failure;
        }
    }

    /** Accepts content in the same revision queue while sharing the preceding native geometry generation. */
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
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        if (predecessor == null) {
            throw new IllegalStateException("content publication needs preceding scene geometry");
        }
        Publication publication = new Publication(snapshot.revision(), onPublished, previousRetired);
        try {
            predecessor.geometry.retain();
            publication.candidate = new Candidate(snapshot.revision(), predecessor.geometry,
                    assembleContent(snapshot.scenes(), snapshot.lights()), true);
            accept(publication);
            queued.addLast(publication);
        } catch (Throwable failure) {
            if (publication.candidate != null) publication.candidate.releaseRejected();
            else predecessor.geometry.release();
            throw failure;
        }
    }

    private long tailRevision() {
        return queued.isEmpty()
                ? published != null ? published.revision : -1L
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
        sessionClosing = enterSessionClose(sessionClosing, ctx.gpuExecutor()::drainAndWaitIdle);
    }

    static boolean enterSessionClose(boolean closing, Runnable settleAcceptedWork) {
        if (closing) return true;
        settleAcceptedWork.run();
        return true;
    }

    public synchronized long publishedRevision() {
        requireOpen();
        return published == null ? -1L : published.revision;
    }

    /** Immutable light/environment view for one target scene. */
    public synchronized SceneContent content(SceneId scene) {
        NativeSnapshot current = requirePublishedScene(scene);
        return current.content.get(scene);
    }

    /** Prepares only the TLAS belonging to {@code scene}; no implicit root-scene global is used. */
    public synchronized TlasBuilder.Prepared prepareTlas(SceneId scene, SceneOrigin origin, GraphicsUse graphicsUse) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        NativeSnapshot current = requirePublishedScene(scene);
        List<NativeInstance> source = current.geometry.instances.get(scene);
        TlasBuilder.InstanceBatch instances = new TlasBuilder.InstanceBatch();
        instances.reset(source.size());
        Map<Long, RtLatestInstanceTransforms.Resolved> latched = new LinkedHashMap<>();
        for (NativeInstance instance : source) {
            RtLatestInstanceTransforms.Resolved resolved = latestTransforms.latch(
                    instance.logical, instance.previousTransform);
            latched.put(instance.logical.identity(), resolved);
            float[] transform = resolved.instance().transform().relativeTo(origin.x(), origin.y(), origin.z());
            instances.append(transform, 0, 0, 0, instance.mesh.accel.deviceAddress,
                    instance.geometryBase, resolved.instance().mask(), instance.sbtRecordOffset);
        }
        frameTransforms.put(scene, Map.copyOf(latched));
        current.graphicsUse.mark(graphicsUse);
        TlasBuilder.Buffers buffers = tlasBuffers.computeIfAbsent(
                scene, ignored -> new TlasBuilder.Buffers());
        return TlasBuilder.prepare(ctx, instances, buffers, graphicsUse);
    }

    /** Packs the GeometryIndex-addressed records for one scene in the same order as its TLAS hit bases. */
    public synchronized ByteBuffer geometryRecords(SceneId scene, SceneOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.geometry.instances.get(scene)) {
            RtLatestInstanceTransforms.Resolved resolved = resolve(scene, instance);
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, resolved.instance(),
                    resolved.previous()));
        }
        return RtRetainedGeometryPlan.pack(records, origin);
    }

    /** Hit-group handle selection in exact {@code geometry * rayType} SBT order. */
    public synchronized List<RtRetainedGeometryPlan.HitGroup> hitGroups(SceneId scene) {
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.geometry.instances.get(scene)) {
            RtLatestInstanceTransforms.Resolved resolved = resolve(scene, instance);
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, resolved.instance(),
                    resolved.previous()));
        }
        return RtRetainedGeometryPlan.hitGroups(records);
    }

    /** Uploads this frame's rebased geometry records and pipeline-specific hit SBT into this scene's buffers. */
    public synchronized PreparedTrace prepareTrace(SceneId scene, SceneOrigin origin, RtPipeline pipeline,
                                                   long tlasHandle, GraphicsUse graphicsUse) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        NativeSnapshot current = requirePublishedScene(scene);
        current.graphicsUse.mark(graphicsUse);
        List<SceneLight> sceneLights = content(scene).lights();
        Map<Long, Integer> lightIndices = new LinkedHashMap<>();
        for (int index = 0; index < sceneLights.size(); index++) {
            lightIndices.put(sceneLights.get(index).identity(), index);
        }
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        List<Integer> emitterOffsets = new ArrayList<>();
        int emitterBytes = 0;
        for (NativeInstance instance : current.geometry.instances.get(scene)) {
            RtLatestInstanceTransforms.Resolved resolved = resolve(scene, instance);
            List<RtRetainedGeometryPlan.GeometryRecord> instanceRecords = RtRetainedGeometryPlan.records(
                    instance.mesh.logical, resolved.instance(), resolved.previous());
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
        TraceBuffers buffers = traceBuffers.computeIfAbsent(scene, ignored -> new TraceBuffers());
        int geometryBytes = Math.multiplyExact(records.size(), RtRetainedGeometryPlan.RECORD_BYTES);
        int lightBytes = Math.multiplyExact(sceneLights.size(), RtRetainedLightPlan.RECORD_BYTES);
        TraceSlot slot = buffers.next(ctx, geometryBytes, hits.remaining(), lightBytes, emitterBytes, pipeline);
        List<RtRetainedGeometryPlan.GeometryRecord> addressedRecords = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            int emitterOffset = emitterOffsets.get(index);
            addressedRecords.add(emitterOffset < 0 ? records.get(index) : records.get(index).withEmitterIndex(
                    slot.emitters.deviceAddress().addBytes(emitterOffset), 0));
        }
        ByteBuffer geometry = RtRetainedGeometryPlan.pack(addressedRecords, origin);
        ByteBuffer emitters = ByteBuffer.allocate(emitterBytes).order(ByteOrder.nativeOrder());
        boolean[] linkedEmitters = new boolean[sceneLights.size()];
        for (NativeInstance instance : current.geometry.instances.get(scene)) {
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
        slot.graphicsUse.mark(graphicsUse);
        RtPipeline.HitTable hitTable = hits.hasRemaining() ? new RtPipeline.HitTable(
                new VulkanDeviceAddressRange(slot.hits.deviceAddress(), hits.remaining()),
                pipeline.retainedHitRecordStride()) : null;
        RtNeeAtBackend.Prepared lighting = neeAt.active(scene);
        if (lighting == null) throw new IllegalStateException("prepareLighting must precede prepareTrace");
        lighting.bindLightTable(slot.lights.deviceAddress());
        return new PreparedTrace(slot.geometry.deviceAddress(), lighting.stateAddress(),
                slot.tlasDescriptor.firstIndex().value(), hitTable);
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
        if (published != null) {
            try {
                published.release();
            } catch (Throwable releaseFailure) {
                failure = releaseFailure;
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
        for (TlasBuilder.Buffers buffers : tlasBuffers.values()) {
            try {
                buffers.destroy();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
        }
        tlasBuffers.clear();
        frameTransforms.clear();
        latestTransforms.retainOnly(java.util.Set.of());
        for (TraceBuffers buffers : traceBuffers.values()) buffers.destroy();
        traceBuffers.clear();
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("retained scene shutdown failed", failure);
    }

    private Candidate prepare(RetainedSceneSnapshot snapshot, Candidate predecessor) {
        Map<Long, NativeMesh> meshes = new LinkedHashMap<>();
        List<NativeMesh> created = new ArrayList<>();
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        try {
            for (RetainedSceneSnapshot.Mesh mesh : snapshot.meshes()) {
                NativeMesh reused = predecessor == null ? null : predecessor.geometry.meshes.get(mesh.identity());
                if (reused != null && RtRetainedGeometryPlan.canReuseBlas(reused.logical.build(), mesh.build())) {
                    reused.retain();
                    reused = reused.withLogical(mesh);
                    meshes.put(mesh.identity(), reused);
                    continue;
                }
                NativeMesh nativeMesh = prepareMesh(mesh);
                created.add(nativeMesh);
                meshes.put(mesh.identity(), nativeMesh);
                builds.add(nativeMesh.buildOperation);
            }
            Candidate candidate = assemble(snapshot, meshes, predecessor);
            candidate.unsubmitted = created;
            candidate.builds = List.copyOf(builds);
            return candidate;
        } catch (Throwable failure) {
            for (NativeMesh mesh : meshes.values()) {
                if (created.contains(mesh)) RtAccel.releaseTransientBlas(mesh.buildOperation);
                else mesh.release();
            }
            throw failure;
        }
    }

    private Candidate prepare(RetainedSceneGeometryDelta delta, Candidate predecessor) {
        return prepare(delta, predecessor, predecessor.content);
    }

    private Candidate prepare(RetainedSceneGeometryDelta delta, Candidate predecessor,
                              Map<SceneId, SceneContent> content) {
        Map<Long, NativeMesh> meshes = new LinkedHashMap<>();
        predecessor.geometry.meshes.forEach((identity, mesh) -> {
            mesh.retain();
            meshes.put(identity, mesh);
        });
        List<NativeMesh> created = new ArrayList<>();
        List<RtAccel.PreparedBlas> builds = new ArrayList<>();
        try {
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
                NativeMesh removed = meshes.remove(identity);
                if (removed != null) removed.release();
            }
            for (RetainedSceneSnapshot.Mesh mesh : finalMeshSets.values()) {
                NativeMesh previous = meshes.get(mesh.identity());
                if (previous != null && RtRetainedGeometryPlan.canReuseBlas(
                        previous.logical.build(), mesh.build())) {
                    meshes.put(mesh.identity(), previous.withLogical(mesh));
                    continue;
                }
                NativeMesh replacement = prepareMesh(mesh);
                created.add(replacement);
                builds.add(replacement.buildOperation);
                NativeMesh removed = meshes.put(mesh.identity(), replacement);
                if (removed != null) removed.release();
            }

            RtRetainedInstanceState instanceState = new RtRetainedInstanceState();
            predecessor.geometry.instances.values().forEach(values -> values.forEach(instance -> {
                instanceState.retain(instance.logical, instance.placementOrdinal);
            }));
            instanceState.apply(delta.mutations(), () -> ++nextPlacementOrdinal);

            Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
            Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
            predecessor.content.keySet().forEach(scene -> {
                instances.put(scene, new ArrayList<>());
                geometryBases.put(scene, 0);
            });
            instanceState.orderedInstances().forEach(instance -> {
                NativeMesh mesh = meshes.get(instance.meshIdentity());
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
            Candidate candidate = new Candidate(delta.revision(), new NativeGeometry(meshes, instances),
                    content, false);
            candidate.unsubmitted = List.copyOf(created);
            candidate.builds = List.copyOf(builds);
            return candidate;
        } catch (Throwable failure) {
            for (NativeMesh mesh : meshes.values()) {
                if (created.contains(mesh)) RtAccel.releaseTransientBlas(mesh.buildOperation);
                else mesh.release();
            }
            throw failure;
        }
    }

    private void accept(Publication publication) {
        Candidate candidate = publication.candidate;
        if (candidate.builds.isEmpty()) {
            candidate.accepted = true;
            candidate.unsubmitted = List.of();
            completeLater(publication, null, null);
            return;
        }
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, candidate.builds),
                () -> {
                    RtAccel.freeBlasScratch(candidate.builds);
                    candidate.buildResourcesReleased = true;
                },
                (build, failure) -> completeLater(publication, build, failure));
        candidate.accepted = true;
        candidate.unsubmitted = List.of();
    }

    private void completeLater(Publication publication, RtGpuExecutor.Build build, Throwable failure) {
        completed.add(new CompletedBuild(publication, build, failure));
    }

    private NativeMesh prepareMesh(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        RtAccel.PersistentBuild nativeBuild = RtAccel.preparePersistentBlasBuild(ctx,
                build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                build.indices().bytes().address(),
                RtRetainedGeometryPlan.blasRanges(build), "retained mesh " + mesh.identity());
        return new NativeMesh(mesh, nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
    }

    private Candidate assemble(RetainedSceneSnapshot snapshot, Map<Long, NativeMesh> meshes,
                               Candidate predecessor) {
        Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
        Map<Long, NativeInstance> previousInstances = new LinkedHashMap<>();
        if (predecessor != null) {
            predecessor.geometry.instances.values().forEach(values -> values.forEach(
                    instance -> previousInstances.put(instance.logical.identity(), instance)));
        }
        Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
        for (RetainedSceneSnapshot.Scene scene : snapshot.scenes()) {
            instances.put(scene.id(), new ArrayList<>());
            geometryBases.put(scene.id(), 0);
        }
        for (RetainedSceneSnapshot.Instance instance : snapshot.instances()) {
            NativeMesh mesh = meshes.get(instance.meshIdentity());
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
        return new Candidate(snapshot.revision(), new NativeGeometry(meshes, instances),
                assembleContent(snapshot.scenes(), snapshot.lights()), false);
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
        NativeSnapshot previous = published;
        published = publication.candidate.publish();
        frameTransforms.clear();
        pruneLatestTransforms();
        neeAt.retainScenes(published.content.keySet());
        publication.published.run();
        if (previous == null) {
            publication.retirePrevious(null);
        } else {
            retirePreviousPublication(sessionClosing,
                    () -> ctx.gpuExecutor().retireAfterGraphics(previous.graphicsUse,
                            () -> publication.retirePrevious(previous::release)),
                    () -> publication.retirePrevious(previous::release));
        }
    }

    private RtLatestInstanceTransforms.Resolved resolve(SceneId scene, NativeInstance instance) {
        Map<Long, RtLatestInstanceTransforms.Resolved> latched = frameTransforms.get(scene);
        if (latched != null) {
            RtLatestInstanceTransforms.Resolved resolved = latched.get(instance.logical.identity());
            if (resolved != null) return resolved;
        }
        return latestTransforms.peek(instance.logical, instance.previousTransform);
    }

    private void pruneLatestTransforms() {
        java.util.Set<Long> retained = new java.util.HashSet<>();
        if (published != null) collectInstanceIdentities(published.geometry, retained);
        for (Publication publication : queued) {
            collectInstanceIdentities(publication.candidate.geometry, retained);
        }
        latestTransforms.retainOnly(retained);
    }

    private static void collectInstanceIdentities(NativeGeometry geometry, java.util.Set<Long> target) {
        geometry.instances.values().forEach(instances -> instances.forEach(
                instance -> target.add(instance.logical.identity())));
    }

    static void retirePreviousPublication(boolean closing, Runnable afterGraphics, Runnable afterIdle) {
        if (closing) afterIdle.run();
        else afterGraphics.run();
    }

    private NativeSnapshot requirePublishedScene(SceneId scene) {
        requireOpen();
        NativeSnapshot current = published;
        if (current == null || !current.content.containsKey(scene)) {
            throw new IllegalArgumentException("scene is not in the published native snapshot");
        }
        return current;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
    }

    private IllegalStateException fatalException() {
        return new IllegalStateException("accepted retained scene GPU work failed", fatalFailure);
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
     * Owns one scene's per-frame geometry, hit-SBT, light, and emitter uploads.
     *
     * <p>Reuse is guarded by an exact wait on the last frame that traced against these buffers.
     */
    private static final class TraceBuffers {
        private TraceSlot slot;

        TraceSlot next(VulkanDeviceContext ctx, int geometryBytes, int hitBytes, int lightBytes, int emitterBytes,
                       RtPipeline pipeline) {
            TraceSlot slot = this.slot;
            if (slot != null) ctx.gpuExecutor().graphicsUseWaiter().await(slot.graphicsUse);
            if (slot == null || slot.geometry.size() < geometryBytes || slot.hits.size() < hitBytes
                    || slot.lights.size() < lightBytes
                    || slot.emitters.size() < emitterBytes
                    || slot.hitStride != pipeline.retainedHitRecordStride()) {
                if (slot != null) {
                    this.slot = null;
                    slot.destroy();
                }
                int geometryCapacity = Math.max(RtRetainedGeometryPlan.RECORD_BYTES, geometryBytes);
                int hitCapacity = Math.max(pipeline.retainedHitRecordStride(), hitBytes);
                int lightCapacity = Math.max(RtRetainedLightPlan.RECORD_BYTES, lightBytes);
                int emitterCapacity = Math.max(Integer.BYTES, emitterBytes);
                GpuBuffer geometry = null;
                GpuBuffer hits = null;
                GpuBuffer lights = null;
                GpuBuffer emitters = null;
                GpuDescriptorRange<GpuDescriptorIndex.Resource> descriptor = null;
                try {
                    geometry = ctx.createBuffer(geometryCapacity, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            true, "retained geometry records");
                    hits = ctx.createAlignedBuffer(hitCapacity, VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR,
                            true, "retained hit SBT", pipeline.retainedHitTableAlignment());
                    lights = ctx.createBuffer(lightCapacity, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            true, "retained light records");
                    emitters = ctx.createBuffer(emitterCapacity, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            true, "retained primitive-light indices");
                    descriptor = ctx.descriptorHeap().allocateResources(1);
                    slot = new TraceSlot(geometry, hits, lights, emitters, descriptor,
                            pipeline.retainedHitRecordStride());
                    this.slot = slot;
                } catch (Throwable failure) {
                    if (descriptor != null) descriptor.destroy();
                    if (emitters != null) emitters.destroy();
                    if (lights != null) lights.destroy();
                    if (hits != null) hits.destroy();
                    if (geometry != null) geometry.destroy();
                    throw failure;
                }
            }
            return slot;
        }

        void destroy() {
            if (slot != null) slot.destroy();
        }
    }

    private static final class TraceSlot {
        final GpuBuffer geometry;
        final GpuBuffer hits;
        final GpuBuffer lights;
        final GpuBuffer emitters;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor;
        final int hitStride;
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();

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
        Candidate candidate;
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
    }
    private record NativeInstance(RetainedSceneSnapshot.Instance logical, NativeMesh mesh,
                                  dev.comfyfluffy.caustica.api.geometry.GeometryTransform previousTransform,
                                  int geometryBase, int sbtRecordOffset, long placementOrdinal) { }
    private record CompletedBuild(Publication publication, RtGpuExecutor.Build build, Throwable failure) { }

    private static class Candidate {
        final long revision;
        final NativeGeometry geometry;
        final Map<SceneId, SceneContent> content;
        final boolean contentOnly;
        List<NativeMesh> unsubmitted = List.of();
        List<RtAccel.PreparedBlas> builds = List.of();
        boolean accepted;
        volatile boolean buildResourcesReleased;
        Candidate(long revision, NativeGeometry geometry, Map<SceneId, SceneContent> content,
                  boolean contentOnly) {
            this.revision = revision;
            this.geometry = geometry;
            this.content = Map.copyOf(content);
            this.contentOnly = contentOnly;
        }
        NativeSnapshot publish() { return new NativeSnapshot(revision, geometry, content); }
        void release() { geometry.release(); }
        void releaseRejected() {
            if (accepted) return;
            if (contentOnly) {
                geometry.release();
                return;
            }
            for (NativeMesh mesh : geometry.meshes.values()) {
                if (unsubmitted.contains(mesh)) RtAccel.releaseTransientBlas(mesh.buildOperation);
                else mesh.release();
            }
        }
        void releaseAfterDeviceIdle() {
            if (!buildResourcesReleased && !builds.isEmpty()) {
                RtAccel.freeBlasScratch(builds);
                buildResourcesReleased = true;
            }
            release();
        }
    }

    private static final class NativeSnapshot extends Candidate {
        final TrackedGraphicsUse graphicsUse = new TrackedGraphicsUse();
        NativeSnapshot(long revision, NativeGeometry geometry, Map<SceneId, SceneContent> content) {
            super(revision, geometry, content, false);
        }
    }

    private static final class NativeGeometry {
        final Map<Long, NativeMesh> meshes;
        final Map<SceneId, List<NativeInstance>> instances;
        private int references = 1;

        NativeGeometry(Map<Long, NativeMesh> meshes, Map<SceneId, List<NativeInstance>> instances) {
            this.meshes = Map.copyOf(meshes);
            this.instances = Map.copyOf(instances);
        }

        synchronized void retain() { references++; }

        synchronized void release() {
            if (--references == 0) meshes.values().forEach(NativeMesh::release);
        }
    }

    private static class NativeMesh {
        final RetainedSceneSnapshot.Mesh logical;
        final RtAccel.PreparedBlas buildOperation;
        final RtAccel accel;
        final GpuBuffer backing;
        int references = 1;
        NativeMesh(RetainedSceneSnapshot.Mesh logical, RtAccel.PreparedBlas buildOperation, RtAccel accel,
                   GpuBuffer backing) {
            this.logical = logical;
            this.buildOperation = buildOperation;
            this.accel = accel;
            this.backing = backing;
        }
        synchronized void retain() { references++; }
        NativeMesh withLogical(RetainedSceneSnapshot.Mesh next) {
            return new NativeMeshView(this, next);
        }
        synchronized void release() {
            if (--references == 0) RtAccel.destroyCallerOwnedAccel(accel, backing);
        }
    }

    private static final class NativeMeshView extends NativeMesh {
        private final NativeMesh owner;
        NativeMeshView(NativeMesh owner, RetainedSceneSnapshot.Mesh logical) {
            super(logical, owner.buildOperation, owner.accel, owner.backing);
            this.owner = owner;
        }
        @Override synchronized void retain() { owner.retain(); }
        @Override synchronized void release() { owner.release(); }
    }
}
