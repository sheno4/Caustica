package dev.comfyfluffy.caustica.api.geometry;

/**
 * One placement of a retained mesh, in one scene and that scene's coordinate system. Issued by
 * {@link dev.comfyfluffy.caustica.api.scene.SceneChannel#newInstance()}. The identity is an opaque mutation capability local to its issuing
 * contribution; another contribution may not set or drop it.
 */
public interface InstanceId {
}
