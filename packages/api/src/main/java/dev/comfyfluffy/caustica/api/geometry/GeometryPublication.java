package dev.comfyfluffy.caustica.api.geometry;

/** Thread-safe visibility receipt for one accepted retained-geometry publication. */
public interface GeometryPublication {
    /** True after the renderer has committed the publication to its native scene. */
    boolean isVisible();

    /** Runs {@code callback} once the renderer has committed this publication. */
    void whenVisible(Runnable callback);

    /** Receipt for a submission which required no native publication. */
    static GeometryPublication alreadyVisible() {
        return new GeometryPublication() {
            @Override public boolean isVisible() { return true; }
            @Override public void whenVisible(Runnable callback) {
                java.util.Objects.requireNonNull(callback, "callback").run();
            }
        };
    }
}
