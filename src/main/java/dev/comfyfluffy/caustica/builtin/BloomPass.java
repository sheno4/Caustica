package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassOptions;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.gen.BloomPushData;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Scene-referred bloom: a downsample/upsample mip pyramid recorded directly onto the frame's command
 * buffer. Owns its own pyramid images, sampler, descriptor sets, and pipeline outright — the engine only
 * sees the published {@code "bloom"} output (see {@link dev.comfyfluffy.caustica.api.pass.PassSetup#publishOutput}).
 *
 * <p>Lives under {@code dev.comfyfluffy.caustica.builtin} alongside {@link SkyLutPass}, for the same
 * reason: it ships through the public {@code CausticaRenderPass} registration API a third-party extension
 * would use, so it only reaches engine internals through public surface.
 */
public final class BloomPass implements CausticaRenderPass {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "bloom");
    private static final ShaderSource SHADERS = ShaderSource.classpath("/caustica/shaders/builtin", "bloom");
    private static final List<ComputeDispatch.Binding> BINDINGS = List.of(
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED, ComputeDispatch.Binding.STORAGE);
    private static final int MAX_LEVELS = 8;
    private static final int MODE_PREFILTER = 0;
    private static final int MODE_DOWNSAMPLE = 1;
    private static final int MODE_UPSAMPLE = 2;

    // The options this pass owns. Declared here rather than inline in BuiltinExtension so the token a
    // reader passes to PassOptions#get and the declaration BuiltinExtension registers are the same object:
    // one source of truth for each id, kind, range and default.
    public static final Option<Float> STRENGTH = Option.range("bloom.strength", 0.0f, 2.0f, 0.02f);
    public static final Option<Float> THRESHOLD_SCENE_LINEAR =
            Option.range("bloom.threshold-scene-linear", 0.0f, 65504.0f, 2.0f);
    public static final Option<Float> SOFT_KNEE_FRACTION =
            Option.range("bloom.soft-knee-fraction", 0.0f, 1.0f, 0.25f);
    public static final Option<Float> RADIUS = Option.range("bloom.radius", 0.25f, 4.0f, 1.0f);
    // No integer/count Option.Kind exists yet; modeled as a float and rounded where consumed.
    public static final Option<Float> LEVELS = Option.range("bloom.levels", 1.0f, 8.0f, 6.0f);
    public static final List<Option<?>> OPTIONS =
            List.of(STRENGTH, THRESHOLD_SCENE_LINEAR, SOFT_KNEE_FRACTION, RADIUS, LEVELS);

    private GpuContext ctx;
    private long sampler;
    private ComputeDispatch dispatch;
    private GpuImage[] levels = new GpuImage[0];

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.AFTER_RECONSTRUCTION;
    }

    @Override
    public void create(PassSetup setup) {
        ctx = setup.context();
        sampler = ComputeDispatch.createLinearClampSampler(ctx, ID + " sampler");
        try {
            PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(
                    PassShaderCompiler.defaultCacheRoot(), ID, SHADERS, "caustica_bloom", "main");
            PassShaderCompiler.validateBindings(ID, compiled.reflectionJson(), BINDINGS,
                    BloomPushData.BYTE_SIZE, "main", 8, 8, 1);
            dispatch = ComputeDispatch.create(ctx, ID.toString(), compiled.spirv(), "main",
                    BINDINGS, BloomPushData.BYTE_SIZE, MAX_LEVELS * 2 - 1, sampler);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        allocate(setup, setup.displayWidth(), setup.displayHeight());
        setup.publishOutput("bloom", levels[0], levels.length);
    }

    @Override
    public void resize(PassSetup setup, int displayWidth, int displayHeight) {
        destroyLevels();
        allocate(setup, displayWidth, displayHeight);
        setup.publishOutput("bloom", levels[0], levels.length);
    }

    private void allocate(PassSetup setup, int displayWidth, int displayHeight) {
        int baseWidth = Math.max(1, displayWidth / 2);
        int baseHeight = Math.max(1, displayHeight / 2);
        // Read at allocate time, not per frame: pyramid depth is a resource-sizing decision, not a
        // push-constant one, and PassSetup#options() is the seam for exactly that. Nothing invalidates a
        // pass on an option write, so a change here takes effect at the next resize.
        int configuredLevels = Math.round(setup.options().get(LEVELS));
        int levelCount = levelCount(baseWidth, baseHeight, Math.min(configuredLevels, MAX_LEVELS), 8);
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
        dispatch.beginFrame();
        GpuImage reconstructedColor = frame.reconstructedColor();
        GpuImage exposure = frame.exposureImage();
        PassOptions options = frame.options();
        float threshold = options.get(THRESHOLD_SCENE_LINEAR);
        float softKnee = threshold * options.get(SOFT_KNEE_FRACTION);
        float radius = options.get(RADIUS);
        // The weight the display-mapping pipeline composites this pyramid with. Divided by the level
        // count here rather than there: the pyramid's depth is this pass's own sizing decision, so how
        // strength relates to it is this pass's arithmetic, not the engine's.
        frame.publishScalar("bloom.strength", levels.length == 0
                ? 0.0f : options.get(STRENGTH) / levels.length);

        for (Step step : plan(levels.length)) {
            GpuImage destination = levels[step.destinationLevel()];
            GpuImage source = step.sourceLevel() < 0 ? reconstructedColor : levels[step.sourceLevel()];
            recordStep(frame, destination, source, exposure, step.mode(), threshold, softKnee, radius);
            frame.memoryBarrier();
        }
    }

    /**
     * One prefilter step reading the reconstructed colour (source level -1), then downsample bottom-up,
     * then upsample top-down. Pure level-index arithmetic so pyramid ordering is testable without a GPU.
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
                            int mode, float threshold, float softKnee, float radius) {
        byte[] pushConstants = new byte[BloomPushData.BYTE_SIZE];
        new BloomPushData(mode, threshold, softKnee, radius)
                .write(ByteBuffer.wrap(pushConstants).order(ByteOrder.nativeOrder()));
        int groupsX = groups(destination.width);
        int groupsY = groups(destination.height);
        dispatch.dispatch(frame.commandBuffer(), new GpuImage[]{destination, source, exposure},
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
