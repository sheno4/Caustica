# Caustica API — target architecture

## The system

Caustica is a Vulkan path tracer. It owns the world renderer, the post-effects chain, the display
transform, present, and its own config. It depends on Minecraft for exactly three things: **lifecycle**,
the **config screen and file**, and the **surface it presents to**.

Everything else Minecraft contributes to a frame — terrain, entities, sky, block outlines — arrives through
this API as an extension, with no privileged path. Minecraft is a client of the API, not a layer of the
renderer.

**The boundary rule.** Anything that *adds to the rendered image* goes through the API. Anything that *adds
a feature to the renderer itself* does not.

| | route |
| --- | --- |
| geometry, lights, materials, an environment, a post effect, UI | extension, through the API |
| F2 HDR screenshots, a new upscaler, a different display transform | mixin against the mod, or a PR |

If you want an API hook so the renderer will behave differently, that is the second row.

---

## Capabilities

The complete list of ways an extension can put something in a frame. Everything retained goes into a
**scene** — see below; a scene is not on this list because it is where a contribution lands, not a way to
make one.

0. **Lifecycle** — world change, resource-pack, and runtime-activation events.
1. **Retained geometry** — lend source-owned GPU mesh buffers and submit placements. The renderer takes
   nothing an acceleration-structure build does not need; everything else reaches the shader by pointer.
2. **Retained lights** — engine-supported primitive emitters. The renderer owns their geometry, sampling,
   and PDFs; an extension may attach a Slang emission profile that authors the emitted value.
3. **Resource injection** — extension-owned images and buffers reach extension-owned shaders through the
   descriptor heap and device addresses. The only route for extension GPU data, textures included.
4. **Material** — the renderer owns the OpenPBR BSDF and evaluates it once. An extension registers a Slang
   surface that feeds it parameters, and registers materials naming that surface.
5. **Environment** — an added implementation named per scene, exactly parallel to material: the scene picks
   which one evaluates the sky and hands it one uninterpreted word, which is how it reaches its own LUTs.
6. **Post effects** — a pass after reconstruction and before the display transform, in scene-linear ACEScg.
   A scene-referred grade is one of these, anchored to the end of the chain.
7. **UI** — a pass after the display transform, drawing a premultiplied sRGB layer once per *rendered*
   frame; presentation consumes it for every output frame.

**Services are not capabilities.** A service puts nothing in a frame; it exists because an extension cannot
build it for itself. The **GPU** service shares the device, allocator, and command buffer the renderer
already owns. **Shader compilation** shares the host's Slang toolchain with the renderer's module search
paths on it. Neither is numbered, because neither adds a way to contribute.

---

## Identity is issued, and nothing is declared

Nothing in the artifact takes a name, and nothing is fixed at startup. Every id comes from the channel that
made the object — a mesh, a placement, a light, a material, a scene, and equally a surface implementation or
an environment. One rule covers all of them: an id is valid until the object is dropped or its collection's
generation moves.

The retained side always worked this way, and the argument was written down there: authoring a key is
writing a hash function, and compressing a composite into a fixed width silently collides. Implementations
were the exception — a namespaced string that had to be unique, spelled identically in two places, and
validated against a charset somebody chose. They are retained objects now, so the exception is gone.

**There is no declaration phase.** A surface implementation is added and dropped exactly like a mesh.
Switching a feature off means it is not in the program: no gate to consult, nothing compiled-but-disabled,
no way to name something that is not there, and no availability query — because the extension that just
added something is the one holding its id. Switching it back on adds it again and issues a new id.

Adding is synchronous and returns a usable id, the way registering a material is, so no ordering between
channels is ever required. Adding recompiles the world program, but scheduling that is not the caller's
problem: the renderer rebuilds at most once per boundary, so a run of additions costs one rebuild — and it
coalesces across extensions, which a caller-side batch could never do. Until the rebuild lands, a material
naming a new surface shades as the visible error surface, which is paid where nothing is looking, because
every realistic trigger already sits on a reload boundary.

**What this gives up is naming across a boundary the process does not span** — a config file, a mod that
will not compile against the one it wants to reference. That naming is real, and belongs to the layer with a
native vocabulary for it: a host's own resource name, persisted by the host, mapped to an id the host holds.
`ResourceId` therefore lives in the settings artifact, and the engine artifact has no name type at all.

---

## Updating extension data is one mechanism at every frequency

There is no per-frame path and no rare-update path, for the same reason retained geometry has neither: a
mesh submitted every frame and a mesh submitted once go through one `SetMesh`. Extension parameters work
the same way, through the machinery that already exists.

