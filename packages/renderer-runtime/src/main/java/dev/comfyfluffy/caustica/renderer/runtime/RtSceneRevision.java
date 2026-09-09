package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.engine.program.ProgramComposition;
import dev.comfyfluffy.caustica.renderer.raytracing.scene.RtRetainedSceneBackend;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;

import java.util.List;

/** Completed renderer scene data and the exact program used to resolve its implementation indices. */
record RtSceneRevision(SharedResource<RtProgramBackend.Published> program,
                       SharedResource<RtRetainedSceneBackend.PreparedSceneRevision> scenes,
                       SharedResource<RtRetainedSceneBackend.PreparedTraceRevision> traceScenes,
                       long publicationCutoff) implements AutoCloseable {
    private static final ProgramComposition EMPTY_COMPOSITION = new ProgramComposition(List.of());

    static SharedResource<RtSceneRevision> prepare(RtSceneRequest request, RtRetainedSceneBackend backend) {
        var program = request.program() == null ? null : request.program().get();
        var scenes = backend.prepareSceneRevision(request.scenes(),
                program == null ? EMPTY_COMPOSITION : program.composition());
        SharedResource<RtRetainedSceneBackend.PreparedTraceRevision> trace = null;
        try {
            if (program != null) trace = backend.prepareTraceRevision(scenes,
                    request.origin(), program.pipeline(), request.metersPerSceneUnit());
            return SharedResource.owned(new RtSceneRevision(
                    request.program() == null ? null : request.program().retain(), scenes, trace,
                    request.publicationCutoff()), RtSceneRevision::close);
        } catch (Throwable failure) {
            var preparedTrace = trace;
            ResourceLifetime.closeAfterFailure(failure, () -> {
                if (preparedTrace != null) preparedTrace.close();
            }, scenes::close);
            throw failure;
        }
    }

    @Override
    public void close() {
        new ResourceLifetime(() -> {
            if (traceScenes != null) traceScenes.close();
        }, scenes::close, () -> {
            if (program != null) program.close();
        }).close();
    }
}
