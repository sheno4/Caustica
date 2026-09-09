package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import org.lwjgl.vulkan.VK10;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.io.IOException;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;

/** Owns presentation pipelines, color state, and display-extent images. */
public final class PresentationResources {
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

    public PresentationResources(RtExposure.Settings exposureSettings) {
        this.exposure = new RtExposure(exposureSettings);
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
        if (lookLut == null) lookLut = RtToneLut.load(context, "lmt.bin");
    }

    public boolean matches(int wantedWidth, int wantedHeight) {
        return displayImage != null && displayImage.width() == wantedWidth && displayImage.height() == wantedHeight
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
    }

    public void destroy() {
        List<Runnable> releases = new ArrayList<>();
        releases.add(this::destroySized);
        releases.add(exposure::destroy);
        if (displayPipeline != null) releases.add(displayPipeline::destroy);
        if (debugPresentPipeline != null) releases.add(debugPresentPipeline::destroy);
        if (sdrToneLut != null) releases.add(sdrToneLut::destroy);
        if (hdrToneLut != null) releases.add(hdrToneLut::destroy);
        if (lookLut != null) releases.add(lookLut::destroy);
        displayPipeline = null;
        debugPresentPipeline = null;
        sdrToneLut = null;
        hdrToneLut = null;
        lookLut = null;
        loadedHdrLutNits = -1;
        new ResourceLifetime(releases.toArray(Runnable[]::new)).close();
    }

    private void destroySized() {
        List<Runnable> releases = new ArrayList<>();
        for (GpuImage image : new GpuImage[]{displayImage, hdrDisplayImage, postColorA, postColorB}) {
            if (image != null) releases.add(image::destroy);
        }
        displayImage = null;
        hdrDisplayImage = null;
        postColorA = null;
        postColorB = null;
        new ResourceLifetime(releases.toArray(Runnable[]::new)).close();
    }
}
