# Caustica Extension API

Status: design, reconciling the implementation slice on the `pack` branch against `ARCHITECTURE.md`.
Scope: the concrete contract between the engine and the code that extends it — Slang interfaces, the
composition model, compilation, and lifecycle.

`ARCHITECTURE.md` owns the layering (`core` / `core_minecraft` / extensions), the three mechanisms, and
the sequencing. This document owns the API those mechanisms are made of. Anything that appears in both
belongs there, not here; the previous revision carried a second roadmap and a second decisions list, and
they had already drifted apart.

## 0. What changed from the ray-pack revision

The previous revision modelled extension as **one installable archive that wins everything**: a `.crp`
zip, a `pack.json`, a discovery pass, and a single `IRayPack` type that the engine specialized every
entry point with. Three of that model's load-bearing assumptions are now rejected.

**Extensions ship Java.** The forks this design exists to absorb — DH, NRD, SHARC, LOD, BLAS slabs,
frame generation, dynamic lights — are Java. An archive that may only contain Slang cannot express any of
them. So the artifact is an ordinary Fabric mod, and the manifest becomes a typed builder call. Nothing
is lost: everything `pack.json` declared is declared in Java instead, where it is compile-checked, and
where the same declaration doubles as the settings UI schema (§8).

**Composition is per-slot, not per-artifact.** The target is a user enabling a Nether sky from one mod
and clouds from another. `IRayPack : IPackSurfaceModel, IPackEnvironmentModel, IPackMediumModel` cannot
express that: one type conforms to all three, so one author wins all three. This is not only a future
limitation — it bites today. A pack that wants to change only the sky must still supply a surface model
and a medium model, and Slang has no implementation inheritance for structs, so "only the sky" means
copying the bundled default's surface and medium source. The composed root type is deleted and replaced
by named slots (§2).

**No archive format, no look pack, for now.** The archive question is deferred until a third-party
ecosystem exists to answer it. The look/LUT pack is deferred deliberately — it is easy and it is a
*second* API, and one API should be right before there are two. `RtLookPackage` stays an engine-internal
versioned constant set; it is not an extension point in this revision.

Kept unchanged, because they were the right calls and survived the reframing: the engine/extension
ownership split, the BSDF-versus-look separation, engine-derived double-count gates, engine-owned
dielectric interfaces, static specialization with no runtime dispatch, and validate-then-swap activation.

## 1. Vocabulary

The word "pack" is retired. Four terms, each mapping to exactly one mechanism:

| Term | What it is | Mechanism | Composes |
|---|---|---|---|
| **Extension** | A Fabric mod (or built-in package) that calls the API. One entry point. | — | — |
| **Feature** | One named, user-visible, individually toggleable unit an extension contributes. `caustica:nether_sky`. The unit the settings UI lists. | — | many |
| **Slot** | An engine-declared substitution point with exactly one winner and a Slang interface. `caustica:sky`. | substitute | one wins |
| **Provider** | An additive registration the engine calls back into. `LightProvider`, `SceneProvider`, `RenderPass`. | register | many |

A feature is the container; slots and providers are what it binds. One feature may bind a slot, add a
provider, and declare settings — and the user toggles all of it with one switch. That grouping is why
"feature" exists as a concept separate from the registration calls: without it, enabling a Nether sky
would mean the user ticking three unrelated boxes.

"Module" is deliberately not used for any of these. It means a Slang module throughout this codebase, and
overloading it onto extension units is how the previous revision ended up with a `slang.module` field
inside a thing also called a module.

## 2. The composition model

### 2.1 Slots

The engine declares a fixed set of slots. Each has an id, a Slang interface, and a built-in default
binding. v1:

| Slot | Slang interface | Built-in default |
|---|---|---|
| `caustica:sky` | `ISkyModel` | `BuiltinSky` |
| `caustica:surface` | `ISurfaceModel` | `BuiltinSurface` |
| `caustica:medium` | `IMediumModel` | `BuiltinMedium` |

Adding a slot is an API change, not something a feature can invent — same rule as render-pass hooks in
`ARCHITECTURE.md` §4. Three is the honest starting set: it is exactly what has been compiled and linked
against a real implementation. Water waves want a fourth (`caustica:interface`, a `perturbNormal` hook);
that slot lands with its first consumer, not before.

