# Caustica extension API

Status: current experimental 0.8 contract.

The API is deliberately Vulkan-native. Extensions compile against the package artifacts defined under
`packages/` and
must not import renderer implementation, Minecraft host, loader, queue, swapchain, or device-negotiation
classes. See [`packages/api/ARCHITECTURE.md`](../packages/api/ARCHITECTURE.md) for exact ownership rules and
[`packages/examples/api-showcase`](../packages/examples/api-showcase) for a non-loader Minecraft extension
consumer on the real `MinecraftExtension`/world-session lifecycle. It has real post/UI recording paths and
lifecycle tests but no standalone loader entrypoint.

## Process registration and settings

An extension receives immutable process capabilities directly; it does not discover a current renderer or
settings singleton:

```java
public final class ExampleExtension implements CausticaExtension, CausticaSettingsExtension {
    static final ResourceId SETTINGS = ResourceId.of("example", "rendering");
    static final Option<Float> STRENGTH =
            Option.range("strength", 0.0f, 1.0f, 0.5f).step(0.05);

    @Override
    public void registerSettings(SettingsRegistry registry) {
        registry.feature(SETTINGS)
                .title(DisplayText.literal("Example rendering"))
                .option(STRENGTH)
                .register();
    }

    @Override
    public void register(CausticaApi api) {
        api.sessions().add(context -> new ExampleSession(context, api.options()));
    }
}
```

Discovery is deterministic and two-pass: all settings declarations are registered, persisted values are
loaded, and only then are render-session factories registered. `OptionLookup.snapshot()` supplies a coherent
frame/session read without exposing host storage or mutation.

## Render-session ownership

Each `RenderSessionFactory` receives a fresh `RenderSessionContext` with `gpu()`, `program()`, `geometry()`,
`lights()`, and `passes()`. Never cache these in the process-lived extension object.

The host teardown order is:

1. stop new pass callbacks;
2. call `RenderSessionContribution.stop()` so producers join and registrations/drop operations finish;
3. invalidate remaining scoped objects at a publication boundary;
4. cross the retained-scene settlement boundary once, allowing the backend to accept or finish native work
   after the ordinary frame loop has stopped;
5. drain accepted work, program readiness, pass uses, and retirement callbacks;
6. close drained pass instances and call `RenderSessionContribution.close()`.

Do not block on device idle or wait for the current frame from a recording callback.

## Programs and cross-contribution selection

Declare one coherent surface, coverage, volume, and environment set with
`ProgramChannel.register(builder -> exports)`. The returned `ProgramRegistration<E>` owns publication state,
the typed export record, completion notification, and removal of the entire set.

`MeshId`, `InstanceId`, and `LightId` are owner-local mutation capabilities. A same-session `LightId` may also
be handed to geometry as a non-owning `PrimitiveLightMap` selection; the receiver still cannot set or drop that
light, and an absent selected light is non-sampleable. `SceneId`, `SurfaceId`, `VolumeId`, and `EnvironmentId`
are same-session, non-owning selection references and may be explicitly handed to another contribution. The
receiver gains neither removal authority nor a lifetime lease. Stale surface and environment references resolve
to visible error implementations; stale volumes resolve to vacuum.

The API showcase exercises this boundary with two independent same-session contributions. One owns program
and light mutation; the other receives selected program and light IDs through an ordinary Java handoff and
uses them for geometry without receiving either mutation channel. The handoff grants neither ownership nor a
lifetime lease and is removed when its producer stops.

The engine resolves published typed program IDs to plain non-negative implementation indices before retaining
or rendering scene state. Zero is reserved for the built-in error surface, vacuum volume, or error environment
as appropriate. Every declaration receives a stable slot for its kind for the lifetime of the session;
removing an unrelated registration does not renumber it or retarget retained scene data. Published membership
is stored directly on each registration, so hot resolution does not scan the published registration list.
These indices are internal values, not an additional public program API.

There is no public cross-owner surface-modifier chain. Minecraft block damage uses Minecraft-owned instance
data. There is also no public scene creation/closure or portal-link API. The engine retains independent scene,
TLAS, environment, and NEE-AT state for several `SceneId` values, but ray-portal administration waits for a
complete transform, traversal, hop/cycle, lighting, and teardown contract. Current multi-scene compatibility is
identity and lifetime isolation only: one trace root selects one entry-scene TLAS. Simultaneous portal traversal
will require a new trace ABI rather than merely another public scene identifier.