**Never rewrite a buffer the GPU may still be reading. Replace it.** That is the single lifetime primitive
stated once more: allocate the new one, hand the old one to `retireAfterUse`, keep going. It is correct at
any frequency, needs no ring index, and never blocks.

The piece that makes it work against a *stable* address is one indirection. A material's word cannot change
without re-registering the material, so point it at a small buffer that holds a pointer, and put the data
behind that:

```
material.parameters ──► [ uint64 dataAddress ]  ──►  the actual parameters
       (stable, set once at registration)     (replaced whenever they change)
```

Updating is then a single aligned 64-bit store into the header plus a `retireAfterUse` of the block it
displaced. An in-flight frame reads either the old pointer or the new one; both address live memory,
because the old block is not freed until the work that could read it has completed. There is no torn read
to guard against at that alignment and no ordering to arrange — a frame that observes the stale pointer
simply renders one frame behind, which is what it would have done anyway.

**A source that does this every frame recycles rather than allocates**, feeding blocks back onto its own
free list from the retirement callback. That is a private optimisation, exactly like a mesh source
suballocating from its own arena instead of allocating a buffer per mesh, and no part of it reaches the API.

**Nothing here uses `GpuFrameUse.awaitCompletion`.** The callback form is what this pattern wants; the
blocking form exists for reusing something last touched many frames ago, and its contract has a sharp edge
— awaiting the token for the frame being recorded waits on work that cannot yet be submitted. Replace-and-
retire never goes near it.

The renderer's own ring — `PUSH_RING` slots of push-constant storage, each awaited before reuse — is the
other approach, and it is deliberately not what the API offers. It needs a ring depth, a frame counter on
both sides of the language boundary, and a correct await; replacement needs none of those.

---

## Ownership decides the shape of a type

Two questions settle where a new type goes: *who implements it* — the renderer, which is a singleton, or an
extension, of which there are many — and *which way it flows*.

| category | shape | example |
| --- | --- | --- |
| 1. Engine service | interface the extension calls, never implements | `GpuDevice`, `PassSetup` |
| 2. Engine-produced resource or identity | opaque type the extension receives, holds, returns | `GpuImage`, `MeshId` |
| 3. Extension contribution | interface the extension implements, the renderer calls | `WorldResourcePass` |
| 4. Extension-produced data | record of plain values and raw handles | `MeshBuild`, `MaterialDefinition` |

**Extension-authored data flows as plain values and raw handles; engine-authored services, resources, and
identities stay opaque.** Category 2 is opaque all the way down — an id is an empty marker interface with
no constructor and no accessor, so the renderer can change what an id is made of without touching extension
code. A category-2 type never appears in a category-4 position: if the renderer consumed the same resource
interface it produces, anything an extension built through the raw escape hatch would have to be laundered
through a hand-written implementation. This is why what crosses into the world pipeline is a descriptor
index and a device address rather than a `GpuImage`.

**Paved paths and escape hatches.** Typed factories exist only where the renderer has an opinion worth
enforcing — a layout, a usage set, an initializing barrier. Everything else is reached through raw handles:
the `VkDevice`, the VMA allocator, the command buffer. **Every typed convenience needs a raw-handle
equivalent that accepts what the escape hatch produces**, or it is a wall rather than a convenience and the
extension reaches around the API entirely.

**Helpers are not in the API.** The artifact holds only what the renderer names in a signature. Descriptor
scaffolding, colour-space maths, texture upload live in `caustica-api-support`, which is optional.

**Settings are a parallel artifact, not a layer.** Typed options, their values, and the text a settings
screen renders live in `caustica-api-settings`. It depends on the engine artifact for `ResourceId` and
nothing else; the engine artifact does not depend on it at all, and the renderer never reads it. A feature
declares what it contributes to a frame in one registry and what it exposes to a settings screen in the
other, and the two are the same feature only because they chose the same id.

This is worth the second entry point. While the two were one artifact, the engine's own feature record
carried a title, a description, and a category that only a settings screen reads, and its constructor
rejected any option kind the *host's storage* could not persist — a store's limitation enforced by the
renderer's registry. Neither survives the split.

What the renderer keeps is `frameIndex()` on every frame context. Freezing state for the length of a frame
is something only the renderer can anchor, because only it knows where a frame begins; but it cannot freeze
state it does not understand. So it publishes the boundary and whoever owns the state snapshots on it. The
cost is honest: two stages of one frame can now observe a setting changing between them, which is a
one-frame skew on a value someone is dragging, and a settings layer that snapshots once per index removes
even that.

---

