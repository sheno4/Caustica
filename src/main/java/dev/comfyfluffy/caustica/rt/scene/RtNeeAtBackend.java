package dev.comfyfluffy.caustica.rt.scene;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.rt.gen.NeeAtStateData;
import dev.comfyfluffy.caustica.rt.pipeline.RtBindings;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent per-scene adaptive light distributions and visible-contribution feedback. */
final class RtNeeAtBackend {
    static final int TILE_SIZE = 8;
    static final int LOCAL_SLOTS = 128;
    static final int CANDIDATES = 8;
    static final int HISTORY_VALID = 1;
    static final int ENVIRONMENT_EMITTERS_SAMPLED = 2;
    private static final int GLOBAL_ENTRY_BYTES = 2 * Float.BYTES;
    private static final int LOCAL_ENTRY_BYTES = 2 * Integer.BYTES;
    private static final int PLAN_ENTRY_BYTES = 3 * Integer.BYTES;
    private static final int PIXEL_FEEDBACK_BYTES = 2 * Integer.BYTES;
    private static final int PUSH_BYTES = 40;
    private static final String SHADER = "/caustica/shaders/pipelines/nee_at/bake.comp.spv";

    private final VulkanDeviceContext context;
    private final ShaderObjectCompute bake;
    private final Map<SceneId, SceneState> scenes = new IdentityHashMap<>();

    RtNeeAtBackend(VulkanDeviceContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.bake = load(context);
    }

    Prepared prepare(SceneId scene, List<RtRetainedSceneBackend.SceneLight> lights,
                     FrameInput input, VkCommandBuffer commandBuffer, GraphicsUse use) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(lights, "lights");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(use, "use");
        SceneState state = scenes.computeIfAbsent(scene, ignored -> new SceneState());
        if (state.active != null) throw new IllegalStateException("lighting frame is already active");
        state.ensure(input.width(), input.height(),
                Math.max(lights.size(), state.previousLights.size()));
        boolean continuous = input.historyContinuous() && state.hasHistory
                && input.frameIndex() == state.lastFrameIndex + 1
                && input.width() == state.width && input.height() == state.height;
        int targetIndex = state.cursor ^ 1;
        Frame target = state.frames[targetIndex];
        Frame previous = state.frames[state.cursor];
        context.gpuExecutor().graphicsUseWaiter().await(target.use);

