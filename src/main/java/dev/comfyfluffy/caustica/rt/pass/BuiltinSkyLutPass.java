package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.ComputeBinding;
import dev.comfyfluffy.caustica.api.pass.ComputeProgram;
import dev.comfyfluffy.caustica.api.pass.DispatchImage;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.ImageRef;
import dev.comfyfluffy.caustica.api.pass.ImageSize;
import dev.comfyfluffy.caustica.api.pass.PassContext;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.pass.ResourceRegistry;
import dev.comfyfluffy.caustica.api.pass.SkyFrame;
import dev.comfyfluffy.caustica.rt.gen.SkyLutPushData;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Atmospheric transmittance, multiple-scattering, and per-frame sky-view LUT generation. */
public final class BuiltinSkyLutPass implements CausticaRenderPass {
    public static final Identifier ID = id("sky_lut");
    public static final int TRANSMITTANCE_WIDTH = 256;
    public static final int TRANSMITTANCE_HEIGHT = 64;
    public static final int MULTISCATTER_WIDTH = 32;
    public static final int MULTISCATTER_HEIGHT = 32;
    public static final int SKY_VIEW_WIDTH = 192;
    public static final int SKY_VIEW_HEIGHT = 216;
    private static final ShaderSource SHADERS =
            ShaderSource.classpath("/caustica/shaders/world");

    private ImageRef transmittance;
    private ImageRef multiScatter;
    private ImageRef skyView;
    private ComputeProgram transmittanceProgram;
    private ComputeProgram multiScatterProgram;
    private ComputeProgram skyViewProgram;

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.ENVIRONMENT_PREPARE;
    }

    @Override
    public void declareResources(ResourceRegistry resources) {
        transmittance = resources.image(id("sky_transmittance"),
                EngineImage.SKY_TRANSMITTANCE_LUT.format(), EngineImage.SKY_TRANSMITTANCE_LUT.size());
        multiScatter = resources.image(id("sky_multiscatter"), EngineImage.SKY_VIEW_LUT.format(),
                ImageSize.fixed(MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT));
        skyView = resources.image(id("sky_view"),
                EngineImage.SKY_VIEW_LUT.format(), EngineImage.SKY_VIEW_LUT.size());
        resources.publish(EngineImage.SKY_TRANSMITTANCE_LUT, transmittance);
        resources.publish(EngineImage.SKY_VIEW_LUT, skyView);

        transmittanceProgram = resources.compute(new ComputeProgram(id("sky_lut_transmittance"),
                SHADERS, "sky_lut_transmittance", "main",
                List.of(ComputeBinding.storage("transmittanceImage")),
                0, 1, 8, 8, 1));
        multiScatterProgram = resources.compute(new ComputeProgram(id("sky_lut_multiscatter"),
                SHADERS, "sky_lut_multiscatter", "main",
                List.of(ComputeBinding.storage("multiScatterImage"),
                        ComputeBinding.sampledLinear("transmittanceLut"),
                        ComputeBinding.sampledLinear("multiScatterLut")),
                SkyLutPushData.BYTE_SIZE, 1, 8, 8, 1));
        skyViewProgram = resources.compute(new ComputeProgram(id("sky_lut_view"),
                SHADERS, "sky_lut_view", "main",
                List.of(ComputeBinding.storage("skyViewImage"),
                        ComputeBinding.sampledLinear("transmittanceLut"),
                        ComputeBinding.sampledLinear("multiScatterLut")),
                SkyLutPushData.BYTE_SIZE, 1, 8, 8, 1));
    }

    @Override
    public void initialize(PassContext context) {
        context.dispatch2D(transmittanceProgram,
                List.of(DispatchImage.bind("transmittanceImage", transmittance)),
                new byte[0], transmittance);
        context.dispatch2D(multiScatterProgram, List.of(
                DispatchImage.bind("multiScatterImage", multiScatter),
                DispatchImage.bind("transmittanceLut", transmittance),
                DispatchImage.bind("multiScatterLut", multiScatter)),
                pushConstants(context.skyFrame()), multiScatter);
    }

    @Override
    public void record(PassContext context) {
        context.dispatch2D(skyViewProgram, List.of(
                DispatchImage.bind("skyViewImage", skyView),
                DispatchImage.bind("transmittanceLut", transmittance),
                DispatchImage.bind("multiScatterLut", multiScatter)),
                pushConstants(context.skyFrame()), skyView);
    }

    private static byte[] pushConstants(SkyFrame frame) {
        byte[] bytes = new byte[SkyLutPushData.BYTE_SIZE];
        new SkyLutPushData(
                new SkyLutPushData.Float4(frame.sunAngleRadians(), frame.moonAngleRadians(),
                        frame.starAngleRadians(), frame.starBrightness()),
                new SkyLutPushData.Float4(frame.sunIlluminanceLux(), frame.moonIlluminanceLux(),
                        frame.nightAirglowLuminance(), frame.starLuminance()),
                new SkyLutPushData.Float4(frame.noonTiltRadians(), frame.sunAngularRadiusRadians(),
                        frame.moonAngularRadiusRadians(), frame.moonPhaseFixedFraction()),
                new SkyLutPushData.Float4(frame.sunDiscHalfAngleRadians(),
                        frame.moonDiscHalfAngleRadians(), frame.viewerAltitudeKm(), frame.moonPhaseIndex()),
                new SkyLutPushData.Float4(frame.groundAlbedo(), frame.horizonSoftenRadians(), 0.0f, 0.0f))
                .write(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()));
        return bytes;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("caustica", path);
    }
}
