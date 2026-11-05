/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.comfyfluffy.cloudly;

import dev.comfyfluffy.cloudly.api.pass.PostEffectFrame;
import dev.comfyfluffy.cloudly.api.resource.ResourceFactory;
import dev.comfyfluffy.cloudly.api.resource.ResourceOwner;
import dev.comfyfluffy.cloudly.api.view.Camera;
import dev.comfyfluffy.cloudly.api.view.SceneView;
import dev.comfyfluffy.cloudly.api.vulkan.*;
import dev.comfyfluffy.cloudly.internal.*;
import dev.comfyfluffy.cloudly.settings.Option;
import dev.comfyfluffy.cloudly.settings.OptionValues;
import dev.comfyfluffy.cloudly.sky.*;
import dev.comfyfluffy.cloudly.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Standalone native Cloudly compositor. All public calls belong to the host's render thread.
 * Shared images are imported into GL without a CPU image copy; the private Vulkan queue owns
 * the original density bake, atmosphere, cloud tracing and reconstruction programs.
 */
public final class CloudlyRuntime implements AutoCloseable {
    /** Exported dedicated Vulkan allocation. The Win32 HANDLE stays owned by this runtime. */
    public record Image(long memoryHandle, long allocationSize, long memoryOffset,
                        int width, int height, int vkFormat) { }
    /** GL signals inputReady after writing inputs, then waits outputReady before sampling output. */
    public record SharedFrame(Image colorInput, Image depthInput, Image output,
                              long inputReadyHandle, long outputReadyHandle) { }
    public record Settings(boolean enabled, String scene, int heightOffsetBlocks, float maxDistanceKm) {
        public Settings {
            Objects.requireNonNull(scene, "scene");
            scene = scene.toLowerCase(Locale.ROOT);
            CloudlyOptions.Scene.valueOf(scene.toUpperCase(Locale.ROOT));
            if (heightOffsetBlocks < -16000 || heightOffsetBlocks > 16000) throw new IllegalArgumentException("Cloud height offset outside -16000..16000");
            if (!Float.isFinite(maxDistanceKm) || maxDistanceKm < 1 || maxDistanceKm > 200) throw new IllegalArgumentException("Cloud distance outside 1..200 km");
        }
    }
    public record Lighting(float sunIlluminanceLux, float moonIlluminanceLux,
                           float blockEmissionLuminanceCdM2, float nightAirglowLuminanceCdM2,
                           float starLuminanceCdM2, float moonPhaseFixedFraction) {
        public static Lighting vanilla() { return new Lighting(128000, 5, 2000, .002f, 10, .1f); }
    }
    public record SkyFrame(float sunAngleRadians, float moonAngleRadians, float starAngleRadians,
                           float starBrightness, int moonPhase, int seaLevel, float rain, float thunder,
                           double terrainFadeStartMeters, double terrainFadeEndMeters, Lighting lighting) {
        public SkyFrame { Objects.requireNonNull(lighting, "lighting"); }
        /** The host already fades its final image; an additional terrain fade is disabled. */
        public static SkyFrame vanilla(float sunAngleRadians, float starBrightness, int moonPhase,
                                       int seaLevel, float rain, float thunder) {
            return new SkyFrame(sunAngleRadians, sunAngleRadians + (float)Math.PI, sunAngleRadians,
                    starBrightness, moonPhase, seaLevel, rain, thunder, 0, 0, Lighting.vanilla());
        }
        private MinecraftSkyFrame captured(double cameraY) {
            var l = lighting;
            var calibration = new MinecraftLightingCalibration(l.sunIlluminanceLux, l.moonIlluminanceLux,
                    l.blockEmissionLuminanceCdM2, l.nightAirglowLuminanceCdM2, l.starLuminanceCdM2, l.moonPhaseFixedFraction);
            return new MinecraftSkyFrame(new MinecraftCelestialFrame(sunAngleRadians, moonAngleRadians,
                    starAngleRadians, starBrightness, moonPhase, seaLevel, cameraY, 1, calibration), rain, thunder,
                    new MinecraftSkyFrame.TerrainFade(terrainFadeStartMeters, terrainFadeEndMeters));
        }
    }

    private final CloudlyDevice device;
    private final GpuDevice gpu;
    private final GpuComputeQueue compute;
    private final ResourceFactory resources = new ResourceFactory();
    private final CloudlyScenePass scenes;
    private Settings settings = new Settings(true, "hangar", 0, 200);
    private MinecraftSkyFrame captured;
    private SharedImage color, depth, output;
    private BorrowedGpuImage colorView, depthView, outputView;
    private ResourceOwner imageOwner;
    private CloudlyDevice.Submission submission;
    private NativeFrame inFlight;
    private SharedFrame shared;
    private boolean closed;

