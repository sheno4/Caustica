package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/** Renderer-neutral inputs for retained geometry published at the update cadence. */
public record SceneGeometryUpdateContext(SceneGeometrySink geometry,
                                         double originX, double originY, double originZ) {
    public SceneGeometryUpdateContext {
        Objects.requireNonNull(geometry, "geometry");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("scene origin must be finite");
        }
    }
}
