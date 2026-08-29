package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.Objects;

/**
 * Retained meshes and their placements, reached from {@link RenderSessionContext#geometry()}.
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
 * placements are also removed when their target scene closes, and everything remaining is removed by the
 * owning session scope.
 */
public interface GeometryChannel {
    /**
     * A fresh mesh id with an immutable placement-data schema. Cheap, thread-safe, and does nothing until a
     * {@link SetMesh} uses it. Naming the schema here preserves its runtime identity before type erasure and
     * lets {@code var mesh = geometry.newMesh(INSTANCE_DATA)} infer a useful Java type.
     */
    <N> MeshId<N> newMesh(ShaderDataType<N> instanceDataType);

    /** A fresh placement id. Cheap, thread-safe, and does not choose a scene; {@link SetInstance} does. */
    InstanceId newInstance();

    /**
     * Apply one atomic batch of operations.
     *
     * <p>A batch may span scenes. That is the point of batching over one collection rather than one per
     * scene: two placements that must not be seen apart — the two sides of a linked pair, a model handed
     * from one scene to another — publish together.
     *
     * <p><b>Validation is synchronous.</b> Every way a batch can be invalid — an unknown or stale id, a
     * placement naming a mesh that neither exists nor is created earlier in the same batch, a placement
     * naming a scene that was never issued or has been dropped, a shader-data token which does not match
     * the schema carried by its program or mesh id, a mesh naming a surface or volume outside its own
     * contribution, cutout geometry naming a surface with no coverage implementation, a malformed
     * build — is decided before this returns, and throws. A mesh, its
     * placements, and its shading programs belong to one contribution scope; {@link SceneId} is the
     * deliberate cross-contribution reference. Nothing is applied if anything throws.
     *
     * <p>See {@link RetainedBatch} for what a batch guarantees, how to choose its granularity, and how its
     * retirement callback follows the retained data that batch introduces.
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
     * @throws IllegalArgumentException if any operation names an id this session did not issue, a stale
     *         scene reference, an identity from another render session, a non-scene identity from another
     *         contribution, or shader data with a mismatched schema token
     */
    void submit(RetainedBatch<Operation> batch);

    sealed interface Operation permits SetMesh, DropMesh, SetInstance, DropInstance { }

    /**
     * Retain or replace a mesh. The source keeps every buffer and its contents unchanged until this batch
     * reports retirement after the build is later replaced, dropped, fails, or the session closes. Closing
     * a scene removes placements in that scene, never this scene-independent mesh.
     * A rejected submission changes nothing and does not take ownership of the callback.
     */
    record SetMesh<N>(MeshId<N> mesh, MeshBuild<N> build) implements Operation {
        public SetMesh {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(build, "build");
        }
    }

    /**
     * Remove a mesh and every placement of it, in every scene. The batch that retained its current build
     * reports when those buffers are free.
     */
    record DropMesh<N>(MeshId<N> mesh) implements Operation {
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
     * <p>{@code instanceData} is a typed 64-bit word reaching the selected surface and volume for
     * this placement only — the last of the implementation, slot-binding, and instance words. It is what lets one mesh
     * be placed many times and still shade differently: a team colour, an animation frame, a per-chunk
     * light sample, an index into a buffer the source published. Its schema must be the one carried by the
     * mesh ID and every shader slot in that mesh; the renderer checks token identity synchronously even
     * when raw Java types bypass compile-time checking. Without it a source would have to
     * duplicate the mesh per placement, which defeats the point of placing it.
     * The callback of the batch containing this operation follows that word until the placement is replaced,
     * dropped, or removed with its scene.
     *
     * <p>Shading only. The renderer derives motion vectors from this placement's current and previous
     * transforms, so rigid per-instance motion is handled, but a word that moves geometry — vertex
     * animation, per-instance deformation — desynchronises them and ghosts the result. Geometry that
     * differs per placement is a different mesh.
     */
    record SetInstance<N>(InstanceId instance, SceneId scene, MeshId<N> mesh,
                          GeometryTransform transform, int mask,
                          ShaderData<N> instanceData) implements Operation {
        public SetInstance {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(instanceData, "instanceData");
            if ((mask & ~0xFF) != 0) {
                throw new IllegalArgumentException("visibility mask must fit in eight bits");
            }
        }
    }

    /**
     * Remove the current placement, leaving its mesh and issued instance id retained. If no placement is
     * current because it was already dropped or its scene closed, this is a no-op; the id may be set again.
     */
    record DropInstance(InstanceId instance) implements Operation {
        public DropInstance {
            Objects.requireNonNull(instance, "instance");
        }
    }
}
