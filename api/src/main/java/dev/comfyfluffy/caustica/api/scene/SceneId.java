package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * A non-owning reference to a scene: one coordinate system, one acceleration structure, one environment,
 * and one set of lights. It permits contribution of geometry and lights but not environment mutation or
 * destruction. Opaque — see {@link RetainedId}.
 */
public interface SceneId extends RetainedId {
}
