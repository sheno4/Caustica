package dev.comfyfluffy.caustica.api.scene;

import java.util.Objects;

/** A camera drawing from one root scene. A scene never owns a camera. */
public record RenderView(SceneId rootScene, SceneCamera camera) {
    public RenderView {
        Objects.requireNonNull(rootScene, "rootScene");
        Objects.requireNonNull(camera, "camera");
    }
}
