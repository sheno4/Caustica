package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.api.view.ViewMedium;
import dev.comfyfluffy.caustica.engine.frame.FrameSnapshot;
import dev.comfyfluffy.caustica.engine.resource.ResourceOwners;
import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.support.SharedResource;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Immutable frame inputs and the ownership acquired before command recording begins. */
record RtCapturedFrame(FrameSnapshot inputs, SharedResource<RtProgramBackend.Published> program,
                       SharedResource<RetainedSceneSnapshot> scenes, ResourceOwners medium,
                       long publicationCutoff) implements AutoCloseable {
    static RtCapturedFrame capture(FrameSnapshot inputs,
                                   Supplier<SharedResource<RtProgramBackend.Published>> programs,
                                   Supplier<SharedResource<RetainedSceneSnapshot>> scenes,
                                   LongSupplier publicationCutoff) {
        var medium = captureMedium(inputs.view().medium());
        SharedResource<RtProgramBackend.Published> program = null;
        try {
            program = programs.get();
            long cutoff = publicationCutoff.getAsLong();
            return new RtCapturedFrame(retainedInputs(inputs, medium), program, scenes.get(), medium, cutoff);
        } catch (Throwable failure) {
            if (program != null) program.close();
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

    @Override public void close() {
        try { scenes.close(); }
        finally {
            try { if (program != null) program.close(); }
            finally { medium.close(); }
        }
    }
}