## One lifetime primitive

The API models exactly one resource lifetime: **retire after GPU use**.

Every other scope — activation, display size, resource pack, world, frame — is a *notification*. They say a
resource became **wrong**. They never say it became **free**. Those are different instants, usually frames
apart, and nothing an extension can observe tells it when the second arrives.

So the shape of every callback that replaces a resource is: allocate the new one, hand the old one to
`retireAfterUse`, keep going. Destroying outright is correct in exactly one place — a lifecycle's final
callback, where the renderer has already idled the device. Everywhere else it is a use-after-free with a
one-frame window, which is the kind that survives testing.

There is no material epoch, and that is what makes this true rather than nearly true: a material scope was
the one place a notification really did end a lifetime.

---

## Retained data works one way

Geometry, lights, and materials share one shape. Learn it once.

**One collection each, renderer-owned, reached from the singleton** — `geometry()`, `lights()`,
`materials()`, with `scenes()` holding the container they go into. No per-source split: ids are issued, so
they cannot collide and there is nothing for a namespace to protect. Channels are thread-safe and
long-lived; submit from a chunk worker, an entity tick, or the render thread.

**Ids are engine-authored and opaque.** A source keeps its own map from its own identity — an entity id, a
packed chunk position — to the issued id. That map is the point, not a cost: authoring keys yourself is
writing a hash function, and compressing a UUID or a composite position into a fixed width silently
collides. Issued ids cannot, and are never reissued within a generation, so a stale id resolves to nothing
rather than to somebody else's object.

```java
MeshId mesh = sections.computeIfAbsent(packedSectionPosition, k -> geometry.newMesh());
```

**Nothing is reset by the renderer.** Retained data persists until whoever submitted it drops it, so one
extension reloading cannot disturb another's. There is deliberately no clear-everything call.

**Atomicity is a scene-contents concept.** Geometry and lights take `AtomicBatch`es; materials, surface
implementations and environments do not. What a batch protects is a frame's picture, and only scene contents
are in it — a table entry nothing names changes nothing, so there is no half-applied state to hide. When one
of those tables is replaced, the atomicity comes from the geometry batch that starts naming the new entries,
which is exactly where the reload story already puts it.

**Updates go in `AtomicBatch`es.** A batch publishes all at once or not at all — the name carries it, because that property is the whole reason batches exist rather than being a convenience for submitting several things. Rejection is
**synchronous** — an unknown or stale id, a placement naming a mesh that is neither live nor created earlier
in the same batch, a malformed build — so a source learns on the calling thread whether it may update its
own bookkeeping. That matters more than it sounds: dropping a mesh and then forgetting its id is only safe
because acceptance is already known. Learning it from a callback would mean holding every id until one
arrived, or orphaning meshes whose batch was refused. Atomicity is the source's choice and
is not free — a mesh cannot publish until its acceleration structure is built, so the slowest member gates
the rest. Group what must not be seen apart (two sections sharing a seam, every mesh in a reload); keep
everything else separate, especially placement-only batches, which need no build and would otherwise wait
on one.

The one asynchronous signal is **retirement**, and it is batch-scoped: everything a batch displaced —
replaced, dropped — is free once it has left the collection and no submitted GPU work still reads it.

Batch scope costs nothing, because atomicity already ties those fates together. A batch that replaces one
mesh and drops another keeps the dropped one in the scene, and therefore read, until the replacement's
acceleration structure is ready; both become unreferenced at the same instant regardless. And it is the
only scope a source can write correctly: superseding usually frees the old build while keeping per-mesh
resources that will be reused, dropping frees everything, and only the caller knows which — at submit time,
where the closure is written. A callback attached to an operation would run identically in both cases with
nothing to distinguish them.

Batches from one source apply in submission order. The renderer may coalesce internally, skipping a build
already obsoleted by a later batch, but that is invisible: each batch still reports what it displaced. If the earlier sits in an
unpublished batch, the later replaces it **in place**, so a batch's atomicity is not broken by a later
unrelated call.

**`scenes().generation()` is how a source knows it was emptied** — a new render session, a device loss.
One generation covers scenes, placements and lights, because a scene is what holds the other two: nothing
survives its scene and no scene survives a change here. Compare a long; missing the change is not silent
either, since stale ids are rejected rather than landing nowhere. This is what makes registration optional:
a source that only pushes data registers nothing at all.

**Registering buys notifications, not access.** World changes, resource-pack changes, and one hook nothing
else provides: submissions made during the frame callback land in *this* frame with *this* camera, which
particles need. There is no session-start callback, because a provider is rebuilt per runtime activation
and a session cannot restart without one — a fresh instance already means an empty scene.

