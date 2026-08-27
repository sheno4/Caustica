package dev.comfyfluffy.caustica.api.scene;

import java.util.Objects;

/**
 * Immutable root-view facts and explicit writer supplied while collecting one rendered frame.
 */
public record SceneFrameContext(RenderView view,
                                double originX, double originY, double originZ,
                                double metersPerWorldUnit,
                                long frameIndex,
                                SceneFrameWriter writer) {
    public SceneFrameContext {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(writer, "writer");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("scene origin must be finite");
        }
        if (!Double.isFinite(metersPerWorldUnit) || metersPerWorldUnit <= 0.0) {
            throw new IllegalArgumentException("meters per world unit must be finite and positive");
        }
    }

    /** Non-owning reference to the camera's root scene. */
    public SceneId rootScene() {
        return view.rootScene();
    }

    /** Camera drawing from {@link #rootScene()}. */
    public SceneCamera camera() {
        return view.camera();
    }
}