Each Minecraft world-session contribution receives its own environment-selection slot for the borrowed scene.
Every successful selection replaces that owner's slot and moves it to latest precedence. Invalidating the
active owner restores the most recently selected surviving slot; invalidating a dormant owner does not disturb
the active selection. Replaced and removed bindings retire only after they have left all owner slots and every
published GPU snapshot that can reference them has retired. Owner drainage waits for those callbacks.
A scene with no surviving selection uses built-in environment implementation zero.

## Views and camera medium

Every `SceneView` contains a host-issued entry scene, a pose-only `Camera`, and one mandatory homogeneous
medium containing the primary-ray origin:

```java
SceneView air = new SceneView(scene, camera, ViewMedium.Vacuum.INSTANCE);
SceneView water = new SceneView(scene, camera,
        new ViewMedium.Volume<>(volumeId, bindingData, instanceData));
```

The Minecraft adapter decides whether the camera begins underwater. The renderer understands only the typed
volume selection and initializes primary-ray transport from it; this does not require a public camera-volume
feature channel or medium stack.

## Retained geometry and lights

Geometry uses issued mesh/instance IDs and atomic retained batches. Mesh streams carry
`VulkanDeviceAddressRange`, which combines a typed `VulkanDeviceAddress` with an explicit byte size; placements
target a host-issued scene and preserve double-precision translation until renderer rebasing. Publication and
replacement callbacks must obey the session's GPU-retirement contract.

`GeometryChannel.submitGroup(...)` is mandatory when separately owned batches must enter one native
publication. Operations validate and become visible together, but each batch keeps its own retirement callback.
The API showcase uses this to publish an uploaded mesh and its first placement atomically without making the
mesh buffer wait for that placement's independent lifetime. Its world-resource pass records the buffer upload,
marks it ready from the frame-completion callback, and performs grouped publication from a later pass callback;
the acceleration build therefore cannot race the upload submission.

The public retained light set is:

| Shape | Units |
|---|---|
| one-sided parallelogram | ACEScg radiance in cd/m² |
| circular spot | ACEScg intensity in candela |
| distant | ACEScg normal illuminance in lux |

`LightDescriptor.Parallelogram` takes two non-collinear half-axis spans. They need not be perpendicular: the
GPU sampler already uses their cross product for area and emitting normal, and skewed emissive faces such as
transformed lava surfaces require that generality. The public name therefore matches the accepted geometry
rather than promising a rectangle invariant the renderer does not enforce.

The renderer implements a publicly described RTXPT-style NEE-AT subset with a Caustica-owned implementation
and ABI. "RTXPT-style" names the algorithm family; it is not a source-compatibility, binary-compatibility,
conformance, or output-equivalence claim. The implementation boundary is public algorithm descriptions only:
no NVIDIA source, shader code, private headers, or binary-derived implementation detail forms part of the
Caustica implementation. Caustica independently defines its light records, feedback quantization, identity
remapping, tiled histogram layout, proposal mixture, candidate RIS estimator, and reverse-MIS integration.
The repository tests those local contracts rather than comparing private implementation details or golden
output from another renderer.

Each scene has persistent double buffers containing a global discrete distribution and tiled local histograms
derived from previous-frame light and pixel feedback. The shader combines global and local proposal PDFs,
selects candidate samples with RIS, and reports reverse-MIS feedback for the next update. Point lights are absent
because no producer justified a fourth physical shape.

CPU property tests cover global distribution normalization and CDF boundaries, local histogram probing and
address ranges, history validity and identity continuity, and agreement with the shader's constants and branch
edges. They are static contract evidence; physical light appearance remains a launch-time validation item.

`Distant.environmentEmitter()` links direct-light sampling to discrete lobes drawn by the active environment.
Once any sampled distant light sets it, non-camera miss queries ask the environment to hide its discrete
emitter lobes. The scene must supply a sampled distant descriptor for every lobe hidden in that mode; the
environment keeps continuous background radiance visible.

## Passes and Vulkan resources

`PassChannel` exposes three engine-defined stages:

- `addWorldResourcePass(...)` records dirty resource/table work before tracing; ordering is meaningless;
- `addPostEffectPass(PassId, PassPlacement, ...)` participates in the scene-colour chain;
- `addUiPass(PassId, PassPlacement, ...)` records into the separate display-resolution UI layer.

Post/UI IDs are stage-local. A pass may declare one optional `before` or `after` anchor. A missing anchor leaves
it unconstrained; acceptance order breaks remaining ties. An anchor can refer to another contribution's pass
in the same stage and grants only an ordering relationship. The same ID in the other stage is unrelated, so it
does not form a cross-stage anchor. Duplicate live IDs within a stage, self-anchors, and cycles are rejected.
This is intentionally narrower than a public render graph.

