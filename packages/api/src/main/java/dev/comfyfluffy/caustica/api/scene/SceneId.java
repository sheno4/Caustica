package dev.comfyfluffy.caustica.api.scene;

/**
 * A non-owning reference to a scene: one coordinate system, one acceleration structure, one environment,
 * and one set of lights. Geometry and light operations may name it as their target, but the reference
 * grants no scene administration or lifetime authority. The identity is opaque, issued by the host for one
 * render session, and may be handed between contributions in that session. Copying it never keeps the scene
 * alive; a stale reference is rejected when submitted.
 */
public interface SceneId {
}
