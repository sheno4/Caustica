# Caustica architecture

Status: current implementation. [EXTENSION_API.md](EXTENSION_API.md) is the concrete authoring reference.

## Purpose and boundaries

Caustica is a host-neutral Vulkan path tracer with Minecraft as its first scene adapter. The renderer owns
transport, GPU data structures, resource-pack epochs, shader composition, reconstruction, display mapping and
GPU lifetime. It does not classify blocks, inspect Minecraft registries, decode resource packs or assign
Minecraft lighting values.

The code is divided by responsibility:

- `api/*` is the public Java registration and provider API. It uses `ResourceId`, `DisplayText`, immutable
  geometry/material/light records and no Minecraft types.
- `engine/*` contains host-neutral frame, light, material and scene records and algorithms.
- `spi/host/RuntimeHost` is the reverse callback contract implemented by the first-party application host;
  `spi/vulkan/*` is the Vulkan backend contract. These are integration types, not supported extension API.
- `rt/*` owns Vulkan resources, acceleration structures, canonical material compilation, world-shader
  composition and path tracing. It must not depend on Minecraft, loader, or Mojang types.
- `minecraft/*` owns block/chunk/entity/resource-pack acquisition, Minecraft material decoding and
  photometric calibration, and the terrain/entity capture adapter.
- `platform/*` is the small loader seam for paths and extension discovery; `src/fabric` and `src/neoforge`
  contain only entrypoints and APIs that cannot be shared.
- `builtin/*` supplies the reference sky and surface through the same registry used by extensions.

Reflected Java shader records live in the owning implementation package: renderer records under `rt.gen`,
built-in pass records under `builtin.gen`, and Minecraft pass records beside that adapter under
`minecraft.*.gen`. Their package placement follows shader ownership and does not expand the public API.

Minecraft is therefore a source of geometry, lights, materials and frame state. It is not a semantic mode
inside the renderer.

`MinecraftApiBootstrap` is the application composition root. First-party Minecraft, client and mixin hooks
drive the concrete `RtRuntime` directly. Minecraft scene producers remain behind the public provider API, and
Minecraft telemetry uses the renderer-owned `RtTelemetry` surface through an application-local adapter.

## Lifecycle and epochs

`RtLifecycleCoordinator` serializes process, Vulkan-device, render-session, runtime-activation, resource-pack
and world identity transitions on the client thread. Resource-pack reload completion may arrive from a loader
worker, but publication is queued
until the next client tick. Initial loading and manual reloads therefore produce the same `ResourcePackEpoch`,
independently of whether RT is enabled or a world is open. World identity changes produce a separate
`WorldEpoch`; changing dimensions does not create a resource-pack epoch.

`RtProgramManager` owns process-scoped shader compilation and its immutable cache. The configured composition
starts compiling from the client tick without waiting for a world. A slot change prepares a candidate program
while the matching active session keeps rendering. Only after the candidate is ready does the runtime replace
its selected-slot contributions and converge the world pipeline on the new program. A failed candidate leaves
the matching active session intact.

`RenderSessionEpoch` begins when RT startup is requested and ends on disable, startup failure or shutdown. It
does not own compiled programs. Its child `RuntimeActivationEpoch` owns extension factory output: live render
passes and providers. `ALWAYS` contributions instantiate for every activation; `SELECTED_SLOT` contributions
instantiate only when their feature owns a selected slot. That runtime closure is separate from the program
closure, which also includes every surface and surface-modifier owner so material dispatch remains complete.
Resource-pack closing / applied and world-change callbacks are delivered only to the resulting activation
instances. A selected-slot change closes and recreates the child activation after its candidate is ready,
leaving the parent render session and process-scoped compiled programs live.

`RtRuntime` is the process integration root. It owns lifecycle coordination and the process-scoped program
manager; each runtime activation owns its provider manager, frame renderer and presenter. `RtRuntime` owns one
`VulkanDeviceContext` for the installed Vulkan device, while each activation only borrows it. The context owns
device infrastructure and survives activation rotation. Bootstrap also constructs one `SlangRuntime` from the
game-directory extraction root and optional configured runtime override, then transfers it to `RtRuntime` for
the process lifetime. Program backends borrow that compiler explicitly; no global compiler instance exists.
`RtFrameRenderer` records frames and owns motion,
geometry, lights and TLAS submission state; `RtFrameResources` owns sized images, display resources and
exposure; `RtWorldResources` owns program, material and resource-pack realization; and the presenter delegates
generated-frame queuing, frame generation and HDR/SDR presentation to activation-owned components.

Minecraft negotiates Vulkan features before device creation, then transfers immutable capabilities and the
reserved compute queue into the matching `VulkanRendererBackend`. Renderer feature checks and low-latency state
are device-scoped through that backend; negotiation state is not a renderer-global capability source.

## Frame and scene flow

At host bootstrap, `CausticaExtension` implementations register features in `CausticaRegistry` through
`FeatureBuilder`. A feature may bind the sky slot, register surface implementations and ordered surface
modifiers, install render passes, and register scene, light and material providers.