The factory receives a stage-specific `PassSetup`; the resulting `Pass` records through the borrowed
`VkCommandBuffer` in `PassFrame` and closes only after its submitted uses drain. The session's resource and
sampler descriptor heaps are already bound and must not be replaced. `GpuFrameUse.whenComplete(...)` and
`GpuDevice.retireAfterUse(...)` are the non-blocking retirement seams.

Images and samplers may use direct descriptor-heap access. Acceleration structures are the deliberate
exception: a shader may declare a conventional SPIR-V binding when shader creation maps it to a resource-heap
index stored in pushed data with `VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT`. This uses neither a
descriptor-set layout nor a descriptor-set bind command. `ShaderObjectGraphics.PushIndexedResourceMapping`
provides that mapping for pass-owned graphics shader objects.

`GpuDescriptorHeapProperties` exposes `resourceDescriptorStrideBytes`, `maximumResourceAllocation`, and
`maximumSamplerAllocation`. The stride is a byte count; the allocation maxima are descriptor-slot counts.
Sampler stride, heap alignments, and total capacities are backend details rather than extension contracts.

`features-builtin/BloomPass` is the concrete owned-descriptor lifecycle example. Its resize path allocates
replacement `VmaImage2D` levels, which allocate and populate resource-heap ranges, records initialization before
publishing their indices to shader work, then calls `GpuDevice.retireAfterUse(...)` for the displaced levels.
Its final `Pass.close()` releases the currently published levels directly because that callback runs only after
the pass's submitted uses have drained. This is the intended replace-publish-retire pattern; allocating an
otherwise unused descriptor solely to demonstrate the allocator would not exercise the publication invariant.
The API showcase provides a smaller executable version as well: it allocates and writes one typed resource
range and one sampler range, publishes their indices, then retires the displaced generation with
`GpuDevice.retireAfterUse`.

The required backend profile is Vulkan 1.4. Its complete feature baseline is shader int64/int16/float16,
storage-image extended formats and formatless reads/writes, shader draw parameters, demote-to-helper invocation,
buffer device addresses, timeline semaphores, synchronization2, dynamic rendering, unified image layouts,
descriptor heaps, shader objects, untyped pointers, acceleration structures, ray-tracing pipelines, ray queries,
and ray-tracing position fetch. Ray stages remain on `VK_KHR_ray_tracing_pipeline`. LWJGL `Vk...` objects are
used for dispatchable handles; non-dispatchable Vulkan handles remain documented scalar values where LWJGL has
no wrapper.

The Minecraft integration additionally requires `VK_KHR_get_surface_capabilities2` at instance creation and
enumerates format/color-space pairs with `vkGetPhysicalDeviceSurfaceFormats2KHR`,
`VkPhysicalDeviceSurfaceInfo2KHR`, and `VkSurfaceFormat2KHR`. `VK_EXT_swapchain_colorspace` is optional and,
when present, exposes the extended color-space enums used for HDR-capable surface selection.

Presentation boundaries group related non-dispatchable handles into semantic records:
`BorrowedImage` carries image/view/format/extent, `PresentationSwapchain` carries the immutable borrowed image
table, and `AcquiredSwapchainTarget` carries one acquired image with its acquire/present synchronization.
Dispatchable handles use LWJGL `Vk...` wrappers where those wrappers exist. Swapchains, images, image views,
samplers, and semaphores remain `long` where a real non-dispatchable handle is required or borrowed because
LWJGL exposes those handles as scalar values; the containing record supplies their role and ownership context.
Descriptor-heap-native objects need not create those handles: `VulkanSampler` encodes
`VkSamplerCreateInfo` directly into its sampler range and owns no `VkSampler`; `RtToneLut` encodes a sampled
image from `VkImageViewCreateInfo` and owns no `VkImageView`. A tone LUT retains its VMA image allocation and
sampled/sampler descriptor ranges only.

Opacity micromaps are not active in the current retained geometry path and have no public API contract. The
old encoder and producer/build integration are absent; cutout coverage continues through the surface coverage
program and any-hit path.

`ShaderObjectGraphics` supports general pass-owned vertex/fragment shader objects with Vulkan-native dynamic
vertex input, topology, rasterization, multisampling, depth, and attachment-zero blend state. Optional
per-stage mappings source conventional SPIR-V resources from descriptor-heap indices stored in pushed data;
the acceleration-structure helper is one use, not the limit of the graphics wrapper.

## Reusable Slang tooling

`packages/slang-tooling` is an independently usable Gradle plugin/tool. Each package owns its shader probes,
generated Java namespace, and record manifest. The tooling owns compiler discovery, the Vulkan 1.4/SPIR-V 1.6
profile, reflection parsing, typed serializers, declaration validation, and reproducible task
inputs. Extensions can therefore validate and generate their own shader records without receiving the
engine's runtime composition compiler.

