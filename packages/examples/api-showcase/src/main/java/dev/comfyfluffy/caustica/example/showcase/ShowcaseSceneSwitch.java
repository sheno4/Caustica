package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.scene.SceneId;
import dev.comfyfluffy.caustica.api.view.Camera;
import dev.comfyfluffy.caustica.api.view.SceneView;
import dev.comfyfluffy.caustica.api.view.ViewMedium;

import java.util.Objects;

/** Chooses between two host-owned scenes without adding scene creation or portal traversal authority. */
record ShowcaseSceneSwitch(SceneId primary, SceneId alternate) {
    ShowcaseSceneSwitch {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(alternate, "alternate");
        if (primary == alternate) throw new IllegalArgumentException("scenes must be distinct");
    }

    SceneView view(boolean useAlternate, Camera camera) {
        return new SceneView(useAlternate ? alternate : primary, camera, ViewMedium.Vacuum.INSTANCE);
    }
}
