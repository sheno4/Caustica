# Caustica Architecture

Status: current architecture and remaining extraction seam. `EXTENSION_API.md` owns the concrete Java
and Slang contracts.
Scope: how the mod is layered, and how other code extends it.

## 1. Thesis

**The mod is the API.** Caustica is a path-tracing engine that happens to render Minecraft, with a public
Java API that other mods extend. Extensions ship Java and Slang; the engine compiles their Slang at
runtime and calls their Java through narrow, versioned interfaces.

This replaces the earlier plan, which modelled extensions on Iris: shader source plus a declarative
manifest, no Java. That plan was wrong for this project, for a reason worth writing down.

### 1.1 Why not the Iris model

Iris packs are GLSL files at fixed filenames (`gbuffers_terrain.fsh`, `composite1..99.fsh`, `final.fsh`)
plus `shaders.properties` and magic comments. Not a graph DSL — a fixed-slot model where creating a file
opts into a slot.

The decisive limitation: **an Iris pack cannot add a light or add geometry.** Handheld light is a uniform
(`heldBlockLightValue`) that a shader turns into an analytic light at the camera — no shadows, nothing
off-screen. Block light is Minecraft's baked voxel lightmap, which shaders recolor but do not compute.
The most-wanted "shader" feature in the ecosystem, dynamic block lights, is implemented by
**LambDynamicLights — a Java mod that mutates Minecraft's light data.** It could not have been a shader
pack.

Caustica has a real light database, a TLAS, and a material registry. Extensions will want to put things
*into* them: a spotlight item, vanilla-style cloud geometry, a second sun that actually casts shadows,
Distant Horizons geometry. None of that is expressible as shader source, and inventing a JSON DSL to
express it would be building a programming language badly.

A second reason: the original motivation was "turn forks into contributions." The forks are
DH support, NRD, SHARC, LOD, BLAS slabs, frame generation — **all Java**. A shader-only extension API
does not address the problem that motivated it.

## 2. Layering

Three layers. Today all of it is one package tree; this describes the target and the boundary discipline
to adopt before the physical split.

### 2.1 `core` — a Vulkan path tracer

Knows nothing about Minecraft. Owns:

- Vulkan context, device bring-up, GPU executor, queue and submission policy (`GpuContext`,
  `RtGpuExecutor`, `RtDeviceBringup`)
- Acceleration structures: BLAS/TLAS lifetime, compaction, OMM, instance assembly (`RtAccel`)
- The **light system**: host-neutral finite and distant light records, GPU upload, sampling, NEE and RIS
- The **material system**: canonical texture pages, material IDs, surface attribute decode
  (`RtMaterialRegistry`)
- Participating media and the medium stack
- Path integration, transport, and the estimator contract
- Reconstruction/denoise integration (`RtDlssRr`, NRD when it lands), frame generation (`RtDlssFg`)
- Screen transform: working space, exposure, tone LUTs, ACES SDR/HDR output, presentation
  (`RtExposure`, `RtToneLut`, `RtDisplayPipeline`, `RtHdr`)
- Pipeline and descriptor management, SBT layout (`RtPipeline`)
- Runtime Slang compilation and the shader cache (`SlangRuntime`, `WorldShaderCompiler`)
- Frame orchestration, and eventually the frame graph (today: `RtComposite`)

### 2.2 `core_minecraft` — Minecraft as a client of core

Everything that knows what a block, chunk, entity, or resource pack is. Feeds core through the same
interfaces a third-party extension would use:

- Chunk section extraction and meshing into BLAS input (`RtTerrain`, `RtTerrainMesher`, `RtFluidMesher`,
  `RtSectionTable`)
- Entity capture and submission (`RtEntities`, `RtEntityCollector`, `RtEntityCapture`)
- Minecraft block light and emissive discovery → core's light system (`RtLightCollector`,
  `RtLightHierarchy`, `RtLightGridManager`)
- LabPBR decode, sprite discovery, material JSON overrides → core's material system
  (`RtBlockMaterials`, `RtMaterialOverrides`, `RtEmissionSemantics`)
- Mixins, world/overlay integration, HUD and name tags

The public API and the new `engine/*` packages are protected by import-firewall tests: their IDs, frame
state, geometry, materials and lights contain no Minecraft types. The honest remaining seam is the
optimized Minecraft adapter. Chunk terrain and animated entity capture still feed specialized retained
prefix/per-frame paths because they preserve asynchronous meshing, compaction, refit and atlas behavior
the generic retained-mesh path does not yet match. They merge into the same canonical geometry table and
TLAS; they are not a second public engine API. Minecraft's section light grid similarly remains an
optimized adapter beside the generic finite/distant provider-light path.

### 2.3 Extensions — everything else

Ordinary Fabric mods (or, initially, internal packages) that depend on core and register into it.
Examples, and which mechanism each uses:

| Extension | Mechanism |
|---|---|
| Spotlight helmet item | register a light provider + game-state hook (landed proof) |
| Rounded block clouds | register a scene provider + named material definition (landed proof) |
| Distant Horizons bridge | register a scene provider |
| Bloom | register render passes |
| Nether / End sky | bind the `caustica:sky` slot |
| Water waves | bind an appearance slot |

