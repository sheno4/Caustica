package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/** One camera whose primary rays begin in one entry scene and one containing medium. */
public record SceneView(SceneId entryScene, Camera camera, ViewMedium medium) {
    /** Creates a view whose camera begins in vacuum. */
    public SceneView(SceneId entryScene, Camera camera) {
        this(entryScene, camera, ViewMedium.Vacuum.INSTANCE);
    }

    public SceneView {
        Objects.requireNonNull(entryScene, "entryScene");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(medium, "medium");
    }
}
