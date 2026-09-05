package dev.comfyfluffy.caustica.api.scene;
import dev.comfyfluffy.caustica.api.geometry.InstanceId;
import dev.comfyfluffy.caustica.api.light.LightId;
import java.util.List;
/**
 * Thread-safe owner-local mutations. Each edit is directly visible as one atomic scene revision.
 * An edit can span scenes and combine placements, lights, and environment selections. It performs no
 * GPU preparation or submission and does not change frames already captured by the renderer.
 */
public interface SceneChannel {
    InstanceId newInstance();
    LightId newLight();
    /** Validates and applies all edits, or changes nothing. Meshes must already be ready. */
    void edit(List<? extends SceneEdit> edits);
}
