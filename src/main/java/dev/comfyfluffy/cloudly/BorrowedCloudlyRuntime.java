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
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Cloudly recorder using the host Vulkan device, queue and command buffer.
 * The host calls record on its command-stream thread and retires every returned lease
 * after GPU completion. No method submits a queue or destroys the borrowed device.
 */
public final class BorrowedCloudlyRuntime {
    public static final int FRAME_BYTES = 264, WORLD_BYTES = 4224, HOST_BYTES = 96;
    private static final int PREPARE = 1, COMPOSITE = 2, CALLBACK_FAILED = 0x80000000;
    private final ResourceFactory resources = new ResourceFactory();
    private final Map<Long, NativeFrame> leases = new HashMap<>();
    private CloudlyDevice device;
    private GpuDevice gpu;
    private GpuComputeQueue compute;
    private CloudlyScenePass scenes;
    private CloudlyRuntime.Settings settings;
    private MinecraftSkyFrame captured;
    private NativeFrame pending;
    private long nextLease;
    private boolean released;
    private volatile boolean ready;
    private volatile float noonTiltDegrees = 30;
    private volatile ReadyScene readyScene;
    private CloudlyScenePass.PreparationFailure publishedPreparationFailure;
    private volatile PreparationFailure preparationFailure;

    /** Immutable scene data published before the host captures matching lights and acknowledges its index. */
    public record ReadyScene(int sceneIndex, float noonTiltDegrees, float sunIlluminanceLux, float moonIlluminanceLux,
                             float sunRed, float sunGreen, float sunBlue,
                             float sunAngularDiameterDegrees, float sunDiscAngularDiameterDegrees,
                             float moonAngularDiameterDegrees) { }

    /** A failed requested scene does not retire a previously active scene. */
    public record PreparationFailure(int sceneIndex, String detail) { }

    public boolean isReady() { return ready; }
    public float noonTiltDegrees() { return noonTiltDegrees; }
    public ReadyScene readyScene() { return readyScene; }
    public PreparationFailure preparationFailure() { return preparationFailure; }

    /** Consumes direct native-ABI buffers only during this callback. */
    public synchronized long record(ByteBuffer frameBytes, ByteBuffer worldBytes, ByteBuffer hostBytes) {
        if (released) throw new IllegalStateException("Cloudly registration was released");
        ByteBuffer frame = packet(frameBytes, FRAME_BYTES);
        ByteBuffer world = packet(worldBytes, WORLD_BYTES);
        ByteBuffer host = packet(hostBytes, HOST_BYTES);
        if (frame.getInt(0) != FRAME_BYTES || frame.getInt(4) != 1
                || world.getInt(0) != WORLD_BYTES || world.getInt(4) != 1
                || host.getInt(0) != HOST_BYTES || host.getInt(4) != 1) {
            throw new IllegalArgumentException("Unsupported Cloudly host ABI");
        }
        int phase = frame.getInt(8);
        if (phase != PREPARE && phase != COMPOSITE) throw new IllegalArgumentException("Unknown Cloudly phase");
        if (device == null) {
            if (phase != PREPARE) throw new IllegalStateException("Prepare must initialize Cloudly before composition");
            CloudlyDevice createdDevice = CloudlyDevice.borrow(frame.getLong(32), frame.getLong(40), frame.getLong(48),
                    frame.getInt(64), frame.getInt(68));
            GpuDevice createdGpu = null;
            GpuComputeQueue createdCompute = null;
            try {
                createdGpu = new GpuDevice(createdDevice);
                createdCompute = new GpuComputeQueue(createdGpu);
                var createdScenes = new CloudlyScenePass(createdGpu, createdCompute, resources, this::options, () -> captured);
                device = createdDevice; gpu = createdGpu; compute = createdCompute; scenes = createdScenes;
            } catch (RuntimeException | Error failure) {
                var failedGpu = createdGpu; var failedCompute = createdCompute;
                ResourceLifetime.closeAfterFailure(failure, () -> { if (failedCompute != null) failedCompute.close(); },
                        () -> { if (failedGpu != null) failedGpu.close(); }, createdDevice::close);
                throw failure;
            }
        } else if (device.vk().address() != frame.getLong(48)) {
            throw new IllegalStateException("A new Vulkan device requires a new Cloudly registration");
        }
        if (phase == PREPARE) return prepare(frame, world, host);
        NativeFrame current = pending;
        if (current == null || current.index != frame.getLong(16)) {
            throw new IllegalStateException("Cloudly composition has no matching prepared frame");
        }
        pending = null;
        long lease = acquire(current);
        try {
            current.commands = new VkCommandBuffer(frame.getLong(56), device.vk());
            if (current.ready) {
                if (current.color.image() != frame.getLong(88)) {
                    throw new IllegalStateException("Host changed its final image between Cloudly phases");
                }
                scenes.record(current);
                if (current.output != null) current.copyOutput();
            }
            current.submitted();
        } catch (RuntimeException | Error failure) {
            world.putInt(8, CALLBACK_FAILED);
            org.slf4j.LoggerFactory.getLogger(BorrowedCloudlyRuntime.class).error("Cloudly HDR composition failed", failure);
        } finally {
            current.close();
        }
        return lease;
    }

