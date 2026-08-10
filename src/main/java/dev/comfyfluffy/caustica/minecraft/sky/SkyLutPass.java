package dev.comfyfluffy.caustica.minecraft.sky;

import dev.comfyfluffy.caustica.api.Option;
import dev.comfyfluffy.caustica.api.ShaderSource;
import dev.comfyfluffy.caustica.api.pass.CausticaRenderPass;
import dev.comfyfluffy.caustica.api.pass.PassFrame;
import dev.comfyfluffy.caustica.api.OptionValues;
import dev.comfyfluffy.caustica.api.ResourceId;
import dev.comfyfluffy.caustica.api.pass.PassSetup;
import dev.comfyfluffy.caustica.api.pass.RenderStage;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.rt.GpuContext;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.accel.GpuBuffer;
import dev.comfyfluffy.caustica.rt.accel.GpuImage;
import dev.comfyfluffy.caustica.rt.gen.SkyInputsData;
import dev.comfyfluffy.caustica.api.pass.ComputeDispatch;
import dev.comfyfluffy.caustica.api.pass.PassShaderCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.data.AtlasIds;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.MoonPhase;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Atmospheric transmittance, multiple-scattering, and per-frame sky-view LUT generation for the Overworld
 * sky. The two scattering LUTs are static and bake once (redone on {@link #invalidate()}); the sky-view
 * LUT re-renders every frame.
 *
 * <p>This Minecraft adapter samples celestial state and publishes the same packed
 * inputs to its LUT bakes and sky slot. The Minecraft light adapter samples those host attributes
 * independently to submit the corresponding distant lights through the light-provider API.
 */
public final class SkyLutPass implements CausticaRenderPass {
    public static final ResourceId ID = ResourceId.of("caustica", "sky_lut");
    private static final Identifier SUN_SPRITE_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_SPRITE_IDS = createMoonSpriteIds();
    static final int TRANSMITTANCE_WIDTH = 256;
    static final int TRANSMITTANCE_HEIGHT = 64;
    static final int MULTISCATTER_WIDTH = 32;
    static final int MULTISCATTER_HEIGHT = 32;
    static final int SKY_VIEW_WIDTH = 192;
    static final int SKY_VIEW_HEIGHT = 216;
    private static final ShaderSource SHADERS = ShaderSource.classpath("/caustica/shaders/minecraft", "sky");

    // The sky-geometry options this pass owns are declared here so the token passed to OptionValues#get
    // and the declaration registered by the Minecraft extension are the same
    // object. This pass is their only reader.
    // No enabled option: the sky slot fills every ray that escapes the world, so there is no state in which
    // this pass does nothing. Its group collapses by the caret alone.
    public static final String GROUP = "sky";
    public static final Option<Float> SUN_NOON_SOUTH_TILT_DEGREES =
            Option.range("sky.sun-noon-south-tilt-degrees", -89.0f, 89.0f, 30.0f).inGroup(GROUP);
    public static final Option<Float> SUN_ANGULAR_RADIUS_DEGREES =
            Option.range("sky.sun-angular-radius-degrees", 0.0f, 20.0f, 0.6f).inGroup(GROUP);
    public static final Option<Float> MOON_ANGULAR_RADIUS_DEGREES =
            Option.range("sky.moon-angular-radius-degrees", 0.0f, 20.0f, 1.5f).inGroup(GROUP);
    public static final Option<Float> SUN_DISC_HALF_ANGLE_DEGREES =
            Option.range("sky.sun-disc-half-angle-degrees", 0.0f, 45.0f, 16.7f).inGroup(GROUP);
    public static final Option<Float> MOON_DISC_HALF_ANGLE_DEGREES =
            Option.range("sky.moon-disc-half-angle-degrees", 0.0f, 45.0f, 11.31f).inGroup(GROUP);
    public static final Option<Float> GROUND_ALBEDO =
            Option.range("sky.ground-albedo", 0.0f, 1.0f, 0.1f).inGroup(GROUP);
    public static final Option<Float> HORIZON_SOFTEN_DEGREES =
            Option.range("sky.horizon-soften-degrees", 0.0f, 90.0f, 15.0f).inGroup(GROUP);
    public static final List<Option<?>> OPTIONS = List.of(
            SUN_NOON_SOUTH_TILT_DEGREES, SUN_ANGULAR_RADIUS_DEGREES, MOON_ANGULAR_RADIUS_DEGREES,
            SUN_DISC_HALF_ANGLE_DEGREES, MOON_DISC_HALF_ANGLE_DEGREES, GROUND_ALBEDO,
            HORIZON_SOFTEN_DEGREES);

    static final List<ComputeDispatch.Binding> TRANSMITTANCE_BINDINGS =
            List.of(ComputeDispatch.Binding.STORAGE);
    static final List<ComputeDispatch.Binding> SCATTER_BINDINGS = List.of(
            ComputeDispatch.Binding.STORAGE, ComputeDispatch.Binding.SAMPLED, ComputeDispatch.Binding.SAMPLED);

    private GpuContext ctx;
    private long sampler;
    /**
     * Point sampling for the celestials atlas only. The LUT sampler above is linear because a baked sky
     * LUT is a smooth function; vanilla's sun and moon sprites are 32x32 pixel art drawn across a quad
     * spanning tens of degrees, where linear magnification interpolates a handful of texels over hundreds
     * of screen pixels and smears the disc.
     */
    private long celestialSampler;
    private GpuImage transmittance;
    private GpuImage multiScatter;
    private GpuImage skyView;
    private ComputeDispatch transmittanceDispatch;
    private ComputeDispatch multiScatterDispatch;
    private ComputeDispatch skyViewDispatch;
    private boolean baked;
    /** The {@code sky.ground-albedo} the current bake used, so an edit to it can invalidate that bake. */
    private float bakedGroundAlbedo;
    /**
     * This frame's {@link SkyInputsData}, published as a set-2 uniform buffer the sky slot reads. Host
     * visible and rewritten in place each frame: it is 112 bytes read by the miss shader only, so a
     * staging copy would cost more than the uncached read it avoids.
     */
    private GpuBuffer skyInputsBuffer;
    // Vanilla's celestials atlas view and the sprite rects within it, cached because getSprite() is a
    // registry lookup and the rects only change when the atlas is restitched or the moon phase ticks.
    private long celestialAtlasView;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    @Override
    public ResourceId id() {
        return ID;
    }

    @Override
    public RenderStage stage() {
        return RenderStage.ENVIRONMENT_PREPARE;
    }

    @Override
    public void create(PassSetup setup) {
        ctx = setup.context();
        // The pass object outlives a RenderPassManager/GPU-context instance. Force the new manager to
        // observe and publish the host atlas even when Vulkan recycles the same numeric view handle.
        celestialAtlasView = 0L;
        celestialUvMoonPhase = -1;
        sampler = ComputeDispatch.createLinearClampSampler(ctx, ID + " sampler");
        celestialSampler = ComputeDispatch.createNearestClampSampler(ctx, ID + " celestials sampler");
        transmittance = ctx.createStorageImage(TRANSMITTANCE_WIDTH, TRANSMITTANCE_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " transmittance");
        multiScatter = ctx.createStorageImage(MULTISCATTER_WIDTH, MULTISCATTER_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " multiscatter");
        skyView = ctx.createStorageImage(SKY_VIEW_WIDTH, SKY_VIEW_HEIGHT,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, ID + " sky view");
        skyInputsBuffer = ctx.createBuffer(SkyInputsData.BYTE_SIZE,
                VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, ID + " sky inputs");
        // Names match caustica_minecraft_sky_bindings.slang's [[vk::binding(N, 2)]] declarations exactly —
        // that module, not this Java class, is what defines the resource's identity.
        setup.publishWorldResource("transmittance", transmittance, sampler);
        setup.publishWorldResource("skyView", skyView, sampler);
        setup.publishWorldResource("skyInputs", skyInputsBuffer);

        try {
            transmittanceDispatch = compile("caustica_minecraft_sky_lut_transmittance",
                    TRANSMITTANCE_BINDINGS, 0);
            multiScatterDispatch = compile("caustica_minecraft_sky_lut_multiscatter",
                    SCATTER_BINDINGS, SkyInputsData.BYTE_SIZE);
            skyViewDispatch = compile("caustica_minecraft_sky_lut_view", SCATTER_BINDINGS,
                    SkyInputsData.BYTE_SIZE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ComputeDispatch compile(String module, List<ComputeDispatch.Binding> bindings,
                                    int pushConstantBytes) throws IOException {
        ResourceId programId = ResourceId.of("caustica", module);
        PassShaderCompiler.CompiledProgram compiled = PassShaderCompiler.compile(
                PassShaderCompiler.defaultCacheRoot(), programId, SHADERS, module, "main");
        PassShaderCompiler.validateBindings(programId, compiled.reflectionJson(), bindings,
                pushConstantBytes, "main", 8, 8, 1);
        return ComputeDispatch.create(ctx, programId.toString(), compiled.spirv(), "main",
                bindings, pushConstantBytes, 1, sampler);
    }

    @Override
    public void record(PassFrame frame) {
        SkyState state = gatherSkyState(frame.options());
        // Before skyInputs(): this is what resolves the sprite rects skyInputs() then reads. Called the
        // other way round, the buffer carries the previous frame's UVs — and on the first frame the
        // untouched full-range defaults, which stretch the whole atlas (sun plus every moon phase) across
        // the sun's quad.
        refreshCelestialAtlas(frame, state);
        // The sky slot reads every one of these values from this buffer; the bakes below read the same
        // bytes as a push constant. One derivation, two consumers.
        SkyInputsData inputs = skyInputs(state);
        inputs.write(MemoryUtil.memByteBuffer(skyInputsBuffer.mapped, SkyInputsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder()));
        skyInputsBuffer.flush();
        byte[] push = pushConstants(inputs);
        // The multi-scatter bake integrates bounces off the ground, so it is the one baked-LUT input a
        // player can edit. Re-bake when it moves, or the option would only apply after a dimension change.
        if (baked && bakedGroundAlbedo != state.groundAlbedo()) {
            baked = false;
        }
        if (!baked) {
            bakedGroundAlbedo = state.groundAlbedo();
            transmittanceDispatch.beginFrame();
            transmittanceDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{transmittance},
                    new byte[0], groups(TRANSMITTANCE_WIDTH), groups(TRANSMITTANCE_HEIGHT), 1);
            frame.memoryBarrier();

            multiScatterDispatch.beginFrame();
            multiScatterDispatch.dispatch(frame.commandBuffer(),
                    new GpuImage[]{multiScatter, transmittance, multiScatter},
                    push, groups(MULTISCATTER_WIDTH), groups(MULTISCATTER_HEIGHT), 1);
            frame.memoryBarrier();
            baked = true;
        }

        skyViewDispatch.beginFrame();
        skyViewDispatch.dispatch(frame.commandBuffer(), new GpuImage[]{skyView, transmittance, multiScatter},
                push, groups(SKY_VIEW_WIDTH), groups(SKY_VIEW_HEIGHT), 1);
    }

    @Override
    public void invalidate() {
        baked = false;
    }

    private static int groups(int extent) {
        return (extent + 7) / 8;
    }

    /**
     * This pass's own snapshot of Minecraft's celestial state, the look package's photometric lighting
     * anchors, and the {@code sky.*} geometry options, gathered fresh every time it's called rather than
     * shared with anything else. Mirrors what {@code RtComposite.skyPush()} computes for the world push's
     * sky fields — deliberately a second, independent read rather than a shared one; see the class javadoc.
     */
    private static SkyState gatherSkyState(OptionValues options) {
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

        RtLookPackage.Lighting lighting = RtLookPackage.current().lighting();
        float sunNoonSouthTiltDegrees = options.get(SUN_NOON_SOUTH_TILT_DEGREES);
        float sunAngularRadiusDegrees = options.get(SUN_ANGULAR_RADIUS_DEGREES);
        float moonAngularRadiusDegrees = options.get(MOON_ANGULAR_RADIUS_DEGREES);
        float sunDiscHalfAngleDegrees = options.get(SUN_DISC_HALF_ANGLE_DEGREES);
        float moonDiscHalfAngleDegrees = options.get(MOON_DISC_HALF_ANGLE_DEGREES);
        float groundAlbedo = options.get(GROUND_ALBEDO);
        float horizonSoftenDegrees = options.get(HORIZON_SOFTEN_DEGREES);
        return new SkyState(
                sunAngle, moonAngle, starAngle, starBrightness,
                lighting.sunIlluminanceLux(), lighting.moonIlluminanceLux(),
                lighting.nightAirglowLuminanceCdM2(), lighting.starLuminanceCdM2(),
                sunNoonSouthTiltDegrees * toRadians,
                sunAngularRadiusDegrees * toRadians,
                moonAngularRadiusDegrees * toRadians,
                lighting.moonPhaseFixedFraction(),
                sunDiscHalfAngleDegrees * toRadians,
                moonDiscHalfAngleDegrees * toRadians,
                viewerAltitudeKm, moonPhase, groundAlbedo,
                horizonSoftenDegrees * toRadians);
    }

    /**
     * The one packing of this frame's sky state, shared by the LUT bakes (as a push constant) and the sky
     * slot (as the published uniform buffer), so the two cannot disagree about what frame they render.
     */
    SkyInputsData skyInputs(SkyState state) {
        return new SkyInputsData(
                new SkyInputsData.Float4(state.sunAngleRadians(), state.moonAngleRadians(),
                        state.starAngleRadians(), state.starBrightness()),
                new SkyInputsData.Float4(state.sunIlluminanceLux(), state.moonIlluminanceLux(),
                        state.nightAirglowLuminance(), state.starLuminance()),
                new SkyInputsData.Float4(state.noonTiltRadians(), state.sunAngularRadiusRadians(),
                        state.moonAngularRadiusRadians(), state.moonPhaseFixedFraction()),
                new SkyInputsData.Float4(state.sunDiscHalfAngleRadians(),
                        state.moonDiscHalfAngleRadians(), state.viewerAltitudeKm(), state.moonPhaseIndex()),
                new SkyInputsData.Float4(state.groundAlbedo(), state.horizonSoftenRadians(), 0.0f, 0.0f),
                new SkyInputsData.Float4(sunU0, sunV0, sunU1, sunV1),
                new SkyInputsData.Float4(moonU0, moonV0, moonU1, moonV1));
    }

    static byte[] pushConstants(SkyInputsData inputs) {
        byte[] bytes = new byte[SkyInputsData.BYTE_SIZE];
        inputs.write(ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()));
        return bytes;
    }

    /**
     * Binds vanilla's celestials atlas (sun + moon-phase sprites) as this pass's own world resource and
     * refreshes the sprite rects when the atlas or the moon phase changes. A sky for another dimension
     * binds no such texture, so this belongs to the slot that draws celestial sprites.
     */
    private void refreshCelestialAtlas(PassFrame frame, SkyState state) {
        long view = celestialsAtlasView();
        int moonPhase = Math.clamp((int) state.moonPhaseIndex(), 0, MOON_SPRITE_IDS.length - 1);
        if (view != celestialAtlasView) {
            celestialAtlasView = view;
            celestialUvMoonPhase = -1;
            if (view != 0L) {
                frame.publishWorldResource("celestialsAtlas", view, celestialSampler);
            }
        }
        if (view == 0L || moonPhase == celestialUvMoonPhase) {
            return;
        }
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
            TextureAtlasSprite sun = atlas.getSprite(SUN_SPRITE_ID);
            sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
            TextureAtlasSprite moon = atlas.getSprite(MOON_SPRITE_IDS[moonPhase]);
            moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
        } catch (Exception ignored) {
            // Atlas not stitched yet — full-range UVs until it is; the discs sample a defined texel either
            // way, and this runs again next frame.
        }
        celestialUvMoonPhase = moonPhase;
    }

    /** Vulkan image view of the vanilla celestials atlas, or 0 while it is unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return view instanceof VulkanGpuTextureView vulkanView ? vulkanView.vkImageView() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Identifier[] createMoonSpriteIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
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
        if (skyInputsBuffer != null) {
            skyInputsBuffer.destroy();
            skyInputsBuffer = null;
        }
        if (ctx != null && sampler != 0L) {
            VK10.vkDestroySampler(ctx.vk(), sampler, null);
            sampler = 0L;
        }
        if (ctx != null && celestialSampler != 0L) {
            VK10.vkDestroySampler(ctx.vk(), celestialSampler, null);
            celestialSampler = 0L;
        }
    }

    /**
     * The semantic sky inputs this pass needs for one frame, before packing into {@link SkyInputsData}.
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
