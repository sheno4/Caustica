# Caustica architecture

Status: current implementation. [EXTENSION_API.md](EXTENSION_API.md) is the extension-authoring reference.

## Boundaries

Caustica is a renderer-generic, Minecraft-independent Vulkan path tracer integrated into Minecraft by the
`minecraft-client` Loom application package. The extension API is intentionally Vulkan-native;
renderer-generic does not mean GPU-neutral.

The physical projects under `packages/` enforce the reusable boundaries:

| Project | Ownership |
|---|---|
| `api`, `api-support`, `settings-api`, `shader-api` | Vulkan-native Java/Slang extension contracts and small consumer utilities |
| `config` | Root-facing persisted renderer configuration |
| `engine` | Renderer-generic session, program, retained-scene, environment-selection, and pass lifecycles |
| `engine-vulkan`, `vulkan-support` | Vulkan profile/backend SPI, mapped descriptor heaps, VMA resources, submissions, synchronization, and reusable helpers |
| `renderer-raytracing` | Program composition, retained acceleration structures, path tracing, and NEE-AT |
| `renderer-presentation` | Exposure, reconstruction-facing presentation inputs, HDR/SDR mapping, and display composition |
| `renderer-runtime` | Generic frame recording/resources/statistics, lifecycle coordination, pass scheduling, telemetry, capture, and the host callback SPI |
| `renderer-denoising` | Renderer-owned temporal-denoiser contracts, frame inputs, reset semantics, and borrowed Vulkan resource descriptions |
| `nvidia-ngx` | NGX, DLSS Ray Reconstruction, and DLSS Frame Generation integration |
| `nvidia-nrd` | Pinned `third_party/NRD` subrepository, NRI Vulkan backend, and native packaging |
| `minecraft-api`, `minecraft-adapter` | Loader-neutral Minecraft world/resource epochs, world sessions, scene borrowing, and environment selection |
| `minecraft-content` | Host-free Minecraft material analysis and texture-page planning/compilation |
| `minecraft-rendering` | Minecraft-independent terrain/entity/material/light/sky rendering and the host-free frame/capture seams |
| `minecraft-client` | Minecraft runtime/session coordination, mapped host/UI access, Vulkan interception, loader entrypoints, mixins, resources, and final composition |
| `features-builtin` | Built-in programs and passes expressed through the same contracts used by extensions |
| `slang-runtime`, `slang-tooling` | Slang runtime ownership and reusable compile/reflection/generation tooling |
| `examples/*` | Strict public-contract consumers for API and glTF integration pressure |

The `minecraft-client` Loom project is deliberately an application package rather than a reusable library. It
owns mapped Minecraft host access, UI, Vulkan interception and device bring-up, Fabric/NeoForge entrypoints and
discovery, mixins, resource loaders, and final object composition. `CausticaClientComposition.current()` is a guarded publish-once
bridge for those entrypoints and mixins. It is not a renderer service locator: `packages/renderer-runtime`
contains no composition/current lookup or Minecraft API/adapter dependency, and runtime, settings, telemetry, UI-overlay, and terrain state are
instance-owned rather than installed into mutable static service slots.

The five frame/capture types moved out of the root into `minecraft-rendering` are
`MinecraftFrameSelector`, `MinecraftFrameSelectionInstaller`, `MinecraftFrameCaptureInstaller`,
`MinecraftFrameCaptureState`, and `MinecraftEntityCaptureBinding`. They contain no Mojang, loader, root-client,
or renderer-implementation dependency. `minecraft-client` supplies their live Minecraft implementations.

## Ownership and lifecycle

Process discovery registers settings first, loads option values, and then registers render-session and
Minecraft-world-session factories. A render session owns its GPU, program, geometry, light, and pass channels.
Minecraft world sessions borrow the host-owned scene for their world epoch.

Program contributions publish one atomic owner-scoped `ProgramRegistration`. Readiness is
pending/ready/failed/cancelled for the complete registration; closing it is the owner's only removal authority.
There is no runtime singleton or renderer-composition lookup involved in publication or frame recording.

Mesh, instance, and light IDs are owner-local mutation capabilities. A same-session `LightId` may also be
handed to geometry as a non-owning `PrimitiveLightMap` selection without granting light mutation authority or
extending its lifetime. A handed-off `SceneId`, `SurfaceId`, `VolumeId`, or `EnvironmentId` is likewise a
same-session non-owning selection reference.

Each environment contribution owns one slot for one scene. Every successful `select` moves that owner to the
latest position and makes its binding active. Invalidating the active owner restores the most recently selected
surviving slot deterministically; invalidating a dormant owner leaves the active selection unchanged. Replaced
or removed bindings retire only after both their slot ownership and every published GPU snapshot reference are
gone. `invalidate()` stops future selections and `drain()` waits for that owner's retirement callbacks.
A scene with no surviving selection uses the renderer's built-in environment fallback.

Teardown stops callbacks and producers before invalidating scoped channels. The retained-scene backend then
crosses one settlement boundary so accepted native work can finish without another render frame. Program
readiness, pass uses, and GPU retirement callbacks drain before implementations close. Recording callbacks
must not block on device idle or await their own frame.

## Frame and scene flow

Each frame uses one `SceneView`: a host-issued entry scene, pose-only camera, and one mandatory homogeneous
medium containing the primary-ray origin. The medium is either `ViewMedium.Vacuum` or a typed
`ViewMedium.Volume`. Minecraft decides fluid containment from the same corner heights and surface triangles
used by its fluid mesher; ray generation only consumes the renderer-generic selection.