---

## Scenes

**A scene is a coordinate system, an acceleration structure, and a set of lights.** Placements and lights
are made *into* one. Meshes and materials are not, and the split is not a convenience:

- **A mesh belongs to no scene** because it is an acceleration-structure input, and a build does not know
  which top-level structure will reference it. A model placed in two scenes is built once.
- **A material belongs to no scene** because `instanceShaderBindingTableRecordOffset` plus `geometryIndex`
  selects a hit record from one shader binding table. Every scene a single trace can reach therefore shares
  one material table, and an id means the same thing everywhere.
- **A placement and a light are scene-scoped** because both are expressed in the scene's coordinates and
  both are gathered by structures the scene owns. Light selection follows: a shading point draws candidates
  from its own scene and no other's.
- **The environment is scene-owned** because it is what a ray leaving the geometry finds, and which geometry
  it left is the scene's. It is not a slot: implementations register as a set and a scene names one, so two
  scenes in one program have different skies with neither extension knowing the other exists. A scene has an
  environment from the moment it is created — there is nothing else for a ray that hits nothing to return.

**A scene is not a namespace.** Ids are issued, so a per-source split would protect nothing; a `SceneId`
says where a placement *is*, not who submitted it, and several sources normally fill one scene. This is why
batches are per channel rather than per scene — a batch may span scenes, which is what publishes two
placements that must not be seen apart.

**The renderer traces the camera's scene and no other.** Which scene that is comes from the host adapter,
not from an extension: choosing what the renderer traces is not a way to add to the image, so by the
boundary rule it is not on the capability list. It reaches sources as `SceneFrameContext.scene()`, and
frame-coherent submission is scoped to it — content in another scene is not view-dependent with respect to
this camera. Other scenes stay retained at full memory cost and contribute nothing.

**Creation is synchronous, for the reason material registration is.** A scene is a table entry and an empty
top-level structure, not acceleration work. Immediacy is what keeps placements and lights free of
cross-channel ordering: both name a scene, and neither could if creation published on a batch boundary.

**Dropping a scene cascades** to every placement and light in it. Rejecting an occupied scene instead would
demand that one source know when every other has left, which none of them can; cascading needs no such
knowledge, and the ids that die go stale, so a source that has not caught up is rejected on its next
submission rather than writing into somebody else's scene. Nothing needs a retirement callback: a placement
owns nothing to reclaim, and a light's parameter buffer goes back through `retireAfterUse` like every other
resource its source allocated.

---

## Geometry

**A mesh is what a build consumes and nothing else**: a position stream, an index stream, a
previous-position stream, and per-geometry flags. Placements are a scene, a transform, and a mask — the
transform in that scene's coordinates, and the mask a ray-visibility filter *within* it, never a way to
select between scenes, which is what keeps membership from being bounded by eight bits. Normals, texture
coordinates, vertex colours, tints, emission, coverage bits — none of it reaches the renderer, because none
of it reaches a build.

Previous positions are the apparent exception and are not shading: the renderer produces motion vectors, so
it interpolates them exactly as it interpolates current positions. Letting a surface author its own was
considered and rejected — a wrong motion vector ghosts the whole image, not one material.

**Material is per geometry, not per triangle.** That is the granularity Vulkan already selects at:
`instanceShaderBindingTableRecordOffset` plus `geometryIndex` picks the hit record. So one BLAS holds one
geometry per material, each naming a contiguous slice of the shared index buffer; only the index buffer is
grouped, never the vertices, and `VK_GEOMETRY_OPAQUE_BIT_KHR` falls out per geometry too, which is where
coverage went. Meshers already batch by render type, so the sort is free, and it removes a per-triangle
indirection at trace time.

This restricts nothing visually. A material fixes only the surface implementation, the medium topology, and
the coverage cutoff; a source splits a geometry only when one of those three actually differs.

### Three words carry everything else

The renderer keeps per-geometry and per-instance records it already indexes on every hit. Three
uninterpreted 64-bit words ride along:

| granularity | typically |
| --- | --- |
| per geometry | a pointer to that geometry's attribute slice |
| per material | a pointer to parameters shared by every geometry using it |
| per placement | an index or packed value distinguishing one placement from another |

Between them they cover everything a surface can need. The per-placement word is what keeps instancing
worth doing — without it, a mesh that must shade differently in two places has to be two meshes — and it is
shading only: the renderer derives motion vectors from a placement's current and previous transform, so
rigid motion is handled, and geometry that genuinely differs per placement is a different mesh.

