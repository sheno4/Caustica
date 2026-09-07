package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsQueue.TrackedGraphicsUse;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.NeeAtStateData;
import dev.comfyfluffy.caustica.renderer.raytracing.layout.RtBindings;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import jdk.jfr.*;

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
    private static final EventType NEE_FRAME_EVENT = EventType.getEventType(NeeFrameEvent.class);
    static final int TILE_SIZE = 8;
    static final int LOCAL_SLOTS = 128;
    static final int CANDIDATES = 8;
    /** Average sampling proxies per light; mirrors {@code NEE_AT_PROXY_RATIO} in world_common.slang. */
    static final int PROXY_RATIO = 12;
    /** Share of NEE candidates drawn from the screen-space tile distribution. */
    static final float LOCAL_TO_GLOBAL_RATIO = 0.65f;
    static final int SCAN_BLOCK = 64;
    static final int ENVIRONMENT_EMITTERS_SAMPLED = 2;
    static final int LOCAL_HISTORY_VALID = 1;
    private static final int GLOBAL_ENTRY_BYTES = 2 * Integer.BYTES;
    private static final int LOCAL_ENTRY_BYTES = 2 * Integer.BYTES;
    private static final int PLAN_ENTRY_BYTES = 2 * Integer.BYTES;
    private static final int PIXEL_FEEDBACK_BYTES = 2 * Integer.BYTES;
    private static final int PUSH_BYTES = 64;
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
        boolean continuous = historyValid(input, state.hasHistory, state.lastFrameIndex,
                state.width, state.height);
        if (state.metadataLights != lights) {
            state.metadataLights = lights;
            state.telemetry = null;
            state.environmentEmitters = lights.stream().anyMatch(light ->
                    light.descriptor() instanceof LightDescriptor.Distant distant && distant.environmentEmitter());
        }
        if (NEE_FRAME_EVENT.isEnabled()) {
            if (state.telemetry == null) state.telemetry = telemetry(lights, false);
            Telemetry telemetry = state.telemetry;
            NeeFrameEvent event = new NeeFrameEvent();
            event.rendererFrameId = input.frameIndex();
            event.scene = scene.toString();
            event.width = input.width();
            event.height = input.height();
            event.candidates = telemetry.candidates();
            event.historyValid = continuous;
            event.localHistoryValid = continuous;
            event.retainedLights = telemetry.lightCount();
            event.parallelograms = telemetry.parallelograms();
            event.spots = telemetry.spots();
            event.distants = telemetry.distants();
            event.commit();
        }
        int targetIndex = state.cursor ^ 1;
        Frame target = state.frames[targetIndex];
        Frame previous = state.frames[state.cursor];
        context.graphics().graphicsUseWaiter().await(target.use);

        RtNeeAtPlan.Plan plan = state.plans.prepare(lights, continuous, input.metersPerSceneUnit());
        if (target.uploadedPlan != plan) {
            write(target.plan, plan.pack());
            target.uploadedPlan = plan;
        }
        int tileCountX = divideRoundUp(input.width(), TILE_SIZE);
        int tileCountY = divideRoundUp(input.height(), TILE_SIZE);
        int flags = (continuous ? LOCAL_HISTORY_VALID : 0)
                | (state.environmentEmitters ? ENVIRONMENT_EMITTERS_SAMPLED : 0);
        NeeAtStateData control = new NeeAtStateData(0L, target.global.deviceAddress().value(),
                target.local.deviceAddress().value(), target.lightFeedback.deviceAddress().value(),
                target.pixelFeedback.deviceAddress().value(),
                target.proxyIndices.deviceAddress().value(), lights.size(),
                continuous ? state.previousLights.size() : 0,
                input.width(), input.height(), tileCountX, tileCountY, TILE_SIZE, LOCAL_SLOTS,
                CANDIDATES, flags, input.metersPerSceneUnit(),
                (int) input.frameIndex(), plan.powerTotal(),
                LOCAL_TO_GLOBAL_RATIO);
        writeState(target.state, control);

        // The previous frame's pixel feedback is read by the bake below, so the ray-tracing writes
        // that produced it must land before either the fills or the compute passes run.
        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT
                        | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
        // Both targets are this frame's write destinations, distinct from the previous frame's
        // buffers the bake reads, so they can be zeroed up front in one go.
        fill(commandBuffer, target.lightFeedback);
        fill(commandBuffer, target.pixelFeedback);
        barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_CLEAR_BIT, VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        int pixels = Math.multiplyExact(input.width(), input.height());
        int lightGroups = divideRoundUp(lights.size(), SCAN_BLOCK);
        if (continuous) {
            dispatch(commandBuffer, target, previous, input, true, 0, divideRoundUp(pixels, SCAN_BLOCK));
            computeBarrier(commandBuffer);
        }
        dispatch(commandBuffer, target, previous, input, continuous, 1, lightGroups);
        computeBarrier(commandBuffer);
        dispatch(commandBuffer, target, previous, input, continuous, 2, 1);
        computeBarrier(commandBuffer);
        dispatch(commandBuffer, target, previous, input, continuous, 3, lightGroups);
        computeBarrier(commandBuffer);
        dispatch(commandBuffer, target, previous, input, continuous, 4,
                divideRoundUp(Math.multiplyExact(lights.size(), PROXY_RATIO), SCAN_BLOCK));
        barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        target.use.mark(use);
        state.cursor = targetIndex;
        state.width = input.width();
        state.height = input.height();
        state.lastFrameIndex = input.frameIndex();
        state.previousLights = lights;
        Prepared prepared = new Prepared(scene, target, control, continuous, use);
        state.active = prepared;
        return prepared;
    }

    /**
     * Records the local distribution after BuildStablePlanes and before FillStablePlanes.
     * The host binds the descriptor heap and retains both storage images in GENERAL layout
     * through submission completion. Depth is linear view Z; motion is current-to-previous
     * displacement in pixels. Depth history shares the feedback frame's lifetime and continuity.
     */
    void bakeLocal(SceneId scene, VkCommandBuffer commandBuffer, int currentLinearDepthIndex,
                   int currentMotionIndex) {
        SceneState state = require(scene);
        Prepared prepared = state.active;
        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                        | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        dispatch(commandBuffer, prepared.frame, state.frames[state.cursor ^ 1],
                prepared.historyValid, 5,
                Math.multiplyExact(prepared.control.tileCountX(), prepared.control.tileCountY()),
                currentLinearDepthIndex, currentMotionIndex);
        // Fill consumes both Build's stable-plane records and the baked local distribution.
        barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
                        | KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
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
        dispatch(commandBuffer, target, previous, continuous, phase, groups, 0, 0);
    }

    private void dispatch(VkCommandBuffer commandBuffer, Frame target, Frame previous,
                          boolean continuous, int phase, int groups, int currentLinearDepthIndex,
                          int currentMotionIndex) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            push.putLong(target.state.deviceAddress().value()).putLong(target.plan.deviceAddress().value())
                    .putLong(previous.pixelFeedback.deviceAddress().value())
                    .putLong(target.blockSums.deviceAddress().value())
                    .putLong(previous.depth.deviceAddress().value())
                    .putLong(target.depth.deviceAddress().value())
                    .putInt(phase).putInt(continuous ? 1 : 0)
                    .putInt(currentLinearDepthIndex).putInt(currentMotionIndex).flip();
            bake.dispatch(commandBuffer, push, groups, 1, 1);
        }
    }

    private static void fill(VkCommandBuffer commandBuffer, GpuBuffer buffer) {
        VK10.vkCmdFillBuffer(commandBuffer, buffer.handle(), 0L, buffer.size(), 0);
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

    static int lightCapacity(int current, int required) {
        if (current >= required) return Math.max(1, current);
        return Math.max(required, Math.max(1, Math.addExact(current, Math.max(1, current / 2))));
    }

    static boolean historyValid(FrameInput input, boolean hasHistory, long lastFrameIndex,
                                int historyWidth, int historyHeight) {
        return input.historyContinuous() && hasHistory
                && input.frameIndex() == lastFrameIndex + 1
                && input.width() == historyWidth && input.height() == historyHeight;
    }

    static Telemetry telemetry(List<RtRetainedSceneBackend.SceneLight> lights, boolean historyValid) {
        int parallelograms = 0;
        int spots = 0;
        int distants = 0;
        for (RtRetainedSceneBackend.SceneLight light : lights) {
            switch (light.descriptor()) {
                case LightDescriptor.Parallelogram ignored -> parallelograms++;
                case LightDescriptor.Spot ignored -> spots++;
                case LightDescriptor.Distant ignored -> distants++;
            }
        }
        return new Telemetry(CANDIDATES, historyValid, parallelograms, spots, distants);
    }

    @Name("dev.comfyfluffy.caustica.NeeFrame")
    @Label("NEE frame inputs") @Category({"Caustica", "Frame"}) @StackTrace(false) @Enabled(false)
    static final class NeeFrameEvent extends Event {
        @Description("RtFrameRenderer host-frame counter; independent of telemetry frameId")
        public long rendererFrameId;
        public String scene;
        public int width;
        public int height;
        public int candidates;
        public boolean historyValid;
        public boolean localHistoryValid;
        public int retainedLights;
        public int parallelograms;
        public int spots;
        public int distants;
    }

    record Telemetry(int candidates, boolean historyValid, int parallelograms, int spots, int distants) {
        int lightCount() {
            return Math.addExact(Math.addExact(parallelograms, spots), distants);
        }

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

        void bindLightTable(VulkanDeviceAddress address) {
            control = new NeeAtStateData(address.value(), control.globalDistributionAddress(),
                    control.localDistributionAddress(), control.lightFeedbackAddress(),
                    control.pixelFeedbackAddress(), control.proxyIndexAddress(), control.lightCount(),
                    control.priorLightCount(),
                    control.extentWidth(), control.extentHeight(), control.tileCountX(), control.tileCountY(),
                    control.tileSize(), control.localSlotCount(), control.candidateCount(), control.flags(),
                    control.metersPerSceneUnit(), control.frameIndex(), control.powerTotal(),
                    control.localToGlobalRatio());
            writeState(frame.state, control);
        }

        VulkanDeviceAddress stateAddress() { return frame.state.deviceAddress(); }
        boolean historyValid() { return historyValid; }
        SceneId scene() { return scene; }
    }

    private final class SceneState {
        Frame[] frames = {new Frame(), new Frame()};
        int cursor;
        int width;
        int height;
        int lightCapacity;
        long lastFrameIndex = Long.MIN_VALUE;
        boolean hasHistory;
        final RtNeeAtPlan.Cache plans = new RtNeeAtPlan.Cache();
        List<RtRetainedSceneBackend.SceneLight> previousLights = List.of();
        List<RtRetainedSceneBackend.SceneLight> metadataLights;
        Telemetry telemetry;
        boolean environmentEmitters;
        Prepared active;

        void ensure(int wantedWidth, int wantedHeight, int lights) {
            if (frames[0].state != null && width == wantedWidth && height == wantedHeight
                    && lightCapacity >= lights) return;
            int capacity = lightCapacity(lightCapacity, lights);
            int tiles = Math.multiplyExact(divideRoundUp(wantedWidth, TILE_SIZE),
                    divideRoundUp(wantedHeight, TILE_SIZE));
            Frame[] replacement = {new Frame(), new Frame()};
            for (Frame frame : replacement) frame.allocate(capacity, wantedWidth, wantedHeight, tiles);
            retire();
            frames = replacement;
            width = wantedWidth;
            height = wantedHeight;
            lightCapacity = capacity;
            hasHistory = false;
            previousLights = List.of();
            cursor = 0;
        }

        void destroy() { for (Frame frame : frames) frame.destroy(); }

        void retire() {
            Frame[] retired = frames;
            // Each slot reads the other slot's feedback. Both completion values must retire before
            // either slot is destroyed, and callbacks must retain this generation after replacement.
            AtomicInteger pending = new AtomicInteger(retired.length);
            for (Frame frame : retired) {
                context.graphics().retireAfterGraphics(frame.use,
                        () -> {
                            if (pending.decrementAndGet() == 0) {
                                for (Frame completed : retired) completed.destroy();
                            }
                        });
            }
        }
    }

    private final class Frame {
        GpuBuffer state;
        GpuBuffer global;
        GpuBuffer proxyIndices;
        GpuBuffer blockSums;
        GpuBuffer local;
        GpuBuffer lightFeedback;
        GpuBuffer pixelFeedback;
        GpuBuffer depth;
        GpuBuffer plan;
        RtNeeAtPlan.Plan uploadedPlan;
        final TrackedGraphicsUse use = new TrackedGraphicsUse();

        void allocate(int lightCapacity, int width, int height, int tiles) {
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            int cleared = usage | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            state = context.createMappedGpuUploadBuffer(NeeAtStateData.BYTE_SIZE, usage, "NEE-AT state");
            // One entry past the last light carries the total proxy count.
            global = context.createBuffer(Math.multiplyExact(lightCapacity + 1, GLOBAL_ENTRY_BYTES),
                    usage, false, "NEE-AT global distribution");
            proxyIndices = context.createBuffer(
                    Math.multiplyExact(Math.multiplyExact(lightCapacity, PROXY_RATIO), Integer.BYTES),
                    usage, false, "NEE-AT sampling proxies");
            blockSums = context.createBuffer(
                    Math.multiplyExact(divideRoundUp(lightCapacity, SCAN_BLOCK), Integer.BYTES),
                    usage, false, "NEE-AT proxy block sums");
            local = context.createBuffer(Math.multiplyExact(Math.multiplyExact(tiles, LOCAL_SLOTS),
                    LOCAL_ENTRY_BYTES), usage, false, "NEE-AT local distributions");
            // One entry past the last light counts pixels whose feedback pick was unusable.
            lightFeedback = context.createBuffer(Math.multiplyExact(lightCapacity + 1, Integer.BYTES),
                    cleared, false, "NEE-AT light feedback");
            pixelFeedback = context.createBuffer(Math.multiplyExact(Math.multiplyExact(width, height),
                    PIXEL_FEEDBACK_BYTES), cleared, false, "NEE-AT pixel feedback");
            depth = context.createBuffer(Math.multiplyExact(Math.multiplyExact(width, height), Float.BYTES),
                    usage, false, "NEE-AT linear depth history");
            plan = context.createMappedGpuUploadBuffer(Math.multiplyExact(lightCapacity, PLAN_ENTRY_BYTES), usage,
                    "NEE-AT identity plan");
        }

        void destroy() {
            if (state == null) return;
            state.destroy(); global.destroy(); proxyIndices.destroy(); blockSums.destroy();
            local.destroy(); lightFeedback.destroy(); pixelFeedback.destroy(); depth.destroy(); plan.destroy();
            uploadedPlan = null;
            state = global = proxyIndices = blockSums = local = lightFeedback = pixelFeedback
                    = depth = plan = null;
        }
    }
}
