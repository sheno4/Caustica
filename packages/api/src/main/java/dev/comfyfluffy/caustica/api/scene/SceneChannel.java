package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.light.LightId;

import java.util.List;

/**
 * Thread-safe owner-local mutations. Each call to edit publishes its entire batch as one atomic revision.
 * A batch can span scenes and combine placements, lights, and environment selections. It performs no
 * GPU preparation or submission and does not change frames already captured by the renderer.
 */
public interface SceneChannel {
    InstanceId newInstance();
    LightId newLight();

    /**
     * Validates and applies all edits, or changes nothing. Meshes must already be ready.
     * Accepted entries retain independent mesh and shader-data claims before returning.
     * The caller keeps ownership of every supplied handle.
     */
    void edit(List<? extends SceneEdit> edits);
}
