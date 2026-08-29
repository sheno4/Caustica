package dev.comfyfluffy.caustica.api.scene;

/**
 * A non-owning reference to a scene: one coordinate system, one acceleration structure, one environment,
 * and one set of lights. Geometry and light operations may name it as their target, but the reference
 * grants no scene administration or channel authority. The identity is opaque and meaningful only to its
 * issuing context.
 */
public interface SceneId {
}
