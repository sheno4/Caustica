package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import org.lwjgl.vulkan.VK10;

/** Owns extent-keyed trace images and continuation queues. */
public final class TraceResources {
    private static final int PATH_RECORDS_PER_PIXEL = 2;

    private TraceExtent extent;
    private TraceImages images;

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
        GpuImage stablePlaneMetadata = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "stable plane metadata " + renderWidth + "x" + renderHeight);
        GpuImage normalRoughness = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderWidth + "x" + renderHeight);
        GpuImage diffuseAlbedo = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderWidth + "x" + renderHeight);
        GpuImage linearDepth = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "guide view depth " + renderWidth + "x" + renderHeight);
        GpuImage motion = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderWidth + "x" + renderHeight);
        GpuImage specularAlbedo = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderWidth + "x" + renderHeight);
        GpuImage specularMotion = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderWidth + "x" + renderHeight);
        GpuImage diffuseRadianceHitDistance = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD diffuse radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage specularRadianceHitDistance = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD specular radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage nrdViewZ = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R32_SFLOAT, "NRD view Z " + renderWidth + "x" + renderHeight);
        GpuImage denoisedDiffuseRadianceHitDistance = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD denoised diffuse radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage denoisedSpecularRadianceHitDistance = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD denoised specular radiance hit distance " + renderWidth + "x" + renderHeight);
        GpuImage nrdStableRadiance = context.createStorageImage(renderWidth, renderHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "NRD stable radiance " + renderWidth + "x" + renderHeight);
        GpuImage reconstructedColor = context.createStorageImage(displayWidth, displayHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "reconstruction output " + displayWidth + "x" + displayHeight);
        images = new TraceImages(traceColor, stablePlaneMetadata,
                normalRoughness, diffuseAlbedo, linearDepth, motion,
                specularAlbedo, specularMotion, diffuseRadianceHitDistance,
                specularRadianceHitDistance, nrdViewZ, denoisedDiffuseRadianceHitDistance,
                denoisedSpecularRadianceHitDistance, nrdStableRadiance, reconstructedColor);
        extent = wanted;
    }

    /** Allocates this frame's path continuation queue; it retires with the frame that traced against it. */
    public GpuBuffer acquireContinuationQueue(VulkanDeviceContext context, RtGpuExecutor.GraphicsUse graphicsUse) {
        TraceExtent current = extent();
        GpuBuffer queue = context.createBuffer(
                continuationBytes(current.renderWidth(), current.renderHeight()),
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                false, "path continuation queue " + current.renderWidth() + "x" + current.renderHeight()
                        + "x" + PATH_RECORDS_PER_PIXEL);
        graphicsUse.whenComplete(queue::destroy);
        return queue;
    }

    public void destroy() {
        destroySized();
    }

    private void destroySized() {
        if (images != null) {
            images.traceColor().destroy();
            images.stablePlaneMetadata().destroy();
            images.normalRoughness().destroy();
            images.diffuseAlbedo().destroy();
            images.linearDepth().destroy();
            images.motion().destroy();
            images.specularAlbedo().destroy();
            images.specularMotion().destroy();
            images.diffuseRadianceHitDistance().destroy();
            images.specularRadianceHitDistance().destroy();
            images.nrdViewZ().destroy();
            images.denoisedDiffuseRadianceHitDistance().destroy();
            images.denoisedSpecularRadianceHitDistance().destroy();
            images.nrdStableRadiance().destroy();
            images.reconstructedColor().destroy();
            images = null;
        }
        extent = null;
    }

    static long continuationBytes(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.multiplyExact(Math.multiplyExact(pixels, PATH_RECORDS_PER_PIXEL),
                PackedPathSegmentData.BYTE_SIZE);
    }
}
