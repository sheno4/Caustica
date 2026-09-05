package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
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
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Immutable scene snapshots consumed by frame-local TLAS, shader-table, and lighting work. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResource<SceneMotionHistory>> motionHistoryByScene = new IdentityHashMap<>();
    private Supplier<SharedResource<RetainedSceneSnapshot>> capture;
    private boolean closed;
    private boolean sessionClosing;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx);
        this.neeAt = new RtNeeAtBackend(ctx);
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
            if (inFlightFrames.isEmpty() && motionHistoryByScene.isEmpty()) return;
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
        int primitive = firstPrimitive;
        int end = Math.addExact(firstPrimitive, primitiveCount);
        for (RetainedSceneSnapshot.PrimitiveEmitter range : ranges) {
            if (range.firstPrimitive() >= end) break;
            int rangeEnd = (int) Math.min((long) end, (long) range.firstPrimitive() + range.primitiveCount());
            if (rangeEnd <= primitive) continue;
            while (primitive < range.firstPrimitive()) {
                output.putInt(-1);
                primitive++;
            }
            int dense = lightIndices.getOrDefault(range.lightIdentity(), -1);
            if (dense >= 0) linkedEmitters[dense] = true;
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
        releaseTerminalFrameRoots();
        capture = null;
        neeAt.destroyAfterDeviceIdle();
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

    private void retainRenderedScenes(java.util.Set<SceneId> retained) {
        List<SharedResource<SceneMotionHistory>> removed = new ArrayList<>();
        motionHistoryByScene.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            removed.add(entry.getValue());
            return true;
        });
        closeAll(removed, null);
    }

    private FrameSnapshot frameLease(SceneId scene, GraphicsUse graphicsUse) {
        Objects.requireNonNull(graphicsUse, "graphicsUse");
        requireOpen();
        boolean created = !inFlightFrames.containsKey(graphicsUse);
        FrameSnapshot frame = latchFrameRoot(inFlightFrames, graphicsUse,
                () -> new FrameSnapshot(graphicsUse, capture.get()));
        if (created) {
            try {
                graphicsUse.whenSubmitted(frame::accept);
                graphicsUse.keepAlive(frame);
            } catch (Throwable failure) {
                frame.close();
                throw failure;
            }
        }
        if (!frame.currentContent.containsKey(scene)) {
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
            Map<SceneId, List<NativeInstance>> captured) {
        Map<SceneId, List<LatchedInstance>> resolved = new IdentityHashMap<>();
        captured.forEach((scene, values) -> {
            List<LatchedInstance> instances = new ArrayList<>();
            int geometryBase = 0;
            for (NativeInstance instance : values) {
                var input = resolveFrameInput(instance.mesh.logical, instance.logical);
                instances.add(new LatchedInstance(instance, instance.logical, input.mesh(), input.placement(),
                        geometryBase, Math.multiplyExact(geometryBase, RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY)));
                geometryBase += instance.mesh.logical.build().geometries().size();
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
        private final Map<SceneId, List<LatchedInstance>> currentInstances;
        private final Map<SceneId, SceneContent> currentContent;
        private final Map<SceneId, FrameSceneSnapshot> scenes = new IdentityHashMap<>();

        FrameSnapshot(GraphicsUse graphicsUse, SharedResource<RetainedSceneSnapshot> root) {
            this.graphicsUse = graphicsUse;
            this.root = root;
            try {
                RetainedSceneSnapshot snapshot = root.get();
                Map<Long, FrameMesh> meshes = new LinkedHashMap<>();
                for (RetainedSceneSnapshot.Mesh mesh : snapshot.meshes()) {
                    meshes.put(mesh.identity(), new FrameMesh(mesh));
                }
                currentContent = assembleContent(snapshot.scenes(), snapshot.lights());
                Map<SceneId, List<NativeInstance>> instances = new IdentityHashMap<>();
                currentContent.keySet().forEach(scene -> instances.put(scene, new ArrayList<>()));
                for (RetainedSceneSnapshot.Instance instance : snapshot.instances()) {
                    instances.get(instance.scene()).add(new NativeInstance(instance, meshes.get(instance.meshIdentity()),
                            instance.placementOrdinal()));
                }
                currentInstances = resolveFrameInstances(instances);
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
                List<FrameInstanceSnapshot> instances = new ArrayList<>();
                for (LatchedInstance frameCurrent : currentInstances.get(scene)) {
                    NativeInstance instance = frameCurrent.nativeInstance;
                    RetainedSceneSnapshot.Instance effective = frameCurrent.current;
                    GeometryTransform previousTransform = effective.transform();
                    MeshBuild.Stream previousPositions = instance.mesh.logical.build().positions();
                    MotionInstanceHistory prior = history == null
                            ? null : history.instances.get(instance.logical.identity());
                    if (prior != null
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
                    ResourceOwners positionResources = ResourceOwners.capture(positions);
                    SharedResource<SceneMotionHistory> replacement = null;
                    try {
                        Map<Long, MotionInstanceHistory> historyInstances = new LinkedHashMap<>();
                        for (FrameInstanceSnapshot instance : entry.getValue().instances) {
                            NativeInstance nativeInstance = instance.nativeInstance;
                            MeshBuild<?> build = nativeInstance.mesh.logical.build();
                            historyInstances.put(nativeInstance.logical.identity(), new MotionInstanceHistory(
                                    nativeInstance.placementOrdinal,
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
    }

    record ResolvedFrameInput(RtRetainedGeometryPlan.ResolvedMesh mesh,
                              RtRetainedGeometryPlan.ResolvedPlacement placement) { }

    private record MotionInstanceHistory(long placementOrdinal,
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
            geometry = ctx.createMappedGpuUploadBuffer(Math.max(RtRetainedGeometryPlan.RECORD_BYTES, geometryBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained geometry records");
            hits = ctx.createMappedGpuUploadBuffer(Math.max(pipeline.retainedHitRecordStride(), hitBytes),
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, "retained hit SBT",
                    pipeline.retainedHitTableAlignment());
            lights = ctx.createMappedGpuUploadBuffer(Math.max(RtRetainedLightPlan.RECORD_BYTES, lightBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained light records");
            emitters = ctx.createMappedGpuUploadBuffer(Math.max(Integer.BYTES, emitterBytes),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained primitive-light indices");
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

    private record NativeInstance(RetainedSceneSnapshot.Instance logical, FrameMesh mesh,
                                  long placementOrdinal) { }

    /** Borrows the ready mesh from the owning scene capture. */
    private static final class FrameMesh {
        final RetainedSceneSnapshot.Mesh logical;
        final RtPreparedMesh nativeMesh;
        FrameMesh(RetainedSceneSnapshot.Mesh logical) {
            this.logical = logical;
            nativeMesh = (RtPreparedMesh) SceneDirectory.preparedResource(logical.ready());
        }
        RtPreparedMesh.State blas() { return nativeMesh.value(); }
    }
}