Built-in features ship as extensions using only public API. That makes "no privileged built-ins"
structural rather than a rule to police — the same property that kept the bundled default pack honest.

Terminology: `EXTENSION_API.md` §1 settles the words. An **extension** is the mod; a **feature** is the
user-visible toggleable unit it contributes; a **slot** is an engine-declared substitution point; a
**provider** is an additive registration. "Module" is reserved for Slang modules throughout.

### 2.4 Interfaces now, jars later

Define the interfaces and make `core_minecraft` implement them **now**; keep everything in one jar. Do
the physical jar split when a second real client exists — Distant Horizons is that client.

An abstraction with exactly one implementation tends to be shaped like that implementation regardless of
intent. The interface discipline gets most of the design benefit immediately; the jar boundary gets
*enforcement*, and enforcement is only worth the refactor cost once there is something to enforce
against.

## 3. The three extension mechanisms

Organized by mechanism rather than by audience, because mechanism determines API shape and composition
behaviour.

| Mechanism | Examples | Composes | Stability |
|---|---|---|---|
| **Register** (additive) | scene providers, light providers, render passes | many coexist | strong — engine calls a narrow interface |
| **Substitute a resource** | tone LUT, LMT, noise textures | one wins per slot | strong — engine declares the slot |
| **Substitute code** (bind a slot) | sky, BSDF, water waves | one wins per slot | varies — see §3.2 |

Register composes; substitute does not. That is not a coincidence: you can add two lights, you cannot
have two water models. Where a mechanism does not compose, the engine needs an explicit owner —
resolved by the user picking one candidate per slot in the settings UI, never by silent last-wins
(`EXTENSION_API.md` §8).

Substitution is **per slot, not per extension**. One mod can win `caustica:sky` while another wins
`caustica:medium`; the engine composes the selection into a generated root type at compile time. That
granularity is what makes "Nether sky from one mod, clouds from another" expressible at all, and it is
the main structural correction over the ray-pack revision.

### 3.1 Register

The engine calls the extension. Provider interfaces are the extension points that need Java, because
they touch structures no shader can reach:

- `SceneProvider` — retain indexed CPU triangle meshes under provider-local stable keys and submit
  double-precision world-space instances. The engine owns upload, BLAS, canonical geometry records,
  rebasing, TLAS assembly, and exact graphics-timeline retirement.
- `LightProvider` — submit a complete per-frame snapshot of rectangle, point, spot and distant lights in
  scene coordinates and physical units; omitted lights disappear immediately and submitted lights
  participate in path-traced visibility/lighting
- `CausticaRenderPass` — contribute Vulkan work at a defined frame stage (§4)
- `MaterialSource` — define stable, textureless OpenPBR materials for provider geometry and submit ordered
  host-material override rules; material-table indices and texture bindings remain epoch-private

Every provider call must be failure-isolated: an exception disables that provider with a clear log, and
never kills the frame loop. Same discipline as the existing per-stage shader fallback.

### 3.2 Substitute code — two forms, very different stability

These look alike and are not:

- **Slot binding.** The engine declares `ISkyModel.evaluateEnvironment(...)`; a feature implements it and
  binds the slot. Checked, narrow, versioned. Engine changes produce a compile error naming the method.
  Registered *sets* — `ISurfaceModel` implementations, which materials select between rather than
  competing for one winner — are the same mechanism with a generated switch instead of a typealias.
- **Slang module substitution.** The engine imports `water`; a feature's `water.slang` wins on the search
  path. Broad and powerful, but ABI-by-convention: every function the engine calls in that module becomes
  an implicit contract, and *adding* a call the engine makes can break overrides silently.

Slang module substitution is how the fork problem gets recreated inside the extension system. **Prefer
slot binding wherever the call surface is known** — for water waves it is exactly one function. Keep
substitution as an escape hatch, unadvertised and unversioned.

### 3.3 Substitute a resource

The engine declares a named slot with a format and semantic contract; a feature fills it, or the built-in
default does. Tone LUT, LMT, and the sky LUTs are already shaped this way (`RtToneLut.load`,
`RtPipeline.setSkyLuts`). No extension binds these yet — the look/LUT extension point is deliberately
deferred so that one API lands correctly before a second one exists.

## 4. Render passes and the fixed-slot constraint

A first pass API (bloom, then the sky LUT) was declarative: `ResourceRegistry.image(...)` /
`imagePyramid(...)` described resources for the engine to allocate, and `PassContext.dispatch(...)`
described a compute call for the engine to record. It worked, but it was a bespoke, compute-only
reimplementation of a slice of Vulkan — no graphics or ray-tracing passes, no buffers, one dispatch shape —
bought for isolation it could not actually enforce, since an extension runs in-process and can reach
`GpuContext.get()` regardless of what the declared API offers. It was replaced with the shape below, which
gives extensions Vulkan directly plus hardened helper types, on the reasoning that the honest version of
"give extensions the engine's own tools" is the engine's own tools, not a parallel abstraction over them.

