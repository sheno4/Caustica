package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import org.lwjgl.vulkan.VK10;

/** Owns extent-keyed trace images and continuation queues. */
public final class TraceResources {
    private static final int PATH_QUEUE_RING = 6;
    private static final int PATH_RECORDS_PER_PIXEL = 2;

    private final GpuBuffer[] continuationQueues = new GpuBuffer[PATH_QUEUE_RING];
    private final RtGpuExecutor.TrackedGraphicsUse[] continuationUses =
            new RtGpuExecutor.TrackedGraphicsUse[PATH_QUEUE_RING];
    private int continuationIndex = -1;
    private TraceExtent extent;
    private TraceImages images;

    public TraceResources() {
        for (int i = 0; i < continuationUses.length; i++) {
            continuationUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        }
    }

    public boolean hasDisplayExtent(int width, int height) {
        return extent != null && extent.displayWidth() == width && extent.displayHeight() == height;
    }

    public TraceExtent extent() {
        if (extent == null) {
            throw new IllegalStateException("Trace resources are not sized");
        }
        return extent;
    }

    public TraceImages images() {
        if (images == null) {
            throw new IllegalStateException("Trace resources are not sized");
        }
        return images;
    }

    /** Replaces the allocation after the caller has drained prior GPU use. */
    public void resize(VulkanDeviceContext context, TraceExtent wanted) {
        destroySized();
        int renderWidth = wanted.renderWidth();
        int renderHeight = wanted.renderHeight();
        int displayWidth = wanted.displayWidth();
        int displayHeight = wanted.displayHeight();

        GpuImage traceColor = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderWidth + "x" + renderHeight);
        long continuationBytes = continuationBytes(renderWidth, renderHeight);
        for (int i = 0; i < continuationQueues.length; i++) {
            continuationQueues[i] = context.createBuffer(continuationBytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false, "path continuation queue " + i + " " + renderWidth + "x" + renderHeight
                            + "x" + PATH_RECORDS_PER_PIXEL);
        }
        GpuImage normalRoughness = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderWidth + "x" + renderHeight);
        GpuImage diffuseAlbedo = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderWidth + "x" + renderHeight);
        GpuImage linearDepth = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderWidth + "x" + renderHeight);
        GpuImage motion = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderWidth + "x" + renderHeight);
        GpuImage specularAlbedo = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderWidth + "x" + renderHeight);
        GpuImage specularMotion = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderWidth + "x" + renderHeight);
        GpuImage reconstructedColor = context.createStorageImage(displayWidth, displayHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "reconstruction output " + displayWidth + "x" + displayHeight);
        images = new TraceImages(traceColor, normalRoughness, diffuseAlbedo, linearDepth, motion,
                specularAlbedo, specularMotion, reconstructedColor);
        extent = wanted;
    }

    public GpuBuffer acquireContinuationQueue(RtGpuExecutor.GraphicsUseWaiter waiter) {
        continuationIndex = (continuationIndex + 1) % continuationQueues.length;
        waiter.await(continuationUses[continuationIndex]);
        return continuationQueues[continuationIndex];
    }

    public void markContinuationUse(RtGpuExecutor.GraphicsUse graphicsUse) {
        continuationUses[continuationIndex].mark(graphicsUse);
    }

    public void destroy() {
        destroySized();
    }

    private void destroySized() {
        if (images != null) {
            images.traceColor().destroy();
            images.normalRoughness().destroy();
            images.diffuseAlbedo().destroy();
            images.linearDepth().destroy();
            images.motion().destroy();
            images.specularAlbedo().destroy();
            images.specularMotion().destroy();
            images.reconstructedColor().destroy();
            images = null;
        }
        for (int i = 0; i < continuationQueues.length; i++) {
            if (continuationQueues[i] != null) {
                continuationQueues[i].destroy();
                continuationQueues[i] = null;
            }
            continuationUses[i].clear();
        }
        continuationIndex = -1;
        extent = null;
    }

    static long continuationBytes(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.multiplyExact(Math.multiplyExact(pixels, PATH_RECORDS_PER_PIXEL),
                PackedPathSegmentData.BYTE_SIZE);
    }
}
