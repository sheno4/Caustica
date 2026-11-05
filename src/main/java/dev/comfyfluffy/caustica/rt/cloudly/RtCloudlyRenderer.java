package dev.comfyfluffy.caustica.rt.cloudly;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.minecraft.rendering.*;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.SkyLutPass;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.*;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtLookPackage;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.settings.Option;
import dev.comfyfluffy.caustica.settings.OptionValues;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import java.util.Locale;

/** Native scene lifecycle and the bridge into the host's scene-linear reconstruction seam. */
public final class RtCloudlyRenderer implements AutoCloseable {
    public static final double METERS_PER_BLOCK = 1.0;
    private final RtContext context;
    private final GpuDevice gpu;
    private final GpuComputeQueue compute;
    private final ResourceFactory resources = new ResourceFactory();
    private final CloudlyScenePass scenes;
    private MinecraftSkyFrame captured;
    private Level capturedLevel;
    private RtImage output;
    private BorrowedGpuImage outputView;
    private ResourceOwner outputOwner;
    private OptionValues values;

    public RtCloudlyRenderer(RtContext context) {
        this.context = context;
        this.gpu = new GpuDevice(context);
        this.compute = new GpuComputeQueue(gpu);
        this.scenes = new CloudlyScenePass(gpu, compute, resources, () -> values, () -> captured);
    }

    /** Publish asynchronous resources before the host reserves this frame's graphics dependencies. */
    public void prepare(double cameraY) {
        compute.publishCompleted();
        Level level = Minecraft.getInstance().level;
        if (level != capturedLevel) {
            scenes.invalidateHistory();
            capturedLevel = level;
        }
        values = snapshotOptions();
        scenes.update();
        captured = captureSky(cameraY);
    }

    public CloudlySkyPreset skyPreset() { return scenes.skyPreset(); }
    public CloudlySurfaceShadow.Snapshot surfaceShadow() { return scenes.surfaceShadow(); }
    public long imageView(int sampledIndex) {
        return gpu.descriptorHeap().imageView(new GpuDescriptorIndex.Resource(sampledIndex));
    }

    public RtCloudlyFrame frame(VkCommandBuffer commands, long index, double time,
                               double cameraX, double cameraY, double cameraZ,
                               float[] inverseClip, float jitterX, float jitterY, float preExposure,
                               RtImage sceneColor, RtImage primaryDepth) {
        ensureOutput(sceneColor.width, sceneColor.height);
        var color = new BorrowedGpuImage(gpu, sceneColor, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
        BorrowedGpuImage depth = null;
        try {
            depth = new BorrowedGpuImage(gpu, primaryDepth, VK10.VK_FORMAT_R32_SFLOAT);
            var frame = new RtCloudlyFrame(commands, index, time, new Camera(cameraX, cameraY, cameraZ),
                    inverseClip, jitterX, jitterY, preExposure, color, depth, outputView, outputOwner,
                    primaryDepth.width, primaryDepth.height);
            frame.whenRetired(color::close);
            frame.whenRetired(depth::close);
            return frame;
        } catch (RuntimeException | Error failure) {
            color.close();
            if (depth != null) depth.close();
            throw failure;
        }
    }

    /** The color bridge writes a distinct HDR image; copy it into the existing exposure/bloom input. */
    public void record(RtCloudlyFrame frame, RtImage sceneColor, MemoryStack stack) {
        scenes.record(frame);
        if (!frame.outputAcquired()) return;
        VulkanCommandEncoder.memoryBarrier(frame.commandBuffer(), stack);
        var copy = VkImageCopy.calloc(1, stack);
        copy.srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.extent().set(sceneColor.width, sceneColor.height, 1);
        VK10.vkCmdCopyImage(frame.commandBuffer(), output.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                sceneColor.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copy);
        VulkanCommandEncoder.memoryBarrier(frame.commandBuffer(), stack);
    }

    private void ensureOutput(int width, int height) {
        if (output != null && output.width == width && output.height == height) return;
        if (outputOwner != null) { outputOwner.close(); outputOwner = null; }
        output = context.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "Native Cloudly HDR composition");
        try {
            outputView = new BorrowedGpuImage(gpu, output, VK10.VK_FORMAT_R16G16B16A16_SFLOAT);
            RtImage image = output;
            BorrowedGpuImage view = outputView;
            outputOwner = resources.create(() -> { view.close(); image.destroy(); });
        } catch (RuntimeException | Error failure) {
            output.destroy(); output = null;
            throw failure;
        }
    }

    private OptionValues snapshotOptions() {
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = CausticaConfig.Rt.Cloudly.ENABLED.value() && mc.level != null
                && Level.OVERWORLD.equals(mc.level.dimension());
        CloudlyOptions.Scene scene = CloudlyOptions.Scene.valueOf(
                CausticaConfig.Rt.Cloudly.SCENE.get().toUpperCase(Locale.ROOT));
        int height = CausticaConfig.Rt.Cloudly.HEIGHT_OFFSET_BLOCKS.value();
        float distance = CausticaConfig.Rt.Cloudly.MAX_DISTANCE_KM.value();
        return new OptionValues() {
            @SuppressWarnings("unchecked")
            @Override public <T> T get(Option<T> option) {
                if (option.equals(CloudlyOptions.ENABLED)) return (T) Boolean.valueOf(enabled);
                if (option.equals(CloudlyOptions.SCENE)) return (T) scene;
                if (option.equals(CloudlyOptions.HEIGHT_OFFSET_BLOCKS)) return (T) Integer.valueOf(height);
                if (option.equals(CloudlyOptions.MAX_DISTANCE_KM)) return (T) Float.valueOf(distance);
                if (option.equals(SkyLutPass.SUN_NOON_SOUTH_TILT_DEGREES)) {
                    CloudlySkyPreset preset = scenes.skyPreset();
                    return (T) Float.valueOf(preset == null ? RtLookPackage.current().sky().sunNoonSouthTiltDegrees()
                            : (float) preset.noonTiltDegrees());
                }
                throw new IllegalArgumentException("Unknown native sky option: " + option.id());
            }
        };
    }

    private MinecraftSkyFrame captureSky(double cameraY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float radians = (float) (Math.PI / 180.0);
        var look = RtLookPackage.current().lighting();
        var calibration = new MinecraftLightingCalibration(look.sunIlluminanceLux(), look.moonIlluminanceLux(),
                look.blockEmissionLuminanceCdM2(), look.nightAirglowLuminanceCdM2(),
                look.starLuminanceCdM2(), look.moonPhaseFixedFraction());
        var celestial = new MinecraftCelestialFrame(
                probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * radians,
                probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * radians,
                probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * radians,
                probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial),
                probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(),
                mc.level.getSeaLevel(), cameraY, METERS_PER_BLOCK, calibration);
        var fog = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogData;
        return new MinecraftSkyFrame(celestial, mc.level.getRainLevel(partial), mc.level.getThunderLevel(partial),
                new MinecraftSkyFrame.TerrainFade(fog.renderDistanceStart * METERS_PER_BLOCK,
                        fog.renderDistanceEnd * METERS_PER_BLOCK));
    }

    @Override public void close() {
        scenes.close();
        if (outputOwner != null) { outputOwner.close(); outputOwner = null; }
        compute.close();
        context.gpuExecutor().flushDestroysAfterDeviceIdle();
        gpu.close();
    }
}
