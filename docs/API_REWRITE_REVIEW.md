# Caustica rewrite API review

Date: 2026-08-29
Scope: architecture, API, and implementation reconciliation
Status: living review checked against the rewrite implementation

## Implemented API slice

The public-contract refactor, engine/Vulkan foundations, retained transport, and live frame path are applied:

- API, API support, settings, shader API, Minecraft API, Minecraft content, engine, engine Vulkan, Vulkan
  support, built-in features, examples, and reusable Slang tooling now live under `packages/`;
- program implementations are declared through one atomic owner-scoped `ProgramRegistration`, with typed
  exports and one pending/ready/failed/cancelled readiness result; compiler failure is isolated per accepted
  registration, and close is linearized against readiness publication;
- public scene creation/administration is removed while opaque `SceneId` targeting remains;
- the engine retains internal per-`SceneId` scene/TLAS/lighting state for future simultaneous portal scenes,
  but the public API contains no placeholder portal link or traversal policy;
- mesh/instance/light IDs remain owner-local mutation capabilities, while explicitly handed-off scene,
  surface, volume, and environment IDs are same-session non-owning references;
- Vulkan device-address ranges and resource/sampler descriptor indices are typed Java values, while LWJGL
  non-dispatchable handles remain documented `long` values;
- the engine implements render-session, program-publication, retained scene/light, pass, Minecraft world,
  descriptor-heap, and teardown lifecycles with owner-scoped tests;
- the fixed and Minecraft ray shader compositions implement ABI 6 and validate as Vulkan 1.4 SPIR-V using
  direct descriptor-heap access;
- the root owns one mapped resource heap and one mapped sampler heap, and its ray pipeline uses heap-native
  push data with no descriptor-set compatibility path;
- the compile-only API showcase uses the new registration, host-issued scene, cross-contribution export, and
  Vulkan range/index contracts;
- the glTF viewer is a strict public-artifact consumer: its Minecraft world contribution owns one atomic
  program set, VMA uploads, retained meshes/placements, resource-epoch replacement, and drained teardown;
- the native retained-scene backend now accepts complete snapshots in revision order, builds reusable BLAS
  candidates, keeps a TLAS ring per `SceneId`, and retires displaced snapshots after their tracked graphics use.
- frame ingress now carries `SceneView`/`Camera` and an optional typed initial volume; Minecraft performs only
  the water-containment policy and hands a session-scoped selection to the renderer, and ray generation
  initializes transport from that selection;
- retained rectangle, circular spot, and distant lights are sampled by the path tracer through a persistent
  per-scene NEE-AT distribution with reverse-MIS feedback;
- the native pass scheduler now borrows the live `VkCommandBuffer` and frame reservation, rebinds descriptor
  heaps per invocation, validates the two-image post chain, and drains pass uses through frame retirement.

Package checks, the API showcase, glTF boundary/lifecycle tests, shader compilation and reflection gates, and
focused engine/runtime tests exercise these paths. Physical launch, validation-layer, visual, and performance
acceptance remain the final proof rather than a reason to describe implemented API paths as absent.

## Feedback proven during implementation

- Program composition needs one explicit GPU implementation-data table. `WorldBindingRoots` now contains only
  live addresses, eight descriptor-heap indices, initial-volume state, and NEE-AT state. Slang reflection fixes
  its scalar-layout offsets and total push-data size at 96 bytes; unused instance-history/material/reserved
  roots were removed. Surface, volume, and environment implementation indices remain category-local and
  deterministic.
- Settings declaration is process state; active composition is render-session state. The settings screen now
  reads only `settings-api` and no longer recreates a global slot registry. A future diagnostic can display a
  session snapshot without becoming a mutation API.
- Core correctly cannot hand every contribution an invented "root scene." The physical Minecraft API now
  supplies a borrowed host-owned `SceneId` with dimension and resource-pack epochs, and owns dimension-keyed
  environment selection. This is the concrete handoff required by terrain, sky, and anchored extension content.
- `VK_EXT_shader_object` is the compute/graphics pass path, not the ray-stage path. Current Vulkan valid usage
  rejects raygen, hit, miss, intersection, and callable stages in `VkShaderCreateInfoEXT`; the world renderer
  therefore keeps `VK_KHR_ray_tracing_pipeline` while using the descriptor-heap pipeline flag, null layout,
  and `vkCmdPushDataEXT`.
- Current world SPIR-V declares `SPV_EXT_descriptor_heap` and has no `DescriptorSet` or `Binding` decorations,
  so the ray pipeline needs no descriptor mapping structures. This is checked from disassembly rather than
  assumed from source syntax.
- Camera submersion proves an internal initial-volume frame input, not a public camera-volume feature. The
  Minecraft adapter now selects water at frame ingress after its volume ID and binding resources are live.
  Core cannot reject a zero `ShaderData` word generically because a schema may encode descriptor index zero or
  a packed scalar; the Minecraft selector separately requires its address-backed records to be live.
- The general surface-modifier hook remained unjustified. Block damage moved into explicit Minecraft instance
  data, removing the cross-owner modifier dispatch and its separate world table pass.
- Rounded clouds were removed from the rewrite and its test surface as requested.
- A generic/Minecraft dual contribution originally needed a global current-session reference in the glTF
  consumer. Moving its program registration into the Minecraft world contribution removed that correlation
  entirely and proved that no public session key is needed for this use case.
- Minecraft-only extension discovery is now separate for Fabric and NeoForge. Dual-capability objects are
  deduplicated by identity; a Minecraft extension no longer has to pretend to be a generic extension merely
  to be discovered.
- Descriptor-heap compute helpers reject SPIR-V carrying `DescriptorSet` or `Binding` decorations before
  calling Vulkan. This turns a mapping-related validation error into a synchronous package invariant.
- Retained native publication is accepted or rejected synchronously. A device/execution failure discovered
  after submit acceptance is session-fatal; the API does not expose an unproven per-mesh recovery graph.
- `packages/engine` now owns renderer-generic ownership/lifecycle state, while `packages/engine-vulkan` owns
  Vulkan profile validation, queues, mapped heaps, VMA-backed resources, GPU submission retirement, and the
  renderer-backend SPI. This is a real dependency boundary, not an attempt to make the public API GPU-neutral.
- `packages/minecraft-content` owns host-free material analysis and page planning/compilation behind pixel
  callbacks, so reusable content logic imports neither Minecraft runtime classes nor renderer internals.
  `packages/minecraft-api` owns loader-neutral world/resource epochs and Minecraft shader-data identity tokens;
  live host capture remains in the adapter/root composition.

