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
each live render session and supplies a fresh `RenderSessionContext`. All GPU handles, passes,
program IDs, geometry IDs, lights, and owned scenes created through that context belong to the session
scope.

This split is necessary because extension discovery can outlive a Vulkan device. A process object cannot
safely retain a device address or a pass from an earlier device, while a session contribution has an exact
pre-device-destruction boundary.

Session shutdown has one defined order:

1. Stop pass callbacks and reject new scoped registrations.
2. Invoke `RenderSessionContribution.stop()` while accepted submission APIs remain valid.
3. Unschedule passes and logically invalidate objects still owned by the session scope. Stale surface and
   environment references resolve to visible error implementations, stale volumes resolve to vacuum, and
   none of those references pin the owner.
4. Cancel pending program tickets, drain submitted GPU uses, return ticket and retirement callbacks, then
   close pass instances.
5. Invoke `RenderSessionContribution.close()` before destroying the device.

`RenderSessionRegistration.close()` requests factory removal and applies this ordering asynchronously to its
active contributions. It prevents future sessions from using the factory but does not wait for active teardown.
Extensions use it for explicit disable or unload; normal session shutdown performs the same cleanup
automatically. Teardown observation, if added, remains callback-only because completion can require render-thread
progress.

## Scenes, views, and ownership

A scene is a retained coordinate space with a stable metres-per-scene-unit scale, placements, lights, an
acceleration structure, and an environment binding. Multiple scenes may be resident at the same time.

`SceneId` is an opaque, non-owning reference. Geometry and light operations may name it, but possession of
the reference does not grant scene administration or channel access. `SceneHandle` is the administration
capability returned by `SceneChannel.create`; only a holder can replace that scene's environment or ask
for early removal. Closing it cascades placements and lights, but never scene-independent meshes. The
render-session contribution owns guaranteed cleanup whether or not the handle is explicitly closed.

`SceneDefinition` supplies the stable coordinate scale and initial `EnvironmentBinding`. An environment's
retirement callback is attached when that binding is introduced, including at scene creation, and follows
it until replacement, scene removal, or session teardown. This lets an extension safely reclaim the GPU
data named by the binding data word. Environment implementation ids may be shared across contributions in one
render session, but only the scene handle grants authority to change a scene's binding.

The camera is not scene state. A rendered view is:

```java
SceneView view = new SceneView(rootScene, camera);
```

This permits a host to keep several dimensions resident and select one for a camera without moving the
camera into the scene object. Host-specific lookup such as `MinecraftDimensionKey -> SceneId` belongs in a
Minecraft package. Core sees only the resulting `SceneId` and `SceneView`.

The API does not expose traversal links between scenes. Such a contract needs concrete engine behavior for
visibility, recursion, transforms, lighting, and lifetime before it can earn a public type.

## Retained scene updates

`GeometryChannel` and `LightChannel` hold asynchronous retained state. Their identities are issued by the
current contribution, so no string registry or global generation number is needed. A mesh, its placements,
and its selected surface and volume implementations belong to one contribution; `SceneId` is the deliberate
cross-contribution target. Each accepted `RetainedBatch` publishes as a unit and keeps its source-owned data
borrowed until the batch's values are replaced, dropped, cascaded, or removed with the session.

These retained channels and `SceneHandle.setEnvironment` are thread-safe, so a content worker submits an
infrequent scene or packed-parameter change directly. The renderer consumes accepted changes in order at
its publication boundaries; there is no public camera-timed scene callback. Dynamic geometry reuses issued
mesh and instance IDs and replaces or drops their retained values, so it needs no separate transient
lifetime.

GPU-visible memory is different: a worker prepares replacement data, while a world-resource pass records
and publishes it before the trace so it cannot race an in-flight reader. The pass is for commands on the
engine-owned queue, not for submitting retained geometry or lights. Minecraft render interpolation and
other host-owned per-frame extraction remain integration work rather than a core extension contract.

## Program composition and the shader ABI

Surfaces, volumes, optional coverage implementations, and environments are retained program objects. An
opaque-only surface omits coverage code; a geometry selecting `Cutout` requires its surface definition to
provide the narrow traversal implementation. Coverage returns only the scalar candidate coverage used by
the cutoff; optical tint and transmission remain surface properties. A mesh geometry
has independent optional surface and interior-volume slots, with at least one present. Surface-only geometry
is ordinary visible geometry; surface plus volume models a visible boundary such as glass around water;
volume-only geometry is an invisible boundary such as a smoke container. A volume boundary mesh is closed,
manifold, and outward-oriented so traversal can distinguish entry from exit. Surface coverage controls
visible boundary shading but does not erase an independent volume crossing; an actual opening is geometry
without a volume slot. A surface paired with a volume is not thin-walled.

The renderer has no public material registry: implementation, slot-binding, and instance data are opaque
extension-owned words, and an extension keeps any shading table behind those roots. Java carries each word
as `ShaderData<T>`, paired with one identity-based `ShaderDataType<T>` defined by the extension. Program IDs,
geometry slots, meshes, placements, and environment bindings propagate those schema parameters, so an
incorrect pairing fails to compile. Submission boundaries also compare token identity so raw types or casts
cannot silently publish a mismatch. The Slang representation remains exactly `uint64_t`; the type parameter
does not prescribe whether the bits are an address, descriptor index, or packed value. Adding a program
object returns `ProgramUpdate<Id>`:

- the ID is available synchronously;
- the `ProgramTicket` reports readiness or failure without blocking;
- several changes may share one renderer-coalesced composition build.

