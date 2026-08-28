package dev.comfyfluffy.caustica.api.geometry;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * One placement of a retained mesh, in one scene and that scene's coordinate system. Issued by
 * {@link GeometryChannel#newInstance()}. Opaque — see {@link RetainedId}.
 */
public interface InstanceId extends RetainedId {
}