**Per-triangle data needs no support at all.** `primitiveIndex` is free in a hit shader, so the source's
Slang does `attributes[primitiveIndex]` and the renderer never participates. That is why the per-triangle
record was deleted rather than relocated. Note `primitiveIndex` is **geometry-local** — it restarts at zero
per geometry — which is exactly why the pointer is per geometry: point it at that slice and the indexing is
correct with no base to add.

A mesh has one lifetime: its buffers, until its retirement callback. Resubmitting one whose geometries now
name different materials is an ordinary update; pass the same topology revision and the renderer keeps the
acceleration structure it already built.

---

## Lights

A light is retained in one scene, in that scene's coordinates, and gathered by the sampling structures
that scene owns — so light selection is scene-local.

**Emissive surfaces are lights, not geometry.** The renderer sees no emission on a triangle, so nothing
emissive can be importance-sampled as geometry. Minecraft converts every emissive block face to a rectangle
area light and shades the surface with whatever material it has. If triangle lights are ever wanted they
arrive as a separate retained list, never as a field on scene geometry.

**Shape and emission are separate.** The renderer owns the geometric and sampling contract for every
primitive it supports — point, spot, rectangle, distant. It chooses candidates, samples, and computes the
PDF. An extension replaces none of that, for the reason it does not replace the BSDF: independently
implemented sampling and evaluation can disagree and bias every lighting backend at once.

What an extension can replace is the emitted value, through a Slang **emission profile** named from the
descriptor. The profile computes the final scene-linear ACEScg value from renderer-produced facts —
direction, distance, cone cosine, projected coordinates — plus one uninterpreted word, in the primitive's
native quantity (candela for point and spot, cd/m² for area). It may ignore the descriptor's own colour
entirely. Sampling stays that of the primitive, so a concentrated profile raises variance but cannot
invalidate the PDF.

The descriptor separately carries a conservative per-channel **peak** and an estimated **average power**.
The peak is a correctness contract wherever the renderer culls on it: every value the profile can produce
while that descriptor is retained must stay within it. The average is only a proposal weight — wrong costs
variance, but must never remove the light from sampling support. Keeping both outside the shader lets the
renderer build acceleration and proposal structures without restricting who authors emission.

A spot carries a full orientation rather than a bare direction, because a two-dimensional profile — a gobo,
a projected texture — needs a stable roll axis for projected coordinates. Separate horizontal and vertical
half-angles define the projection and its aspect; a plain radial falloff ignores both.

**The boundary:** a different emission distribution over a supported primitive is content and belongs in
the API. A new emitter *shape* or transport model — a tube, a line, a volumetric beam — changes how the
renderer samples light and is an engine feature.

---

## Material

The renderer owns the OpenPBR BSDF and evaluates it once, so an implementation cannot break value/pdf
agreement. It uploads **no** shading parameters. Colour, roughness, metalness, transmission and emission
are the extension's business.

A material carries only what the renderer reads: which registered surface implementation shades the hit,
whether it bounds a participating medium, the coverage cutoff any-hit compares against, and one
uninterpreted 64-bit word — wide enough to be a device address, so parameters are whatever buffer the
source points at, with no size beyond which an extension starts packing bits.

**Registration is synchronous** and returns a usable id. A material is a table entry, not acceleration
work, and immediacy is what removes cross-channel ordering as a concept: geometry submitted afterwards can
always name it.

So a resource reload is not a global event. Register the new materials, resubmit the affected meshes in one
atomic batch, drop the old ones. Both sets are live in between — peak memory, in exchange for **no frame in
which the scene references something that has been taken away**. Granularity is the source's choice; under
an epoch model there was none.

What still bounds a material is the runtime activation, because its surface names a compiled implementation
and the compiled closure changes with feature selection. Materials are retained *within* an activation and
a new activation starts empty, which is the geometry rule, so it stays one story.

### Dropping tells you when your resources are free

The renderer counts which geometry names each material, because it reads a triangle's material id directly.
Dropping does not free anything immediately: it marks the material and calls back once nothing names it and
no submitted GPU work still reads it.

That callback is the point of the refcount. The renderer's own entry is a few words and costs nothing to
hold. What is expensive is whatever the *source* associated with the material — its textures, parameter
buffers, descriptor ranges — and only the source knows what those are.

So an out-of-order drop is neither an error nor rejected. The drop is honoured from the source's point of
view, its bookkeeping stays consistent with the renderer's, and retirement arrives when the last mesh goes.
A source that forgets to drop that geometry never gets the callback — observable from its side, and worth a
renderer warning when a pending retirement outlives expectation.

