# Caustica rewrite API review

Date: 2026-08-31
Scope: architecture, API, and implementation reconciliation
Status: rewrite, Ray Reconstruction, and NRD integration accepted for the current experimental API

## Executive decision

Continue against the implemented experimental API. Package/lifecycle gates, Minecraft material and retained
lighting integration, stable-plane Ray Reconstruction, and the independent NRD route are complete. Keep the
contract experimental while quantitative transport tuning continues; no structural rewrite blocker remains.

The main API remains deliberately Vulkan-native. Reusable code should be described as renderer-generic or
Minecraft-independent rather than GPU-neutral. Vulkan command buffers, device addresses, descriptor
heaps, and retirement are intentional extension contracts; Minecraft lifecycle and host types remain separate.

## Implemented decisions

- `packages/api` exposes process registration, owner-scoped render sessions, asynchronous compute recording,
  atomic program registration, retained geometry/lights, views, staged passes, and typed Vulkan resources.
- `packages/engine` owns renderer-generic session, program, retained-scene, environment-selection, and pass
  state. `packages/engine-vulkan` owns Vulkan profile validation, mapped heaps, VMA resources, submission
  retirement, diagnostics, and the renderer-backend SPI.
- `packages/renderer-raytracing` owns program composition, retained acceleration structures, path transport,
  and NEE-AT. `packages/renderer-presentation` owns display composition.
- `packages/renderer-runtime` owns generic frame renderer/resources/statistics, lifecycle coordination, pass
  scheduling, telemetry, capture helpers, jitter, and `RuntimeHost`. Its dependency/import gates exclude the `minecraft-client` application,
  loaders, Minecraft runtime, and client composition.
- `packages/minecraft-api`, `minecraft-adapter`, `minecraft-content`, and `minecraft-rendering` divide Minecraft
  lifecycle, host-free content analysis, and Minecraft-independent rendering logic from the mapped host.
- the `minecraft-client` Loom application remains the necessary exception. It owns Minecraft runtime/session
  coordination, mapped Minecraft capture, client UI,
  Vulkan interception/device integration, resource loaders, Fabric/NeoForge entrypoints and discovery, mixins,
  and final composition. Its guarded publish-once `CausticaClientComposition.current()` is the entrypoint/mixin
  bridge; it does not make `renderer-runtime` a service-locator package.
- `MinecraftFrameSelector`, `MinecraftFrameSelectionInstaller`, `MinecraftFrameCaptureInstaller`,
  `MinecraftFrameCaptureState`, and `MinecraftEntityCaptureBinding` are now host-free types in
  `minecraft-rendering`; `minecraft-client` implements them against live Minecraft state.
- the API showcase is a non-loader API consumer on the real `MinecraftExtension`/world-session lifecycle.
  Its post and UI passes own and record shader objects. Its world-resource pass records a device-addressable
  buffer upload and barrier, waits for frame completion, then atomically publishes a mesh and placement with
  independent retirement. Its executable fixtures also cover resource/sampler descriptor allocation and
  writes, publish-before-retire replacement through `GpuDevice.retireAfterUse`, program ready/failure/cancel
  outcomes, retained mesh replacement, cross-scene instance movement, and independent multi-scene targeting. It
  cannot launch as a standalone mod.

Artifact ownership is also visible in Java namespaces. Host-free material content lives under
`dev.comfyfluffy.caustica.minecraft.content`, reusable Minecraft rendering lives under
`dev.comfyfluffy.caustica.minecraft.rendering`, the mapped application lives under
`dev.comfyfluffy.caustica.minecraft.client`, and live renderer orchestration lives under
`dev.comfyfluffy.caustica.renderer.runtime`. This prevents separate artifacts from appearing to co-own one
flat implementation namespace; the host SPI remains under `dev.comfyfluffy.caustica.spi.host`.

Device interception, loader entrypoints, mixins, and composition are application responsibilities in the
`minecraft-client` Loom package rather than reusable library responsibilities.

## Contract decisions

### Sessions and programs

Each factory receives a fresh session context. GPU, compute, program, geometry, light, resource, and pass
capabilities are session-owned and must not be cached in process-lived extension objects. Runtime state is
reached by explicit construction and ownership; `packages/renderer-runtime` has no runtime singleton,
current-composition lookup, or renderer-composition locator.

Shutdown stops producers and pass callbacks, invalidates contribution-owned state, then crosses one explicit
retained-scene settlement boundary before owner drains. That boundary lets the backend accept or complete
native retained publications after the ordinary frame loop has stopped; drainage no longer depends on another
render frame arriving. Compute terminals, pass uses, program callbacks, retained retirements, and final
contribution close follow.

