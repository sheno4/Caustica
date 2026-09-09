package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import it.unimi.dsi.fastutil.longs.Long2IntFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;


import dev.comfyfluffy.caustica.api.geometry.GeometryTransform;
import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.engine.scene.SceneDirectory;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
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
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.engine.scene.SnapshotList;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.renderer.raytracing.accel.TlasBuilder;
import dev.comfyfluffy.caustica.renderer.raytracing.resource.RtCompletionSlotPool;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeCompletion;

import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR;

/** Workers publish completed geometry revisions; frames retain current and submitted history roots. */
public final class RtRetainedSceneBackend implements RetainedSceneBackend {
    private final VulkanDeviceContext ctx;
    private final RtNeeAtBackend neeAt;
    private final RtFramePreparation framePreparation = new RtFramePreparation();
    private final RtCompletionSlotPool<TraceSlot> traceSlots;
    private final RtCompletionSlotPool<InstanceUploadSlot> instanceBuffers;
    private final TlasBuilder.Pool tlasSlots;
    private final Map<GraphicsUse, FrameSnapshot> inFlightFrames = new IdentityHashMap<>();
    private final Map<SceneId, SharedResource<PreparedWorldGeometry>> preparedGeometryByScene = new IdentityHashMap<>();
    private Thread retirementThread;
    private final java.util.concurrent.ExecutorService retirement = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Caustica scene retirement");
        thread.setDaemon(true);
        retirementThread = thread;
        return thread;
    });
    private final RtInstanceTablePlan.Builder instanceTables = new RtInstanceTablePlan.Builder();
    private final SceneRevisionPreparation scenePreparation = new SceneRevisionPreparation((mesh, composition) ->
            new FrameMesh(mesh, (RtPreparedMesh) SceneDirectory.preparedResource(mesh.ready()), composition));
    private Runnable scenePreparationQuiescer = () -> { };
    private final Map<SceneId, SceneCaches> preparationCaches = new IdentityHashMap<>();
    private Supplier<SharedResource<RetainedSceneSnapshot>> capture;
    private boolean closed;
    private boolean sessionClosing;

    public RtRetainedSceneBackend(VulkanDeviceContext ctx) {
        this.ctx = Objects.requireNonNull(ctx);
        this.neeAt = new RtNeeAtBackend(ctx);
        this.traceSlots = new RtCompletionSlotPool<>(TraceSlot::destroy);
        this.instanceBuffers = new RtCompletionSlotPool<>(slot -> slot.buffer.destroy());
        this.tlasSlots = new TlasBuilder.Pool(ctx);
    }

    private void retire(Runnable release) {
        if (Thread.currentThread() == retirementThread) release.run();
        else retirement.execute(release);
    }

    @Override public synchronized void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture) {
        this.capture = Objects.requireNonNull(capture);
    }

    /** The runtime stops preparation and releases its pending/publication claims before lifecycle drains. */
    public synchronized void bindScenePreparationQuiescer(Runnable quiescer) {
        scenePreparationQuiescer = Objects.requireNonNull(quiescer);
    }

    /** A serial preparation worker owns the assembly caches; the returned revision owns its source resources. */
    public SharedResource<PreparedSceneRevision> prepareSceneRevision(SharedResource<RetainedSceneSnapshot> root,
                                                                     ProgramComposition composition) {
        requireOpen();
        return RtFramePreparation.measure("scene-revision", root.get().instances().size(), root.get().lights().size(),
                () -> scenePreparation.prepare(root, composition));
    }

    /** Called after the preparation worker is quiescent; existing frame claims remain valid. */
    public void clearScenePreparation() {
        var released = new ArrayList<>(preparedGeometryByScene.values());
        preparedGeometryByScene.clear();
        new ResourceLifetime(scenePreparation::clear, () -> closeAll(released, null)).close();
    }

    private void quiesceScenePreparation() {
        Runnable quiescer;
        synchronized (this) { quiescer = scenePreparationQuiescer; }
        quiescer.run();
        clearScenePreparation();
    }

    /** Prepares the global light distribution before the stable-plane build. */
    public synchronized PreparedLighting prepareLighting(SceneId scene, LightingFrame frame,
                                                          VkCommandBuffer commandBuffer,
                                                          GraphicsUse graphicsUse) {
        PreparedWorldGeometry current = frameScene(scene, graphicsUse).geometry;
        var input = new RtNeeAtBackend.FrameInput(frame.width(), frame.height(), frame.frameIndex(),
                frame.metersPerSceneUnit(), frame.historyContinuous());
        return new PreparedLighting(neeAt.prepare(frame.view(), scene, current.lighting, input,
                commandBuffer, graphicsUse));
    }

    /** Bakes the local distribution from BuildStablePlanes linear depth and pixel motion. */
    public synchronized void bakeLocal(PreparedLighting lighting, VkCommandBuffer commandBuffer,
                                       int currentLinearDepthIndex, int currentMotionIndex) {
        neeAt.bakeLocal(lighting.delegate, commandBuffer, currentLinearDepthIndex, currentMotionIndex);
    }

    public synchronized void finishLighting(SceneId scene, PreparedLighting lighting,
                                             VkCommandBuffer commandBuffer, GraphicsUse graphicsUse) {
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        if (lighting.delegate.scene() != scene) {
            throw new IllegalArgumentException("prepared lighting belongs to another scene");
        }
        neeAt.finish(lighting.delegate, commandBuffer, graphicsUse);
    }

    /** Invalidates feedback when a prepared frame cannot reach its trace completion point. */
    public synchronized void abandonLighting(SceneId scene, PreparedLighting lighting) {
        Objects.requireNonNull(lighting, "lighting");
        if (lighting.delegate.scene() != scene) {
            throw new IllegalArgumentException("prepared lighting belongs to another scene");
        }
        neeAt.abandon(lighting.delegate);
    }

    public synchronized void releaseView(Object view) { neeAt.releaseView(view); }

    @Override
    public void prepareForSessionClose() {
        synchronized (this) {
            requireOpen();
            if (sessionClosing) return;
            sessionClosing = true;
        }
        quiesceScenePreparation();
        ctx.drainAndWaitIdle();
        synchronized (this) {
            releaseTerminalFrameRoots();
        }
    }

    @Override
    public void settleFrameUses() {
        quiesceScenePreparation();
        synchronized (this) {
            requireOpen();
            if (inFlightFrames.isEmpty()) return;
        }
        ctx.drainAndWaitIdle();
        synchronized (this) {
            releaseTerminalFrameRoots();
        }
    }

    /** Immutable light/environment view for one target scene. */
    public synchronized SceneContent content(SceneId scene, GraphicsUse graphicsUse) {
        return frameScene(scene, graphicsUse).geometry.trace.current.content;
    }

    private static TlasBuilder.InstanceWriter<FrameInstanceSnapshot> instanceWriter(SceneOrigin origin) {
        return (instance, target) -> {
            target.transform().matrix().put(instance.current.transform().relativeTo(origin.x(), origin.y(), origin.z()));
            target.instanceCustomIndex(instance.geometryBase())
                    .mask(instance.current.mask())
                    .instanceShaderBindingTableRecordOffset(instance.sbtRecordOffset())
                    .flags(org.lwjgl.vulkan.KHRAccelerationStructure.VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(instance.mesh.blas().accel.deviceAddress.value());
        };
    }

    /** The serial preparation worker waits for GPU completion before exposing the atomic bundle. */
    public SharedResource<PreparedTraceRevision> prepareTraceRevision(SharedResource<PreparedSceneRevision> source,
                                                                     SceneOrigin origin, RtPipeline pipeline,
                                                                     float metersPerSceneUnit) {
        requireOpen();
        var revision = source.get();
        var completed = new IdentityHashMap<SceneId, SharedResource<PreparedWorldGeometry>>();
        try {
            for (var entry : revision.instances.entrySet()) {
                SceneId scene = entry.getKey();
                var cached = preparedGeometryByScene.get(scene);
                if (cached != null && cached.get().matches(entry.getValue(), revision.content.get(scene),
                        origin, pipeline, metersPerSceneUnit)) {
                    completed.put(scene, cached.retain());
                    continue;
                }
                int instanceCount = entry.getValue().stream().mapToInt(page -> page.instances.size()).sum();
                var ready = RtFramePreparation.measure("trace-revision", instanceCount, revision.content.get(scene).lights().size(),
                        () -> prepareWorldRevision(scene, source, origin, pipeline, metersPerSceneUnit));
                completed.put(scene, ready);
                var displaced = preparedGeometryByScene.put(scene, ready.retain());
                if (displaced != null) displaced.close();
            }
            var obsolete = new ArrayList<SharedResource<PreparedWorldGeometry>>();
            preparedGeometryByScene.entrySet().removeIf(entry -> {
                if (completed.containsKey(entry.getKey())) return false;
                obsolete.add(entry.getValue());
                return true;
            });
            closeAll(obsolete, null);
            preparationCaches.keySet().retainAll(completed.keySet());
            var value = new PreparedTraceRevision(source.retain(), Collections.unmodifiableMap(completed),
                    origin, metersPerSceneUnit);
            return SharedResource.owned(value, released -> retire(released::close));
        } catch (Throwable failure) {
            closeAll(completed.values(), failure);
            throw failure;
        }
    }

    private SharedResource<PreparedWorldGeometry> prepareWorldRevision(SceneId scene,
            SharedResource<PreparedSceneRevision> source, SceneOrigin origin, RtPipeline pipeline, float metersPerSceneUnit) {
        RtRevisionResources resources = new RtRevisionResources();
        var sourceClaim = source.retain();
        resources.add(sourceClaim::close);
        CompletableFuture<Void> pendingTlas = null;
        try {
            var revision = source.get();
            var cache = preparationCaches.computeIfAbsent(scene, ignored -> new SceneCaches());
            var inputs = FrameSceneSnapshot.prepare(revision.content.get(scene), revision.instances.get(scene));
            var tableInputs = new ArrayList<RtInstanceTablePlan.Input>(inputs.instances.size());
            for (var page : SnapshotList.pagesOf(inputs.instances)) {
                for (var instance : page) tableInputs.add(new RtInstanceTablePlan.Input(
                        instance.current.identity(), instance.current.placementOrdinal(),
                        instance.resolvedMesh().build(), instance.current.transform(), instance.current.instanceData().bits()));
            }
            var previous = preparedGeometryByScene.get(scene);
            var instanceTable = instanceTables.build(tableInputs, previous == null ? null : previous.get().instanceTable);
            InstanceUploadSlot instanceSlot = instanceBuffers.acquire(slot -> slot.buffer.size() >= instanceTable.byteSize(),
                    () -> new InstanceUploadSlot(ctx.createMappedGpuUploadBuffer(RtCompletionSlotPool.capacity(instanceTable.byteSize()),
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained instance history table")), resources::add);
            GpuBuffer instanceBuffer = instanceSlot.buffer;
            var instancePages = cache.instances.resolve(instanceTable, origin);
            var previousInstancePages = instanceSlot.pages;
            instanceSlot.pages = List.of();
            if (TlasBuilder.copyPages(MemoryUtil.memByteBuffer(instanceBuffer.mapped(), instanceTable.byteSize()),
                    instancePages, previousInstancePages) != 0) instanceBuffer.flush(0L, instanceTable.byteSize());
            instanceSlot.pages = instancePages;
            var reserved = RtFramePreparation.measure("tlas-reserve", inputs.instances.size(), 0,
                    () -> tlasSlots.reserve(inputs.instances.size(), resources::add));
            var tlas = RtFramePreparation.measure("tlas-pack", inputs.instances.size(), 0,
                    () -> TlasBuilder.packPages(reserved,
                            cache.tlas.resolve(inputs.instances, origin, instanceWriter(origin))));
            var done = new CompletableFuture<Void>();
            ctx.gpuExecutor().submit(command -> TlasBuilder.record(ctx, command, tlas), completion -> {
                switch (completion) {
                    case GpuComputeCompletion.Succeeded ignored -> done.complete(null);
                    case GpuComputeCompletion.Failed failed -> done.completeExceptionally(failed.failure());
                    case GpuComputeCompletion.Cancelled ignored -> done.completeExceptionally(
                            new java.util.concurrent.CancellationException("scene TLAS preparation cancelled"));
                }
            });
            pendingTlas = done;
            var trace = prepareTraceGeometry(cache, inputs, origin, pipeline, instanceTable, resources);
            var lighting = neeAt.prepareLightRevision(inputs.content.lights(), metersPerSceneUnit);
            resources.add(lighting::close);
            RtFramePreparation.measure("tlas-ready", inputs.instances.size(), 0, () -> { done.join(); return null; });
            pendingTlas = null;
            ctx.descriptorHeap().writer().writeAccelerationStructure(trace.slot.tlasDescriptor, 0, tlas.accel.handle);
            var ready = new PreparedWorldGeometry(trace, origin, pipeline, instanceTable, instanceBuffer,
                    lighting, metersPerSceneUnit, resources);
            return SharedResource.owned(ready, released -> retire(released.resources::close));
        } catch (Throwable failure) {
            // A packing failure cannot release the source BLAS or TLAS slot while its build is running.
            if (pendingTlas != null) suppressCleanupFailure(failure, pendingTlas::join);
            suppressCleanupFailure(failure, resources::close);
            throw failure;
        }
    }

    /** Borrowed completed geometry and actual submitted predecessor, both protected by this frame. */
    public synchronized GeometryHistory geometry(SceneId scene, GraphicsUse graphicsUse) {
        var use = frameScene(scene, graphicsUse);
        var current = use.geometry;
        var previous = use.previous;
        return new GeometryHistory(current.instanceBuffer.deviceAddress(),
                previous == null ? null : previous.instanceBuffer.deviceAddress(),
                previous == null ? 0 : previous.instanceTable.mask(), current.origin,
                previous == null ? current.origin : previous.origin);
    }

    public synchronized PreparedTrace finishTrace(SceneId scene, GraphicsUse graphicsUse, PreparedLighting lighting) {
        var use = frameScene(scene, graphicsUse);
        var trace = use.geometry.trace;
        lighting.delegate.bindLightTable(trace.slot.lights.deviceAddress());
        return new PreparedTrace(trace.slot.geometry.deviceAddress(), lighting.delegate.stateAddress(),
                trace.slot.tlasDescriptor.firstIndex().value(), trace.hitTable);
    }

    public record GeometryHistory(VulkanDeviceAddress currentInstances, VulkanDeviceAddress previousInstances,
                                  int previousMask, SceneOrigin currentOrigin, SceneOrigin previousOrigin) { }

    private PendingTrace prepareTraceGeometry(SceneCaches cache, FrameSceneSnapshot current, SceneOrigin origin,
                                              RtPipeline pipeline, RtInstanceTablePlan instanceTable, RtRevisionResources resources) {
        List<SceneLight> sceneLights = current.content.lights();
        LightIndexRevision indexed = cache.lightIndex;
        boolean rebuildLightIndices = indexed == null || indexed.lights != sceneLights
                || !indexed.indices.matches(sceneLights);
        int geometryCount = current.geometryHighWater;
        List<List<FrameInstanceSnapshot>> pageInputs = SnapshotList.pagesOf(current.instances);
        LightIndexRevision[] preparedIndex = {indexed};
        var tracePlans = cache.tracePlans;
        TraceBatch[] pages;
        try (var batch = framePreparation.batch()) {
            if (rebuildLightIndices) {
                batch.submit(RtFramePreparation.measured("light-index", 0, sceneLights.size(),
                        () -> {
                            RtDenseLightIndex<SceneLight> indices = indexed == null
                                    ? new RtDenseLightIndex<>(sceneLights.size(), SceneLight::identity) : indexed.indices;
                            indices.update(sceneLights);
                            preparedIndex[0] = new LightIndexRevision(sceneLights, indices);
                        }));
            }
            pages = tracePlans.resolveBatches(pageInputs, framePreparation);
        }
        LightIndexRevision lightIndexRevision = preparedIndex[0];
        if (rebuildLightIndices) cache.lightIndex = lightIndexRevision;
        Long2IntFunction lightIndices = lightIndexRevision.indices;
        int emitterBytes = current.emitterHighWater;
        int geometryBytes = Math.multiplyExact(geometryCount, RtRetainedGeometryPlan.RECORD_BYTES);
        int hitBytes = Math.multiplyExact(Math.multiplyExact(geometryCount,
                RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY), pipeline.retainedHitRecordStride());
        int lightBytes = Math.multiplyExact(sceneLights.size(), RtRetainedLightPlan.RECORD_BYTES);
        TraceSlot slot = acquireTraceSlot(geometryBytes, hitBytes, lightBytes, emitterBytes,
                pipeline, resources);
        // Table slot assignment is part of the immutable geometry payload.
        slot.setInstanceTable(instanceTable);
        var emitterPages = cache.emitterPages;
        List<TracePageWork> writes = new ArrayList<>();
        List<TraceBatchResidency> checkedBatches = new ArrayList<>();
        boolean flushGeometry = false, flushHits = false;
        int activeGeometryRecords = 0;
        int activeEmitterBytes = 0;
        int activeInstances = 0;
        for (int index = 0; index < pages.length; index++) {
            TraceBatch batch = pages[index];
            activeGeometryRecords = Math.addExact(activeGeometryRecords, batch.geometryCount);
            activeEmitterBytes = Math.addExact(activeEmitterBytes, batch.emitterBytes);
            activeInstances = Math.addExact(activeInstances, batch.plans.length);
            var batchResidency = slot.batch(batch, pages);
            boolean geometryResident = batchResidency.hasGeometry(pipeline, slot.instanceAssignments);
            boolean emittersResident = batch.emitterBytes == 0 || batchResidency.hasEmitters(lightIndexRevision);
            if (geometryResident && emittersResident) continue;
            checkedBatches.add(batchResidency);
            for (var page : geometryResident ? batch.emitters : batch.plans) {
                TracePageResidency residency = slot.page(page.range, batchResidency);
                int emitterBase = page.range.emitterBase();
                long emitterAddress = page.emitterBytes == 0 ? 0L
                        : slot.emitters.deviceAddress().addBytes(emitterBase).value();
                int flags = 0;
                int instanceIndex = slot.instanceTable.slot(page.identity.current.identity(),
                        page.identity.current.placementOrdinal());
                if (!geometryResident && !residency.hasGeometry(page.identity, page.geometryBase,
                        emitterAddress, instanceIndex)) {
                    flags |= TracePageWork.GEOMETRY;
                    flushGeometry = true;
                }
                if (!geometryResident && !residency.hasHits(page.identity, page.hitBase(pipeline), pipeline)) {
                    flags |= TracePageWork.HITS;
                    flushHits = true;
                }
                EmitterPageCache emitterPage = null;
                if (!emittersResident && page.emitterBytes != 0) {
                    emitterPage = emitterPages.computeIfAbsent(page.range, ignored -> new EmitterPageCache());
                    if (emitterPage.runs == null || !emitterPage.runs.hasRevision(lightIndexRevision)
                            || !residency.hasEmitters(page.range, emitterBase, emitterPage.runs.generation())) {
                        flags |= TracePageWork.EMITTERS;
                    }
                }
                if (flags != 0) writes.add(new TracePageWork(page, residency, slot,
                        emitterBase, pipeline, lightIndexRevision, lightIndices, emitterPage, flags));
            }
        }
        RtFramePreparation.traceRanges(activeGeometryRecords, geometryCount,
                activeEmitterBytes, emitterBytes, activeInstances);
        if (!writes.isEmpty()) {
            List<List<TracePageWork>> writeChunks = RtFramePreparation.chunks(writes, TracePageWork::work);
            framePreparation.run("pack", writeChunks,
                    List::size,
                    chunk -> chunk.forEach(TracePageWork::pack));
        }
        if (flushGeometry && geometryBytes > 0) slot.geometry.flush(0L, geometryBytes);
        if (flushHits && hitBytes > 0) slot.hits.flush(0L, hitBytes);
        boolean flushEmitters = false;
        for (TracePageWork write : writes) flushEmitters |= write.emittersWritten;
        if (flushEmitters) slot.emitters.flush(0L, emitterBytes);
        writes.forEach(TracePageWork::commit);
        for (var batch : checkedBatches)
            batch.written(pipeline, lightIndexRevision, slot.instanceAssignments);
        slot.retainPages(pages);
        var emitterLayout = tracePlans.emitterLayout;
        if (cache.emitterLayout != emitterLayout) {
            emitterPages.keySet().retainAll(slot.pages.keySet());
            cache.emitterLayout = emitterLayout;
        }
        BitSet linked = slot.linked(emitterLayout, lightIndexRevision);
        List<ByteBuffer> lightPages = cache.lights.resolve(sceneLights, origin, linked, framePreparation);
        List<ByteBuffer> previousLightPages = slot.lightPages;
        slot.lightPages = List.of();
        if (TlasBuilder.copyPages(MemoryUtil.memByteBuffer(slot.lights.mapped(), lightBytes),
                lightPages, previousLightPages) != 0) slot.lights.flush(0L, lightBytes);
        slot.lightPages = lightPages;
        RtPipeline.HitTable hitTable = hitBytes > 0 ? new RtPipeline.HitTable(
                new VulkanDeviceAddressRange(slot.hits.deviceAddress(), hitBytes),
                pipeline.retainedHitRecordStride()) : null;
        return new PendingTrace(current, slot, hitTable);
    }

    private record PendingTrace(FrameSceneSnapshot current, TraceSlot slot, RtPipeline.HitTable hitTable) { }

    /** A single atomic geometry/light/resource publication; every child has completed its GPU build. */
    public static final class PreparedTraceRevision {
        private final SharedResource<PreparedSceneRevision> source;
        private final Map<SceneId, SharedResource<PreparedWorldGeometry>> scenes;
        private final SceneOrigin origin;
        private final float metersPerSceneUnit;

        PreparedTraceRevision(SharedResource<PreparedSceneRevision> source,
                              Map<SceneId, SharedResource<PreparedWorldGeometry>> scenes, SceneOrigin origin,
                              float metersPerSceneUnit) {
            this.source = source;
            this.scenes = scenes;
            this.origin = origin;
            this.metersPerSceneUnit = metersPerSceneUnit;
        }

        public boolean contains(SceneId scene) { return scenes.containsKey(scene); }
        public SceneOrigin origin() { return origin; }
        public float metersPerSceneUnit() { return metersPerSceneUnit; }

        private void close() {
            new ResourceLifetime(() -> closeAll(scenes.values(), null), source::close).close();
        }
    }

    /** Immutable buffers and TLAS ownership remain shared through preparation, frames, and view history. */
    static final class PreparedWorldGeometry {
        final PendingTrace trace;
        final SceneOrigin origin;
        final RtPipeline pipeline;
        final RtInstanceTablePlan instanceTable;
        final GpuBuffer instanceBuffer;
        final SharedResource<RtNeeAtBackend.LightRevision> lighting;
        final float metersPerSceneUnit;
        final RtRevisionResources resources;

        PreparedWorldGeometry(PendingTrace trace, SceneOrigin origin, RtPipeline pipeline,
                              RtInstanceTablePlan instanceTable, GpuBuffer instanceBuffer,
                              SharedResource<RtNeeAtBackend.LightRevision> lighting, float metersPerSceneUnit,
                              RtRevisionResources resources) {
            this.trace = trace;
            this.origin = origin;
            this.pipeline = pipeline;
            this.instanceTable = instanceTable;
            this.instanceBuffer = instanceBuffer;
            this.lighting = lighting;
            this.metersPerSceneUnit = metersPerSceneUnit;
            this.resources = resources;
        }

        boolean matches(Object layout, SceneContent content, SceneOrigin origin, RtPipeline pipeline,
                        float metersPerSceneUnit) {
            return trace.current.layout == layout && trace.current.content == content
                    && this.origin.equals(origin) && this.pipeline == pipeline && this.metersPerSceneUnit == metersPerSceneUnit;
        }
    }

    static boolean hasEmitterMapping(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges,
                                     int firstPrimitive, int primitiveCount) {
        long end = Math.addExact((long) firstPrimitive, primitiveCount);
        int index = firstEmitterRange(ranges, firstPrimitive);
        return primitiveCount > 0 && index < ranges.size() && ranges.get(index).firstPrimitive() < end;
    }

    /** Sorted, disjoint ranges allow skipping every emitter before this geometry in logarithmic time. */
    static int firstEmitterRange(List<RetainedSceneSnapshot.PrimitiveEmitter> ranges, int primitive) {
        int low = 0, high = ranges.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            var range = ranges.get(middle);
            if ((long) range.firstPrimitive() + range.primitiveCount() <= primitive) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Releases all native state after the GPU executor has stopped and the device has been made idle. */
    public void shutdownAfterDeviceIdle() {
        synchronized (this) { if (closed) return; }
        quiesceScenePreparation();
        framePreparation.close();
        // Retirement callbacks may acquire this monitor, so join their executor outside it.
        new ResourceLifetime(this::releaseDeviceIdleResources, retirement::close).close();
    }

    private synchronized void releaseDeviceIdleResources() {
        closed = true;
        capture = null;
        new ResourceLifetime(this::releaseTerminalFrameRoots, neeAt::destroyAfterDeviceIdle,
                traceSlots::close, instanceBuffers::close, tlasSlots::close).close();
    }

    private void releaseTerminalFrameRoots() {
        preparationCaches.clear();
        releaseTerminalFrameRoots(inFlightFrames);
    }

    @SafeVarargs
    static void releaseTerminalFrameRoots(Map<?, ? extends AutoCloseable>... groups) {
        List<AutoCloseable> roots = new ArrayList<>();
        for (var group : groups) {
            roots.addAll(group.values());
            group.clear();
        }
        closeAll(roots, null);
    }

    static Map<SceneId, SceneContent> assembleContent(
            List<RetainedSceneSnapshot.Scene> scenes, List<RetainedSceneSnapshot.Light> lights) {
        return new RtLightPageAssembly().resolve(scenes, lights);
    }

    /** Capture retained scene revisions independently of command recording. */
    public synchronized SharedResource<RetainedSceneSnapshot> captureSnapshot() {
        requireOpen();
        return capture.get();
    }

    /** Attach captured inputs to one execution; later scene edits cannot change this frame. */
    public synchronized void beginFrame(SharedResource<PreparedTraceRevision> root,
                                         SharedResource<PreparedTraceRevision> previous, GraphicsUse graphicsUse) {
        requireOpen();
        FrameSnapshot frame = new FrameSnapshot(graphicsUse, root.retain(), previous == null ? null : previous.retain());
        inFlightFrames.put(graphicsUse, frame);
        try {
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
        if (!frame.root.get().contains(scene)) {
            throw new IllegalArgumentException("scene is not in the frame's scene revision");
        }
        return frame;
    }

    /** Immutable renderer inputs prepared from one atomic scene boundary, with shared source ownership. */
    public static final class PreparedSceneRevision {
        private final SharedResource<RetainedSceneSnapshot> root;
        private final ProgramComposition composition;
        final Map<SceneId, List<InstancePage>> instances;
        final Map<SceneId, SceneContent> content;

        private PreparedSceneRevision(SharedResource<RetainedSceneSnapshot> root,
                                      ProgramComposition composition,
                                      Map<SceneId, List<InstancePage>> instances,
                                      Map<SceneId, SceneContent> content) {
            this.root = root;
            this.composition = composition;
            this.instances = instances;
            this.content = content;
        }

        /** Borrowed source values remain valid while the prepared revision is retained. */
        public RetainedSceneSnapshot snapshot() { return root.get(); }

        private void close() { root.close(); }
    }

    /** Serial-worker cache; its publication claim keeps every borrowed assembly value alive. */
    static final class SceneRevisionPreparation {
        private final FrameAssembly frames;
        private final RtLightPageAssembly lights = new RtLightPageAssembly();
        private SharedResource<PreparedSceneRevision> prepared;

        SceneRevisionPreparation(java.util.function.BiFunction<RetainedSceneSnapshot.Mesh, ProgramComposition, FrameMesh> resolveMesh) {
            frames = new FrameAssembly(resolveMesh);
        }

        SharedResource<PreparedSceneRevision> prepare(SharedResource<RetainedSceneSnapshot> source,
                                                      ProgramComposition composition) {
            RetainedSceneSnapshot snapshot = source.get();
            if (prepared != null && prepared.get().snapshot() == snapshot
                    && prepared.get().composition == composition) return prepared.retain();
            SharedResource<RetainedSceneSnapshot> root = source.retain();
            SharedResource<PreparedSceneRevision> replacement;
            try {
                PreparedSceneRevision previous = prepared == null ? null : prepared.get();
                Map<SceneId, SceneContent> content = previous != null
                        && previous.snapshot().scenes() == snapshot.scenes()
                        && previous.snapshot().lights() == snapshot.lights()
                        ? previous.content : Collections.unmodifiableMap(lights.resolve(snapshot.scenes(), snapshot.lights()));
                var instances = frames.resolve(snapshot, composition);
                replacement = SharedResource.owned(new PreparedSceneRevision(root, composition, instances, content),
                        PreparedSceneRevision::close);
            } catch (Throwable failure) {
                // Partial assembly borrows this source; discard it before releasing the source claim.
                frames.clear();
                lights.clear();
                suppressCleanupFailure(failure, root::close);
                throw failure;
            }
            SharedResource<PreparedSceneRevision> previous = prepared;
            prepared = replacement;
            if (previous != null) previous.close();
            return replacement.retain();
        }

        void clear() {
            frames.clear();
            lights.clear();
            SharedResource<PreparedSceneRevision> previous = prepared;
            prepared = null;
            if (previous != null) previous.close();
        }
    }

    /** Reuses CPU values by immutable page identity; consuming frames retain their own scene roots. */
    static final class FrameAssembly {
        private final Long2ObjectOpenHashMap<FrameMesh> previousFrameMeshes = new Long2ObjectOpenHashMap<>();
        private Set<List<RetainedSceneSnapshot.Mesh>> previousMeshPages = Collections.newSetFromMap(new IdentityHashMap<>());
        private Map<List<RetainedSceneSnapshot.Instance>, Map<SceneId, InstancePage>> previousInstancePages = new IdentityHashMap<>();
        private final Map<SceneId, RtStableTraceRanges> traceRanges = new IdentityHashMap<>();
        private List<RetainedSceneSnapshot.Mesh> assembledMeshes;
        private List<RetainedSceneSnapshot.Instance> assembledInstances;
        private List<RetainedSceneSnapshot.Scene> assembledScenes;
        private ProgramComposition assembledComposition;
        private Map<SceneId, List<InstancePage>> assembledPages;
        private final java.util.function.BiFunction<RetainedSceneSnapshot.Mesh, ProgramComposition, FrameMesh> resolveMesh;

        FrameAssembly(java.util.function.BiFunction<RetainedSceneSnapshot.Mesh, ProgramComposition, FrameMesh> resolveMesh) {
            this.resolveMesh = resolveMesh;
        }

        void clear() {
            previousFrameMeshes.clear();
            previousMeshPages.clear();
            previousInstancePages.clear();
            traceRanges.clear();
            assembledMeshes = null;
            assembledInstances = null;
            assembledScenes = null;
            assembledComposition = null;
            assembledPages = null;
        }

        Map<SceneId, List<InstancePage>> resolve(RetainedSceneSnapshot snapshot, ProgramComposition composition) {
            if (assembledComposition != composition) {
                clear();
                assembledComposition = composition;
            }
            boolean replacedMeshes = false;
            if (assembledMeshes != snapshot.meshes()) {
                Set<List<RetainedSceneSnapshot.Mesh>> nextMeshPages = Collections.newSetFromMap(new IdentityHashMap<>());
                var refreshedMeshes = new LongOpenHashSet();
                for (var page : SnapshotList.pagesOf(snapshot.meshes())) {
                    nextMeshPages.add(page);
                    if (previousMeshPages.contains(page)) continue;
                    for (var mesh : page) {
                        refreshedMeshes.add(mesh.identity());
                        var previous = previousFrameMeshes.get(mesh.identity());
                        if (previous != null && previous.logical == mesh) continue;
                        if (previous != null) replacedMeshes = true;
                        previousFrameMeshes.put(mesh.identity(), resolveMesh.apply(mesh, composition));
                    }
                }
                for (var page : previousMeshPages) {
                    if (nextMeshPages.contains(page)) continue;
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
            if (replacedMeshes) {
                previousInstancePages.clear();
                traceRanges.clear();
            }
            if (!replacedMeshes && assembledInstances == snapshot.instances() && assembledScenes == snapshot.scenes()) {
                return assembledPages;
            }
            List<List<RetainedSceneSnapshot.Instance>> sourcePages = SnapshotList.pagesOf(snapshot.instances());
            Set<List<RetainedSceneSnapshot.Instance>> retainedSources = Collections.newSetFromMap(new IdentityHashMap<>());
            retainedSources.addAll(sourcePages);
            var changedInstances = new Long2ObjectOpenHashMap<RetainedSceneSnapshot.Instance>();
            for (var source : sourcePages) {
                if (!previousInstancePages.containsKey(source)) {
                    for (var instance : source) changedInstances.put(instance.placementOrdinal(), instance);
                }
            }
            var previousInstances = new Long2ObjectOpenHashMap<InstancePage>();
            for (var entry : previousInstancePages.entrySet()) {
                if (retainedSources.contains(entry.getKey())) continue;
                entry.getValue().forEach((scene, page) -> {
                    for (var instance : page.instances) {
                        var next = changedInstances.get(instance.current.placementOrdinal());
                        if (next != null && next.scene() == scene && next.identity() == instance.current.identity()) {
                            previousInstances.put(instance.current.placementOrdinal(), page);
                        } else traceRanges.get(scene).release(instance.range);
                    }
                });
            }
            var nextPages = new IdentityHashMap<List<RetainedSceneSnapshot.Instance>, Map<SceneId, InstancePage>>();
            Map<SceneId, List<InstancePage>> resolved = new IdentityHashMap<>();
            for (var scene : snapshot.scenes()) {
                resolved.put(scene.id(), new ArrayList<>());
            }
            for (var source : sourcePages) {
                var previous = previousInstancePages.get(source);
                Map<SceneId, InstancePage> translated = previous != null
                        && (assembledScenes == snapshot.scenes() || resolved.keySet().containsAll(previous.keySet()))
                        ? previous : new IdentityHashMap<>();
                if (previous != null) {
                    previous.forEach((scene, page) -> {
                        List<InstancePage> scenePages = resolved.get(scene);
                        if (scenePages == null) {
                            page.instances.forEach(instance -> traceRanges.get(scene).release(instance.range));
                            return;
                        }
                        if (translated != previous) translated.put(scene, page);
                        scenePages.add(page);
                    });
                } else {
                    Map<SceneId, List<RetainedSceneSnapshot.Instance>> instances = new IdentityHashMap<>();
                    for (var instance : source) {
                        instances.computeIfAbsent(instance.scene(), ignored -> new ArrayList<>()).add(instance);
                    }
                    instances.forEach((scene, values) -> {
                        var ranges = traceRanges.computeIfAbsent(scene, ignored -> new RtStableTraceRanges());
                        var page = new InstancePage(values, ranges, previousInstances, previousFrameMeshes);
                        translated.put(scene, page);
                        resolved.get(scene).add(page);
                    });
                }
                nextPages.put(source, translated);
            }
            traceRanges.keySet().removeIf(scene -> !resolved.containsKey(scene));
            resolved.replaceAll((scene, pages) -> List.copyOf(pages));
            previousInstancePages = nextPages;
            assembledInstances = snapshot.instances();
            assembledScenes = snapshot.scenes();
            assembledPages = Collections.unmodifiableMap(resolved);
            return assembledPages;
        }

        private static int emitterBytes(RetainedSceneSnapshot.Instance instance, FrameMesh mesh) {
            int bytes = 0;
            List<? extends MeshBuild.Geometry<?>> geometries = mesh.logical.build().geometries();
            for (MeshBuild.Geometry<?> geometry : geometries) {
                if (!hasEmitterMapping(instance.primitiveEmitters(), geometry.firstIndex() / 3,
                        geometry.triangleCount())) continue;
                bytes = Math.addExact(bytes, Math.multiplyExact(geometry.triangleCount(), Integer.BYTES));
            }
            return bytes;
        }


    }

    private SceneUse frameScene(SceneId scene, GraphicsUse graphicsUse) {
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
        ResourceLifetime.closeAfterFailure(failure, cleanup);
    }

    private static void throwFailure(Throwable failure, String message) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException(message, failure);
    }

    private final class FrameSnapshot implements AutoCloseable {
        private final GraphicsUse graphicsUse;
        private SharedResource<PreparedTraceRevision> root;
        private final SharedResource<PreparedTraceRevision> previous;

        FrameSnapshot(GraphicsUse graphicsUse, SharedResource<PreparedTraceRevision> root,
                      SharedResource<PreparedTraceRevision> previous) {
            this.graphicsUse = graphicsUse;
            this.root = root;
            this.previous = previous;
        }

        SceneUse scene(SceneId scene) {
            return new SceneUse(root.get().scenes.get(scene).get(),
                    previous != null && previous.get().contains(scene) ? previous.get().scenes.get(scene).get() : null);
        }

        @Override public void close() {
            SharedResource<PreparedTraceRevision> released;
            synchronized (RtRetainedSceneBackend.this) {
                if (root == null) return;
                inFlightFrames.remove(graphicsUse, this);
                released = root;
                root = null;
            }
            new ResourceLifetime(released::close, () -> {
                if (previous != null) previous.close();
            }).close();
        }
    }

    /** Borrowed from the frame's retained current and submitted-predecessor roots. */
    private record SceneUse(PreparedWorldGeometry geometry, PreparedWorldGeometry previous) { }

    private static final class FrameSceneSnapshot {
        final SceneContent content;
        final List<FrameInstanceSnapshot> instances;
        final Object layout;
        final int geometryHighWater;
        final int emitterHighWater;

        FrameSceneSnapshot(SceneContent content, List<FrameInstanceSnapshot> instances,
                           Object layout, int geometryHighWater, int emitterHighWater) {
            this.content = content;
            this.instances = instances;
            this.layout = layout;
            this.geometryHighWater = geometryHighWater;
            this.emitterHighWater = emitterHighWater;
        }

        static FrameSceneSnapshot prepare(SceneContent content, List<InstancePage> inputs) {
            var geometryPages = new ArrayList<List<FrameInstanceSnapshot>>(inputs.size());
            int geometryEnd = 0, emitterEnd = 0;
            for (var page : inputs) {
                geometryPages.add(page.instances);
                geometryEnd = Math.max(geometryEnd, page.geometryHighWater);
                emitterEnd = Math.max(emitterEnd, page.emitterHighWater);
            }
            return new FrameSceneSnapshot(content, SnapshotList.ofPages(geometryPages),
                    inputs, geometryEnd, emitterEnd);
        }
    }

    /** CPU plans follow instance revision identity and borrow resources from their retained source revision. */
    static final class TracePlanCache {
        private IdentityHashMap<FrameInstanceSnapshot, TracePagePlan> plans = new IdentityHashMap<>();
        private IdentityHashMap<List<FrameInstanceSnapshot>, TraceBatch> pagePlans = new IdentityHashMap<>();
        private List<List<FrameInstanceSnapshot>> previousPages;
        private TraceBatch[] previousBatches;
        private EmitterLayout emitterLayout;

        EmitterLayout emitterLayout() { return emitterLayout; }

        TraceBatch[] resolveBatches(List<List<FrameInstanceSnapshot>> pages, RtFramePreparation preparation) {
            if (previousPages != null && previousPages.size() == pages.size()) {
                boolean unchanged = true;
                for (int index = 0; index < pages.size(); index++) {
                    if (previousPages.get(index) != pages.get(index)) { unchanged = false; break; }
                }
                if (unchanged) return previousBatches;
            }
            int count = 0;
            TraceBatch[] resolved = new TraceBatch[pages.size()];
            var missing = new ArrayList<TracePlanRequest>();
            var changed = new ArrayList<TraceBatchRequest>();
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                var page = pages.get(pageIndex);
                count = Math.addExact(count, page.size());
                var cached = pagePlans.get(page);
                if (cached != null) {
                    resolved[pageIndex] = cached;
                    continue;
                }
                var pageValues = new TracePagePlan[page.size()];
                changed.add(new TraceBatchRequest(pageIndex, page, pageValues));
                for (int index = 0; index < page.size(); index++) {
                    var instance = page.get(index);
                    TracePagePlan plan = plans.get(instance);
                    if (plan == null) missing.add(new TracePlanRequest(pageValues, index, instance));
                    else pageValues[index] = plan;
                }
            }
            if (!missing.isEmpty()) {
                List<List<TracePlanRequest>> chunks = RtFramePreparation.chunks(missing, TracePlanRequest::work);
                preparation.run("plan", chunks,
                        chunk -> chunk.stream().mapToInt(TracePlanRequest::work).sum(),
                        chunk -> chunk.forEach(request -> request.target[request.index] =
                                new TracePagePlan(request.instance)));
                for (TracePlanRequest request : missing) plans.put(request.instance, request.target[request.index]);
            }
            for (var request : changed) {
                var batch = new TraceBatch(request.plans);
                resolved[request.index] = batch;
                pagePlans.put(request.instances, batch);
            }
            if (pagePlans.size() > pages.size() * 2 + 64) {
                var retained = new IdentityHashMap<List<FrameInstanceSnapshot>, TraceBatch>();
                for (var page : pages) retained.put(page, pagePlans.get(page));
                pagePlans = retained;
            }
            if (plans.size() > count * 2 + 64) {
                var retained = new IdentityHashMap<FrameInstanceSnapshot, TracePagePlan>();
                for (var batch : resolved) {
                    for (var plan : batch.plans) retained.put(plan.identity, plan);
                }
                plans = retained;
            }
            previousPages = List.copyOf(pages);
            previousBatches = resolved;
            emitterLayout = EmitterLayout.resolve(emitterLayout, resolved);
            return resolved;
        }
    }

    private record TraceBatchRequest(int index, List<FrameInstanceSnapshot> instances, TracePagePlan[] plans) { }

    /** Only emitter range membership and order affect the scene's linked-light layout. */
    static final class EmitterLayout {
        final List<RtStableTraceRanges.PageRange[]> groups;

        private EmitterLayout(List<RtStableTraceRanges.PageRange[]> groups) { this.groups = groups; }

        static EmitterLayout resolve(EmitterLayout previous, TraceBatch[] batches) {
            var groups = new ArrayList<RtStableTraceRanges.PageRange[]>();
            for (var batch : batches) {
                if (batch.emitterRanges.length != 0) groups.add(batch.emitterRanges);
            }
            if (previous != null && previous.matches(groups)) return previous;
            return new EmitterLayout(List.copyOf(groups));
        }

        private boolean matches(List<RtStableTraceRanges.PageRange[]> next) {
            // Compare flattened range identities; storage-page boundaries do not change the light layout.
            int groupIndex = 0, rangeIndex = 0;
            for (var group : next) {
                if (groupIndex == groups.size()) return false;
                if (rangeIndex == 0 && group == groups.get(groupIndex)) {
                    groupIndex++;
                    continue;
                }
                for (var range : group) {
                    if (groupIndex == groups.size() || range != groups.get(groupIndex)[rangeIndex]) return false;
                    if (++rangeIndex == groups.get(groupIndex).length) {
                        groupIndex++;
                        rangeIndex = 0;
                    }
                }
            }
            return groupIndex == groups.size() && rangeIndex == 0;
        }
    }

    /** Storage-page aggregates allow unchanged groups to skip per-instance residency checks. */
    static final class TraceBatch {
        final TracePagePlan[] plans;
        final TracePagePlan[] emitters;
        final RtStableTraceRanges.PageRange[] emitterRanges;
        final int geometryCount;
        final int emitterBytes;

        TraceBatch(TracePagePlan[] plans) {
            this.plans = plans;
            var emitting = new ArrayList<TracePagePlan>();
            int geometryCount = 0, emitterBytes = 0;
            for (var plan : plans) {
                geometryCount = Math.addExact(geometryCount, plan.records.size());
                emitterBytes = Math.addExact(emitterBytes, plan.emitterBytes);
                if (plan.emitterBytes != 0) emitting.add(plan);
            }
            this.geometryCount = geometryCount;
            this.emitterBytes = emitterBytes;
            emitters = emitting.toArray(TracePagePlan[]::new);
            emitterRanges = new RtStableTraceRanges.PageRange[emitters.length];
            for (int index = 0; index < emitters.length; index++) emitterRanges[index] = emitters[index].range;
        }
    }

    static final class TraceBatchResidency {
        final TraceBatch batch;
        Object revision;
        private Object pipeline;
        private Object lightRevision;
        private long instanceAssignments;

        TraceBatchResidency(TraceBatch batch) { this.batch = batch; }

        boolean hasGeometry(Object pipeline, long instanceAssignments) {
            return this.pipeline == pipeline && this.instanceAssignments == instanceAssignments;
        }

        boolean hasEmitters(Object lightRevision) { return this.lightRevision == lightRevision; }

        void written(Object pipeline, Object lightRevision, long instanceAssignments) {
            this.pipeline = pipeline;
            this.lightRevision = lightRevision;
            this.instanceAssignments = instanceAssignments;
        }
    }

    private record TracePlanRequest(TracePagePlan[] target, int index, FrameInstanceSnapshot instance) {
        int work() {
            return Math.max(1, instance.geometryRecords.size());
        }
    }

    /** Immutable geometry, emitter layout, and pipeline SBT data for one current instance. */
    static final class TracePagePlan {
        final FrameInstanceSnapshot identity;
        final RtStableTraceRanges.PageRange range;
        final int geometryBase;
        final List<RtRetainedGeometryPlan.GeometryRecord> records;
        final List<EmitterSpan> emitterSpans;
        final int emitterBytes;
        private final List<RtRetainedGeometryPlan.HitGroup> hitGroups;
        private final IdentityHashMap<RtPipeline, ByteBuffer> hitsByPipeline = new IdentityHashMap<>();

        TracePagePlan(FrameInstanceSnapshot frame) {
            identity = frame;
            range = frame.range;
            geometryBase = range.geometryBase();
            var spans = new ArrayList<EmitterSpan>();
            int bytes = 0;
            List<? extends MeshBuild.Geometry<?>> geometries = frame.mesh.logical.build().geometries();
            for (int index = 0; index < geometries.size(); index++) {
                MeshBuild.Geometry<?> geometry = geometries.get(index);
                if (!hasEmitterMapping(frame.current.primitiveEmitters(), geometry.firstIndex() / 3,
                        geometry.triangleCount())) continue;
                spans.add(new EmitterSpan(index, bytes, geometry.firstIndex() / 3,
                        geometry.triangleCount(), frame.current.primitiveEmitters()));
                bytes = Math.addExact(bytes, Math.multiplyExact(geometry.triangleCount(), Integer.BYTES));
            }
            records = frame.geometryRecords;
            emitterSpans = List.copyOf(spans);
            emitterBytes = bytes;
            if (records.size() != range.geometryCount() || emitterBytes != range.emitterBytes()) {
                throw new IllegalStateException("trace page layout differs from its stable range");
            }
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

        void packGeometry(TraceSlot slot, int emitterBase) {
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
            RtRetainedGeometryPlan.packInto(target, output,
                    slot.instanceTable.slot(identity.current.identity(), identity.current.placementOrdinal()));
        }

        void packHits(TraceSlot slot, RtPipeline pipeline) {
            ByteBuffer source = hits(pipeline);
            ByteBuffer target = MemoryUtil.memByteBuffer(slot.hits.mapped() + hitBase(pipeline), source.remaining());
            target.put(0, source, source.position(), source.remaining());
        }

    }

    /** Serial-worker CPU caches share one scene lifetime; completed revisions own GPU resources separately. */
    private static final class SceneCaches {
        LightIndexRevision lightIndex;
        final TracePlanCache tracePlans = new TracePlanCache();
        final RtPackedLightPages lights = new RtPackedLightPages();
        final RtPackedTlasPages tlas = new RtPackedTlasPages();
        final RtPackedInstancePages instances = new RtPackedInstancePages();
        final IdentityHashMap<RtStableTraceRanges.PageRange, EmitterPageCache> emitterPages = new IdentityHashMap<>();
        EmitterLayout emitterLayout;
    }

    private static final class EmitterPageCache {
        private static final ThreadLocal<RtEmitterRuns.Builder> BUILDERS =
                ThreadLocal.withInitial(RtEmitterRuns.Builder::new);
        RtEmitterRuns runs;

        void resolve(TracePagePlan page, LightIndexRevision revision, Long2IntFunction indices) {
            if (runs == null) {
                if (page.emitterSpans.isEmpty()) {
                    runs = RtEmitterRuns.empty();
                } else {
                    var builder = BUILDERS.get();
                    builder.reset();
                    for (EmitterSpan span : page.emitterSpans) {
                        builder.addSpan(span.byteOffset, span.firstPrimitive, span.primitiveCount, span.ranges);
                    }
                    runs = builder.build();
                }
            }
            runs.resolve(revision, indices);
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
        final int emitterBase;
        final RtPipeline pipeline;
        final LightIndexRevision lightIndexRevision;
        final Long2IntFunction lightIndices;
        final int flags;
        final EmitterPageCache emitterPage;
        boolean emittersWritten;

        TracePageWork(TracePagePlan page, TracePageResidency residency, TraceSlot slot,
                      int emitterBase, RtPipeline pipeline, LightIndexRevision lightIndexRevision,
                      Long2IntFunction lightIndices, EmitterPageCache emitterPage, int flags) {
            this.page = page;
            this.residency = residency;
            this.slot = slot;
            this.emitterBase = emitterBase;
            this.pipeline = pipeline;
            this.lightIndexRevision = lightIndexRevision;
            this.lightIndices = lightIndices;
            this.flags = flags;
            this.emitterPage = emitterPage;
        }

        void pack() {
            if ((flags & GEOMETRY) != 0) {
                page.packGeometry(slot, emitterBase);
            }
            if ((flags & HITS) != 0) {
                page.packHits(slot, pipeline);
            }
            if ((flags & EMITTERS) != 0) {
                emitterPage.resolve(page, lightIndexRevision, lightIndices);
                if (!residency.hasEmitters(page.range, emitterBase, emitterPage.runs.generation())
                        && page.emitterBytes > 0) {
                    ByteBuffer target = MemoryUtil.memByteBuffer(slot.emitters.mapped() + emitterBase, page.emitterBytes)
                            .order(ByteOrder.nativeOrder());
                    emitterPage.runs.pack(target);
                    emittersWritten = true;
                }
            }
        }

        void commit() {
            long emitterAddress = page.emitterBytes == 0 ? 0L
                    : slot.emitters.deviceAddress().addBytes(emitterBase).value();
            if ((flags & GEOMETRY) != 0) {
                residency.geometryWritten(page.identity, page.geometryBase, emitterAddress,
                        slot.instanceTable.slot(page.identity.current.identity(), page.identity.current.placementOrdinal()));
            }
            if ((flags & HITS) != 0) {
                residency.hitsWritten(page.identity, page.hitBase(pipeline), pipeline);
            }
            if ((flags & EMITTERS) != 0) {
                residency.linkedEmittersWritten(emitterPage.runs.linked());
                residency.emittersWritten(page.range, emitterBase, emitterPage.runs.generation());
            }
        }

        int work() {
            return Math.max(1, Math.addExact(page.records.size(), page.emitterBytes / Integer.BYTES));
        }

    }

    /**
     * Independent stamps keep light-index edits from invalidating geometry or the SBT.
     * Geometry stores instance indices; origin-relative transforms belong to the instance table.
     */
    static final class TracePageResidency {
        private TraceBatchResidency batch;
        private Object geometryPage;
        private int geometryBase;
        private long geometryEmitterAddress;
        private int geometryInstanceIndex;
        private Object hitPage;
        private int hitBase;
        private Object hitPipeline;
        private Object emitterPage;
        private int emitterBase;
        private Object emitterGeneration;
        // Dense indices can be far apart even when this page links only a handful of lights.
        int[] linkedEmitters = new int[0];

        void linkedEmittersWritten(int[] indices) {
            linkedEmitters = indices;
        }

        void addLinkedEmittersTo(BitSet target) {
            for (int index : linkedEmitters) target.set(index);
        }

        boolean hasGeometry(Object page, int base, long emitterAddress, int instanceIndex) {
            return geometryPage == page && geometryBase == base
                    && geometryEmitterAddress == emitterAddress && geometryInstanceIndex == instanceIndex;
        }

        void geometryWritten(Object page, int base, long emitterAddress, int instanceIndex) {
            geometryPage = page;
            geometryBase = base;
            geometryEmitterAddress = emitterAddress;
            geometryInstanceIndex = instanceIndex;
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
            return emitterPage == page && emitterBase == base && emitterGeneration == revision;
        }

        void emittersWritten(Object page, int base, Object revision) {
            emitterPage = page;
            emitterBase = base;
            emitterGeneration = revision;
        }
    }

    record FrameInstanceSnapshot(RetainedSceneSnapshot.Instance current, FrameMesh mesh,
                                 RtStableTraceRanges.PageRange range,
                                 List<RtRetainedGeometryPlan.GeometryRecord> geometryRecords) {
        int geometryBase() { return range.geometryBase(); }

        int sbtRecordOffset() {
            return Math.multiplyExact(geometryBase(), RtRetainedGeometryPlan.HIT_RECORDS_PER_GEOMETRY);
        }

        RtRetainedGeometryPlan.ResolvedMesh resolvedMesh() { return mesh.resolved; }
    }

    /** Storage pages group iteration; each instance revision owns its own trace range generation. */
    static final class InstancePage {
        final List<FrameInstanceSnapshot> instances;
        final int geometryHighWater;
        final int emitterHighWater;

        InstancePage(List<RetainedSceneSnapshot.Instance> values, RtStableTraceRanges ranges,
                     Long2ObjectOpenHashMap<InstancePage> previousPages, Long2ObjectOpenHashMap<FrameMesh> meshes) {
            var frames = new ArrayList<FrameInstanceSnapshot>(values.size());
            for (var logical : values) {
                var mesh = meshes.get(logical.meshIdentity());
                var previousPage = previousPages.get(logical.placementOrdinal());
                FrameInstanceSnapshot previous = previousPage == null ? null
                        : previousPage.instance(logical.placementOrdinal(), logical.identity());
                if (previous != null && previous.current == logical && previous.mesh == mesh) {
                    frames.add(previous);
                    continue;
                }
                int count = mesh.logical.build().geometries().size();
                int emitterBytes = FrameAssembly.emitterBytes(logical, mesh);
                var range = previous == null ? ranges.reserve(count, emitterBytes)
                        : ranges.replace(previous.range, count, emitterBytes);
                var records = RtRetainedGeometryPlan.records(mesh.resolved, 0);
                var value = new FrameInstanceSnapshot(logical, mesh, range, records);
                frames.add(value);
            }
            instances = List.copyOf(frames);
            int geometryEnd = 0, emitterEnd = 0;
            for (var instance : instances) {
                geometryEnd = Math.max(geometryEnd, Math.addExact(instance.range.geometryBase(), instance.range.geometryCount()));
                emitterEnd = Math.max(emitterEnd, Math.addExact(instance.range.emitterBase(), instance.range.emitterBytes()));
            }
            geometryHighWater = geometryEnd;
            emitterHighWater = emitterEnd;
        }

        private FrameInstanceSnapshot instance(long placementOrdinal, long identity) {
            int first = 0;
            int end = instances.size();
            while (first < end) {
                int middle = (first + end) >>> 1;
                if (instances.get(middle).current.placementOrdinal() < placementOrdinal) first = middle + 1;
                else end = middle;
            }
            if (first == instances.size()) return null;
            var instance = instances.get(first);
            return instance.current.placementOrdinal() == placementOrdinal && instance.current.identity() == identity
                    ? instance : null;
        }

    }

    public record SceneLight(long identity, LightDescriptor descriptor) {
        public SceneLight { Objects.requireNonNull(descriptor, "descriptor"); }
    }

    public record SceneContent(EnvironmentBinding<?> environment, List<SceneLight> lights) {
        public SceneContent {
            if (!(lights instanceof SnapshotList<?>)) lights = List.copyOf(lights);
        }
    }

    public record LightingFrame(Object view, int width, int height, long frameIndex, float metersPerSceneUnit,
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
            ByteBuffer target = roots.slice().order(ByteOrder.nativeOrder());
            target.putLong(RtBindings.WORLD_GEOMETRY_TABLE_ADDRESS_OFFSET,
                    geometryRecordsAddress.value());
            target.putInt(RtBindings.WORLD_TOP_LEVEL_AS_INDEX_OFFSET, tlasDescriptorIndex);
            target.putLong(RtBindings.WORLD_NEE_AT_STATE_ADDRESS_OFFSET, neeAtStateAddress.value());
        }
    }

    /** A last-reader release returns writable storage; pipeline hit records retain their required alignment. */
    private TraceSlot acquireTraceSlot(int geometryBytes, int hitBytes, int lightBytes,
                                      int emitterBytes, RtPipeline pipeline, RtRevisionResources resources) {
        TraceSlot slot = traceSlots.acquire(candidate ->
                        candidate.geometry.size() >= geometryBytes && candidate.hits.size() >= hitBytes
                                && candidate.lights.size() >= lightBytes && candidate.emitters.size() >= emitterBytes
                                && candidate.hits.deviceAddress().value() % pipeline.retainedHitTableAlignment() == 0,
                () -> createTraceSlot(ctx, geometryBytes, hitBytes, lightBytes, emitterBytes, pipeline));
        try {
            resources.add(() -> traceSlots.release(slot));
            return slot;
        } catch (Throwable failure) {
            traceSlots.release(slot);
            throw failure;
        }
    }

    private static TraceSlot createTraceSlot(VulkanDeviceContext ctx, int geometryBytes, int hitBytes,
                                             int lightBytes, int emitterBytes, RtPipeline pipeline) {
        var resources = new RtRevisionResources();
        try {
            var geometry = ctx.createMappedGpuUploadBuffer(
                    RtCompletionSlotPool.capacity(Math.max(RtRetainedGeometryPlan.RECORD_BYTES, geometryBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained geometry records");
            resources.add(geometry::destroy);
            var hits = ctx.createMappedGpuUploadBuffer(
                    RtCompletionSlotPool.capacity(Math.max(pipeline.retainedHitRecordStride(), hitBytes)),
                    VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, "retained hit SBT",
                    pipeline.retainedHitTableAlignment());
            resources.add(hits::destroy);
            var lights = ctx.createMappedGpuUploadBuffer(
                    RtCompletionSlotPool.capacity(Math.max(RtRetainedLightPlan.RECORD_BYTES, lightBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained light records");
            resources.add(lights::destroy);
            var emitters = ctx.createMappedGpuUploadBuffer(
                    RtCompletionSlotPool.capacity(Math.max(Integer.BYTES, emitterBytes)),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "retained primitive-light indices");
            resources.add(emitters::destroy);
            var descriptor = ctx.descriptorHeap().allocateResources(1);
            resources.add(descriptor::destroy);
            return new TraceSlot(geometry, hits, lights, emitters, descriptor, resources);
        } catch (Throwable failure) {
            suppressCleanupFailure(failure, resources::close);
            throw failure;
        }
    }

    static final class TraceSlot {
        RtInstanceTablePlan instanceTable;
        long instanceAssignments;
        List<ByteBuffer> lightPages = List.of();
        private Object batchRevision;
        private Object linkedLayout;
        private Object linkedRevision;
        private BitSet linked = new BitSet();
        final IdentityHashMap<TraceBatch, TraceBatchResidency> batches = new IdentityHashMap<>();
        final IdentityHashMap<RtStableTraceRanges.PageRange, TracePageResidency> pages = new IdentityHashMap<>();
        final GpuBuffer geometry;
        final GpuBuffer hits;
        final GpuBuffer lights;
        final GpuBuffer emitters;
        final GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor;
        private final RtRevisionResources resources;

        TraceSlot(GpuBuffer geometry, GpuBuffer hits, GpuBuffer lights, GpuBuffer emitters,
                  GpuDescriptorRange<GpuDescriptorIndex.Resource> tlasDescriptor, RtRevisionResources resources) {
            this.geometry = geometry;
            this.hits = hits;
            this.lights = lights;
            this.emitters = emitters;
            this.tlasDescriptor = tlasDescriptor;
            this.resources = resources;
        }

        void setInstanceTable(RtInstanceTablePlan table) {
            if (!table.sameSlotAssignments(instanceTable)) instanceAssignments++;
            instanceTable = table;
        }

        TraceBatchResidency batch(TraceBatch batch, Object currentRevision) {
            var residency = batches.computeIfAbsent(batch, TraceBatchResidency::new);
            residency.revision = currentRevision;
            return residency;
        }

        TracePageResidency page(RtStableTraceRanges.PageRange range, TraceBatchResidency batch) {
            TracePageResidency residency = pages.computeIfAbsent(range, ignored -> new TracePageResidency());
            residency.batch = batch;
            return residency;
        }

        void retainPages(Object currentRevision) {
            if (batchRevision == currentRevision) return;
            var iterator = batches.values().iterator();
            while (iterator.hasNext()) {
                var batch = iterator.next();
                if (batch.revision == currentRevision) continue;
                for (var plan : batch.batch.plans) {
                    var residency = pages.get(plan.range);
                    if (residency != null && residency.batch == batch) pages.remove(plan.range);
                }
                iterator.remove();
            }
            batchRevision = currentRevision;
        }

        BitSet linked(EmitterLayout currentLayout, Object lightRevision) {
            if (linkedLayout != currentLayout || linkedRevision != lightRevision) {
                var next = new BitSet();
                for (var group : currentLayout.groups) {
                    for (var range : group) pages.get(range).addLinkedEmittersTo(next);
                }
                linked = next;
                linkedLayout = currentLayout;
                linkedRevision = lightRevision;
            }
            return linked;
        }

        void destroy() {
            resources.close();
        }
    }

    static LightIndexRevision indexLights(List<SceneLight> lights) {
        var indices = new RtDenseLightIndex<SceneLight>(lights.size(), SceneLight::identity);
        indices.update(lights);
        return new LightIndexRevision(lights, indices);
    }

    record LightIndexRevision(List<SceneLight> lights, RtDenseLightIndex<SceneLight> indices) { }

    private static final class InstanceUploadSlot {
        final GpuBuffer buffer;
        List<ByteBuffer> pages = List.of();

        InstanceUploadSlot(GpuBuffer buffer) { this.buffer = buffer; }
    }

    /**
     * Immutable CPU resolution reused for one captured mesh and composition. The preparation cache
     * retains the source root; each consuming renderer revision owns its mesh and program resources.
     */
    static final class FrameMesh {
        final RetainedSceneSnapshot.Mesh logical;
        final RtPreparedMesh nativeMesh;
        final RtRetainedGeometryPlan.ResolvedMesh resolved;
        FrameMesh(RetainedSceneSnapshot.Mesh logical, RtPreparedMesh nativeMesh, ProgramComposition composition) {
            this.logical = logical;
            this.nativeMesh = nativeMesh;
            resolved = RtRetainedGeometryPlan.resolve(logical.build(), composition);
        }
        RtPreparedMesh.State blas() { return nativeMesh.value(); }
    }
}
