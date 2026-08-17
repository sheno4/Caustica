package dev.comfyfluffy.caustica.rt.light;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.api.gpu.GpuBuffer;
import dev.comfyfluffy.caustica.engine.light.RetainedLightBatch;
import dev.comfyfluffy.caustica.engine.light.RetainedLightSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous lifecycle for one retained finite-light generation at a time. CPU record packing and
 * BVH construction run on a worker. The render thread
 * only atomically publishes GPU-complete buffers; the worker also allocates/fills staging and device
 * buffers and enqueues the copy on {@link RtGpuExecutor}. The previous complete generation remains
 * shader-visible until that point. Host generations arriving during a build coalesce to the latest
 * immutable snapshot and start after the in-flight generation reaches a terminal state.
 */
public final class RtRetainedLightScene {
    private final TaskScheduler taskScheduler;
    private final Object buildLock = new Object();
    private final Object taskLock = new Object();
    private final ConcurrentLinkedQueue<Completion> completions = new ConcurrentLinkedQueue<>();
    private int activeTasks;
    private volatile long latestRequest;
    private long sourceGeneration = Long.MIN_VALUE;
    private SceneOrigin sourceOrigin;
    private double sourceMetersPerWorldUnit = Double.NaN;
    private Input pendingInput;
    private PublishedState published = PublishedState.EMPTY;

    public RtRetainedLightScene() {
        this(new OwnedTaskScheduler());
    }

