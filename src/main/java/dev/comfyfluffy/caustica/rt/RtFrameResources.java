package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtGpuExecutor;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.renderer.presentation.RtDebugPresentPipeline;
import dev.comfyfluffy.caustica.renderer.presentation.RtDisplayPipeline;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.renderer.presentation.RtToneLut;
import dev.comfyfluffy.caustica.renderer.raytracing.gen.PackedPathSegmentData;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;

/** Owns images, buffers, LUTs, and pipelines whose validity is tied to the current frame extent. */
final class RtFrameResources {
    private final RtFramePresenter presenter;
    private final RtLookPackage look;
    private static final int PATH_QUEUE_RING = 6;
    private static final int PATH_RECORDS_PER_PIXEL = 2;
    RtDisplayPipeline displayPipeline;
    RtDebugPresentPipeline debugPresentPipeline;
    RtToneLut sdrToneLut;
    RtToneLut hdrToneLut;
    RtToneLut lookLut;
    int loadedHdrLutNits = -1;

    GpuImage output;
    private final GpuBuffer[] continuationQueues = new GpuBuffer[PATH_QUEUE_RING];
    private final RtGpuExecutor.TrackedGraphicsUse[] continuationUses = new RtGpuExecutor.TrackedGraphicsUse[PATH_QUEUE_RING];
    private int continuationIndex = -1;
    GpuImage displayImage;
    GpuImage hdrDisplayImage;
    GpuImage gNormal;
    GpuImage gAlbedo;
    GpuImage gDepth;
    GpuImage gMotion;
    GpuImage gSpecAlbedo;
    GpuImage gSpecMotion;
    GpuImage rrOutput;
    GpuImage postColorA;
    GpuImage postColorB;
    final RtExposure exposure;

    int displayW = -1;
    int displayH = -1;
    int renderW = -1;
    int renderH = -1;
    boolean renderSizeRrEnabled;
    int renderSizeRrQuality = Integer.MIN_VALUE;

    RtFrameResources(RtFramePresenter presenter, RtLookPackage look, RtExposure.Settings exposureSettings) {
        this.presenter = presenter;
        this.look = look;
        this.exposure = new RtExposure(look, exposureSettings);
        for (int i = 0; i < continuationUses.length; i++) {
            continuationUses[i] = new RtGpuExecutor.TrackedGraphicsUse();
        }
    }

    void ensurePresentationPipelines(VulkanDeviceContext context) throws IOException {
        if (displayPipeline == null) {
            displayPipeline = RtDisplayPipeline.create(context);
        }
        if (debugPresentPipeline == null) {
            debugPresentPipeline = RtDebugPresentPipeline.create(context);
        }
        if (sdrToneLut == null) {
            sdrToneLut = RtToneLut.load(context, "sdr_aces2_rec709.bin");
        }
        int wantedHdrNits = CausticaConfig.Rt.Hdr.PEAK_NITS.value();
        if (hdrToneLut == null || loadedHdrLutNits != wantedHdrNits) {
            RtToneLut replacement = RtToneLut.load(context,
                    "hdr_aces2_rec2020_" + wantedHdrNits + "nit.bin");
            if (replacement.size != sdrToneLut.size) {
                replacement.destroy();
                throw new IllegalStateException("SDR/HDR tone LUT size mismatch: "
                        + sdrToneLut.size + " vs " + replacement.size);
            }
            if (hdrToneLut != null) {
                context.waitIdle();
                hdrToneLut.destroy();
            }
            hdrToneLut = replacement;
            loadedHdrLutNits = wantedHdrNits;
        }
        if (lookLut == null) {
            lookLut = RtToneLut.loadResource(context, look.lmtResource());
        }
    }

    /** Ensure the complete extent-keyed resource set, replacing it only after all prior GPU use drains. */
    boolean ensureSized(VulkanDeviceContext context, int width, int height) {
        boolean rrEnabled = RtDlssRr.configured();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueues[0] != null && displayImage != null && hdrDisplayImage != null
                && rrOutput != null && postColorA != null && postColorB != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return false;
        }

        presenter.invalidateRenderedFrame();
        context.waitIdle();
        destroySizedImages();
        displayW = width;
        displayH = height;
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;

        output = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "trace color " + renderW + "x" + renderH);
        long continuationBytes = continuationBytes(renderW, renderH);
        for (int i = 0; i < continuationQueues.length; i++) {
            continuationQueues[i] = context.createBuffer(continuationBytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    false, "path continuation queue " + i + " " + renderW + "x" + renderH
                            + "x" + PATH_RECORDS_PER_PIXEL);
        }
        displayImage = context.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                "RT display image " + width + "x" + height);
        hdrDisplayImage = context.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "RT HDR display image " + width + "x" + height);
        gNormal = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT,
                "guide linear depth " + renderW + "x" + renderH);
        gMotion = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT,
                "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = context.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT,
                "guide specular motion " + renderW + "x" + renderH);
        rrOutput = context.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "DLSS-RR output " + width + "x" + height);
        postColorA = context.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "post chain A " + width + "x" + height);
        postColorB = context.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "post chain B " + width + "x" + height);

        exposure.ensureResources(context);
        return true;
    }

    void destroySizedImages() {
        displayImage = destroy(displayImage);
        hdrDisplayImage = destroy(hdrDisplayImage);
        output = destroy(output);
        for (int i = 0; i < continuationQueues.length; i++) {
            if (continuationQueues[i] != null) {
                continuationQueues[i].destroy();
                continuationQueues[i] = null;
            }
            continuationUses[i].clear();
        }
        continuationIndex = -1;
        gNormal = destroy(gNormal);
        gAlbedo = destroy(gAlbedo);
        gDepth = destroy(gDepth);
        gMotion = destroy(gMotion);
        gSpecAlbedo = destroy(gSpecAlbedo);
        gSpecMotion = destroy(gSpecMotion);
        rrOutput = destroy(rrOutput);
        postColorA = destroy(postColorA);
        postColorB = destroy(postColorB);
    }

    void destroy() {
        destroySizedImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (debugPresentPipeline != null) {
            debugPresentPipeline.destroy();
            debugPresentPipeline = null;
        }
        if (sdrToneLut != null) {
            sdrToneLut.destroy();
            sdrToneLut = null;
        }
        if (hdrToneLut != null) {
            hdrToneLut.destroy();
            hdrToneLut = null;
        }
        if (lookLut != null) {
            lookLut.destroy();
            lookLut = null;
        }
        loadedHdrLutNits = -1;
        displayW = -1;
        displayH = -1;
        renderW = -1;
        renderH = -1;
        renderSizeRrEnabled = false;
        renderSizeRrQuality = Integer.MIN_VALUE;
    }

    private static GpuImage destroy(GpuImage image) {
        if (image != null) {
            image.destroy();
        }
        return null;
    }

    GpuBuffer acquireContinuationQueue(RtGpuExecutor.GraphicsUseWaiter waiter) {
        continuationIndex = (continuationIndex + 1) % continuationQueues.length;
        waiter.await(continuationUses[continuationIndex]);
        return continuationQueues[continuationIndex];
    }

    void markContinuationUse(RtGpuExecutor.GraphicsUse graphicsUse) {
        continuationUses[continuationIndex].mark(graphicsUse);
    }

    static long continuationBytes(int width, int height) {
        long pixels = Math.multiplyExact((long) width, (long) height);
        return Math.multiplyExact(Math.multiplyExact(pixels, PATH_RECORDS_PER_PIXEL),
                PackedPathSegmentData.BYTE_SIZE);
    }
}
