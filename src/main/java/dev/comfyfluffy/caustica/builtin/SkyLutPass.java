package dev.comfyfluffy.caustica.builtin;

import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import dev.comfyfluffy.caustica.api.provider.LightProvider;
import dev.comfyfluffy.caustica.api.provider.LightSink;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.gen.SkyLutPushData;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Atmospheric transmittance, multiple-scattering, and per-frame sky-view LUT generation. The two
 * scattering LUTs are static and bake once (redone on {@link #invalidate()}); the sky-view LUT re-renders
 * every frame from this pass's own {@link #gatherSkyState()} sample.
 *
 * <p>Lives under {@code dev.comfyfluffy.caustica.builtin} rather than the engine's {@code rt} tree
 * deliberately: this pass ships through the same {@code CausticaRenderPass}/{@code LightProvider}
 * registration API a third-party extension would use ({@code api.BuiltinExtension} registers it, it does
 * not get called directly), so it only reaches engine internals through public surface —
 * {@link GpuContext}, {@link GpuImage}, the now-public {@link ComputeDispatch}/{@link PassShaderCompiler}
 * pass-authoring helpers, and {@link RtLookPackage#current()} for its config, the same way
 * {@code BuiltinExtension} already reads it for bloom.
 *
 * <p>It also no longer reads a shared, engine-published sky snapshot: it samples Minecraft's celestial
 * state itself, once per frame, independently of whatever {@code RtComposite} computes for the world
 * push's NEE sun/moon. The two are allowed to drift by up to a frame's worth of partial-tick sampling
 * order — deliberately, since unifying them would require the world push's own sun/moon state to route
 * through the (currently nonexistent) light-provider path first. See {@link #submitLights}.
 */
public final class SkyLutPass implements CausticaRenderPass, LightProvider {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("caustica", "sky_lut");
    private static final Identifier SUN_LIGHT_ID = Identifier.fromNamespaceAndPath("caustica", "sky_sun");
    private static final Identifier MOON_LIGHT_ID = Identifier.fromNamespaceAndPath("caustica", "sky_moon");
    static final int TRANSMITTANCE_WIDTH = 256;
    static final int TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32;
    static final int MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192;
    static final int SKY_VIEW_HEIGHT = 216;
    private static final ShaderSource SHADERS = ShaderSource.classpath("/caustica/shaders/world", "sky");
    static final List<ComputeDispatch.Binding> TRANSMITTANCE_BINDINGS =
            List.of(ComputeDispatch.Binding.STORAGE);
    static final List<ComputeDispatch.Binding> SCATTER_BINDINGS = List.of(
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED, ComputeDispatch.Binding.SAMPLED);

    private GpuContext ctx;
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
        // Names match caustica_lut_sky_bindings.slang's own [[vk::binding(N, 2)]] declarations exactly —
        // that module, not this Java class, is what defines the resource's identity.
        setup.publishWorldResource("transmittance", transmittance, sampler);
        setup.publishWorldResource("skyView", skyView, sampler);

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
        SkyState state = gatherSkyState();
        if (!baked) {
            transmittanceDispatch.beginFrame();
            transmittanceDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{transmittance},
                    new byte[0], groups(TRANSMITTANCE_WIDTH), groups(TRANSMITTANCE_HEIGHT), 1);
            frame.memoryBarrier();

            multiScatterDispatch.beginFrame();
            multiScatterDispatch.dispatch(frame.commandBuffer(),
                    new GpuImage[]{multiScatter, transmittance, multiScatter},
                    pushConstants(state), groups(MULTISCATTER_WIDTH), groups(MULTISCATTER_HEIGHT), 1);
            frame.memoryBarrier();
            baked = true;
        }

        skyViewDispatch.beginFrame();
        skyViewDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{skyView, transmittance, multiScatter},
                pushConstants(state), groups(SKY_VIEW_WIDTH), groups(SKY_VIEW_HEIGHT), 1);
    }

    /**
     * Placeholder exercise of the light-provider API from a real render pass: submits the sun and moon as
     * distant directional lights using the same state this frame's LUT bake used, so a reviewer can judge
     * the shape a "sky as a light provider" registration would take. See {@link LightSink}'s javadoc for
     * why nothing reads these yet.
     */
    @Override
    public void submitLights(LightSink sink) {
        SkyState state = gatherSkyState();
        float peakSun = (float) Math.cos(state.sunAngleRadians());
        sink.directionalLight(SUN_LIGHT_ID,
                (float) -Math.sin(state.sunAngleRadians()),
                (float) (Math.cos(state.noonTiltRadians()) * peakSun),
                (float) (Math.sin(state.noonTiltRadians()) * peakSun),
                state.sunIlluminanceLux());
        float peakMoon = (float) Math.cos(state.moonAngleRadians());
        sink.directionalLight(MOON_LIGHT_ID,
                (float) -Math.sin(state.moonAngleRadians()),
                (float) (Math.cos(state.noonTiltRadians()) * peakMoon),
                (float) (Math.sin(state.noonTiltRadians()) * peakMoon),
                state.moonIlluminanceLux());
    }

    @Override
    public void invalidate() {
        baked = false;
    }

    private static int groups(int extent) {
        return (extent + 7) / 8;
    }

    /**
     * This pass's own snapshot of Minecraft's celestial state and the look package's sky constants,
     * gathered fresh every time it's called rather than shared with anything else. Mirrors what
     * {@code RtComposite.skyPush()} computes for the world push's sky fields — deliberately a second,
     * independent read rather than a shared one; see the class javadoc.
     */
    private static SkyState gatherSkyState() {
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        int seaLevel = mc.level != null ? mc.level.getSeaLevel() : 0;
        // No direct camera-position accessor is reachable from here the way RtComposite reads it (its
        // camY arrives pre-captured off the level-projection mixin hook); the render camera's entity eye
        // height is a close enough independent read for a LUT bake, and it's what "gather it yourself"
        // means for code that isn't in that capture path.
        Entity cameraEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        double camY = cameraEntity != null ? cameraEntity.getEyePosition(partial).y : seaLevel;
        float viewerAltitudeKm = Math.clamp((float) ((camY - seaLevel) / 100.0), 0.0f, 99.0f);
        float toRadians = (float) (Math.PI / 180.0);
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * toRadians;
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * toRadians;
        float starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * toRadians;
        float starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        float moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new

        RtLookPackage.Sky sky = RtLookPackage.current().sky();
        RtLookPackage.Lighting lighting = RtLookPackage.current().lighting();
        return new SkyState(
                sunAngle, moonAngle, starAngle, starBrightness,
                lighting.sunIlluminanceLux(), lighting.moonIlluminanceLux(),
                lighting.nightAirglowLuminanceCdM2(), lighting.starLuminanceCdM2(),
                sky.sunNoonSouthTiltDegrees() * toRadians,
                sky.sunAngularRadiusDegrees() * toRadians,
                sky.moonAngularRadiusDegrees() * toRadians,
                lighting.moonPhaseFixedFraction(),
                sky.sunDiscHalfAngleDegrees() * toRadians,
                sky.moonDiscHalfAngleDegrees() * toRadians,
                viewerAltitudeKm, moonPhase, sky.groundAlbedo(),
                sky.horizonSoftenDegrees() * toRadians);
    }

    static byte[] pushConstants(SkyState state) {
        byte[] bytes = new byte[SkyLutPushData.BYTE_SIZE];
        new SkyLutPushData(
                new SkyLutPushData.Float4(state.sunAngleRadians(), state.moonAngleRadians(),
                        state.starAngleRadians(), state.starBrightness()),
                new SkyLutPushData.Float4(state.sunIlluminanceLux(), state.moonIlluminanceLux(),
                        state.nightAirglowLuminance(), state.starLuminance()),
                new SkyLutPushData.Float4(state.noonTiltRadians(), state.sunAngularRadiusRadians(),
                        state.moonAngularRadiusRadians(), state.moonPhaseFixedFraction()),
                new SkyLutPushData.Float4(state.sunDiscHalfAngleRadians(),
                        state.moonDiscHalfAngleRadians(), state.viewerAltitudeKm(), state.moonPhaseIndex()),
                new SkyLutPushData.Float4(state.groundAlbedo(), state.horizonSoftenRadians(), 0.0f, 0.0f))
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

    /**
     * This pass's own copy of the semantic sky inputs it needs for one frame — no longer the engine's
     * shared {@code rt.SkyFrame} snapshot. See the class javadoc for why the duplication is deliberate.
     */
    record SkyState(
            float sunAngleRadians, float moonAngleRadians, float starAngleRadians, float starBrightness,
            float sunIlluminanceLux, float moonIlluminanceLux, float nightAirglowLuminance,
            float starLuminance, float noonTiltRadians, float sunAngularRadiusRadians,
            float moonAngularRadiusRadians, float moonPhaseFixedFraction, float sunDiscHalfAngleRadians,
            float moonDiscHalfAngleRadians, float viewerAltitudeKm, float moonPhaseIndex,
            float groundAlbedo, float horizonSoftenRadians) {
    }
}
