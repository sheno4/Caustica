package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;

import java.io.IOException;

/** Coordinates trace and presentation resource owners at the root renderer lifetime. */
final class RtFrameResources {
    private final RtFramePresenter presenter;
    private final TraceResources trace = new TraceResources();
    private final PresentationResources presentation;
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    RtFrameResources(RtFramePresenter presenter, RtLookPackage look, RtExposure.Settings exposureSettings) {
        this.presenter = presenter;
        this.presentation = new PresentationResources(look, exposureSettings);
    }

    TraceResources trace() {
        return trace;
    }

    PresentationResources presentation() {
        return presentation;
    }

    void ensurePresentationPipelines(VulkanDeviceContext context) throws IOException {
        presentation.ensurePipelines(context, CausticaConfig.Rt.Hdr.PEAK_NITS.value());
    }

    /** Resizes both owners after one shared drain of all prior frame use. */
    boolean ensureSized(VulkanDeviceContext context, int width, int height) {
        boolean rrEnabled = RtDlssRr.configured();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (presentation.matches(width, height) && trace.hasDisplayExtent(width, height)
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return false;
        }

        presenter.invalidateRenderedFrame();
        context.waitIdle();
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        int renderWidth = optimal != null ? optimal[0] : width;
        int renderHeight = optimal != null ? optimal[1] : height;
        trace.resize(context, new TraceExtent(renderWidth, renderHeight, width, height));
        presentation.resize(context, width, height);
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;
        return true;
    }

    void destroy() {
        trace.destroy();
        presentation.destroy();
        renderSizeRrEnabled = false;
        renderSizeRrQuality = Integer.MIN_VALUE;
    }
}
