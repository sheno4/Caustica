package dev.comfyfluffy.caustica.api.light;

import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/**
 * Retained lights, reached from {@link RenderSessionContext#lights()}. The same shape as retained geometry: one
 * engine-owned collection, issued ids, batched atomic updates, nothing reset on the renderer's schedule.
 *
 * <p>A light id is a mutation capability local to the contribution whose context issued it. A light is
 * placed into a same-session scene, in that scene's coordinate system, and is gathered by the sampling
 * structures that scene owns. Light selection is therefore scene-local: a shading point draws candidates
 * from its own scene's lights and from no other's.
 *
 * <p>Lights are not derived from geometry. The renderer sees no emission on a triangle, so an emissive
 * surface is lit by retaining a light beside it — Minecraft turns every emissive block face into a
 * rectangle. That is what keeps the acceleration path free of shading data.
 *
 * <p>All methods are thread-safe.
 */
public interface LightChannel {
    /** A fresh light id. Cheap, thread-safe, and does not choose a scene; {@link SetLight} does. */
    LightId newLight();

    /**
     * Apply operations, with the contract {@link RetainedBatch} describes and the synchronous validation the
     * geometry channel has. A batch may span scenes.
     *
     * @throws IllegalArgumentException if an operation mutates a light id not issued by this contribution,
     *         names a stale scene reference, or uses an identity from another render session
     */
    void submit(RetainedBatch<Operation> batch);

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

    /**
     * Remove the current light while retaining its issued id. If no light is current because it was
     * already dropped or its scene closed, this is a no-op; the id may be set again.
     */
    record DropLight(LightId light) implements Operation {
        public DropLight {
            Objects.requireNonNull(light, "light");
        }
    }
}