    /** Creates a Vulkan compute device on the physical GPU identified by GL_DEVICE_UUID_EXT. */
    public static CloudlyRuntime create(byte[] deviceUuid) { return new CloudlyRuntime(new CloudlyDevice(deviceUuid)); }
    private CloudlyRuntime(CloudlyDevice device) {
        this.device = device;
        gpu = new GpuDevice(device);
        compute = new GpuComputeQueue(gpu);
        scenes = new CloudlyScenePass(gpu, compute, resources, this::options, () -> captured);
    }
    public void setSettings(Settings settings) { requireOpen(); this.settings = Objects.requireNonNull(settings); }
    /**
     * Begins a host frame and publishes completed scene preparation before the host submits
     * its matching lights. Call before rendering each frame, including before the first resize.
     */
    public boolean isReady() {
        requireOpen();
        retireFrame();
        compute.publishCompleted();
        scenes.update();
        return scenes.resourcesReady();
    }
    /** The authored sun-path tilt of the currently published scene, in host world axes. */
    public float noonTiltDegrees() {
        requireOpen();
        var preset = scenes.skyPreset();
        if (preset == null) throw new IllegalStateException("Cloudly scene is not ready");
        return (float) preset.noonTiltDegrees();
    }
    /** Invalidates cloud and camera history without changing the external semaphore state. */
    public void resetHistory() { requireOpen(); retireFrame(); scenes.invalidateHistory(); }

