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
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
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
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/**
 * Prepares GPU resources before atomically applying each scene edit. Independent edits may finish in
 * any order; edits touching the same retained identities preserve their accepted order.
 * Frames retain immutable revisions and all resource graphs reachable through their shader data.
 */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final RetainedSceneProgressQueue<CompletedBatch> completed = new RetainedSceneProgressQueue<>();
    private final RtLatestInstanceTransforms latestTransforms = new RtLatestInstanceTransforms();
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResource<SceneMotionHistory>> motionHistoryByScene =
            new IdentityHashMap<>();
    /** Every entry pairs geometry records with the BLAS built from that exact geometry revision. */
    private final Map<Long, MeshEntry> meshTable = new LinkedHashMap<>();
    private final RtRetainedInstanceState instanceState = new RtRetainedInstanceState();
    private Map<SceneId, SceneContent> liveContent = Map.of();
    private PublishedSceneRevision published;
    private long acceptedRevision = -1L;
    private long visibleRevision = -1L;
    private final ScenePublicationQueue<EditKey, PendingEdit> pendingEdits = new ScenePublicationQueue<>();
    private Map<SceneId, SceneContent> acceptedContent = Map.of();
    private ResourceOwners liveResources = ResourceOwners.capture(List.of());
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

    @Override
    public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable onPublished) {
        requireOpen();
        requireIncreasingRevision(snapshot.revision());
        List<PlannedMesh> planned = planMeshes(snapshot.meshes());
        var content = assembleContent(snapshot.scenes(), snapshot.lights());
        var resources = new ArrayList<ResourceRef>();
        snapshot.instances().forEach(instance -> resources.add(instance.instanceData().resource()));
        addEnvironmentResources(content, resources);
        var keys = new java.util.HashSet<EditKey>();
        keys.add(new EditKey("all", 0L));
        acceptEdit(snapshot.revision(), planned, keys, resources, () -> {
            var retained = snapshot.meshes().stream().map(RetainedSceneSnapshot.Mesh::identity)
                    .collect(java.util.stream.Collectors.toSet());
            new ArrayList<>(meshTable.keySet()).stream().filter(id -> !retained.contains(id)).forEach(this::dropMesh);
            instanceState.replaceAll(snapshot.instances(), () -> ++nextPlacementOrdinal);
            liveContent = content;
        }, onPublished);
        acceptedContent = content;
        latestTransforms.acceptSnapshot(snapshot.instances());
    }

    @Override
    public synchronized void publishGeometry(RetainedSceneGeometryDelta delta,
                                             Supplier<RetainedSceneSnapshot> fallbackSnapshot,
                                             Runnable onPublished) {
        requireOpen();
        requireIncreasingRevision(delta.revision());
        acceptGeometry(delta, null, onPublished);
    }

    @Override
    public synchronized void publishGeometryAndContent(RetainedSceneGeometryDelta delta,
            RetainedSceneContentSnapshot content, Supplier<RetainedSceneSnapshot> fallbackSnapshot,
            Runnable onPublished) {
        requireOpen();
        requireIncreasingRevision(delta.revision());
        acceptGeometry(delta, assembleContent(content.scenes(), content.lights()), onPublished);
    }

    @Override
    public synchronized void publishContent(RetainedSceneContentSnapshot snapshot, Runnable onPublished) {
        requireOpen();
        requireIncreasingRevision(snapshot.revision());
        Map<SceneId, SceneContent> content = assembleContent(snapshot.scenes(), snapshot.lights());
        ContentPatch patch = ContentPatch.between(acceptedContent, content);
        var resources = new ArrayList<ResourceRef>();
        addEnvironmentResources(content, resources);
        acceptEdit(snapshot.revision(), List.of(), patch.keys(), resources,
                () -> liveContent = patch.apply(liveContent), onPublished);
        acceptedContent = content;
    }

    private void acceptGeometry(RetainedSceneGeometryDelta delta,
                                Map<SceneId, SceneContent> content, Runnable onPublished) {
        var meshes = new LinkedHashMap<Long, RetainedSceneSnapshot.Mesh>();
        var keys = new java.util.HashSet<EditKey>();
        var resources = new ArrayList<ResourceRef>();
        for (var mutation : delta.mutations()) {
            switch (mutation) {
                case RetainedSceneGeometryDelta.SetMesh set -> {
                    meshes.put(set.mesh().identity(), set.mesh());
                    keys.add(new EditKey("mesh", set.mesh().identity()));
                }
                case RetainedSceneGeometryDelta.DropMesh drop -> {
                    meshes.remove(drop.identity());
                    keys.add(new EditKey("mesh", drop.identity()));
                }
                case RetainedSceneGeometryDelta.SetInstance set -> {
                    var instance = set.instance();
                    keys.add(new EditKey("instance", instance.identity()));
                    keys.add(new EditKey("mesh", instance.meshIdentity()));
                    resources.add(instance.instanceData().resource());
                }
                case RetainedSceneGeometryDelta.DropInstance drop ->
                        keys.add(new EditKey("instance", drop.identity()));
            }
        }
        ContentPatch patch = content == null ? ContentPatch.empty() : ContentPatch.between(acceptedContent, content);
        keys.addAll(patch.keys());
        if (content != null) addEnvironmentResources(content, resources);
        List<PlannedMesh> planned = planMeshes(meshes.values());
        acceptEdit(delta.revision(), planned, keys, resources, () -> {
            for (var mutation : delta.mutations()) {
                if (mutation instanceof RetainedSceneGeometryDelta.DropMesh drop && !meshes.containsKey(drop.identity())) {
                    dropMesh(drop.identity());
                }
            }
            instanceState.apply(delta.mutations(), () -> ++nextPlacementOrdinal);
            liveContent = patch.apply(liveContent);
        }, onPublished);
        if (content != null) acceptedContent = content;
        latestTransforms.acceptMutations(delta.mutations());
    }

    private void acceptEdit(long revision, List<PlannedMesh> planned, java.util.Set<EditKey> keys,
                            List<ResourceRef> references, Runnable apply, Runnable onPublished) {
        ResourceOwners owners;
        try {
            owners = ResourceOwners.capture(references);
        } catch (Throwable failure) {
            releasePlanned(planned, failure);
            throw failure;
        }
        PendingEdit edit = new PendingEdit(revision, planned, keys, owners, apply, onPublished);
        var ticket = pendingEdits.add(keys, keys.contains(new EditKey("all", 0L)), edit);
        try {
            submitPlanned(ticket);
        } catch (Throwable failure) {
            pendingEdits.remove(ticket);
            releasePlanned(planned, failure);
            owners.close();
            throw failure;
        }
        acceptedRevision = revision;
    }

    private void requireIncreasingRevision(long revision) {
        if (revision <= acceptedRevision) throw new IllegalArgumentException("scene revisions must increase");
    }

    private static void addEnvironmentResources(Map<SceneId, SceneContent> content, List<ResourceRef> references) {
        content.values().forEach(value -> {
            if (value.environment() != null) references.add(value.environment().bindingData().resource());
        });
    }

    @Override
    public synchronized void onProgressAvailable(Runnable wakeup) {
        completed.onProgressAvailable(wakeup);
    }

    /** Commits a ready edit only after earlier edits to its identities have committed. */
    @Override
    public synchronized void progress() {
        requireOpen();
        CompletedBatch completion;
        while ((completion = completed.poll()) != null) {
            completion.edit.ready = true;
            completion.edit.value.failure = completion.failure;
        }
        var callbacks = new ArrayList<Runnable>();
        pendingEdits.commitReady(edit -> {
            if (edit.failure != null) throwFailure(edit.failure, "retained scene preparation failed");
            installPlanned(edit.planned);
            edit.apply.run();
            var references = new ArrayList<ResourceRef>();
            instanceState.orderedInstances().forEach(instance -> references.add(instance.instanceData().resource()));
            addEnvironmentResources(liveContent, references);
            ResourceOwners next = ResourceOwners.capture(references);
            liveResources.close();
            liveResources = next;
            if (published != null) {
                published.release();
                published = null;
            }
            visibleRevision = Math.max(visibleRevision, edit.revision);
            neeAt.retainScenes(liveContent.keySet());
            retainRenderedScenes(liveContent.keySet());

            edit.resources.close();
            callbacks.add(edit.published);
        });
        if (pendingEdits.values().isEmpty()) pruneLatestTransforms();
        callbacks.forEach(Runnable::run);
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
            if (inFlightFrames.isEmpty() && motionHistoryByScene.isEmpty()) return;
        }
        ctx.drainAndWaitIdle();
        synchronized (this) {
            releaseTerminalFrameRoots();
        }
    }

    /** Highest committed edit revision; independent edits may complete out of acceptance order. */
    public synchronized long publishedRevision() {
        requireOpen();
        return visibleRevision;
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
        terminal.add(liveResources);
        for (PendingEdit edit : pendingEdits.values()) {
            terminal.add(edit.resources);
            for (PlannedMesh mesh : edit.planned) {
                if (mesh.coherent() != null) terminal.add(mesh.coherent());
                if (mesh.build() != null) {
                    terminal.add(mesh.build().generation());
                    terminal.add(mesh.build().buildUse());
                }
            }
        }
        pendingEdits.clear();
        meshTable.clear();
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
                        ? prepareMesh(mesh, current)
                        : prepareMesh(mesh, null);
                planned.add(new PlannedMesh(mesh.identity(), null,
                        new PendingBuild(prepared.mesh(), prepared.buildUse())));
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

    private void installPlanned(List<PlannedMesh> planned) {
        for (PlannedMesh mesh : planned) {
            MeshEntry entry = meshTable.computeIfAbsent(mesh.identity(), ignored -> new MeshEntry());
            MeshGeneration previous = entry.current;
            entry.current = mesh.coherent() != null ? mesh.coherent() : mesh.build().generation();
            if (previous != null) previous.close();
        }
    }

    private void dropMesh(long identity) {
        MeshEntry removed = meshTable.remove(identity);
        if (removed != null) removed.close();
    }

    private void submitPlanned(ScenePublicationQueue.Edit<EditKey, PendingEdit> ticket) {
        PendingEdit edit = ticket.value;
        var builds = edit.planned.stream().map(PlannedMesh::build).filter(Objects::nonNull).toList();
        if (builds.isEmpty()) {
            completed.add(new CompletedBatch(ticket, null));
            return;
        }
        var operations = builds.stream().map(build -> build.buildUse().operation()).toList();
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, operations), completion -> {
            Throwable failure = switch (completion) {
                case GpuComputeCompletion.Succeeded ignored -> null;
                case GpuComputeCompletion.Cancelled ignored -> new CancellationException("scene build cancelled");
                case GpuComputeCompletion.Failed failed -> failed.failure();
            };
            if (failure == null) {
                try {
                    closeAll(builds.stream().map(PendingBuild::buildUse).toList(), null);
                } catch (Throwable cleanup) {
                    failure = cleanup;
                }
            }
            completed.add(new CompletedBatch(ticket, failure));
        });
    }

    private PreparedMesh prepareMesh(RetainedSceneSnapshot.Mesh mesh, MeshGeneration source) {
        MeshBuild<?> build = mesh.build();
        var dependencies = new ArrayList<AutoCloseable>();
        RtAccel.PersistentBuild nativeBuild = null;
        SharedResource<BlasGeneration> blas = null;
        MeshGeneration generation = null;
        try {
            dependencies.add(ResourceOwners.capture(List.of(build.positions().resource(), build.indices().resource())));
            if (source == null) {
                nativeBuild = RtAccel.prepareUpdateablePersistentBlasBuild(ctx,
                        build.positions().bytes().address(), build.positions().byteStride(), build.vertexCount(),
                        build.indices().bytes().address(), RtRetainedGeometryPlan.blasRanges(build),
                        "retained mesh " + mesh.identity());
            } else {
                var sourceBlas = source.blas.retain();
                dependencies.add(sourceBlas);
                nativeBuild = RtAccel.preparePersistentBlasUpdate(ctx, sourceBlas.get().buildOperation,
                        build.positions().bytes().address(), build.indices().bytes().address(),
                        "retained mesh " + mesh.identity());
            }
            var value = new BlasGeneration(nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
            blas = SharedResource.owned(value, BlasGeneration::destroy);
            generation = new MeshGeneration(mesh, blas);
            var use = new BlasBuildUse(nativeBuild.op(), dependencies);
            return new PreparedMesh(generation, use);
        } catch (Throwable failure) {
            if (nativeBuild != null) {
                RtAccel.PreparedBlas operation = nativeBuild.op();
                suppressCleanupFailure(failure, () -> RtAccel.freeBlasScratch(List.of(operation)));
                if (generation != null) {
                    MeshGeneration rejected = generation;
                    suppressCleanupFailure(failure, rejected::close);
                } else if (blas != null) {
                    SharedResource<BlasGeneration> rejected = blas;
                    suppressCleanupFailure(failure, rejected::close);
                } else {
                    RtAccel.PersistentBuild rejected = nativeBuild;
                    suppressCleanupFailure(failure, () -> RtAccel.destroyCallerOwnedAccel(rejected.accel(), rejected.backing()));
                }
            }
            closeAll(dependencies, failure);
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
        if (published != null) return published;
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
            next = new PublishedSceneRevision(visibleRevision,
                    SharedResource.owned(root, SceneRevisionRoot::destroy));
        } catch (Throwable failure) {
            suppressCleanupFailure(failure, layout::close);
            throw failure;
        }
        PublishedSceneRevision previous = published;
        published = next;
        instanceState.latchPreviousTransforms();
        if (previous != null) previous.release();
        return published;
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

    static ResolvedFrameInput resolveFrameInput(RetainedSceneSnapshot.Mesh mesh,
                                               RetainedSceneSnapshot.Instance instance) {
        MeshBuild<?> build = mesh.build();
        var geometries = new ArrayList<RtRetainedGeometryPlan.ResolvedGeometry>();
        for (int index = 0; index < build.geometries().size(); index++) {
            MeshBuild.Geometry<?> geometry = build.geometries().get(index);
            RetainedSceneSnapshot.GeometryPrograms programs = mesh.geometryPrograms().get(index);
            geometries.add(new RtRetainedGeometryPlan.ResolvedGeometry(geometry,
                    programs.surfaceImplementation(), programs.volumeImplementation(),
                    geometry.surface() == null ? 0L : geometry.surface().bindingData().bits(),
                    geometry.volume() == null ? 0L : geometry.volume().bindingData().bits()));
        }
        return new ResolvedFrameInput(new RtRetainedGeometryPlan.ResolvedMesh(build, geometries),
                new RtRetainedGeometryPlan.ResolvedPlacement(instance.transform(), instance.instanceData().bits()));
    }

    private static Map<SceneId, List<LatchedInstance>> resolveFrameInstances(
            Map<SceneId, List<LatchedInstance>> captured) {
        Map<SceneId, List<LatchedInstance>> resolved = new IdentityHashMap<>();
        captured.forEach((scene, values) -> {
            List<LatchedInstance> instances = new ArrayList<>();
            int geometryBase = 0;
            for (LatchedInstance instance : values) {
                var input = resolveFrameInput(instance.nativeInstance.mesh.logical, instance.current);
                instances.add(instance.resolve(input.mesh(), input.placement(), geometryBase));
                geometryBase += instance.nativeInstance.mesh.logical.build().geometries().size();
            }
            resolved.put(scene, List.copyOf(instances));
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
        private ResourceOwners resources;
        private final Map<SceneId, FrameSceneSnapshot> scenes = new IdentityHashMap<>();

        FrameSnapshot(GraphicsUse graphicsUse, SharedResource<SceneRevisionRoot> root) {
            this.graphicsUse = graphicsUse;
            this.root = root;
            ResourceOwners acquired = null;
            try {
                Map<SceneId, List<LatchedInstance>> captured = captureFrameValues(
                        root.get().geometry().instances,
                        instance -> new LatchedInstance(
                                instance, latestTransforms.resolve(instance.logical)));
                acquired = ResourceOwners.capture(frameResourceReferences(root.get(), captured));
                this.currentInstances = resolveFrameInstances(captured);
                this.currentContent = root.get().content;
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
                        if (prior.topology.compatibleWith(instance.mesh.logical.build())) {
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
                    ResourceOwners positionResources = resources.retainOnly(positions);
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
            ResourceOwners releasedResources;
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
        final ResourceOwners positionResources;

        SceneMotionHistory(Map<Long, MotionInstanceHistory> instances,
                           ResourceOwners positionResources) {
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

    /** One published immutable mesh revision. */
    private static final class MeshEntry implements AutoCloseable {
        MeshGeneration current;
        @Override public void close() {
            MeshGeneration released = current;
            current = null;
            if (released != null) released.close();
        }
    }

    private record PendingBuild(MeshGeneration generation, BlasBuildUse buildUse) { }

    /** Either an immediately coherent generation or a build to submit, never both. */
    private record PlannedMesh(long identity, MeshGeneration coherent, PendingBuild build) { }
    private record NativeInstance(RetainedSceneSnapshot.Instance logical, MeshGeneration mesh,
                                  dev.comfyfluffy.caustica.api.geometry.GeometryTransform previousTransform,
                                  int geometryBase, int sbtRecordOffset, long placementOrdinal) { }
    private record CompletedBatch(ScenePublicationQueue.Edit<EditKey, PendingEdit> edit, Throwable failure) { }

    private record EditKey(String kind, Object identity) { }

    private static final class PendingEdit {
        final long revision;
        final List<PlannedMesh> planned;
        final java.util.Set<EditKey> keys;
        final ResourceOwners resources;
        final Runnable apply;
        final Runnable published;
        Throwable failure;
        PendingEdit(long revision, List<PlannedMesh> planned, java.util.Set<EditKey> keys,
                    ResourceOwners resources, Runnable apply, Runnable published) {
            this.revision = revision;
            this.planned = planned;
            this.keys = java.util.Set.copyOf(keys);
            this.resources = resources;
            this.apply = apply;
            this.published = published;
        }
    }

    record ContentPatch(Map<SceneId, SceneContent> added, java.util.Set<SceneId> removed,
                                Map<SceneId, EnvironmentBinding<?>> environments,
                                Map<Long, RetainedSceneSnapshot.Light> lights, java.util.Set<Long> droppedLights) {
        static ContentPatch empty() {
            return new ContentPatch(Map.of(), java.util.Set.of(), Map.of(), Map.of(), java.util.Set.of());
        }
        static ContentPatch between(Map<SceneId, SceneContent> before, Map<SceneId, SceneContent> after) {
            var added = new IdentityHashMap<SceneId, SceneContent>();
            var removed = java.util.Collections.newSetFromMap(new IdentityHashMap<SceneId, Boolean>());
            var environments = new IdentityHashMap<SceneId, EnvironmentBinding<?>>();
            before.keySet().stream().filter(scene -> !after.containsKey(scene)).forEach(removed::add);
            after.forEach((scene, value) -> {
                SceneContent previous = before.get(scene);
                if (previous == null) added.put(scene, new SceneContent(value.environment(), List.of()));
                else if (!Objects.equals(previous.environment(), value.environment())) environments.put(scene, value.environment());
            });
            Map<Long, RetainedSceneSnapshot.Light> oldLights = flattenLights(before);
            Map<Long, RetainedSceneSnapshot.Light> nextLights = flattenLights(after);
            var droppedLights = new java.util.HashSet<>(oldLights.keySet());
            droppedLights.removeAll(nextLights.keySet());
            nextLights.entrySet().removeIf(entry -> Objects.equals(oldLights.get(entry.getKey()), entry.getValue()));
            return new ContentPatch(added, removed, environments, nextLights, droppedLights);
        }
        java.util.Set<EditKey> keys() {
            var keys = new java.util.HashSet<EditKey>();
            if (!added.isEmpty() || !removed.isEmpty()) keys.add(new EditKey("all", 0L));
            environments.keySet().forEach(scene -> keys.add(new EditKey("environment", scene)));
            lights.keySet().forEach(id -> keys.add(new EditKey("light", id)));
            droppedLights.forEach(id -> keys.add(new EditKey("light", id)));
            return keys;
        }
        Map<SceneId, SceneContent> apply(Map<SceneId, SceneContent> current) {
            var result = new IdentityHashMap<>(current);
            removed.forEach(result::remove);
            result.putAll(added);
            environments.forEach((scene, environment) -> result.put(scene,
                    new SceneContent(environment, result.get(scene).lights())));
            var allLights = flattenLights(result);
            droppedLights.forEach(allLights::remove);
            allLights.putAll(lights);
            result.replaceAll((scene, value) -> new SceneContent(value.environment(), allLights.values().stream()
                    .filter(light -> light.scene() == scene)
                    .map(light -> new SceneLight(light.identity(), light.descriptor())).toList()));
            return Map.copyOf(result);
        }
        private static Map<Long, RetainedSceneSnapshot.Light> flattenLights(Map<SceneId, SceneContent> content) {
            var lights = new LinkedHashMap<Long, RetainedSceneSnapshot.Light>();
            content.forEach((scene, value) -> value.lights().forEach(light -> lights.put(light.identity(),
                    new RetainedSceneSnapshot.Light(light.identity(), scene, light.descriptor()))));
            return lights;
        }
    }
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
        final ResourceOwners resources;
        SceneRevisionRoot(SharedResource<SceneLayoutGeneration> geometry, Map<SceneId, SceneContent> content) {
            this.geometry = geometry;
            this.content = Map.copyOf(content);
            var references = new ArrayList<ResourceRef>();
            geometry.get().instances.values().forEach(instances -> instances.forEach(instance ->
                    references.add(instance.logical.instanceData().resource())));
            addEnvironmentResources(content, references);
            resources = ResourceOwners.capture(references);
        }
        SceneLayoutGeneration geometry() { return geometry.get(); }
        void destroy() {
            closeAll(List.of(geometry, resources), null);
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
        final ResourceOwners resources;
        MeshGeneration(RetainedSceneSnapshot.Mesh logical, SharedResource<BlasGeneration> blas) {
            this.logical = logical;
            this.blas = blas;
            var references = new ArrayList<ResourceRef>();
            references.add(logical.build().positions().resource());
            references.add(logical.build().indices().resource());
            logical.build().geometries().forEach(geometry -> {
                if (geometry.surface() != null) references.add(geometry.surface().bindingData().resource());
                if (geometry.volume() != null) references.add(geometry.volume().bindingData().resource());
            });
            resources = ResourceOwners.capture(references);
        }
        BlasGeneration blas() { return blas.get(); }
        MeshGeneration withLogical(RetainedSceneSnapshot.Mesh next) {
            var retained = blas.retain();
            try {
                return new MeshGeneration(next, retained);
            } catch (Throwable failure) {
                retained.close();
                throw failure;
            }
        }
        @Override public void close() { closeAll(List.of(blas, resources), null); }
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
