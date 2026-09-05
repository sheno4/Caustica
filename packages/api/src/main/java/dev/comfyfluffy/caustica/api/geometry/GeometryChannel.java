package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.retained.RetainedBatch;
import dev.comfyfluffy.caustica.api.retained.RetainedPublication;
import dev.comfyfluffy.caustica.api.light.LightChannel;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.program.ShaderDataType;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.api.resource.ResourceRef;
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
     * mesh id, a released or foreign-session resource reference, cutout geometry
     * naming a surface with no coverage implementation, or a malformed build.
     * Validation completes before this method returns. Surface and volume ids may be explicitly handed off
     * across contributions in this render session; they remain non-owning references to implementations
     * whose issuer alone may remove them. Nothing is applied if anything throws.
     *
     * <p>See {@link RetainedBatch} for what a batch guarantees and how to choose its granularity.
     *
     * <p>Acceptance is synchronous. The returned receipt becomes visible only after the renderer commits
     * the accepted native scene publication.
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
    RetainedPublication submit(RetainedBatch<Operation> batch);

    /**
     * Applies several ordered batches as one atomic publication.
     *
     * <p>Operations apply in list order and become visible together. Validation and native acceptance cover
     * the entire group: if anything throws, no batch is applied. Resource owners referenced by the
     * operations may be shared across batches in the group.
     *
     * <p>A group must contain at least one batch.
     */
    RetainedPublication submitGroup(List<RetainedBatch<Operation>> batches);

    /**
     * Applies ordered geometry work and coalesced current-frame rigid placements together.
     *
     * <p>{@code latestInstances} may only change the transform and visibility mask of a placement which
     * exists after {@code batches} are applied. It preserves that placement's scene, mesh, shader data,
     * emitter map, and retained resources. Renderers may consume these placements for the current frame
     * before an ordered mesh publication becomes visible. At least one of the two lists must be non-empty.
     */
    default RetainedPublication submitGroupWithLatest(List<RetainedBatch<Operation>> batches,
                                                      List<LatestInstance> latestInstances) {
        throw new UnsupportedOperationException("latest rigid placements are not supported by this channel");
    }

    /**
     * Publishes geometry and its retained lights as one scene revision.
     *
     * <p>Both channels must come from the same contribution in the same render session. Geometry batches
     * and light operations retain their referenced resource owners. The complete mutation is rejected if
     * either side is invalid; no intermediate geometry-only or light-only revision is observable.
     */
    RetainedPublication submitWithLights(List<RetainedBatch<Operation>> geometryBatches,
                                         LightChannel lights,
                                         RetainedBatch<LightChannel.Operation> lightBatch);

    sealed interface Operation permits SetMesh, DropMesh, SetInstance, DropInstance { }

    /** A transform-and-mask update for an existing placement; it owns no retained resources. */
    record LatestInstance(InstanceId instance, GeometryTransform transform, int mask) {
        public LatestInstance {
            Objects.requireNonNull(instance, "instance");
            Objects.requireNonNull(transform, "transform");
            if ((mask & ~0xFF) != 0) {
                throw new IllegalArgumentException("visibility mask must fit in eight bits");
            }
        }
    }

    /**
     * Retain or replace a mesh. A non-NONE stream or geometry-binding reference follows its
     * {@link ResourceOwner} lifetime and may be shared with other operations or batches. A NONE
     * reference does not provide resource-lifetime tracking. Closing a scene removes placements in that
     * scene, never this scene-independent mesh. A rejected submission changes nothing.
     */
    record SetMesh<N>(MeshId<N> mesh, MeshBuild<N> build) implements Operation {
        public SetMesh {
            Objects.requireNonNull(mesh, "mesh");
            Objects.requireNonNull(build, "build");
        }
    }

    /** Remove a mesh and every placement of it, in every scene. */
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
     * synchronously even when raw Java types bypass compile-time checking. With
     * {@link ResourceRef#none()}, the word provides no tracked resource lifetime. A non-NONE resource
     * owner may be shared across placements and batches.
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