Compile, raw-reflection, and typed-record tasks accept both directory includes and shader-module JARs. JAR
inputs are deterministically extracted into task-local include roots containing only `.slang` resources. The
API showcase resolves the artifact-backed `caustica-shader-api` JAR for both compilation and record reflection
instead of reading the shader API project's source directory. An independently built extension can therefore
consume the shader ABI as a package artifact rather than depending on the repository layout.

A source consumer can apply the tooling through `pluginManagement.includeBuild` pointing at
`packages/slang-tooling`. Normal versioned plugin-marker resolution requires the marker to be published in a
repository configured by the consumer; this documentation does not claim that a public marker repository is
currently available.

## Minecraft boundary and current acceptance

`packages/minecraft-api` supplies a borrowed scene paired with its dimension key and resource epoch, plus
Minecraft environment selection without exposing a dimension-to-scene directory or engine scene
administration. `packages/minecraft-rendering` owns the
Minecraft-independent terrain/entity/material/light/sky implementation plus the five host-free frame/capture
types: `MinecraftFrameSelector`, `MinecraftFrameSelectionInstaller`, `MinecraftFrameCaptureInstaller`,
`MinecraftFrameCaptureState`, and `MinecraftEntityCaptureBinding`.

The corresponding Java namespaces make artifact ownership explicit: host-free material content is under
`dev.comfyfluffy.caustica.minecraft.content`, reusable rendering is under
`dev.comfyfluffy.caustica.minecraft.rendering`, mapped application code is under
`dev.comfyfluffy.caustica.minecraft.client`, and generic live renderer orchestration is under
`dev.comfyfluffy.caustica.renderer.runtime`. The renderer host SPI remains separately named under
`dev.comfyfluffy.caustica.spi.host`.

The `packages/minecraft-client` Loom application is the integration exception. It owns mapped Minecraft host/UI access, Vulkan
interception and device integration, loader entrypoints/discovery, mixins, resource loaders, and final
composition. Its guarded `CausticaClientComposition.current()` bridges entrypoints and mixins. Renderer runtime
state itself is instance-owned: `packages/renderer-runtime` has no current-composition lookup or mutable static
service installation.

Rounded clouds are deliberately excluded from this rewrite.

The final static gates are green for the current checkout: the Fabric check completed 190 actionable tasks,
and the NeoForge `minecraft-client` plus glTF viewer checks completed 162 actionable tasks. The final
RR-disabled Fabric run reached `ray-tracing-test-place` with `VK_LAYER_KHRONOS_validation` active, produced no
actionable Vulkan validation diagnostic, device loss, or fatal renderer output, and shut down normally.

At the default 854 x 480 test resolution with validation and RR disabled, a 1,000-frame active renderer sample
measured 2.930 ms median, 3.733 ms p95, 4.183 ms p99, and 2.686 ms mean. The median corresponds to roughly 341
frames per second of renderer throughput rather than a guarantee about host presentation cadence.

Visual review covered first-person player-body exclusion and the ordinary hand/UI path. Camera eye-volume A/B
captures prove water-volume activation. Camera containment and fluid meshing share the same corner-height and
two-triangle surface rule; quantitative absorption/refraction and physical flowing-fluid edge behavior remain
unaccepted. Runtime telemetry simultaneously retained Parallelogram, Spot, and Distant
lights with eight NEE-AT candidates and valid history; raw 1-spp captures do not yet accept their appearance.
The high-contrast captures reflect the quartz/floor composition plus raw 1-spp output and do not establish an
exposure bug. DLSS Ray Reconstruction is explicitly deferred and not accepted: attempted RR captures produced
a black output without the renderer debug overlay, while RR-disabled captures rendered the world and overlay.

The executable API showcase additionally covers program ready/failure/cancellation outcomes, same-session
cross-owner program/light selection, owner-local mutation, grouped mesh/placement publication, retained mesh
replacement, cross-scene placement movement, independent multi-scene targeting, descriptor resource/sampler writes,
and publish-before-retire cleanup. Engine tests, rather than a fake consumer implementation, verify pass-cycle
rejection and deterministic multi-owner environment restoration because those results are not observable
through the contribution-facing interfaces.

Simultaneous residency means independent scene/TLAS state can coexist and an entry view can select either one.
It does not render both scenes through a portal. Portal traversal still needs an explicit multi-scene trace ABI,
transform and hop/cycle rules, lighting behavior, and teardown semantics. Minecraft's current `PortalSurface`
is an emissive end-portal material model, not a ray-scene switch.
