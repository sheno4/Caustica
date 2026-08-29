package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorRange;
import dev.comfyfluffy.caustica.api.vulkan.GpuDescriptorIndex;
import dev.comfyfluffy.caustica.api.vulkan.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.api.scene.EnvironmentBinding;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneBackend;
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
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Vulkan owner for immutable retained-scene publications. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final Map<SceneId, TlasBuilder.Ring> tlasRings = new IdentityHashMap<>();
    private final Map<SceneId, TraceRing> traceRings = new IdentityHashMap<>();
    private final ArrayDeque<Publication> queued = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<CompletedBuild> completed = new ConcurrentLinkedQueue<>();
    private NativeSnapshot published;
    private Throwable fatalFailure;
    private boolean closed;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.neeAt = new RtNeeAtBackend(ctx);
    }

    public synchronized PreparedLighting prepareLighting(SceneId scene, LightingFrame frame,
                                                          VkCommandBuffer commandBuffer,
                                                          GraphicsUse graphicsUse) {
        NativeSnapshot current = requirePublishedScene(scene);
        current.graphicsUse.mark(graphicsUse);
        var input = new RtNeeAtBackend.FrameInput(frame.width(), frame.height(), frame.frameIndex(),
                frame.metersPerSceneUnit(), frame.historyContinuous());
        return new PreparedLighting(neeAt.prepare(scene, current.content.get(scene).lights(), input,
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
    public synchronized void publish(RetainedSceneSnapshot snapshot, Runnable previousRetired) {
        if (closed) throw new IllegalStateException("retained scene backend is closed");
        if (fatalFailure != null) throw fatalException();
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(previousRetired, "previousRetired");
        long tailRevision = queued.isEmpty()
                ? published != null ? published.revision : -1L
                : queued.getLast().snapshot.revision();
        if (snapshot.revision() <= tailRevision) {
            throw new IllegalArgumentException("scene revisions must increase");
        }
        Publication publication = new Publication(snapshot, previousRetired);
        Candidate predecessor = queued.isEmpty() ? published : queued.getLast().candidate;
        try {
            publication.candidate = prepare(snapshot, predecessor);
            accept(publication);
            queued.addLast(publication);
        } catch (Throwable failure) {
            if (publication.candidate != null) publication.candidate.releaseRejected();
            throw failure;
        }
    }

    /** Publishes completed snapshots in accepted revision order. */
    public synchronized void progress() {
        requireOpen();
        CompletedBuild terminal;
        while ((terminal = completed.poll()) != null) terminal.publication.complete(terminal.failure);
        while (true) {
            Publication head = queued.peekFirst();
            if (head == null || !head.completed) return;
            finishBuild(head);
        }
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
        List<NativeInstance> source = current.instances.get(scene);
        TlasBuilder.InstanceBatch instances = new TlasBuilder.InstanceBatch();
        instances.reset(source.size());
        for (NativeInstance instance : source) {
            float[] transform = instance.logical.transform().relativeTo(origin.x(), origin.y(), origin.z());
            instances.append(transform, 0, 0, 0, instance.mesh.accel.deviceAddress,
                    instance.geometryBase, instance.logical.mask(), instance.sbtRecordOffset);
        }
        current.graphicsUse.mark(graphicsUse);
        TlasBuilder.Ring ring = tlasRings.computeIfAbsent(scene, ignored -> new TlasBuilder.Ring());
        return TlasBuilder.prepare(ctx, instances, ring, graphicsUse);
    }

    /** Packs the GeometryIndex-addressed records for one scene in the same order as its TLAS hit bases. */
    public synchronized ByteBuffer geometryRecords(SceneId scene, SceneOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.instances.get(scene)) {
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, instance.logical,
                    instance.previousTransform));
        }
        return RtRetainedGeometryPlan.pack(records, origin);
    }

    /** Hit-group handle selection in exact {@code geometry * rayType} SBT order. */
    public synchronized List<RtRetainedGeometryPlan.HitGroup> hitGroups(SceneId scene) {
        NativeSnapshot current = requirePublishedScene(scene);
        List<RtRetainedGeometryPlan.GeometryRecord> records = new ArrayList<>();
        for (NativeInstance instance : current.instances.get(scene)) {
            records.addAll(RtRetainedGeometryPlan.records(instance.mesh.logical, instance.logical,
                    instance.previousTransform));
        }
        return RtRetainedGeometryPlan.hitGroups(records);
    }

    /** Uploads this frame's rebased geometry records and pipeline-specific hit SBT into a protected ring slot. */
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
        for (NativeInstance instance : current.instances.get(scene)) {
            List<RtRetainedGeometryPlan.GeometryRecord> instanceRecords = RtRetainedGeometryPlan.records(
                    instance.mesh.logical, instance.logical, instance.previousTransform);
            for (int geometryIndex = 0; geometryIndex < instanceRecords.size(); geometryIndex++) {
                records.add(instanceRecords.get(geometryIndex));
                emitterOffsets.add(emitterBytes);
                int triangles = instance.mesh.logical.build().geometries().get(geometryIndex).triangleCount();
                emitterBytes = Math.addExact(emitterBytes, Math.multiplyExact(triangles, Integer.BYTES));
            }
        }
        List<RtRetainedGeometryPlan.HitGroup> groups = RtRetainedGeometryPlan.hitGroups(records);
        ByteBuffer hits = pipeline.retainedHitRecords(groups);
        TraceRing ring = traceRings.computeIfAbsent(scene, ignored -> new TraceRing());
        int geometryBytes = Math.multiplyExact(records.size(), RtRetainedGeometryPlan.RECORD_BYTES);
        int lightBytes = Math.multiplyExact(sceneLights.size(), RtRetainedLightPlan.RECORD_BYTES);
        TraceSlot slot = ring.next(ctx, geometryBytes, hits.remaining(), lightBytes, emitterBytes, pipeline);
        List<RtRetainedGeometryPlan.GeometryRecord> addressedRecords = new ArrayList<>(records.size());
        for (int index = 0; index < records.size(); index++) {
            addressedRecords.add(records.get(index).withEmitterIndex(
                    slot.emitters.deviceAddress() + emitterOffsets.get(index), 0));
        }
        ByteBuffer geometry = RtRetainedGeometryPlan.pack(addressedRecords, origin);
        ByteBuffer emitters = ByteBuffer.allocate(emitterBytes).order(ByteOrder.nativeOrder());
        boolean[] linkedEmitters = new boolean[sceneLights.size()];
        for (NativeInstance instance : current.instances.get(scene)) {
            for (MeshBuild.Geometry<?> meshGeometry : instance.mesh.logical.build().geometries()) {
                int primitiveBase = meshGeometry.firstIndex() / 3;
                for (int localPrimitive = 0; localPrimitive < meshGeometry.triangleCount(); localPrimitive++) {
                    int dense = emitterIndex(instance.logical.primitiveEmitters(),
                            primitiveBase + localPrimitive, lightIndices);
                    emitters.putInt(dense);
                    if (dense >= 0) linkedEmitters[dense] = true;
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
                slot.hits.deviceAddress(), pipeline.retainedHitRecordStride(), hits.remaining()) : null;
        RtNeeAtBackend.Prepared lighting = neeAt.active(scene);
        if (lighting == null) throw new IllegalStateException("prepareLighting must precede prepareTrace");
        lighting.bindLightTable(slot.lights.deviceAddress());
        return new PreparedTrace(slot.geometry.deviceAddress(), lighting.stateAddress(),
                slot.tlasDescriptor.firstIndex().value(), hitTable);
    }

    private static int emitterIndex(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                    int primitive, Map<Long, Integer> lightIndices) {
        for (RetainedSceneSnapshot.PrimitiveEmitter range : ranges) {
            if (primitive < range.firstPrimitive()) break;
            if (primitive < range.firstPrimitive() + range.primitiveCount()) {
                return lightIndices.getOrDefault(range.lightIdentity(), -1);
            }
        }
        return -1;
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
        for (TlasBuilder.Ring ring : tlasRings.values()) {
            try {
                ring.destroy();
            } catch (Throwable releaseFailure) {
                if (failure == null) failure = releaseFailure;
                else failure.addSuppressed(releaseFailure);
            }
        }
        tlasRings.clear();
        for (TraceRing ring : traceRings.values()) ring.destroy();
        traceRings.clear();
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
                NativeMesh reused = predecessor == null ? null : predecessor.meshes.get(mesh.identity());
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

    private void accept(Publication publication) {
        Candidate candidate = publication.candidate;
        if (candidate.builds.isEmpty()) {
            candidate.accepted = true;
            candidate.unsubmitted = List.of();
            completed.add(new CompletedBuild(publication, null));
            return;
        }
        ctx.gpuExecutor().submit(cmd -> RtAccel.recordBlasBuilds(ctx, cmd, candidate.builds),
                () -> {
                    RtAccel.freeBlasScratch(candidate.builds);
                    candidate.buildResourcesReleased = true;
                },
                (ignored, failure) -> completed.add(new CompletedBuild(publication, failure)));
        candidate.accepted = true;
        candidate.unsubmitted = List.of();
    }

    private NativeMesh prepareMesh(RetainedSceneSnapshot.Mesh mesh) {
        MeshBuild<?> build = mesh.build();
        RtAccel.PersistentBuild nativeBuild = RtAccel.preparePersistentBlasBuild(ctx,
                build.positions().deviceAddress(), build.positions().byteStride(), build.vertexCount(),
                build.indices().deviceAddress(),
                RtRetainedGeometryPlan.blasRanges(build), "retained mesh " + mesh.identity());
        return new NativeMesh(mesh, nativeBuild.op(), nativeBuild.accel(), nativeBuild.backing());
    }

    private Candidate assemble(RetainedSceneSnapshot snapshot, Map<Long, NativeMesh> meshes,
                               Candidate predecessor) {
        Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
        Map<SceneId, MutableSceneContent> mutableContent = new IdentityHashMap<>();
        Map<Long, NativeInstance> previousInstances = new LinkedHashMap<>();
        if (predecessor != null) {
            predecessor.instances.values().forEach(values -> values.forEach(
                    instance -> previousInstances.put(instance.logical.identity(), instance)));
        }
        Map<SceneId, Integer> geometryBases = new IdentityHashMap<>();
        for (RetainedSceneSnapshot.Scene scene : snapshot.scenes()) {
            instances.put(scene.id(), new ArrayList<>());
            mutableContent.put(scene.id(), new MutableSceneContent(scene.environment()));
            geometryBases.put(scene.id(), 0);
        }
        for (RetainedSceneSnapshot.Instance instance : snapshot.instances()) {
            NativeMesh mesh = meshes.get(instance.meshIdentity());
            int geometryBase = geometryBases.get(instance.scene());
            NativeInstance previous = previousInstances.get(instance.identity());
            instances.get(instance.scene()).add(new NativeInstance(instance, mesh,
                    previous == null ? instance.transform() : previous.logical.transform(), geometryBase,
                    Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY)));
            geometryBases.put(instance.scene(), Math.addExact(geometryBase,
                    mesh.logical.build().geometries().size()));
        }
        for (RetainedSceneSnapshot.Light light : snapshot.lights()) {
            mutableContent.get(light.scene()).lights.add(new SceneLight(light.identity(), light.descriptor()));
        }
        Map<SceneId, SceneContent> content = new IdentityHashMap<>();
        mutableContent.forEach((scene, value) -> content.put(scene,
                new SceneContent(value.environment, List.copyOf(value.lights))));
        instances.replaceAll((ignored, value) -> List.copyOf(value));
        return new Candidate(snapshot.revision(), meshes, instances, content);
    }

    private void finishBuild(Publication publication) {
        if (queued.getFirst() != publication) {
            throw new IllegalStateException("retained scene publication order changed");
        }
        if (publication.failure != null) {
            fatalFailure = publication.failure;
            throw fatalException();
        }
        queued.removeFirst();
        NativeSnapshot previous = published;
        published = publication.candidate.publish();
        neeAt.retainScenes(published.content.keySet());
        if (previous == null) {
            publication.retirePrevious(null);
        } else {
            ctx.gpuExecutor().retireAfterGraphics(previous.graphicsUse,
                    () -> publication.retirePrevious(previous::release));
        }
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
                                boolean historyContinuous) { }

    public static final class PreparedLighting {
        private final RtNeeAtBackend.Prepared delegate;
        private PreparedLighting(RtNeeAtBackend.Prepared delegate) { this.delegate = delegate; }
        public boolean historyValid() { return delegate.historyValid(); }
    }

    public record PreparedTrace(long geometryRecordsAddress, long neeAtStateAddress,
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
                    geometryRecordsAddress);
            target.putInt(base + RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET, tlasDescriptorIndex);
            target.putLong(base + RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET, neeAtStateAddress);
        }
    }

    private static final class TraceRing {
        private static final int SIZE = 4;
        private final TraceSlot[] slots = new TraceSlot[SIZE];
        private int cursor;

        TraceSlot next(VulkanDeviceContext ctx, int geometryBytes, int hitBytes, int lightBytes, int emitterBytes,
                       RtPipeline pipeline) {
            TraceSlot slot = slots[cursor];
            cursor = (cursor + 1) % SIZE;
            if (slot != null) ctx.gpuExecutor().graphicsUseWaiter().await(slot.graphicsUse);
            if (slot == null || slot.geometry.size() < geometryBytes || slot.hits.size() < hitBytes
                    || slot.lights.size() < lightBytes
                    || slot.emitters.size() < emitterBytes
                    || slot.hitStride != pipeline.retainedHitRecordStride()) {
                int slotIndex = (cursor + SIZE - 1) % SIZE;
                if (slot != null) {
                    slots[slotIndex] = null;
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
                    descriptor = ctx.descriptorHeap().allocateResources(1, "retained scene TLAS");
                    slot = new TraceSlot(geometry, hits, lights, emitters, descriptor,
                            pipeline.retainedHitRecordStride());
                    slots[slotIndex] = slot;
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
            for (TraceSlot slot : slots) if (slot != null) slot.destroy();
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
        final RetainedSceneSnapshot snapshot;
        final Runnable previousRetired;
        Candidate candidate;
        volatile boolean completed;
        volatile Throwable failure;
        private boolean previousSettled;
        Publication(RetainedSceneSnapshot snapshot, Runnable previousRetired) {
            this.snapshot = snapshot;
            this.previousRetired = previousRetired;
        }
        void complete(Throwable failure) {
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
                                  int geometryBase, int sbtRecordOffset) { }
    private record CompletedBuild(Publication publication, Throwable failure) { }

    private static class Candidate {
        final long revision;
        final Map<Long, NativeMesh> meshes;
        final Map<SceneId, List<NativeInstance>> instances;
        final Map<SceneId, SceneContent> content;
        List<NativeMesh> unsubmitted = List.of();
        List<RtAccel.PreparedBlas> builds = List.of();
        boolean accepted;
        volatile boolean buildResourcesReleased;
        Candidate(long revision, Map<Long, NativeMesh> meshes, Map<SceneId, List<NativeInstance>> instances,
                  Map<SceneId, SceneContent> content) {
            this.revision = revision;
            this.meshes = Map.copyOf(meshes);
            this.instances = Map.copyOf(instances);
            this.content = Map.copyOf(content);
        }
        NativeSnapshot publish() { return new NativeSnapshot(revision, meshes, instances, content); }
        void release() { meshes.values().forEach(NativeMesh::release); }
        void releaseRejected() {
            if (accepted) return;
            for (NativeMesh mesh : meshes.values()) {
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
        NativeSnapshot(long revision, Map<Long, NativeMesh> meshes,
                       Map<SceneId, List<NativeInstance>> instances, Map<SceneId, SceneContent> content) {
            super(revision, meshes, instances, content);
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
