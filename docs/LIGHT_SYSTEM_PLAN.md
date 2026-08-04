# Caustica Light System Plan

Status: design. Scope: the emitter light database, its proposal structure, and the direct-lighting
estimator — `rt/terrain/RtLight*`, `rt/material/RtEmissionGrid`, and `lighting.slang`.

`ARCHITECTURE.md` owns the layering and the register-mechanism sequencing; this document owns the light
system's own design and slots its work into that ordering (§7).

## 1. What exists today

- **Emitters are terrain quads.** `RtLightCollector` turns each emissive quad into one rectangle light —
  bounding rectangle, mean radiance over it, so total power equals the quad's true emissive integral.
  `lightSections` is fed only from `SectionGeom` ([RtTerrain.java:1605](../src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java:1605)).
- **One immutable generation at a time.** `RtLightGridManager` builds lights, the global power alias, the
  section-local aliases, and the proposal grid on a worker, uploads one arena, and atomically publishes.
  Rebuilds are throttled to 50 ms ([RtTerrain.java:121](../src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrain.java:121)).
- **The proposal is a two-stratum mixture.** A cell within ±2 sections of a powered section gets spans
  weighted `power / max(1, distSq)` in section units; everything else falls to a global power alias with
  no distance term. At M=8 the split is 6 local / 2 global
  ([lighting.slang:250](../shaders/pipelines/world/lighting.slang:250)).
- **RIS is single-frame.** `risInitial` builds a fresh reservoir from M candidates every frame. There is
  no temporal or spatial reuse.
- **Measured cost.** Pinning the candidate walk out of the shader saved 5.9 ms of an 18.0 ms frame at
  M=8 — ~4.3 ms at secondary vertices, ~1.1 ms at the primary hit. Forcing coherent selection recovered
  at most 1.3 ms of that ([lighting.slang:224](../shaders/pipelines/world/lighting.slang:224)).

## 2. One root cause

**Minecraft's 16³ chunk section is the light system's spatial primitive, and it is baked into the GPU
ABI.** Each 32-byte light record spends bits 0–29 on a *grid-relative section coordinate*, and the shader
reconstructs the local proposal PDF from it in O(1):

```slang
int3 delta = lightSectionCoord(light) - cellCoord;
if (all(abs(delta) <= int3(2, 2, 2))) {
    localPdf = power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
}
```

The section coordinate exists for one reason: after `selectSectionLight` returns a global light index,
the shader has lost which span proposed it, and the mixture PDF must be computable for *any* light —
including one that arrived through the global stratum. The section coordinate is how "which span" is
recovered from "which light."

That single choice is why every problem below is hard, and it has a visible cost already: the whole light
array is re-walked and re-packed whenever the grid origin moves, and a light outside the packed grid is a
hard `throw` ([RtLightHierarchy.java:144](../src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtLightHierarchy.java:144)).

## 3. Four problems

### 3.1 The core / `core_minecraft` split does not hold

`ARCHITECTURE.md` §2.2 flags `rt/terrain/RtLight*` as a boundary on the wrong side. It is more specific
than file placement — the *unit* is wrong, not the package:

| File | Belongs in | Note |
|---|---|---|
| `RtLightCollector` | `core_minecraft` | Sprites, LabPBR masks, block-light levels, emissive quads. Cleanly Minecraft. |
| `RtLightGridManager` | **`core` already** | Async generation, VMA, staging, publish/retire. One Minecraft import, for the debug dump only. |
| `RtLightHierarchy` | split | Vose alias, Morton order, and record packing are core; `SectionInput(sectionSlot, sectionX/Y/Z, …)` is Minecraft. |
| `RtLightGrid` | core algorithm, Minecraft units | `NEIGHBOR_RADIUS = 2` is in *sections*; weights are in section units; origin is `minX * 16`. |
| `lighting.slang` | core, except the ABI | `lightSectionCoord` puts the chunk grid inside the estimator. |