        RtNeeAtPlan.Plan plan = RtNeeAtPlan.build(lights,
                continuous ? state.previousLights : List.of(), input.metersPerSceneUnit());
        write(target.plan, plan.pack());
        int tileCountX = divideRoundUp(input.width(), TILE_SIZE);
        int tileCountY = divideRoundUp(input.height(), TILE_SIZE);
        int flags = (continuous ? HISTORY_VALID : 0)
                | (lights.stream().anyMatch(light -> light.descriptor() instanceof LightDescriptor.Distant distant
                        && distant.environmentEmitter())
                ? ENVIRONMENT_EMITTERS_SAMPLED : 0);
        NeeAtStateData control = new NeeAtStateData(0L, target.global.deviceAddress(),
                target.local.deviceAddress(), target.lightFeedback.deviceAddress(),
                target.pixelFeedback.deviceAddress(), lights.size(),
                continuous ? state.previousLights.size() : 0,
                input.width(), input.height(), tileCountX, tileCountY, TILE_SIZE, LOCAL_SLOTS,
                CANDIDATES, flags, input.metersPerSceneUnit(),
                (int) input.frameIndex());
        writeState(target.state, control);

        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        dispatch(commandBuffer, target, previous, input, continuous, 0, 1);
        computeBarrier(commandBuffer);
        int localTiles = Math.multiplyExact(tileCountX, tileCountY);
        dispatch(commandBuffer, target, previous, input, continuous, 1, localTiles);
        computeBarrier(commandBuffer);
        int pixels = Math.multiplyExact(input.width(), input.height());
        dispatch(commandBuffer, target, previous, input, continuous, 2, divideRoundUp(pixels, 64));
        barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        target.use.mark(use);
        state.cursor = targetIndex;
        state.width = input.width();
        state.height = input.height();
        state.lastFrameIndex = input.frameIndex();
        state.previousLights = List.copyOf(lights);
        Prepared prepared = new Prepared(scene, target, control, continuous, use);
        state.active = prepared;
        return prepared;
    }

    void finish(SceneId scene, Prepared prepared, VkCommandBuffer commandBuffer, GraphicsUse use) {
        SceneState state = require(scene);
        if (state.active != prepared) throw new IllegalArgumentException("lighting frame is not active");
        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        prepared.frame.use.mark(use);
        state.hasHistory = true;
        state.active = null;
    }

    void abandon(SceneId scene, Prepared prepared) {
        SceneState state = require(scene);
        if (state.active != prepared) throw new IllegalArgumentException("lighting frame is not active");
        state.hasHistory = false;
        state.active = null;
    }

    Prepared active(SceneId scene) {
        return require(scene).active;
    }

    void destroyAfterDeviceIdle() {
        scenes.values().forEach(SceneState::destroy);
        scenes.clear();
        bake.close();
    }

    void retainScenes(Set<SceneId> retained) {
        var iterator = scenes.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (retained.contains(entry.getKey())) continue;
            SceneState state = entry.getValue();
            if (state.active != null) throw new IllegalStateException("cannot retire active lighting state");
            iterator.remove();
            state.retire();
        }
    }

    private SceneState require(SceneId scene) {
        SceneState state = scenes.get(scene);
        if (state == null) throw new IllegalArgumentException("scene has no NEE-AT state");
        return state;
    }

    private void dispatch(VkCommandBuffer commandBuffer, Frame target, Frame previous,
                          FrameInput input, boolean continuous, int phase, int groups) {
        ByteBuffer push = MemoryUtil.memAlloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
        try {
            push.putLong(target.state.deviceAddress()).putLong(target.plan.deviceAddress())
                    .putLong(previous.lightFeedback.deviceAddress())
                    .putLong(previous.pixelFeedback.deviceAddress())
                    .putInt(phase).putInt(continuous ? 1 : 0).flip();
            bake.dispatch(commandBuffer, push, groups, 1, 1);
        } finally {
            MemoryUtil.memFree(push);
        }
    }

    private static void writeState(GpuBuffer buffer, NeeAtStateData value) {
        ByteBuffer bytes = MemoryUtil.memByteBuffer(buffer.mapped(), NeeAtStateData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        value.write(bytes);
        buffer.flush(0, NeeAtStateData.BYTE_SIZE);
    }

    private static void write(GpuBuffer buffer, ByteBuffer source) {
        if (!source.hasRemaining()) return;
        MemoryUtil.memByteBuffer(buffer.mapped(), source.remaining()).put(source.duplicate());
        buffer.flush(0, source.remaining());
    }

    private static int divideRoundUp(int value, int divisor) {
        return Math.max(1, (value + divisor - 1) / divisor);
    }

    private static void computeBarrier(VkCommandBuffer commandBuffer) {
        barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    private static void barrier(VkCommandBuffer commandBuffer, long sourceStage, long sourceAccess,
                                long destinationStage, long destinationAccess) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            VkMemoryBarrier2.Buffer memory = VkMemoryBarrier2.calloc(1, stack);
            memory.get(0).sType$Default().srcStageMask(sourceStage).srcAccessMask(sourceAccess)
                    .dstStageMask(destinationStage).dstAccessMask(destinationAccess);
            VkDependencyInfo dependency = VkDependencyInfo.calloc(stack).sType$Default()
                    .pMemoryBarriers(memory);
            VK13.vkCmdPipelineBarrier2(commandBuffer, dependency);
        }
    }

    private static ShaderObjectCompute load(VulkanDeviceContext context) {
        try (InputStream input = RtNeeAtBackend.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing NEE-AT shader " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return ShaderObjectCompute.create(context, spirv, "main");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record FrameInput(int width, int height, long frameIndex, float metersPerSceneUnit,
                      boolean historyContinuous) {
        FrameInput {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("extent must be positive");
            if (!(metersPerSceneUnit > 0.0f) || !Float.isFinite(metersPerSceneUnit)) {
                throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
            }
        }
    }

    static final class Prepared {
        private final SceneId scene;
        private final Frame frame;
        private NeeAtStateData control;
        private final boolean historyValid;
        private final GraphicsUse use;

        Prepared(SceneId scene, Frame frame, NeeAtStateData control,
                 boolean historyValid, GraphicsUse use) {
            this.scene = scene;
            this.frame = frame;
            this.control = control;
            this.historyValid = historyValid;
            this.use = use;
        }

        void bindLightTable(long address) {
            control = new NeeAtStateData(address, control.globalDistributionAddress(),
                    control.localDistributionAddress(), control.lightFeedbackAddress(),
                    control.pixelFeedbackAddress(), control.lightCount(), control.priorLightCount(),
                    control.extentWidth(), control.extentHeight(), control.tileCountX(), control.tileCountY(),
                    control.tileSize(), control.localSlotCount(), control.candidateCount(), control.flags(),
                    control.metersPerSceneUnit(), control.frameIndex());
            writeState(frame.state, control);
        }

        long stateAddress() { return frame.state.deviceAddress(); }
        boolean historyValid() { return historyValid; }
        SceneId scene() { return scene; }
    }

    private final class SceneState {
        final Frame[] frames = {new Frame(), new Frame()};
        int cursor;
        int width;
        int height;
        int lightCapacity;
        long lastFrameIndex = Long.MIN_VALUE;
        boolean hasHistory;
        List<RtRetainedSceneBackend.SceneLight> previousLights = List.of();
        Prepared active;

        void ensure(int wantedWidth, int wantedHeight, int lights) {
            if (frames[0].state != null && width == wantedWidth && height == wantedHeight
                    && lightCapacity >= lights) return;
            for (Frame frame : frames) {
                context.gpuExecutor().graphicsUseWaiter().await(frame.use);
                frame.destroy();
            }
            int capacity = Math.max(1, lights);
            int tiles = Math.multiplyExact(divideRoundUp(wantedWidth, TILE_SIZE),
                    divideRoundUp(wantedHeight, TILE_SIZE));
            for (Frame frame : frames) frame.allocate(capacity, wantedWidth, wantedHeight, tiles);
            width = wantedWidth;
            height = wantedHeight;
            lightCapacity = capacity;
            hasHistory = false;
            previousLights = List.of();
            cursor = 0;
        }

        void destroy() { for (Frame frame : frames) frame.destroy(); }

        void retire() {
            AtomicInteger pending = new AtomicInteger(frames.length);
            for (Frame frame : frames) {
                context.gpuExecutor().retireAfterGraphics(frame.use,
                        () -> { if (pending.decrementAndGet() == 0) destroy(); });
            }
        }
    }

    private final class Frame {
        GpuBuffer state;
        GpuBuffer global;
        GpuBuffer local;
        GpuBuffer lightFeedback;
        GpuBuffer pixelFeedback;
        GpuBuffer plan;
        final TrackedGraphicsUse use = new TrackedGraphicsUse();

        void allocate(int lightCapacity, int width, int height, int tiles) {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            state = context.createBuffer(NeeAtStateData.BYTE_SIZE, usage, true, "NEE-AT state");
            global = context.createBuffer(Math.multiplyExact(lightCapacity, GLOBAL_ENTRY_BYTES), usage,
                    false, "NEE-AT global distribution");
            local = context.createBuffer(Math.multiplyExact(Math.multiplyExact(tiles, LOCAL_SLOTS),
                    LOCAL_ENTRY_BYTES), usage, false, "NEE-AT local distributions");
            lightFeedback = context.createBuffer(Math.multiplyExact(lightCapacity, Integer.BYTES), usage,
                    false, "NEE-AT light feedback");
            pixelFeedback = context.createBuffer(Math.multiplyExact(Math.multiplyExact(width, height),
                    PIXEL_FEEDBACK_BYTES), usage, false, "NEE-AT pixel feedback");
            plan = context.createBuffer(Math.multiplyExact(lightCapacity, PLAN_ENTRY_BYTES), usage,
                    true, "NEE-AT identity plan");
        }

        void destroy() {
            if (state == null) return;
            state.destroy(); global.destroy(); local.destroy(); lightFeedback.destroy();
            pixelFeedback.destroy(); plan.destroy();
            state = global = local = lightFeedback = pixelFeedback = plan = null;
        }
    }
}