One `ProgramRegistration` atomically declares an owner's coherent surface, coverage, volume, and environment
set. Its readiness result covers the complete registration and is pending, ready, failed, or cancelled.
Closing the registration removes the entire set. Per-object mutation, exact intermediate composition lookup,
and feature-slot activation are outside the current lifecycle.

Typed surface, volume, and environment IDs remain at the public boundary. After a composition is published,
the engine resolves them directly to plain non-negative implementation indices for retained snapshots and hot
rendering paths. Index zero is reserved for the built-in error surface, vacuum volume, or error environment as
appropriate. A declaration receives a stable session-lifetime slot in its program kind; removing another
owner leaves that slot unchanged rather than compacting or retargeting retained scene data. Each registration
carries its current published-membership bit, so resolution does not scan the published registration list.
There is no parallel object hierarchy for an already-resolved program index.

Mesh, instance, and light IDs remain owner-local mutation capabilities. A `LightId` may additionally cross an
owner boundary as a same-session, non-owning `PrimitiveLightMap` selection; it grants no light mutation
authority and does not pin its issuer. Explicitly handed-off same-session scene and program IDs are likewise
non-owning selections.

### Scene views and environments

Every `SceneView` contains a host-issued `SceneId`, a pose-only `Camera`, and one mandatory homogeneous medium
containing the primary-ray origin:
`ViewMedium.Vacuum` or a typed `ViewMedium.Volume`. Minecraft performs water-containment policy; renderer
transport only consumes the selected volume. There is no public scene creation, closure, portal-link, or
arbitrary medium-stack authority.

Environment selection is an owner-slot model for each scene:

1. a successful `select` replaces that owner's slot and makes it the latest active selection;
2. reselection by an existing owner moves that owner to latest precedence;
3. invalidating a dormant owner leaves the active environment unchanged;
4. invalidating the active owner restores the most recently selected surviving owner deterministically;
5. a displaced binding retires only after it is absent from owner slots and no published GPU snapshot can
   still reference it; owner drainage waits for the associated retirement callbacks.

This is selection and restoration, not a global environment registry or hardcoded sky slot. A scene with no
surviving selection uses built-in environment implementation zero.

### Retained geometry, lights, and passes

Retained geometry and light batches publish atomically. Geometry additionally requires grouped publication:
several independently retired batches validate, enter the accepted native publication, and become visible
together. The showcase proves the use case by grouping a resident mesh buffer with its first placement while
attaching buffer ownership only to the mesh batch. Accepted snapshots are ordered by revision; displaced
resources retire after their tracked GPU use. Producers own and synchronize resident input buffers; the
renderer owns acceleration policy, TLAS insertion, descriptor publication, and retirement tracking.

The public light set is one-sided parallelogram radiance in cd/m², circular-spot intensity in candela, and
distant normal illuminance in lux. `LightDescriptor.Parallelogram` names the geometry actually sampled by the
GPU: two non-collinear half-axis spans need not be perpendicular, which is required for skewed emissive faces
such as transformed lava surfaces. Calling this shape a rectangle would impose a false public invariant. The
  ray tracer consumes all three through persistent per-scene double buffers: per-pixel feedback reservoirs, a
  global discrete distribution, jittered local histograms enabled for stationary screen-space history, mixture
  proposal PDFs, candidate RIS, and reverse MIS. CPU property coverage checks global normalization and boundary
ownership, local histogram probing/addressing, history validity and identity continuity, and agreement with
shader constants and branch edges. This is static algorithmic evidence, not a live lighting result.

"RTXPT-style" identifies the public algorithm family only. The Caustica NEE-AT records, feedback encoding,
identity remapping, tiled distributions, proposal mixture, RIS estimator, and reverse-MIS integration are an
independent implementation derived from public algorithm descriptions. No NVIDIA source, shader code, private
headers, or binary-derived implementation detail is part of this implementation, and the review does not claim
source, binary, conformance, or output equivalence with RTXPT.

World-resource passes record before tracing. Post and UI passes have stage-local IDs and one optional
before/after anchor. An anchor may name a pass owned by another contribution in the same stage; it conveys
ordering only, not ownership or lifetime. Missing anchors are unconstrained and acceptance order resolves
remaining ties. The same textual ID in another stage is unrelated, not a cross-stage edge. Duplicate live IDs
within one stage, self-anchors, and cycles are rejected. Pass instances borrow the live command buffer and bound
descriptor heaps, and close after submitted uses drain.

There is no public or internal lifecycle built around a global surface-modifier chain. Block damage travels
through explicit Minecraft instance/material data; program, material, and pass resources follow their owning
session/package lifecycles.