The lifecycle code is already core-shaped. What is not is the spatial unit — a `LightProvider` spotlight,
a Distant Horizons light, or an entity light has no section coordinate, and today that is a thrown
exception rather than a missing feature.

### 3.2 Mid-distance boiling is a cliff, not a falloff

Two effects compound.

**The global stratum has no distance term.** `globalPdf = power * invTotalPower`
([lighting.slang:184](../shaders/pipelines/world/lighting.slang:184)) is pure emitted power over every
light in the world. A light more than 2 sections away has `localPdf = 0`, so its only proposal route is
`power / ΣP` — roughly 1/N in a lit base or cave system, sampled 2 times out of 8.

**The transition is discontinuous.** At roughly 32–48 blocks a light goes from 1/d²-weighted with 6
candidates to power-uniform with 2. Nothing interpolates. Note this is per *light–point pair*, not per
point: it also happens well inside the grid for any light 3+ sections away.

Two smaller contributors: the local weight is per section *pair*, so every point in a 16-block cell
shares one weight — the jittered lookup
([lighting.slang:244](../shaders/pipelines/world/lighting.slang:244)) hides the seam but not the
within-cell error; and `max(1, distanceSq)` flattens the self-cell and its six face neighbours to equal
weight.

Do not respond by raising `NEIGHBOR_RADIUS`. Spans are O(r³) per powered section, so 2 → 4 takes each
section from 125 to 729 spans, with grid memory and build time following.

### 3.3 There are no dynamic lights

Emissive entities are always-gathered on path hits and illuminate nothing. Handheld light — the
LambDynamicLights case `ARCHITECTURE.md` §1.1 builds its entire argument on — does not exist.

It cannot be bolted onto the current path. The generation is one immutable worker snapshot throttled to
50 ms, the arena is a single wholesale allocation and copy, and a moving light shifts the grid AABB,
which re-packs the section coordinate of *every* light.

### 3.4 Orientation is discovered too late

Lights are oriented rectangles (`lightGeometricNormal`, with a flip bit), but the proposal has no
orientation term at all. `evalSampleContrib` discovers `cosL <= 0` and returns zero *after* the candidate
has been spent ([lighting.slang:55](../shaders/pipelines/world/lighting.slang:55)). In a room of
one-sided emissive quads a meaningful fraction of the 8 candidates are dead on arrival, and no amount of
grid tuning recovers them.

## 4. Target architecture

### 4.1 Two-level light BVH

A BVH over all lights, with leaves carrying position, half-axes, radiance, and an orientation cone;
interior nodes carrying an AABB, summed power, and a bounding cone.

- **Static level** — terrain emitters, built on the worker and throttled, exactly as today.
- **Dynamic level** — entities, held items, and `LightProvider` registrations. Tens of lights, rebuilt
  per frame.
- A trivial root joins them.

The BVH has no opinion about chunk sections, so **the section coordinate leaves the ABI as a consequence
of the structure**, not as a separate refactor. Terrain, entity, and provider lights become leaves of one
tree with one record format, one `evalSampleContrib`, and one reservoir. The shader never branches on
tier for shading.

Build cost is low: `RtLightHierarchy` already Morton-orders lights by section, so an LBVH build is that
ordering plus a hierarchy pass.

### 4.2 Presampled tiles

A compute pass descends the BVH and writes flat `PresampledLight` records — position, half-axes,
radiance, **and the source PDF** — into per-tile arrays. `risInitial` then picks M entries from its tile.

Storing the PDF is the load-bearing detail. The descent's branch-probability product is known during
traversal; writing it means the shader reconstructs nothing, which is exactly what the section coordinate
existed to enable. **This is why the BVH and presampling cannot be sequenced separately** (§7.1).

### 4.3 The mixture keeps a global stratum

A light absent from a tile has zero proposal probability, which is a support hole and therefore bias.
Keep the global power alias as one stratum so support stays complete:

```
q = α·q_tile + β·q_dynamic + (1 − α − β)·q_global
```

The existing three-way-shaped mixture in `proposalPdf` already has the right form; only the terms change.

