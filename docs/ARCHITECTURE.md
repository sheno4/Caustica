# Caustica Architecture

Status: design. Supersedes the framing in the former `RAY_PACK_ARCHITECTURE.md`, now rewritten as
`EXTENSION_API.md`: this document owns the layering, the three mechanisms, and the sequencing; that one
owns the concrete API they are made of.
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

- Vulkan context, device bring-up, GPU executor, queue and submission policy (`RtContext`,
  `RtGpuExecutor`, `RtDeviceBringup`)
- Acceleration structures: BLAS/TLAS lifetime, compaction, OMM, instance assembly (`RtAccel`)
- The **light system**: GPU light records, grid/hierarchy, sampling structures, NEE and RIS
- The **material system**: canonical texture pages, material IDs, surface attribute decode
  (`RtMaterialRegistry`)
- Participating media and the medium stack
- Path integration, transport, and the estimator contract
- Reconstruction/denoise integration (`RtDlssRr`, NRD when it lands), frame generation (`RtDlssFg`)
- Screen transform: working space, exposure, tone LUTs, ACES SDR/HDR output, presentation
  (`RtExposure`, `RtToneLut`, `RtDisplayPipeline`, `RtHdr`)
- Pipeline and descriptor management, SBT layout (`RtPipeline`)
- Runtime Slang compilation and the shader cache (`SlangRuntime`, `RayPackShaderCompiler`)
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

Note two existing seams that already sit exactly on this line, currently on the wrong side of it:
`rt/terrain/RtLight*` mixes *collecting lights from Minecraft blocks* with *the GPU light structures*,
and `RtBlockMaterials` vs `RtMaterialRegistry` splits MC sprite knowledge from canonical GPU pages. Those
are the first two boundaries to make explicit.

For the light seam the problem is the *unit*, not the package: Minecraft's 16³ chunk section is the light
system's spatial primitive and is baked into the GPU light record, so a provider-supplied light has no
representable position. `LIGHT_SYSTEM_PLAN.md` §3.1 has the file-by-file breakdown.

### 2.3 Extensions — everything else

Ordinary Fabric mods (or, initially, internal packages) that depend on core and register into it.
Examples, and which mechanism each uses:

| Extension | Mechanism |
|---|---|
| Spotlight / handheld light item | register a light provider + game-state hook |
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

- `SceneProvider` — contribute geometry and instances to the TLAS (clouds, DH, custom entities)
- `LightProvider` — contribute records to the light database, so they participate in NEE/RIS with real
  shadows rather than being a shader-side fake
- `RenderPass` — contribute compute work at a defined frame stage (§4)
- `MaterialSource` — contribute material data
- `EnvironmentField` — contribute world-derived data for shaders to sample

Every provider call must be failure-isolated: an exception disables that provider with a clear log, and
never kills the frame loop. Same discipline as the existing per-stage shader fallback.

### 3.2 Substitute code — two forms, very different stability

These look alike and are not:

