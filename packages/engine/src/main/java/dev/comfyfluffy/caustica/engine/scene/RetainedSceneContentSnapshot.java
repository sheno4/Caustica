package dev.comfyfluffy.caustica.engine.scene;

import java.util.List;

/** Immutable light and environment publication which reuses the preceding native geometry revision. */
public record RetainedSceneContentSnapshot(long revision,
                                           List<RetainedSceneSnapshot.Scene> scenes,
                                           List<RetainedSceneSnapshot.Light> lights) {
    public RetainedSceneContentSnapshot {
        scenes = List.copyOf(scenes);
        lights = List.copyOf(lights);
    }
}
