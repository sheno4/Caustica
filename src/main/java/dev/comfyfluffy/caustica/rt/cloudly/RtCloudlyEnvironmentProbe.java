package dev.comfyfluffy.caustica.rt.cloudly;

import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.vulkan.*;
import dev.comfyfluffy.caustica.minecraft.rendering.MinecraftSkyFrame;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.CloudlyOptions;
import dev.comfyfluffy.caustica.minecraft.rendering.sky.cloudly.CloudlyScenePass;
import dev.comfyfluffy.caustica.settings.OptionValues;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import dev.comfyfluffy.caustica.vulkan.VmaImage2D;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.vulkan.VK10.*;

/** A complete six-direction native capture is published atomically for subsequent world rays. */
final class RtCloudlyEnvironmentProbe implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtCloudlyEnvironmentProbe.class);
    private final GpuDevice gpu;
    private final ResourceFactory resources;
    private final CloudlyScenePass scenes;
    private CloudlyEnvironment cubes;
    private VmaImage2D black, depth;
    private GpuImage blackView, depthView;
    private ResourceOwner inputsOwner;
    private boolean initialized;
    private CloudlyEnvironment.Snapshot published;
    private Batch pending;
    private Object identity;
    private long revision;
    private int height;
    private float distance;
    private MinecraftSkyFrame previousSky;
    private Camera publishedOrigin;
    private boolean closed;

    RtCloudlyEnvironmentProbe(GpuDevice gpu, ResourceFactory resources, CloudlyScenePass scenes) {
        this.gpu = gpu; this.resources = resources; this.scenes = scenes;
    }

    synchronized void refresh(MinecraftSkyFrame sky, OptionValues options, Camera origin) {
        if (closed) return;
        Object selected = scenes.environmentIdentity();
        long selectedRevision = scenes.environmentRevision();
        int selectedHeight = options.get(CloudlyOptions.HEIGHT_OFFSET_BLOCKS);
        float selectedDistance = options.get(CloudlyOptions.MAX_DISTANCE_KM);
        boolean changed = identity != selected || revision != selectedRevision || height != selectedHeight
                || distance != selectedDistance || discontinuous(previousSky, sky);
        if (changed || publishedOrigin != null && distanceSquared(publishedOrigin, origin) > 512.0 * 512.0) clear();
        if (pending != null && distanceSquared(pending.origin, origin) > 64.0 * 64.0) {
            pending.close(); pending = null;
        }
        identity = selected; revision = selectedRevision; height = selectedHeight; distance = selectedDistance;
        previousSky = sky;
        if (selected == null) releaseInputs();
    }

    synchronized CloudlyEnvironment.Snapshot capture() {
        return published == null ? null : published.retain();
    }

    synchronized void deactivate() {
        releaseInputs();
        identity = null;
        previousSky = null;
    }

    synchronized void record(RtCloudlyFrame frame, MinecraftSkyFrame sky, OptionValues options) {
        if (closed || sky == null || scenes.environmentIdentity() == null) return;
        Camera camera = frame.view().camera();
        refresh(sky, options, camera);
        ensureInputs();
        frame.retain(inputsOwner);
        if (!initialized) initializeInputs(frame);
        if (pending == null) pending = new Batch(cubes.acquire(), camera, sky, options, frame.timeSeconds());
        Batch batch = pending;
        int face = batch.face;
        var submitted = new AtomicBoolean();
        frame.whenSubmitted(() -> submitted.set(true));
        frame.whenRetired(() -> {
            if (submitted.get()) return;
            synchronized (RtCloudlyEnvironmentProbe.this) {
                if (pending == batch) { pending = null; batch.close(); }
            }
        });
        var directional = frame.environmentFace(batch.origin, CloudlyCubeProjection.fromClip(face), batch.time,
                blackView, depthView);
        boolean recorded = scenes.recordEnvironmentFace(directional, batch.sky, batch.options,
                source -> batch.capture.recordFace(directional, source, face, false),
                source -> batch.capture.recordFace(directional, source, face, true));
        if (!recorded) return;
        frame.whenSubmitted(() -> {
            synchronized (RtCloudlyEnvironmentProbe.this) {
                if (closed || pending != batch) return;
                batch.face++;
                if (batch.face == 6) {
                    var previous = published;
                    published = batch.capture.snapshot();
                    publishedOrigin = batch.origin;
                    pending = null;
                    batch.close();
                    if (previous != null) previous.close();
                    if (previous == null) LOGGER.info("Original AC8 environment published: six {}x{} HDR faces; separate celestial contribution", CloudlyEnvironment.SIZE, CloudlyEnvironment.SIZE);
                }
            }
        });
    }

    private void ensureInputs() {
        if (inputsOwner != null) return;
        var color = VmaImage2D.create(gpu, CloudlyEnvironment.SIZE, CloudlyEnvironment.SIZE,
                VK_FORMAT_R32G32B32A32_SFLOAT, VK_IMAGE_USAGE_TRANSFER_DST_BIT, "Cloudly environment zero scene");
        VmaImage2D zero = null;
        CloudlyEnvironment allocated = null;
        try {
            zero = VmaImage2D.create(gpu, CloudlyEnvironment.SIZE, CloudlyEnvironment.SIZE,
                    VK_FORMAT_R32_SFLOAT, VK_IMAGE_USAGE_TRANSFER_DST_BIT, "Cloudly environment zero depth");
            allocated = new CloudlyEnvironment(gpu, resources);
            var depthAllocation = zero;
            var owner = resources.create(() -> new ResourceLifetime(color::close, depthAllocation::close).close());
            black = color; depth = zero; cubes = allocated; inputsOwner = owner;
            blackView = new ImageView(color); depthView = new ImageView(zero);
        } catch (RuntimeException | Error failure) {
            var depthAllocation = zero; var cubeAllocation = allocated;
            ResourceLifetime.closeAfterFailure(failure, color::close,
                    () -> { if (depthAllocation != null) depthAllocation.close(); },
                    () -> { if (cubeAllocation != null) cubeAllocation.close(); });
            throw failure;
        }
    }

    private void initializeInputs(RtCloudlyFrame frame) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var images = VkImageMemoryBarrier2.calloc(2, stack);
            long[] handles = {black.image(), depth.image()};
            for (int i = 0; i < handles.length; i++) {
                var image = images.get(i).sType$Default().srcStageMask(VK13.VK_PIPELINE_STAGE_2_NONE)
                        .srcAccessMask(VK13.VK_ACCESS_2_NONE).dstStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT)
                        .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(handles[i]);
                image.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            }
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(images));
            var value = VkClearColorValue.calloc(stack);
            var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdClearColorImage(frame.commandBuffer(), black.image(), VK_IMAGE_LAYOUT_GENERAL, value, range);
            vkCmdClearColorImage(frame.commandBuffer(), depth.image(), VK_IMAGE_LAYOUT_GENERAL, value, range);
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT).srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                    .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT).dstAccessMask(VK13.VK_ACCESS_2_SHADER_READ_BIT);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(frame.commandBuffer(), VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
        ResourceOwner recordedOwner = inputsOwner;
        frame.whenSubmitted(() -> {
            synchronized (RtCloudlyEnvironmentProbe.this) {
                if (inputsOwner == recordedOwner) initialized = true;
            }
        });
    }

    synchronized void clear() {
        if (pending != null) { pending.close(); pending = null; }
        if (published != null) { published.close(); published = null; }
        publishedOrigin = null;
    }

    private static boolean discontinuous(MinecraftSkyFrame previous, MinecraftSkyFrame current) {
        if (previous == null || current == null) return previous != current;
        return Math.abs(Math.IEEEremainder(current.celestial().sunAngleRadians() - previous.celestial().sunAngleRadians(), Math.PI * 2)) > .05
                || Math.abs(Math.IEEEremainder(current.celestial().moonAngleRadians() - previous.celestial().moonAngleRadians(), Math.PI * 2)) > .05
                || current.celestial().moonPhaseIndex() != previous.celestial().moonPhaseIndex()
                || Math.abs(current.rainStrength() - previous.rainStrength()) > .1
                || Math.abs(current.thunderStrength() - previous.thunderStrength()) > .1;
    }

    private static double distanceSquared(Camera a, Camera b) {
        return Math.pow(a.x()-b.x(),2) + Math.pow(a.y()-b.y(),2) + Math.pow(a.z()-b.z(),2);
    }

    private void releaseInputs() {
        clear();
        if (cubes != null) { cubes.close(); cubes = null; }
        if (inputsOwner != null) { inputsOwner.close(); inputsOwner = null; }
        black = depth = null;
        blackView = depthView = null;
        initialized = false;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        releaseInputs();
    }

    private static final class Batch implements AutoCloseable {
        final CloudlyEnvironment.Capture capture;
        final Camera origin;
        final MinecraftSkyFrame sky;
        final OptionValues options;
        final double time;
        int face;
        Batch(CloudlyEnvironment.Capture capture, Camera origin, MinecraftSkyFrame sky, OptionValues options, double time) {
            this.capture=capture; this.origin=origin; this.sky=sky; this.options=options; this.time=time;
        }
        @Override public void close() { capture.close(); }
    }

    private record ImageView(VmaImage2D source) implements GpuImage {
        public long image() { return source.image(); }
        public long view() { return source.view(); }
        public int width() { return source.width(); }
        public int height() { return source.height(); }
        public int format() { return source.format(); }
        public GpuImageDescriptor descriptor(GpuImageDescriptorKind kind) {
            var index = kind == GpuImageDescriptorKind.SAMPLED ? source.sampledIndex() : source.storageIndex();
            return new GpuImageDescriptor() {
                public GpuImageDescriptorKind kind() { return kind; }
                public GpuDescriptorIndex.Resource index() { return index; }
            };
        }
    }
}
