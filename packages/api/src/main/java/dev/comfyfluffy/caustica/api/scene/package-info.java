/**
 * Opaque scene targeting and environment-binding values.
 *
 * <p>The host owns scene creation, coordinate scale and removal. Contributions select environments through
 * their own slots; removing a slot restores the most recent surviving selection. Extensions receive
 * {@link dev.comfyfluffy.caustica.api.scene.SceneId} only at host-specific integration boundaries and may use
 * it as a same-session, non-owning target for geometry, lights, and views. Environment ids and bindings may
 * likewise cross contribution boundaries without transferring implementation or scene authority.
 */
package dev.comfyfluffy.caustica.api.scene;
