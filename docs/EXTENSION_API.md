# Caustica extension API

Status: current experimental 0.8 contract.

The API is deliberately Vulkan-native. Extensions compile against published projects under `packages/` and
must not import renderer implementation, Minecraft host, loader, queue, swapchain, or device-negotiation
classes. See [`packages/api/ARCHITECTURE.md`](../packages/api/ARCHITECTURE.md) for exact ownership rules and
[`packages/examples/api-showcase`](../packages/examples/api-showcase) for the compile-time Minecraft extension
consumer. The example is being kept on the real `MinecraftExtension`/world-session lifecycle; its source and
tests are authoritative while that conversion is completed.

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
4. drain accepted work, program readiness, pass uses, and retirement callbacks;
5. close drained pass instances and call `RenderSessionContribution.close()`.

Do not block on device idle or wait for the current frame from a recording callback.

## Programs and cross-contribution selection

Declare one coherent surface, coverage, volume, and environment set with
`ProgramChannel.register(builder -> exports)`. The returned `ProgramRegistration<E>` owns publication state,
the typed export record, completion notification, and removal of the entire set.

`MeshId`, `InstanceId`, and `LightId` are owner-local mutation capabilities. `SceneId`, `SurfaceId`,
`VolumeId`, and `EnvironmentId` are same-session, non-owning selection references and may be explicitly handed
to another contribution. The receiver gains neither removal authority nor a lifetime lease. Stale surface and
environment references resolve to visible error implementations; stale volumes resolve to vacuum.

There is no public cross-owner surface-modifier chain. Minecraft block damage uses Minecraft-owned instance
data. There is also no public scene creation/closure or portal-link API. The engine retains independent scene,
TLAS, environment, and NEE-AT state for several `SceneId` values, but ray-portal administration waits for a
complete transform, traversal, hop/cycle, lighting, and teardown contract.

Each Minecraft world-session contribution receives its own environment-selection slot for the borrowed scene.
Every successful selection replaces that owner's slot and moves it to latest precedence. Invalidating the
active owner restores the most recently selected surviving slot; invalidating a dormant owner does not disturb
the active selection. Replaced and removed bindings retire only after they have left all owner slots and every
published GPU snapshot that can reference them has retired. Owner drainage waits for those callbacks.

## Views and camera medium

Every `SceneView` contains a host-issued entry scene, a pose-only `Camera`, and a mandatory containing medium:

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

The public retained light set is:

| Shape | Units |
|---|---|
| rectangle | ACEScg radiance in cd/m² |
| circular spot | ACEScg intensity in candela |
| distant | ACEScg normal illuminance in lux |

The renderer uploads these records into a persistent, double-buffered NEE-AT distribution per scene, samples
it for next-event estimation, and feeds reverse-MIS outcomes into later updates. This is a clean-room
implementation of the public RTXPT algorithmic style using Caustica's own ABI and ownership model. Point lights
are absent because no producer justified a fourth physical shape.

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
it unconstrained; acceptance order breaks remaining ties. Duplicate IDs, self/cross-stage anchors, and cycles
are rejected. This is intentionally narrower than a public render graph.

The factory receives a stage-specific `PassSetup`; the resulting `Pass` records through the borrowed
`VkCommandBuffer` in `PassFrame` and closes only after its submitted uses drain. The session's resource and
sampler descriptor heaps are already bound and must not be replaced. `GpuFrameUse.whenComplete(...)` and
`GpuDevice.retireAfterUse(...)` are the non-blocking retirement seams.

`GpuDescriptorHeapProperties` names byte-valued facts with their units:
`resourceDescriptorStrideBytes`, `samplerDescriptorStrideBytes`, `resourceHeapAlignmentBytes`, and
`samplerHeapAlignmentBytes`. Capacities and maximum allocations remain counts of descriptor slots.

The required backend profile is Vulkan 1.4 with `VK_KHR_unified_image_layouts`, descriptor heaps,
synchronization2, and shader objects for compute/raster. Ray stages remain on
`VK_KHR_ray_tracing_pipeline`. LWJGL `Vk...` objects are used for dispatchable handles; non-dispatchable Vulkan
handles remain documented scalar values where LWJGL has no wrapper.

Vulkan opacity-micromap acceleration remains part of retained geometry. The obsolete standalone compute
encoder program has been removed, so extensions should treat opacity-micromap hints as geometry inputs rather
than depend on an encoder-program lifecycle.

## Reusable Slang tooling

`packages/slang-tooling` is an independently usable Gradle plugin/tool. Each package owns its shader probes,
generated Java namespace, and record manifest. The tooling owns compiler discovery, the Vulkan 1.4/SPIR-V 1.6
profile, reflection parsing, typed serializers/schema tokens, declaration validation, and reproducible task
inputs. Extensions can therefore validate and generate their own shader records without receiving the
engine's runtime composition compiler.

## Minecraft boundary and current acceptance

`packages/minecraft-api` supplies read-only world/resource epochs, dimension-to-scene lookup, and Minecraft
environment selection without exposing engine scene administration. `packages/minecraft-rendering` owns the
Minecraft-independent terrain/entity/material/light/sky implementation plus the five host-free frame/capture
types: `MinecraftFrameSelector`, `MinecraftFrameSelectionInstaller`, `MinecraftFrameCaptureInstaller`,
`MinecraftFrameCaptureState`, and `MinecraftEntityCaptureBinding`.

The root Loom application is the integration exception. It owns mapped Minecraft host/UI access, Vulkan
interception and device integration, loader entrypoints/discovery, mixins, resource loaders, and final
composition. Its guarded `CausticaClientComposition.current()` bridges entrypoints and mixins. Renderer runtime
state itself is instance-owned: `packages/renderer-runtime` has no current-composition lookup or mutable static
service installation.

Rounded clouds are deliberately excluded from this rewrite.

Complete the package, architecture, lifecycle, unit, shader/reflection, ABI, and example-consumer gates first.
Then perform a physical Minecraft launch with validation layers, screenshot review, and default-resolution
performance measurement. Those physical gates are still pending; no live visual or frame-rate result is
claimed here.