## 5. Why a BVH rather than a wider or cascaded grid

An earlier reading of the 5.9 ms measurement — "the cost was chasing depth, not divergence" — argued
against a light tree on the grounds that a ~12-level descent is four times the current 3-load chain. That
extrapolation is wrong, and the correction matters enough to state plainly: the measurement describes a
chain executed **inline, per candidate, inside a low-occupancy megakernel raygen**. It is a statement
about that execution context, not about dependent loads in general.

Presampling changes the context. The traversal moves from once per candidate per vertex — tens of
millions of chains per frame at render resolution with M=8 — to once per tile entry, on the order of 10⁵.

| | today | BVH + presampled tiles |
|---|---|---|
| Traversals per frame | ~10⁷, divergent, low occupancy | ~10⁵, coherent compute |
| Dependent loads in the hot path | 3 (span → alias → record) | **1** (tile entry, cached) |
| Proposal quality | 1/d² within 2 sections, power-uniform beyond | ~power/d² with orientation, continuous at all distances |
| Orientation | none; discovered after selection | cone bounds during descent |

The hot path gets **shorter than it is now**. Descent depth stops mattering once it is amortized roughly
250:1 into a pass where latency hides behind occupancy instead of competing with live path state.

A cascaded clipmap grid would remove the cliff but keeps the grid's other two limits: no orientation
term, and a spatial unit that still has to be reconciled with non-section lights. It survives in this
plan only as the *tile conditioning* structure (§8.1), which is a much smaller thing.

## 6. Wavefront and occupancy

Ranked by leverage for this codebase:

1. **Presampled tiles.** Highest value for the smallest architectural change — a new compute pass plus a
   rewrite of `risInitial`'s candidate loop. `indirect.rgen` stays a megakernel.
2. **Occupancy is the actual mechanism.** The 4.6 ms of latency is not "loads are slow," it is "there are
   not enough concurrent waves to hide them," because the raygen holds path state, payload, and the
   medium stack live. Anything that moves light sampling into a small-register pass hides it for free.
3. **SER.** `trace_ser.slang` already exists. Reordering by hit point makes tile lookups coherent within
   a wave and compounds with presampling. Note the gap in `EXTENSION_API.md` §11 — the slot-generic
   `indirect.slang` has no reordered variant, so opting into it currently trades SER away.
4. **A full wavefront split** (trace → hit buffer → light sample → shadow → shade). The largest occupancy
   win, but it materialises hit points to memory and gives up register-resident path state. Treat it as
   the fallback if 1–3 fall short, not as the opening move.

## 7. Ordering

### 7.1 The light track

Sub-steps are individually verifiable; the step boundary is where behaviour changes.

**L1 — two-level BVH plus presampled tiles.** Replaces `RtLightGrid` entirely and retires the section
coordinate from the ABI.

- *L1a.* Build the BVH on the worker **alongside** the existing grid. No shader change; validate against
  a CPU reference and the existing `Rt.Lights.DUMP`/`STATS` paths.
- *L1b.* Add the presample pass, writing tiles with stored PDFs. Still no shading change; validate
  sampled tile frequencies against a CPU reference distribution.
- *L1c.* Switch `risInitial` to tiles, retire the grid, drop `Light.section`.

These cannot be reordered into "kill the section coordinate first." The section coordinate exists to
reconstruct a PDF the shader has otherwise lost (§2); removing it before something else carries the PDF
means inventing a third reconstruction scheme for a structure about to be deleted.

L1c is also the right moment to pay down the RIS target-function debt recorded in `EXTENSION_API.md`
§11 — `risInitial` is being rewritten anyway, and routing it through the surface slot's `evaluateBsdf`
is much cheaper here than as a separate pass over the same code.

**L2 — dynamic tier.** Entity, held-item, and provider lights as the BVH's second level. This is the
first thing that makes an emissive entity actually illuminate anything.

**L3 — ReSTIR temporal and spatial reuse.** The variance fix proper; raises effective M by orders of
magnitude.

