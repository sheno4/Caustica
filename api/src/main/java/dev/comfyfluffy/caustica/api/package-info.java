/**
 * The host-neutral Caustica extension API.
 *
 * <h2>Lifetime</h2>
 *
 * Extensions register process-lived factories through
 * {@link dev.comfyfluffy.caustica.api.CausticaApi#sessions()}. A factory receives a fresh
 * {@link dev.comfyfluffy.caustica.api.session.RenderSessionContext} for every live render session. Every
 * pass, provider, retained identity, and GPU-backed object created through that context belongs to the
 * session scope and is drained before the device is destroyed.
 *
 * <h2>Scenes and views</h2>
 *
 * {@link dev.comfyfluffy.caustica.api.scene.SceneId} is a non-owning reference used by geometry, lights,
 * and cameras. {@link dev.comfyfluffy.caustica.api.scene.OwnedScene} is the exclusive capability for
 * changing a scene's environment or closing it. Multiple scenes may remain resident, while a
 * {@link dev.comfyfluffy.caustica.api.scene.RenderView} states which root scene one camera draws from. A
 * scene never owns a camera.
 *
 * <h2>Contributions</h2>
 *
 * Retained geometry, materials, lights, and program implementations use issued identities rather than
 * caller-authored names. Asynchronous retained changes use their session channels. Work that must become
 * visible in the frame currently being collected uses the explicit
 * {@link dev.comfyfluffy.caustica.api.scene.SceneFrameWriter} supplied to a
 * {@link dev.comfyfluffy.caustica.api.scene.SceneProvider}.
 *
 * <p>GPU queues and submission remain renderer-owned. Extensions prepare CPU data on their own executors
 * and record GPU work through a typed {@link dev.comfyfluffy.caustica.api.pass.Pass} registered at the
 * pre-trace, post-effect, or UI stage. Completion and retirement are callback-based; no public API blocks
 * waiting for GPU or program progress.
 *
 * <h2>Boundary</h2>
 *
 * Core types contain no Minecraft identifiers or lifecycle. Dimension-to-scene lookup, level ownership,
 * camera capture, and resource reloads belong to a Minecraft integration package. Native queue policy,
 * presentation, upscaling, and other renderer mechanisms remain engine concerns.
 */
package dev.comfyfluffy.caustica.api;
