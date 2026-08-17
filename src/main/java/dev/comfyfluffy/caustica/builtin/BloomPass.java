package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.builtin.gen.BloomPushData;
import dev.comfyfluffy.caustica.api.ResourceId;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Scene-referred bloom: a downsample/upsample mip pyramid recorded directly onto the frame's command
 * buffer. Owns its own pyramid images, sampler, descriptor sets, and pipeline outright, and touches the
 * engine only through the post chain: it reads {@link PassFrame#sceneColor()} and adds the finished
 * pyramid onto it in {@link PassFrame#sceneColorTarget()}, like any other post effect.
 *
 * <p>Lives under {@code dev.comfyfluffy.caustica.builtin} as a renderer-owned reference pass. It ships
 * through the public {@code CausticaRenderPass} registration API a third-party extension
 * would use, so it only reaches engine internals through public surface.
 */
public final class BloomPass implements CausticaRenderPass {
    public static final ResourceId ID = ResourceId.of("caustica", "bloom");
    private static final ShaderSource SHADERS = ShaderSource.classpath("/caustica/shaders/builtin", "bloom");
    // destination, source (sampled), exposure, incoming chain image. The last two are each read by only
    // some modes but bound on every dispatch, so one pipeline covers the pyramid and the composite.
    private static final List<ComputeDispatch.Binding> BINDINGS = List.of(
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED,
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.STORAGE);
    private static final int MAX_LEVELS = 8;
    private static final int MODE_PREFILTER = 0;
    private static final int MODE_DOWNSAMPLE = 1;
    private static final int MODE_UPSAMPLE = 2;
    private static final int MODE_COMPOSITE = 3;

    // The options this pass owns. Declared here rather than inline in BuiltinExtension so the token a
    // reader passes to OptionValues#get and the declaration BuiltinExtension registers are the same object:
    // one source of truth for each id, kind, range and default.
    public static final String GROUP = "bloom";
    public static final Option<Boolean> ENABLED = Option.bool("bloom.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Float> STRENGTH = Option.range("bloom.strength", 0.0f, 2.0f, 0.02f).inGroup(GROUP);
    // The declared maximum is the storage clamp, so it stays at half-float range for a hand-edited config;
    // the slider covers the few stops around scene-linear mid-grey where thresholding is actually set.
    public static final Option<Float> THRESHOLD_SCENE_LINEAR =
            Option.range("bloom.threshold-scene-linear", 0.0f, 65504.0f, 2.0f)
                    .inGroup(GROUP).sliderRange(0.0, 16.0);
    public static final Option<Float> SOFT_KNEE_FRACTION =
            Option.range("bloom.soft-knee-fraction", 0.0f, 1.0f, 0.25f).inGroup(GROUP);
    public static final Option<Float> RADIUS = Option.range("bloom.radius", 0.25f, 4.0f, 1.0f).inGroup(GROUP);
    // No integer/count Option.Kind exists yet; modeled as a float with a unit step and rounded where consumed.
    public static final Option<Float> LEVELS =
            Option.range("bloom.levels", 1.0f, 8.0f, 6.0f).inGroup(GROUP).step(1.0);
    public static final List<Option<?>> OPTIONS =
            List.of(ENABLED, STRENGTH, THRESHOLD_SCENE_LINEAR, SOFT_KNEE_FRACTION, RADIUS, LEVELS);

    private GpuDevice ctx;
    private long sampler;
    private ComputeDispatch dispatch;
    private GpuImage[] levels = new GpuImage[0];

    @Override
    public ResourceId id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.AFTER_RECONSTRUCTION;
    }

    @Override
    public void create(PassSetup setup) {
        ctx = setup.device();
        sampler = ComputeDispatch.createLinearClampSampler(ctx, ID + " sampler");
        try {
            PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(
                    PassShaderCompiler.defaultCacheRoot(), ID, SHADERS, "caustica_bloom", "main");
            PassShaderCompiler.validateBindings(ID, compiled.reflectionJson(), BINDINGS,
                    BloomPushData.BYTE_SIZE, "main", 8, 8, 1);
            dispatch = ComputeDispatch.create(ctx, ID.toString(), compiled.spirv(), "main",
                    BINDINGS, BloomPushData.BYTE_SIZE, MAX_LEVELS * 2, sampler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        allocate(setup.displayWidth(), setup.displayHeight());
    }

    @Override
    public void resize(PassSetup setup, int displayWidth, int displayHeight) {
        destroyLevels();
        allocate(displayWidth, displayHeight);
    }

    private void allocate(int displayWidth, int displayHeight) {
        int baseWidth = Math.max(1, displayWidth / 2);
        int baseHeight = Math.max(1, displayHeight / 2);
        // Sized from the display alone, ignoring the configured depth: allocating every level the geometry
        // allows costs about 1.5% of the base mip and lets record() vary the depth per frame, so editing it
        // applies on the next frame instead of waiting for a resize to reallocate the pyramid.
        int levelCount = levelCount(baseWidth, baseHeight, MAX_LEVELS, 8);
        levels = new GpuImage[levelCount];
        int width = baseWidth;
        int height = baseHeight;
        for (int level = 0; level < levelCount; level++) {
            levels[level] = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    ID + " level " + level + " " + width + "x" + height);
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
    }

    /** How many pyramid levels fit before hitting {@code maximum} or shrinking below {@code minimumDimension}. */
    static int levelCount(int width, int height, int maximum, int minimumDimension) {
        int levels = 1;
        while (levels < maximum && width > minimumDimension && height > minimumDimension) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            levels++;
        }
        return levels;
    }

    @Override
    public void record(PassFrame frame) {
        OptionValues options = frame.options();
        // Before sceneColorTarget(): taking a chain target and then writing nothing would hand the engine
        // an image holding whatever the last frame left in it.
        if (!options.get(ENABLED)) {
            return;
        }
        dispatch.beginFrame();
        GpuImage scene = frame.sceneColor();
        GpuImage target = frame.sceneColorTarget();
        GpuImage exposure = frame.exposureImage();
        float threshold = options.get(THRESHOLD_SCENE_LINEAR);
        float softKnee = threshold * options.get(SOFT_KNEE_FRACTION);
        float radius = options.get(RADIUS);
        // The pyramid is allocated as deep as the display allows; this frame uses only the configured
        // prefix of it.
        int activeLevels = Math.clamp(Math.round(options.get(LEVELS)), 1, levels.length);
        // Level 0 ends up holding the SUM of every band, so dividing by the depth is what makes an
        // authored strength mean the same thing at every resolution. The pyramid's depth is this pass's
        // own sizing decision, so that arithmetic is this pass's too, not the engine's.
        float compositeStrength = options.get(STRENGTH) / activeLevels;

        for (Step step : plan(activeLevels)) {
            GpuImage destination = levels[step.destinationLevel()];
            GpuImage source = step.sourceLevel() < 0 ? scene : levels[step.sourceLevel()];
            recordStep(frame, destination, source, exposure, scene, step.mode(), threshold, softKnee,
                    radius, 0.0f);
            frame.memoryBarrier();
        }
        // The chain link itself: everything above only built the pyramid off the incoming image.
        recordStep(frame, target, levels[0], exposure, scene, MODE_COMPOSITE, threshold, softKnee, radius,
                compositeStrength);
    }

    /**
     * One prefilter step reading the reconstructed colour (source level -1), then downsample bottom-up,
     * then upsample top-down. Pure level-index arithmetic so pyramid ordering is testable without a GPU.
     *
     * <p>The last step always writes level 0 whatever the depth, which is what lets the composite step
     * that follows read the finished pyramid from that one level.
     */
    static List<Step> plan(int levelCount) {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(MODE_PREFILTER, 0, -1));
        for (int level = 1; level < levelCount; level++) {
            steps.add(new Step(MODE_DOWNSAMPLE, level, level - 1));
        }
        for (int level = levelCount - 2; level >= 0; level--) {
            steps.add(new Step(MODE_UPSAMPLE, level, level + 1));
        }
        return steps;
    }

    record Step(int mode, int destinationLevel, int sourceLevel) {
    }

    private void recordStep(PassFrame frame, GpuImage destination, GpuImage source, GpuImage exposure,
                            GpuImage scene, int mode, float threshold, float softKnee, float radius,
                            float compositeStrength) {
        byte[] pushConstants = new byte[BloomPushData.BYTE_SIZE];
        new BloomPushData(mode, threshold, softKnee, radius, compositeStrength)
                .write(ByteBuffer.wrap(pushConstants).order(ByteOrder.nativeOrder()));
        int groupsX = groups(destination.width());
        int groupsY = groups(destination.height());
        dispatch.dispatch(frame.commandBuffer(), new GpuImage[]{destination, source, exposure, scene},
                pushConstants, groupsX, groupsY, 1);
    }

    static int groups(int extent) {
        return (extent + 7) / 8;
    }

    @Override
    public void destroy() {
        destroyLevels();
        if (dispatch != null) {
            dispatch.destroy();
            dispatch = null;
        }
        if (ctx != null && sampler != 0L) {
            VK10.vkDestroySampler(ctx.vk(), sampler, null);
            sampler = 0L;
        }
    }

    private void destroyLevels() {
        for (GpuImage image : levels) {
            image.destroy();
        }
        levels = new GpuImage[0];
    }
}
