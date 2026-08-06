package dev.comfyfluffy.caustica.rt.pass;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.EngineImage;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.SkyFrame;
import dev.comfyfluffy.caustica.rt.SkyFrameState;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.gen.SkyLutPushData;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Atmospheric transmittance, multiple-scattering, and per-frame sky-view LUT generation. The two
 * scattering LUTs are static and bake once (redone on {@link #invalidate()}); the sky-view LUT re-renders
 * every frame from {@link SkyFrameState}, which it reads directly rather than being handed a value.
 */
public final class SkyLutPass implements CausticaRenderPass {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "sky_lut");
    static final int TRANSMITTANCE_WIDTH = 256;
    static final int TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32;
    static final int MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192;
    static final int SKY_VIEW_HEIGHT = 216;
    private static final ShaderSource SHADERS = ShaderSource.classpath("/caustica/shaders/world");
    static final List<ComputeDispatch.Binding> TRANSMITTANCE_BINDINGS =
            List.of(ComputeDispatch.Binding.STORAGE);
    static final List<ComputeDispatch.Binding> SCATTER_BINDINGS = List.of(
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED, ComputeDispatch.Binding.SAMPLED);

    private RtContext ctx;
    private long sampler;
    private GpuImage transmittance;
    private GpuImage multiScatter;
    private GpuImage skyView;
    private ComputeDispatch transmittanceDispatch;
    private ComputeDispatch multiScatterDispatch;
    private ComputeDispatch skyViewDispatch;
    private boolean baked;

    @Override
    public Identifier id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.ENVIRONMENT_PREPARE;
    }

    @Override
    public void create(PassSetup setup) {
        ctx = setup.context();
        sampler = ComputeDispatch.createLinearClampSampler(ctx, ID + " sampler");
        transmittance = ctx.createStorageImage(TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " transmittance");
        multiScatter = ctx.createStorageImage(MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " multiscatter");
        skyView = ctx.createStorageImage(SKY_VIEW_WIDTH, SKY_VIEW_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " sky view");
        setup.publish(EngineImage.SKY_TRANSMITTANCE_LUT, transmittance);
        setup.publish(EngineImage.SKY_VIEW_LUT, skyView);

        try {
            transmittanceDispatch = compile("sky_lut_transmittance", TRANSMITTANCE_BINDINGS, 0);
            multiScatterDispatch = compile("sky_lut_multiscatter", SCATTER_BINDINGS, SkyLutPushData.BYTE_SIZE);
            skyViewDispatch = compile("sky_lut_view", SCATTER_BINDINGS, SkyLutPushData.BYTE_SIZE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ComputeDispatch compile(String module, List<ComputeDispatch.Binding> bindings,
                                    int pushConstantBytes) throws IOException {
        Identifier programId = Identifier.fromNamespaceAndPath("caustica", module);
        PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(
                PassShaderCompiler.defaultCacheRoot(), programId, SHADERS, module, "main");
        PassShaderCompiler.validateBindings(programId, compiled.reflectionJson(), bindings,
                pushConstantBytes, "main", 8, 8, 1);
        return ComputeDispatch.create(ctx, programId.toString(), compiled.spirv(), "main",
                bindings, pushConstantBytes, 1, sampler);
    }

    @Override
    public void record(PassFrame frame) {
        if (!baked) {
            transmittanceDispatch.beginFrame();
            transmittanceDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{transmittance},
                    new byte[0], groups(TRANSMITTANCE_WIDTH), groups(TRANSMITTANCE_HEIGHT), 1);
            frame.memoryBarrier();

            multiScatterDispatch.beginFrame();
            multiScatterDispatch.dispatch(frame.commandBuffer(),
                    new GpuImage[]{multiScatter, transmittance, multiScatter},
                    pushConstants(SkyFrameState.current()), groups(MULTISCATTER_WIDTH), groups(MULTISCATTER_HEIGHT), 1);
            frame.memoryBarrier();
            baked = true;
        }

        skyViewDispatch.beginFrame();
        skyViewDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{skyView, transmittance, multiScatter},
                pushConstants(SkyFrameState.current()), groups(SKY_VIEW_WIDTH), groups(SKY_VIEW_HEIGHT), 1);
    }

    @Override
    public void invalidate() {
        baked = false;
    }

    private static int groups(int extent) {
        return (extent + 7) / 8;
    }

    static byte[] pushConstants(SkyFrame frame) {
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

    @Override
    public void destroy() {
        if (transmittanceDispatch != null) {
            transmittanceDispatch.destroy();
            transmittanceDispatch = null;
        }
        if (multiScatterDispatch != null) {
            multiScatterDispatch.destroy();
            multiScatterDispatch = null;
        }
        if (skyViewDispatch != null) {
            skyViewDispatch.destroy();
            skyViewDispatch = null;
        }
        if (transmittance != null) {
            transmittance.destroy();
            transmittance = null;
        }
        if (multiScatter != null) {
            multiScatter.destroy();
            multiScatter = null;
        }
        if (skyView != null) {
            skyView.destroy();
            skyView = null;
        }
        if (ctx != null && sampler != 0L) {
            VK10.vkDestroySampler(ctx.vk(), sampler, null);
            sampler = 0L;
        }
    }
}
