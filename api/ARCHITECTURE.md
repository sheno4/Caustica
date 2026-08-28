# Caustica extension API architecture

The public API is a contribution boundary, not a mirror of the renderer implementation. It exposes the
minimum capabilities needed to add scene content, shader implementations, synchronized GPU work, post
effects, and UI. Minecraft lifecycle, dimension keys, resource packs, presentation, upscaling, and native
queue ownership remain outside the core API.

## Process registration and render sessions

Extension discovery is process-scoped:

```java
public final class ExampleExtension implements CausticaExtension {
    @Override
    public void register(CausticaApi api) {
        api.sessions().add(context -> new ExampleSession(context));
    }
}
```

The registered factory is reusable; the object it returns is not. The host invokes the factory once for
each live render session and supplies a fresh `RenderSessionContext`. All GPU handles, passes, providers,
program IDs, geometry IDs, lights, and owned scenes created through that context belong to the session
scope.

This split is necessary because extension discovery can outlive a Vulkan device. A process object cannot
safely retain a device address or a pass from an earlier device, while a session contribution has an exact
pre-device-destruction boundary.

Session shutdown has one defined order:

1. Stop frame callbacks and reject new scoped registrations.
2. Invoke `RenderSessionContribution.stop()` while accepted submission APIs remain valid.
3. Unschedule passes and logically invalidate objects still owned by the session scope. Stale surface and
   environment references resolve to visible error implementations, stale volumes resolve to vacuum, and
   none of those references pin the owner.
4. Cancel pending program tickets, drain submitted GPU uses, return ticket and retirement callbacks, then
   close pass instances.
5. Invoke `RenderSessionContribution.close()` before destroying the device.

`RenderSessionRegistration.close()` disables a factory and applies this ordering to its active
contribution. Extensions use it for explicit disable or unload; normal session shutdown performs the same
cleanup automatically.

## Scenes, views, and ownership

A scene is a retained coordinate space with a stable metres-per-scene-unit scale, placements, lights, an
acceleration structure, and an environment binding. Multiple scenes may be resident at the same time.

`SceneId` is an opaque, non-owning reference. Geometry and light operations may name it, but possession of
the reference does not grant scene administration or channel access. `SceneHandle` is the administration
capability returned by `SceneChannel.create`; only a holder can replace that scene's environment or ask
for early removal. Closing it cascades placements and lights, but never scene-independent meshes. The
render-session contribution owns guaranteed cleanup whether or not the handle is explicitly closed.

`SceneDefinition` supplies the stable coordinate scale and initial `SceneEnvironment`. An environment's
retirement callback is attached when that binding is introduced, including at scene creation, and follows
it until replacement, scene removal, or session teardown. This lets an extension safely reclaim the GPU
data named by the binding's parameter word.

The camera is not scene state. A rendered view is:

```java
SceneView view = new SceneView(selectedScene, camera);
```

This permits a host to keep several dimensions resident and select one for a camera without moving the
camera into the scene object. Host-specific lookup such as `MinecraftDimensionKey -> SceneId` belongs in a
Minecraft package. Core sees only the resulting `SceneId` and `SceneView`.

The API does not expose traversal links between scenes. Such a contract needs concrete engine behavior for
visibility, recursion, transforms, lighting, and lifetime before it can earn a public type.

## Retained and frame-coherent scene updates

`GeometryChannel` and `LightChannel` hold asynchronous retained state. Their identities are issued by the
current contribution, so no string registry or global generation number is needed. A mesh, its placements,
and its selected surface and volume implementations belong to one contribution; `SceneId` is the deliberate
cross-contribution target. Each accepted `RetainedBatch` publishes as a unit and keeps its source-owned data
borrowed until the batch's values are replaced, dropped, cascaded, or removed with the session.

These retained channels and `SceneHandle.setEnvironment` are thread-safe, so a content worker may submit an
infrequent scene or packed-parameter change without waiting for a frame callback. GPU-visible memory is
different: a worker prepares replacement data, while a world-resource pass records and publishes it before
the trace so it cannot race an in-flight reader.

