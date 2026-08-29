package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.pipeline.RtDebugPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtToneLut;
import dev.comfyfluffy.caustica.rt.pass.RenderPassManager;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;

/** Owns images, buffers, LUTs, and pipelines whose validity is tied to the current frame extent. */
final class RtFrameResources {
    private final RtFramePresenter presenter;
    private static final long PATH_RECORD_BYTES = 48L;
    RtDisplayPipeline displayPipeline;
    RtDebugPresentPipeline debugPresentPipeline;
    RtToneLut sdrToneLut;
    RtToneLut hdrToneLut;
    RtToneLut lookLut;
    int loadedHdrLutNits = -1;

    GpuImage output;
    GpuBuffer continuationQueue;
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
    final RtExposure exposure = new RtExposure();

    int displayW = -1;
    int displayH = -1;
    int renderW = -1;
    int renderH = -1;
    boolean renderSizeRrEnabled;
    int renderSizeRrQuality = Integer.MIN_VALUE;

    RtFrameResources(RtFramePresenter presenter) {
        this.presenter = presenter;
    }

    void bindGuideImages(RtPipeline pipeline) {
        if (pipeline == null || gNormal == null) {
            return;
        }
        pipeline.setExtraStorageImage(0, gNormal.view());
        pipeline.setExtraStorageImage(1, gAlbedo.view());
        pipeline.setExtraStorageImage(2, gDepth.view());
        pipeline.setExtraStorageImage(3, gMotion.view());
        pipeline.setExtraStorageImage(4, gSpecAlbedo.view());
        pipeline.setExtraStorageImage(5, gSpecMotion.view());
    }

    void ensurePresentationPipelines(GpuContext context, RtLookPackage look) throws IOException {
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
    boolean ensureSized(GpuContext context, int width, int height, RenderPassManager passManager,
            RtPipeline worldPipeline) {
        boolean rrEnabled = RtDlssRr.configured();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueue != null && displayImage != null && hdrDisplayImage != null
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
        long pixelRecords = Math.multiplyExact((long) renderW, (long) renderH);
        long continuationBytes = Math.multiplyExact(Math.multiplyExact(pixelRecords, 2L), PATH_RECORD_BYTES);
        continuationQueue = context.createBuffer(continuationBytes, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                false, "path continuation queue " + renderW + "x" + renderH + "x2");
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
        passManager.resize(width, height);
        passManager.setReconstructedColor(rrOutput);
        passManager.setSceneColorTargets(postColorA, postColorB);
        passManager.setExposureImage(exposure.image());
        displayPipeline.invalidateImages();
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view());
            bindGuideImages(worldPipeline);
        }
        debugPresentPipeline.setImages(displayImage.view(), gNormal.view(), gAlbedo.view(), gDepth.view(),
                gMotion.view(), gSpecAlbedo.view(), gSpecMotion.view(), rrOutput.view(), exposure.image().view(),
                exposure.stateBuffer());
        return true;
    }

    void destroySizedImages() {
        displayImage = destroy(displayImage);
        hdrDisplayImage = destroy(hdrDisplayImage);
        output = destroy(output);
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
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
}