The engine keeps independent retained geometry, light, environment, TLAS, and NEE-AT state per `SceneId`.
Public extensions can target a borrowed scene but cannot create, close, or link scenes. This is identity and
lifetime isolation, not simultaneous traversal: each current trace root selects exactly one TLAS. A ray portal
requires a new multi-scene trace ABI plus transform, hop/cycle, lighting, and teardown semantics.

For a frame:

1. `minecraft-client` captures host state through the package-owned frame/capture seams;
2. the adapter selects the scene and camera medium and advances world-session contributions;
3. retained geometry, lights, environment, and program state are snapshotted atomically;
4. reusable BLAS candidates and the scene TLAS are prepared, then the persistent per-scene NEE-AT distribution
   is updated;
5. world-resource passes record and the ray pipeline traces; the selected raw, NRD, or Ray Reconstruction route
   reconstructs scene colour before post effects, UI, and SDR/HDR presentation;
6. successful submission attaches exact graphics-timeline retirement to every referenced resource.

The active Vulkan profile is Vulkan 1.4 with unified image layouts, descriptor heaps, push descriptors,
synchronization2, and shader objects for compute/raster. Push descriptors are required by the wrapped NRI
backend; renderer shaders otherwise use descriptor heaps. Ray stages remain on `VK_KHR_ray_tracing_pipeline`.
The renderer device context owns one mapped resource heap and one mapped sampler heap; passes borrow the bound
heaps and live `VkCommandBuffer`.
`GpuDescriptorHeapProperties` exposes the resource descriptor stride in bytes plus the maximum resource and
sampler allocation sizes in slots. Sampler stride, heap alignment, and total capacity remain backend details.
Images and samplers use direct heap access. Acceleration structures use a conventional SPIR-V binding mapped
at shader or pipeline creation to a heap index in pushed data. This mapping does not introduce a descriptor-set
layout or descriptor-set bind command.

## Programs, materials, and lighting

Runtime composition generates surface, coverage, volume, and environment dispatch from complete accepted
program registrations. There is no public surface-modifier chain. Minecraft block damage is explicit
Minecraft instance/material data, not a renderer-global modifier lifecycle.

Minecraft material analysis and page compilation are host-free in `minecraft-content`; live registry/atlas
capture stays in `minecraft-client`. `minecraft-rendering` owns the Minecraft Slang modules, reflected records, material
GPU publication, sky LUTs, and terrain/entity/light adapters without importing Minecraft runtime classes.

One-sided parallelogram, circular-spot, and distant lights publish through retained engine operations. The ray
tracer builds
persistent per-scene double buffers containing a global discrete distribution and tiled local histograms from
previous-frame light and pixel feedback. Sampling uses mixture proposal PDFs and candidate RIS; reverse MIS
feeds the next update. CPU property tests cover global normalization and CDF boundaries, local histogram
probing/address ranges, history validity and identity continuity, and shader-source constants/branch edges.
These static properties do not replace physical visual validation.

Opacity micromaps are not active in the current geometry or acceleration path and have no public contract.
Cutout coverage remains a surface-program and any-hit concern.

## Reconstruction and denoising

The renderer has three mutually exclusive reconstruction routes. `RAY_RECONSTRUCTION` is the default and is
the only route that invokes NGX DLSS Ray Reconstruction. `TEMPORAL_DENOISER` runs the bundled NRD backend at
display resolution, using REBLUR by default with RELAX as the alternate method. `RAW` performs an exact copy
of the traced image and is the minimal reference route. Neither NRD nor RAW depends on Streamline.

Ray Reconstruction consumes primary-surface normal, depth, albedo, roughness, and dense motion guides. A
bounded deterministic stable-plane traversal follows the dominant delta chain for reflection reconstruction
metadata. It keeps primary normal/depth/motion attached to the primary surface and supplies a separately
unfolded virtual endpoint only for eligible reflection motion; transmission retains the optical fallback.

NRD consumes separate demodulated diffuse and specular radiance/hit-distance signals, view-space depth,
primary normal/roughness, and screen-space motion. RELAX uses linear RGB and absolute hit distance. REBLUR uses
YCoCg and normalized hit distance with the matching backend parameters. Primary emission and environment
radiance remain an explicit unfiltered residual, and composition remodulates the two denoised lobes before
applying pre-exposure once. Camera cuts, scene/history discontinuities, resizes, route changes, and method
changes reset or recreate temporal state.

## Passes and validation

World-resource passes run before tracing. Post and UI passes use stage-local `PassId` values and at most one
`PassPlacement.before(...)` or `after(...)` constraint. An anchor may name another contribution's pass in the
same stage and grants ordering only. Missing anchors are unconstrained and acceptance order breaks remaining
ties. The same textual ID in another stage is unrelated; duplicate live IDs within one stage, self-anchors,
and cycles are rejected.

Static package, dependency/import, lifecycle, shader/reflection, ABI, native NRD, and example-consumer gates are
green. Direct Fabric launches into `ray-tracing-test-place` validate raw, RELAX, REBLUR, and release-DLL Ray
Reconstruction routes. Route-specific telemetry confirms that only the selected reconstruction backend records
work, and the captured final/debug views contain the rendered world without an actionable Vulkan, NRI, NRD, or
NGX failure.
