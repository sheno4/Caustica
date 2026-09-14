package dev.comfyfluffy.caustica.renderer.presentation.fog;

import dev.comfyfluffy.caustica.api.pass.Pass;
import dev.comfyfluffy.caustica.api.pass.PassId;
import dev.comfyfluffy.caustica.api.pass.PostEffectFrame;
import dev.comfyfluffy.caustica.api.pass.PostEffectSetup;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.scene.SceneId;
import org.joml.Matrix4f;
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
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;

import java.nio.ByteOrder;
import java.util.List;
import java.util.function.Supplier;

/** Cumulative single scattering resolved independently at each output pixel's physical depth. */
public final class FogPass implements Pass<PostEffectFrame> {
    public static final PassId ID = new PassId("caustica", "fog");
    public static final String GROUP = "fog";
    public static final Option<Boolean> ENABLED = Option.bool("fog.enabled", true).inGroupAsHeader(GROUP);
    public static final Option<Float> DENSITY = Option.range("fog.density", 0.0f, 4.0f, 1.0f).inGroup(GROUP);
    public static final Option<Float> RESOLUTION_DIVISOR =
            Option.range("fog.resolution-divisor", 4.0f, 8.0f, 8.0f).inGroup(GROUP).step(4.0);
    public static final Option<Float> SAMPLES = Option.range("fog.samples", 32.0f, 512.0f, 64.0f).inGroup(GROUP).step(32.0);
    public static final Option<Float> DEBUG = Option.range("fog.debug", 0.0f, 2.0f, 0.0f).inGroup(GROUP).step(1.0);
    public static final List<Option<?>> OPTIONS = List.of(ENABLED, DENSITY, RESOLUTION_DIVISOR, SAMPLES, DEBUG);
    private int allocatedSteps;
    private static final int VISIBILITY_SAMPLES = 8;

    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final Supplier<OptionValues> options;
    private final ShaderObjectCompute shader;
    private VmaImage2D fog;
    private PrefixBuffer prefix;
    private PrefixBuffer[] history;
    private long previousFrameIndex = Long.MIN_VALUE;
    private Camera previousCamera;
    private SceneId previousScene;
    private double previousUnits;
    private float previousDensity;
    private float previousDebug;
    private float[] previousFromClip;
    private final float[] previousClip = new float[16];
    private VmaMappedBuffer visibilityRays;
    private VmaMappedBuffer visibilityResults;
    private ResourceOwner imagesOwner;

    public FogPass(PostEffectSetup setup, ResourceFactory resources, Supplier<OptionValues> options) {
        this.gpu = setup.gpu();
        this.resources = resources;
        this.options = options;
        this.shader = ShaderObjectCompute.load(gpu, FogPass.class, "/caustica/shaders/pipelines/fog/main.comp.spv");
    }

    @Override
    public void record(PostEffectFrame frame) {
        OptionValues values = options.get();
        // Interior media already supply transport; the outdoor field cannot describe their boundary crossings.
        if (!(frame.view().medium() instanceof ViewMedium.Vacuum)) return;
        var medium = frame.view().spatialMedium();
        if (medium == null || medium.bindingData().type() != FogVolume.BINDING_DATA
                || !frame.spatialMediumActive()) return;
        long binding = medium.bindingData().bits();
        int divisor = Math.round(values.get(RESOLUTION_DIVISOR));
        int width = Math.max(1, (frame.renderWidth() + divisor - 1) / divisor);
        int height = Math.max(1, (frame.renderHeight() + divisor - 1) / divisor);
        int steps = Math.round(values.get(SAMPLES));
        boolean rebuilt = ensureImages(width, height, steps);
        var camera = frame.view().camera();
        boolean reuse = !rebuilt && previousFrameIndex == frame.frameIndex() - 1
                && frame.view().entryScene().equals(previousScene)
                && frame.metersPerSceneUnit() == previousUnits
                && values.get(DENSITY) == previousDensity && values.get(DEBUG) == previousDebug;
        if (reuse) {
            new Matrix4f().set(previousFromClip).invert().translate(
                    (float) (camera.x() - previousCamera.x()), (float) (camera.y() - previousCamera.y()),
                    (float) (camera.z() - previousCamera.z())).get(previousClip);
        } else {
            java.util.Arrays.fill(previousClip, 0.0f);
        }
        previousFrameIndex = frame.frameIndex();
        previousCamera = camera;
        previousScene = frame.view().entryScene();
        previousUnits = frame.metersPerSceneUnit();
        previousDensity = values.get(DENSITY);
        previousDebug = values.get(DEBUG);
        previousFromClip = frame.cameraRelativeFromClip();
        frame.retain(imagesOwner);
        if (rebuilt) ComputeSynchronization.initializeImages(frame.commandBuffer(), List.of(fog));
        ComputeSynchronization.betweenDispatches(frame.commandBuffer());
        GpuImage scene = frame.sceneColor();
        GpuImage target = frame.acquireSceneColorOutput();
        // Reuse a small ray batch while accumulating front-to-back transport in the fog image.
        for (int firstStep = 0; firstStep < steps; firstStep += VISIBILITY_SAMPLES) {
            dispatch(frame, binding, values.get(DEBUG), scene,
                    fog.storageIndex().value(), fog.width(), fog.height(), 2, firstStep);
            frame.traceVisibility(visibilityRays.deviceAddressAt(0), visibilityResults.deviceAddressAt(0),
                    width * VISIBILITY_SAMPLES, height);
            dispatch(frame, binding, values.get(DEBUG), scene,
                    fog.storageIndex().value(), fog.width(), fog.height(), 0, firstStep);
            ComputeSynchronization.betweenDispatches(frame.commandBuffer());
        }
        dispatch(frame, binding, values.get(DEBUG), scene,
                target.descriptor(GpuImageDescriptorKind.STORAGE).index().value(), target.width(), target.height(), 1, 0);
    }