## Executive decision

Continue integration and physical validation against the implemented package APIs, while keeping them
experimental until the launch, validation-layer, visual, and performance gates pass.

The draft API has a good low-level core: process registration is separated from render-session lifetime,
session-owned objects have explicit retirement, retained geometry and lights publish atomically, and shader
data has a useful typed-but-opaque representation. Those decisions should survive the rewrite.

The boundary is proven for session/program ownership, strict external-style glTF geometry, Vulkan upload
lifetime, descriptor-heap compute, engine-side retained publication, initial camera volume, retained-light
sampling, and pass recording. A real Minecraft launch remains the end-to-end acceptance gate.

The following decisions steer the remaining integration:

1. Make the primary extension API explicitly Vulkan-native. Keep Minecraft-specific lifecycle in its own
   package, but do not split command buffers, device addresses, descriptor heaps, or VMA into a ceremonial
   Vulkan add-on: they are part of the intended contract.
2. Keep scene targeting and views in the public shape, but keep scene creation/administration internal for
   this rewrite. This preserves the geometry/light signatures needed by future ray portals without freezing
   an unobservable scene-creation API before portal traversal semantics exist.
3. Add a Minecraft-specific world/dimension/resource lifecycle API. Dimension keys, resource packs,
   Minecraft sky selection, anchors, and client-world epochs do not belong in core.
4. Replace per-object shader-program mutation with one atomic, owner-scoped program registration. Return one
   readiness result for the complete set and remove `SUPERSEDED`, individual drop methods, and exact
   intermediate-composition semantics from the public API.
5. Keep the implemented Vulkan 1.4 profile and reusable Slang build/reflection tooling as the only shader and
   pipeline foundation.

The rewrite should be organized as physical Gradle projects under `packages/...`, with dependency tests
enforcing the boundaries. Package names may change freely; preserving the current `rt`/`spi` layout has no
value by itself.

## Review basis

This review used these consumers and implementation areas as evidence:

- the public Java and Slang contract under `packages/api/`;
- the rewritten strict-boundary glTF viewer under `extensions/gltf-viewer`;
- Minecraft terrain, entity, particle, cloud, material, sky, damage, and overlay implementations;
- renderer lifecycle, geometry, acceleration, material, shader-composition, pass, presentation, and GPU
  lifetime code under `src/main/java`;
- API, renderer, geometry, material, shader, and lifecycle tests;
- the build-time and runtime Slang reflection implementations;
- Vulkan device bring-up and the scans proving removal of descriptor-set, synchronization-1, legacy transfer,
  legacy physical-device-query, and legacy dynamic-state call sites from the rewritten path.