    /**
     * Recreates exportable RGBA16F display-linear BT.709 colour and R32F reverse-depth images.
     * The host must finish GL use and delete previous imported GL objects before calling resize.
     */
    public SharedFrame resize(int width, int height) {
        requireOpen();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Positive cloud image extent required");
        if (shared != null && color.width() == width && color.height() == height) return shared;
        retireFrame();
        releaseImages();
        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try {
            color = device.createSharedImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage);
            depth = device.createSharedImage(width, height, VK_FORMAT_R32_SFLOAT, usage);
            output = device.createSharedImage(width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage);
            colorView = new BorrowedGpuImage(gpu, color, color.format());
            depthView = new BorrowedGpuImage(gpu, depth, depth.format());
            outputView = new BorrowedGpuImage(gpu, output, output.format());
            var cv = colorView; var dv = depthView; var ov = outputView;
            imageOwner = resources.create(() -> new ResourceLifetime(cv::close, dv::close, ov::close).close());
            shared = new SharedFrame(export(color), export(depth), export(output), device.inputReadyHandle(), device.outputReadyHandle());
            scenes.invalidateHistory();
            return shared;
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, this::releaseImages);
            throw failure;
        }
    }
    private static Image export(SharedImage image) {
        return new Image(image.memoryHandle(), image.allocationSize(), image.memoryOffset(), image.width(), image.height(), image.format());
    }

    /**
     * Waits the GL input-ready semaphore and signals output-ready after native composition.
     * inverseClip is a column-major inverse projection/rotation matrix, with Vulkan downward Y
     * and reverse Z in [0,1]; zero depth denotes environment. The host colour is already
     * display-linear. It is lifted and display mapped around the native scene-linear compositor;
     * this display seam does not recover Remix's original HDR scene or world-lighting inputs.
     * Consumes the scene published by isReady() at frame begin; it cannot promote a different
     * scene after the host has submitted its lights for this frame.
     */
    public void render(long index, double timeSeconds, double cameraX, double cameraY, double cameraZ,
                       float[] inverseClip, float jitterX, float jitterY, float preExposure, SkyFrame sky) {
        requireOpen();
        Objects.requireNonNull(sky, "sky");
        if (shared == null) throw new IllegalStateException("Resize shared images before rendering");
        if (inverseClip == null || inverseClip.length != 16) throw new IllegalArgumentException("inverseClip requires 16 floats");
        if (!Float.isFinite(preExposure) || preExposure <= 0) throw new IllegalArgumentException("Positive finite exposure required");
        retireFrame();
        captured = sky.captured(cameraY);
        var preset = scenes.skyPreset();
        float exposure = (float)(preExposure * 10.0 / Math.max(1.0, preset == null ? 100000 : preset.sunIlluminanceLux()));
        var frame = new NativeFrame(index, timeSeconds, new Camera(cameraX, cameraY, cameraZ),
                inverseClip, jitterX, jitterY, exposure);
        try {
            submission = device.submitFrame(commands -> {
                frame.commands = commands;
                scenes.record(frame);
                if (!frame.acquired) copyInput(commands);
            }, color, depth, output);
            inFlight = frame;
            frame.submitted();
        } catch (RuntimeException | Error failure) {
            ResourceLifetime.closeAfterFailure(failure, () -> {
                if (submission != null) retireFrame(); else frame.close();
            });
            throw failure;
        }
    }
    private void copyInput(VkCommandBuffer commands) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.extent().set(color.width(), color.height(), 1);
            vkCmdCopyImage(commands, color.image(), VK_IMAGE_LAYOUT_GENERAL, output.image(), VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }
    private OptionValues options() {
        Settings selected = settings;
        return new OptionValues() {
            @SuppressWarnings("unchecked")
            @Override public <T> T get(Option<T> option) {
                if (option.equals(CloudlyOptions.ENABLED)) return (T)Boolean.valueOf(selected.enabled);
                if (option.equals(CloudlyOptions.SCENE)) return (T)CloudlyOptions.Scene.valueOf(selected.scene.toUpperCase(Locale.ROOT));
                if (option.equals(CloudlyOptions.HEIGHT_OFFSET_BLOCKS)) return (T)Integer.valueOf(selected.heightOffsetBlocks);
                if (option.equals(CloudlyOptions.MAX_DISTANCE_KM)) return (T)Float.valueOf(selected.maxDistanceKm);
                if (option.equals(CloudlyOptions.SUN_NOON_SOUTH_TILT_DEGREES)) {
                    var preset = scenes.skyPreset();
                    return (T)Float.valueOf(preset == null ? 30 : (float)preset.noonTiltDegrees());
                }
                throw new IllegalArgumentException("Unknown Cloudly option " + option.id());
            }
        };
    }
    private void retireFrame() {
        if (submission == null) return;
        submission.close();
        submission = null;
        var frame = inFlight; inFlight = null;
        if (frame != null) frame.close();
    }
    private void releaseImages() {
        var owner = imageOwner; imageOwner = null;
        var cv = colorView; colorView = null;
        var dv = depthView; depthView = null;
        var ov = outputView; outputView = null;
        var c = color; color = null;
        var d = depth; depth = null;
        var o = output; output = null;
        shared = null;
        new ResourceLifetime(() -> {
            if (owner != null) owner.close();
            else new ResourceLifetime(() -> { if (cv != null) cv.close(); },
                    () -> { if (dv != null) dv.close(); }, () -> { if (ov != null) ov.close(); }).close();
        }, () -> { if (c != null) c.close(); }, () -> { if (d != null) d.close(); }, () -> { if (o != null) o.close(); }).close();
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Cloudly runtime is closed"); }
    /** The host deletes GL imports first. All native commands and resource claims then drain. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        new ResourceLifetime(this::retireFrame, scenes::close, compute::close, this::releaseImages, gpu::close, device::close).close();
    }

    private final class NativeFrame implements PostEffectFrame, AutoCloseable {
        private VkCommandBuffer commands;
        private final long index;
        private final double time;
        private final SceneView view;
        private final float[] inverseClip, jitter;
        private final float exposure;
        private final List<Runnable> accepted = new ArrayList<>(), retired = new ArrayList<>();
        private boolean acquired;
        NativeFrame(long index, double time, Camera camera, float[] inverseClip, float jitterX, float jitterY, float exposure) {
            this.index = index; this.time = time; view = new SceneView(camera);
            this.inverseClip = inverseClip.clone(); jitter = new float[] {jitterX, jitterY}; this.exposure = exposure;
        }
        public VkCommandBuffer commandBuffer() { return commands; }
        public long frameIndex() { return index; }
        public SceneView view() { return view; }
        public double timeSeconds() { return time; }
        public double metersPerSceneUnit() { return 1; }
        public int renderWidth() { return color.width(); }
        public int renderHeight() { return color.height(); }
        public GpuImage sceneColor() { return colorView; }
        public GpuImage primaryDepth() { return depthView; }
        public GpuImage depth() { return depthView; }
        public float[] cameraRelativeFromClip() { return inverseClip.clone(); }
        public float[] traceJitter() { return jitter.clone(); }
        public float preExposure() { return exposure; }
        public boolean spatialMediumActive() { return false; }
        public GpuImage exposureImage() { throw unsupported(); }
        public GpuAccelerationStructureDescriptor entrySceneTlasDescriptor() { throw unsupported(); }
        public float[] cameraTlasPosition() { throw unsupported(); }
        public void traceVisibility(VulkanDeviceAddress rays, VulkanDeviceAddress results, int width, int height) { throw unsupported(); }
        public void sampleVolumeLighting(VulkanDeviceAddress samples, VulkanDeviceAddress results, int width, int height) { throw unsupported(); }
        private UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Standalone cloud composition does not trace the host scene"); }
        public GpuImage acquireSceneColorOutput() {
            if (!acquired) { retain(imageOwner); acquired = true; }
            return outputView;
        }
        public void retain(ResourceOwner owner) { var claim = owner.retain(); retired.add(claim::close); }
        public void whenSubmitted(Runnable callback) { accepted.add(callback); }
        public void whenRetired(Runnable callback) { retired.add(callback); }
        void submitted() { var callbacks = List.copyOf(accepted); accepted.clear(); runAll(callbacks); }
        public void close() { accepted.clear(); var callbacks = List.copyOf(retired); retired.clear(); runAll(callbacks); }
        private static void runAll(List<Runnable> callbacks) {
            new ResourceLifetime(callbacks.toArray(Runnable[]::new)).close();
        }
    }
}
