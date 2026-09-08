/**
 * The Vulkan-native Caustica extension API.
 *
 * <h2>Lifetime</h2>
 *
 * Extensions register process-lived factories through
 * {@link dev.comfyfluffy.caustica.api.CausticaApi#sessions()}. A factory receives a fresh
 * {@link dev.comfyfluffy.caustica.api.session.RenderSessionContext} for every live render session. Every
 * pass, retained identity, and GPU-backed object created through that context belongs to the
 * session scope and is drained before the device is destroyed.
 *
 * <h2>Scenes and views</h2>
 *
 * {@link dev.comfyfluffy.caustica.api.scene.SceneId} is a non-owning target reference used by geometry,
 * lights, and views. The host creates and removes scenes; contributions select environments through their
 * own scene slots. Multiple scenes may remain resident, while a
 * {@link dev.comfyfluffy.caustica.api.view.SceneView} associates one camera with its entry scene and
 * containing medium. A scene never owns a camera.
 *
 * <h2>Contributions</h2>
 *
 * Instance and light mutation identities remain local to their issuing contribution. Ready meshes are
 * immutable owning claims, shareable across contributions and scenes. Scene, surface, volume and environment
 * ids are non-owning same-session references; sharing them transfers no removal authority or lifetime.
 * Geometry independently selects surface and interior-volume programs. Their shaders receive extension-owned
 * implementation, slot-binding and instance data words; extensions keep their own shading tables behind
 * those roots.
 *
 * <p>Preparation produces ready resource revisions before scene mutation. Each thread-safe scene edit
 * publishes its entire batch atomically, without GPU work or changes to captured frames. Program registration
 * compiles asynchronously and becomes active at a separate program publication boundary.
 *
 * <p>The renderer owns GPU queues and submission. Extensions prepare CPU data on their own executors,
 * submit asynchronous immutable-resource initialization through
 * {@link dev.comfyfluffy.caustica.api.vulkan.GpuComputeQueue}, and record frame-dependent work through a
 * typed {@link dev.comfyfluffy.caustica.api.pass.Pass} registered at the pre-trace, post-effect, or UI
 * stage. Completion and retirement are callback-based; no public API blocks waiting for GPU or program
 * progress.
 *
 * <h2>Boundary</h2>
 *
 * API types contain no Minecraft identifiers or lifecycle. Dimension-to-scene lookup, level ownership,
 * camera capture, and resource reloads belong to a Minecraft integration package. Native queue policy,
 * presentation, upscaling, and other renderer mechanisms are not part of this API.
 */
package dev.comfyfluffy.caustica.api;