---

## Resource injection

`VK_EXT_descriptor_heap` collapses Vulkan's binding model to user-written descriptors in a user-allocated
heap — one resource heap and one sampler heap bound at a time, no set layouts, no pools.
`VK_EXT_descriptor_buffer` got partway (application-managed memory) but kept sets and layouts.

The heap model is what makes extension-owned resources actually extension-owned. A source writes its own
descriptors, keeps its own indices, and its Slang indexes the heap directly. The renderer discovers nothing
by reflection, publishes no named bindings, and never learns a texture exists.

**There is no texture concept in the API** — no registry, no engine-issued slot, no texture type. A texture
is extension GPU data like any other. That removes texture residency, texture lifetime, texture
refcounting, and the class of bug where a slot means one thing to the renderer and another to the shader
sampling it.

**The renderer still owns the heap**, because only one can be bound: it allocates and sub-allocates index
ranges — an allocator, not a registry. It does not know what a descriptor is for, does not refcount it, and
does not retire it; a source frees its range through `retireAfterUse`. Worth naming rather than pretending
resource ownership is total.

**This is all-or-nothing.** Heap binding and legacy descriptor sets cannot mix in one draw or dispatch, so
adopting it moves the entire renderer — world pipelines, post passes, UI passes — and sets a hardware
floor.

**The environment reaches its resources this way, and that is what keeps it independent of its pass.** A sky
bakes transmittance and sky-view LUTs in a `WorldResourcePass`, writes their heap indices into a buffer it
owns, and puts that buffer's address in the environment's word. The pass and the environment are then
coupled by nothing but a pointer the extension itself holds, so they can be added and dropped separately and
a mistake is the same class of bug as a material pointing at a freed parameter buffer — which is already the
contract. The alternative was to bundle the pass into the environment so one lifetime covered both, and it
is only unnecessary because of this.

Named bindings (`publishWorldTexture`, `publishWorldBuffer`) and anchored resource modules remain for
everything not yet moved, and are what the heap retires.

---

## Passes declare when, not what

Extensions own their passes outright — shader, pipeline, intermediates, dispatch. The pass API answers two
questions and nothing else.

**Order.** Which interface a pass implements *is* where in the frame it records. There is no stage enum,
because there are exactly as many places to record as there are interfaces:

| interface | records | reads | writes |
| --- | --- | --- | --- |
| `WorldResourcePass` | before the trace | nothing from the frame | its own heap range and buffers |
| `PostEffectPass` | after reconstruction | scene colour, exposure | a chain target, which enrols it |
| `UiPass` | after the display transform | nothing of the world but the camera | the UI layer |

An extension constructs its own pass and hands over the instance; there is no factory and no engine-created
context, because whatever two of your passes share you already have somewhere to put. Adding runs
`activated` against the current epoch before the pass records anything, so one added mid-session is
indistinguishable from one added at startup; removing runs `deactivated` once the last frame that recorded
it completes, and the instance is finished.

Passes record in the order they were added, which is meaningless for world-resource and UI passes and load
order for everyone. The post-effect chain is the one place order is visible, so a pass may anchor itself to an
end of it — `FIRST` for a pass that wants the scene image as reconstruction left it, `LAST` for a grade,
`MIDDLE` for everything else, which is nearly everything.

**An anchor is not a priority and not a dependency.** There is no number to escalate and no way to name
another pass, so a pass still cannot say "after theirs" — deliberately, because the rule that makes the
chain composable is that **a pass must work when the passes around it are absent**. Two passes claiming the
same end run in the order they were added: they both wanted the same place and only one can have it.

**Lifetime.** One shared lifecycle names the scopes a pass's resources live inside, and every callback that
can allocate receives the same setup, so a rebuild after a resize or a pack swap has the handles the first
build had.

```
activated                                                       deactivated
├─ displayResized ─────── displayResized ──────────────────────────┤
└─ resourcePackClosing ── resourcePackApplied ── resourcePackClosing┘
```

A pass instance belongs to one runtime activation and is never reused, which is what makes the final
callback an unconditional destroy — everywhere else, `retireAfterUse`.

### UI is not a post effect

A UI pass records once per *rendered* frame; one recorded layer may contribute to several presented frames.
The API promises only that presentation keeps UI out of scene interpolation and consumes it for every
output frame — not which recomposition a frame-generation backend uses.

