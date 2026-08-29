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
 * <p>The renderer owns one collection per session and issues collision-free ids. A {@link SceneId} selects
 * a placement's target scene. The channel is thread-safe and may be used from worker, tick, or render
 * threads.
 *
 * <p>Meshes are session-scoped acceleration-structure inputs. Only {@link SetInstance} assigns a scene, so
 * one mesh may be placed in multiple scenes.
 *
 * <p>Retained objects persist until their owner drops them. Placements are also removed when their target
 * scene closes, and the session removes all remaining objects during teardown.
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
     * <p>A batch may span scenes. Use one batch for placements that must become visible together.
     *
     * <p>Validation is synchronous. Invalid input includes an unknown or stale id, a
     * placement naming a mesh that neither exists nor is created earlier in the same batch, a placement
     * naming a scene that was never issued or has been dropped, a shader-data token which does not match
     * the schema carried by its program or mesh id, a mesh naming a surface or volume outside its own
     * contribution, cutout geometry naming a surface with no coverage implementation, a malformed
     * build. Validation completes before this method returns. A mesh, its
     * placements, and its shading programs belong to one contribution scope; {@link SceneId} is the
     * supported cross-contribution reference. Nothing is applied if anything throws.
     *
     * <p>See {@link RetainedBatch} for what a batch guarantees, how to choose its granularity, and how its
     * retirement callback follows the retained data that batch introduces.
     *
     * <p>Acceptance is synchronous, so callers may update their bookkeeping after this method returns.
     *
     * <p>Asynchronous GPU build failures are not reported by this method. A failed mesh remains unpublished,
     * and its batch's retirement callback runs after its buffers are no longer in use.
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
     * moves it. Setting it with a different scene moves it between scenes; a placement exists in one scene.
     *
     * <p>{@code transform} is expressed in {@code scene}'s coordinate system. Each scene has its own, and
     * the renderer rebases each against its own origin, so a position means nothing without the scene it
     * was submitted with.
     *
     * <p>{@code mask} is the 8-bit ray visibility mask compared against a trace's cull mask. It selects
     * what a ray sees within a scene. The {@link SceneId} controls scene membership independently.
     *
     * <p>{@code instanceData} is a typed 64-bit word reaching the selected surface and volume for
     * this placement only. It lets placements of one mesh use different shading data. Its schema must match
     * the one carried by the mesh ID and every shader slot in that mesh; the renderer checks token identity
     * synchronously even when raw Java types bypass compile-time checking.
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
