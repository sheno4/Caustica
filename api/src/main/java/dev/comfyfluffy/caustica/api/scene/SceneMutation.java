package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.scene.geometry.GeometryChannel;
import dev.comfyfluffy.caustica.api.scene.light.LightChannel;

import java.util.List;
import java.util.Objects;

/**
 * Geometry and light operations published atomically at one frame boundary. The retirement callback
 * follows every value introduced by the mutation until each has been replaced, dropped, removed by a
 * scene/session cascade, and is no longer read by submitted GPU work.
 */
public record SceneMutation(List<GeometryChannel.Operation> geometry,
                            List<LightChannel.Operation> lights,
                            Runnable retired) {
    public SceneMutation {
        geometry = List.copyOf(geometry);
        lights = List.copyOf(lights);
        Objects.requireNonNull(retired, "retired");
        if (geometry.isEmpty() && lights.isEmpty()) {
            throw new IllegalArgumentException("a scene mutation needs at least one operation");
        }
    }

    /** A mutation for which the caller requires no retirement notification. */
    public static SceneMutation of(List<GeometryChannel.Operation> geometry,
                                   List<LightChannel.Operation> lights) {
        return new SceneMutation(geometry, lights, () -> { });
    }
}
