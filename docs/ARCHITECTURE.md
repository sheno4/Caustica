# Caustica architecture

Status: current implementation. [EXTENSION_API.md](EXTENSION_API.md) is the concrete authoring reference.

## Purpose and boundaries

Caustica is a host-neutral Vulkan path tracer with Minecraft as its first scene adapter. The renderer owns
transport, GPU data structures, resource epochs, shader composition, reconstruction, display mapping and
GPU lifetime. It does not classify blocks, inspect Minecraft registries, decode resource packs or assign
Minecraft lighting values.

The code is divided by responsibility:

- `api/*` is the public Java registration and provider API. It uses `ResourceId`, `DisplayText`, immutable
  geometry/material/light records and no Minecraft types.
- `engine/*` contains host-neutral frame, light, material and scene records and algorithms.
- `rt/*` owns Vulkan resources, acceleration structures, canonical material compilation, world-shader
  composition and path tracing. Import-firewall tests keep Minecraft, loader, and Mojang types out.
- `minecraft/*` owns block/chunk/entity/resource-pack acquisition, Minecraft material decoding and
  photometric calibration, and the terrain/entity capture adapter.
- `platform/*` is the small loader seam for paths and extension discovery; `src/fabric` and `src/neoforge`
  contain only entrypoints and APIs that cannot be shared.
- `builtin/*` supplies the reference sky and surface through the same registry used by extensions.

Minecraft is therefore a source of geometry, lights, materials and frame state. It is not a semantic mode
inside the renderer.

## Frame and scene flow

At host bootstrap, `CausticaExtension` implementations register features in `CausticaRegistry` through
`FeatureBuilder`. A feature may bind the sky slot, register surface implementations and ordered surface
modifiers, install render passes, and register scene, light and material providers.

For each resource epoch:

1. material sources transactionally submit named `MaterialDefinition`s and ordered `MaterialRule`s;
2. the host adapter submits a neutral `MaterialCatalog` containing canonical texture sources, emission
   footprint resolution and its default uniform-emission calibration;
3. `RtMaterialPageCompiler` builds canonical texture pages and emission footprints;
4. `RtMaterialRegistry` compiles surfaces and bindings and publishes one immutable snapshot;
5. geometry stores stable `MaterialHandle(ResourceId)` names until the snapshot resolves them to private,
   epoch-local binding indices.

For each frame:

1. providers prepare and submit complete geometry/light snapshots;
2. retained provider meshes and the primary scene source are rebased into one geometry-record index space;
3. retained, frame-varying and distant light segments become one sampling distribution;
4. BLAS work completes before the TLAS build;
5. the world pipeline traces, reconstruction runs, scene-referred render passes chain, and the display
   pipeline maps the result to SDR or HDR;
6. successful submission attaches exact graphics-timeline lifetimes to every retained or transient
   resource used by the frame.

Provider failures are isolated per provider. A failed submission publishes no partial snapshot, disables
that provider, and leaves other providers and the active renderer running.

## Geometry

The public geometry boundary is `SceneProvider.submitGeometry(SceneGeometrySink)`. Providers retain
immutable indexed `TriangleMesh` values under provider-local keys and submit double-precision
`GeometryTransform` instances. Instances are frame declarations and disappear when omitted; retained meshes
remain resident until explicitly released, replaced under the same key, or their provider stops. The engine
owns upload, BLAS creation, rebasing, canonical geometry records, TLAS insertion and lifetime.

Minecraft's rounded clouds are the proof consumer. `MinecraftCloudSceneProvider` generates deterministic
closed rounded-cuboid meshes, references the named `caustica:cloud` material and submits them through the
ordinary retained-geometry API.

Minecraft terrain and animated entities use the internal, host-neutral `RtSceneSource` environment and
CPU-capture contract. The renderer injects its geometry manager through `ProviderManager`; sources submit
canonical captures and retain source-specific cancellation, meshing and texture discovery, while the
manager owns every GPU geometry buffer, BLAS operation, record, instance and retirement. These adapters
live under `minecraft/*`, use the same geometry table and TLAS as public provider geometry, and are not a
second public extension API. The renderer contains no block, chunk or entity types.

## Lights

`LightProvider` submits a complete frame snapshot of `LightDescriptor.Rectangle`, `Point`, `Spot` and
`Distant` records. Finite positions are scene coordinates. Rectangle radiance is cd/m², point and spot
intensity is candela, and distant normal illuminance is lux. `Distant.direction` points toward the source;
its angular radius defines the sampled source cone.

Finite retained lights use a source-owned retained BVH segment. Per-frame finite provider lights use a
transient BVH, and distant lights occupy the distant segment of the same frame light buffer. The shader's
light-tree sampler selects across retained finite, transient finite and distant segments using their
canonical selection metrics, then samples the chosen finite hierarchy or distant source. NEE and RIS
therefore see one light scene; there is no fixed celestial-light branch or legacy light grid.

Minecraft supplies emissive terrain lights through its retained scene, sun and moon as ordinary distant
descriptors, and the equipped `caustica:spotlight_helmet` as an ordinary spot descriptor. The helmet is
the light-provider proof: it receives the same visibility, shadow and path-traced lighting treatment as
any other submitted spot.

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
only generated dispatch interfaces and reflected resources.

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

- Minecraft terrain/entity capture is an internal adapter over the engine-owned retained geometry manager;
  it is not yet expressed by the public retained mesh API.
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
