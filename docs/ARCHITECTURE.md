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

Extracting bloom and the sky LUT against a hypothetical pass API surfaced three requirements that an
abstract design would have missed:

1. **Recording must be imperative, not a declared pass list.** Bloom is one logical pass but `2N-1`
   dispatches whose count depends on runtime resolution. Declaratively that needs a loop construct in a
   schema; in Java it is a `for` loop. This single case is why the JSON graph was the wrong shape.
2. **"Runs once" is a first-class lifecycle.** The transmittance and multiscatter LUTs bake on the first
   frame and never again. The engine should own that bookkeeping — and therefore own invalidation on
   resize, dimension change, and epoch switch — instead of every extension carrying a `baked` flag.
3. **Engine-consumed resources must be fixed slots.** `bindings.slang` declares
   `[[vk::binding(10,0)]] Sampler2D skyViewLut`. For an extension to *supply* the sky LUT it has to land
   in that exact binding. There is no mechanism for an extension to invent a new engine-visible resource,
   because the engine's shaders have no binding for it.

The resulting rule for v1:

> An extension may create resources freely for its **own** passes to read and write; it may **fill** an
> engine-declared named slot; it may **not** invent new engine-visible bindings.

This directly bounds what is possible today. Clouds as *geometry* work — that is a scene provider
feeding the TLAS. Clouds as a *volume the engine's integrator samples* do not, until either a new engine
binding exists or the descriptor model goes bindless. Worth knowing before designing around it.

Proposed shape, kept minimal on purpose:

```java
public interface CausticaRenderPass {
    ResourceLocation id();                       // diagnostics, config, conflict reporting
    RenderStage stage();                         // ENVIRONMENT_PREPARE | BEFORE_TRACE | AFTER_RECONSTRUCTION | LOOK
    void declareResources(ResourceRegistry r);   // engine allocates, resizes, retires
    void record(PassContext ctx);                // engine owns the command buffer
}
```

Two decisions taken now rather than left open:

- **Barriers between dispatches are automatic.** Bloom already inserts one after every step, and an
  extension that can *omit* a barrier can corrupt the frame. Cheap correctness; add an opt-out only if
  profiling demands it.
- **Extensions name a size *policy*, never pixels.** `Size.displayRelative(2)`, `Size.fixed(256, 64)`. The
  engine owns allocation, resize, aliasing, and retirement.

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

- **Command recording, barriers, queues, submission, resource lifetime.** Extensions never see a
  `VkCommandBuffer`, `VkDescriptorSet`, or allocation.
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

- **Runtime Slang compilation is real and load-bearing.** `RayPackShaderCompiler` extracts world-pipeline
  and appearance sources and drives the pinned compiler via the `causticaslang` shim. It compiles engine
  entry points specialized with an appearance type (`compileSpecialized`) and ordinary stages
  (`compilePlain`). Under this architecture it is promoted from pack infrastructure to *the* mechanism by
  which any extension ships Slang.
- **Substituted appearance code runs in real frames.** `pack_sky_miss.slang` renders behind
  `caustica.rt.packSky` — confirmed on GPU. `pack_closest_hit`/`pack_indirect` compile and link against
  the built-in implementation but have not been traced in a frame.
- **The whole world pipeline can compile at runtime** behind `caustica.rt.dynamicWorldShaders`, content
  unchanged, with per-stage fallback to build-time SPIR-V.
- **Nothing has been layered yet.** `core` / `core_minecraft` / extensions is a target; the package tree
  is still flat. No provider interface exists. No pass API exists. No registry exists — slot selection is
  still two booleans in `CausticaConfig`.
- **The pack vocabulary is still in the tree.** `rt/pack/`, `RayPack*`, `IRayPack`, `pack_*.slang`, and
  `resources/caustica/raypacks/` all predate this framing; `EXTENSION_API.md` §10 maps the rename.
- **The former "engine/0.1" sketch is deleted** — a parallel implementation that nothing called.

## 8. Sequencing

Ordered by what is unproven, cheapest verification first.

1. **Render pass API, extracted from bloom.** The only one of the three mechanisms that is entirely
   unproven. Bloom is a leaf — nothing downstream but display mapping — and has a pixel-parity target.
   Success criterion: the extension touches no `VkDescriptorSet`, `VkImage`, or barrier.
2. **Sky LUT against the same API.** Proves the fixed-slot mechanism and the engine-consumed case, where
   an extension's output feeds `indirect.rgen`'s `celestialLight`. Needs the validation and fallback story
   that bloom does not.
3. **Provider interfaces, extracted from `core_minecraft`.** Make Minecraft's terrain, entity, light, and
   material paths go through `SceneProvider` / `LightProvider` / `MaterialSource` without moving files.
4. **A second scene provider (Distant Horizons).** The first genuine test that the abstraction is not
   just Minecraft in a trench coat — and the trigger for the physical jar split.
5. **Light provider proof: a handheld spotlight.** The case Iris structurally cannot do; the clearest
   demonstration of why this architecture exists.

Do not design any of these ahead of its extraction. The JSON pass graph was invented ahead of a consumer
and never met one; that failure mode is the reason for this ordering.

This ordering covers the **register** mechanism. The **substitute** mechanism has its own ordering in
`EXTENSION_API.md` §10, and the two are independent — the slot rename and the composition-root spike do
not wait on the pass API, and the pass API does not wait on the registry. They meet at step 4 there, when
`caustica:builtin` and a bloom pass both become features on the same builder.

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
