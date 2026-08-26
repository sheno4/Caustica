package dev.comfyfluffy.caustica.api.scene;

import dev.comfyfluffy.caustica.api.RetainedId;

/**
 * A scene: one coordinate system, one acceleration structure, one set of lights. Issued by
 * {@link SceneChannel#newScene}. Opaque — see {@link RetainedId}.
 */
public interface SceneId extends RetainedId {
}