Surface and volume definitions attach retirement callbacks to their typed `implementationData` roots when added;
dropping their ids needs no new callback. Environment implementations have no implementation root because
each `EnvironmentBinding` owns and retires its own `bindingData`.

The opaque 64-bit vocabulary follows ownership depth consistently:

| Name | Owner and Java source | Shader use |
|---|---|---|
| `compositionData` | Engine-generated `ShaderRootData` | Root table for the active composed program |
| `implementationData` | `ShaderData<I>` in `SurfaceDefinition<I, B, N>` / `VolumeDefinition<I, B, N>` | State shared by every binding of one registered implementation |
| `bindingData` | `ShaderData<B>` in a typed surface/volume slot or environment binding | State for one geometry slot or scene environment binding |
| `instanceData` | `ShaderData<N>` in `GeometryChannel.SetInstance<N>` | State for one mesh placement; `newMesh(ShaderDataType<N>)` fixes the ID's runtime schema and `MeshId<N>` ensures all slots accept it |

Each ticket names an exact requested composition. A later accepted request may make that intermediate
composition unnecessary, completing its ticket as `SUPERSEDED` while the later ticket observes the combined
candidate. Supersession is not retirement: that change may still publish as part of the combined candidate.
A candidate publishes transactionally only after successful compilation; failure leaves the last
ready composition active without partially applying additions or removals. Additions introduced only by a
failed candidate are abandoned, keep permanent fallback ids, and retire their roots. Failed removals leave
their implementations live and may be requested again; later candidates restart from the last ready
composition rather than inheriting failed source. A pending ticket is cancelled during
contribution teardown, and every callback registered before teardown returns before the contribution closes.

There is deliberately no `awaitCompletion`. Compilation completion can require render-thread progress, so
blocking that thread creates a deadlock. A feature that needs an error-free switch registers a ticket
callback and only then publishes replacement geometry or replaces a `SceneHandle` environment binding. A
feature that accepts the surface error implementation or volume vacuum fallback may publish the ID
immediately.

Java's `ProgramAbi.VERSION` and the public `caustica/shaders/api` Slang modules form one ABI. The public
shader inputs include:

- composition, implementation, slot-binding, and placement-instance data words;
- OpenPBR thin-wall selection in the evaluated surface, with only non-thin-walled transmission changing
  the path's volume stack;
- primitive-local barycentrics;
- current/previous object-to-scene and inverse-transpose normal transforms, plus current/previous geometric
  normals for reconstruction-correct normal mapping on moving instances;
- the scene's stable metres-per-scene-unit conversion, including physical-to-scene conversion for volume
  absorption;
- environment dispatch selected per scene;
- versioned push data and typed descriptor-heap indices/accessors.

Shader definitions accept dotted compound module names and namespace-qualified type names. Public types live
under an extension-unique namespace; composition uniqueness applies to the qualified name, not its bare final
identifier. Each surface and each present coverage definition carries its own shader source so their modules
need not share a resolver.

Custom light emission profiles are absent because the current renderer and public Slang composition have
no complete consumer for them. Ordinary OpenPBR surface emission and the built-in light descriptors remain
available.

`OpenPbrSurface` is the complete extension-produced surface value. `SurfaceClosure`, BSDF query/sample
records, event bits, and response-stylization hooks are engine transport details and therefore are not in
the public shader ABI. This keeps value/PDF agreement under one engine-owned BSDF instead of allowing an
extension-only hook to affect one lighting path but not another.

`VolumeProperties` contains exactly one field: a finite, non-negative homogeneous
`absorptionCoefficient` per scene unit. Guide tint, ray epsilon, miss termination, and whether to invoke the
identity-default boundary-lighting hook are renderer policies, not material properties. The boundary hook
is named `evaluateBoundaryLighting` so it cannot be confused with future participating-medium or froxel
transport. Scattering coefficients and phase functions are intentionally absent until the engine has an
actual heterogeneous-volume consumer; adding them will require a shader ABI revision.

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
the root-scene TLAS expose typed immutable `GpuImageDescriptor` and
`GpuAccelerationStructureDescriptor` views whose entries and resources the engine retains through the
current frame. `GpuDescriptorHeapProperties` exposes the unified resource and sampler strides required
when independently compiling pass shaders for `spvDescriptorHeapEXT`.

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
world-view-projection matrix, and a borrowed TLAS for root-scene occlusion queries by world-anchored
overlays. Its layer supplies the display extent and format. It does not expose scene-linear colour or
general scene mutation.

## Package boundary

Core packages contain host-neutral concepts only:

- `api.session`: render-session ownership and teardown.
- `api.retained`: opaque retained identities and atomic retained batches.
- `api.scene`: scene identity, creation metadata, environment binding, and administration.
- `api.geometry`, `api.light`: session-retained world contributions.
- `api.view`: immutable camera state associated with one root scene.
- `api.program`: composed-world Slang inputs and non-blocking compilation observation.
- `api.pass`: GPU frame-recording stages, including post effects and UI.
- `api.gpu`: Vulkan services an extension cannot recreate independently.
- `api.host`: the small bootstrap bridge used by a host adapter.

Minecraft dimension identifiers, level lifetime, camera capture, origin selection, resource reloads, and
dimension-to-scene directories belong to Minecraft integration packages. Presentation policy, DLSS,
screenshots, telemetry, and native queue orchestration belong to engine or integration packages. Optional
helpers that do not appear in an engine signature belong in `api-support`, not in the core contract.
