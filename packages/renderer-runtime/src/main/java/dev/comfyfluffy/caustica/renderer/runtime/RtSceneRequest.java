package dev.comfyfluffy.caustica.renderer.runtime;

import dev.comfyfluffy.caustica.engine.scene.RetainedSceneSnapshot;
import dev.comfyfluffy.caustica.engine.scene.SceneOrigin;
import dev.comfyfluffy.caustica.renderer.raytracing.RtProgramBackend;
import dev.comfyfluffy.caustica.support.SharedResource;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Program and scene values captured together before asynchronous renderer preparation. */
record RtSceneRequest(SharedResource<RtProgramBackend.Published> program,
                      SharedResource<RetainedSceneSnapshot> scenes,
                      long publicationCutoff, SceneOrigin origin, float metersPerSceneUnit) implements AutoCloseable {
    static RtSceneRequest capture(Supplier<SharedResource<RtProgramBackend.Published>> programs,
                                  Supplier<SharedResource<RetainedSceneSnapshot>> scenes,
                                  LongSupplier publicationCutoff, SceneOrigin origin, float metersPerSceneUnit) {
        var program = programs.get();
        try {
            long cutoff = publicationCutoff.getAsLong();
            return new RtSceneRequest(program, scenes.get(), cutoff, origin, metersPerSceneUnit);
        } catch (Throwable failure) {
            if (program != null) program.close();
            throw failure;
        }
    }

    Object key() {
        return new Key(program == null ? null : program.reference(), scenes.reference(), publicationCutoff,
                origin, metersPerSceneUnit);
    }

    @Override public void close() {
        try { scenes.close(); }
        finally { if (program != null) program.close(); }
    }

    // Shared reference identities compare revisions without traversing their scene contents.
    private record Key(SharedResource.Reference<RtProgramBackend.Published> program,
                       SharedResource.Reference<RetainedSceneSnapshot> scenes, long cutoff,
                       SceneOrigin origin, float metersPerSceneUnit) { }
}