The Vulkan recommendations follow the official Khronos
[deprecated-item guide](https://docs.vulkan.org/guide/latest/deprecated.html),
[legacy functionality specification](https://docs.vulkan.org/spec/latest/appendices/legacy.html),
[`VK_KHR_unified_image_layouts` reference](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_unified_image_layouts.html),
[`VK_EXT_descriptor_heap` reference](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_descriptor_heap.html),
and [descriptor-heap guide](https://docs.vulkan.org/guide/latest/descriptor_heap.html).
The reflection recommendation follows Slang's
[compilation API](https://docs.shader-slang.org/en/latest/compilation-api.html) and
[reflection API](https://shader-slang.org/slang/user-guide/reflection.html).

## What the repository currently proves

### The new API has an implemented session core

The contract is now exercised by production bootstrap/program registration and engine implementations:

- `MinecraftApiBootstrap`, `BuiltinExtension`, and `MinecraftProvidersExtension` use process registration,
  generic render sessions, Minecraft world sessions, separate settings registration, and atomic program sets;
- engine tests execute contribution teardown, program compilation/publication, same-session cross-owner
  selection, retained geometry/light retirement, pass scheduling, dimension scenes, environment selection,
  and resource-pack epochs;
- `RtRuntime` owns one `VulkanDeviceContext` for the installed host device. That context owns the required
  resource/sampler heaps, and device bring-up validates the Vulkan 1.4 feature
  profile before creating the logical device;
- fixed and Minecraft Slang implement the version-6 surface, coverage, volume, and environment interfaces.
- the glTF viewer imports only API/Minecraft API artifacts, owns real VMA acceleration inputs behind retained
  callbacks, and drops geometry before closing its same-contribution program registration.
- Bloom is an executing-shape post-effect implementation using a compute `VkShaderEXT`, descriptor-heap
  indices, `vkCmdPushDataEXT`, unified `GENERAL` images, and synchronization2 barriers.

The retained world renderer now consumes program roots, geometry, initial-volume state, descriptor-heap frame
images, and light distributions on the live frame path. API and engine tests do not replace the final real
client launch, validation-layer scan, screenshot review, and performance measurement.

### Existing engine features used as proof cases

| Existing feature | Correct destination | API pressure it creates |
|---|---|---|
| Terrain/chunk meshes | Minecraft adapter -> retained engine geometry | Thread-safe retained updates, atomic batches, double-precision placement, replacement and retirement |
| Entities and particles | Minecraft adapter, with host frame extraction | Rigid previous transforms, camera-coherent host snapshotting, textures/coverage, frequent placement updates |
| Rounded clouds | Removed from this rewrite | No API pressure; the requested rewrite intentionally omits the feature and its tests |
| Minecraft material/resource packs | Minecraft adapter plus reusable GPU upload support | Resource-pack epochs, source decoding, table replacement, descriptor retirement; not a core material registry |
| glTF content | Reusable glTF scene package plus a Minecraft anchor package | Core geometry/surface APIs should not require Minecraft; anchors and reload are Minecraft lifecycle |
| Opaque/cutout surfaces | Shader API and program composition | Narrow any-hit coverage and full closest-hit material evaluation are both justified |
| Water interior | Surface plus volume model | Homogeneous absorption, refraction, boundary response, and camera-start transport are consumed; nested-medium stacks and refracted shadow connections remain out of scope |
| Camera inside water | Minecraft adapter -> internal frame ingress -> generic volume dispatch | Primary rays must start in the host-selected medium. Submersion and biome colour are Minecraft policy; the initial medium and transport are engine concepts. |
| End-portal/procedural surface | Surface program | Typed implementation/binding/instance roots and time-dependent shader evaluation are justified |
| Minecraft sky LUT | Minecraft dimension/environment package | A dimension-keyed sky is real, but the key, time/weather state, and selection policy are not core |
| Block damage | Minecraft material/surface state plus a world-resource pass | Does not yet justify a generic global surface-modifier dispatch |
| Rectangle/spot/distant lights | Retained light API | All three supported physical shapes are consumed by NEE-AT and reverse MIS; no point-light API is needed |
| Bloom | Ordered post-effect API | Explicit chain input/output and stage-local registration order are sufficient for the current producer |
| Name tags/outlines/glow | UI pass API | Display-resolution layer, root TLAS descriptor, unjittered view, and deterministic stage order |
| DLSS RR/FG, HDR, presentation | Engine-NVIDIA and Minecraft-Vulkan integration | Internal backend capabilities; not extension-facing scene API |
| Screenshot/export/telemetry | Engine diagnostics plus host/client presentation | Engine owns render-target export and timing collection; Minecraft owns UI, commands, and output path choice. None require public scene mutation authority. |

## API verdicts

The test is not whether a type can be imagined. It is whether an existing or deliberately selected example
requires it and whether a narrower alternative is worse.

| API area | Verdict | Evidence and required change |
|---|---|---|
| `CausticaExtension` + process factory registration | Keep current capability registration | Process lifetime can outlive every Vulkan device. Contribution ownership is internally sequenced and diagnostics can report its scope. A public stable contribution ID is deferred until a real consumer must address another contribution by name. |
| `RenderSessionContext` and `stop -> drain -> close` | Keep | This is the strongest part of the design. Device objects cannot be cached by process factories, and final cleanup must follow GPU retirement. Keep the exact no-blocking rule. |
| Separate settings catalog | Keep as an independent add-on | Settings are process/UI state, not renderer state. No current producer needs a public cross-catalog contribution identity. Add shared stable IDs only when diagnostics or another consumer must correlate settings and a live render contribution. |
| `SceneId` as a non-owning target | Keep | Geometry, lights, and a view need one identity for a TLAS-backed coordinate/lifetime domain. Keeping this target in retained operations avoids a later geometry API break when rays can traverse another scene. It must be issued by the engine/session that owns the scene. |
| Public `SceneChannel.create` / `SceneHandle.close` | Move internal for this rewrite | An extension-created scene cannot currently become a rendered view or a ray-traversal target. Future ray portals justify the multi-scene data model, but not this administration contract: portals also need a link transform, destination lifetime, shader-visible traversal target, hop policy, and environment/light rules. Keep the engine implementation multi-scene-capable and add the correct public creation/link capability with the portal feature. |
| `SceneView` and `Camera` values | Keep where consumed | Internal frame ingress and `PassFrame` carry one coherent view, timing, physical scale, and render extent. Do not put Minecraft dimension keys in it or duplicate the value in APIs that do not consume it. |
| Initial camera/view volume | Keep internal frame ingress, not a new feature channel | Underwater rendering proves that ray generation sometimes starts inside a volume. `Camera` remains pose/projection only. Minecraft detects submersion and selects the water binding; raygen consumes one optional typed initial volume and otherwise starts in vacuum. Do not publish an arbitrary medium stack until nested camera-start volumes have a real producer. |
| `GeometryChannel` + issued ids + `RetainedBatch` | Keep | Terrain, glTF, and entities need retained, atomic replacement. Keep meshes scene-independent and placements scene-targeted. Add support upload/build authoring rather than expanding core with Minecraft materials. |
| Geometry cross-contribution restrictions | Remove the same-owner selection restriction | Keep mesh/instance/light mutation IDs owner-local, but allow `SceneId`, `SurfaceId`, `VolumeId`, and `EnvironmentId` to cross contributions as same-session, non-owning references. The issuer retains removal authority; stale program IDs use the documented error/vacuum fallback and do not pin another contribution. Validate session identity and generated shader-data schema tokens. No implicit lease or global registry is needed. |
| `LightChannel` and three physical light shapes | Keep experimental through visual validation | Rectangle radiance in cd/m², circular-spot intensity in candela, and distant illuminance in lux all have retained records and a real NEE-AT consumer. Point was removed because no producer required a fourth shape. |
| Typed `ShaderDataType<T>` / `ShaderData<T>` | Keep the checked ABI word, simplify construction | A root may be a Vulkan device address, descriptor index, or packed scalar, so it cannot be replaced wholesale by one `Vk...` type. Generate schema tokens and encoders from reflection; make unrestricted raw-bit construction an explicit escape hatch instead of normal consumer code. |
| Surface + optional coverage | Keep | Opaque and alpha-tested geometry require different traversal cost and inputs. Coverage must stay narrower than full surface evaluation. |
| Volume implementation | Keep the narrow consumed ABI | Water proves homogeneous absorption, boundary response, refraction, and camera-start state. Do not add scattering/phase fields or a public medium stack before participating media or nested-volume producers require them. |
| Environment implementation | Keep shader concept; change selection authority | A registered environment cannot become visible without scene administration. Core owns generic environment dispatch; a Minecraft environment provider/catalog chooses a binding per dimension and the Minecraft adapter applies it to its host-owned scene. |
| Registration readiness and exact-composition states | Keep readiness directly on `ProgramRegistration` | A complete owner set is pending, ready, failed, or cancelled. Compiler scheduling is not a useful feature outcome. The compiler may coalesce registrations internally, but a ready result means that complete registration is active and a failed result means none of it published. The direct non-blocking callback preserves callback-thread and teardown guarantees without a second readiness object. |
| One-call-at-a-time `ProgramChannel` updates | Replace with atomic `ProgramRegistration` | A synchronous builder declares all surfaces, volumes, and environments for one owner, returns a caller-defined typed export record of IDs, and commits once. Closing the registration removes the entire set; session scope closes it automatically. Hot replacement registers a new set, waits until ready, republishes geometry/environment bindings, then closes the old set. No existing producer proves a need for six independent add/drop operations. |
| Post/UI passes in registration order | Keep stages; defer public IDs/constraints | The current built-in producer and two-image post chain need deterministic stage-local registration order, which the scheduler supplies. No independent producer currently proves public `before`/`after` addressing. Add stable pass IDs only with that use case; do not expose a speculative render graph. |
| World-resource pass | Keep | Sky LUTs, damage tables, material/texture tables, and extension buffers need synchronized work on the engine-owned command stream before trace. Put `SceneView` on its frame so per-view/per-dimension resources are identifiable. |
| `VkCommandBuffer`, `VkDevice`, VMA, descriptor heaps | Keep in the main API | Vulkan is an intentional API dependency. Use LWJGL `Vk...` wrapper classes for dispatchable handles, expose direct Vulkan recording contracts through `api.vulkan`, and keep Minecraft types in a separate package for lifecycle/ownership reasons rather than GPU neutrality. |
| `GpuDescriptorRange` + retirement | Keep | Immutable submitted descriptor bytes plus allocate/publish/retire is the right rule. Use typed heap/index handles and expose capacity/alignment facts needed by real allocators. |
| `GpuDescriptorWriter.writeResource(VkResourceDescriptorInfoEXT)` | Keep the Vulkan struct; add conveniences only where repeated | The call consumes the struct synchronously, so its native lifetime is tractable and Vulkan dependency is intended. A support package may provide safe builders for common buffer/image cases. Acceleration structures remain raw `long` handles in LWJGL; no `VkAccelerationStructureKHR` wrapper class exists. |
| `api.host.CausticaBootstrap` | Move internal | It wires a host implementation and is not an extension capability. The same applies to `spi.host`, `spi.vulkan`, queues, swapchain, and device-negotiation types. |
| General surface-modifier dispatch | Do not add yet | Block damage is the only proof and is Minecraft-specific. Keep it in the Minecraft surface/material data path until an independent package needs cross-owner modifier composition. |
| Ray portals and cross-scene traversal | Reserve the scene-targeting shape, defer the feature | The desired portal swaps the ray's traversal scene at a surface while both scenes remain resident. Do not add a placeholder surface field yet: the feature needs source/destination transforms including previous-frame motion, a shader-visible TLAS target, hop/cycle policy, destination teardown behavior, and lighting/environment rules. Keeping `SceneId` in views and retained content is sufficient forward compatibility; public scene creation and `PortalLink` can be added together later. |

### Simpler program ownership

The previous channel exposed six add/drop operations, one ID/readiness pair per add, and the exact intermediate
composition state of an internally coalescing compiler. Existing producers instead declare coherent sets of
shaders: Minecraft registers its related surfaces, coverage, volume, and environment together, and the glTF
viewer registers its material family together. Model that ownership directly:

```java
interface ProgramChannel {
    <E> ProgramRegistration<E> register(Function<ProgramBuilder, E> declaration);
}

interface ProgramBuilder {
    <I, B, N> SurfaceId<B, N> surface(SurfaceDefinition<I, B, N> definition);
    <I, B, N> VolumeId<B, N> volume(VolumeDefinition<I, B, N> definition);
    <B> EnvironmentId<B> environment(EnvironmentDefinition<B> definition);
}

interface ProgramRegistration<E> extends AutoCloseable {
    E exports();
    void whenComplete(Consumer<? super Completion> callback);
}
```

The declaration callback is synchronous and valid only during registration. `E` is a package-defined record
holding its typed IDs, so heterogeneous generic exports need no untyped map. Registration accepts the set
atomically and reports one non-blocking `Ready`, `Failed`, or `Cancelled` completion. Closing removes
the set; session teardown closes it automatically. Coverage stays part of a surface definition, not a fourth
registration category.

Readiness is folded into `ProgramRegistration` itself: `whenComplete` reports its single terminal result and
shares one linearization point with `close`. A separate ticket or polling state added no lifetime or consumer
value and is therefore absent from the API.

Hot replacement remains simple: register the replacement, wait for readiness, atomically republish retained
geometry/environment bindings to its IDs, then close the old registration. Resource-only reloads update the
descriptor-addressed data without recompiling. Do not add per-ID drop again until a real producer must remove
one shader independently while keeping the rest of its registration live.

Compile-time Slang reflection should generate the manifest, schema tokens, and normal root encoders used by
these definitions. Runtime API users should mostly select generated declarations and supply Vulkan-backed
roots, not repeat qualified type names and construct arbitrary 64-bit words by hand.

### What cross-contribution geometry means

A contribution is the automatic ownership scope created for one extension in one render session. The API
separates mutation authority from selection, so reusable shader packages can hand typed program references to
content packages without transferring ownership.

Use two categories instead:

- owner-local mutation keys: `MeshId`, `InstanceId`, and `LightId`; only their issuing contribution may set,
  replace, or drop those objects;
- same-session selection references: `SceneId`, `SurfaceId`, `VolumeId`, and `EnvironmentId`; another
  contribution may name them after an explicit Java handoff, but receives no mutation/removal authority.

For example, a standard-material package A can export a typed record containing
`SurfaceId<MaterialBinding, InstanceBinding>` plus its generated binding/instance encoders. A glTF package B
uses that ID in its mesh, owns the slot and instance roots through its retained batch, and cannot remove A's
program. If A closes, the next program publication makes the stale ID resolve to the error surface. A's
implementation root retires after old programs and GPU work drain; B's mesh and B-owned roots remain retained
but are not dereferenced by the fallback. The reference neither pins A nor delays teardown.

Validation still rejects references from another render session and mismatched reflected schema tokens. Do
not add a global surface registry or an implicit lifetime lease. If B cannot tolerate fallback, the
composition root must coordinate A and B's lifetimes explicitly. This keeps teardown acyclic while permitting
real reusable shader packages.

### Retained-light sampling and NEE-AT

The path tracer consumes every retained light through a clean-room adaptive next-event estimator inspired by
the public RTXPT NEE-AT design, without copying the local proprietary implementation. State is persistent and
double-buffered per `SceneId`, so several resident scenes retain independent histories for eventual portal
work without exposing scene-table details in the public API.

Each frame performs three GPU phases:

1. A 64-lane cooperative reduction builds a global CDF from physical-power priors blended 25/75 with the
   previous frame's visible-contribution feedback. Stable light identities remap history across retained-list
   changes.
2. Each 8x8 screen tile builds a 128-slot open-addressed local histogram and CDF from the previous pixel
   feedback, including edge-tile and empty-history handling.
3. The 8-byte-per-pixel feedback buffer is cleared for the new trace. Sampling uses binary search in the
   global/local CDFs, while the local table permits constant-time PDF lookup for reverse MIS.

Rectangle priors include physical area in m², circular spots use their exact cone solid angle, and distant
lights use a documented 1 m² reference area. Camera cuts, discontinuous frame indices, extent changes, scene
retirement, and absent history reset adaptation. Exact saturating atomic feedback remains intentional because
the ray-tracing stages cannot assume a subgroup shape that would make a portable grouped reduction equivalent.

The estimator supports rectangle, circular spot, and distant lights. A distant light may declare that the
environment renders the same emitter: primary/delta misses preserve the visible sun or moon, while non-delta
secondary paths avoid estimating that linked source twice. Current transport keeps one homogeneous active
medium; straight shadow connections account for its transmittance but do not bend through refractive
boundaries. Nested medium stacks and refracted shadow paths are explicit future transport work, not hidden API
promises.

## Required lifecycle boundaries

### Process and device

The process object discovers packages and registers session factories. Each opened contribution receives an
internal owner scope; the process owns no device address, pass, scene, or program ID. Public stable
contribution descriptors remain deferred until a real cross-package diagnostic or ordering use case exists.

The Minecraft Vulkan host negotiates one immutable required `DeviceProfile` before logical-device creation.
The Minecraft composition root may remain one process facade for mixin entry points, but its device and
session children form an explicit owned object graph rather than package-level "current" singletons:

```text
Minecraft composition root
  -> EngineProcess
      -> EngineDevice
          -> RenderSession
```

`EngineProcess` owns compiler caches and process diagnostics. `EngineDevice` owns queues, allocator,
descriptor heaps, timeline retirement, and enabled logical-device features. `RenderSession` owns program
composition, scenes, retained content, passes, and frame state.

### Host frame ingress

The rewrite needs an internal, atomic host call resembling:

```java
renderSession.beginFrame(new FrameInput(
        view, initialVolume, frameIndex, renderExtent, displayExtent, hostTime));
```

This is not extension API. It is the point where Minecraft interpolation/extraction supplies the selected
view and optional initial volume, and the engine drains retained updates, snapshots program state, and records
one coherent frame. The initial value identifies a registered volume plus the binding and instance words that
its shader ABI already requires; ray generation evaluates it at the camera position and initializes the one
active homogeneous medium, with absence meaning vacuum/air. Keep the value internal while only
the host can create rendered views. If a later offscreen or multi-view public API lets an extension construct a
rendered view, expose the same typed value there with an explicit in-flight resource-lifetime contract.
Without it, the implicit call ordering currently hidden behind the runtime singleton will reappear in a
different class.

### Minecraft world/dimension lifecycle

Minecraft owns a map such as `ResourceKey<Level> -> host SceneHandle`. A Minecraft extension package may be
notified of dimension/world and resource-pack epochs and receive only the non-owning `SceneId` it may target.
The engine never sees a `ResourceKey`, `ClientLevel`, resource pack, block, entity, or registry.

Camera submersion follows that boundary too. The Minecraft adapter samples the camera fluid state, fluid
height, and biome water colour, then maps the result to the generic registered water volume used by
`FrameInput.initialVolume`. The engine does not know what water, a biome, or an underwater camera is; it only
initializes transport inside the selected volume. This is per-view frame state, not scene state, because two
cameras may render the same scene from opposite sides of a boundary.

A dimension sky follows the same division:

1. a package registers a generic environment shader implementation with the render session;
2. its Minecraft-side provider declares which dimensions it supports and prepares time/weather/LUT binding
   state;
3. the Minecraft adapter selects the binding for its host-owned scene;
4. core renders only `SceneId + EnvironmentBinding`.

This proves both sides without putting dimension keys in `api.scene` or letting a sky package administer an
entire scene.

### Forward-compatible scene shape for ray portals

Keep the engine's scene store and TLAS table capable of holding several resident scenes, and keep `SceneId`
opaque in every public signature. Do not expose its internal table index or descriptor address: a later portal
implementation may need generations, indirection, or a different GPU traversal table without changing Java
consumers.

For this rewrite, Minecraft owns internal scene handles and hands extensions non-owning `SceneId` targets.
The later portal feature can add two capabilities without changing `SetInstance`, `SetLight`, or `SceneView`:

1. controlled creation/ownership of another resident scene;
2. a retained `PortalLink` naming source and destination scenes, current and previous source-to-destination
   transforms, and disappearance/termination policy.

A portal surface should select a link; engine transport performs the scene/TLAS swap while preserving path,
medium, ray-cone, and motion state. Do not let arbitrary surface shader code issue an independent trace and
rebuild that state itself. The exact hop/cycle limit and destination environment/light behavior can remain out
of the current shader ABI until the feature is implemented.

## Physical package structure

The extracted projects live under `packages/`. `api`, `engine`, `engine-vulkan`, `minecraft-api`,
`minecraft-content`, `minecraft-adapter`, the support/tooling projects, built-ins, and examples are physical
Gradle projects now. Rows marked planned describe remaining extraction boundaries rather than artifacts that
already exist.

| Project | Owns | Must not depend on |
|---|---|---|
| `packages/api` | Vulkan-native extension contract: session ownership, retained scene content, programs, views, `VkDevice`/`VkCommandBuffer`, descriptor heaps, VMA access, and GPU retirement | Minecraft and engine implementation |
| `packages/settings-api` | Feature metadata, option declarations/values, display text | Renderer and Minecraft implementation |
| `packages/shader-api` | Versioned public Slang modules and generated ABI manifest | Minecraft shader implementations |
| `packages/slang-tooling` | Gradle plugin/tool, compilation profile, reflection adapter, Java encoders, manifests | Engine-specific record lists |
| `packages/vulkan-support` | Optional uploaders, VMA helpers, retained Vulkan buffer/image wrappers, mesh builders, common descriptor/table helpers | Minecraft identity and engine private resources |
| `packages/engine` | Renderer-generic session, contribution, program, retained-scene, environment-selection, and pass lifecycles | Minecraft, loader, and Vulkan implementation types |
| `packages/engine-vulkan` | Vulkan 1.4 profile validation, queues, mapped descriptor heaps, VMA resources, submission retirement, diagnostics, and renderer-backend SPI | Minecraft, loaders, and root-mod composition |
| `packages/engine-nvidia` (planned) | NGX/Streamline/DLSS RR/FG and low-latency backend | Minecraft scene/content types |
| `packages/features-builtin` | Error/default programs and built-in post/display features | Minecraft |
| `packages/minecraft-api` | Loader-neutral dimension/world/resource epochs, Minecraft extension hooks, and Minecraft shader-data identity tokens | Renderer internals |
| `packages/minecraft-content` | Host-free material analysis, page planning/compilation, and reusable content values | Minecraft host classes, loaders, renderer internals |
| `packages/minecraft-adapter` | Loader-neutral Minecraft world-session lifecycle and its bridge to engine sessions | Minecraft host classes, loader APIs, and renderer implementation details |
| `packages/minecraft-rendering` (planned) | Terrain/entity/particle/material/sky/damage/overlay capture, generated Minecraft shader records, and GPU publication | Loader-specific code where avoidable |
| `packages/minecraft-vulkan` (planned) | Device interception, queue/swapchain/HDR integration | Scene/material implementation details |
| `packages/platform-fabric` (planned) | Fabric entrypoints and discovery | NeoForge code, renderer implementation details |
| `packages/platform-neoforge` (planned) | NeoForge entrypoints and discovery | Fabric code, renderer implementation details |
| `packages/examples/api-showcase` | Pure contract pressure test | Engine implementation packages |
| `packages/examples/gltf-content` | Reusable glTF loading, Vulkan uploads, and scene contribution | Minecraft |
| `packages/examples/gltf-viewer-minecraft` | Anchors, resource reload, mod entrypoints | Renderer internals |

The most important dependency rule is:

```text
Minecraft/loader packages -> engine + engine-vulkan + API
engine                    -> API
engine-vulkan             -> Vulkan-native API
extensions                -> API (+ optional Minecraft API)
engine/engine-vulkan/API  -X-> Minecraft and loader packages
API                       -X-> engine implementation
```

Enforce this through Gradle project dependencies and architecture tests. Text-matching source checks are
useful backstops, not the primary boundary.

The API is deliberately Vulkan-dependent. The remaining boundary prevents Minecraft types and loader
lifecycle from leaking into reusable scene/shader packages, and prevents extensions from reaching engine
queues, swapchain ownership, device negotiation, or renderer-private resources.

The API and adapter documentation now use "Vulkan-native" or the narrower active invariant instead of
"host-neutral". Vulkan coupling and the Minecraft package boundary remain separate decisions.

`packages/api-support` contains only pure-Java colour/texture helpers and is now dependency-free.
`packages/settings-api` likewise imports no main API type and no longer declares a false dependency on it.

## Vulkan Java type and `long` audit

Use real LWJGL Vulkan wrapper objects wherever they exist. `GpuDevice.vk()` already returns `VkDevice`, and
`PassFrame.commandBuffer()` already returns `VkCommandBuffer`; future exposed dispatchable handles such as a
queue should follow the same rule.

LWJGL intentionally represents non-dispatchable handles and Vulkan scalar typedefs as Java primitives. There
is no Java wrapper class named `VkImage`, `VkImageView`, `VkAccelerationStructureKHR`, `VkDeviceAddress`, or
`VkDeviceSize`; VMA likewise exposes allocator/allocation handles as `long`. Do not substitute a native
create-info/range struct, whose off-heap lifetime is unrelated, merely to remove a primitive.

| Current public `long` | Actual meaning | Rewrite decision |
|---|---|---|
| `MeshBuild.Stream.bufferDeviceAddress`, `deviceAddress()` | `VkDeviceAddress` | Replace the loose address/offset/size tuple with a retained Java `DeviceAddressRange`/`VulkanBufferSlice` value. Its final native address is still a `long`, exposed only at Vulkan marshaling boundaries. |
| `MeshBuild.Stream.byteOffset`, `byteSize` | `VkDeviceSize` byte counts | Keep primitive storage inside the semantic range value; name units explicitly and validate unsigned overflow/alignment once. |
| `GpuImage.image()`, `view()` | `VkImage`, `VkImageView` non-dispatchable handles | No LWJGL `Vk...` replacement exists. Either keep explicit raw-handle access for command recording or return small `VulkanImageHandle`/`VulkanImageViewHandle` records with deliberate unwrap methods. Do not confuse them with ownership. |
| acceleration-structure descriptor argument | `VkAccelerationStructureKHR` non-dispatchable handle | Keep a documented raw handle or wrap it in a semantic descriptor value; no `VkAccelerationStructureKHR` Java class exists. |
| `GpuDevice.vmaAllocator()` | opaque VMA pointer handle | Prefer a Vulkan/VMA support service or a named `VmaAllocatorHandle` if the raw allocator remains public. It cannot become a Vulkan `Vk...` type. |
| `ShaderData.bits` | arbitrary 64-bit shader ABI word | Keep the representation because it may be an address, heap index, or packed scalar. Normal construction comes from generated typed encoders; reserve `fromBits` as an explicit expert escape hatch. |
| descriptor heap strides | `VkDeviceSize` byte counts | Keep `long`, rename with `Bytes`, and do not wrap ordinary size arithmetic. |
| `PassFrame.frameIndex`, `IndexRevision.value` | counter and opaque revision | Keep `long`; neither is a Vulkan handle. |

Descriptor heap indices remain 32-bit in the shader ABI. Use distinct resource-index and sampler-index records
where mixing the two is possible, rather than widening them to `long`.

## Vulkan 1.4 modernization decision

### Device profile

Make the following a fail-fast renderer profile rather than public runtime booleans:

- Vulkan API version 1.4;
- required Vulkan 1.2/1.3/1.4 feature booleans actually used, including buffer device address,
  shader float16, synchronization2, and dynamic rendering;
- timeline semaphores and `shaderInt64`, used by retirement and the shader ABI;
- `VK_KHR_unified_image_layouts` with `unifiedImageLayouts` enabled;
- `VK_EXT_descriptor_heap` with the heap features used by the shader ABI;
- `VK_EXT_shader_object` with `VkPhysicalDeviceShaderObjectFeaturesEXT.shaderObject` enabled;
- `VK_KHR_shader_untyped_pointers` for direct descriptor-heap shader access;
- the required KHR device extensions, enabling the acceleration-structure, ray-tracing-pipeline, ray-query,
  and position-fetch feature bits; `VK_KHR_deferred_host_operations` is an extension dependency without a
  feature bit.

Vulkan 1.4 does not automatically enable feature booleans, and it does not promote unified image layouts,
descriptor heaps, shader objects, untyped pointers, or the KHR ray-tracing suite into core. Query the loader
instance version, requested instance version, physical-device version, extension presence, feature structs,
and properties before creating the device. Report one structured unsupported-profile diagnostic.

Require `VK_EXT_shader_object` for raster and compute. It does not replace
`VK_KHR_ray_tracing_pipeline`; ray-tracing pipelines and their shader binding tables remain. Device creation
must query and enable `VkPhysicalDeviceShaderObjectFeaturesEXT.shaderObject`. Every shader-object draw path
must set the extension's complete required dynamic state before drawing; package shared state emission so
individual passes cannot accidentally depend on state left by a previous pass.

Keep SER, opacity micromaps, HDR, frame generation, and vendor low-latency support as separately negotiated
internal capabilities unless promoted into the explicit required profile.

### Replace legacy mechanisms consistently

The implementation now uses one modern path:

- core `vkCmdPipelineBarrier2` and `vkQueueSubmit2`, not synchronization-1 or KHR aliases under a hard 1.4
  baseline;
- specific stage/access masks, using `NONE` for empty scopes instead of TOP/BOTTOM-of-pipe sentinels;
- `vkCmdCopyBuffer2`, `vkCmdCopyImage2`, and `vkCmdBlitImage2`;
- dynamic rendering for raster work;
- one resource heap and one sampler heap bound for the command stream;
- descriptor heaps instead of descriptor sets, pools, and descriptor layouts; use `vkCmdPushDataEXT` for
  small per-dispatch data because ordinary push constants depend on pipeline-layout state and invalidate,
  and are invalidated by, descriptor-heap state;
- shader objects for compute/raster; ray-tracing pipelines remain;
- where [`VK_KHR_device_address_commands`](https://docs.vulkan.org/features/latest/features/proposals/VK_KHR_device_address_commands.html)
  is explicitly added to the required profile, use its device-address commands; otherwise retain the modern
  core `*2` buffer commands.

Fixed scans reject synchronization-1 barriers/submits, legacy image/buffer transfer calls, render passes,
descriptor-set allocation/binding, legacy physical-device property queries, and legacy dynamic viewport/scissor
commands in the rewritten path. Acceleration-structure and opacity-micromap KHR/EXT entry points remain current
extension APIs, not deprecated aliases. The hardware floor needs no compatibility abstraction.

### Unified image layouts

`VK_KHR_unified_image_layouts` guarantees that `GENERAL` is not less efficient than specialized layouts
where `GENERAL` is allowed. It does not erase initial `UNDEFINED`, swapchain `PRESENT_SRC_KHR`, video layouts,
or every extension-specific restriction.

Use these invariants:

- engine-owned non-present images stay `GENERAL` after initialization;
- new images transition from `UNDEFINED` once;
- swapchain images still transition to/from presentation as required;
- imported Minecraft images are normalized at the host boundary instead of carrying arbitrary layout
  integers through the core API;
- extension-borrowed engine images expose the invariant but never layout-mutation authority.

### Descriptor heap

Keep exactly one long-lived resource heap and one sampler heap. Heap and set/buffer mechanisms may coexist in
an application, but they cannot be active simultaneously; switching replaces or invalidates relevant binding
state and may be costly.

The public Vulkan helper should expose:

- typed resource and sampler ranges/indices;
- capacity, maximum allocation, alignment, and effective stride facts;
- typed sampler, image, buffer-address/range, and acceleration-structure descriptor descriptions;
- host-write flushing rules including non-coherent atom alignment;
- descriptor-read synchronization for copied or GPU-written descriptor bytes;
- allocate -> encode -> publish -> retire as the only update pattern.

A uniform resource stride may deliberately be the maximum of the descriptor sizes the engine supports, but
document it as an engine packing policy rather than a single Vulkan-provided resource stride.

Heap-native pipelines and shaders must carry the descriptor-heap creation flags
(`VK_PIPELINE_CREATE_2_DESCRIPTOR_HEAP_BIT_EXT` through flags2, or
`VK_SHADER_CREATE_DESCRIPTOR_HEAP_BIT_EXT` for shader objects), and command buffers must bind both heaps
before heap access. Their pipeline layout is null, and their push-constant storage is populated with
`vkCmdPushDataEXT`; recording `vkCmdPushConstants` would invalidate heap state. If secondary command buffers are introduced, their inheritance uses
`VkCommandBufferInheritanceDescriptorHeapInfoEXT`.

The ray pipeline's reflected `WorldBindingRoots` is a 96-byte push-data block:

| Offset | Contents |
|---:|---|
| 0, 8, 16, 24 | world-push, composition, retained-geometry, and path-queue device addresses |
| 32–60 | TLAS plus seven storage-image descriptor-heap indices, each 32-bit |
| 64, 68 | initial-volume implementation index and active flag |
| 72, 80 | initial-volume binding and instance shader words |
| 88 | NEE-AT state device address |

Slang reflection generates and verifies every offset and the total size. There is no material-table,
material-surface, instance-history, or reserved NEE word in this root; those unused fields are not kept for
speculative compatibility.

## Reusable compile-time Slang reflection

`packages/slang-tooling` is a reusable Gradle plugin/tooling package. The record generator accepts validated
declarative specs—record kind, probe, Slang struct, Java package/class, and reader policy—instead of a root-owned
hard-coded registry. Each package owns its declarations; for example, `features-builtin` owns Bloom's push-data
record. Malformed, unknown, duplicate, invalid-identifier, and reader-policy inputs have focused tests.

The tooling currently owns the compilation profile, Vulkan 1.4/SPIR-V validation, typed Java record
generation, reader/writer policy, and declaration validation. Its continued internal direction is:

1. compile against one named Caustica Vulkan 1.4 capability profile that separately pins the SPIR-V version,
   Vulkan target environment, and descriptor-heap/untyped-pointer capabilities;
2. validate SPIR-V with `spirv-val --target-env vulkan1.4`; the active build uses this target rather than the
   former Vulkan 1.2 validation floor;
3. query Slang `ProgramLayout` through the compilation/reflection API;
4. convert reflection into a Caustica-owned stable manifest rather than exposing Slang JSON shape to every
   consumer;
5. generate Java layout records, writers/readers, schema tokens, entry-point metadata, heap resource types,
   and an ABI hash;
6. cache by source graph, Slang version, target capability profile, matrix/layout policy, and shader ABI;
7. fail the build when Java, shader ABI, descriptor mapping, heap resource category/stride, push-data range,
   entry-point stage, or required pipeline/shader heap flags disagree.

The runtime composition compiler remains private because surfaces, volumes, and environments are selected at
runtime. Sharing more of the stable reflection adapter and cache is an internal tooling improvement; it does
not justify exposing compiler objects through the extension API.

`packages/shader-api` should ship the public Slang source modules plus the versioned generated manifest.
Extension pass packages can ship precompiled SPIR-V and generated Java without depending on the runtime
composition compiler.

## Example API-user pressure test

`packages/examples/api-showcase` is the compile-time probe. It covers the present draft, while its README
labels the parts that cannot currently become observable.

| Case | Why it is needed | Better narrower alternative? | Current result |
|---|---|---|---|
| Process factory + session contribution | Device recreation and enable/disable | A process singleton would retain stale GPU state | Necessary and well-shaped |
| `stop` and drained `close` | Join producers before destroying GPU state | Blocking device idle is broader and can deadlock | Necessary and well-shaped |
| Opaque surface | Ordinary authored/procedural materials | Fixed renderer material registry blocks package shaders | Necessary |
| Cutout coverage | Alpha-tested traversal | Full surface evaluation in any-hit is too broad/expensive | Necessary |
| Homogeneous volume | Water/glass interior and proposed invisible boundary | Encoding volume in surface data loses boundary semantics | Narrow ABI is justified; independent slot/reuse remains provisional |
| Initial view volume | Existing underwater camera path | Treating the camera as always in air gives incorrect primary-ray absorption and boundary transport | Required on internal frame ingress; Minecraft selects it and engine transport consumes it |
| Environment shader | Custom sky radiance | Hardcoded sky slot is less composable | Necessary; Minecraft selects a dimension binding for its host-owned scene |
| Geometry mesh + placements | glTF/terrain reuse and motion | Immediate per-frame geometry rebuild is worse | Necessary |
| Retained atomic batch | Seams/resource reload/multi-object publication | Per-operation callbacks expose half-updated frames | Necessary |
| Three light types | Minecraft proves rectangle/spot/distant and the path tracer samples all three | One untyped light record would weaken units/validation | Necessary; keep experimental through visual/unit validation |
| World-resource pass | LUT/table/texture publication | Direct queue submission breaks engine ordering | Necessary |
| Post-effect pass | Bloom and future effects | One hardcoded renderer chain is not extensible | Necessary; current registration order is sufficient, public constraints deferred |
| UI pass + TLAS view | Outlines/name tags/glow | Treating UI as scene-linear post effect is wrong | Necessary; current stage order is sufficient, public constraints deferred |
| Descriptor range + retirement | Shader-visible extension resources | Mutable in-flight descriptors are racy | Necessary in the main Vulkan-native API |
| Extension-created second scene | Future ray portal, but no current traversal link | Keep scene-targeted retained operations now and add creation/link authority later | `SceneId` shape justified; public `SceneChannel.create` deferred |
| Minecraft dimension mapping | Per-dimension scene and sky lifetime | Putting dimension keys in engine scene types couples reusable packages to Minecraft | Necessary in `minecraft-api` |
| Settings options | Bloom/sky/feature choices | Renderer-owned option state mixes UI and frame engine | Necessary add-on; cross-catalog stable identity remains deferred without a consumer |

The example is not proof merely because it compiles. Acceptance for the implemented rewrite is:

- it imports only published API/support packages;
- it creates no engine implementation object and reaches no singleton;
- its surface, coverage, volume, and environment shaders compile with the shared toolchain;
- a camera-start-inside-volume case initializes primary-ray transport and exits into air correctly;
- it uploads and retires real buffers/descriptors;
- it publishes, replaces, and drops a mesh and placement;
- it submits all three supported lights and they visibly affect direct lighting;
- its world-resource, post, and UI passes execute in deterministic order;
- its Minecraft layer attaches to a dimension scene, handles resource reload, and changes sky binding;
- every program-readiness and retirement callback resolves under normal close, failure, and cancellation;
- validation and architecture tests enforce the package boundary.

Until those checks pass, APIs should be labeled experimental rather than retained because they look complete
on paper.

## Remaining acceptance order

The package skeleton, Vulkan 1.4 substrate, generic Slang records, atomic program registration, retained
geometry/TLAS, path transport, initial volume, NEE-AT lights, and staged passes are implemented. Remaining work
should continue from integration to evidence:

1. finish physical package extraction where root composition still owns implementation classes;
2. run the complete compile, shader/reflection, architecture, and lifecycle gate;
3. launch the specified Minecraft test world with validation enabled and eliminate validation/runtime errors;
4. inspect deterministic screenshots for transport, guides, sky, geometry, water, and three light shapes;
5. rerun without validation to measure the default-resolution frame rate and investigate regressions;
6. remove only compatibility/dead code made obsolete by a verified replacement, then checkpoint the clean tree.

The local RTXPT source was used only to identify algorithmic pressure for adaptive NEE. The implementation is
clean-room and follows this repository's data model, shader ABI, synchronization, and ownership rules.

## Validation performed

- `:packages:api:check`, `:packages:api-support:check`, `:packages:settings-api:check`,
  `:packages:minecraft-api:check`, `:packages:minecraft-content:check`, `:packages:engine:check`,
  `:packages:engine-vulkan:check`, `:packages:renderer-presentation:check`,
  `:packages:renderer-raytracing:check`,
  `:packages:shader-api:check`, and `:packages:vulkan-support:check` have
  focused green gates and import/dependency boundary checks.
- `:packages:examples:api-showcase:check` passes against published contract artifacts only.
- `:packages:examples:gltf-viewer-minecraft:check` passes, including strict implementation-package rejection, retained
  lifecycle tests, resource-epoch replacement, and Vulkan 1.4 Slang/SPIR-V validation.
- Fixed/Minecraft world composition, the reflected 96-byte world root, generated shader records, API
  reflection, NEE-AT records, and Bloom's 40-byte shader-object push-data ABI validate together.

The showcase remains a compile-time contract pressure test. These checks prove the implemented ownership and
package boundaries, but validation-layer, visual, and performance acceptance still require the physical
Minecraft launch.

## Final high-level decisions

- Continue the rewrite against the reconciled experimental API and refine it only from concrete producers.
- Keep session ownership, retirement, retained batches, typed shader words, narrow coverage, and engine-owned
  OpenPBR transport.
- Keep scene targeting in the public shape for future ray portals, but keep scene creation/admin internal
  until the portal link and traversal contract are implemented.
- Model an underwater camera as one optional initial view volume on internal frame ingress; keep fluid
  detection in Minecraft and keep `Camera` pose-only.
- Keep internal atomic frame ingress and remove remaining root-composition reliance on the runtime singleton
  as package extraction reaches those classes.
- Make one main extension API explicitly Vulkan-native; split Minecraft lifecycle, settings, shader ABI,
  reusable Vulkan support, and Slang tooling only where they have real ownership/build boundaries.
- Replace per-object program mutation and exact-composition readiness objects with one owner-scoped atomic registration
  and one pending/ready/failed/cancelled readiness result.
- Allow explicitly handed-off same-session surface, volume, environment, and scene references across
  contributions without transferring removal authority or pinning the issuer.
- Keep `SceneView` on the stages that consume it. Defer public pass/contribution IDs and ordering constraints
  until a second real producer needs cross-package addressing.
- Keep the three-shape retained-light API backed by the clean-room NEE-AT consumer; use launch-time visual and
  unit checks before stabilizing it.
- Adopt one Vulkan 1.4 path with unified layouts, descriptor heaps, synchronization2, modern transfer/submit
  calls, and required shader objects for raster/compute.
- Require the showcase and a Minecraft layer to prove every public authority before stabilizing it.
