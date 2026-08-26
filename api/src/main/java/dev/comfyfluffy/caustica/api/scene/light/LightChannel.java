package dev.comfyfluffy.caustica.api.scene.light;

import dev.comfyfluffy.caustica.api.scene.AtomicBatch;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.List;
import java.util.Objects;

/**
 * Retained lights, reached from {@link CausticaApi#lights()}. The same shape as retained geometry: one
 * engine-owned collection, issued ids, batched atomic updates, nothing reset on the renderer's schedule.
 *
 * <p>A light is placed into a scene, in that scene's coordinate system, and is gathered by the sampling
 * structures that scene owns. Light selection is therefore scene-local: a shading point draws candidates
 * from its own scene's lights and from no other's.
 *
 * <p>Lights are not derived from geometry. The renderer sees no emission on a triangle, so an emissive
 * surface is lit by retaining a light beside it — Minecraft turns every emissive block face into a
 * rectangle. That is what keeps the acceleration path free of shading data.
 */
public interface LightChannel {
    /** A fresh light id. Cheap, thread-safe, and does not choose a scene; {@link SetLight} does. */
    LightId newLight();

    /**
     * Apply operations, with the contract {@link AtomicBatch} describes and the synchronous validation the
     * geometry channel has. A batch may span scenes.
     *
     * <p>A descriptor is copied, but the buffer an {@link LightDescriptor.Emission} points at is not. The
     * callback of the batch that retained the descriptor runs after replacement, removal, or a scene
     * cascade and after the last GPU read, which is when the source may reuse that buffer.
     *
     * @throws IllegalArgumentException if any operation names an id this channel did not issue, a scene
     *         {@link SceneChannel} did not issue, or one belonging to an earlier
     *         {@link SceneChannel#generation()}
     * @throws IllegalStateException if no render session is active
     */
    void submit(List<AtomicBatch<Operation>> batches);

    sealed interface Operation permits SetLight, DropLight { }

    /**
     * Create or replace one light in one scene. Setting a light that already exists with a different scene
     * moves it, because a light exists in exactly one. Batches from one source apply in submission order.
     */
    record SetLight(LightId light, SceneId scene, LightDescriptor descriptor) implements Operation {
        public SetLight {
            Objects.requireNonNull(light, "light");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Remove one light. */
    record DropLight(LightId light) implements Operation {
        public DropLight {
            Objects.requireNonNull(light, "light");
        }
    }
}
