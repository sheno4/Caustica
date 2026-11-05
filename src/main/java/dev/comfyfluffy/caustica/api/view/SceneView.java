package dev.comfyfluffy.caustica.api.view;

import java.util.Objects;

/** Camera sampled for one native sky frame. */
public record SceneView(Camera camera) {
    public SceneView { Objects.requireNonNull(camera, "camera"); }
}
