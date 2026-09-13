package dev.comfyfluffy.caustica.renderer.presentation.fog;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.GpuImage;
import dev.comfyfluffy.caustica.api.vulkan.GpuImageDescriptorKind;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData;
import dev.comfyfluffy.caustica.renderer.presentation.gen.FogPushData.Float4;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ComputeSynchronization;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.ShaderObjectCompute;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteOrder;
import java.util.List;
import java.util.function.Supplier;

/** Single-scattering integration of a spatial medium followed by depth-aware reconstruction. */
public final class FogPass implements Pass<PostEffectFrame> {
    public static final PassId ID = new PassId("caustica", "fog");
    public static final String GROUP = "fog";
    public static final Option<Boolean> ENABLED = Option.bool("fog.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Float> DENSITY = Option.range("fog.density", 0.0f, 4.0f, 1.0f).inGroup(GROUP);
    public static final Option<Float> RESOLUTION_DIVISOR =
            Option.range("fog.resolution-divisor", 4.0f, 8.0f, 8.0f).inGroup(GROUP).step(4.0);
    public static final Option<Float> DEBUG = Option.range("fog.debug", 0.0f, 2.0f, 0.0f).inGroup(GROUP).step(1.0);
    public static final List<Option<?>> OPTIONS = List.of(ENABLED, DENSITY, RESOLUTION_DIVISOR, DEBUG);
    private static final int STEPS = 48;
    private static final int VISIBILITY_SAMPLES = (STEPS + 3) / 4;

    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final Supplier<OptionValues> options;
    private final Supplier<FogFrame> input;
    private final ShaderObjectCompute shader;
    private FogField uploaded;
    private VmaMappedBuffer fieldBuffer;
    private ResourceOwner fieldOwner;
    private VmaImage2D fog;
    private VmaImage2D distance;
    private VmaMappedBuffer visibilityRays;
    private VmaMappedBuffer visibilityResults;
    private ResourceOwner imagesOwner;

    public FogPass(PostEffectSetup setup, ResourceFactory resources, Supplier<OptionValues> options,
                   Supplier<FogFrame> input) {
        this.gpu = setup.gpu();
        this.resources = resources;
        this.options = options;
        this.input = input;
        this.shader = ShaderObjectCompute.load(gpu, FogPass.class, "/caustica/shaders/pipelines/fog/main.comp.spv");
    }

    @Override
    public void record(PostEffectFrame frame) {
        OptionValues values = options.get();
        if (!values.get(ENABLED)) return;
        // Interior media already supply transport; the outdoor field cannot describe their boundary crossings.
        if (!(frame.view().medium() instanceof ViewMedium.Vacuum)) return;
        FogFrame medium = input.get();
        if (medium == null || values.get(DENSITY) == 0.0f) return;
        if (uploaded != medium.field()) upload(medium.field());
        int divisor = Math.round(values.get(RESOLUTION_DIVISOR));
        int width = Math.max(1, (frame.renderWidth() + divisor - 1) / divisor);
        int height = Math.max(1, (frame.renderHeight() + divisor - 1) / divisor);
        boolean rebuilt = ensureImages(width, height);
        frame.retain(fieldOwner);
        frame.retain(imagesOwner);
        if (rebuilt) ComputeSynchronization.initializeImages(frame.commandBuffer(), List.of(fog, distance));
        GpuImage scene = frame.sceneColor();
        GpuImage target = frame.acquireSceneColorOutput();
        dispatch(frame, medium, values.get(DENSITY), values.get(DEBUG), scene,
                fog.storageIndex().value(), fog.width(), fog.height(), 2);
        frame.traceVisibility(visibilityRays.deviceAddressAt(0), visibilityResults.deviceAddressAt(0),
                width * VISIBILITY_SAMPLES, height);
        dispatch(frame, medium, values.get(DENSITY), values.get(DEBUG), scene,
                fog.storageIndex().value(), fog.width(), fog.height(), 0);
        ComputeSynchronization.betweenDispatches(frame.commandBuffer());
        dispatch(frame, medium, values.get(DENSITY), values.get(DEBUG), scene,
                target.descriptor(GpuImageDescriptorKind.STORAGE).index().value(), target.width(), target.height(), 1);
    }

    private void upload(FogField field) {
        float[] voxels = field.voxels();
        for (int i = 3; i < voxels.length; i += 4) voxels[i] = (float) (voxels[i] - field.originY());
        VmaMappedBuffer replacement = VmaMappedBuffer.create(gpu, (long) voxels.length * 4,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, "Fog spatial field");
        ResourceOwner owner;
        try {
            replacement.mapped().order(ByteOrder.nativeOrder()).asFloatBuffer().put(voxels);
            replacement.flush(0, replacement.byteSize());
            owner = resources.create(replacement::close);
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        if (fieldOwner != null) fieldOwner.close();
        fieldOwner = owner;
        fieldBuffer = replacement;
        uploaded = field;
    }

    private boolean ensureImages(int width, int height) {
        if (fog != null && fog.width() == width && fog.height() == height) return false;
        VmaImage2D replacement = VmaImage2D.create(gpu, width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "Fog scattering and transmittance");
        VmaImage2D replacementDistance;
        try {
            replacementDistance = VmaImage2D.create(gpu, width, height, VK10.VK_FORMAT_R32_SFLOAT,
                    "Fog integration distance");
        } catch (RuntimeException | Error failure) {
            replacement.close();
            throw failure;
        }
        ResourceOwner owner;
        VmaMappedBuffer rays = null;
        VmaMappedBuffer results = null;
        try {
            long rayCount = (long) width * height * VISIBILITY_SAMPLES;
            rays = VmaMappedBuffer.create(gpu, rayCount * 48, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Fog visibility rays");
            results = VmaMappedBuffer.create(gpu, rayCount * 16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Fog visibility results");
            var rayOwner = rays;
            var resultOwner = results;
            owner = resources.create(() -> new ResourceLifetime(replacement::close, replacementDistance::close,
                    rayOwner::close, resultOwner::close).close());
        } catch (RuntimeException | Error failure) {
            if (rays != null) rays.close();
            if (results != null) results.close();
            new ResourceLifetime(replacement::close, replacementDistance::close).close();
            throw failure;
        }
        if (imagesOwner != null) imagesOwner.close();
        imagesOwner = owner;
        fog = replacement;
        distance = replacementDistance;
        visibilityRays = rays;
        visibilityResults = results;
        return true;
    }

    private void dispatch(PostEffectFrame frame, FogFrame medium, float density, float debug, GpuImage scene,
                          int targetIndex, int targetWidth, int targetHeight, int mode) {
        float[] matrix = frame.cameraRelativeFromClip();
        float[] jitter = frame.traceJitter();
        float[] tlasCamera = frame.cameraTlasPosition();
        var camera = frame.view().camera();
        FogField field = medium.field();
        float exposure = frame.preExposure();
        float[] lightDirection = medium.lightDirection();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.malloc(FogPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
            new FogPushData(fieldBuffer.deviceAddressAt(0).value(),
                    visibilityRays.deviceAddressAt(0).value(), visibilityResults.deviceAddressAt(0).value(),
                    targetIndex,
                    scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    frame.primaryDepth().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    fog.sampledIndex().value(), mode == 0 ? distance.storageIndex().value() : distance.sampledIndex().value(),
                    frame.entrySceneTlasDescriptor().index().value(), mode, STEPS,
                    column(matrix, 0), column(matrix, 4), column(matrix, 8), column(matrix, 12),
                    new Float4(wrapped(camera.x()), wrapped(camera.y()), wrapped(camera.z()), medium.windPhase()),
                    new Float4((float) (field.originX() - camera.x()), (float) (field.originY() - camera.y()),
                            (float) (field.originZ() - camera.z()), field.spacing()),
                    new Float4(field.sizeX(), field.sizeY(), field.sizeZ(), jitter[0]),
                    new Float4(0.003f * density * medium.timeDensity() * (float) frame.metersPerSceneUnit(),
                            (float) (medium.layerHeight() - camera.y()), medium.heightFalloff(),
                            256.0f / (float) frame.metersPerSceneUnit()),
                    new Float4(lightDirection[0], lightDirection[1], lightDirection[2], debug),
                    vector(medium.lightRadiance(), exposure),
                    vector(medium.ambientRadiance(), exposure),
                    new Float4(tlasCamera[0], tlasCamera[1], tlasCamera[2], jitter[1])).write(push);
            shader.dispatch(frame.commandBuffer(), push, (targetWidth + 7) / 8, (targetHeight + 7) / 8, 1);
        }
    }

    private static Float4 column(float[] m, int offset) {
        return new Float4(m[offset], m[offset + 1], m[offset + 2], m[offset + 3]);
    }

    private static float wrapped(double coordinate) {
        return (float) (coordinate - Math.floor(coordinate / 4096.0) * 4096.0);
    }

    private static Float4 vector(float[] v, float scale) {
        return new Float4(v[0] * scale, v[1] * scale, v[2] * scale, 0);
    }

    @Override
    public void close() {
        new ResourceLifetime(() -> { if (fieldOwner != null) fieldOwner.close(); },
                () -> { if (imagesOwner != null) imagesOwner.close(); }, shader::close).close();
    }
}
