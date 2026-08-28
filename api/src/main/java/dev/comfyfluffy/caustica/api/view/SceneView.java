package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/** One camera drawing from one selected scene. A scene never owns a camera. */
public record SceneView(SceneId scene, Camera camera) {
    public SceneView {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(camera, "camera");
    }
}
