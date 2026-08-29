package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.session.RenderSessionContext;
import dev.comfyfluffy.caustica.api.scene.SceneId;

import java.util.List;
import java.util.Objects;

/**
 * Retained meshes and their placements, reached from {@link RenderSessionContext#geometry()}.
 *
 * <p>The renderer owns one collection per session and issues collision-free ids. Mesh and instance ids are
 * mutation capabilities local to the contribution whose context issued them. A {@link SceneId} selects a
 * placement's target scene and may be an explicitly handed-off reference from another contribution in the
 * same render session. The channel is thread-safe and may be used from worker, tick, or render threads.
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
     * naming a stale scene, a shader-data token which does not match the schema carried by its program or
     * mesh id, cutout geometry naming a surface with no coverage implementation, or a malformed build.
     * Validation completes before this method returns. Surface and volume ids may be explicitly handed off
     * across contributions in this render session; they remain non-owning references to implementations
     * whose issuer alone may remove them. Nothing is applied if anything throws.
     *
     * <p>See {@link RetainedBatch} for what a batch guarantees, how to choose its granularity, and how its
     * retirement callback follows the retained data that batch introduces.
     *
     * <p>Acceptance is synchronous, so callers may update their bookkeeping after this method returns.
     *
     * <p>Native allocation, command construction, and GPU-submit acceptance are part of synchronous
     * validation. A failure there rejects the whole batch. A device or execution failure discovered after
     * the GPU accepted the work is terminal for the render session; it is not recovered as a per-mesh
     * rejection.
     *
     * @throws IllegalArgumentException if an operation mutates a mesh or instance id not issued by this
     *         contribution, names a stale selection reference, uses an identity from another render session,
     *         or supplies shader data with a mismatched schema token
     */
    void submit(RetainedBatch<Operation> batch);

    /**
     * Apply several independently-retired batches as one atomic publication.
     *
     * <p>Operations apply in list order and become visible together. Validation and native acceptance cover
     * the entire group: if anything throws, no batch is applied and no retirement callback is transferred.
     * Each accepted batch keeps its own retirement lifetime; grouping publications does not make unrelated
     * resources wait for one another.
     *
     * <p>A group must contain at least one batch. Implementations that only provide the single-batch API
     * reject groups larger than one rather than weakening atomicity or retirement guarantees.
     */
    default void submitGroup(List<RetainedBatch<Operation>> batches) {
        batches = List.copyOf(batches);
        if (batches.isEmpty()) throw new IllegalArgumentException("a submission group needs at least one batch");
        if (batches.size() != 1) {
            throw new UnsupportedOperationException("this geometry channel does not support grouped submission");
        }
        submit(batches.getFirst());
    }

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
     *
     * <p>{@code primitiveLights} uses global mesh triangle numbering and is placement-local because its
     * retained light IDs are scene-local while a mesh is reusable. Its ranges must fit the selected mesh and
     * accurately cover each visible emitter proxy so reverse light/BSDF MIS has the correct PDF. A light from
     * another contribution in the same render session may be selected, but the reference grants no set/drop
     * authority and does not pin the light. An absent light, or one currently placed in a different scene,
     * resolves as non-sampleable.
     */
    record SetInstance<N>(InstanceId instance, SceneId scene, MeshId<N> mesh,
                          GeometryTransform transform, int mask,
                          ShaderData<N> instanceData, PrimitiveLightMap primitiveLights) implements Operation {
        public SetInstance(InstanceId instance, SceneId scene, MeshId<N> mesh,
                           GeometryTransform transform, int mask, ShaderData<N> instanceData) {
            this(instance, scene, mesh, transform, mask, instanceData, PrimitiveLightMap.EMPTY);
        }

        public SetInstance {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(scene, "scene");
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(instanceData, "instanceData");
            Objects.requireNonNull(primitiveLights, "primitiveLights");
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