For each resource-pack epoch:

1. material sources transactionally submit named `MaterialDefinition`s, ordered `MaterialRule`s and neutral
   texture assets, each carrying its own emission calibration when needed;
2. the renderer validates and aggregates those contributions into a source-neutral material catalog;
3. `RtMaterialPageCompiler` builds canonical texture pages and emission footprints;
4. `RtMaterialRegistry` compiles surfaces and bindings and publishes one immutable semantic snapshot to every
   scene provider;
5. geometry stores stable `MaterialHandle(ResourceId)` names until the snapshot resolves them to private,
   epoch-local binding indices.

For each frame:

1. providers prepare and submit complete geometry/light snapshots;
2. the frame adapter supplies one rebase origin and all provider meshes enter its geometry-record index space;
3. retained, frame-varying and distant light segments become one sampling distribution;
4. BLAS work completes before the TLAS build;
5. the world pipeline traces, reconstruction runs, scene-referred render passes chain, and the display
   pipeline maps the result to SDR or HDR;
6. successful submission attaches exact graphics-timeline lifetimes to every retained or transient
   resource used by the frame.

Provider failures are isolated per provider. A failed submission publishes no partial contribution, disables
that provider, retires its staged resources and leaves other providers and the active renderer running.

## Geometry

The public geometry boundary has two cadences. Providers retain the thread-safe `SceneScope` received by
`SceneProvider.onSessionStart` and enqueue long-lived changes for the host's static scene-update cadence.
Providers that need same-frame camera coherence use `submitGeometry(SceneFrameContext)`, whose changes use
the dynamic acceleration policy. Both paths publish atomic, source-local groups of `Put`, `Drop`, `Place`,
`Transform` and `Remove` operations. `Put` retains an immutable indexed `SceneMesh`; `Place` and `Transform`
preserve double-precision world translation until renderer rebasing. Omitted groups remain unchanged. Meshes and
placements remain resident until explicitly replaced or removed, or until their provider stops. The engine
owns upload, BLAS creation, rebasing, canonical geometry records, TLAS insertion and lifetime.

Minecraft's rounded clouds are the proof consumer. `MinecraftCloudSceneProvider` generates deterministic
closed rounded-cuboid meshes, references the named `caustica:cloud` material and submits them through the
ordinary retained-geometry API.

Minecraft terrain and animated entities submit immutable meshes and atomic retained updates through the
same `SceneProvider` API as other providers. They retain source-specific cancellation and CPU meshing,
while the manager owns every GPU geometry buffer, BLAS operation, record, instance and retirement. Scene
providers contribute source-local CPU or borrowed Vulkan textures through the public texture API; the renderer
qualifies their identities and privately owns descriptor slots, uploads and retirement. The renderer contains
no block, chunk or entity types.

## Lights

`LightProvider` submits a complete frame snapshot of `LightDescriptor.Rectangle`, `Point`, `Spot` and
`Distant` records. It may also return an immutable `RetainedLightCollection`; each group has a source-local
key and revision, and the collection generation changes whenever group membership or a revision changes.
An unchanged generation is reused without traversing its groups. Omitted retained keys are removed when a
new generation is collected. Finite positions are scene coordinates. Rectangle radiance is cd/m², point and
spot intensity is candela, and distant normal illuminance is lux. `Distant.direction` points toward the
source; its angular radius describes the source extent.

The provider manager currently retains these immutable frame and retained snapshots as the light-system
skeleton. No renderer buffer, acceleration structure, proposal distribution or direct-light sampling pass
consumes them. Emissive surfaces and environment emitters therefore contribute only when paths hit them.

Minecraft supplies emissive terrain lights as ordinary source-qualified retained light groups.
Minecraft supplies sun and moon as ordinary distant descriptors and the equipped `caustica:spotlight_helmet`
as an ordinary spot descriptor. These exercise provider collection and identity without selecting a
renderer-side sampling implementation.

## Materials and OpenPBR

Materials are source-neutral content. `MaterialSource` can define a stable named material and submit
ordered rules matching a material resource and optional geometry resource. Source order is preserved,
definitions are unique, and a source failure cannot publish partial results.

`MaterialTopology` is structural and independent of OpenPBR `transmission_weight`:

- `SURFACE` participates in the surface/thin-sheet closure;
- `MEDIUM_BOUNDARY` participates in boundary crossing and the engine medium stack.

Coverage is also structural. Opaque, cutout and stochastic coverage are binding/geometry decisions made
before closest-hit surface evaluation. Neither topology nor coverage is a writable procedural material
field. A nonzero `transmission_weight` on `SURFACE` is thin-sheet transmission; it does not silently turn
the geometry into a volume boundary.

The implemented authored/writable vocabulary is a subset of OpenPBR Surface 1.1.1:

- `base_color`, `base_metalness`, `specular_roughness`, `specular_ior`;
- `transmission_weight`, `transmission_color`;
- `subsurface_weight`, `subsurface_color`, `subsurface_scatter_anisotropy` in thin-sheet form;
- `emission_color`, `emission_luminance`;
- writable `geometry_normal` plus Caustica's previous-frame normal for reconstruction.

