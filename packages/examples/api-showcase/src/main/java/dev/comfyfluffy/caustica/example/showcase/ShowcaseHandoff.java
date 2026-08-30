package dev.comfyfluffy.caustica.example.showcase;

import dev.comfyfluffy.caustica.api.light.LightId;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Process-owned rendezvous for selections shared by two contributions in the same world session. */
final class ShowcaseHandoff {
    record Selections(ShowcasePrograms.Exports programs, List<LightId> lights,
                      BooleanSupplier programsReady) {
        Selections {
            java.util.Objects.requireNonNull(programs, "programs");
            lights = List.copyOf(lights);
            if (lights.isEmpty()) throw new IllegalArgumentException("at least one shared light is required");
            java.util.Objects.requireNonNull(programsReady, "programsReady");
        }

        boolean ready() {
            return programsReady.getAsBoolean();
        }
    }

    private final IdentityHashMap<SceneId, Selections> sessions = new IdentityHashMap<>();

    synchronized void publish(SceneId scene, Selections selections) {
        if (sessions.putIfAbsent(scene, selections) != null) {
            throw new IllegalStateException("showcase selections already published for this scene");
        }
    }

    synchronized Selections require(SceneId scene) {
        Selections selections = sessions.get(scene);
        if (selections == null) throw new IllegalStateException("showcase selection owner must open first");
        return selections;
    }

    synchronized void remove(SceneId scene, Selections selections) {
        sessions.remove(scene, selections);
    }
}
