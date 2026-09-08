package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;

import java.util.List;

/** Immutable frame inputs and the ownership acquired before command recording begins. */
record RtCapturedFrame(FrameSnapshot inputs, ResourceOwners medium) implements AutoCloseable {
    static RtCapturedFrame capture(FrameSnapshot inputs) {
        var medium = captureMedium(inputs.view().medium());
        try {
            return new RtCapturedFrame(retainedInputs(inputs, medium), medium);
        } catch (Throwable failure) {
            medium.close();
            throw failure;
        }
    }

    static ResourceOwners captureMedium(ViewMedium medium) {
        return ResourceOwners.capture(medium instanceof ViewMedium.Volume<?, ?> volume
                ? List.of(volume.bindingData(), volume.instanceData()) : List.of());
    }

    private static FrameSnapshot retainedInputs(FrameSnapshot inputs, ResourceOwners resources) {
        if (!(inputs.view().medium() instanceof ViewMedium.Volume<?, ?> volume)) return inputs;
        var view = inputs.view();
        return new FrameSnapshot(new dev.comfyfluffy.caustica.api.view.SceneView(view.entryScene(), view.camera(),
                retainedMedium(volume, resources)), inputs.sceneOrigin(), inputs.proceduralSurfaceAnimationEnabled(),
                inputs.timeSeconds(), inputs.metersPerWorldUnit());
    }

    private static <B, N> ViewMedium.Volume<B, N> retainedMedium(ViewMedium.Volume<B, N> volume,
                                                               ResourceOwners resources) {
        return new ViewMedium.Volume<>(volume.implementation(), resources.data(volume.bindingData()),
                resources.data(volume.instanceData()));
    }

    @Override public void close() { medium.close(); }
}
