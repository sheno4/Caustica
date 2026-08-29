# Caustica rewrite API review

Date: 2026-08-30
Scope: architecture, API, and implementation reconciliation
Status: current review and decision record; focused package checks are green, physical acceptance pending

## Executive decision

Continue integration against the implemented experimental API. Complete every static gate, then perform the
physical Minecraft validation-layer, visual, and performance gates before stabilizing the contract.

The main API remains deliberately Vulkan-native. Reusable code should be described as renderer-generic or
Minecraft-independent rather than GPU-neutral. Vulkan command buffers, device addresses, descriptor
heaps, and retirement are intentional extension contracts; Minecraft lifecycle and host types remain separate.

## Implemented decisions

- `packages/api` exposes process registration, owner-scoped render sessions, atomic program registration,
  retained geometry/lights, views, staged passes, and typed Vulkan resource contracts.
- `packages/engine` owns renderer-generic session, program, retained-scene, environment-selection, and pass
  state. `packages/engine-vulkan` owns Vulkan profile validation, mapped heaps, VMA resources, submission
  retirement, diagnostics, and the renderer-backend SPI.
- `packages/renderer-raytracing` owns program composition, retained acceleration structures, path transport,
  NEE-AT, and Vulkan opacity-micromap acceleration. `packages/renderer-presentation` owns display composition.
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
  Its post and UI passes own and record shader objects, while its world-resource pass currently performs only
  pre-trace readiness and environment publication. It cannot launch as a standalone mod.

Device interception, loader entrypoints, mixins, and composition are application responsibilities in the
`minecraft-client` Loom package rather than reusable library responsibilities.

## Contract decisions

### Sessions and programs

Each factory receives a fresh session context. GPU, program, geometry, light, and pass capabilities are
session-owned and must not be cached in process-lived extension objects. Runtime state is reached by explicit
construction and ownership; `packages/renderer-runtime` has no runtime singleton, current-composition lookup,
or renderer-composition locator.

One `ProgramRegistration` atomically declares an owner's coherent surface, coverage, volume, and environment
set. Its readiness result covers the complete registration and is pending, ready, failed, or cancelled.
Closing the registration removes the entire set. Per-object mutation, exact intermediate composition lookup,
and feature-slot activation are outside the current lifecycle.

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

Retained geometry and light batches publish atomically. Accepted snapshots are ordered by revision; displaced
resources retire after their tracked GPU use. Producer callbacks describe content and lifetime while the
renderer owns upload, acceleration policy, TLAS insertion, descriptor publication, and retirement.

The public light set is rectangle radiance in cd/m², circular-spot intensity in candela, and distant normal
illuminance in lux. The ray tracer consumes all three through persistent per-scene double buffers: a global
discrete distribution, tiled local histograms derived from previous-frame light and pixel feedback, mixture
proposal PDFs, candidate RIS, and reverse MIS. CPU property coverage checks global normalization and boundary
ownership, local histogram probing/addressing, history validity and identity continuity, and agreement with
shader constants and branch edges. This is static algorithmic evidence, not a live lighting result.

World-resource passes record before tracing. Post and UI passes have stage-local IDs and one optional
before/after anchor. Missing anchors are unconstrained; acceptance order resolves remaining ties. Duplicate,
self, cross-stage, and cyclic constraints are rejected. Pass instances borrow the live command buffer and
bound descriptor heaps, and close after submitted uses drain.

There is no public or internal lifecycle built around a global surface-modifier chain. Block damage travels
through explicit Minecraft instance/material data; program, material, and pass resources follow their owning
session/package lifecycles.

## Vulkan and shader decisions

The required profile is Vulkan 1.4 with unified image layouts, descriptor heaps, synchronization2, and shader
objects for compute/raster. Ray stages remain `VK_KHR_ray_tracing_pipeline`. One mapped resource heap and one
mapped sampler heap are bound for renderer command streams. Images and samplers use direct heap access. An
acceleration structure is the deliberate exception: SPIR-V declares a conventional binding and shader or
pipeline creation maps it to a heap index stored in pushed data with
`VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT`. This still uses no descriptor-set layout or bind
command. Heap-native code records its data with `vkCmdPushDataEXT`.

