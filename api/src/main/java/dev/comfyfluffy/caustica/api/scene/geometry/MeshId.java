package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * A retained mesh, issued by {@link GeometryChannel#newMesh()}. Belongs to no scene: it is placed into one
 * or several by {@link GeometryChannel.SetInstance}. Opaque — see {@link RetainedId}.
 */
public interface MeshId extends RetainedId {
}