**World-anchored overlays are UI.** Selection boxes, entity outlines, name tags, waypoint markers —
anything drawn at a world position but authored as crisp 2D — belongs here even though the camera positions
it. Two reasons, either sufficient: thin high-contrast geometry does not survive a temporal upscaler, so it
must be drawn at display resolution after reconstruction; and it must not be embedded in the scene input
that frame generation interpolates. A post-effect pass fails both. The post chain is for effects on the
scene image itself.

The camera a UI pass receives is the rendered frame's, and no new one exists for a generated frame — so
alignment between world-anchored UI and a generated scene cannot be exact. Anchor to the world only where
that is acceptable, never for a reticle.

---

## The display transform is not replaceable

```
scene-linear ACEScg → exposure → post chain (grade last) → ACES 2.0 output transform → sRGB SDR / PQ BT.2020 HDR
```

The output transform is not a function an extension could swap; it is a multi-way contract. One
scene-linear input fans out to SDR and HDR with peak-nits adaptation, and its output must stay compatible
with the UI composite, with HDR screenshot capture, and with what frame generation expects at present.
Replacing it would also force the display mode to become visible to extensions — the one fact the UI
contract deliberately hides.

Same argument as materials: the renderer owns the BSDF so an implementation cannot break value/pdf
agreement, and owns the output transform so one cannot break the display contract. By the boundary rule a
different transform is not adding to the image — it is changing how the renderer maps its output.

**A look is an ordinary post effect**, and that is not a workaround: ACES puts an LMT scene-referred and
before the output transform, which is exactly where the post chain already sits. So a LUT loader and a
procedural grade are the same kind of thing as bloom, need no mechanism of their own, and reach the right
place by anchoring `LAST`.

It used to be a fixed slot, on the argument that a Slang function composes at compile time and costs no 3D
texture fetch. That is a performance argument, and it bought a whole selection mechanism to serve one
customization point. The dispatch it saves is one per frame.

What a slot did buy is exclusivity: two extensions can now both anchor a grade `LAST`, and both will run.
Nothing detects that, because nothing in the API knows which post effect is a grade. If that has to be
prevented, the answer is a frame position of its own — an interface, the way UI is — not a return to
selection.

**Deliberately foreclosed:** an extension cannot ship AgX, Khronos PBR Neutral, or Filmic. Those are tone
curves, not looks, and a post-effect pass cannot substitute because the output transform still runs
afterwards and would transform twice. If wanted, they arrive as an engine-side setting the renderer
validates and applies itself — a small PR, not a new extension surface.

---

## User stories

**A terrain mod adds voxel geometry.** Builds meshes on worker threads into its own arena, sorted by
material so each becomes one geometry, with UVs, normals and tints packed into an attribute buffer of its
own layout. Lends positions and indices, points the geometry word at that buffer, and maps each packed
section position to an issued mesh id placed once in the dimension's scene. A rebuild is one update under
the mapped id; unload drops the mesh and the map entry. It refcounts the arena through per-mesh retirements and frees it at shutdown.

**The same mod survives a resource reload.** Registers the new materials, resubmits every affected section
in one batch, drops the old materials. Both sets live in between, so no frame shows a wrong texture, and it
frees the old atlas in the retirement callback of the last dropped material — the only signal that says the
GPU is done.

**A shader pack replaces the sky.** Declares an environment implementation, keeps the key its
registration issued, and hands it to whoever creates the scene. Plus a `WorldResourcePass`
that bakes transmittance and sky-view LUTs into images it owns, whose heap indices its own Slang reads. The
renderer never learns what a LUT is.

**A mod adds dispersive stained glass.** Registers a surface implementation and materials naming it, with a
medium topology and a material word pointing at its parameter buffer. Writes its textures into its heap
range, stores those indices in that buffer, and reads `primitiveIndex` to pick a tile.

**A mod makes glowstone emit.** Shades the block with an ordinary material and separately retains one
rectangle area light per emissive face, in the same scene as the block. The renderer samples the rectangle and computes its PDF; emission
never appears in the geometry at all, which is why the acceleration path needs no shading fields.

**A mod adds film grain.** Registers a `PostEffectPass`, allocates its intermediate at `displayResized` and
retires the previous one, takes a chain target each frame, writes every pixel. Skipping the target for a
frame removes it from the chain at no cost.

**A minimap mod draws waypoints.** Registers a `UiPass`, projects markers with the frame's camera, draws
crisp 2D into the layer. Not a post effect: it would be blurred by the upscaler and interpolated as part of
the scene.

**A mod adds a textured flashlight.** Retains a spot with a full orientation and an emission profile. Its
`WorldResourcePass` publishes the cookie; the light's word addresses it. The renderer still samples the
spot and computes its PDF while the profile authors the angular emission, and the descriptor supplies the
conservative peak.

