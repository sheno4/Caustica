package dev.comfyfluffy.caustica.engine.scene;

/** Direct retained database replacement. apply performs no GPU allocation or submission. */
public interface RetainedSceneBackend {
    /** Captures independent ownership before returning; a throw leaves the preceding snapshot current. */
    void apply(RetainedSceneSnapshot snapshot);

    default void progress() {
    }

    default void settleFrameUses() {
    }

    default void prepareForSessionClose() {
    }
}