    private long prepare(ByteBuffer frame, ByteBuffer world, ByteBuffer host) {
        if (pending != null) { pending.close(); pending = null; }
        settings = new CloudlyRuntime.Settings((host.getInt(12) & 1) != 0,
                switch (host.getInt(16)) { case 0 -> "hangar"; case 1 -> "mission3"; case 2 -> "mission11";
                    default -> throw new IllegalArgumentException("Unknown Cloudly scene"); },
                host.getInt(20), host.getFloat(24));
        double cameraY = frame.getDouble(160);
        var lighting = new MinecraftLightingCalibration(host.getFloat(60), host.getFloat(64), host.getFloat(68),
                host.getFloat(72), host.getFloat(76), host.getFloat(80));
        captured = new MinecraftSkyFrame(new MinecraftCelestialFrame(host.getFloat(28), host.getFloat(32),
                host.getFloat(36), host.getFloat(40), host.getInt(44), host.getInt(48), cameraY, 1, lighting),
                host.getFloat(52), host.getFloat(56),
                new MinecraftSkyFrame.TerrainFade(frame.getFloat(252), frame.getFloat(256)));
        compute.publishCompleted();
        if ((host.getInt(12) & 2) != 0) scenes.invalidateHistory();
        int acknowledged = host.getInt(84);
        scenes.updateForHost(switch (acknowledged) {
            case 0 -> CloudlyOptions.Scene.HANGAR; case 1 -> CloudlyOptions.Scene.MISSION3;
            case 2 -> CloudlyOptions.Scene.MISSION11; default -> null;
        });
        var scenePreparationFailure = scenes.preparationFailure();
        if (scenePreparationFailure != publishedPreparationFailure) {
            publishedPreparationFailure = scenePreparationFailure;
            String detail = scenePreparationFailure == null ? null : scenePreparationFailure.cause().getMessage();
            preparationFailure = scenePreparationFailure == null ? null : new PreparationFailure(scenePreparationFailure.scene().ordinal(),
                    detail == null ? scenePreparationFailure.cause().getClass().getSimpleName() : detail);
        }
        var publication = scenes.readyScene();
        if (publication != null) {
            var preset = publication.sky(); var color = preset.sunColorLinearBt709();
            readyScene = new ReadyScene(publication.scene().ordinal(), (float) preset.noonTiltDegrees(),
                    (float) preset.sunIlluminanceLux(), (float) preset.moonIlluminanceLux(),
                    (float) color.red(), (float) color.green(), (float) color.blue(),
                    (float) Math.toDegrees(preset.sunAngularRadiusRadians() * 2),
                    (float) Math.toDegrees(preset.sunDiscHalfAngleRadians() * 2),
                    (float) Math.toDegrees(preset.moonDiscHalfAngleRadians().orElse(Math.toRadians(.26)) * 2));
        } else readyScene = null;
        ready = scenes.resourcesReady();
        if (ready) noonTiltDegrees = (float) scenes.skyPreset().noonTiltDegrees();
        var current = new NativeFrame(frame, host, ready);
        pending = current;
        long lease = acquire(current);
        try {
            var batch = device.recordBorrowedWork(current.commands);
            current.retired.add(batch::close);
            if (batch.failure() != null) throw new IllegalStateException("Cloudly preparation recording failed", batch.failure());
            if (ready) {
                scenes.prepareWorld(current);
                var shadow = scenes.preparedSurfaceShadow();
                if (shadow != null) {
                    current.retired.add(shadow::close);
                    writeShadow(world, shadow, host.getInt(48));
                }
                var rays = scenes.preparedRayResources();
                if (rays != null) writeRayResources(world, rays, host.getInt(48));
            }
        } catch (RuntimeException | Error failure) {
            world.putInt(8, CALLBACK_FAILED);
            org.slf4j.LoggerFactory.getLogger(BorrowedCloudlyRuntime.class).error("Cloudly world preparation failed", failure);
        }
        return lease;
    }