### 2.2 The generated composition root

A binding names a Slang module and a concrete type. At activation the engine emits one small Slang source
file — the **composition root** — and specializes every generic entry point with it:

```slang
// generated; never authored
import caustica_api;
import nethersky_sky;
import wavewater_medium;
import caustica_builtin;

export struct Composition : IComposition {
    public typealias Sky     = NetherSky;        // caustica:nether_sky
    public typealias Surface = BuiltinSurface;   // built-in default
    public typealias Medium  = WaveWaterMedium;  // wavewater:waves
}
```

against

```slang
public interface IComposition {
    associatedtype Sky     : ISkyModel;
    associatedtype Surface : ISurfaceModel;
    associatedtype Medium  : IMediumModel;
};
```

with entry points reading `void main<TC : IComposition>(...)` and instantiating `TC.Sky sky;` where they
today instantiate `TPack pack;`.

This is the whole mechanism, and its main virtue is what it does *not* require. The shim's
`SlangSession.compileSpecialized(engineModule, entryPoint, implModule, implType)` takes exactly one type
argument and stays unchanged. The compiler still specializes one entry point with one concrete type; that
type is now generated rather than authored. Mix-and-match costs one generated file.

Two things to verify before committing to it, in this order:

1. **Associated types through the shim.** Slang supports `associatedtype` with interface constraints, but
   this path has not been exercised through `causticaslang`. A one-file spike — an entry point generic
   over a two-slot `IComposition`, specialized with a generated root — settles it in an afternoon and
   should run before any of §10.
2. **Fallback if it does not hold:** generate a root that *implements* one flat interface by forwarding
   each method to the selected type (`float3 evaluateEnvironment(q) { NetherSky s; return
   s.evaluateEnvironment(q); }`). Purely mechanical codegen, no language feature beyond what already
   compiles today, and it inlines to the same code. Uglier to read in a capture; identical at runtime.

### 2.3 Why not one type parameter per slot

`main<TSurface : ISurfaceModel, TSky : ISkyModel, TMedium : IMediumModel>` also expresses the model and
needs no generated file — but it needs the shim to accept N type arguments, it puts the slot list into
every entry point signature so adding a slot edits every stage, and it gives the cache nothing to key on
but an ordered tuple. The composition root gives the cache one hash and the diagnostics one name.

### 2.4 The built-in default is an ordinary feature

`caustica:builtin` registers the default binding for every slot through the same API an external mod uses.
That keeps "no privileged built-ins" structural rather than a rule to police — the property the bundled
default pack was there to guarantee, preserved without the pack.

## 3. The Java API surface

One Fabric entry point:

```java
public interface CausticaExtension {
    void register(CausticaRegistry registry);
}
```

```java
public final class NetherSkyExtension implements CausticaExtension {
    @Override
    public void register(CausticaRegistry registry) {
        registry.feature(id("nethersky", "nether_sky"))
                .title(Component.translatable("feature.nethersky.nether_sky"))
                .category(FeatureCategory.SKY)
                .shaderSource(ShaderSource.classpath("/nethersky/shaders"))
                .bind(Slots.SKY, "nethersky_sky", "NetherSky")
                .option(Option.bool("ambient_glow", true).reload(Reload.LOOK))
                .option(Option.range("fog_density", 0.0f, 4.0f, 1.0f).reload(Reload.LOOK))
                .register();
    }
}
```

