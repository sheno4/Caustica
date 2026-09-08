package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import java.util.function.Supplier;

/** Frame execution pulls an owning snapshot from the retained database. */
public interface RetainedSceneBackend {
    /** Supplies atomic scene captures to renderer preparation. */
    void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture);

    default void settleFrameUses() {
    }

    default void prepareForSessionClose() {
    }
}