    private static void writeShadow(ByteBuffer world, CloudlySurfaceShadow.Snapshot shadow, int seaLevel) {
        var grid = shadow.grid();
        world.putInt(8, 1);
        world.putLong(16, shadow.image()).putLong(24, shadow.view()).putInt(32, VK_FORMAT_R16G16B16A16_SFLOAT);
        world.putInt(36, CloudlySurfaceShadow.SIZE).putInt(40, CloudlySurfaceShadow.SIZE).putInt(44, CloudlySurfaceShadow.SIZE);
        world.putFloat(48, grid.minX()).putFloat(52, grid.minZ() + seaLevel).putFloat(56, -grid.minY() - grid.height());
        world.putFloat(64, grid.width()).putFloat(68, grid.depth()).putFloat(72, grid.height());
        world.putFloat(80, shadow.lightX()).putFloat(84, shadow.lightY()).putFloat(88, shadow.lightZ());
    }

    private void writeRayResources(ByteBuffer world, CloudlyOriginalPass.RayResources rays, int seaLevel) {
        var clouds = rays.clouds(); var atmosphere = rays.atmosphere(); var environment = rays.environment();
        if (clouds.bindings().size() != 55) throw new IllegalStateException("Original ray descriptor ABI changed");
        world.putLong(96, clouds.parametersAddress()).putLong(104, clouds.cameraAddress())
                .putLong(112, clouds.bindingsAddress()).putLong(120, atmosphere.parametersAddress());
        for (var binding : clouds.bindings()) {
            writeDescriptor(world, binding.slot(), clouds.descriptors().get(binding.name()));
        }
        writeDescriptor(world, 55, atmosphere.descriptors().get("MultiScatteringLut"));
        writeDescriptor(world, 56, atmosphere.descriptors().get("MultiScatteringLutSampler"));
        writeDescriptor(world, 57, environment.descriptors().get("StarsBackground"));
        writeDescriptor(world, 58, environment.descriptors().get("StarsTextureSampler"));
        writeDescriptor(world, 59, environment.descriptors().get("MoonTexture"));
        writeDescriptor(world, 60, environment.descriptors().get("MoonTextureSampler"));
        if (environment.parametersByteSize() != 7344) throw new IllegalStateException("Original environment constants ABI changed");
        int offset = 128 + 61 * 64;
        world.putInt(offset, 1162).putInt(offset + 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                .putLong(offset + 48, environment.parametersBuffer()).putLong(offset + 56, environment.parametersByteSize());
        writeDescriptor(world, 62, environment.descriptors().get("HostStarsCatalogueCube"));
        world.putInt(12, 63).putFloat(60, seaLevel);
        world.putInt(8, world.getInt(8) | 2);
    }

    private void writeDescriptor(ByteBuffer world, int index, GpuDescriptorIndex descriptor) {
        if (descriptor == null) throw new IllegalStateException("Original ray descriptor is unbound at " + index);
        var heap = gpu.descriptorHeap(); var entry = heap.resolve(descriptor);
        int offset = 128 + index * 64;
        world.putInt(offset, 1101 + index).putInt(offset + 4, entry.type());
        world.putLong(offset + 40, entry.sampler()).putLong(offset + 48, entry.buffer()).putLong(offset + 56, entry.byteSize());
        if (entry.type() == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || entry.type() == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
            world.putLong(offset + 40, entry.offset());
        } else if (entry.type() == VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE || entry.type() == VK_DESCRIPTOR_TYPE_STORAGE_IMAGE) {
            var view = heap.viewInfo(descriptor); var image = heap.imageInfo(view.image());
            world.putLong(offset + 8, view.image()).putLong(offset + 16, entry.imageView());
            world.putInt(offset + 24, view.format()).putInt(offset + 28, image.width())
                    .putInt(offset + 32, image.height()).putInt(offset + 36, image.depth());
            if (view.baseMip() > 255 || view.levels() > 255 || view.baseLayer() > 65535 || view.layers() > 65535) {
                throw new IllegalStateException("Original image subresource range exceeds host ABI");
            }
            long range = view.viewType() | (long) view.baseMip() << 8 | (long) view.levels() << 16
                    | (long) view.baseLayer() << 24 | (long) view.layers() << 40;
            world.putLong(offset + 48, range).putLong(offset + 56, Integer.toUnsignedLong(image.mipLevels()) | (long) image.layers() << 32);
        }
    }

    private OptionValues options() {
        return new OptionValues() {
            @SuppressWarnings("unchecked") public <T> T get(Option<T> option) {
                if (option.equals(CloudlyOptions.ENABLED)) return (T) Boolean.valueOf(settings.enabled());
                if (option.equals(CloudlyOptions.SCENE)) return (T) CloudlyOptions.Scene.valueOf(settings.scene().toUpperCase(java.util.Locale.ROOT));
                if (option.equals(CloudlyOptions.HEIGHT_OFFSET_BLOCKS)) return (T) Integer.valueOf(settings.heightOffsetBlocks());
                if (option.equals(CloudlyOptions.MAX_DISTANCE_KM)) return (T) Float.valueOf(settings.maxDistanceKm());
                if (option.equals(CloudlyOptions.SUN_NOON_SOUTH_TILT_DEGREES)) {
                    var preset = scenes.skyPreset();
                    return (T) Float.valueOf(preset == null ? 30 : (float) preset.noonTiltDegrees());
                }
                throw new IllegalArgumentException("Unknown Cloudly option " + option.id());
            }
        };
    }

    private long acquire(NativeFrame frame) {
        long id = ++nextLease;
        frame.references++;
        leases.put(id, frame);
        return id;
    }

    /** Called by the host only after the corresponding GPU command list completes. */
    public synchronized void retire(long lease) {
        NativeFrame frame = leases.remove(lease);
        if (frame == null) throw new IllegalStateException("Unknown or already retired Cloudly lease " + lease);
        frame.close();
    }

    /** The host calls this after unregistering and retiring its final GPU lease. */
    public synchronized void release() {
        if (released) return;
        if (!leases.isEmpty()) throw new IllegalStateException("Cloudly registration still owns GPU leases");
        released = true;
        ready = false;
        readyScene = null;
        publishedPreparationFailure = null;
        preparationFailure = null;
        if (pending != null) { pending.close(); pending = null; }
        if (device != null) {
            new ResourceLifetime(scenes::close, device::cancelBorrowedWork, compute::close, gpu::close, device::close).close();
        }
    }

    private static ByteBuffer packet(ByteBuffer bytes, int size) {
        if (bytes == null || !bytes.isDirect() || bytes.capacity() < size) {
            throw new IllegalArgumentException("Cloudly ABI packets must be direct and complete");
        }
        return bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }

    private final class NativeFrame implements PostEffectFrame, AutoCloseable {
        private VkCommandBuffer commands;
        private final long index;
        private final double time;
        private final SceneView view;
        private final float[] inverseClip = new float[16], jitter = new float[2];
        private final float exposure;
        private final BorrowedGpuImage color, depth;
        private final boolean ready;
        private final List<Runnable> accepted = new ArrayList<>(), retired = new ArrayList<>();
        private VmaImage2D output;
        private GpuImage outputView;
        private int references = 1;
        NativeFrame(ByteBuffer frame, ByteBuffer host, boolean ready) {
            this.ready = ready;
            commands = new VkCommandBuffer(frame.getLong(56), device.vk());
            index = frame.getLong(16); time = host.getDouble(88);
            view = new SceneView(new Camera(frame.getDouble(152), frame.getDouble(160), frame.getDouble(168)));
            for (int i = 0; i < 16; i++) inverseClip[i] = frame.getFloat(176 + i * 4);
            jitter[0] = frame.getFloat(240); jitter[1] = frame.getFloat(244);
            exposure = frame.getFloat(248);
            color = image(frame, 88);
            try { depth = image(frame, 120); }
            catch (RuntimeException | Error failure) { color.close(); throw failure; }
            retired.add(color::close); retired.add(depth::close);
        }
        private BorrowedGpuImage image(ByteBuffer frame, int offset) {
            return new BorrowedGpuImage(gpu, frame.getLong(offset), frame.getLong(offset + 8),
                    frame.getInt(offset + 20), frame.getInt(offset + 24), frame.getInt(offset + 16));
        }
        public VkCommandBuffer commandBuffer() { return commands; }
        public long frameIndex() { return index; }
        public SceneView view() { return view; }
        public double timeSeconds() { return time; }
        public double metersPerSceneUnit() { return 1; }
        public int renderWidth() { return depth.width(); }
        public int renderHeight() { return depth.height(); }
        public boolean sceneLinearHdr() { return true; }
        public GpuImage sceneColor() { return color; }
        public GpuImage primaryDepth() { return depth; }
        public GpuImage depth() { return depth; }
        public float[] cameraRelativeFromClip() { return inverseClip.clone(); }
        public float[] traceJitter() { return jitter.clone(); }
        public float preExposure() { return exposure; }
        public boolean spatialMediumActive() { return false; }
        public GpuImage exposureImage() { throw unavailable(); }
        public GpuAccelerationStructureDescriptor entrySceneTlasDescriptor() { throw unavailable(); }
        public float[] cameraTlasPosition() { throw unavailable(); }
        public void traceVisibility(VulkanDeviceAddress rays, VulkanDeviceAddress results, int width, int height) { throw unavailable(); }
        public void sampleVolumeLighting(VulkanDeviceAddress samples, VulkanDeviceAddress results, int width, int height) { throw unavailable(); }
        private UnsupportedOperationException unavailable() { return new UnsupportedOperationException("Host ray query service is not registered"); }
        public GpuImage acquireSceneColorOutput() {
            if (outputView == null) {
                output = VmaImage2D.create(gpu, color.width(), color.height(), color.format(), VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        "Cloudly host HDR composition");
                retired.add(output::close);
                ComputeSynchronization.initializeImages(commands, List.of(output));
                outputView = new GpuImage() {
                    public long image() { return output.image(); }
                    public long view() { return output.view(); }
                    public int width() { return output.width(); }
                    public int height() { return output.height(); }
                    public int format() { return output.format(); }
                    public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
                        return new GpuImageDescriptor() {
                            public GpuImageDescriptorKind kind() { return kind; }
                            public GpuDescriptorIndex.Resource index() {
                                return kind == GpuImageDescriptorKind.STORAGE ? output.storageIndex() : output.sampledIndex();
                            }
                        };
                    }
                };
            }
            return outputView;
        }
        private void copyOutput() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
                vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0, barrier, null, null);
                var region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.extent().set(color.width(), color.height(), 1);
                vkCmdCopyImage(commands, output.image(), VK_IMAGE_LAYOUT_GENERAL, color.image(), VK_IMAGE_LAYOUT_GENERAL, region);
            }
        }
        public void retain(ResourceOwner owner) { var claim = owner.retain(); retired.add(claim::close); }
        public void whenSubmitted(Runnable callback) { accepted.add(callback); }
        public void whenRetired(Runnable callback) { retired.add(callback); }
        private void submitted() {
            var callbacks = List.copyOf(accepted); accepted.clear();
            new ResourceLifetime(callbacks.toArray(Runnable[]::new)).close();
        }
        public void close() {
            if (--references != 0) return;
            accepted.clear();
            var callbacks = List.copyOf(retired); retired.clear();
            new ResourceLifetime(callbacks.toArray(Runnable[]::new)).close();
        }
    }
}
