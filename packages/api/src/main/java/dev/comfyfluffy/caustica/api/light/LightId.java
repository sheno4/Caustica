package dev.comfyfluffy.caustica.api.light;

/**
 * A retained light, in one scene. Issued by {@link dev.comfyfluffy.caustica.api.scene.SceneChannel#newLight()}. The identity is opaque and
 * local to its issuing contribution; another contribution may not set or drop it.
 */
public interface LightId {
}
