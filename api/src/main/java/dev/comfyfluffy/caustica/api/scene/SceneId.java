package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.retained.RetainedId;

/**
 * A non-owning reference to a scene: one coordinate system, one acceleration structure, one environment,
 * and one set of lights. Geometry and light operations may name it as their target, but the reference
 * grants no scene administration or channel authority. Opaque — see {@link RetainedId}.
 */
public interface SceneId extends RetainedId {
}
