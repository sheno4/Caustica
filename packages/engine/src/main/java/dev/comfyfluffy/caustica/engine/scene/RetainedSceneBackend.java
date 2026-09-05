package dev.comfyfluffy.caustica.engine.scene;

import dev.comfyfluffy.caustica.support.SharedResource;
import java.util.function.Supplier;

/** Frame execution pulls an owning snapshot from the retained database. */
public interface RetainedSceneBackend {
    /** Capture runs at the frame boundary on the program publication/control thread. */
    void bind(Supplier<SharedResource<RetainedSceneSnapshot>> capture);

    default void progress() {
    }

    default void settleFrameUses() {
    }

    default void prepareForSessionClose() {
    }
}
