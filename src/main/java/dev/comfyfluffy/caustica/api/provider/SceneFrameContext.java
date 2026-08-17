package dev.comfyfluffy.caustica.api.provider;

import java.util.Objects;

/** Renderer-neutral inputs shared by all scene providers during one frame. */
public record SceneFrameContext(SceneGeometrySink geometry,
                                double originX, double originY, double originZ,
                                long frameIndex,
                                SceneCamera camera) {
    public SceneFrameContext {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(camera, "camera");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("scene origin must be finite");
        }
    }
}
