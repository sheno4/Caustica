package dev.comfyfluffy.caustica.api.scene.geometry;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * One placement of a retained mesh, in one scene and that scene's coordinate system. Issued by
 * {@link GeometryChannel#newInstance()}. Opaque — see {@link RetainedId}.
 */
public interface InstanceId extends RetainedId {
}