Everything the old `pack.json` carried appears here — id, version (the mod's), Slang module and type,
source root, settings schema — with three differences that matter: it is compile-checked, it can also
register Java providers, and it is per-feature rather than per-artifact.

`ShaderSource.classpath(...)` names a resource root inside the extension's own jar. The engine extracts it
alongside the engine sources into the compiler's search path, exactly as `RayPackShaderCompiler` already
extracts the bundled sources. An extension ships Slang source; it never ships SPIR-V, a descriptor layout,
or pipeline metadata. That rule is unchanged and is the one place the archive-era validation discipline
stays fully intact.

Provider registration (`SceneProvider`, `LightProvider`, `RenderPass`, `MaterialSource`) hangs off the
same feature builder and is specified in `ARCHITECTURE.md` §3.1; it is not restated here.

## 4. The Slang API surface

Renamed throughout: the `Pack` prefix is dropped, and the single API module is split by slot so a feature
imports only the interface it binds.

| Was | Is |
|---|---|
| `caustica_ray_pack_api` (one module) | `caustica_api` (shared types, `IComposition`) + `caustica_surface` / `caustica_sky` / `caustica_medium` |
| `IRayPack` | *deleted* — see §2.2 |
| `IPackSurfaceModel` | `ISurfaceModel` |
| `IPackEnvironmentModel` | `ISkyModel` |
| `IPackMediumModel` | `IMediumModel` |
| `PackSurfaceInput`, `PackSurfaceClosure`, `PackBsdfQuery`, … | `SurfaceInput`, `SurfaceClosure`, `BsdfQuery`, … |
| `PACK_EVENT_*`, `PACK_SURFACE_*`, `PACK_DIMENSION_*`, `PACK_MEDIUM_*` | `EVENT_*`, `SURFACE_*`, `DIMENSION_*`, `MEDIUM_*` |
| `PackFrameContext` / `packFrameContext` | `FrameContext` / `frameContext` |
| `pack_sky_miss.slang`, `pack_closest_hit.slang`, `pack_indirect.slang`, `pack_frame.slang` | `sky_miss.slang`, `closest_hit.slang`, `indirect.slang`, `frame.slang` |
| `default_pack.slang`, `caustica_default_*.slang` | `caustica_builtin.slang`, `caustica_builtin_*.slang` |

Dropping the `pack_` prefix on the entry points is safe: they carry no stage infix, so `closest_hit.slang`
and the build-time `closest_hit.rchit.slang` remain distinct filenames, distinct synthetic module names in
`compilePlain`, and `build.gradle`'s `**/*.<stage>.slang` glob still skips them. The "no stage infix =
reached only through import" convention is doing the work, not the prefix.

### 4.1 Surface

```slang
public interface ISurfaceModel {
    // Interpretation of the engine-decoded canonical material. All material policy lives here.
    public float evaluateCoverage(SurfaceInput input);
    public SurfaceClosure createSurface(SurfaceInput input);

    // Estimator contract. value and pdf must agree; pdf >= 0; both finite. Never called with a
    // transmissive closure — the engine routes those through its own dielectric interface.
    public BsdfEvaluation evaluateBsdf(BsdfQuery query);
    public BsdfSample sampleBsdf(BsdfSampleQuery query);   // weight, direction, EVENT_* flags

    // Look. Applied by the engine to an already-shaded contribution. Never used by the estimator.
    public float3 evaluateResponse(float3 radiance, SurfaceClosure surface);
};
```

Two things are deliberately separated, and this is the single most important invariant in the API:

- **The BSDF is the estimator's target function.** RIS candidate weighting, ReSTIR reuse, and MIS all
  depend on it. Value and pdf must agree, stay finite, stay non-negative, or every engine lighting backend
  is biased.
- **The response is the look.** Quantization, banding, palette snapping, cel thresholds — applied to an
  already-shaded contribution, never to the BSDF. Non-physical by design and unconstrained.

With the look pack cut, `evaluateResponse` is now the *only* look hook in the API. Exposure, bloom, LMT,
and photometric anchors are engine-owned in this revision. It stays because it costs nothing (it is
implemented and linked) and because it is the hook a stylized surface needs; it is not the seed of a
second look API.

`shadingNormal` and `demodulationAlbedo` (§6) are not separate query points. The engine reads
`SurfaceClosure.shadingNormal` / `.diffuseAlbedo` off the closure the feature already built. Splitting
them out is a compatible addition for whenever something needs to diverge.

### 4.2 The loop contract

The engine owns the bounce loop; the slot implementation fills the shading body. Some loop values look
like appearance and are correctness:

| Loop value | Owner | Why |
|---|---|---|
| `L`, throughput, pdf division | Engine | Energy bookkeeping |
| Russian roulette, bounce cap | Engine | Termination policy |
| Medium attenuation, medium stack | Engine | Beer–Lambert, push/pop correctness |
| Ray cone width and spread | Engine | Filtering and LOD consistency |
| **Celestial disc visibility** | Engine, derived from `EVENT_*` flags | A diffuse bounce already accounted for the sun via NEE; showing the disc again double-counts it |
| **Emitter direct-hit gating** | Engine, derived from RIS participation | An emitter sampled by RIS must not also be gathered on hit; an emitter *not* sampled must always gather, or its light is lost |
| Contribution at a vertex | Slot | Appearance |
| Continuation direction and weight | Slot | Appearance |
| Lobe classification (`eventFlags`) | Slot | Input to the two gates above |

Something that could set either gate directly could silently double-count or lose light, and the symptom
is "this looks brighter," not a visible failure — which is why the derivation is on the engine side, not
left as an invariant an author is asked to respect. `indirect.slang` derives the celestial gate from
`BsdfSample.eventFlags` today; the build-time `indirect.rgen.slang` fallback still computes it as
hand-tuned local logic that assumes its own BSDF branches.

### 4.3 Sky

```slang
public interface ISkyModel {
    public float3 evaluateEnvironment(EnvironmentQuery query);   // sky, background, celestial bodies
};
```

The sky is evaluated in the primary pass for background pixels. That is the one place slot code runs
during primary-hit processing, and it writes radiance only — never a guide.

**`getDirectionalLightCount` / `getDirectionalLight` move out of this interface.** They are additive —
"here are N celestial lights" — crammed into a substitution point where exactly one implementation wins.
Under §1 that is a `LightProvider`, which is also the only shape that lets a second sun cast real shadows
through NEE rather than being a shader-side fake. Removing them makes `ISkyModel` a single function, which
is the right size for the slot that the Nether-sky work will be the first real consumer of.

### 4.4 Medium and dielectric interfaces

**Dielectric interface behaviour is engine-owned in full**: Fresnel, reflection/refraction choice, total
internal reflection, medium push/pop, and the surface-bias regimes. The medium stack stays engine-side and
out of the closure's live register set.

```slang
public interface IMediumModel {
    public Medium createMedium(MediumInput input);          // scattering, absorption, ior, anisotropy
    public PhaseEvaluation evaluatePhase(PhaseQuery query);  // value, pdf — must agree, like the BSDF
    public PhaseSample samplePhase(PhaseSampleQuery query);
};
```

A full phase-function estimator, not just an anisotropy parameter: volume NEE needs eval/sample/pdf under
the same contract as the BSDF.

Consequence, accepted: refraction is not stylizable. The engine's transmission-chain walk selects the
guide branch and uses material IOR, so exaggerated or absent refraction would produce guides describing
different geometry than what was shaded. The escape is a `caustica:interface` slot with a `perturbNormal`
hook — added with its first consumer, with the "must match guide and shading" invariant enforced from day
one rather than retrofitted.

## 5. Materials

**Materials are content; slots are interpretation.** Definition belongs to Minecraft resource packs plus
the engine material compiler. An extension contributes no material defaults and no material file.

The engine owns sprite and texture discovery, LabPBR and other source-format decoding, resource-pack
priority and JSON merging, canonical texture pages and mips, stable material IDs within a resource epoch,
core surface attributes and sampling, alpha coverage for geometry and OMM, and material lifetime.

`createSurface` receives a canonical description: base color and opacity, shading and geometric normal,
linear roughness, metalness, IOR and transmission, emission, AO and subsurface hints, semantic tags
(water, particle, foliage, portal). Policy like "foliage gets more SSS" is expressed in `createSurface`
keyed on those tags, not in a data file — more expressive, and it removes the ambiguity of two systems
both describing a complete surface.

Per-block data the canonical attributes do not carry (a stylization group, a palette index) is an opaque
extension namespace in the *resource pack*: the engine defines the format and the merge, the feature
assigns meaning.

## 6. Render products

**No extension produces, defines, or reinterprets a canonical render product except scene radiance.** The
engine writes every product and owns every convention: working space, radiometric unit and pre-exposure
status, resolution, encoding and valid range, background convention, motion direction and coordinate
space, depth convention and invalid value, which surface event the value describes, and history-reset
semantics. Those conventions matter more than the formats — most reconstruction bugs are two components
agreeing on `RG16F motion` and disagreeing about sign or space.

Accepted inconsistency, unchanged: `demodulationAlbedo` is engine-derived as `albedo * (1 - metal)`. The
assumed invariant is that diffuse response is proportional to it. A palettized or heavily tinted diffuse
response violates that, residual texture stays in the demodulated irradiance, and DLSS-RR either
over-blurs it or smears it as ghost texture. It presents as a denoiser bug and is not one. The escape is
the query point in §4.1.

## 7. Compilation and specialization

The engine ships a pinned Slang compiler and owns every generated binary. Authors and users install
nothing. One coordinator, not scattered Slang calls:

1. reuse or create a session for the API/compiler configuration, with only approved search paths;
2. extract engine sources and every enabled feature's `ShaderSource` root into that search path;
3. generate the composition root (§2.2) from the active selection;
4. compile each required engine entry point, specialized with the composition type;
5. reflect and validate the resource layout against the engine pipeline contract;
6. emit and validate SPIR-V; create pipelines.

Compared with the archive-era version this loses steps that only existed to police an untrusted zip:
manifest schema validation, path normalization, identifier checks, and conformance lookup by string name.
Conformance is now checked by the Slang compiler at step 4, and misspelling a slot is a Java compile error
at registration. What survives intact: **appearance modules must contain no entry points and no global
resources**, and reflection is validated against the pipeline contract rather than accepted as permission
to bind.

The coordinator should report **per-variant register and scratch usage** and warn above a ceiling. Slot
code inlines into the integrator's hot loop; without this, occupancy regressions are invisible until they
surface as frame time. This measurement is still outstanding for the surface slice that already compiles.

Compilation happens at activation, reload, and selection change — never on the render thread or inside a
frame. The active composition stays live while a candidate compiles.

### 7.1 Cache

Key: hashes of every transitively imported source (engine and feature); the composition root text, which
already encodes the full selection; the option values that reach specialization; Slang compiler version
and flags; capability plan; Vulkan target environment. Entries hold only reproducible engine artifacts.

The cache is in-memory and per-run today, keyed on none of the above. Fixing it is both the correctness
step and the iteration-speed fix: ~1.8 s of cold compilation for the world pipeline is already an
iteration-speed problem.

## 8. Selection, settings, and the derived UI

The settings UI is not designed; it is **derived from the registry**, and this is the main reason the
registration API is shaped the way it is.

| Registry structure | UI |
|---|---|
| Slot with N candidate bindings | Radio group. Options are the candidates; label and description come from the owning feature. |
| Provider registration | Checkbox on the owning feature. |
| `Option.bool` / `range` / `enumOf` / `color` | Generated widget with label, range, and default from the declaration. |
| `FeatureCategory` | Section grouping. |

Two consequences worth stating explicitly:

**Conflict policy stops being a policy.** `ARCHITECTURE.md` open question 3 asks whether non-composing
mechanisms resolve by priority, first-wins, or hard error. With slots as radio groups, two candidates for
one slot are not a conflict — they are a choice, and the user makes it. The default is always the built-in
binding; a newly installed extension never silently changes the image. Predictable beats clever here, and
it removes an entire ordering mechanism from the engine.

**Every option declares a reload class**, so an edit is not a device-idle terrain clear:

- `LOOK` — parameters only; reset affected look history;
- `PROGRAM` — recompose affected entry points, rebuild pipelines and affected histories; keep materials
  and geometry;
- `MATERIAL` — rebuild material registry and derived geometry metadata;
- `WORLD` — invalidate world-dependent histories and databases.

Changing a slot selection is `PROGRAM` by construction. The reload class is the difference between
toggling a Nether sky feeling instant and feeling like a resource reload, and it is knowable from the
declaration, which is why it belongs on `Option` rather than in a handler.

The engine still owns the settings that are not aesthetic: reconstruction quality, backend preference,
frame generation, HDR and mastering, display adjustment, budgets, debug and capture. It invents no
aesthetic knobs — that is the point of the boundary.

## 9. Composition lifecycle

A **composition** is one immutable set of slot bindings plus the option snapshot and the compiled
artifacts they produced. A **resource epoch** is the Minecraft resource-pack generation behind texture
pages and material IDs. They change independently.

Activation is transactional: validate the candidate selection without touching the active one; generate
the root; compile and validate every required variant; allocate persistent resources; rebuild material
state if interpretation changed; publish one immutable reference; increment the history-reset sequence;
retire the previous composition after its last graphics use. No partially initialized state is ever
visible to rendering. A failed candidate leaves the current composition active. Startup failure activates
the all-built-in composition; if that fails, Caustica returns to the vanilla renderer.

This is `RayPackEpochManager`'s existing validate-then-swap logic essentially verbatim — the part of the
pack work that carries over untouched. What goes away is everything upstream of it: discovery, duplicate
resolution, manifest parsing, and API-version negotiation. There is nothing to discover; features register
at mod init, and the selection is a user setting.

A frame reads one composition reference for its whole lifetime. Histories rotate only after successful
frame submission; a failed or skipped frame never advances history.

## 10. Refactor map

Ordered so each step is independently revertible. Steps 1–3 are renames and can land before any design
question is settled.

**1. Retire the `pack` vocabulary.** Mechanical, one commit, no behaviour change.

| Path | Action |
|---|---|
| `rt/pack/RayPackShaderCompiler.java` | → `rt/shader/WorldShaderCompiler.java` |
| `rt/pack/RayPackEpoch.java` | → `rt/shader/Composition.java` |
| `rt/pack/RayPackEpochManager.java` | → `rt/shader/CompositionManager.java` |
| `rt/pack/RayPackManifest.java`, `RayPackManifestSchema.java`, `RayPackDiscovery.java` | delete (+ their tests) |
| `rt/pack/RayPackId.java` | delete — use `ResourceLocation` |
| `rt/pack/RayPackContract.java` | delete — a Java API is compile-checked; keep one `CausticaApi.VERSION` string for diagnostics |
| `shaders/pipelines/world/pack_*.slang` | drop the `pack_` prefix (§4) |
| `resources/caustica/raypacks/api/0.1/caustica_ray_pack_api.slang` | → `resources/caustica/shaders/api/`, split per slot |
| `resources/caustica/raypacks/default/shaders/` | → `resources/caustica/shaders/builtin/`, `default_pack` → `caustica_builtin` |
| `caustica.rt.packSky`, `caustica.rt.packSurface` | keep as-is until step 4, then delete in favour of slot selection |
| `caustica.rt.dynamicWorldShaders` | keep; converges to always-on when the build-time fallbacks retire |

`RayPackEpoch.contentHash` currently covers manifest bytes. With the manifest gone it becomes a hash of
the composition root plus every transitively imported source — which is what §7.1 needs anyway, so this
rename is the natural moment to fix it.

**2. Spike associated types** (§2.2). One throwaway file, two slots, one generated root. This gates the
shape of everything after it; do it before writing the registry.

**3. Split the API module per slot and delete `IRayPack`.** With three slots and one implementation each,
this is still mechanical. The entry points change from `TPack pack;` to `TC.Sky sky;`.

**4. Introduce the registry**: `CausticaExtension`, `CausticaRegistry`, `Feature`, `Slot`, `Option`,
`ShaderSource`. Port the built-in bindings to it as `caustica:builtin`, which is the dogfooding check — if
the built-in default needs a private path, the API is wrong.

**5. Nether sky as the first real feature.** Already on the roadmap independently, and it exercises the
whole chain: a second sky binding, a slot the user selects between, a classpath shader source, and options
with a reload class. A better forcing function than a synthetic test feature because it has to actually
look right.

**6. Persistent shader cache** (§7.1), once the key is well-defined.

**7. Settings UI derived from the registry** (§8).

Deliberately *not* in this list: the render-pass API, the provider interfaces, and the frame graph
(tracked in `ARCHITECTURE.md` §8), and the light system (tracked in `LIGHT_SYSTEM_PLAN.md` §7). They are
separate mechanisms with their own extraction targets, and interleaving them here is how the previous
revision grew a second roadmap. `ARCHITECTURE.md` §8.1 records where the three tracks actually couple.

One coupling reaches back into this list: `LIGHT_SYSTEM_PLAN.md` L1c rewrites `risInitial`, which is the
cheap moment to route RIS's target function through `evaluateBsdf` and retire the debt in §11. That makes
step 3 here (splitting the API module per slot) worth landing before L1c if the two are close in time —
opportunistic, not blocking.

### 10.1 The `Rt` prefix

`Rt` is a namespace marker from before the package tree existed, and it is now redundant with it:
`caustica.rt.material.RtMaterialRegistry` says "material" twice and "rt" twice. The target is package =
layer plus domain, class = the thing: `caustica.engine.material.MaterialRegistry`,
`caustica.mc.terrain.TerrainMesher`, `caustica.api.Feature`.

About a dozen names need more than prefix removal, because the bare noun collides or is too vague:
`RtBuffer`/`RtImage` → `GpuBuffer`/`GpuImage` (`java.nio.Buffer`, `java.awt.Image`), `RtComposite` →
`FrameRenderer`, `RtPipeline` → `WorldPipeline`, `RtAccel` → `AccelerationStructures`, `RtEntities` →
`EntityScene`, `RtLookPackage` → `Look`. `RtMaterials` versus `RtMaterialRegistry` needs disambiguating on
the merits regardless of prefix.

**Recommendation: do not sweep this now.** It touches ~90 files for zero behavioural gain and would
conflict with every in-flight branch (`pack`, plus the atmosphere, BLAS-slab, and visibility-island
plans). It is also the same edit as the `core` / `core_minecraft` package split in `ARCHITECTURE.md` §2 —
files move exactly once, and doing it twice is the expensive outcome. Adopt the rule now (new code gets no
prefix and lives in a layer package), rename the `pack` vocabulary now because that concept is genuinely
being deleted, and let the `Rt` names die when the split moves the files.

## 11. Convergence debt

`closest_hit.slang` / `indirect.slang` duplicate the non-slot logic of `closest_hit.rchit.slang` /
`indirect.rgen.slang` — ray-cone LOD, LabPBR decode, the dielectric branch, RIS — because the slice was
built without risking the proven build-time path. That was right for the slice and is wrong as an end
state: two copies of the dielectric interface will diverge.

Convergence: once the generic path has traced real frames and its register usage is measured, the
build-time files are deleted and the generic ones become the only world pipeline, with
`dynamicWorldShaders` retired. Two things block that today — no Vulkan-capable environment has traced a
frame through the surface slice, and `indirect.slang` has no EXT_SER-reordered variant, so opting into it
trades the SER optimization for slot appearance. The SER branch needs splitting into its own module before
the build-time path can go.

Known accepted divergences until then: the built-in surface has no delta/mirror lobe (`EVENT_*` has no
flag for one), so a roughness-0 material renders as a very tight glossy lobe rather than an exact mirror;
and RIS emitter lighting keeps its own target function rather than calling `evaluateBsdf`. The RIS one
has a scheduled payoff point rather than an open-ended one — `LIGHT_SYSTEM_PLAN.md` L1c rewrites
`risInitial` for presampled light tiles, and routing it through the slot there is far cheaper than a
dedicated pass over the same code.

## 12. Open questions

1. Do associated types survive the shim (§2.2), and if not, is the forwarding-struct root acceptable in
   captures and diagnostics?
2. Does `caustica:surface` want to be one slot or two? Material interpretation (`createSurface`) and the
   BSDF are separable, and a feature that only wants "foliage gets more SSS" should not have to
   reimplement Cook-Torrance.
3. What does a feature that needs *both* a slot binding and a render pass look like when the pass API
   lands — one toggle, or does the pass need its own?
4. Should options that reach specialization be distinguished at the declaration from those that reach a
   uniform? Today the difference is invisible and shows up as an unexpected recompile.
5. How much material opacity policy must be engine-owned for OMM and conservative geometry correctness?
6. What resource ceilings suit the minimum supported GPU, and what is the register-usage warning
   threshold?
7. When a third-party ecosystem does appear, does an archive format come back as a *loader feature*
   written against this API — the dogfooding test — or stay out of scope permanently?