Call timing never changes the meaning of an asynchronous channel. Work that must join the frame currently
being collected uses the explicit `SceneFrameWriter` supplied to a `SceneProvider`:

- `submit(SceneMutation)` publishes retained geometry and light operations atomically across both channels
  at the frame boundary.

`SceneFrame` carries `SceneView`, the current scene origin, the selected scene's stable coordinate scale,
frame index, and the writer. CPU workers may prepare source data independently; the provider snapshots it
and explicitly publishes only what must be coherent with that frame. There is no separate transient-
geometry lifetime: dynamic geometry reuses issued mesh/instance IDs and replaces or drops their retained
values, so the ordinary batch retirement contract remains sufficient.

## Program composition and the shader ABI

Surfaces, volumes, coverage implementations, and environments are retained program objects. A mesh geometry
has independent optional surface and interior-volume slots, with at least one present. Surface-only geometry
is ordinary visible geometry; surface plus volume models a visible boundary such as glass around water;
volume-only geometry is an invisible boundary such as a smoke container. A volume boundary mesh is closed,
manifold, and outward-oriented so traversal can distinguish entry from exit. Surface coverage controls
visible boundary shading but does not erase an independent volume crossing; an actual opening is geometry
without a volume slot. A surface paired with a volume is not thin-walled.

The renderer has no public material registry: implementation, geometry, and instance data are opaque
extension-owned words, and an extension keeps any shading table behind those roots. Adding a program object
returns `ProgramUpdate<Id>`:

- the ID is available synchronously;
- the `ProgramTicket` reports readiness or failure without blocking;
- several changes may share one renderer-coalesced composition build.

A pending ticket is cancelled during contribution teardown, and every callback registered before teardown
returns before the contribution closes.

There is deliberately no `awaitCompletion`. Compilation completion can require render-thread progress, so
blocking that thread creates a deadlock. A feature that needs an error-free switch registers a ticket
callback and only then publishes replacement geometry or replaces a `SceneHandle` environment binding. A
feature that accepts the surface error implementation or volume vacuum fallback may publish the ID
immediately.

Java's `ProgramAbi.VERSION` and the public `caustica/shaders/api` Slang modules form one ABI. The public
shader inputs include:

- surface, volume, geometry, and placement data words;
- OpenPBR thin-wall selection in the evaluated surface, with only non-thin-walled transmission changing
  the path's volume stack;
- primitive-local barycentrics;
- object-to-scene, previous-object-to-scene, and normal transforms;
- the scene's stable metres-per-scene-unit conversion, including physical-to-scene conversion for volume
  extinction and ray bias;
- environment dispatch selected per scene;
- versioned push data and typed descriptor-heap indices/accessors.

Custom light emission profiles are absent because the current renderer and public Slang composition have
no complete consumer for them. Ordinary OpenPBR surface emission and the built-in light descriptors remain
available.

The engine's Slang compiler remains private behind `ProgramChannel`, because it must compose those world
implementations. Independent Vulkan passes own their shader compilation and may package SPIR-V or use any
compiler that produces the documented Vulkan and descriptor-heap ABI. No compiler belongs to the render
session context.

## GPU work and synchronization

The renderer owns graphics/compute queues and submission. The public GPU API intentionally exposes no
queue, immediate-submit method, fence wait, or asynchronous command queue. Extensions may run arbitrary
CPU preparation on their own executors, then record GPU work into engine-provided command buffers at the
stage where the result is consumed.

The stages are:

- `addWorldResourcePass`: before tracing, for dirty texture, environment, lookup-table, or buffer updates.
- `addPostEffectPass`: after reconstruction, in the ordered scene-colour chain.
- `addUiPass`: after the display transform, into the display-resolution UI layer.

All three methods accept the same generic `PassFactory<S, F>` and create a `Pass<F>`. The registration
method selects timing, while `F` exposes only the frame resources valid at that stage. A pass has no resize
or resource-pack lifecycle callbacks. It compares current frame facts with the state it built and replaces
resources when they differ. `Pass.close()` is final and occurs only after that pass's submitted uses drain.

