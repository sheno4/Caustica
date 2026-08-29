/**
 * Opaque scene targeting and environment-binding values.
 *
 * <p>The host owns scene creation, environment selection, coordinate scale, and removal. Extensions receive
 * {@link dev.comfyfluffy.caustica.api.scene.SceneId} only at host-specific integration boundaries and may use
 * it as a same-session, non-owning target for geometry, lights, and views. Environment ids and bindings may
 * likewise cross contribution boundaries without transferring implementation or scene authority.
 */
package dev.comfyfluffy.caustica.api.scene;
