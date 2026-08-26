package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.scene.AtomicBatch;
import dev.comfyfluffy.caustica.api.CausticaApi;
import dev.comfyfluffy.caustica.api.scene.SceneChannel;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.List;
import java.util.Objects;

/**
 * Retained meshes and their placements, reached from {@link CausticaApi#geometry()}.
 *
 * <p>One collection, renderer-owned. Extensions do not get their own — ids are issued, so they cannot
 * collide, and there is nothing for a per-source namespace to protect. A {@link SceneId} is not a
 * namespace either: it is where a placement <em>is</em>, not who submitted it, and several sources fill one
 * scene. Thread-safe and long-lived: submit from a chunk-build worker, an entity tick, or the render
 * thread.
 *
 * <p><b>A mesh belongs to no scene.</b> It is an acceleration-structure input, and a build does not know
 * which top-level structure will reference it; only {@link SetInstance} names a scene. So a model placed in
 * two scenes is built once.
 *
 * <p><b>The renderer never clears the collection.</b> Retained objects persist until whoever submitted them
 * drops them, so one extension reloading cannot disturb another's. There is deliberately no "clear
 * everything" call: a source drops the ids it holds, which is exactly the bookkeeping it already has. The
 * two things that do empty it are outside this channel — {@link SceneChannel#dropScene} for one scene's
 * placements, {@link SceneChannel#generation()} for all of it.
 */
public interface GeometryChannel {
    /** A fresh mesh id. Cheap, thread-safe, and does nothing until a {@link SetMesh} uses it. */
    MeshId newMesh();

    /** A fresh placement id. Cheap, thread-safe, and does not choose a scene; {@link SetInstance} does. */
    InstanceId newInstance();

    /**
     * Apply operations. Each {@link AtomicBatch} publishes independently of the others; the call itself is
     * only a way to hand over several at once.
     *
     * <p>A batch may span scenes. That is the point of batching over one collection rather than one per
     * scene: two placements that must not be seen apart — the two sides of a linked pair, a model handed
     * from one scene to another — publish together.
     *
     * <p><b>Validation is synchronous.</b> Every way a batch can be invalid — an unknown or stale id, a
     * placement naming a mesh that neither exists nor is created earlier in the same batch, a placement
     * naming a scene that was never issued or has been dropped, a malformed build — is decided before this
     * returns, and throws. Nothing is applied if anything throws.
     *
     * <p>See {@link AtomicBatch} for what a batch guarantees, how to choose its granularity, and when its
     * retirement callback runs.
     *
     * <p>Acceptance is deliberately not a callback, because the question it answers is "may I update my own
     * bookkeeping now". Dropping a mesh and then forgetting the id is only safe if the drop is known to
     * have been accepted; learning that asynchronously would mean either holding every id until a callback
     * or orphaning meshes whose batch was refused.
     *
     * <p>A failure the renderer only discovers later — an acceleration build that fails on the GPU — is not
     * reported here. The mesh does not appear and its batch's retirement runs, so the source reclaims its
     * buffers the same way it would for any other release.
     *
     * @throws IllegalArgumentException if any operation names an id this channel did not issue, a scene
     *         {@link SceneChannel} did not issue, or one belonging to an earlier
     *         {@link SceneChannel#generation()}
     * @throws IllegalStateException if no render session is active
     */
    void submit(List<AtomicBatch<Operation>> batches);

    sealed interface Operation permits SetMesh, DropMesh, SetInstance, DropInstance { }

    /**
     * Retain or replace a mesh. The source keeps every buffer and its contents unchanged until the batch
     * that displaces this build reports retirement — which is also when the buffers this one displaces come
     * back. A rejected submission changes nothing and releases nothing.
     */
    record SetMesh(MeshId mesh, MeshBuild build) implements Operation {
        public SetMesh {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(build, "build");
        }
    }

    /** Remove a mesh and every placement of it, in every scene. Its buffers come back through this batch's retirement. */
    record DropMesh(MeshId mesh) implements Operation {
        public DropMesh {
            Objects.requireNonNull(mesh, "mesh");
        }
    }

    /**
     * Create or replace one placement of a mesh, in one scene. Setting an instance that already exists
     * moves it, so there is no separate transform operation; setting it with a different scene moves it
     * between scenes, because a placement exists in exactly one.
     *
     * <p>{@code transform} is expressed in {@code scene}'s coordinate system. Each scene has its own, and
     * the renderer rebases each against its own origin, so a position means nothing without the scene it
     * was submitted with.
     *
     * <p>{@code mask} is the 8-bit ray visibility mask compared against a trace's cull mask. It selects
     * what a ray sees within a scene and has nothing to do with which scene that is — scene membership is
     * this operation's {@link SceneId}, so it is not bounded by eight.
     *
     * <p>{@code properties} is an uninterpreted 64-bit word reaching the surface for this placement only —
     * the third and last of them, beside the per-geometry and per-material words. It is what lets one mesh
     * be placed many times and still shade differently: a team colour, an animation frame, a per-chunk
     * light sample, an index into a buffer the source published. Without it a source would have to
     * duplicate the mesh per placement, which defeats the point of placing it.
     *
     * <p>Shading only. The renderer derives motion vectors from this placement's current and previous
     * transforms, so rigid per-instance motion is handled, but a word that moves geometry — vertex
     * animation, per-instance deformation — desynchronises them and ghosts the result. Geometry that
     * differs per placement is a different mesh.
     */
    record SetInstance(InstanceId instance, SceneId scene, MeshId mesh, GeometryTransform transform,
                       int mask, long properties) implements Operation {
        public SetInstance {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(transform, "transform");
            if ((mask & ~0xFF) != 0) {
                throw new IllegalArgumentException("visibility mask must fit in eight bits");
            }
        }
    }

    /** Remove one placement, leaving its mesh retained. */
    record DropInstance(InstanceId instance) implements Operation {
        public DropInstance {
            Objects.requireNonNull(instance, "instance");
        }
    }
}