Legitimate alternative ordering: **L3 before L1.** Temporal reuse is independent, smaller, and would
partly mask the boiling on its own. The argument for L1 first is that temporal reuse layered over a
proposal with a hard support cliff shows up as lag and ghosting at exactly the cliff distance, so the
temporal pass is harder to tune before the proposal is continuous. This is a judgement call, not a
constraint — take L3 first if a faster partial win is worth the retuning.

### 7.2 Where it meets the main refactor

Three convergence points with `ARCHITECTURE.md` §8, which the two orderings did not previously
acknowledge:

**§8 step 5 ("light provider proof: a handheld spotlight") is blocked on L2, and §8 does not say so.**
That step is listed as a provider-interface exercise, but a handheld light is a *dynamic* light, and the
light system structurally cannot accept one (§3.3). Its real prerequisites are L1 and L2 plus §8 step 3's
provider interfaces. L2 and §8 step 5 are the same piece of work approached from two directions and
should be planned as one.

**The presample pass is a design input to §8 step 1 (the render pass API), even though it lands after
it.** Its shape — runs every frame, fixed dispatch, engine-consumed output feeding RIS, needs a
validation and fallback story — is squarely in what the pass API must support, and it is a better third
consumer than anything else on the list. Bloom is a leaf with no engine consumer; the sky LUT proves the
engine-consumed case at small scale; the presample pass proves it at frame-critical scale.

**L1 does not wait on any extension-API work.** It touches no slot interface and no registry. The one
overlap is the `evaluateBsdf` routing at L1c, which is opportunistic rather than blocking — if the slot
split (`EXTENSION_API.md` §10 step 3) has not landed, L1c keeps the current inline target function and
the debt stays where it is.

Nothing in the light track depends on the archive/registry work, and nothing in the registry work depends
on the light track. They are genuinely parallel until §8 step 3.

## 8. Open questions

1. **What conditions a tile?** Screen tiles are the ReSTIR default and cheap (~512 tiles × 1K entries ×
   32 B ≈ 16 MB), but they degrade at secondary vertices where hit points scatter — and the measurement
   puts 4.3 of the 5.9 ms *at* secondary vertices, exactly where screen tiles are weakest. World-space
   cells handle secondaries correctly but need clipmapping to bound the count; a naive 4096 cells × 512 ×
   32 B ≈ 64 MB is too much. Prototype world-space clipmapped cells first given where the cost actually
   is, but this is not settled from reading — it needs a measurement.
2. Tile staleness: presampled one frame ahead, or in-frame before the trace? In-frame costs a barrier and
   a dependency; one frame stale costs correctness on fast-moving dynamic lights.
3. Does the dynamic level want its own tile stratum, or is it small enough to sample exhaustively at each
   vertex? At tens of lights, exhaustive may beat any structure.
4. Cone bounds cost bits in the node. What is the minimum useful encoding, and does the record still fit
   the cache-line-friendly size the current 32-byte light record was chosen for?
5. What invalidates a tile — camera motion, light generation change, dimension change? This ties into the
   history-reset sequence the composition lifecycle already owns.
6. Does Distant Horizons geometry contribute emitters, and if so at what LOD? A distant lava lake is a
   large, dim, low-frequency emitter and may want a different leaf representation.

## 9. What stays engine-owned

Unchanged from `ARCHITECTURE.md` §6 and restated here because the light system is where they bite:

- **Emitter direct-hit gating** stays derived from RIS participation. An emitter sampled by RIS must not
  also be gathered on hit; an emitter *not* sampled must always be gathered, or its light is lost.
- **The estimator contract.** A proposal PDF that does not match the sampling procedure biases every
  backend, and the symptom is a brightness shift rather than a visible failure.
- **Absolute radiance.** Lights carry scene-linear ACEScg in absolute units; no producer emits
  pre-exposed or tonemapped values.
- **Persistent lighting state stores engine facts only** — light identity, sample point, proposal and
  target terms, age, epoch tags — never a slot-supplied closure, so reservoirs stay out of any
  cross-frame ABI that appearance code can change.
