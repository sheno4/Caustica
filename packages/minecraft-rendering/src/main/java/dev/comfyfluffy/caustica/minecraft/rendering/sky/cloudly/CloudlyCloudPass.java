package dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeJob;
import dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyCloudComponentData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyCloudPushData;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyCloudPushData.Float4;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.gen.CloudlyNoiseProfileData;
import dev.comfyfluffy.caustica.support.ColorSpaces;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import dev.comfyfluffy.caustica.vulkan.ComputeSynchronization;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;

/** Camera-ray scattering through original source volumes under an explicit local approximation adapter. */
public final class CloudlyCloudPass implements Pass<PostEffectFrame> {
    public static final PassId ID = new PassId("caustica", "cloudly_clouds");
    public static final String GROUP = "sky-cloudly";
    public static final Option<Boolean> ENABLED = Option.bool("sky.cloudly.enabled", false).inGroupAsHeader(GROUP);
    public static final Option<Optional<String>> SOURCE_PACK = Option.optionalString("sky.cloudly.source-pack").inGroup(GROUP);
    public static final Option<Float> SAMPLES = Option.range("sky.cloudly.samples", 64, 1024, 512).inGroup(GROUP).step(32);
    public static final Option<Float> MAX_DISTANCE_KM = Option.range("sky.cloudly.max-distance-km", 1, 200, 200).inGroup(GROUP);
    public static final List<Option<?>> OPTIONS = List.of(ENABLED, SOURCE_PACK, SAMPLES, MAX_DISTANCE_KM);
    private static final Logger LOGGER = LoggerFactory.getLogger(CloudlyCloudPass.class);
    private static final int MAX_COMPONENTS = 64;

    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final Supplier<OptionValues> options;
    private final Supplier<MinecraftSkyFrame> frames;
    private final Supplier<CloudlySkyLighting> atmosphere;
    private final SourceModel model;
    private final ShaderObjectCompute shader;
    private final ResourceOwner shaderOwner;
    private final CloudlyGpuTiming timing;
    private final Thread worker;
    private GpuComputeJob uploadJob;
    private Published published;
    private Layer layer;
    private long layerFrame = -1;
    private boolean closed;

    /** Validates this camera adapter's supported source subset before the session allocates GPU state. */
    public static void validateSource(CloudlySourcePack source) { SourceModel.from(source); }

    public CloudlyCloudPass(PostEffectSetup setup, GpuComputeQueue compute, ResourceFactory resources,
                            Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames) {
        this(setup, compute, resources, options, frames, configuredSource(options.get()));
    }

    /** Shares the session's immutable source pack with sky LUT and retained-light preparation. */
    public CloudlyCloudPass(PostEffectSetup setup, GpuComputeQueue compute, ResourceFactory resources,
                            Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames,
                            CloudlySourcePack source) {
        this(setup, compute, resources, options, frames, source, () -> null);
    }

    public CloudlyCloudPass(PostEffectSetup setup, GpuComputeQueue compute, ResourceFactory resources,
                            Supplier<OptionValues> options, Supplier<MinecraftSkyFrame> frames,
                            CloudlySourcePack source, Supplier<CloudlySkyLighting> atmosphere) {
        this.gpu = setup.gpu();
        this.resources = resources;
        this.options = options;
        this.frames = frames;
        this.atmosphere = atmosphere;
        if (source == null) {
            model = null;
            shader = null;
            shaderOwner = null;
            timing = null;
            worker = null;
            return;
        }
        model = SourceModel.from(source);
        shader = ShaderObjectCompute.load(gpu, CloudlyCloudPass.class, "/caustica/shaders/pipelines/cloudly/main.comp.spv");
        try { shaderOwner = resources.create(shader::close); }
        catch (RuntimeException | Error failure) { shader.close(); throw failure; }
        try { timing = new CloudlyGpuTiming(gpu, resources); }
        catch (RuntimeException | Error failure) { shaderOwner.close(); throw failure; }
        worker = Thread.ofVirtual().name("Caustica Cloudly source upload").unstarted(() -> prepare(compute, source));
        worker.start();
    }

