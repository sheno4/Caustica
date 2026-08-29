package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.gpu.GpuAccelerationStructureDescriptor;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuFrameUse;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.pass.UiFrame;
import dev.comfyfluffy.caustica.api.pass.UiSetup;
import dev.comfyfluffy.caustica.api.pass.WorldResourceSetup;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.engine.pass.PassKey;
import dev.comfyfluffy.caustica.engine.pass.PassSchedulerBackend;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.VulkanBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.Objects;

/** Vulkan-native pass scheduler over the command buffer and completion reservation of one renderer frame. */
public final class RtPassSchedulerBackend implements PassSchedulerBackend {
    private final GpuDevice gpu;
    private final WorldResourceSetup worldSetup;
    private final PostEffectSetup postSetup;
    private final UiSetup uiSetup;
    private final CommandHooks commands;
    private FrameState current;
    private InvocationBase<?> active;
    private GpuImage sceneColor;
    private int nextPostTarget;
    private long chainVersion;

    public RtPassSchedulerBackend(GpuContext gpu, int sceneColorFormat, int exposureFormat, int uiLayerFormat) {
        this(gpu, sceneColorFormat, exposureFormat, uiLayerFormat, new NativeCommandHooks(gpu));
    }

    RtPassSchedulerBackend(GpuDevice gpu, int sceneColorFormat, int exposureFormat, int uiLayerFormat,
                           CommandHooks commands) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        worldSetup = new WorldResourceSetup(gpu);
        postSetup = new PostEffectSetup(gpu, sceneColorFormat, exposureFormat);
        uiSetup = new UiSetup(gpu, uiLayerFormat);
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    /**
     * Installs the renderer-owned state borrowed by all pass callbacks for one frame. The command buffer
     * must already be recording, and every image must already be in the unified GENERAL layout.
     */
    public void beginFrame(FrameState frame) {
        if (active != null) throw new IllegalStateException("a pass invocation is still active");
        if (current != null) throw new IllegalStateException("the previous pass frame was not ended");
        current = Objects.requireNonNull(frame, "frame");
        sceneColor = frame.reconstructedSceneColor();
        nextPostTarget = 0;
        chainVersion = 0L;
    }

    /** Ends the current borrow after every pass stage has recorded into its command buffer. */
    public void endFrame() {
        requireFrame();
        if (active != null) throw new IllegalStateException("a pass invocation is still active");
        current = null;
        sceneColor = null;
        nextPostTarget = 0;
        chainVersion = 0L;
    }

    /** Last successfully published post-effect output, or the reconstruction if no effect participated. */
    public GpuImage sceneColor() {
        requireFrame();
        return sceneColor;
    }

    @Override public WorldResourceSetup worldResourceSetup() { return worldSetup; }
    @Override public PostEffectSetup postEffectSetup() { return postSetup; }
    @Override public UiSetup uiSetup() { return uiSetup; }

    @Override
    public Invocation<PassFrame> beginWorldResource(PassKey pass) {
        requireStage(pass, PassKey.Stage.WORLD_RESOURCE);
        FrameState frame = beginInvocation();
        return activate(new PlainInvocation(frame));
    }

    @Override
    public PostInvocation beginPostEffect(PassKey pass) {
        requireStage(pass, PassKey.Stage.POST_EFFECT);
        FrameState frame = beginInvocation();
        return activate(new Post(frame, sceneColor, chainVersion, nextPostTarget));
    }

    @Override
    public Invocation<UiFrame> beginUi(PassKey pass) {
        requireStage(pass, PassKey.Stage.UI);
        FrameState frame = beginInvocation();
        if (frame.ui() == null) throw new IllegalStateException("UI frame state is not available");
        return activate(new Ui(frame));
    }

    private FrameState beginInvocation() {
        FrameState frame = requireFrame();
        if (active != null) throw new IllegalStateException("pass invocations must be completed in order");
        commands.bindDescriptorHeaps(frame.commandBuffer());
        return frame;
    }

    private <I extends InvocationBase<?>> I activate(I invocation) {
        active = invocation;
        return invocation;
    }