`GpuDescriptorHeapProperties` uses explicit byte units:

- `resourceDescriptorStrideBytes` and `samplerDescriptorStrideBytes`;
- `resourceHeapAlignmentBytes` and `samplerHeapAlignmentBytes`.

Capacities and maximum allocation counts remain slot counts. Descriptor indices remain 32-bit shader values;
device-address ranges carry a typed address plus explicit byte size.

The dead opacity-micromap compute encoder program, its shader, reflected push record, and pipeline wrapper have
been removed. Vulkan opacity-micromap acceleration remains active in retained geometry through producer hints
and the Vulkan acceleration-structure path. Documentation should say opacity-micromap acceleration, not imply
that the deleted encoder is still a program stage.

The fixed and Minecraft world compositions retain the reflected 96-byte `WorldBindingRoots` ABI: four device
addresses, the pushed TLAS heap index used by the acceleration-structure binding mapping, seven direct
storage-image heap indices, initial-volume state, and the NEE-AT state address. The selected environment
implementation and binding word live in the reflected 320-byte addressable `WorldPush`. The general
surface-modifier and unused speculative roots are absent.

## Package decision table

| Project | Current responsibility |
|---|---|
| `api`, `api-support` | Vulkan-native extension values and consumer helpers |
| `settings-api`, `shader-api` | Settings contracts and public Slang ABI modules |
| `config` | Root-facing persisted renderer configuration |
| `engine`, `engine-vulkan` | Renderer-generic ownership plus Vulkan execution/backend ownership |
| `vulkan-support` | Reusable VMA/upload/synchronization helpers |
| `renderer-raytracing` | Program composition, retained scene acceleration, transport, NEE-AT, opacity micromaps |
| `renderer-presentation` | Exposure and SDR/HDR presentation |
| `renderer-runtime` | Instance-owned live renderer orchestration and host callback SPI |
| `nvidia-ngx` | NGX/DLSS integration |
| `features-builtin` | Built-in programs and passes through extension-facing contracts |
| `minecraft-api`, `minecraft-adapter` | Minecraft extension/world-session lifecycle and engine bridge |
| `minecraft-content` | Host-free material analysis and page planning |
| `minecraft-rendering` | Host-free frame/capture seams and Minecraft-independent rendering/GPU publication |
| `minecraft-client` | Loom host, mapped Minecraft integration, loaders, mixins, UI, and final composition |
| `slang-runtime`, `slang-tooling` | Slang runtime and reusable compile/reflection/generation tooling |
| `examples/*` | Strict public-contract consumers for API and glTF integration pressure |

## Acceptance decision

Focused package, shader, reflection, ABI, and example checks are green. The remaining order is:

1. complete the package, import/dependency, lifecycle, unit, shader-compilation/reflection, ABI, and example
   consumer gates;
2. launch Minecraft with Vulkan validation enabled and resolve validation/runtime errors;
3. review deterministic screenshots for geometry, sky, materials, water, UI overlays, and all three light types;
4. run without validation and measure default-resolution performance.

No physical launch, visual result, validation-layer result, or frame-rate result is asserted by this review.

## Final decisions

- Keep one Vulkan-native extension API and Minecraft lifecycle in separate packages.
- Keep renderer runtime state instance-owned; retain the `minecraft-client` publish-once composition bridge solely for Loom
  host hooks and mixins.
- Keep atomic owner-scoped program registration and owner-scoped retained retirement.
- Keep deterministic owner-slot environment precedence, restoration, and drainage semantics.
- Keep scene targeting but defer public scene administration and portal traversal. Current compatibility is
  identity and lifetime isolation only; simultaneous portal traversal requires a new multi-scene trace ABI.
- Keep mandatory `SceneView.medium()` and Minecraft-owned primary-origin containment policy.
- Keep stage-local pass ordering rather than exposing a general render graph.
- Keep the three-shape NEE-AT light contract experimental through physical validation.
- Keep Vulkan opacity-micromap acceleration while removing the unused encoder program.
- Complete static gates before claiming any physical acceptance.