Three requirements survive from the first slice and still shape the current one:

1. **Recording is imperative.** Bloom is one logical pass but `2N-1` dispatches whose count depends on
   runtime resolution — a `for` loop, not a declared list.
2. **"Runs once" is a first-class lifecycle.** `CausticaRenderPass.create` runs once; `resize` runs on
   dimension change; a pass with its own persistent bake (the sky LUTs) tracks that itself and clears it
   in `invalidate()`, called by the engine on a world/resource-reload boundary.
3. **Engine-consumed resources are pass-declared, not engine-declared.** `EngineImage` (a fixed Java enum
   mapping names to hand-maintained `bindings.slang` binding indices, one `RtPipeline.setXxx` method per
   entry) is gone. In its place, two mechanisms split by which pipeline actually reads the resource:
   - `PassSetup.publishWorldResource(name, ...)` — for a resource the *world ray-tracing pipeline's own
     shaders* read (the sky LUTs feeding `sky_miss.slang`). The pass declares its own `[[vk::binding(N,
     2)]]` in a module it owns (e.g. `caustica_lut_sky_bindings.slang`); the engine reserves descriptor
     set 2 generically and discovers every binding in it — name, index, *and kind* — from that
     composition's own Slang reflection (`WorldShaderCompiler.passResourceBindings()`). Kind isn't
     limited to sampled images: the reflected type shape (`kind`/`baseShape`/`access`/`combined`, matched
     against `WorldShaderCompiler.PassResourceKind`) also covers storage images
     (`RWTexture2D`→`STORAGE_IMAGE`) and buffers (`StructuredBuffer`/`RWStructuredBuffer`→
     `STORAGE_BUFFER`, `ConstantBuffer`→`UNIFORM_BUFFER`) — `PassSetup` has both a `(name, GpuImage,
     sampler)` and a `(name, GpuBuffer)` overload, and `RtPipeline` picks the matching `VkDescriptorType`
     and write shape per binding. A pass needing more than one buffer's worth of data doesn't need a
     second mechanism either: it can stash further addresses inside its own buffer and chase them with
     `ConstPtr<T>` from there, entirely within its own Slang — this is why buffer device addresses
     (`WorldPush`'s own delivery mechanism) never needed extending for this.

     One real Slang constraint shapes all of this: a generic entry point's type-parameter implementation
     (here, whatever `TC.Sky` resolves to) is not allowed to declare global shader parameters itself — the
     binding must be reachable through an ordinary, non-generic import. `FeatureBuilder.passResourceModule`
     is how a feature says "anchor this"; the engine imports every selected feature's declared modules
     into one generated, always-valid module (`caustica_pass_resources`, built alongside the composition
     root) that `sky_miss.slang` imports unconditionally — so the engine still never needs to know a
     specific pass's resource *names*, only that this fixed anchor module exists.
   Nothing analogous is needed for a resource the *display-mapping* pipeline reads, because no pass hands
     it one: post effects chain over the scene image instead (see below).

   The engine's own two inputs (`RECONSTRUCTED_COLOR`, `EXPOSURE`) went the other way, since those are
   genuinely engine-owned per §6: `PassFrame.sceneColor()`/`exposureImage()`, plain read accessors, no
   registry.

4. **Post effects chain; they do not hand the display map layers to combine.** `PassFrame` exposes
   `sceneColor()` (the display-res scene image as it stands right here — scene-linear ACEScg, unexposed)
   and `sceneColorTarget()` (where this pass writes its version of it). Taking a target is what joins the
   chain: the engine then barriers after the pass and hands what it wrote to the next pass's
   `sceneColor()`, and the display map reads whatever the last one produced. A pass that never asks for a
   target — a sky bake, a light upload — leaves the chain untouched and costs it nothing, and with no pass
   chained at all the display map reads the reconstruction directly.

   Two display-res `R16G16B16A16` images rotate, which is enough for a chain of any length because the
   reconstruction seeds it read-only; that also keeps the raw reconstruction intact for the two engine
   consumers that want it unmodified (auto-exposure metering, the debug scene view). Read and write are
   always distinct images, so an effect may gather from neighbouring pixels — which is exactly what the
   discarded alternative could not do.

   *This replaced a registry of additive layers the display shader summed.* That version worked for bloom
   and could not have worked for anything else: additive layers hide ordering because addition commutes,
   and layers were composited last, inside the display map, so a chained effect would have run against the
   pre-bloom image and had bloom added on top of its result afterwards. It also made "blend mode" an
   engine concept that would have had to grow an enum, while still not expressing an effect that needs the
   composite as a *spatial* input (depth of field, motion blur, lens distortion). Chaining is both more
   general and less engine machinery: the pass reads the previous image and writes any function of it, so
   the engine has no notion of blending at all. The cost is one full-res read+write per chained pass
   (~59 MB, well under 0.1 ms at 1440p) plus the second rotation image, against bloom's former free ride
   inside a read/write the display pass was doing anyway.

   Deliberately scene-referred only. The display map writes two encodings from one `lookedAcesCg` — sRGB
   SDR and PQ/BT.2020 HDR — so a *display*-referred chain would have to run twice over two encodings with
   every effect encoding-aware. Grain, vignette and chromatic aberration stay inside the display shader
   until there is a second consumer that justifies a separate, explicitly display-referred stage.

The v1 rule is unchanged: an extension may create resources freely for its own passes; it may fill an
engine-declared slot; it may not invent a new engine-visible binding *in set 0* (the truly fixed slots —
TLAS, gBuffers, block/celestial atlases). Set 2 is the escape hatch this section describes — one
reflection-discovered binding per declared name, any of the four kinds above; still not an unbounded
bindless array the way set 1's entity/material textures are, and still nothing secondary rays see beyond
what's already reachable by BDA. Clouds as *geometry* work today (a scene provider feeding the TLAS);
clouds as a *volume the engine's integrator samples* still don't — that needs either an unbounded array
(closer to set 1's shape than set 2's) or the descriptor model going fully bindless, neither of which set
2 was built for.

The landed shape:

```java
public interface CausticaRenderPass {
    ResourceId id();
    RenderStage stage();
    default List<ResourceId> after() { return List.of(); }   // ordering within a stage
    default void create(PassSetup setup) {}                  // pass allocates its own GPU resources
    default void resize(PassSetup setup, int w, int h) {}
    void record(PassFrame frame);                             // raw VkCommandBuffer, mid-recording
    default void invalidate() {}                              // redo persistent bake state
    default void destroy() {}
}
```

`PassSetup` exposes `GpuContext` (device, allocator, `createStorageImage`/`createBuffer`, debug labelling),
`publishWorldResource` (see above), and now `options()` — a live (not frame-frozen) read of
this pass's owning feature's `Option` values, for a create/resize-time decision like sizing an image
pyramid (see §7's `CausticaOptions` entry). `PassFrame` exposes the command buffer, the
display extent, `sceneColor()`/`sceneColorTarget()`/`exposureImage()`, an `options()` snapshot frozen for the whole
frame (unlike `PassSetup`'s live read — see §7),
and one `memoryBarrier()` helper matching the broad full-pipeline-barrier idiom used everywhere else in
`rt/RtComposite.java`. There is no `ComputeProgram`, `ImageRef`, or `DispatchImage` — a pass builds its own
descriptor sets and pipeline against `GpuContext` directly, the same way `RtExposurePipeline` and
`OverlayPipelines` (then `RtOverlayPipelines`) already did before any pass API existed. `GpuImage`/`GpuBuffer` (renamed from
`RtImage`/`RtBuffer` — §10.1's rename, done early because these two are now genuinely public API surface)
are the only non-raw types a pass touches, and both are thin RAII wrappers around a handle, not an
abstraction over Vulkan's semantics.

Two decisions from the first slice are reversed here, deliberately:

- **Barriers between dispatches are no longer automatic.** The declarative version inserted one after
  every recorded dispatch: safe, but it assumed every pass is a flat list of compute dispatches with
  nothing else going on, which stops being true the moment a pass does graphics or mixes compute with a
  copy. A pass now calls `PassFrame.memoryBarrier()` itself between its own dispatches; the engine still
  inserts the one barrier a pass has no way to place correctly — after a whole stage's passes finish,
  before the next stage's read.
- **Extensions size their own resources in pixels.** The `Size.displayRelative(2)` / `Size.fixed(256, 64)`
  policy type is gone; a pass reads `PassSetup.displayWidth()/displayHeight()` in `create`/`resize` and
  calls `GpuContext.createStorageImage` with whatever dimensions it wants. There was no real allocation,
  resize, or retirement policy for the engine to own here beyond what `GpuContext` already provides to
  every other engine-internal caller — the size-policy type was solving a problem the declarative
  resource layer created for itself.

## 5. No archive format in v1

There is no installable shader-pack zip, and no separate look/LUT pack. An extension is an ordinary
Fabric mod that ships Java and Slang and registers through the API above; the engine compiles its Slang
at runtime and calls its Java through the interfaces in §3.

What that removes from v1:

- One public API to version and document, not two.
- Manifest schema, discovery, duplicate resolution, and API-version negotiation disappear rather than
  moving — a Java registration is compile-checked, and there is nothing to discover.
- `additionalProperties: false` and its whole validation tier stop being an engine compatibility promise.
- The archive format question is deferred until a third-party ecosystem exists to answer it.

What survives from the pack work: the Slang interface contract, the ownership analysis, static
specialization, and validate-then-swap activation. The one rule that stays fully intact is that an
extension ships Slang *source* — never SPIR-V, a descriptor layout, or pipeline metadata.

The cost is deferral, not elimination. If an archive format is wanted later, the honest way to add it is
as a loader feature written against this same public API — which also dogfoods it: if the loader can be
written that way, the API is expressive enough for the interesting cases.

`EXTENSION_API.md` is the spec for the Slang interfaces (surface, sky, medium), the slot composition
model, and the compilation and lifecycle contract.

## 6. What the engine never delegates

These stay engine-owned regardless of layer or mechanism, because they are correctness, not policy. Most
were established by the ray-pack work and survive the reframing unchanged.

- **Submission, queue policy, stage order, engine-slot lifetime, canonical render products.** A render
  pass *does* see the raw `VkCommandBuffer` mid-recording and *does* build its own descriptor sets and
  pipelines (§4) — that reversal from the original framing is the point of the current pass API. What
  stays engine-owned regardless: which queue a frame submits to, the order stages record in, the lifetime
  and layout contract of engine-declared descriptor sets (set 0, and reserving set 2's *index* — never
  what's inside it), and the barrier crossing between one stage and the next. Everything a pass allocates
  for itself, it manages itself; the engine does not track or validate it.
- **Double-count invariants.** Celestial-disc visibility and emitter direct-hit gating are derived by the
  engine from lobe classification, never set by an extension. Anything that could set them directly could
  silently double-count or lose light, and the symptom is "this looks brighter," not a visible failure.
- **Dielectric interfaces and the medium stack.** Fresnel, reflect/refract, TIR, push/pop.
- **The estimator contract.** A BSDF's value and pdf must agree, stay finite, stay non-negative, or every
  lighting backend is biased. Look policy applies to an already-shaded contribution, never to the BSDF.
- **Working space and pre-exposure.** Scene-linear ACEScg in absolute units; pre-exposure applied at one
  storage boundary and cancelled at display.
- **Payload, SBT, and descriptor ABI.**
- **Canonical render products and their conventions** — depth, motion, normal-roughness, albedo — since
  reconstruction correctness depends on all producers agreeing.

## 7. What exists today

Honest status, so this reads as a target and not a claim:

- **The ray-pack vocabulary is fully retired.** `EXTENSION_API.md` §10's rename map is done: `rt/pack/`,
  `RayPack*`, `IRayPack`, `pack_*.slang`, and `resources/caustica/raypacks/` are gone. Runtime Slang
  compilation lives in `rt/shader/WorldShaderCompiler`, driving the pinned compiler via the
  `causticaslang` shim — `compileSpecialized` for entry points specialized with a slot selection,
  `compilePlain` for ordinary stages. `caustica.rt.dynamicWorldShaders` and the build-time SPIR-V fallback
  are both gone too: runtime compilation is now the only path for the whole world pipeline, with no
  fallback if it fails.
- **The registry, slots, and providers exist and are load-bearing.** `CausticaApi` / `CausticaRegistry` /
  `Feature` / `Slot` / `Slots` are real; `caustica:builtin` registers through the same API a third-party
  extension would use (`builtin/BuiltinExtension.java`), which is the dogfooding check §8 step 3 originally
  asked for landing later — it landed with the registry instead. Slot selection compiles a generated
  composition root (`rt/shader/Composition`, `CompositionManager`) rather than reading two config
  booleans. Persisted selection and the settings controls call `registry.select()`; installing a second
  sky binding therefore becomes a user choice rather than last-wins behavior.
  `SceneProvider.submitGeometry` is a working retained-geometry path,
  exercised by Minecraft's generated rounded clouds: public records contain CPU arrays, resource-named
  material handles, and world transforms only, while `RtSceneGeometryManager` owns their Vulkan lifetime
  and merges them into the same 24-bit geometry-record index space as terrain and entities. Minecraft's
  optimized terrain prefix and per-frame entity capture remain an explicit adapter input to that generic
  composition step; they are not represented as a second public scene API. `LightProvider` now also has
  a working `submitLights(LightSink)` snapshot path. Minecraft exercises it with sun/moon
  `LightDescriptor.Distant` records and the equipped spotlight helmet's `LightDescriptor.Spot`.
  `MaterialSource.submitMaterials` is likewise load-bearing: Minecraft defines the textureless
  `caustica:cloud` material and adapts resource-pack rules, including the end portal's registered
  procedural surface.
- **The render pass API is real, raw-Vulkan, and three built-in passes run through it, including a
  graphics one.** `CausticaRenderPass` gives a pass a `VkCommandBuffer` and lets it build its own
  descriptor sets and pipelines directly against `GpuContext` — see §4. `BloomPass`, `SkyLutPass`, and
  `WorldOverlayPass` (all `builtin/`) are ordinary passes registered by `caustica:builtin`, not privileged
  engine code; `RtBloomPipeline` and `RtSkyLut` (the
  pre-pass-API built-ins) are deleted. `RenderPassManager` sequences passes by stage plus a same-stage
  `after()` topological order, and isolates a failing pass (disables it, logs, keeps the frame loop
  running) the same way `ProviderManager` isolates a failing provider. `SkyLutPass` moved out of `rt/`
  deliberately, as a test of the register mechanism: it now lives beside a hypothetical third-party pass
  (`builtin/`, sibling to `api/`/`rt/`/`client/`), reaches the engine only through `GpuContext`,
  `GpuImage`, the now-public `ComputeDispatch`/`PassShaderCompiler` pass-authoring helpers, and
  `RtLookPackage.current()` for config — and gathers its own per-frame sky state directly from Minecraft
  instead of reading a shared snapshot the engine publishes. Minecraft's separate light adapter derives
  sun and moon from the same host state and submits them as ordinary distant lights; the engine contains
  no fixed celestial-light record. `ComputeDispatch`/`PassShaderCompiler` had to be promoted from
  package-private to public for this move to compile at all — real friction the exercise was meant to
  surface, not a decision made ahead of a consumer; they now live in `api/pass/` alongside
  `CausticaRenderPass`/`PassSetup`/`PassFrame` rather than `rt/pass/`, since that friction was really "this
  is public API in an engine-internal package," not just a visibility modifier. The follow-up round finished the job: `SkyLutPass`
  now declares and binds its own sky-view/transmittance samplers entirely (§4 point 3), and
  `indirect_core.slang`'s NEE no longer reads them at all — `dominantCelestialLight` and its
  `transmittanceLut` import are gone. Direct sun/moon lighting now arrives through the same distant-light
  provider path any extension uses rather than through a fixed celestial special case.
- **`WorldOverlayPass` proved the pass API is graphics-capable, not just compute.** The block-outline/
  glow-outline/name-tag world-space overlays (`rt/overlay/`, three raster features drawing into one
  shared, mod-owned buffer that's then composited into the UI target) moved to `builtin/overlay/` and
  became one `CausticaRenderPass` under `RenderStage.OVERLAY` — the first pass to use `vkCmdDraw`/dynamic
  rendering rather than `ComputeDispatch`. `RenderStage.OVERLAY` existed in the enum from the start but was
  dead code (nothing ever called `RenderPassManager.record(OVERLAY, ...)`) until this pass registered.
  It stays a single coordinating pass wrapping the three feature objects rather than three separately
  registered passes — the shared buffer's clear-once/composite-once choreography (and the
  premultiplied-alpha trick that depends on more than one feature drawing into it) has no equivalent in the
  pass API's per-pass model, and inventing one would be design-ahead-of-a-second-consumer, the exact
  mistake §8's closing line warns against. It records on its own late transient command buffer from
  `GameRendererMixin`'s post-upscale seam (`RtComposite.recordOverlayPasses()`), not inside the main
  `RtComposite.composite()` recording — overlays must render after the world is upscaled, which happens
  after that method has already returned.
- **`rt/pipeline`'s compute pipelines were evaluated and left alone — not a gap, a finding.** `RtPipeline`
  is the engine-owned ray-tracing pipeline the pass stages themselves bracket, not a pass. `RtDlssRr`
  produces the image `PassFrame.sceneColor()` starts from; `RtDlssFg` drives extra swapchain presents outside any single
  command buffer. `RtHdrCompositePipeline`/`RtSdrPresentPipeline` record on present-time transient command
  buffers with their own acquire/present semaphores. `RtExposure` is a cross-frame state machine whose
  `beginFrame()` must run before the trace, which no pass stage precedes. `RtDisplayPipeline`/
  `RtDebugPresentPipeline` are shape-compatible with `ComputeDispatch` but are fixed built-ins wired to
  non-extensible inputs (raw guide buffers `PassFrame` never exposes) — porting them would add API surface
  with no real third-party consumer to justify it. None of this is being ported.
- **`OptionValues` has a real backing store, and bloom/sky are its first tenants.** `CausticaOptions`
  (top-level package, next to `CausticaConfig`) is TOML-backed (`config/caustica-options.toml`, separate
  from `CausticaConfig`'s `caustica.toml` — a fixed hand-curated schema vs. whatever extensions happen to
  have declared), keyed `<featureId>.<optionId>` so one extension's options can't collide with another's,
  with the same system-property-then-file-then-default precedence `CausticaConfig` uses. It is **not** a
  render-pass concern and does not live under `rt/pass/`: it holds every registered `Feature`'s options
  including features with no render pass at all, and it is what a feature enable/disable toggle will have
  to consult *before* the engine decides which passes, providers and Slang modules to instantiate. So it
  is loaded once from `CausticaApi.initialize()` at mod init and handed to `RenderPassManager.create`,
  rather than being constructed by it — its lifetime is the process's, not the Vulkan device's, which is
  also what lets a settings screen opened from the title menu read the same values the renderer will.
  `caustica:builtin` moved its `bloom.*`/`sky.*` numbers out of `look.json` (schema 4 → 5) into declared
  `Option.range(...)` values — neither is a colour-science calibration that has to move in lock step with
  the LMT the way exposure/lighting still do, so they no longer belong in that versioned package.
- **Options are read through the declared `Option<T>` token, not a string id.** `OptionValues.get` takes
  the very constant the feature registered (`BloomPass.LEVELS`, `SkyLutPass.GROUND_ALBEDO`, …), declared
  as a `public static final` on the pass that owns it and registered via `FeatureBuilder#options(List)`.
  That makes each id, kind, range and default exist exactly once: there is no `fallback` parameter to
  restate a default that could drift from the declaration, the `T` is checked at compile time rather than
  cast blind, a typo can't compile, and the lookup is a map hit instead of the per-read scan over the
  feature's option list the string API needed. Reading a token the owning feature never declared — or a
  lookalike sharing an id but not the declaration — throws. The store's value map is immutable and swapped
  wholesale on write, so `PassFrame`'s "frozen for the whole frame" guarantee is a reference read at
  `beginFrame` rather than a per-frame defensive copy of every option.
- **The sky is entirely the sky pass's now, and `WorldPush` lost 112 bytes of it.** `WorldPush` used to
  carry seven `float4`s of sky state (`celestial`, `skyLook0`–`3`, `sunUv`, `moonUv`) that `RtComposite`
  filled from its own celestial derivation — a second, independent copy of what `SkyLutPass` already
  computed for its LUT bake, which the two were explicitly allowed to disagree about by a frame. All of
  it moved into `SkyInputs` (`sky.slang`), which `SkyLutPass` fills once and publishes two ways: as the
  push constant for its own bakes and as a set-2 uniform buffer (`skyInputs`) the sky slot reads. That
  makes `publishWorldResource(String, GpuBuffer)` load-bearing, and
  it deleted `rt/SkyFrame`, `RtComposite.skyPush()`/`SkyPush`/`CelestialUv`/`celestialUv()`/the UV cache,
  and `RenderPassManager#optionsForFeature` with both its callers. Bloom strength stopped reaching the
  display pipeline as a number at all: bloom composites itself onto the post chain (§4) with its own
  `BloomPush.compositeStrength`, carrying the `1/levelCount` normalisation the summed bands need, so
  `DisplayPush.bloomStrength` and `PassFrame#publishScalar` — an API whose only caller was bloom — were
  both deleted. `RtComposite` no longer imports anything from `builtin`, and `BuiltinExtension.ID` is
  package-private again.
- **The celestials atlas stopped being an engine descriptor.** `bindings.slang`'s fixed `celestialsAtlas`
  (set 0, binding 9) and `RtPipeline`'s `setSkyAtlas`/`hasSkyAtlas` existed only so the Overworld sky could
  draw vanilla's sun and moon sprites; a sky for another dimension binds no such thing. It is now one of
  `SkyLutPass`'s own set-2 bindings, republished through a `PassFrame#publishWorldResource` overload — a
  pass wrapping a host resource whose handle a resource reload replaces has no create/resize call to
  publish the new one from. Set 0's binding 9 stays a hole rather than being renumbered: it is the
  engine's stable ABI.
- **Shader tree: `api/` + `world/` are the engine, `builtin/` is the extension, and nothing crosses
  backwards.** `world/*` imports only `world/*` and `api/*`; `builtin/*` imports only `caustica_*` — its
  own modules plus the public API. The Overworld atmosphere model, its three LUT bakes, the sky slot, and
  its bindings all live under `builtin/sky/` (they were in the engine's `world/sky/`, which made the
  engine tree own an extension's physics); bloom moved from `passes/` to `builtin/bloom/`; the builtin
  surface slot got its own subdirectory. Three symbols had to move to make the boundary
  real: `celestialSquareFrame` (pure geometry `math.slang`'s NEE square sampling needs), `CelestialLight`
  (engine NEE state), and the `bt709ToAcesCg`/`srgbToLinear` colour primitives — the last into a new
  `api/caustica_color.slang`, since the colour space a slot must return is part of the slot contract.
- **Every module name equals its file name.** Modules that relied on Slang's implicit file-name module now
  declare `module x;` explicitly, and `.slangdconfig` plus `.vscode/settings.json` give the language
  server the same search paths the compiler uses, so `import foo` resolves in the editor. Those search
  paths duplicate what `WorldShaderCompiler`'s `WORLD_MODULES`/`API_MODULES` and each feature's
  `ShaderSource` roots express at runtime; they have to be kept in sync by hand.
- **The `caustica:medium` slot is gone; two slots remain.** `IMediumModel`, `BuiltinMedium`,
  `MediumInput`/`MediumProperties`/`Phase*`, the `MEDIUM_*` tags, `Slots.MEDIUM` and
  `IComposition.Medium` are deleted. No engine stage ever called `createMedium` — what actually runs is
  `world/medium.slang`'s engine-owned `MediumStack` and Beer-Lambert extinction, which shares nothing
  with the slot but the word "medium". It was reachable only by a third party implementing it and
  watching it do nothing. Volume scattering through a slot returns when there is a volumetric integrator
  to call it; see `EXTENSION_API.md` §4.4.
- **`FrameContext` is gone.** It had already shrunk to `rain`/`thunder` once the celestial fields turned
  out to have no readers; those two never had a producer either — no weather state reaches the world push
  — so it was a struct of two always-zero floats threaded through `SurfaceInput`, `EnvironmentQuery`,
  `MediumInput`, a `world/frame.slang` module, and a parameter on `tracePath`, the hottest function in the
  renderer. `caustica_builtin_medium`'s `(1.0 + 4.0 * weather)` scattering term was the only read, and it
  always evaluated to `1.0`. When weather does get a producer, the honest shape is a field on whichever
  input struct needs it, added then.
- **Provider proofs are real consumers.** Generated rounded-corner block clouds exercise ordinary
  retained triangle geometry, stable
  `MaterialHandle` resolution and textureless OpenPBR definitions; the end portal exercises registered
  procedural surface selection and independent emission color; the spotlight helmet exercises a dynamic
  spot descriptor that appears and disappears with equipment state; sun and moon exercise distant lights.
  All four enter through Minecraft adapters, while the renderer sees only host-neutral submissions.
- **Layering is enforced incrementally, not by separate jars.** Public contracts live under `api`, new
  renderer-owned frame/scene/light code under `engine`, and host adapters under `minecraft`; import
  firewalls protect those boundaries. Historical `rt/*` still contains both generic renderer machinery
  and optimized Minecraft terrain/entity/material code. That remaining package extraction is honest debt,
  while a physical artifact split remains deferred until a second host client justifies it.
- **The former "engine/0.1" sketch is deleted** — a parallel implementation that nothing called.

## 8. Sequencing

Ordered by what is unproven, cheapest verification first.

1. **Render pass API, extracted from bloom.** Done, on the raw-Vulkan shape in §4 rather than the
   declarative one this step originally proposed — see §4's opening for why that changed. Bloom was still
   the right first case: a leaf with nothing downstream but display mapping, so the extraction cost was
   isolated to one pass.
2. **Sky LUT against the same API.** Done, and later revisited: proved the engine-consumed case first via
   a fixed `EngineImage` slot, then — once `SkyLutPass` moved out of `rt/` into `builtin/` to exercise the
   API as a genuine third-party pass would — that slot was replaced by the reflection-driven
   `publishWorldResource`/set-2 mechanism §4 describes now. `EngineImage` is deleted.
3. **Provider interfaces, extracted from `core_minecraft`.** Done for the public contribution paths:
   retained geometry, physical lights, named material definitions and ordered material rules are consumed
   transactionally with per-provider failure isolation. The remaining work is performance convergence of
   Minecraft's optimized terrain/entity/section-light adapters, not missing API semantics.
4. **A second scene provider (Distant Horizons).** The first genuine test that the abstraction is not
   just Minecraft in a trench coat — and the trigger for the physical jar split.
5. **Light provider proof: a handheld spotlight.** Done as the `caustica:spotlight_helmet` item. Minecraft
   submits a generic spot descriptor; neither the engine nor the light API knows about players or items.

Do not design any of these ahead of its extraction. The JSON pass graph was invented ahead of a consumer
and never met one; that failure mode is the reason for this ordering.

### 8.1 Three tracks, and where they meet

This ordering covers the **register** mechanism only. Two other orderings run alongside it:
`EXTENSION_API.md` §10 for the **substitute** mechanism, and `LIGHT_SYSTEM_PLAN.md` §7 for the light
system. All three are largely parallel; the couplings that are easy to miss:

- **Finite and distant provider lights are independent of Minecraft sections.** The optimized section
  light grid remains an adapter, while rectangle/point/spot descriptors use generic finite-light records
  and distant descriptors use direction, RGB illuminance and angular radius. This is why the helmet and
  celestials no longer need a Minecraft-shaped coordinate.
- **The presample pass is a design input to step 1, though it lands after it.** Its shape — every frame,
  fixed dispatch, engine-consumed output feeding RIS, needs validation and fallback — is squarely what
  the pass API must support, and it is a better third consumer than anything else listed. Bloom is a leaf
  with no engine consumer; the sky LUT proves the engine-consumed case at small scale; the presample pass
  proves it at frame-critical scale.
- **The substitute track meets this one at `EXTENSION_API.md` §10 step 4**, when `caustica:builtin` and a
  bloom pass both become features on the same builder. The slot rename and the composition-root spike do
  not wait on the pass API, and the pass API does not wait on the registry.
- **The light track waits on neither.** L1 touches no slot interface and no registry. Its one overlap is
  opportunistic: L1c rewrites `risInitial` anyway, which is the cheap moment to converge RIS's target
  function with the engine's own `evaluateBsdf` and retire the convergence debt in `EXTENSION_API.md` §11.

## 9. Open questions

1. Bindless or descriptor indirection for extension-declared, engine-visible resources — the constraint in
   §4 that currently blocks volumetric clouds. What does it cost?
2. Slang module substitution: version it, restrict it to declared-leaf modules, or leave it undocumented?
3. ~~Conflict policy for non-composing mechanisms.~~ Answered: slots are user-selected radio groups
   defaulting to the built-in binding (`EXTENSION_API.md` §8), so two candidates are a choice, not a
   conflict.
4. ~~Do look packs stay Slang-source-only once extensions can ship Java?~~ Answered by removing the
   question: there is no look pack in v1, and extensions ship Java plus Slang source.
5. Persistent on-disk shader cache. In-memory and per-run today; ~1.8 s of cold compilation for the world
   pipeline makes this an iteration-speed problem before a viability one.
6. Where does the frame graph land relative to this? `RtComposite` still owns the pass sequence directly,
   and the pass API in §4 is a graph in embryo.
7. What is the API stability promise across Minecraft versions, and does core version separately from
   `core_minecraft`?
