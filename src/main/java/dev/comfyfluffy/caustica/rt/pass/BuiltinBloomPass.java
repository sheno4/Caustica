package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.ComputeBinding;
import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
import dev.comfyfluffy.caustica.api.pass.DispatchImage;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.ImagePyramid;
import dev.comfyfluffy.caustica.api.pass.ImageRef;
import dev.comfyfluffy.caustica.api.pass.PassContext;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.pass.ResourceRegistry;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.gen.BloomPushData;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Scene-referred bloom implemented entirely through the public render-pass API. */
public final class BuiltinBloomPass implements CausticaRenderPass {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "bloom");
    private static final Identifier PYRAMID_ID =
            Identifier.fromNamespaceAndPath("caustica", "bloom_pyramid");
    private static final Identifier PROGRAM_ID =
            Identifier.fromNamespaceAndPath("caustica", "bloom_compute");
    private static final int MAX_LEVELS = 8;
    private static final int MODE_PREFILTER = 0;
    private static final int MODE_DOWNSAMPLE = 1;
    private static final int MODE_UPSAMPLE = 2;

    private final RtLookPackage.Bloom settings;
    private ImageRef reconstructedColor;
    private ImageRef exposure;
    private ImagePyramid pyramid;
    private ComputeProgram program;

    public BuiltinBloomPass(RtLookPackage.Bloom settings) {
        this.settings = settings;
    }

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.AFTER_RECONSTRUCTION;
    }

    @Override
    public void declareResources(ResourceRegistry resources) {
        reconstructedColor = resources.engineImage(EngineImage.RECONSTRUCTED_COLOR);
        exposure = resources.engineImage(EngineImage.EXPOSURE);
        pyramid = resources.imagePyramid(PYRAMID_ID, EngineImage.BLOOM.format(),
                EngineImage.BLOOM.size(), Math.min(settings.levels(), MAX_LEVELS), 8);
        resources.publish(EngineImage.BLOOM, pyramid.level(0));
        program = resources.compute(new ComputeProgram(PROGRAM_ID,
                ShaderSource.classpath("/caustica/shaders/passes/bloom"),
                "caustica_bloom", "main",
                List.of(ComputeBinding.storage("dstImage"), ComputeBinding.sampledLinear("srcImage"),
                        ComputeBinding.storage("exposureImage")),
                BloomPushData.BYTE_SIZE, MAX_LEVELS * 2 - 1, 8, 8, 1));
    }

    @Override
    public void record(PassContext context) {
        float softKnee = settings.thresholdSceneLinear() * settings.softKneeFraction();
        int levels = context.levelCount(pyramid);
        recordStep(context, pyramid.level(0), reconstructedColor, MODE_PREFILTER, softKnee);
        for (int level = 1; level < levels; level++) {
            recordStep(context, pyramid.level(level), pyramid.level(level - 1), MODE_DOWNSAMPLE, softKnee);
        }
        for (int level = levels - 2; level >= 0; level--) {
            recordStep(context, pyramid.level(level), pyramid.level(level + 1), MODE_UPSAMPLE, softKnee);
        }
    }

    private void recordStep(PassContext context, ImageRef destination, ImageRef source,
                            int mode, float softKnee) {
        byte[] pushConstants = new byte[BloomPushData.BYTE_SIZE];
        new BloomPushData(mode, settings.thresholdSceneLinear(), softKnee, settings.radius())
                .write(ByteBuffer.wrap(pushConstants).order(ByteOrder.nativeOrder()));
        context.dispatch2D(program, List.of(
                DispatchImage.bind("dstImage", destination),
                DispatchImage.bind("srcImage", source),
                DispatchImage.bind("exposureImage", exposure)), pushConstants, destination);
    }
}
