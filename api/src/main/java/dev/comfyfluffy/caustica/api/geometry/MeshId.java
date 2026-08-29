package dev.comfyfluffy.caustica.api.geometry;

/**
 * A retained mesh, issued by {@link GeometryChannel#newMesh}. Belongs to no scene: it is placed into one
 * or several by {@link GeometryChannel.SetInstance}. Its placement-data schema is fixed when the id is
 * issued. The identity is opaque and meaningful only to its issuing context.
 *
 * @param <N> data schema shared by every placement of this mesh and accepted by every shading slot
 */
public interface MeshId<N> {
}