    private static CloudlySourcePack configuredSource(OptionValues values) {
        if (!values.get(ENABLED) || values.get(SOURCE_PACK).isEmpty()) return null;
        try { return CloudlySourcePack.load(Path.of(values.get(SOURCE_PACK).orElseThrow())); }
        catch (IOException failure) { throw new UncheckedIOException("Unable to load the configured Cloudly source pack", failure); }
    }

    private void prepare(GpuComputeQueue compute, CloudlySourcePack source) {
        synchronized (this) { if (closed) return; }
        try {
            GpuComputeJob accepted = CloudlyVolumeUpload.prepare(gpu, compute, resources, source, this::uploaded);
            synchronized (this) {
                if (closed) accepted.close();
                else uploadJob = accepted;
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) { if (closed) return; }
            LOGGER.error("Cloudly source volume preparation failed", failure);
        }
    }

    private synchronized void uploaded(CloudlyVolumeUpload.Completion completion) {
        if (completion instanceof CloudlyVolumeUpload.Failed failed) {
            if (closed) return;
            LOGGER.error("Cloudly source volume upload failed", failed.failure());
            return;
        }
        if (!(completion instanceof CloudlyVolumeUpload.Ready ready)) return;
        CloudlyCloudResources clouds = ready.resources();
        if (closed) { clouds.close(); return; }
        VmaMappedBuffer buffer = null;
        ResourceOwner owner = null;
        try {
            int componentBytes = Math.multiplyExact(model.components().size(), CloudlyCloudComponentData.BYTE_SIZE);
            int byteCount = componentBytes + model.details().size() * CloudlyNoiseProfileData.BYTE_SIZE;
            buffer = VmaMappedBuffer.create(gpu, byteCount, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Cloudly source components");
            var bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < model.components().size(); index++) {
                SourceComponent component = model.components().get(index);
                CloudlyCloudResources.Texture texture = clouds.texture(component.textureId());
                new CloudlyCloudComponentData(component.centerAndDensity(), component.inverseSizeAndCos(),
                        component.uvScale(), component.uvOffset(), texture.sampledIndex().value(), texture.mipLevels(), component.layer(), 0,
                        new CloudlyCloudComponentData.Float4(texture.width(), texture.height(), texture.depth(), 0))
                        .write(bytes.slice(index * CloudlyCloudComponentData.BYTE_SIZE,
                                CloudlyCloudComponentData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
            }
            for (int index = 0; index < model.details().size(); index++) {
                model.details().get(index).data().write(bytes.slice(componentBytes + index * CloudlyNoiseProfileData.BYTE_SIZE,
                        CloudlyNoiseProfileData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
            }
            buffer.flush(0, byteCount);
            VmaMappedBuffer root = buffer;
            owner = resources.create(() -> new ResourceLifetime(root::close, clouds::close).close());
            Published revision = new Published(root, model.components().size(), clouds.samplerIndex().value(), owner);
            published = revision;
            LOGGER.info("ACE COMBAT clouds ready: {} volume components, {} uploaded textures",
                    revision.componentCount(), clouds.textures().size());
        } catch (RuntimeException | Error failure) {
            if (owner != null) owner.close();
            else {
                if (buffer != null) buffer.close();
                clouds.close();
            }
            LOGGER.error("Cloudly source component publication failed", failure);
        }
    }

    @Override public void record(PostEffectFrame frame) {
        if (model == null) return;
        OptionValues values = options.get();
        if (!values.get(ENABLED) || !(frame.view().medium() instanceof ViewMedium.Vacuum)) return;
        MinecraftSkyFrame captured = frames.get();
        if (captured == null) return;
        CloudlySkyLighting sky = atmosphere.get();
        if (sky == null || sky.frameIndex() != frame.frameIndex()) return;
        frame.retain(sky.owner());
        Published revision;
        synchronized (this) {
            if (closed || published == null) return;
            revision = published;
            frame.retain(revision.owner());
            frame.retain(shaderOwner);
        }
        var celestial = captured.celestial();
        double angle = celestial.sunAngleRadians();
        double tilt = Math.toRadians(values.get(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES));
        float sunX = (float) -Math.sin(angle);
        float sunY = (float) (Math.cos(tilt) * Math.cos(angle));
        float sunZ = (float) (Math.sin(tilt) * Math.cos(angle));
        float illuminance = model.sun() == null ? celestial.lighting().sunIlluminanceLux()
                : (float) model.sun().sunIlluminanceLux();
        float[] sunColor = model.sun() == null ? new float[]{1, 1, 1} : model.sun().sunColorAcesCg();
        double moonAngle = celestial.moonAngleRadians();
        float moonX = (float)-Math.sin(moonAngle);
        float moonY = (float)(Math.cos(tilt) * Math.cos(moonAngle));
        float moonZ = (float)(Math.sin(tilt) * Math.cos(moonAngle));
        double fraction = celestial.lighting().moonPhaseFixedFraction();
        float moonIlluminance = (float)((model.sun() == null ? celestial.lighting().moonIlluminanceLux()
                : model.sun().moonIlluminanceLux()) * (fraction + (1 - fraction)
                * Math.abs(celestial.moonPhaseIndex() - 4.0) / 4.0));
        var camera = frame.view().camera();
        float units = (float) frame.metersPerSceneUnit();
        float[] matrix = frame.cameraRelativeFromClip();
        float[] jitter = frame.traceJitter();
        Layer target = layerFor(frame);
        var output = target.image();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.malloc(CloudlyCloudPushData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            new CloudlyCloudPushData(revision.buffer().deviceAddressAt(0).value(), revision.componentCount(),
                    revision.samplerIndex(), output.storageIndex().value(),
                    0,
                    frame.primaryDepth().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    Math.round(values.get(SAMPLES)), column(matrix, 0), column(matrix, 4), column(matrix, 8), column(matrix, 12),
                    new Float4((float) (camera.x() * units), (float) ((camera.y() - celestial.seaLevel()) * units),
                            (float) (camera.z() * units), units),
                    new Float4(sunX, sunY, sunZ, illuminance),
                    new Float4(model.extinction(), model.phaseG1(), model.phaseG2(), model.phaseMix()),
                    new Float4(model.ambientScale(), model.phaseScale(), model.albedo(), frame.preExposure()),
                    new Float4(jitter[0], jitter[1], values.get(MAX_DISTANCE_KM) * 1000, model.shadowFirstStep()),
                    new Float4(model.shadowStepBase(), model.voxelStep(), model.targetOpticalDepth(), model.emptyStepMultiplier()),
                    new Float4(sunColor[0], sunColor[1], sunColor[2], 0),
                    new Float4(model.ambientColor()[0], model.ambientColor()[1], model.ambientColor()[2], 0),
                    new Float4(model.scatteringOrders(), model.scatteringAttenuation(), model.scatteringContribution(), model.scatteringEccentricity()),
                    new Float4(model.maxShapeLod(), model.details().size(), 0, 0),
                    new Float4(moonX, moonY, moonZ, moonIlluminance),
                    new Float4(model.sun() == null ? (float)Math.toRadians(values.get(SkyLutPass.SUN_ANGULAR_RADIUS_DEGREES))
                            : (float)model.sun().sunAngularRadiusRadians(),
                            (float)Math.toRadians(values.get(SkyLutPass.MOON_ANGULAR_RADIUS_DEGREES)), 0, 0),
                    sky.ambientIndex(), sky.transmittanceIndex(), sky.samplerIndex(), 0).write(push);
            int measurement = timing.begin(frame, output.width(), output.height());
            shader.dispatch(frame.commandBuffer(), push, (output.width() + 7) / 8, (output.height() + 7) / 8, 1);
            timing.end(frame, measurement);
        }
        layerFrame = frame.frameIndex();
    }

    private synchronized Layer layerFor(PostEffectFrame frame) {
        if (layer == null || layer.image().width() != frame.renderWidth() || layer.image().height() != frame.renderHeight()) {
            VmaImage2D image = VmaImage2D.create(gpu, frame.renderWidth(), frame.renderHeight(),
                    VK_FORMAT_R16G16B16A16_SFLOAT, "Cloudly separate scattering and transmittance");
            ResourceOwner owner;
            try { owner = resources.create(image::close); }
            catch (RuntimeException | Error failure) { image.close(); throw failure; }
            Layer previous = layer;
            layer = new Layer(image, owner);
            layerFrame = -1;
            ComputeSynchronization.initializeImages(frame.commandBuffer(), List.of(image));
            if (previous != null) previous.owner().close();
        }
        frame.retain(layer.owner());
        return layer;
    }

    /** Borrow the current frame's separate medium layer after tracing, retaining its image through composition. */
    synchronized Layer composedLayer(PostEffectFrame frame) {
        if (closed || layerFrame != frame.frameIndex()) return null;
        frame.retain(layer.owner());
        return layer;
    }

    record Layer(VmaImage2D image, ResourceOwner owner) { }

    private static Float4 column(float[] matrix, int offset) {
        return new Float4(matrix[offset], matrix[offset + 1], matrix[offset + 2], matrix[offset + 3]);
    }

    @Override public void close() {
        Published previous;
        GpuComputeJob pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            previous = published;
            published = null;
            pending = uploadJob;
        }
        new ResourceLifetime(() -> { if (worker != null) worker.interrupt(); },
                () -> { if (pending != null) pending.close(); },
                this::drainPreparation,
                () -> { if (previous != null) previous.owner().close(); },
                () -> { if (layer != null) layer.owner().close(); },
                () -> { if (shaderOwner != null) shaderOwner.close(); },
                () -> { if (timing != null) timing.close(); }).close();
    }

    /** Device allocations cease before session teardown; accepted GPU jobs retain their own resources. */
    private void drainPreparation() {
        if (worker == null) return;
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); }
            catch (InterruptedException cancellation) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private record Published(VmaMappedBuffer buffer, int componentCount, int samplerIndex, ResourceOwner owner) { }
    record SourceComponent(int textureId, CloudlyCloudComponentData.Float4 centerAndDensity,
                                   CloudlyCloudComponentData.Float4 inverseSizeAndCos,
                                   CloudlyCloudComponentData.Float4 uvScale, CloudlyCloudComponentData.Float4 uvOffset,
                                   int layer) { }

    /** Source values stay separate from adapter assumptions; no omitted native field receives an implicit value. */
    record SourceModel(List<SourceComponent> components, CloudlySkyPreset sun, float extinction,
                               float phaseG1, float phaseG2, float phaseMix, float phaseScale, float ambientScale,
                               float albedo, float shadowFirstStep, float shadowStepBase,
                               float[] ambientColor, float scatteringOrders, float scatteringAttenuation,
                               float scatteringContribution, float scatteringEccentricity,
                               float voxelStep, float targetOpticalDepth, float emptyStepMultiplier,
                               float maxShapeLod, List<CloudlyDetailProfile> details) {
        static SourceModel from(CloudlySourcePack source) {
            Map<String, Object> adapter = source.rendererAdapter();
            require(adapter, "target", "Caustica");
            require(adapter, "approximation", true);
            if (number(adapter, "densityChannel") != 0) throw new IllegalArgumentException("Only red-channel Cloudly density is supported");
            require(adapter, "positionUnits", "metres");
            require(adapter, "sourceUpAxis", "Z");
            require(adapter, "axisMap", "X,Z,-Y");
            require(adapter, "heightOrigin", "hostSeaLevel");
            require(adapter, "scaleMeaning", "fullSize");
            require(adapter, "rotationMeaning", "eulerZRadians");
            require(adapter, "componentTransforms", "absolute");
            require(adapter, "densityOperation", "maxUnion");
            require(adapter, "densityRemap", "identity");
            require(adapter, "extinctionUnits", "inverseMetres");
            Map<String, Object> sky = source.skyParameters();
            Map<String, Object> extinction = object(property(object(property(sky, "SigmaCloudAsAlbedoExtinction")), "Extinction_"));
            Map<String, Object> trace = object(property(sky, "TraceParams"));
            Map<String, Object> defaults = source.nativeDefaults().containsKey("skyParameters")
                    ? object(source.nativeDefaults().get("skyParameters")) : Map.of();
            float g1 = resolved(sky, defaults, "HGPhaseFunction_G1", number(adapter, "phaseG1"));
            float g2 = resolved(sky, defaults, "HGPhaseFunction_G2", number(adapter, "phaseG2"));
            float mix = resolved(sky, defaults, "HGPhaseFunction_MixFactor", number(adapter, "phaseMixFactor"));
            float albedo = number(adapter, "singleScatteringAlbedo");
            if (Math.abs(g1) >= 1 || Math.abs(g2) >= 1 || mix < 0 || mix > 1 || albedo < 0 || albedo > 1) {
                throw new IllegalArgumentException("Cloudly phase or single-scattering adapter value is outside its physical range");
            }
            float sigma = number(extinction, "Factor");
            float ambient = number(sky, "CloudSkyAmbientLightIntensity");
            float firstStep = number(trace, "SunRayStepFirstLen");
            float stepBase = number(trace, "SunRayStepBase");
            if (sigma < 0 || ambient < 0 || firstStep <= 0 || stepBase <= 0) {
                throw new IllegalArgumentException("Cloudly optical coefficients and shadow lengths must be nonnegative");
            }
            List<SourceComponent> components = new ArrayList<>();
            List<Map<String, Object>> authored = source.components();
            for (Map<String, Object> component : authored) {
                if (Boolean.TRUE.equals(property(component, "IsGroup")) || Boolean.TRUE.equals(property(component, "IsHidden"))) continue;
                if (number(component, "DensityOperation") != 0) throw new IllegalArgumentException("Only source density operation zero is supported");
                Map<String, Object> remap = object(property(component, "DensityRemap"));
                if (number(remap, "X") != 0 || number(remap, "Y") != 1) throw new IllegalArgumentException("Only identity source density remap is supported");
                Map<String, Object> rotation = object(property(component, "Rotation"));
                if (number(rotation, "X") != 0 || number(rotation, "Y") != 0) throw new IllegalArgumentException("Only source Z Euler rotation is supported");
                Map<String, Object> shared = object(property(component, "SharedData"));
                validateSharedData(shared, false);
                String placement = member(property(shared, "PlacementTypeOverride"));
                int parentId = Math.round(number(component, "ParentId"));
                if (parentId >= 0) {
                    Map<String, Object> parent = authored.get(parentId);
                    if (Boolean.TRUE.equals(property(parent, "IsHidden"))) continue;
                    Map<String, Object> parentShared = object(property(parent, "SharedData"));
                    validateSharedData(parentShared, true);
                    if (placement.equals("None")) placement = member(property(parentShared, "PlacementTypeOverride"));
                }
                int layer = switch (placement) {
                    case "Base" -> 0;
                    case "Alto" -> 1;
                    case "Cirrus", "CirrusFar" -> 2;
                    default -> throw new IllegalArgumentException("Unsupported source cloud placement " + placement);
                };
                float density = number(object(property(sky, "CloudLayer" + layer)), "CloudDensity");
                Map<String, Object> position = object(property(component, "Position"));
                Map<String, Object> size = object(property(component, "Scale"));
                float sx = number(size, "X"), sy = number(size, "Y"), sz = number(size, "Z");
                float angle = number(rotation, "Z");
                if (sx <= 0 || sy <= 0 || sz <= 0 || density < 0) throw new IllegalArgumentException("Cloudly source size or density is invalid");
                components.add(new SourceComponent(Math.round(number(component, "TextureId")),
                        vector(number(position, "X"), number(position, "Z"), -number(position, "Y"), density),
                        vector(1 / sx, 1 / sy, 1 / sz, (float) Math.cos(angle)),
                        vector(object(property(component, "UVScale")), (float) Math.sin(angle)),
                        vector(object(property(component, "UVOffset")), 0), layer));
            }
            if (components.isEmpty() || components.size() > MAX_COMPONENTS) throw new IllegalArgumentException("Cloudly requires 1..64 visible source components");
            CloudlySkyPreset sun = adapter.containsKey("sunAzimuthAxis") || adapter.containsKey("sunDirectionSource")
                    ? CloudlySkyPreset.from(source) : null;
            float[] ambientColor = new float[]{1, 1, 1};
            if ("sceneSkyLightSrgb".equals(adapter.get("ambientColorSource"))) {
                Map<String, Object> color = object(property(object(property(source.sceneParameters(), "skyLight")), "LightColor"));
                ambientColor = ColorSpaces.srgbToAcesCg(number(color, "R") / 255.0,
                        number(color, "G") / 255.0, number(color, "B") / 255.0);
            }
            float orders = resolved(sky, defaults, "CloudScatteringTimes", 1);
            float attenuation = resolved(sky, defaults, "MS_Attenuation", .5f);
            float contribution = resolved(sky, defaults, "MS_Contribution", .5f);
            float eccentricity = resolved(sky, defaults, "MS_EccentricityAttenuationForG", 1);
            if (orders < 1 || orders > 8 || attenuation < 0 || attenuation > 1 || contribution < 0 || contribution >= 1
                    || eccentricity < 0 || eccentricity > 1) throw new IllegalArgumentException("Invalid source scattering orders or weights");
            return new SourceModel(List.copyOf(components), sun, sigma, g1, g2, mix,
                    number(sky, "CloudPhaseFunctionScale_HG"), ambient, albedo, firstStep, stepBase,
                    ambientColor, orders, attenuation, contribution, eccentricity,
                    adapterNumber(adapter, "maxVoxelStep", .75f), adapterNumber(adapter, "targetOpticalDepth", .25f),
                    adapterNumber(adapter, "emptySpaceStepMultiplier", 4), adapterNumber(adapter, "maxShapeLod", 1),
                    CloudlyDetailProfile.from(source));
        }
    }

    private static CloudlyCloudComponentData.Float4 vector(Map<String, Object> source, float w) {
        return vector(number(source, "X"), number(source, "Y"), number(source, "Z"), w);
    }
    private static CloudlyCloudComponentData.Float4 vector(float x, float y, float z, float w) {
        return new CloudlyCloudComponentData.Float4(x, y, z, w);
    }
    private static void require(Map<String, Object> record, String key, Object value) {
        if (!value.equals(record.get(key))) throw new IllegalArgumentException("Cloudly requires explicit adapter " + key + "=" + value);
    }
    private static String member(Object value) {
        String text = (String) value;
        int separator = text.lastIndexOf("::");
        return separator < 0 ? text : text.substring(separator + 2);
    }
    private static void validateSharedData(Map<String, Object> shared, boolean group) {
        for (String feature : List.of("RainCloudStretch", "IsRainPillar", "IsAbsorptionMask", "StraightStretch",
                "StraightStretchCoversEntireMap", "KeepDensity")) {
            if (Boolean.TRUE.equals(property(shared, feature))) {
                throw new IllegalArgumentException("Cloudly camera adapter does not support source feature " + feature);
            }
        }
        Map<String, Object> shear = object(property(shared, "Shear"));
        if (number(shear, "X") != 0 || number(shear, "Y") != 0 || number(shear, "Z") != 0) {
            throw new IllegalArgumentException("Cloudly camera adapter does not support source shear");
        }
        if (!group && (!member(property(shared, "PlacementMode")).equals("Disable")
                || number(shared, "UserHeightOffsetInMeters") != 0)) {
            throw new IllegalArgumentException("Cloudly camera adapter requires authored absolute placement without height adjustment");
        }
    }
    private static float sourceOrAdapter(Map<String, Object> source, String key, Map<String, Object> adapter, String fallback) {
        Object value = optionalProperty(source, key);
        return value == null ? number(adapter, fallback) : finite((Number) value, key);
    }
    private static float resolved(Map<String, Object> serialized, Map<String, Object> defaults, String key, float fallback) {
        Object value = optionalProperty(serialized, key);
        if (value == null) value = optionalProperty(defaults, key);
        return value == null ? fallback : finite((Number) value, key);
    }
    private static float adapterNumber(Map<String, Object> adapter, String key, float fallback) {
        return resolved(adapter, Map.of(), key, fallback);
    }
    private static float number(Map<String, Object> source, String key) { return finite((Number) property(source, key), key); }
    private static float finite(Number value, String key) {
        float result = value.floatValue();
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Cloudly property is not finite: " + key);
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    private static Object property(Map<String, Object> record, String name) {
        Object value = optionalProperty(record, name);
        if (value == null) throw new IllegalArgumentException("Missing source or adapter property " + name);
        return value;
    }
    private static Object optionalProperty(Map<String, Object> record, String name) {
        Object result = null;
        Pattern qualified = Pattern.compile(Pattern.quote(name) + "\\[\\d+]");
        for (var entry : record.entrySet()) {
            if (entry.getKey().equals(name) || qualified.matcher(entry.getKey()).matches()) {
                if (result != null) throw new IllegalArgumentException("Ambiguous native property " + name);
                result = entry.getValue();
            }
        }
        return result;
    }
}
