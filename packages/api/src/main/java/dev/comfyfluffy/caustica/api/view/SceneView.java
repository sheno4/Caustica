package dev.comfyfluffy.caustica.api.view;

import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/** One camera, its entry scene, containing volume, and optional spatial medium. */
public record SceneView(SceneId entryScene, Camera camera, ViewMedium medium,
                        SpatialMedium<?, ?> spatialMedium) {
    /** Creates a view with no spatial medium. */
    public SceneView(SceneId entryScene, Camera camera, ViewMedium medium) {
        this(entryScene, camera, medium, null);
    }

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
