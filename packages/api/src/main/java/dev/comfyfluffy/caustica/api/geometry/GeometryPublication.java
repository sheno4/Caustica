package dev.comfyfluffy.caustica.api.geometry;

/** Thread-safe polling receipt for one accepted retained-geometry publication. */
@FunctionalInterface
public interface GeometryPublication {
    /** True after the renderer has committed the publication to its native scene. */
    boolean isVisible();

    /** Receipt for a submission which required no native publication. */
    static GeometryPublication alreadyVisible() {
        return () -> true;
    }
}