    private FrameState requireFrame() {
        if (current == null) throw new IllegalStateException("beginFrame must be called before recording passes");
        return current;
    }

    private static void requireStage(PassKey pass, PassKey.Stage expected) {
        Objects.requireNonNull(pass, "pass");
        if (pass.stage() != expected) throw new IllegalArgumentException("pass stage is " + pass.stage());
    }

    public record FrameState(
            VkCommandBuffer commandBuffer,
            GpuFrameUse gpuUse,
            long frameIndex,
            SceneView view,
            double timeSeconds,
            double metersPerSceneUnit,
            int renderWidth,
            int renderHeight,
            GpuImage reconstructedSceneColor,
            GpuImage exposureImage,
            GpuImage postColorA,
            GpuImage postColorB,
            UiState ui) {
        public FrameState {
            Objects.requireNonNull(commandBuffer, "commandBuffer");
            Objects.requireNonNull(gpuUse, "gpuUse");
            if (frameIndex < 0L) throw new IllegalArgumentException("frameIndex must be non-negative");
            Objects.requireNonNull(view, "view");
            if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("timeSeconds must be finite");
            if (!(metersPerSceneUnit > 0.0) || !Double.isFinite(metersPerSceneUnit)) {
                throw new IllegalArgumentException("metersPerSceneUnit must be finite and positive");
            }
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalArgumentException("render extent must be positive");
            }
            Objects.requireNonNull(reconstructedSceneColor, "reconstructedSceneColor");
            Objects.requireNonNull(exposureImage, "exposureImage");
            Objects.requireNonNull(postColorA, "postColorA");
            Objects.requireNonNull(postColorB, "postColorB");
            if (postColorA == postColorB) throw new IllegalArgumentException("post targets must be distinct");
        }
    }

    public record UiState(
            GpuImage layer,
            float[] worldViewProjection,
            GpuAccelerationStructureDescriptor rootSceneTlasDescriptor) {
        public UiState {
            Objects.requireNonNull(layer, "layer");
            Objects.requireNonNull(rootSceneTlasDescriptor, "rootSceneTlasDescriptor");
            if (worldViewProjection == null || worldViewProjection.length != 16) {
                throw new IllegalArgumentException("worldViewProjection must contain 16 values");
            }
            worldViewProjection = worldViewProjection.clone();
        }

        @Override public float[] worldViewProjection() { return worldViewProjection.clone(); }
    }

    interface CommandHooks {
        void bindDescriptorHeaps(VkCommandBuffer commandBuffer);
        void passBarrier(VkCommandBuffer commandBuffer);
    }

    private static final class NativeCommandHooks implements CommandHooks {
        private final GpuContext gpu;

        private NativeCommandHooks(GpuContext gpu) {
            this.gpu = Objects.requireNonNull(gpu, "gpu");
        }

        @Override public void bindDescriptorHeaps(VkCommandBuffer commandBuffer) {
            gpu.bindDescriptorHeaps(commandBuffer);
        }

        @Override public void passBarrier(VkCommandBuffer commandBuffer) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanBarriers.memoryBarrier(commandBuffer, stack);
            }
        }
    }

    private abstract class InvocationBase<F extends PassFrame> implements Invocation<F> {
        final FrameState state;
        F frame;
        private boolean finished;

        InvocationBase(FrameState state, F frame) {
            this.state = state;
            this.frame = frame;
        }

        @Override public F frame() {
            requireLive();
            return frame;
        }

        @Override public void submit(Runnable drained) {
            finish(drained);
        }

        @Override public void abandon(Throwable failure, Runnable drained) {
            Objects.requireNonNull(failure, "failure");
            finish(drained);
        }

        final void requireLive() {
            if (finished || active != this) throw new IllegalStateException("pass invocation is no longer active");
        }

        private void finish(Runnable drained) {
            requireLive();
            Objects.requireNonNull(drained, "drained");
            commands.passBarrier(state.commandBuffer());
            finished = true;
            active = null;
            state.gpuUse().retire(drained);
        }
    }

    private abstract class BorrowedFrame implements PassFrame {
        final InvocationBase<?> owner;
        final FrameState state;

        BorrowedFrame(InvocationBase<?> owner, FrameState state) {
            this.owner = owner;
            this.state = state;
        }

        final void requireLive() { owner.requireLive(); }
        @Override public VkCommandBuffer commandBuffer() { requireLive(); return state.commandBuffer(); }
        @Override public GpuFrameUse gpuUse() { requireLive(); return state.gpuUse(); }
        @Override public long frameIndex() { requireLive(); return state.frameIndex(); }
        @Override public SceneView view() { requireLive(); return state.view(); }
        @Override public double timeSeconds() { requireLive(); return state.timeSeconds(); }
        @Override public double metersPerSceneUnit() { requireLive(); return state.metersPerSceneUnit(); }
        @Override public int renderWidth() { requireLive(); return state.renderWidth(); }
        @Override public int renderHeight() { requireLive(); return state.renderHeight(); }
    }

    private final class PlainInvocation extends InvocationBase<PassFrame> {
        PlainInvocation(FrameState state) {
            super(state, null);
            this.frame = new BorrowedFrame(this, state) { };
        }
    }

    private final class Post extends InvocationBase<PostEffectFrame> implements PostInvocation {
        private final GpuImage input;
        private final long inputVersion;
        private final int targetIndex;
        private GpuImage output;
        private boolean validated;

        Post(FrameState state, GpuImage input, long inputVersion, int targetIndex) {
            super(state, null);
            this.input = input;
            this.inputVersion = inputVersion;
            this.targetIndex = targetIndex;
            this.frame = new PostFrame(this, state);
        }

        @Override public void validateOutputChain() {
            requireLive();
            if (validated) throw new IllegalStateException("post output chain was already validated");
            if (chainVersion != inputVersion || sceneColor != input) {
                throw new IllegalStateException("post effect acquired against a stale scene-colour chain");
            }
            if (output != null) {
                sceneColor = output;
                nextPostTarget = targetIndex ^ 1;
                chainVersion++;
            }
            validated = true;
        }

        @Override public void submit(Runnable drained) {
            if (!validated) throw new IllegalStateException("post output chain must be validated before submit");
            super.submit(drained);
        }

        @Override public void abandon(Throwable failure, Runnable drained) {
            requireLive();
            if (validated && output != null) {
                sceneColor = input;
                nextPostTarget = targetIndex;
                chainVersion = inputVersion;
            }
            super.abandon(failure, drained);
        }

        private final class PostFrame extends BorrowedFrame implements PostEffectFrame {
            PostFrame(InvocationBase<?> owner, FrameState state) { super(owner, state); }

            @Override public GpuImage sceneColor() { requireLive(); return input; }

            @Override public GpuImage acquireSceneColorOutput() {
                requireLive();
                if (output == null) {
                    GpuImage first = targetIndex == 0 ? state.postColorA() : state.postColorB();
                    GpuImage second = targetIndex == 0 ? state.postColorB() : state.postColorA();
                    output = first != input ? first : second;
                    if (output == input) throw new IllegalStateException("post output aliases its input");
                }
                return output;
            }

            @Override public GpuImage exposureImage() { requireLive(); return state.exposureImage(); }
        }
    }

    private final class Ui extends InvocationBase<UiFrame> {
        Ui(FrameState state) {
            super(state, null);
            this.frame = new UiBorrow(this, state);
        }

        private final class UiBorrow extends BorrowedFrame implements UiFrame {
            UiBorrow(InvocationBase<?> owner, FrameState state) { super(owner, state); }
            @Override public GpuImage layer() { requireLive(); return state.ui().layer(); }
            @Override public float[] worldViewProjection() { requireLive(); return state.ui().worldViewProjection(); }
            @Override public GpuAccelerationStructureDescriptor rootSceneTlasDescriptor() {
                requireLive();
                return state.ui().rootSceneTlasDescriptor();
            }
        }
    }
}