    RtRetainedLightScene(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    /**
     * Accept the host's latest immutable input and advance renderer-owned build/upload publication.
     * Unchanged generations reuse the existing batch snapshot without comparison or copying.
     */
    public PublishedState advance(GpuContext ctx, RetainedLightSnapshot snapshot,
                                  SceneOrigin origin, double metersPerWorldUnit,
                                  double debugWorldX, double debugWorldY, double debugWorldZ) {
        publishReady(ctx);
        if (snapshot.generation() != sourceGeneration || !origin.equals(sourceOrigin)
                || metersPerWorldUnit != sourceMetersPerWorldUnit) {
            sourceGeneration = snapshot.generation();
            sourceOrigin = origin;
            sourceMetersPerWorldUnit = metersPerWorldUnit;
            if (snapshot.isEmpty()) {
                pendingInput = null;
                invalidate(ctx, ctx.gpuExecutor().latestGraphicsUse());
            } else {
                pendingInput = new Input(snapshot.batches(), origin.x(), origin.y(), origin.z(),
                        metersPerWorldUnit, new DebugFocus(debugWorldX, debugWorldY, debugWorldZ));
            }
        }
        startPending(ctx);
        return published;
    }

    private void startPending(GpuContext ctx) {
        if (pendingInput == null || !isIdle()) return;
        Input input = pendingInput;
        pendingInput = null;
        request(ctx, input);
    }

    /** True only when no worker/upload owns a generation and no completion is waiting to be published. */
    private boolean isIdle() {
        synchronized (taskLock) {
            return activeTasks == 0 && completions.isEmpty();
        }
    }

    private void request(GpuContext ctx, Input input) {
        if (!isIdle()) {
            throw new IllegalStateException(
                    "RtRetainedLightScene.request() called while a generation is still in flight");
        }
        long requestId;
        synchronized (buildLock) {
            requestId = ++latestRequest;
        }
        Request request = new Request(requestId, ctx, input);
        beginTask();
        try {
            taskScheduler.submit(() -> runWorker(request), this::finishTask);
        } catch (Throwable t) {
            finishTask();
            throw t;
        }
    }

    /** Publish only fully uploaded, internally coherent worker generations. */
    private void publishReady(GpuContext ctx) {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Failed failed) {
                if (isLatest(failed.requestId)) {
                    throw new RuntimeException("RT light hierarchy build failed for request "
                            + failed.requestId, failed.failure);
                }
                continue;
            }
            if (completion instanceof Empty empty) {
                if (isLatest(empty.requestId)) publishEmpty(ctx, empty.requestId);
                continue;
            }

            Uploaded uploaded = (Uploaded) completion;
            if (!isLatest(uploaded.requestId) || uploaded.failure != null) {
                uploaded.destroy();
                if (isLatest(uploaded.requestId) && uploaded.failure != null) {
                    throw new RuntimeException("RT light hierarchy upload failed for request "
                            + uploaded.requestId, uploaded.failure);
                }
                continue;
            }
            publish(ctx, uploaded);
        }
    }

    /** World-reset path only. Normal light changes intentionally retain the published generation. */
    private void invalidate(GpuContext ctx, GraphicsUse lastGraphicsUse) {
        cancelPending();
        PublishedState old = published;
        published = PublishedState.EMPTY;
        old.retire(ctx, lastGraphicsUse);
    }

    /** Invalidate the current generation (in-flight build/upload self-discards) and drop any completion. */
    private void cancelPending() {
        pendingInput = null;
        synchronized (buildLock) {
            latestRequest++;
        }
        discardCompletions();
    }

    public void destroyAfterDeviceIdle() {
        discardCompletions();
        PublishedState old = published;
        published = PublishedState.EMPTY;
        old.destroy();
    }

    /**
     * Stop renderer-owned CPU light work before the GPU executor takes its stable drain snapshot.
     * Upload callbacks remain part of that subsequent GPU drain and are released at device idle.
     */
    public void stopCpuWork() {
        cancelPending();
        taskScheduler.shutdown();
    }

    private void runWorker(Request request) {
        try {
            RtRetainedLightSceneBuilder.Data data = RtRetainedLightSceneBuilder.build(request.input.batches,
                    request.input.rebaseX, request.input.rebaseY, request.input.rebaseZ,
                    request.input.metersPerWorldUnit,
                    () -> !isLatest(request.requestId));
            if (isLatest(request.requestId)) {
                if (data.lightCount() == 0) {
                    completions.add(new Empty(request.requestId));
                } else {
                    // VMA allocation, staging serialization/flush, and executor enqueue all stay on this
                    // worker. The render thread only atomically publishes Uploaded.
                    submitUpload(request.ctx, request.requestId, data, request.input.debugFocus);
                }
            }
        } catch (Throwable t) {
            if (isLatest(request.requestId)) {
                completions.add(new Failed(request.requestId, t));
            }
        } finally {
            finishTask();
        }
    }

    private void submitUpload(GpuContext ctx, long requestId, RtRetainedLightSceneBuilder.Data data,
                              DebugFocus debugFocus) {
        Layout layout = Layout.of(data);

        GpuBuffer arena = null;
        GpuBuffer upload = null;
        try {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            arena = ctx.createAsyncBuffer(layout.totalBytes, usage, false,
                    "retained light scene arena " + requestId);
            upload = ctx.createUploadBuffer(layout.totalBytes,
                    "retained light scene upload " + requestId);

            long cursor = upload.mapped() + layout.lightOffset;
            MemoryUtil.memFloatBuffer(cursor, data.packedLights().length).put(data.packedLights());
            cursor = upload.mapped() + layout.nodeOffset;
            MemoryUtil.memFloatBuffer(cursor, data.packedNodes().length).put(data.packedNodes());
            upload.flush();

            GpuBuffer submittedUpload = upload;
            GpuBuffer submittedArena = arena;
            Layout submittedLayout = layout;
            beginTask();
            boolean accepted = false;
            try {
                ctx.gpuExecutor().submit(
                        () -> !isLatest(requestId),
                        cmd -> recordUpload(cmd, submittedUpload, submittedArena,
                                submittedLayout.totalBytes),
                        () -> { },
                        (build, failure) -> finishUpload(requestId, data, debugFocus,
                                submittedUpload, submittedArena, submittedLayout, build, failure));
                accepted = true;
                upload = null;
                arena = null;
            } finally {
                if (!accepted) finishTask();
            }
        } finally {
            if (upload != null) upload.destroy();
            if (arena != null) arena.destroy();
        }
    }

    private void finishUpload(long requestId, RtRetainedLightSceneBuilder.Data data,
                              DebugFocus debugFocus,
                              GpuBuffer upload,
                              GpuBuffer arena, Layout layout,
                              RtGpuExecutor.Build build, Throwable failure) {
        try {
            upload.destroy();
        } finally {
            try {
                if (isLatest(requestId)) {
                    completions.add(new Uploaded(requestId, data, debugFocus,
                            arena, layout, build, failure));
                }
                else arena.destroy();
            } finally {
                finishTask();
            }
        }
    }

    private void publish(GpuContext ctx, Uploaded uploaded) {
        PublishedState next = new PublishedState(uploaded.arena, uploaded.layout,
                uploaded.data.lightCount(), uploaded.data.rootNodeIndex(),
                uploaded.data.rebaseX(), uploaded.data.rebaseY(),
                uploaded.data.rebaseZ(), (float) uploaded.data.metersPerWorldUnit());
        PublishedState old = published;
        // The executor's host-side timeline wait only proves that the transfer completed. It does not
        // establish device-memory visibility from the async queue to the graphics queue. Publish the
        // exact upload build so beginGraphicsUse() attaches the required semaphore dependency
        // before any shader can dereference this generation's buffer device addresses.
        ctx.gpuExecutor().markPublished(uploaded.build);
        published = next;
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUse());

        if (CausticaConfig.Rt.Lights.DUMP.value() && uploaded.debugFocus != null) {
            dumpNearbyLights(uploaded.data, uploaded.debugFocus);
        }

        if (CausticaConfig.Rt.Lights.STATS.value()) {
            CausticaMod.LOGGER.info("RT light hierarchy {}: {} lights / {} nodes / depth {} / {} KiB / {} lm",
                    uploaded.requestId, uploaded.data.lightCount(),
                    uploaded.data.lightBvh().nodes().size(), uploaded.data.lightBvh().maxDepth(),
                    (uploaded.layout.totalBytes + 1023L) >> 10,
                    uploaded.data.lightBvh().totalLuminousPowerLumens());
        }
    }

    private static void dumpNearbyLights(RtRetainedLightSceneBuilder.Data data,
                                         DebugFocus focus) {
        double px = focus.relativeX(data.rebaseX());
        double py = focus.relativeY(data.rebaseY());
        double pz = focus.relativeZ(data.rebaseZ());
        double radius = CausticaConfig.Rt.Lights.DUMP_RADIUS.value();
        double radiusSq = radius * radius;
        float[] lights = data.packedLights();
        int dumped = 0;
        for (int light = 0; light < data.lightCount(); light++) {
            int record = light * RtRetainedLightSceneBuilder.GPU_FLOATS_PER_LIGHT;
            float x = lights[record];
            float y = lights[record + 1];
            float z = lights[record + 2];
            double dx = x - px, dy = y - py, dz = z - pz;
            if (dx * dx + dy * dy + dz * dz > radiusSq) continue;
            float leR = lights[record + 12];
            float leG = lights[record + 13];
            float leB = lights[record + 14];
            int type = Float.floatToRawIntBits(lights[record + 3]);
            CausticaMod.LOGGER.info("RT light[{}] type={} world=({}, {}, {}) radiometry=({}, {}, {}) metric={}",
                    light, type, x + data.rebaseX(), y + data.rebaseY(), z + data.rebaseZ(),
                    leR, leG, leB, lights[record + 15]);
            dumped++;
        }
        CausticaMod.LOGGER.info("RT light dump: {} lights within {} world units", dumped, (int) radius);
    }

    private void publishEmpty(GpuContext ctx, long requestId) {
        if (!isLatest(requestId)) return;
        PublishedState old = published;
        published = PublishedState.empty();
        old.retire(ctx, ctx.gpuExecutor().latestGraphicsUse());
    }

    private void discardCompletions() {
        Completion completion;
        while ((completion = completions.poll()) != null) {
            if (completion instanceof Uploaded uploaded) uploaded.destroy();
        }
    }

    private boolean isLatest(long requestId) {
        return requestId == latestRequest;
    }

    private void beginTask() {
        synchronized (taskLock) {
            activeTasks++;
        }
    }

    private void finishTask() {
        synchronized (taskLock) {
            if (--activeTasks < 0) throw new IllegalStateException("RT hierarchy active-task underflow");
            if (activeTasks == 0) taskLock.notifyAll();
        }
    }

    private static void recordUpload(VkCommandBuffer cmd, GpuBuffer upload, GpuBuffer arena,
                                     long totalBytes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack);
            region.get(0).srcOffset(0L).dstOffset(0L).size(totalBytes);
            VK10.vkCmdCopyBuffer(cmd, upload.handle(), arena.handle(), region);
        }
    }

    public interface TaskScheduler {
        /** The cancellation callback must run only when an accepted task will never execute. */
        void submit(Runnable task, Runnable cancelled);

        default void shutdown() {
        }
    }

    private record Input(List<RetainedLightBatch> batches,
                         double rebaseX, double rebaseY, double rebaseZ,
                         double metersPerWorldUnit, DebugFocus debugFocus) { }

    public record PublishedState(GpuBuffer arena, Layout layout, int lightCount, int rootNodeIndex,
                           double rebaseX, double rebaseY, double rebaseZ,
                           float metersPerWorldUnit) {
        private static final PublishedState EMPTY = empty();

        private static PublishedState empty() {
            return new PublishedState(
                    null, Layout.EMPTY, 0, -1, 0, 0, 0, 1.0f);
        }

        public long lightAddress() { return address(layout.lightOffset); }
        public long nodeAddress() { return address(layout.nodeOffset); }

        private long address(long offset) {
            return arena != null ? arena.deviceAddress() + offset : 0L;
        }

        private void retire(GpuContext ctx, GraphicsUse lastGraphicsUse) {
            if (arena != null) {
                ctx.gpuExecutor().retireAfterGraphics(lastGraphicsUse, arena::destroy);
            }
        }

        private void destroy() {
            if (arena != null) arena.destroy();
        }
    }

    public record Layout(long lightOffset, long nodeOffset, long totalBytes) {
        private static final Layout EMPTY = new Layout(0, 0, 0);

        static Layout of(RtRetainedLightSceneBuilder.Data data) {
            long cursor = 0L;
            long lights = cursor;
            cursor = align16(Math.addExact(cursor, data.lightBytes()));
            long nodes = cursor;
            cursor = align16(Math.addExact(cursor, data.nodeBytes()));
            return new Layout(lights, nodes, cursor);
        }

        private static long align16(long value) {
            return Math.addExact(value, 15L) & ~15L;
        }
    }

    private sealed interface Completion permits Failed, Empty, Uploaded { }
    private record Failed(long requestId, Throwable failure) implements Completion { }
    private record Empty(long requestId) implements Completion { }
    private record Uploaded(long requestId, RtRetainedLightSceneBuilder.Data data,
                            DebugFocus debugFocus,
                            GpuBuffer arena, Layout layout,
                            RtGpuExecutor.Build build, Throwable failure) implements Completion {
        void destroy() { arena.destroy(); }
    }
    private record Request(long requestId, GpuContext ctx, Input input) { }

    private record DebugFocus(double worldX, double worldY, double worldZ) {
        double relativeX(double rebaseX) { return worldX - rebaseX; }
        double relativeY(double rebaseY) { return worldY - rebaseY; }
        double relativeZ(double rebaseZ) { return worldZ - rebaseZ; }
    }

    private static final class OwnedTaskScheduler implements TaskScheduler {
        private final ThreadPoolExecutor executor;

        private OwnedTaskScheduler() {
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "rt-retained-lights");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            };
            executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), factory);
            executor.allowCoreThreadTimeOut(true);
        }

        @Override
        public void submit(Runnable task, Runnable cancelled) {
            executor.execute(new ScheduledTask(task, cancelled));
        }

        @Override
        public void shutdown() {
            for (Runnable queued : executor.shutdownNow()) {
                ((ScheduledTask) queued).cancelled.run();
            }
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted stopping retained-light worker", e);
            }
        }

        private record ScheduledTask(Runnable task, Runnable cancelled) implements Runnable {
            @Override
            public void run() {
                task.run();
            }
        }
    }
}
