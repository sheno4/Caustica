package dev.comfyfluffy.caustica.api.retained;

/** Thread-safe visibility receipt for one accepted retained-scene revision. */
public interface RetainedPublication {
    /** True after the renderer has committed the revision to its native scene. */
    boolean isVisible();

    /** Runs {@code callback} once the renderer has committed this revision. */
    void whenVisible(Runnable callback);

    /** Receipt for a submission which required no native publication. */
    static RetainedPublication alreadyVisible() {
        return new RetainedPublication() {
            @Override public boolean isVisible() { return true; }
            @Override public void whenVisible(Runnable callback) {
                java.util.Objects.requireNonNull(callback, "callback").run();
            }
        };
    }
}