`specular_roughness` is perceptual roughness and the engine squares it to obtain GGX alpha. Dielectric F0
comes from IOR and metal F0 comes from base color. `specular_color` is fixed to its white default because
conductor F82 edge tint is not implemented. `geometry_opacity` and `geometry_thin_walled` are not writable
fields: coverage and `MaterialTopology` own those decisions. There is no `materialTags` field.

The reference closure implements rough dielectric/metal reflection, OpenPBR thin dielectric-sheet BTDF,
and the thin subsurface sheet. Thin-sheet transmission and subsurface reflection/transmission participate
in the same evaluate/sample/pdf mixture; unsupported coat, fuzz, anisotropic specular, thin film,
dispersion, displacement and full volumetric subsurface parameters stay at their OpenPBR 1.1.1 defaults.

Source acquisition stays outside the compiler. Minecraft owns atlas/resource discovery, borrowed versus
owned image lifetime, LabPBR channel decoding, emission inference, material IOR lookup, and the JSON
lighting calibration for sun, moon, block emission, night airglow, stars and moon phase. It emits semantic
`OpenPbrTextureTexel` values. The neutral page compiler alone packs GPU page channels, mips and fixed-size
`EmissionFootprint` samples. The registry contains no Minecraft texture, block, lava or particle identity.

Minecraft material proofs are ordinary consumers:

- the end portal resource rule selects registered `caustica:end_portal`, whose procedural surface writes
  independent emission color and luminance;
- water is a named `MEDIUM_BOUNDARY` material selecting `caustica:minecraft_water`; its surface owns wave
  normals, medium extinction, reconstruction transmittance and caustic boundary-light response;
- clouds use a named textureless surface material;
- lava resolves its ordinary atlas material and emitting variant;
- particle billboards use a Minecraft-owned named surface material, then derive producer texture and
  coverage through generic binding operations within the captured material epoch.

## Shader composition

Runtime composition has one substitution slot: `Slots.SKY` / `ISkyModel`. Surfaces do not compete for a
slot. Every `FeatureBuilder.surface(...)` registration becomes a case in generated `ISurfaceDispatch`,
and each compiled material selects its surface by registered `ResourceId`.

`ISurfaceModel` has four load-bearing methods:

- `evaluateSurface` edits the decoded OpenPBR `MaterialInput`;
- `evaluateResponse` applies a final stylized response while the engine retains BSDF ownership;
- `evaluateMedium` returns the boundary's generic `MediumProperties`;
- `evaluateMediumLighting` returns an optional multiplicative direct-light response at a tracked boundary.

Medium behavior is selected by the same registered surface implementation stored in the material binding.
The engine retains boundary tracking,
Beer-Lambert attenuation, guide traversal and the medium stack.

Projected surface modifiers are additive. `FeatureBuilder.surfaceModifier(...)` registrations become an
ordered generated `ISurfaceModifierDispatch`; geometry explicitly marked as a modifier receiver runs each
modifier sequentially after surface evaluation. Minecraft block damage is the proof: a render pass
publishes its current damage entries and crack textures, and `MinecraftDamageModifier` projects them onto
receiver terrain without a renderer-owned block-damage branch.

Minecraft's portal, water and damage Slang modules live under `shaders/minecraft`; the renderer imports
only generated dispatch interfaces and reflected resources. Java records generated from reflected pass
layouts are likewise emitted beside the pass that owns the layout rather than through `rt.gen`.

## Render passes and resources

`CausticaRenderPass` receives Vulkan access through `PassSetup` and `PassFrame`. Passes allocate their own
resources, may publish reflected world resources in descriptor set 2, and may chain scene-referred color
through `sceneColor()` / `sceneColorTarget()`. The world shader compiler validates reflected names, kinds
and descriptor indices. Set 0 remains engine-owned; set 1 is the bindless texture array; set 2 contains
feature-declared reflected world resources.

The Minecraft sky LUT pass and damage modifier pass use this path. Resource modules are explicitly
anchored with `FeatureBuilder.passResourceModule(...)` because Slang generic implementations cannot own
global shader parameters directly.

## Current limitations

- Provider geometry is triangle-mesh based. There is no public volume-density source contract, so rounded
  clouds are geometry rather than participating volumes.
- The OpenPBR subset omits coat, fuzz, thin film, dispersion, anisotropic specular, displacement and a full
  volumetric subsurface model.
- The BSDF has no exact delta/mirror event; zero roughness is represented by a very tight glossy lobe.
- Runtime shader compilation has no persistent cross-run cache yet.

## Visual verification

Changes at these boundaries should be checked in-world even when ABI tests pass:

- rounded cloud silhouette, normals and stable world placement;
- helmet spotlight cone, range and shadows;
- end-portal procedural animation and independent emission color;
- water waves, refraction, underwater attenuation and caustic response;
- block-damage crack projection and texture stage;
- lava and particles after material resource reload;
- sun/moon direction, angular size and brightness across time and moon phase.