The showcase's retained world buffer proves producer-to-renderer GPU-resource handoff and retirement. Its
descriptor-table fixture allocates and writes typed resource/sampler ranges, publishes replacement indices,
and retires displaced ranges through `GpuDevice.retireAfterUse`. `features-builtin/BloomPass` exercises the
same rule with live `VmaImage2D` levels.

## Vulkan and shader decisions

The required profile is Vulkan 1.4 with unified image layouts, descriptor heaps, synchronization2, and shader
objects for compute/raster. Ray stages remain `VK_KHR_ray_tracing_pipeline`. One mapped resource heap and one
mapped sampler heap are bound for renderer command streams. Images and samplers use direct heap access. An
acceleration structure is the deliberate exception: SPIR-V declares a conventional binding and shader or
pipeline creation maps it to a heap index stored in pushed data with
`VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT`. This still uses no descriptor-set layout or bind
command. Heap-native code records its data with `vkCmdPushDataEXT`.

The Minecraft host also requires the instance extension `VK_KHR_get_surface_capabilities2` and queries
format/color-space pairs with `vkGetPhysicalDeviceSurfaceFormats2KHR`,
`VkPhysicalDeviceSurfaceInfo2KHR`, and `VkSurfaceFormat2KHR`. `VK_EXT_swapchain_colorspace` remains optional;
when available it exposes the extended color-space enums used to select HDR-capable pairs.

`GpuDescriptorHeapProperties` exposes only the implementation facts an extension currently needs:
`resourceDescriptorStrideBytes`, `maximumResourceAllocation`, and `maximumSamplerAllocation`. The stride is
in bytes and both maximum allocations are descriptor-slot counts. Sampler stride, heap alignments, and total
capacities remain backend details. Descriptor indices remain 32-bit shader values; device-address ranges carry
a typed address plus explicit byte size.

Opacity micromaps are not an active geometry or acceleration path in this checkout. The old compute encoder,
shader, reflected record, pipeline wrapper, producer hints, and retained-build integration are absent. Cutout
coverage remains a surface-program/any-hit concern; no public OMM contract should be inferred from it.

`ShaderObjectGraphics` is a general Vulkan-native vertex/fragment shader-object helper, not a fullscreen-only
wrapper. Its immutable state describes dynamic vertex bindings and attributes, topology, rasterization,
multisampling, depth, and attachment-zero blending. Either stage may additionally map conventional SPIR-V
resources to pushed descriptor-heap indices; the showcase exercises the acceleration-structure mapping.

The fixed and Minecraft world compositions share the reflected 136-byte `WorldBindingRoots` ABI: four device
addresses, the pushed TLAS heap index used by the acceleration-structure binding mapping, fourteen direct
storage-image heap indices, initial-volume state, the NEE-AT state address, and NRD signal encoding. The
selected environment implementation and binding word live in the reflected addressable `WorldPush`. The
general surface-modifier and unused speculative roots are absent.

Presentation handoffs use semantic records rather than positional handle lists. `BorrowedImage` keeps an
image, view, format, and extent together; `PresentationSwapchain` carries the immutable borrowed image table; and
`AcquiredSwapchainTarget` keeps the selected image and extent with its acquire/present synchronization.
LWJGL `Vk...` wrappers are used for dispatchable handles such as `VkDevice`. Vulkan swapchains, images, image
views, samplers, and semaphores remain `long` values where an actual non-dispatchable handle is required or
borrowed because LWJGL represents those handles as scalars; their semantic record supplies the missing
type-level context. Descriptor-heap-native resources do not manufacture handles solely to encode a descriptor:
`VulkanSampler` writes `VkSamplerCreateInfo` directly into a sampler range and owns no `VkSampler`, while
`RtToneLut` writes a sampled-image descriptor from `VkImageViewCreateInfo` and owns no `VkImageView`. The LUT
retains only its VMA image allocation and sampled/sampler descriptor ranges.

## Compile-time Slang decision

`packages/slang-tooling` is build infrastructure and is separate from the engine's in-process
`slang-runtime`. Its compile, raw-reflection, and typed-record tasks accept directory includes and packaged
shader-module JARs. JAR inputs are deterministically expanded into task-local include roots containing only
`.slang` resources, so an extension can consume an artifact-backed shader package without reading another
project's source tree or depending on its checkout layout. The API showcase resolves the
`caustica-shader-api` runtime artifact for both shader compilation and reflection/record generation.