- **Slot binding.** The engine declares `IMediumModel.perturbNormal(...)`; a feature implements it and
  binds the slot. Checked, narrow, versioned. Engine changes produce a compile error naming the method.
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
`RtContext.get()` regardless of what the declared API offers. It was replaced with the shape below, which
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
   - `PassSetup.publishOutput(name, image, levelCount)` — for a pass's output a *different* pipeline reads
     directly as a plain image view (bloom's base level, read by the display-mapping compute pipeline).
     No Slang involved; just a named `RenderPassManager` registry, single-publisher-validated the same way.

   The engine's own two inputs (`RECONSTRUCTED_COLOR`, `EXPOSURE`) went the other way, since those are
   genuinely engine-owned per §6: `PassFrame.reconstructedColor()`/`exposureImage()`, plain read accessors,
   no registry.

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
    Identifier id();
    RenderStage stage();
    default List<Identifier> after() { return List.of(); }   // ordering within a stage
    default void create(PassSetup setup) {}                  // pass allocates its own GPU resources
    default void resize(PassSetup setup, int w, int h) {}
    void record(PassFrame frame);                             // raw VkCommandBuffer, mid-recording
    default void invalidate() {}                              // redo persistent bake state
    default void destroy() {}
}
```

`PassSetup` exposes `RtContext` (device, allocator, `createStorageImage`/`createBuffer`, debug labelling)
plus `publishWorldResource`/`publishOutput` (see above). `PassFrame` exposes the command buffer, the
display extent, `reconstructedColor()`/`exposureImage()`, a `PassOptions` snapshot (declared now, unread
until the config-storage work lands — see §7),
and one `memoryBarrier()` helper matching the broad full-pipeline-barrier idiom used everywhere else in
`rt/RtComposite.java`. There is no `ComputeProgram`, `ImageRef`, or `DispatchImage` — a pass builds its own
descriptor sets and pipeline against `RtContext` directly, the same way `RtExposurePipeline` and
`RtOverlayPipelines` already did before any pass API existed. `GpuImage`/`GpuBuffer` (renamed from
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
  calls `RtContext.createStorageImage` with whatever dimensions it wants. There was no real allocation,
  resize, or retirement policy for the engine to own here beyond what `RtContext` already provides to
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
  extension would use (`api/BuiltinExtension.java`), which is the dogfooding check §8 step 3 originally
  asked for landing later — it landed with the registry instead. Slot selection compiles a generated
  composition root (`rt/shader/Composition`, `CompositionManager`) rather than reading two config
  booleans. What's still thin: `registry.select()` has no caller — one feature binds each slot and nothing
  lets a user choose another yet — and `SceneProvider`/`MaterialSource` are lifecycle callbacks only
  (`update`/`prepareFrame`/`onResourceReload`), with no method through which a provider actually
  contributes geometry or a material; Minecraft's terrain/entity paths still run through direct calls in
  `RtComposite` alongside the provider shim rather than through it. `LightProvider` now also has
  `submitLights(LightSink)`, exercised by `SkyLutPass` (below) — but it is a placeholder shape with no
  consumer, not a working contribution path; see `LightSink`'s javadoc.
- **The render pass API is real, raw-Vulkan, and two built-in passes run through it.** `CausticaRenderPass`
  gives a pass a `VkCommandBuffer` and lets it build its own descriptor sets and pipelines directly against
  `RtContext` — see §4. `BloomPass` (`rt/pass/`) and `SkyLutPass` (`builtin/`) are ordinary passes
  registered by `caustica:builtin`, not privileged engine code; `RtBloomPipeline` and `RtSkyLut` (the
  pre-pass-API built-ins) are deleted. `RenderPassManager` sequences passes by stage plus a same-stage
  `after()` topological order, and isolates a failing pass (disables it, logs, keeps the frame loop
  running) the same way `ProviderManager` isolates a failing provider. `SkyLutPass` moved out of `rt/`
  deliberately, as a test of the register mechanism: it now lives beside a hypothetical third-party pass
  (`builtin/`, sibling to `api/`/`rt/`/`client/`), reaches the engine only through `RtContext`,
  `GpuImage`, the now-public `ComputeDispatch`/`PassShaderCompiler` pass-authoring helpers, and
  `RtLookPackage.current()` for config — and gathers its own per-frame sky state directly from Minecraft
  instead of reading a shared snapshot the engine publishes (`rt/SkyFrame`'s NEE-facing copy and this
  pass's copy are now allowed to drift). It also registers as a `LightProvider` and submits the sun/moon
  through the new (fake) `submitLights`/`LightSink` path — see above and `docs/LIGHT_SYSTEM_PLAN.md` §7.2
  for why it can't be a real one yet. `ComputeDispatch`/`PassShaderCompiler` had to be promoted from
  package-private to public for this move to compile at all — real friction the exercise was meant to
  surface, not a decision made ahead of a consumer; they now live in `api/pass/` alongside
  `CausticaRenderPass`/`PassSetup`/`PassFrame` rather than `rt/pass/`, since that friction was really "this
  is public API in an engine-internal package," not just a visibility modifier. The follow-up round finished the job: `SkyLutPass`
  now declares and binds its own sky-view/transmittance samplers entirely (§4 point 3), and
  `indirect_core.slang`'s NEE no longer reads them at all — `dominantCelestialLight` and its
  `transmittanceLut` import are gone, `celestialLight` sits at its zero-illuminance default, and direct
  sun/moon lighting is genuinely absent (a deliberate, accepted regression) until `LIGHT_SYSTEM_PLAN.md`
  L2 gives `submitLights`/`LightSink` a real consumer.
- **Physical layering hasn't happened.** `core` / `core_minecraft` / extensions is still a target; the
  package tree is flat (`rt/...`, not `engine/...` + `mc/...`). §2.4's plan — interfaces now, jars later —
  is why this is expected at this stage rather than a gap.
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
3. **Provider interfaces, extracted from `core_minecraft`.** Partly done — the registry, the three provider
   interfaces, and failure-isolated dispatch (`ProviderManager`) all exist and `caustica:builtin` registers
   through them. Not done: the interfaces are lifecycle callbacks only, with no method through which a
   provider contributes geometry, a light, or a material, so Minecraft's terrain/entity/light paths still
   run beside the provider shim rather than through it. That's the remaining work in this step.
4. **A second scene provider (Distant Horizons).** The first genuine test that the abstraction is not
   just Minecraft in a trench coat — and the trigger for the physical jar split.
5. **Light provider proof: a handheld spotlight.** The case Iris structurally cannot do; the clearest
   demonstration of why this architecture exists. **Blocked on light-system work** — see below.

Do not design any of these ahead of its extraction. The JSON pass graph was invented ahead of a consumer
and never met one; that failure mode is the reason for this ordering.

### 8.1 Three tracks, and where they meet

This ordering covers the **register** mechanism only. Two other orderings run alongside it:
`EXTENSION_API.md` §10 for the **substitute** mechanism, and `LIGHT_SYSTEM_PLAN.md` §7 for the light
system. All three are largely parallel; the couplings that are easy to miss:

- **Step 5 is not a provider-interface exercise.** A handheld light is a *dynamic* light, and the light
  system structurally cannot accept one: the generation is a 50 ms-throttled immutable snapshot, and
  every light record carries a Minecraft section coordinate that a spotlight does not have. Step 5's real
  prerequisites are `LIGHT_SYSTEM_PLAN.md` L1 and L2 plus step 3 here. L2 and step 5 are the same work
  approached from two directions and should be planned as one.
- **The presample pass is a design input to step 1, though it lands after it.** Its shape — every frame,
  fixed dispatch, engine-consumed output feeding RIS, needs validation and fallback — is squarely what
  the pass API must support, and it is a better third consumer than anything else listed. Bloom is a leaf
  with no engine consumer; the sky LUT proves the engine-consumed case at small scale; the presample pass
  proves it at frame-critical scale.
- **The substitute track meets this one at `EXTENSION_API.md` §10 step 4**, when `caustica:builtin` and a
  bloom pass both become features on the same builder. The slot rename and the composition-root spike do
  not wait on the pass API, and the pass API does not wait on the registry.
- **The light track waits on neither.** L1 touches no slot interface and no registry. Its one overlap is
  opportunistic: L1c rewrites `risInitial` anyway, which is the cheap moment to route RIS's target
  function through the surface slot's `evaluateBsdf` and retire the convergence debt in
  `EXTENSION_API.md` §11 — but if the slot split has not landed, L1c keeps the inline target function and
  the debt stays put.

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
