/**
 * Independently retained scene identity and administration.
 *
 * <p>A scene owns its environment binding, coordinate scale, placements, and lights. It does not own
 * cameras or scene-independent meshes. {@link dev.comfyfluffy.caustica.api.scene.SceneId} is a non-owning
 * target reference; {@link dev.comfyfluffy.caustica.api.scene.SceneHandle} is the separate administration
 * capability. Every created scene is removed automatically with its render-session contribution.
 */
package dev.comfyfluffy.caustica.api.scene;
