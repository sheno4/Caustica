package dev.comfyfluffy.caustica.renderer.raytracing.scene;

import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.light.LightDescriptor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.support.SharedResource;
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

/** Per-view adaptive light distributions and completion-owned visible-contribution feedback. */
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
    private static final int PIXEL_FEEDBACK_BYTES = 2 * Integer.BYTES;
    private static final int PUSH_BYTES = 88;
    private static final String SHADER = "/caustica/shaders/pipelines/nee_at/bake.comp.spv";

    private final VulkanDeviceContext context;
    private final ShaderObjectCompute bake;
    private final Map<Object, SceneState> views = new IdentityHashMap<>();
    private final RtNeeAtPlan.Cache lightPlans = new RtNeeAtPlan.Cache();
    private RtNeeAtPlan.Plan cachedPlan;
    private SharedResource<LightRevision> cachedLightRevision;

    RtNeeAtBackend(VulkanDeviceContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.bake = load(context);
    }

    /** Runs on the serial scene worker; metadata contains no submitted-predecessor state. */
    SharedResource<LightRevision> prepareLightRevision(List<RtRetainedSceneBackend.SceneLight> lights,
                                                      float metersPerSceneUnit) {
        RtNeeAtPlan.Plan plan = lightPlans.prepare(lights, metersPerSceneUnit);
        if (cachedPlan == plan) return cachedLightRevision.retain();
        RtRevisionResources resources = new RtRevisionResources();
        SharedResource<LightRevision> replacement;
        try {
            GpuBuffer power = upload(plan.packPower(), "NEE-AT light powers", resources);
            GpuBuffer identities = upload(plan.packIdentities(), "NEE-AT dense identities", resources);
            GpuBuffer lookup = upload(plan.packLookup(), "NEE-AT identity lookup", resources);
            LightRevision revision = new LightRevision(power, identities, lookup, plan.count(), plan.mask(),
                    plan.powerTotal(), lights.stream().anyMatch(light ->
                    light.descriptor() instanceof LightDescriptor.Distant distant && distant.environmentEmitter()),
                    telemetry(lights));
            replacement = SharedResource.owned(revision, released -> context.deferDestroy(resources::close));
        } catch (RuntimeException | Error failure) {
            try (resources) { throw failure; }
        }
        var old = cachedLightRevision;
        cachedLightRevision = replacement;
        cachedPlan = plan;
        if (old != null) old.close();
        return replacement.retain();
    }

    private GpuBuffer upload(ByteBuffer bytes, String label, RtRevisionResources resources) {
        GpuBuffer buffer = context.createMappedGpuUploadBuffer(Math.max(4, bytes.remaining()),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, label);
        resources.add(buffer::destroy);
        write(buffer, bytes);
        return buffer;
    }

    Prepared prepare(Object view, SceneId scene, SharedResource<LightRevision> revision,
                     FrameInput input, VkCommandBuffer commandBuffer, GraphicsUse use) {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(commandBuffer, "commandBuffer");
        Objects.requireNonNull(use, "use");
        SceneState state = views.get(view);
        if (state == null || state.scene != scene) {
            if (state != null) state.retire();
            state = new SceneState(scene);
            views.put(view, state);
        }
        if (state.active != null) throw new IllegalStateException("lighting frame is already active");
        LightRevision lights = revision.get();
        SharedResource<Frame> targetOwner = state.slots.acquire(
                frame -> frame.width == input.width() && frame.height == input.height()
                        && frame.lightCapacity >= lights.count,
                () -> new Frame(Math.max(1, lights.count), input.width(), input.height(),
                        Math.multiplyExact(divideRoundUp(input.width(), TILE_SIZE),
                                divideRoundUp(input.height(), TILE_SIZE))));
        SharedResource<Frame> previousOwner = state.history.capture();
        Frame previous = previousOwner == null ? null : previousOwner.get();
        boolean continuous = previous != null && historyValid(input, true, previous.input.frameIndex(),
                previous.width, previous.height);
        if (NEE_FRAME_EVENT.isEnabled()) {
            Telemetry telemetry = lights.telemetry;
            NeeFrameEvent event = new NeeFrameEvent();
            event.rendererFrameId = input.frameIndex();
            event.scene = scene.toString();
            event.width = input.width();
            event.height = input.height();
            event.candidates = CANDIDATES;
            event.historyValid = continuous;
            event.localHistoryValid = continuous;
            event.retainedLights = telemetry.lightCount();
            event.parallelograms = telemetry.parallelograms();
            event.spots = telemetry.spots();
            event.distants = telemetry.distants();
            event.commit();
        }
        Frame target = targetOwner.get();
        target.lights = revision.retain();
        target.input = input;
        int tileCountX = divideRoundUp(input.width(), TILE_SIZE);
        int tileCountY = divideRoundUp(input.height(), TILE_SIZE);
        int flags = (continuous ? LOCAL_HISTORY_VALID : 0)
                | (lights.environmentEmitters ? ENVIRONMENT_EMITTERS_SAMPLED : 0);
        NeeAtStateData control = new NeeAtStateData(0L, target.global.deviceAddress().value(),
                target.local.deviceAddress().value(), target.lightFeedback.deviceAddress().value(),
                target.pixelFeedback.deviceAddress().value(),
                target.proxyIndices.deviceAddress().value(), lights.count,
                continuous ? previous.lights.get().count : 0,
                input.width(), input.height(), tileCountX, tileCountY, TILE_SIZE, LOCAL_SLOTS,
                CANDIDATES, flags, input.metersPerSceneUnit(),
                (int) input.frameIndex(), lights.powerTotal,
                LOCAL_TO_GLOBAL_RATIO);
        Prepared prepared = new Prepared(state, targetOwner, previousOwner, control, continuous);
        use.keepAlive(prepared);
        state.active = prepared;

        try {
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
            int lightGroups = divideRoundUp(lights.count, SCAN_BLOCK);
            if (continuous) {
                dispatch(commandBuffer, target, previous, true, 0, divideRoundUp(pixels, SCAN_BLOCK));
                computeBarrier(commandBuffer);
            }
            dispatch(commandBuffer, target, previous, continuous, 1, lightGroups);
            computeBarrier(commandBuffer);
            dispatch(commandBuffer, target, previous, continuous, 2, 1);
            computeBarrier(commandBuffer);
            dispatch(commandBuffer, target, previous, continuous, 3, lightGroups);
            computeBarrier(commandBuffer);
            dispatch(commandBuffer, target, previous, continuous, 4,
                    divideRoundUp(Math.multiplyExact(lights.count, PROXY_RATIO), SCAN_BLOCK));
            barrier(commandBuffer, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

            return prepared;
        } catch (RuntimeException | Error failure) {
            state.active = null;
            throw failure;
        }
    }

    /**
     * Records the local distribution after BuildStablePlanes and before FillStablePlanes.
     * The host binds the descriptor heap and retains both storage images in GENERAL layout
     * through submission completion. Depth is linear view Z; motion is current-to-previous
     * displacement in pixels. Depth history shares the feedback frame's lifetime and continuity.
     */
    void bakeLocal(Prepared prepared, VkCommandBuffer commandBuffer, int currentLinearDepthIndex,
                   int currentMotionIndex) {
        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR
                        | VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT | VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        dispatch(commandBuffer, prepared.frame, prepared.previous,
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

    void finish(Prepared prepared, VkCommandBuffer commandBuffer, GraphicsUse use) {
        SceneState state = prepared.owner;
        if (state.active != prepared) throw new IllegalArgumentException("lighting frame is not active");
        barrier(commandBuffer, KHRSynchronization2.VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                VK13.VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
        use.whenSubmitted(() -> state.history.submitted(prepared.targetOwner));
        state.active = null;
    }

    void abandon(Prepared prepared) {
        SceneState state = prepared.owner;
        if (state.active != prepared) throw new IllegalArgumentException("lighting frame is not active");
        state.active = null;
    }

    void releaseView(Object view) {
        SceneState state = views.remove(view);
        if (state != null) state.retire();
    }

    void destroyAfterDeviceIdle() {
        views.values().forEach(SceneState::retire);
        views.clear();
        if (cachedLightRevision != null) cachedLightRevision.close();
        cachedLightRevision = null;
        cachedPlan = null;
        bake.close();
    }

    void retainScenes(Set<SceneId> retained) {
        var iterator = views.values().iterator();
        while (iterator.hasNext()) {
            SceneState state = iterator.next();
            if (retained.contains(state.scene)) continue;
            iterator.remove();
            state.retire();
        }
    }

    private void dispatch(VkCommandBuffer commandBuffer, Frame target, Frame previous,
                          boolean continuous, int phase, int groups) {
        dispatch(commandBuffer, target, previous, continuous, phase, groups, 0, 0);
    }

    private void dispatch(VkCommandBuffer commandBuffer, Frame target, Frame previous,
                          boolean continuous, int phase, int groups, int currentLinearDepthIndex,
                          int currentMotionIndex) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            LightRevision lights = target.lights.get();
            push.putLong(target.state.deviceAddress().value()).putLong(lights.power.deviceAddress().value())
                    .putLong(continuous ? previous.pixelFeedback.deviceAddress().value() : 0)
                    .putLong(target.blockSums.deviceAddress().value())
                    .putLong(continuous ? previous.depth.deviceAddress().value() : 0)
                    .putLong(target.depth.deviceAddress().value())
                    .putInt(phase).putInt(continuous ? 1 : 0)
                    .putInt(currentLinearDepthIndex).putInt(currentMotionIndex)
                    .putLong(continuous ? previous.lights.get().identities.deviceAddress().value() : 0)
                    .putLong(lights.lookup.deviceAddress().value()).putInt(lights.mask).putInt(0).flip();
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

    static boolean historyValid(FrameInput input, boolean hasHistory, long lastFrameIndex,
                                int historyWidth, int historyHeight) {
        return input.historyContinuous() && hasHistory
                && input.frameIndex() == lastFrameIndex + 1
                && input.width() == historyWidth && input.height() == historyHeight;
    }

    static Telemetry telemetry(List<RtRetainedSceneBackend.SceneLight> lights) {
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
        return new Telemetry(parallelograms, spots, distants);
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

    record Telemetry(int parallelograms, int spots, int distants) {
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

    static final class LightRevision {
        final GpuBuffer power;
        final GpuBuffer identities;
        final GpuBuffer lookup;
        final int count;
        final int mask;
        final float powerTotal;
        final boolean environmentEmitters;
        final Telemetry telemetry;

        LightRevision(GpuBuffer power, GpuBuffer identities, GpuBuffer lookup, int count, int mask,
                      float powerTotal, boolean environmentEmitters, Telemetry telemetry) {
            this.power = power;
            this.identities = identities;
            this.lookup = lookup;
            this.count = count;
            this.mask = mask;
            this.powerTotal = powerTotal;
            this.environmentEmitters = environmentEmitters;
            this.telemetry = telemetry;
        }

    }

    static final class Prepared implements AutoCloseable {
        private final SceneState owner;
        private final Frame frame;
        private final Frame previous;
        private final SharedResource<Frame> targetOwner;
        private final SharedResource<Frame> previousOwner;
        private NeeAtStateData control;
        private final boolean historyValid;

        Prepared(SceneState owner, SharedResource<Frame> targetOwner, SharedResource<Frame> previousOwner,
                 NeeAtStateData control,
                 boolean historyValid) {
            this.owner = owner;
            this.targetOwner = targetOwner;
            this.previousOwner = previousOwner;
            this.frame = targetOwner.get();
            this.previous = previousOwner == null ? null : previousOwner.get();
            this.control = control;
            this.historyValid = historyValid;
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
        SceneId scene() { return owner.scene; }

        @Override public void close() {
            try (previousOwner) { targetOwner.close(); }
        }
    }

    private final class SceneState {
        final SceneId scene;
        final RtFeedbackSlots<Frame> slots = new RtFeedbackSlots<>(Frame::releaseMetadata,
                frame -> context.deferDestroy(frame::destroy));
        final RtFeedbackHistory<Frame> history = new RtFeedbackHistory<>();
        Prepared active;

        SceneState(SceneId scene) { this.scene = scene; }

        void retire() {
            try (history) { slots.close(); }
        }
    }

    private final class Frame {
        final RtRevisionResources resources = new RtRevisionResources();
        final GpuBuffer state;
        final GpuBuffer global;
        final GpuBuffer proxyIndices;
        final GpuBuffer blockSums;
        final GpuBuffer local;
        final GpuBuffer lightFeedback;
        final GpuBuffer pixelFeedback;
        final GpuBuffer depth;
        SharedResource<LightRevision> lights;
        FrameInput input;
        final int lightCapacity;
        final int width;
        final int height;

        Frame(int lightCapacity, int width, int height, int tiles) {
            this.lightCapacity = lightCapacity;
            this.width = width;
            this.height = height;
            int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            int cleared = usage | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            try {
                state = context.createMappedGpuUploadBuffer(NeeAtStateData.BYTE_SIZE, usage, "NEE-AT state");
                resources.add(state::destroy);
                // One entry past the last light carries the total proxy count.
                global = context.createBuffer(Math.multiplyExact(lightCapacity + 1, GLOBAL_ENTRY_BYTES),
                        usage, false, "NEE-AT global distribution");
                resources.add(global::destroy);
                proxyIndices = context.createBuffer(
                        Math.multiplyExact(Math.multiplyExact(lightCapacity, PROXY_RATIO), Integer.BYTES),
                        usage, false, "NEE-AT sampling proxies");
                resources.add(proxyIndices::destroy);
                blockSums = context.createBuffer(
                        Math.multiplyExact(divideRoundUp(lightCapacity, SCAN_BLOCK), Integer.BYTES),
                        usage, false, "NEE-AT proxy block sums");
                resources.add(blockSums::destroy);
                local = context.createBuffer(Math.multiplyExact(Math.multiplyExact(tiles, LOCAL_SLOTS),
                        LOCAL_ENTRY_BYTES), usage, false, "NEE-AT local distributions");
                resources.add(local::destroy);
                // One entry past the last light counts pixels whose feedback pick was unusable.
                lightFeedback = context.createBuffer(Math.multiplyExact(lightCapacity + 1, Integer.BYTES),
                        cleared, false, "NEE-AT light feedback");
                resources.add(lightFeedback::destroy);
                pixelFeedback = context.createBuffer(Math.multiplyExact(Math.multiplyExact(width, height),
                        PIXEL_FEEDBACK_BYTES), cleared, false, "NEE-AT pixel feedback");
                resources.add(pixelFeedback::destroy);
                depth = context.createBuffer(Math.multiplyExact(Math.multiplyExact(width, height), Float.BYTES),
                        usage, false, "NEE-AT linear depth history");
                resources.add(depth::destroy);
            } catch (RuntimeException | Error failure) {
                try (resources) { throw failure; }
            }
        }

        void releaseMetadata() {
            var released = lights;
            lights = null;
            input = null;
            if (released != null) released.close();
        }

        void destroy() { resources.close(); }
    }
}