---

## Open questions

**Lights are described as one shape with geometry but are not yet one implementation.** The retained-update
model above is the target for both; the light path still needs the issued-id and batch treatment before the
symmetry claim in capability 2 is true.

**Tracing more than one scene is engine work, and only the container is in place.** The API can hold
several scenes; the renderer traces one. Crossing between them mid-path — a portal as a ray switch — is the
second row of the boundary rule, because it changes how the renderer traverses rather than what it draws.
What it needs: an array or heap-indexed TLAS binding in place of the single `topLevelAS`; `camOffset` and
the procedural domain offset moved out of push constants into a per-scene table, since each scene rebases
against its own origin; a scene index on the resumable path segment; and a rule for what the medium stack
does at a crossing. The extension-facing half is a scene transition declared as data — a material topology
that says this geometry switches, and a per-placement target scene with the transform into its frame —
because the switch itself is transport and the renderer owns transport for the same reason it owns the BSDF
and the light PDFs.

Portals also want a sampling strategy, not an emitter: a portal has no emitted value to author, so it is
not a `LightDescriptor`. Sampling a point on the aperture gives a direction with a known PDF, the ray
traced along it crosses and returns what it actually finds, and MIS against the BSDF strategy keeps the
estimator exact. The renderer can derive the aperture list from the transition placements it already knows,
so this costs no API surface. It reuses the rectangle solid-angle sampler the area light needs anyway, and
the mixture weight is a proposal weight under the rule `averagePower` already states — wrong costs variance
and nothing else.

**Descriptor heap: what it actually costs.** The heap model is the better fit and the reason capability 3
has no publish API. It also indexes acceleration structures uniformly, which is what a second TLAS would
want.

**It is not a hardware trade.** The floor is already set by what the renderer requires to trace at all —
`VK_KHR_ray_tracing_pipeline`, `VK_KHR_acceleration_structure`, `VK_KHR_ray_tracing_position_fetch`,
`VK_KHR_ray_query` — which is Turing and later, RT cores or not. `VK_EXT_descriptor_heap` does not reach
less far than that, so choosing it over `VK_EXT_descriptor_buffer` gives up no card that could have run this
renderer anyway. The exact driver-version floor is worth recording here once someone has confirmed it
against a support matrix rather than asserted it.

What it does cost is that the two binding models cannot mix in one draw or dispatch, so adoption moves the
whole renderer at once — world pipelines, post passes, UI passes — and cannot be a runtime fallback without
two binding paths through all of it. That is the real question, and it is about migration size rather than
reach.

LWJGL is not a blocker: `EXTDescriptorHeap` ships in 3.4.1, the version already pinned, with
`vkWriteResourceDescriptorsEXT` writing descriptors to a host address — which is what
`GpuDescriptorRange.mapped()` exists to hand over.

**`SurfaceInput` is well behind the Java side.** It still carries texture coordinates, LOD, base texture
index and flags, tint, vertex colour, primitive emission, and a tangent frame — all now the extension's own
business behind a pointer — and its material word is still 32 bits, which the pointer-indirection idiom
above needs widened to 64 before an extension can use it at all. It should reduce to the three words,
`primitiveIndex`, position, geometric normal, outgoing direction, and the world-pinned procedural position.
The tangent frame in particular belongs to the extension, being derived from texture coordinates the
renderer no longer sees.

**Opacity micromaps are unplaced.** They *are* a build input, so by the rule above they belong in the mesh
— most likely as a prebuilt micromap the source hands over, since the conservative per-triangle bounds the
renderer used to take no longer cross the boundary.

**The singleton is two surfaces.** Installing the host adapter is bootstrap; the registry and the channels
are the extension entry point. Splitting bootstrap into its own type would say "not for extensions" in the
name rather than in a comment.

**Two lifecycle vocabularies.** Passes and providers name the same scopes differently, and the provider
side has no setup payload on resource-pack callbacks — acute now that sources own GPU buffers and must
rebuild them there. The two-phase provider teardown does earn its keep: it is the CPU side, stopping
dispatch before releasing after workers join, which passes do not have.

**Refcounting granularity.** Material retirement needs each mesh's distinct material set, built while the
renderer already walks its geometries — so the cost rides along with a pass it makes anyway. Refcounting
per (mesh, material) rather than per triangle keeps it that way; worth confirming before the walk is
written.

---

## Potential improvements

- **State the ignore rules once.** Dropped submissions, rejected batches, and disabled contributions are
  all "the renderer carries on without you", documented per site rather than in one place.