    private boolean ensureImages(int width, int height, int steps) {
        if (fog != null && fog.width() == width && fog.height() == height && allocatedSteps == steps) return false;
        VmaImage2D replacement = VmaImage2D.create(gpu, width, height, VK10.VK_FORMAT_R32G32B32A32_SFLOAT,
                "Fog scattering and transmittance");
        ResourceOwner owner;
        VmaMappedBuffer rays = null;
        VmaMappedBuffer results = null;
        PrefixBuffer replacementPrefix = null;
        PrefixBuffer firstHistory = null;
        PrefixBuffer secondHistory = null;
        try {
            long rayCount = (long) width * height * VISIBILITY_SAMPLES;
            rays = VmaMappedBuffer.create(gpu, rayCount * 48, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Fog visibility rays");
            results = VmaMappedBuffer.create(gpu, rayCount * 16, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    "Fog visibility results");
            replacementPrefix = PrefixBuffer.create(gpu, (long) width * height * (steps + 1L) * 16L);
            long historyBytes = 80L + (long) width * height * steps * 16L;
            firstHistory = PrefixBuffer.create(gpu, historyBytes);
            secondHistory = PrefixBuffer.create(gpu, historyBytes);
            var prefixOwner = replacementPrefix;
            var firstHistoryOwner = firstHistory;
            var secondHistoryOwner = secondHistory;
            var rayOwner = rays;
            var resultOwner = results;
            owner = resources.create(() -> new ResourceLifetime(replacement::close, prefixOwner::close,
                    firstHistoryOwner::close, secondHistoryOwner::close, rayOwner::close, resultOwner::close).close());
        } catch (RuntimeException | Error failure) {
            if (rays != null) rays.close();
            if (results != null) results.close();
            if (replacementPrefix != null) replacementPrefix.close();
            if (firstHistory != null) firstHistory.close();
            if (secondHistory != null) secondHistory.close();
            replacement.close();
            throw failure;
        }
        if (imagesOwner != null) imagesOwner.close();
        imagesOwner = owner;
        fog = replacement;
        prefix = replacementPrefix;
        history = new PrefixBuffer[]{firstHistory, secondHistory};
        allocatedSteps = steps;
        visibilityRays = rays;
        visibilityResults = results;
        return true;
    }

    private void dispatch(PostEffectFrame frame, long binding, float debug, GpuImage scene,
                          int targetIndex, int targetWidth, int targetHeight, int mode, int firstStep) {
        float[] matrix = frame.cameraRelativeFromClip();
        float[] jitter = frame.traceJitter();
        float[] tlasCamera = frame.cameraTlasPosition();
        var spatial = frame.view().spatialMedium();
        var camera = frame.view().camera();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var push = stack.malloc(FogPushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
            new FogPushData(binding,
                    visibilityRays.deviceAddressAt(0).value(), visibilityResults.deviceAddressAt(0).value(), prefix.address(),
                    targetIndex,
                    scene.descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    frame.primaryDepth().descriptor(GpuImageDescriptorKind.SAMPLED).index().value(),
                    fog.sampledIndex().value(),
                    mode, allocatedSteps, Math.round(debug),
                    column(matrix, 0), column(matrix, 4), column(matrix, 8), column(matrix, 12),
                    new Float4(tlasCamera[0], tlasCamera[1], tlasCamera[2], (float) (0.001 / frame.metersPerSceneUnit())),
                    new Float4((float) (camera.x() - spatial.originX()), (float) (camera.y() - spatial.originY()),
                            (float) (camera.z() - spatial.originZ()), Float.intBitsToFloat((int) frame.frameIndex())),
                    new Float4(jitter[0], jitter[1], frame.preExposure(), firstStep),
                    history[(int) (frame.frameIndex() & 1)].address(), history[1 - (int) (frame.frameIndex() & 1)].address(),
                    column(previousClip, 0), column(previousClip, 4), column(previousClip, 8), column(previousClip, 12)).write(push);
            shader.dispatch(frame.commandBuffer(), push, (targetWidth + 7) / 8, (targetHeight + 7) / 8, 1);
        }
    }

    private static Float4 column(float[] m, int offset) {
        return new Float4(m[offset], m[offset + 1], m[offset + 2], m[offset + 3]);
    }

    /** Device-local cumulative transport; its shared image owner retires every recorded GPU use. */
    private record PrefixBuffer(long allocator, long buffer, long allocation, long address) implements AutoCloseable {
        static PrefixBuffer create(GpuDevice gpu, long bytes) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var info = VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
                        .usage(VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                var allocationInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                var buffer = stack.mallocLong(1);
                var allocation = stack.mallocPointer(1);
                int result = Vma.vmaCreateBuffer(gpu.vmaAllocator(), info, allocationInfo, buffer, allocation, null);
                if (result != VK10.VK_SUCCESS) throw new IllegalStateException("Fog prefix allocation failed: " + result);
                long address = VK12.vkGetBufferDeviceAddress(gpu.vk(),
                        VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer.get(0)));
                return new PrefixBuffer(gpu.vmaAllocator(), buffer.get(0), allocation.get(0), address);
            }
        }

        @Override public void close() { Vma.vmaDestroyBuffer(allocator, buffer, allocation); }
    }

    @Override
    public void close() {
        new ResourceLifetime(() -> { if (imagesOwner != null) imagesOwner.close(); }, shader::close).close();
    }
}
