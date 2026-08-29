package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/** One camera drawing from one root scene. A scene never owns a camera. */
public record SceneView(SceneId rootScene, Camera camera) {
    public SceneView {
        Objects.requireNonNull(rootScene, "rootScene");
        Objects.requireNonNull(camera, "camera");
    }
}