A source checkout can make the Gradle plugin available with `pluginManagement.includeBuild`.
Normal versioned plugin-marker resolution additionally requires that the marker be published to a repository
configured by the consumer; no public marker repository is claimed here.

## Package decision table

| Project | Current responsibility |
|---|---|
| `api`, `api-support` | Vulkan-native extension values and consumer helpers |
| `settings-api`, `shader-api` | Settings contracts and public Slang ABI modules |
| `config` | Root-facing persisted renderer configuration |
| `engine`, `engine-vulkan` | Renderer-generic ownership plus Vulkan execution/backend ownership |
| `vulkan-support` | Reusable VMA/upload/synchronization helpers |
| `renderer-raytracing` | Program composition, retained scene acceleration, transport, and NEE-AT |
| `renderer-presentation` | Exposure and SDR/HDR presentation |
| `renderer-runtime` | Instance-owned live renderer orchestration and host callback SPI |
| `renderer-denoising` | Temporal-denoiser frame contract and borrowed Vulkan resource boundary |
| `nvidia-ngx` | NGX/DLSS integration |
| `nvidia-nrd` | Pinned NRD/NRI Vulkan backend and native packaging |
| `features-builtin` | Built-in programs and passes through extension-facing contracts |
| `minecraft-api`, `minecraft-adapter` | Minecraft extension/world-session lifecycle and engine bridge |
| `minecraft-content` | Host-free material analysis and page planning |
| `minecraft-rendering` | Host-free frame/capture seams and Minecraft-independent rendering/GPU publication |
| `minecraft-client` | Loom host, mapped Minecraft integration, loaders, mixins, UI, and final composition |
| `slang-runtime`, `slang-tooling` | Slang runtime and reusable compile/reflection/generation tooling |
| `examples/*` | Strict public-contract consumers for API and glTF integration pressure |

## Acceptance decision

Static package, dependency/import, lifecycle, shader/reflection, ABI, native NRD, and example-consumer gates are
green. Direct Fabric launches enter `ray-tracing-test-place` and exercise exact RAW, NRD RELAX, NRD REBLUR, and
release-DLL DLSS Ray Reconstruction routes. `frame.rawCopy`, `frame.nrd`, and `frame.dlssRr` telemetry confirms
route exclusivity. Final and stable-plane debug captures contain the rendered world, and the tested routes shut
down without an actionable Vulkan validation, NRI, NRD, device-loss, or NGX failure.

Visual review covers Minecraft nearest atlas sampling, authored cutout alpha, material-derived coloured
emission, primary-emission/NEE separation, temporally continuous light publication, first-person player-body
exclusion, and the ordinary hand/UI path. Primary cutout visibility is deterministic so unfiltered environment
and emission residuals do not flicker, while secondary stochastic coverage remains part of path transport.

NEE-AT legitimately converges faster when the camera approaches a lit surface because the emitter occupies a
larger solid angle and the screen-space proposal has more useful local history. That should reduce variance,
not change the estimator's mean. Atomic geometry/light publication and stable retained-light identities remove
the renderer-side cause of several-frame black-light refreshes.

Camera containment and fluid meshing share the same corner-height and two-triangle surface rule. Quantitative
water absorption/refraction and physical flowing-fluid edge behavior remain separate validation work rather
than a package/API rewrite blocker.

## Final decisions

- Keep one Vulkan-native extension API and Minecraft lifecycle in separate packages.
- Keep renderer runtime state instance-owned; retain the `minecraft-client` publish-once composition bridge solely for Loom
  host hooks and mixins.
- Keep atomic owner-scoped program registration and owner-scoped retained retirement.
- Keep deterministic owner-slot environment precedence, restoration, and drainage semantics.
- Keep scene targeting but defer public scene administration and portal traversal. The compatibility claim ends
  at identity and lifetime isolation: each trace still selects exactly one entry-scene TLAS. Simultaneous portal
  traversal requires a new multi-scene trace ABI and is not implemented or demonstrated by the examples.
  Showcase targeting plus renderer content-partition tests prove only that independent scene state can coexist;
  they do not render both scenes through a portal. Minecraft's current `PortalSurface` is an emissive end-portal
  material model and does not switch ray scenes.
- Keep mandatory `SceneView.medium()` and Minecraft-owned primary-origin containment policy.
- Keep stage-local pass ordering rather than exposing a general render graph.
- Keep the three-shape NEE-AT light contract experimental while quantitative convergence tuning continues.
- Keep opacity micromaps outside the active contract until a concrete producer and retained-build use case
  justify reintroducing them.
- Keep the default route on stable-plane DLSS Ray Reconstruction; keep RAW and NRD as independent routes with no
  Streamline dependency.
