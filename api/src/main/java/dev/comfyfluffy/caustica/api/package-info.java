/**
 * The Caustica extension API.
 *
 * <h2>What this is an API to</h2>
 *
 * Caustica is a Vulkan path tracer that owns the world renderer, the post-effects chain, the display
 * transform, and present. It depends on Minecraft for three things only — lifecycle, the config screen and
 * file, and the surface it presents to. Everything else Minecraft contributes to a rendered frame, terrain
 * and entities included, arrives through this API as an extension, with no privileged path.
 *
 * <p>That is the boundary rule: <b>anything that adds to the rendered image goes through this API;
 * anything that adds a feature to the renderer itself does not.</b> A mod contributing geometry, lights,
 * materials, an environment, a post effect, or UI is an extension. A change to how screenshots are
 * captured, how the display transform works, or which upscaler runs is a change to Caustica — mixin it or
 * open a pull request.
 *
 * <h2>Everything retained lives in a scene</h2>
 *
 * A scene ({@link dev.comfyfluffy.caustica.api.scene.SceneChannel}) is a coordinate system, an
 * acceleration structure, and a set of lights. Placements and lights are made <em>into</em> one; meshes and
 * materials are not — a mesh because it is an acceleration-structure input independent of any top-level
 * structure, a material because one shader binding table serves every scene a single trace can reach. So a
 * model placed in two scenes is built once and a material id means the same thing everywhere. The
 * environment goes the other way and is scene-owned: it is what a ray leaving the geometry finds, and which
 * geometry it left is the scene's.
 *
 * <p>The renderer traces the camera's scene and no other. Which scene that is comes from the host adapter
 * rather than from an extension — choosing what the renderer traces is not a way to add to the image — and
 * it reaches sources as {@link dev.comfyfluffy.caustica.api.scene.SceneFrameContext#scene()}.
 *
 * <h2>Capabilities</h2>
 *
 * <ol start="0">
 * <li><b>Lifecycle</b> — world change, resource-pack, and runtime activation events. Everything below
 *     is scoped by these.</li>
 * <li><b>Retained geometry</b> ({@link dev.comfyfluffy.caustica.api.scene.geometry}) — lend source-owned GPU
 *     mesh buffers and submit placements through
 *     {@link dev.comfyfluffy.caustica.api.CausticaApi#geometry}, from any thread at any time; the renderer
 *     retains the borrow and schedules its own acceleration-structure work. It never clears the collection
 *     on its own: a source clears its own geometry when its world changes, so one extension's reload cannot
 *     clear another's. Registering a {@link dev.comfyfluffy.caustica.api.scene.SceneProvider} is
 *     separate and buys notifications rather than access — world changes and the frame-coherent submission
 *     point.</li>
 * <li><b>Retained lights</b> ({@link dev.comfyfluffy.caustica.api.scene.light}) — the same shape for primitive
 *     emitters, placed into a scene the same way, and selected scene-locally.</li>
 * <li><b>Resource injection</b> ({@link dev.comfyfluffy.caustica.api.pass.WorldResourcePass}) — bind
 *     extension-owned images and buffers into the world pipeline, computed by the extension's own passes
 *     before the trace. The renderer does ordering and barriers; it never interprets the contents.</li>
 * <li><b>Material</b> ({@link dev.comfyfluffy.caustica.api.material}) — the renderer owns the OpenPBR
 *     BSDF and evaluates it once, so value/pdf agreement cannot be broken by an implementation. An
 *     extension registers a Slang surface that feeds that BSDF its parameters.</li>
 * <li><b>Environment</b> ({@link dev.comfyfluffy.caustica.api.program.ProgramChannel}) — an added
 *     {@code IEnvironmentModel} set, named per scene, exactly parallel to
 *     material: the scene picks the implementation and hands it one uninterpreted word.</li>
 * <li><b>Post effects</b> ({@link dev.comfyfluffy.caustica.api.pass.PostEffectPass}) — a pass after
 *     reconstruction and before the display transform, working in scene-linear ACEScg, optionally anchored
 *     to an end of the chain ({@link dev.comfyfluffy.caustica.api.pass.PassAnchor}). A scene-referred grade
 *     is one of these anchored last, which is where ACES puts an LMT. Only effects on the scene image
 *     itself; anything crisp and 2D is UI, even when the world positions it.</li>
 * <li><b>UI</b> ({@link dev.comfyfluffy.caustica.api.ui}) — a pass after the display transform, drawing a
 *     separate sRGB layer once per rendered frame. Presentation consumes it for every output frame; a
 *     frame-generation backend may reuse it or interpolate it separately from the HUD-less scene before
 *     recompositing the two. World-anchored overlays — outlines, name tags, selection boxes — are UI: they
 *     need display resolution after reconstruction and must stay outside scene interpolation, so the
 *     camera is available here.</li>
 * </ol>
 *
 * <h2>Services</h2>
 *
 * Distinct from the capabilities above, which are ways to put something in the frame. A service puts
 * nothing in the frame; it is something the host lends an extension because an extension cannot build it
 * for itself. There is no numbered list because a service adds no new way to contribute — it only makes
 * the capabilities usable.
 *
 * <ul>
 * <li><b>GPU</b> ({@link dev.comfyfluffy.caustica.api.gpu}) — the device, allocator, and command buffer the
 *     renderer already owns. Sharing them beats every extension creating its own.</li>
 * <li><b>Shader compilation</b> ({@link dev.comfyfluffy.caustica.api.shader}) — the host's Slang
 *     toolchain, with the renderer's own module search paths already on it.</li>
 * </ul>
 *
 * <p>Settings are <b>not</b> here. Typed options, their values, and the text a settings screen renders live
 * in {@code caustica-api-settings}, which is parallel to this artifact rather than layered on it: it
 * depends on this one and the renderer never reads it. An extension adds what it contributes to a frame
 * here and declares what it exposes to a settings screen there, and nothing links the two but the extension
 * itself. What the
 * renderer offers instead is {@code frameIndex()} on every frame context — the one fact an outside layer
 * needs to freeze its own state for the length of a frame.
 *
 * <h2>A pass declares when, not what</h2>
 *
 * Extensions own their passes outright — the shader, the pipeline, the intermediate images, the dispatch.
 * So {@link dev.comfyfluffy.caustica.api.pass} answers only two questions, and deliberately nothing else:
 *
 * <ul>
 * <li><b>Order</b> — which interface a pass implements <em>is</em> where in the frame it records. There is
 *     no stage enum to pick from, because there are exactly as many places to record as there are
 *     interfaces: {@link dev.comfyfluffy.caustica.api.pass.WorldResourcePass} before the trace,
 *     {@link dev.comfyfluffy.caustica.api.pass.PostEffectPass} after reconstruction,
 *     {@link dev.comfyfluffy.caustica.api.ui.UiPass} after the display transform.</li>
 * <li><b>Lifetime</b> — {@link dev.comfyfluffy.caustica.api.pass.PassLifecycle} names the epochs a pass's
 *     resources live inside, and every callback that can allocate is handed the same setup, so a rebuild
 *     after a resize or a resource-pack swap has the handles the first build had.</li>
 * </ul>
 *
 * <p>What a pass computes never enters the API. The renderer does not inspect it, name it, or validate it
 * — a frame context carries only what the renderer itself produced and the pass cannot get elsewhere.
 *
 * <h2>Identity is issued, and nothing is declared</h2>
 *
 * Nothing in this artifact takes a name, and nothing is fixed at startup. Every id comes from the channel
 * that made the object ({@link dev.comfyfluffy.caustica.api.RetainedId}) — a mesh, a placement, a light, a
 * material, a scene, and equally a surface implementation or an environment. There is one lifetime rule for
 * all of them: an id is valid until the object is dropped or its collection's generation moves.
 *
 * <p>Authoring a key is writing a hash function: a namespaced string has to be unique, has to be spelled
 * the same in two places, and is validated against a charset somebody chose. An issued identity has none of
 * those failure modes and needs no registry of reserved names.
 *
 * <p><b>There is no declaration phase.</b> A surface implementation is added and dropped exactly like a
 * mesh, so switching a feature off means it is not in the program — there is no gate to consult, nothing
 * compiled-but-disabled, and no way to name something that is not there. Adding recompiles the world
 * program, which is why {@link dev.comfyfluffy.caustica.api.program.ProgramChannel} batches: one batch is
 * one recompile.
 *
 * <p>What this gives up is naming across a boundary the process does not span — a config file, or a mod
 * that will not compile against the one it wants to reference. That naming is real, and it belongs to the
 * layer with a native vocabulary for it: a host's own resource name, persisted by the host, mapped to an id
 * the host holds. It is not the renderer's.
 *
 * <h2>Ownership decides the shape of a type</h2>
 *
 * Two questions settle where a new type goes: <i>who implements it</i> — the renderer, which is a
 * singleton, or an extension, of which there are many — and <i>which way it flows</i>. Interfaces carry
 * behaviour; records carry data; and no type is ever implemented by both sides.
 *
 * <ol>
 * <li><b>Engine services</b> — an interface the extension calls and never implements.
 *     {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice},
 *     {@link dev.comfyfluffy.caustica.api.pass.PassSetup},
 *     {@link dev.comfyfluffy.caustica.api.pass.PostEffectFrame},
 *     {@link dev.comfyfluffy.caustica.api.scene.SceneChannel}.</li>
 * <li><b>Engine-allocated resources</b> — an interface the extension receives from a factory, owns, and
 *     destroys after every API borrow retires. {@link dev.comfyfluffy.caustica.api.gpu.GpuBuffer},
 *     {@link dev.comfyfluffy.caustica.api.gpu.GpuImage},
 *     {@link dev.comfyfluffy.caustica.api.gpu.GpuFrameUse}. Beside them sit the issued identities an
 *     extension receives, holds and hands back — {@link dev.comfyfluffy.caustica.api.RetainedId} and its
 *     subinterfaces, {@link dev.comfyfluffy.caustica.api.scene.SceneId} among them.</li>
 * <li><b>Extension contributions</b> — an interface the extension implements and the renderer calls.
 *     {@link dev.comfyfluffy.caustica.api.CausticaExtension},
 *     {@link dev.comfyfluffy.caustica.api.pass.WorldResourcePass},
 *     {@link dev.comfyfluffy.caustica.api.pass.PostEffectPass},
 *     {@link dev.comfyfluffy.caustica.api.ui.UiPass},
 *     {@link dev.comfyfluffy.caustica.api.scene.SceneProvider}.</li>
 * <li><b>Extension-produced data</b> — a record of plain values and raw Vulkan handles. The renderer reads
 *     it and either copies or borrows it; it never takes ownership.
 *     {@link dev.comfyfluffy.caustica.api.scene.geometry.MeshBuild},
 *     {@link dev.comfyfluffy.caustica.api.material.MaterialDefinition},
 *     {@link dev.comfyfluffy.caustica.api.scene.light.LightDescriptor}.</li>
 * </ol>
 *
 * <p>The rule that falls out: <b>extension to engine is plain values and raw handles; engine to extension
 * is an interface.</b> A category-2 type must never appear in a category-4 position. The moment the
 * renderer <i>consumes</i> a type it also <i>produces</i>, anything an extension built for itself has to
 * be laundered through a hand-written implementation of an interface it was never meant to implement.
 * This is why {@link dev.comfyfluffy.caustica.api.pass.WorldResourceSetup#publishWorldTexture} takes an
 * image view and a layout rather than a {@code GpuImage}.
 *
 * <h2>Paved paths and escape hatches</h2>
 *
 * The renderer offers typed factories only where it has an opinion worth enforcing — a layout, a usage
 * set, an initializing barrier, persistent mapping. Everything else is reached through the raw handles it
 * already exposes: {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice#vk()},
 * {@link dev.comfyfluffy.caustica.api.gpu.GpuDevice#vmaAllocator()},
 * {@link dev.comfyfluffy.caustica.api.pass.PostEffectFrame#commandBuffer()}.
 *
 * <p><b>Every typed convenience needs a raw-handle equivalent that accepts what the escape hatch
 * produces.</b> A factory an extension cannot substitute for is not a convenience, it is a wall: the
 * extension either abandons the escape hatch or reaches around the API entirely. Allocating outside the
 * renderer's own allocator is worse for everyone than sharing it, so the API's job is to make sharing the
 * easy path and to accept whatever comes back.
 *
 * <h2>Helpers are not here</h2>
 *
 * This artifact holds only what the renderer names in a signature. Anything an extension could have
 * written for itself — descriptor and pipeline scaffolding, colour-space maths, texture upload — lives in
 * {@code caustica-api-support}, which depends on this and is entirely optional.
 */
package dev.comfyfluffy.caustica.api;
