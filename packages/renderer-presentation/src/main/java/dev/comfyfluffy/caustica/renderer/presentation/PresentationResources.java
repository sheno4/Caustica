package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import org.lwjgl.vulkan.VK10;

import java.io.IOException;
import java.util.Objects;

/** Owns presentation pipelines, color state, and display-extent images. */
public final class PresentationResources {
    private final RtLookPackage look;
    private final RtExposure exposure;
    private RtDisplayPipeline displayPipeline;
    private RtDebugPresentPipeline debugPresentPipeline;
    private RtToneLut sdrToneLut;
    private RtToneLut hdrToneLut;
    private RtToneLut lookLut;
    private int loadedHdrLutNits = -1;
    private GpuImage displayImage;
    private GpuImage hdrDisplayImage;
    private GpuImage postColorA;
    private GpuImage postColorB;
    private int width = -1;
    private int height = -1;

    public PresentationResources(RtLookPackage look, RtExposure.Settings exposureSettings) {
        this.look = Objects.requireNonNull(look, "look");
        this.exposure = new RtExposure(look, exposureSettings);
    }

    public RtExposure exposure() { return exposure; }
    public RtDisplayPipeline displayPipeline() { return Objects.requireNonNull(displayPipeline, "displayPipeline"); }
    public RtDebugPresentPipeline debugPresentPipeline() { return Objects.requireNonNull(debugPresentPipeline, "debugPresentPipeline"); }
    public RtToneLut sdrToneLut() { return Objects.requireNonNull(sdrToneLut, "sdrToneLut"); }
    public RtToneLut hdrToneLut() { return Objects.requireNonNull(hdrToneLut, "hdrToneLut"); }
    public RtToneLut lookLut() { return Objects.requireNonNull(lookLut, "lookLut"); }
    public int loadedHdrLutNits() { return loadedHdrLutNits; }
    public GpuImage displayImage() { return Objects.requireNonNull(displayImage, "displayImage"); }
    public GpuImage hdrDisplayImage() { return Objects.requireNonNull(hdrDisplayImage, "hdrDisplayImage"); }
    public GpuImage postColorA() { return Objects.requireNonNull(postColorA, "postColorA"); }
    public GpuImage postColorB() { return Objects.requireNonNull(postColorB, "postColorB"); }

    public void configureExposure(RtExposure.Settings settings) {
        exposure.configure(settings);
    }

    public void ensurePipelines(VulkanDeviceContext context, int wantedHdrNits) throws IOException {
        if (displayPipeline == null) displayPipeline = RtDisplayPipeline.create(context);
        if (debugPresentPipeline == null) debugPresentPipeline = RtDebugPresentPipeline.create(context);
        if (sdrToneLut == null) sdrToneLut = RtToneLut.load(context, "sdr_aces2_rec709.bin");
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
        if (lookLut == null) lookLut = RtToneLut.loadResource(context, look.lmtResource());
    }

    public boolean matches(int wantedWidth, int wantedHeight) {
        return width == wantedWidth && height == wantedHeight && displayImage != null
                && hdrDisplayImage != null && postColorA != null && postColorB != null && exposure.ready();
    }

    /** Replaces the allocation after the caller has drained prior GPU use. */
    public void resize(VulkanDeviceContext context, int wantedWidth, int wantedHeight) {
        destroySized();
        displayImage = context.createStorageImage(wantedWidth, wantedHeight, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                "RT display image " + wantedWidth + "x" + wantedHeight);
        hdrDisplayImage = context.createStorageImage(wantedWidth, wantedHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + wantedWidth + "x" + wantedHeight);
        postColorA = context.createStorageImage(wantedWidth, wantedHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "post chain A " + wantedWidth + "x" + wantedHeight);
        postColorB = context.createStorageImage(wantedWidth, wantedHeight,
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "post chain B " + wantedWidth + "x" + wantedHeight);
        exposure.ensureResources(context);
        width = wantedWidth;
        height = wantedHeight;
    }

    public void destroy() {
        destroySized();
        exposure.destroy();
        if (displayPipeline != null) { displayPipeline.destroy(); displayPipeline = null; }
        if (debugPresentPipeline != null) { debugPresentPipeline.destroy(); debugPresentPipeline = null; }
        if (sdrToneLut != null) { sdrToneLut.destroy(); sdrToneLut = null; }
        if (hdrToneLut != null) { hdrToneLut.destroy(); hdrToneLut = null; }
        if (lookLut != null) { lookLut.destroy(); lookLut = null; }
        loadedHdrLutNits = -1;
    }

    private void destroySized() {
        displayImage = destroy(displayImage);
        hdrDisplayImage = destroy(hdrDisplayImage);
        postColorA = destroy(postColorA);
        postColorB = destroy(postColorB);
        width = -1;
        height = -1;
    }

    private static GpuImage destroy(GpuImage image) {
        if (image != null) image.destroy();
        return null;
    }
}
