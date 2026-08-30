package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.presentation.RtFramePresenter;

import dev.comfyfluffy.caustica.config.CausticaConfig;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.renderer.presentation.PresentationResources;
import dev.comfyfluffy.caustica.renderer.presentation.RtExposure;
import dev.comfyfluffy.caustica.renderer.presentation.RtLookPackage;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceExtent;
import dev.comfyfluffy.caustica.renderer.raytracing.TraceResources;
import dev.comfyfluffy.caustica.nvidia.ngx.DlssRayReconstruction;
import dev.comfyfluffy.caustica.renderer.denoising.DenoiserRoute;

import java.io.IOException;

/** Coordinates trace and presentation resource owners at the root renderer lifetime. */
final class RtFrameResources {
    private final RtFramePresenter presenter;
    private final DlssRayReconstruction rayReconstruction;
    private final TraceResources trace = new TraceResources();
    private final PresentationResources presentation;
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    RtFrameResources(RtFramePresenter presenter, DlssRayReconstruction rayReconstruction,
                     RtLookPackage look, RtExposure.Settings exposureSettings) {
        this.presenter = presenter;
        this.rayReconstruction = rayReconstruction;
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
    boolean ensureSized(VulkanDeviceContext context, int width, int height, DenoiserRoute route,
                        Runnable beforeResizeAfterIdle) {
        boolean rrEnabled = usesRayReconstructionRenderSize(route, rayReconstruction.configured());
        int rrQuality = rrEnabled ? rayReconstruction.quality() : Integer.MIN_VALUE;
        if (presentation.matches(width, height) && trace.hasDisplayExtent(width, height)
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return false;
        }

        presenter.invalidateRenderedFrame();
        context.waitIdle();
        beforeResizeAfterIdle.run();
        int[] optimal = rrEnabled ? rayReconstruction.queryOptimalRenderSize(width, height) : null;
        int renderWidth = optimal != null ? optimal[0] : width;
        int renderHeight = optimal != null ? optimal[1] : height;
        trace.resize(context, new TraceExtent(renderWidth, renderHeight, width, height));
        presentation.resize(context, width, height);
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;
        return true;
    }

    static boolean usesRayReconstructionRenderSize(DenoiserRoute route, boolean configured) {
        return route == DenoiserRoute.RAY_RECONSTRUCTION && configured;
    }

    void destroy() {
        trace.destroy();
        presentation.destroy();
        renderSizeRrEnabled = false;
        renderSizeRrQuality = Integer.MIN_VALUE;
    }
}