Retirement is callback-based:

- `GpuFrameUse.retire` follows commands recorded for the current frame.
- `GpuDevice.retireAfterUse` follows device work submitted before the call.
- retained batch callbacks follow the particular values introduced by the batch.

Callbacks must not block or throw. None of these primitives imply that unrelated device work is idle.

## Vulkan and descriptor heaps

Every API session has a hard Vulkan 1.4 logical-device baseline including buffer device addresses, dynamic
rendering, synchronization2, unified image layouts, descriptor heaps, shader objects, untyped pointers,
acceleration structures, ray-tracing pipelines, ray queries, and position fetch. These are guarantees, not
runtime capability booleans.

`GpuDevice.vk()` exposes the typed LWJGL `VkDevice`. Extensions obtain its physical device and query Vulkan
features, properties, formats, and limits directly. A physical-device support query does not reveal which
optional features the renderer enabled at logical-device creation, so only the documented baseline is
usable without a separate pre-device agreement. Wide-line primitives are not in the baseline; UI and debug
lines use ordinary triangle geometry.

The renderer binds one resource heap and one sampler heap before extension passes record. Passes must not
rebind either heap. `GpuDescriptorHeap` assigns shader-visible slot ranges; `GpuDescriptorWriter` encodes
descriptors for extension-owned resources and makes non-coherent writes visible. Engine-owned images and
the selected-scene TLAS expose immutable `GpuResourceDescriptor` views whose entries and resources the
engine retains through the current frame. `GpuDescriptorHeapProperties` exposes the unified resource and
sampler strides required when independently compiling pass shaders for `spvDescriptorHeapEXT`.

Submitted descriptor bytes are immutable. Updating a descriptor therefore means:

1. Allocate a replacement range.
2. Encode it with `GpuDescriptorWriter`.
3. Record and publish the new first-slot index at the appropriate pass boundary.
4. Retire the displaced range after its last use.

Renderer-borrowed `GpuImage` objects use the unified `GENERAL` layout and are valid only for the callback
that supplied them. Their shader descriptors are already encoded; the extension passes the borrowed index
to its shader and never rewrites or retires it. An extension owns raw Vulkan/VMA resources it allocates
itself and releases them through retirement or a drained final lifecycle callback before device
destruction. LWJGL wraps dispatchable handles such as `VkDevice` and `VkCommandBuffer`; non-dispatchable
handles such as `VkImage`, `VkImageView`, and
`VkAccelerationStructureKHR` remain `long` because LWJGL provides no wrapper classes for them.

## Post effects and UI

A post effect reads `sceneColor()` and joins the chain only by calling
`acquireSceneColorOutput()`. The output is distinct from the input and must be fully written. Effects
compose in registration order; there are no first/last anchor sentinels.

UI is a separate display-resolution layer. `UiFrame` includes the immutable `SceneView`, an unjittered
world-view-projection matrix, and a borrowed TLAS for selected-scene occlusion queries by world-anchored
overlays. It does not expose scene-linear colour or general scene mutation.

## Package boundary

Core packages contain host-neutral concepts only:

- `api.session`: render-session ownership and teardown.
- `api.retained`: opaque retained identities and atomic retained batches.
- `api.scene`: scene identity, creation metadata, environment binding, and administration.
- `api.geometry`, `api.light`: session-retained world contributions.
- `api.view`: immutable camera state associated with one selected scene.
- `api.frame`, `api.provider`: frame-coherent publication and its session-scoped sources.
- `api.program`: composed-world Slang inputs and non-blocking compilation observation.
- `api.pass`: GPU frame-recording stages, including post effects and UI.
- `api.gpu`: Vulkan services an extension cannot recreate independently.
- `api.host`: the small bootstrap bridge used by a host adapter.

Minecraft dimension identifiers, level lifetime, camera capture, origin selection, resource reloads, and
dimension-to-scene directories belong to Minecraft integration packages. Presentation policy, DLSS,
screenshots, telemetry, and native queue orchestration belong to engine or integration packages. Optional
helpers that do not appear in an engine signature belong in `api-support`, not in the core contract.
