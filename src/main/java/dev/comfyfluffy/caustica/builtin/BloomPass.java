package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.gpu.GpuDevice;
import dev.comfyfluffy.caustica.api.gpu.GpuImage;
import dev.comfyfluffy.caustica.api.gpu.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.builtin.gen.BloomPushData;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.settings.ResourceId;
import dev.comfyfluffy.caustica.vulkan.ComputeSynchronization;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import dev.comfyfluffy.caustica.vulkan.VulkanSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Scene-referred Bloom implemented as a descriptor-heap-native shader-object compute pass. */
public final class BloomPass implements Pass<PostEffectFrame> {
    public static final ResourceId ID = ResourceId.of("caustica", "bloom");
    private static final String SHADER = "/caustica/shaders/pipelines/bloom/main.comp.spv";
    private static final int MAX_LEVELS = 8;
    private static final int MODE_PREFILTER = 0;
    private static final int MODE_DOWNSAMPLE = 1;
    private static final int MODE_UPSAMPLE = 2;
    private static final int MODE_COMPOSITE = 3;

    public static final String GROUP = "bloom";
    public static final Option<Boolean> ENABLED = Option.bool("bloom.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Float> STRENGTH =
            Option.range("bloom.strength", 0.0f, 2.0f, 0.02f).inGroup(GROUP);
    public static final Option<Float> THRESHOLD_SCENE_LINEAR =
            Option.range("bloom.threshold-scene-linear", 0.0f, 65504.0f, 2.0f)
                    .inGroup(GROUP).sliderRange(0.0, 16.0);
    public static final Option<Float> SOFT_KNEE_FRACTION =
            Option.range("bloom.soft-knee-fraction", 0.0f, 1.0f, 0.25f).inGroup(GROUP);
    public static final Option<Float> RADIUS =
            Option.range("bloom.radius", 0.25f, 4.0f, 1.0f).inGroup(GROUP);
    public static final Option<Float> LEVELS =
            Option.range("bloom.levels", 1.0f, 8.0f, 6.0f).inGroup(GROUP).step(1.0);
    public static final List<Option<?>> OPTIONS =
            List.of(ENABLED, STRENGTH, THRESHOLD_SCENE_LINEAR, SOFT_KNEE_FRACTION, RADIUS, LEVELS);

    private final GpuDevice gpu;
    private final Supplier<OptionValues> options;
    private final VulkanSampler sampler;
    private final ShaderObjectCompute shader;
    private VmaImage2D[] levels = new VmaImage2D[0];
    private int builtWidth;
    private int builtHeight;

    public BloomPass(PostEffectSetup setup, Supplier<OptionValues> options) {
        this.gpu = Objects.requireNonNull(setup, "setup").gpu();
        this.options = Objects.requireNonNull(options, "options");
        VulkanSampler createdSampler = VulkanSampler.linearClamp(gpu, ID + " sampler");
        try {
            this.shader = loadShader(gpu);
            this.sampler = createdSampler;
        } catch (RuntimeException | Error failure) {
            createdSampler.close();
            throw failure;
        }
    }

    private static ShaderObjectCompute loadShader(GpuDevice gpu) {
        try (InputStream input = BloomPass.class.getResourceAsStream(SHADER)) {
            if (input == null) throw new IllegalStateException("missing Bloom shader " + SHADER);
            byte[] bytes = input.readAllBytes();
            ByteBuffer spirv = MemoryUtil.memAlloc(bytes.length);
            try {
                spirv.put(bytes).flip();
                return ShaderObjectCompute.create(gpu, spirv, "main");
            } finally {
                MemoryUtil.memFree(spirv);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void record(PostEffectFrame frame) {
        OptionValues values = options.get();
        if (!values.get(ENABLED)) return;

        GpuImage scene = frame.sceneColor();
        ensureLevels(frame, scene.width(), scene.height());
        GpuImage target = frame.acquireSceneColorOutput();
        GpuImage exposure = frame.exposureImage();
        float threshold = values.get(THRESHOLD_SCENE_LINEAR);
        float softKnee = threshold * values.get(SOFT_KNEE_FRACTION);
        float radius = values.get(RADIUS);
        int activeLevels = Math.clamp(Math.round(values.get(LEVELS)), 1, levels.length);
        float compositeStrength = values.get(STRENGTH) / activeLevels;

        for (Step step : plan(activeLevels)) {
            VmaImage2D destination = levels[step.destinationLevel()];
            int sourceIndex = step.sourceLevel() < 0
                    ? scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value()
                    : levels[step.sourceLevel()].sampledIndex().value();
            dispatch(frame, destination.storageIndex().value(), sourceIndex,
                    exposure.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    step.mode(), threshold, softKnee, radius, 0.0f,
                    destination.width(), destination.height());
            ComputeSynchronization.betweenDispatches(frame.commandBuffer());
        }
        dispatch(frame,
                target.descriptor(GpuImageDescriptorKind.STORAGE).index().value(),
                levels[0].sampledIndex().value(),
                exposure.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                MODE_COMPOSITE, threshold, softKnee, radius, compositeStrength,
                target.width(), target.height());
    }

    private void ensureLevels(PostEffectFrame frame, int displayWidth, int displayHeight) {
        if (displayWidth == builtWidth && displayHeight == builtHeight) return;
        VmaImage2D[] replacement = allocateLevels(displayWidth, displayHeight);
        ComputeSynchronization.initializeImages(frame.commandBuffer(), Arrays.asList(replacement));
        VmaImage2D[] previous = levels;
        levels = replacement;
        builtWidth = displayWidth;
        builtHeight = displayHeight;
        if (previous.length != 0) gpu.retireAfterUse(() -> closeLevels(previous));
    }

    private VmaImage2D[] allocateLevels(int displayWidth, int displayHeight) {
        int baseWidth = Math.max(1, displayWidth / 2);
        int baseHeight = Math.max(1, displayHeight / 2);
        VmaImage2D[] allocated = new VmaImage2D[levelCount(baseWidth, baseHeight, MAX_LEVELS, 8)];
        int width = baseWidth;
        int height = baseHeight;
        try {
            for (int level = 0; level < allocated.length; level++) {
                allocated[level] = VmaImage2D.create(gpu, width, height,
                        VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        ID + " level " + level + " " + width + "x" + height);
                width = Math.max(1, width / 2);
                height = Math.max(1, height / 2);
            }
            return allocated;
        } catch (RuntimeException | Error failure) {
            closeLevels(allocated);
            throw failure;
        }
    }

    private void dispatch(PostEffectFrame frame, int destinationIndex, int sourceIndex,
                          int exposureIndex, int sceneIndex, int mode, float threshold,
                          float softKnee, float radius, float compositeStrength,
                          int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(BloomPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
            new BloomPushData(destinationIndex, sourceIndex, sampler.index().value(), exposureIndex,
                    sceneIndex, mode, threshold, softKnee, radius, compositeStrength).write(push);
            shader.dispatch(frame.commandBuffer(), push, groups(width), groups(height), 1);
        }
    }

    static int levelCount(int width, int height, int maximum, int minimumDimension) {
        int levels = 1;
        while (levels < maximum && width > minimumDimension && height > minimumDimension) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            levels++;
        }
        return levels;
    }

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

    record Step(int mode, int destinationLevel, int sourceLevel) { }

    static int groups(int extent) {
        return (extent + 7) / 8;
    }

    @Override
    public void close() {
        closeLevels(levels);
        levels = new VmaImage2D[0];
        shader.close();
        sampler.close();
    }

    private static void closeLevels(VmaImage2D[] images) {
        for (VmaImage2D image : images) if (image != null) image.close();
    }
}
