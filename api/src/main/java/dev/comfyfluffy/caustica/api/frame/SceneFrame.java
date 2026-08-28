package dev.comfyfluffy.caustica.api.frame;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;

import java.util.Objects;

/**
 * Immutable selected-scene/view facts and explicit writer supplied while collecting one rendered frame.
 * Positions and the origin are in the selected scene's coordinate units. {@code metersPerSceneUnit} is
 * copied from that scene's immutable {@code SceneDefinition}; it is not independently frame-varying.
 *
 * <p>{@code originX/Y/Z} is the renderer's double-precision rebase origin for this frame. Camera positions
 * and geometry translations remain absolute scene coordinates; callers must not subtract this origin when
 * submitting them. The renderer subtracts it when producing float GPU coordinates. A provider uses the
 * origin only when preparing auxiliary frame-local data that must match those rebased GPU coordinates.
 */
public record SceneFrame(SceneView view,
                         double originX, double originY, double originZ,
                         double metersPerSceneUnit,
                         long frameIndex,
                         SceneFrameWriter writer) {
    public SceneFrame {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(writer, "writer");
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("scene origin must be finite");
        }
        if (!Double.isFinite(metersPerSceneUnit) || metersPerSceneUnit <= 0.0) {
            throw new IllegalArgumentException("meters per scene unit must be finite and positive");
        }
    }

    /** Non-owning reference to the scene selected by this frame's view. */
    public SceneId scene() {
        return view.scene();
    }

    /** Camera drawing from {@link #scene()}. */
    public Camera camera() {
        return view.camera();
    }
}
