package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.SpatialMedium;
import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

/** Immutable frame inputs and the ownership acquired before command recording begins. */
record RtCapturedFrame(FrameSnapshot inputs, ResourceOwners medium) implements AutoCloseable {
    static RtCapturedFrame capture(FrameSnapshot inputs) {
        var medium = new ResourceOwners();
        try {
            return new RtCapturedFrame(retainedInputs(inputs, medium), medium);
        } catch (Throwable failure) {
            ResourceLifetime.closeAfterFailure(failure, medium::close);
            throw failure;
        }
    }

    private static FrameSnapshot retainedInputs(FrameSnapshot inputs, ResourceOwners resources) {
        var view = inputs.view();
        ViewMedium containing = view.medium() instanceof ViewMedium.Volume<?, ?> volume
                ? retainedMedium(volume, resources) : view.medium();
        SpatialMedium<?, ?> spatial = view.spatialMedium() == null ? null
                : retainedSpatialMedium(view.spatialMedium(), resources);
        return new FrameSnapshot(new SceneView(view.entryScene(), view.camera(), containing, spatial),
                inputs.sceneOrigin(), inputs.proceduralSurfaceAnimationEnabled(),
                inputs.timeSeconds(), inputs.metersPerWorldUnit());
    }

    private static <B, N> ViewMedium.Volume<B, N> retainedMedium(ViewMedium.Volume<B, N> volume,
                                                               ResourceOwners resources) {
        return new ViewMedium.Volume<>(volume.implementation(), resources.data(volume.bindingData()),
                resources.data(volume.instanceData()));
    }

    private static <B, N> SpatialMedium<B, N> retainedSpatialMedium(SpatialMedium<B, N> spatial,
                                                                   ResourceOwners resources) {
        return new SpatialMedium<>(spatial.implementation(), resources.data(spatial.bindingData()),
                resources.data(spatial.instanceData()), spatial.originX(), spatial.originY(), spatial.originZ());
    }

    @Override public void close() { medium.close(); }
}
