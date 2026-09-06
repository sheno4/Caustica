# Caustica extension API architecture

The public API is a contribution boundary, not a mirror of the renderer implementation. It exposes the
minimum capabilities needed to add scene content, shader implementations, synchronized GPU work, post
effects, and UI. The contract is explicitly Vulkan-native. Minecraft lifecycle, dimension keys, resource
packs, presentation, upscaling, and native queue ownership remain outside the main API.

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
each live render session and supplies a fresh `RenderSessionContext`. All Vulkan handles, passes, program
registrations, geometry IDs, and light IDs created through that context belong to the contribution scope.

This split is necessary because extension discovery can outlive a Vulkan device. A process object cannot
safely retain a device address or a pass from an earlier device, while a session contribution has an exact
pre-device-destruction boundary.

Session shutdown has one defined order:

1. Stop pass callbacks and reject new scoped registrations.
2. Invoke `RenderSessionContribution.stop()` while accepted submission APIs remain valid.
3. Unschedule passes and logically invalidate objects still owned by the session scope. Stale surface and
   environment references resolve to visible error implementations, stale volumes resolve to vacuum, and
   none of those references pin the owner.
4. Cancel pending compute jobs and program registrations, drain submitted GPU uses, return terminal,
   readiness, and retirement callbacks, then close pass instances.
5. Invoke `RenderSessionContribution.close()` before destroying the device.

`RenderSessionRegistration.close()` requests factory removal and applies this ordering asynchronously to its
active contributions. It prevents future sessions from using the factory but does not wait for active teardown.
Extensions use it for explicit disable or unload; normal session shutdown performs the same cleanup
automatically. Teardown observation, if added, remains callback-only because completion can require render-thread
progress.

## Scenes, views, and ownership

A scene is a retained coordinate space with a stable metres-per-scene-unit scale, placements, lights, an
acceleration structure, and an optional selected environment binding. With no selection the renderer uses its
built-in environment fallback. Multiple scenes may be resident at the same time.

`SceneId` is an opaque, same-session, non-owning reference. Geometry and light operations may name an id
explicitly handed to their contribution, but possession grants no scene creation or
removal authority and does not extend the scene lifetime. The Minecraft integration owns those operations
for the rendered dimensions. Environment bindings may be selected atomically through `SceneEdit.SetEnvironment`; the Minecraft host
also provides a scoped selector for its dimension environment.

The camera is not scene state. A rendered view is:

```java
SceneView view = new SceneView(entryScene, camera, containingMedium);
```

This permits a host to keep several dimensions resident and select one for a camera without moving the
camera into the scene object. The view also carries one homogeneous volume or vacuum containing the primary-ray
origin.
Host-specific lookup such as `MinecraftDimensionKey -> SceneId` belongs in a
Minecraft package. The API sees only the resulting `SceneId` and `SceneView`.

The API does not expose traversal links between scenes. Current multi-scene support isolates identity and
lifetime; each trace root still selects exactly one TLAS. Simultaneous portal traversal needs a new trace ABI
and concrete behavior for visibility, recursion, transforms, lighting, and lifetime before it can earn a
public type.

## Mesh preparation and atomic scene edits

`MeshPreparer.prepare` asynchronously builds an immutable `ReadyMesh<N>`. Its future completes only when
native preparation finishes. Preparation captures the input resource references before returning; callers
may then release their own claims. An optional ready mesh supplies compatible refit input without modifying
that mesh. GPU-generated inputs must finish their `GpuComputeQueue` job before preparation begins.

A ready mesh is a shared owning reference. `retain()` creates another independent claim, and closing a
claim releases it. Placements and captured frames retain their own claims, so a producer can close its
claim after publication. The final release retires native geometry and its input dependencies. A ready mesh
may be shared by multiple placements, scenes, and contributions in the same render session.

`SceneChannel.edit(List<? extends SceneEdit>)` validates and applies one atomic edit containing ready mesh
placements, transforms, removals, lights, and environment selections. It performs no native mesh preparation
or GPU submission. A frame captures the resulting coherent scene state. There is no pending publication or
visibility receipt. Producers keep their current placements until replacements are ready; neighboring
Minecraft sections can therefore switch together in one edit while unrelated transforms continue changing.

`InstanceId` and `LightId` grant mutation authority only to their issuing contribution. A same-session
`LightId` may cross contributions as a non-owning `PrimitiveLightMap` selection; absent lights become
non-sampleable. Scene and program IDs are non-owning selections. Unavailable programs use their documented
fallback and begin resolving to the ready implementation without requiring another mesh preparation.

A producer decides whether a completed replacement is still wanted and closes obsolete results. Closing
a contribution removes its placements and selections and drains accepted preparation before device teardown.
Recurring work required by a frame belongs in a world-resource pass. Private transfer or compute
initialization belongs in `GpuComputeQueue`, followed by mesh preparation and a direct scene edit.

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
does not prescribe whether the bits are an address, descriptor index, or packed value.

`ResourceOwner` is one closeable ownership claim. `retain()` returns an independent claim and `close()`
releases only that handle; a closed handle cannot be retained. `ShaderData` owns a retained dependency
from construction. Program registration, scene edits, preparation, and frame capture retain their own data
copies before returning. The caller closes its data values and resource handles when its own use ends.
Description records borrow the data handles supplied to them. Passes use `frame.retain(owner)` to keep
resources alive through frame completion. Final destruction callbacks run off the render thread after
all claims end. No call implicitly consumes the caller's handle.

Pass-facing `GpuImage` values are borrowed from the enclosing frame. Host image inputs use `OwnedGpuImage`
so recording can retain an independent image claim through completion.

`ProgramChannel.register` invokes one synchronous declaration callback. Its temporary `ProgramBuilder`
issues every surface, volume, and environment ID in one owner set, and the callback returns an immutable,
source-defined export value carrying those typed IDs. Returning commits the whole declaration atomically;
throwing or returning null accepts none of it. The resulting `ProgramRegistration<Exports>` is both the
owner-only removal capability and the non-blocking readiness observation for the complete set. Several
registrations may share renderer-coalesced compilation work without exposing that scheduling as an API
outcome. Observable results are isolated as if accepted sets were composed independently in deterministic
acceptance order: one bad set cannot fail another publishable set, and unaffected later sets are retried
against the last successful composition. Registration close and readiness publication are linearized, so a
pending close which wins cannot be followed by publication.

Surface and volume definitions carry shared `ResourceOwner` dependencies in their typed
`implementationData`. Geometry slots, placement data, and environment bindings carry the same form of
opaque-data dependency. Providers create owning claims with `ResourceFactory` and supply final-release
callbacks. The engine retains claims while programs, scenes, or frames reference the data; callbacks run
after the final claim ends. Closing a program registration removes its implementations without invalidating
resources still retained by an in-flight frame.

The opaque 64-bit vocabulary follows ownership depth consistently:

| Name | Owner and Java source | Shader use |
|---|---|---|
| `compositionData` | Engine-generated `ShaderRootData` | Root table for the active composed program |
| `implementationData` | `ShaderData<?>` in `SurfaceDefinition<B, N>` / `VolumeDefinition<B, N>` | State shared by every binding of one registered implementation |
| `bindingData` | `ShaderData<B>` in a typed surface/volume slot or environment binding | State for one geometry slot or scene environment binding |
| `instanceData` | `ShaderData<N>` in `SceneEdit.SetInstance<N>` | State for one mesh placement; `MeshPreparer.prepare(ShaderDataType<N>, ...)` fixes the schema shared by its slots and placements |

Each registration describes one complete accepted program set. It becomes `READY` only when an active world
program contains the whole set, `FAILED` when none of the set can publish, or `CANCELLED` when the owner
closes it or its contribution ends first. Failed declarations keep permanent fallback IDs and retire their
accepted roots. Failure leaves the last ready composition active. Every callback registered before teardown
returns before the contribution closes.

There is deliberately no `awaitCompletion`. Compilation completion can require render-thread progress, so
blocking that thread creates a deadlock. A feature that needs an error-free switch registers a replacement
set, waits through a readiness callback, publishes replacement geometry or exports a replacement environment
binding to the host selector, and then closes the displaced registration. A feature that accepts the surface error implementation
or volume vacuum fallback may publish an exported ID immediately.

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

`VolumeProperties` contains a finite, non-negative homogeneous `absorptionCoefficient` per scene unit and
a physical index of refraction used at thick boundary crossings. Guide tint, ray epsilon, miss termination,
and whether to invoke the identity-default boundary-lighting hook are renderer policies. The boundary hook
is named `evaluateBoundaryLighting` so it cannot be confused with future participating-medium or froxel
transport. Scattering coefficients and phase functions are intentionally absent until the engine has an
actual heterogeneous-volume consumer; adding them will require a shader ABI revision.

The engine's Slang compiler remains private behind `ProgramChannel`, because it must compose those world
implementations. Independent Vulkan passes own their shader compilation and may package SPIR-V or use any
compiler that produces the documented Vulkan and descriptor-heap ABI. No compiler belongs to the render
session context.

## GPU work and synchronization

The renderer owns Vulkan queues, command buffers, submission, and timeline waits. `GpuComputeQueue` exposes a
contribution-scoped asynchronous recording lane without exposing a raw queue, fence wait, or immediate-submit
operation. Extensions may run arbitrary CPU preparation on their own executors, allocate a private output,
and submit transfer or compute commands which initialize it. The renderer serializes terminal callbacks
through render-session progress and cancels queued jobs during contribution teardown.

A successful job participates in the next graphics submission's timeline dependency. The recorder must still
finish with the access and image-layout barriers required by its eventual consumer. A buffer or image shared
between distinct compute and graphics queue families must use every family returned by
`GpuComputeQueue.sharedQueueFamilyIndices()` or perform an explicit ownership transfer. Publication happens
only from `GpuComputeCompletion.Succeeded`; failed and cancelled jobs publish nothing.

Passes are the frame-ordered recording API. The stages are:

- `addWorldResourcePass`: before tracing, for dirty texture, environment, lookup-table, or buffer updates.
- `addPostEffectPass`: after reconstruction, in the ordered scene-colour chain.
- `addUiPass`: after the display transform, into the display-resolution UI layer.

All three methods accept the same generic `PassFactory<S, F>` and create a `Pass<F>`. The registration
method selects timing, while `F` exposes only the frame resources valid at that stage. A pass has no resize
or resource-pack lifecycle callbacks. It compares current frame facts with the state it built and replaces
resources when they differ. `Pass.close()` is final and occurs only after that pass's submitted uses drain.

Frame execution retains its resource dependencies:

- the internal frame completion reservation follows commands recorded for the current frame.
- `PassFrame.retain` attaches a shared resource claim to the frame execution without exposing GPU completion.
- Shared resource final-release callbacks run after producer, retained scene, job, and frame claims end.

Callbacks must not block or throw. None of these primitives imply that unrelated device work is idle.

Frame input capture retains the selected program, scene revisions, and camera-medium data before recording.
Pass allocations attach to a separate execution-owned resource collection through `PassFrame.retain`.
The renderer releases that collection after accepted work completes, or after abandoned commands cannot
execute. Graphics submission and completion callbacks remain internal platform services.

## Vulkan and descriptor heaps

Every API session has a hard Vulkan 1.4 logical-device baseline: shader int64/int16/float16, storage-image
extended formats and formatless reads/writes, shader draw parameters, demote-to-helper invocation, buffer device
addresses, timeline semaphores, synchronization2, dynamic rendering, unified image layouts, descriptor heaps,
push descriptors, shader objects, untyped pointers, acceleration structures, ray-tracing pipelines, ray queries,
and ray-tracing position fetch. These are guarantees, not runtime capability booleans.

`GpuDevice.vk()` exposes the typed LWJGL `VkDevice`. Extensions obtain its physical device and query Vulkan
features, properties, formats, and limits directly. A physical-device support query does not reveal which
optional features the renderer enabled at logical-device creation, so only the documented baseline is
usable without a separate pre-device agreement. Wide-line primitives are not in the baseline; UI and debug
lines use ordinary triangle geometry.

Retained buffer streams use `VulkanDeviceAddress` and `VulkanDeviceAddressRange` Java values so a device
address cannot be confused with a byte count or unrelated handle. Their payload remains `long` because
`VkDeviceAddress` is an integer typedef and LWJGL provides no wrapper object for it.

The renderer binds one resource heap and one sampler heap before extension passes record. Passes must not
rebind either heap. `GpuDescriptorHeap` assigns resource and sampler ranges with distinct
`GpuDescriptorIndex` types; `GpuDescriptorWriter` encodes
descriptors for extension-owned resources and makes non-coherent writes visible. Engine-owned images and
the entry-scene TLAS expose typed immutable `GpuImageDescriptor` and
`GpuAccelerationStructureDescriptor` views whose entries and resources the engine retains through the
current frame. `GpuDescriptorHeapProperties` exposes the resource stride required when shader or pipeline
creation maps a conventional resource binding to a pushed heap index, plus maximum resource and sampler
allocation sizes. Sampler stride, heap alignment, and total capacity are backend details.

Images and samplers may use direct heap access. Acceleration structures are the deliberate exception: SPIR-V
may declare a conventional binding when shader creation maps it to a resource-heap index stored in pushed data
with `VK_DESCRIPTOR_MAPPING_SOURCE_HEAP_WITH_PUSH_INDEX_EXT`. The mapping still uses a null pipeline layout and
does not permit descriptor-set bind commands.

Heap-native pipelines use a null pipeline layout. Data declared in Slang's push-constant storage class is
recorded with `vkCmdPushDataEXT`, not `vkCmdPushConstants`: ordinary push constants depend on descriptor-set
pipeline-layout state, and either command family invalidates state from the other. Passes must not record
descriptor-set, descriptor-buffer, push-descriptor, or ordinary push-constant commands while using the
renderer-bound heaps.

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
`acquireSceneColorOutput()`. The output is distinct from the input and must be fully written. Every post
effect has a stage-local `PassId` and may declare one `PassPlacement.before` or `after` relationship. A
missing anchor leaves the effect unconstrained so optional effects remain independently installable, then
activates if that anchor arrives later; global acceptance order breaks otherwise-unconstrained ties. The
registration that would create a cycle is rejected, as are duplicate live ids. UI passes use the same narrow
ordering contract, without first/last sentinels or a public render graph.

UI is a separate display-resolution layer. `UiFrame` includes the immutable `SceneView`, an unjittered
world-view-projection matrix, and a borrowed TLAS for entry-scene occlusion queries by world-anchored
overlays. Its layer supplies the display extent and format. It does not expose scene-linear colour or
general scene mutation.

## Package boundary

The main artifact is Vulkan-native while remaining independent of Minecraft and loader lifecycle:

- `api.session`: render-session ownership and teardown.
- `api.scene`: scene identity, environment bindings, and atomic scene edits.
- `api.geometry`, `api.light`: immutable ready meshes, preparation, placements, and light descriptors.
- `api.view`: immutable camera state associated with one entry scene and containing medium.
- `api.program`: composed-world Slang inputs and non-blocking compilation observation.
- `api.pass`: GPU frame-recording stages, including post effects and UI.
- `api.vulkan`: Vulkan services an extension cannot recreate independently.

Minecraft dimension identifiers, level lifetime, camera capture, origin selection, resource reloads, and
dimension-to-scene directories belong to Minecraft integration packages. Presentation policy, DLSS,
screenshots, telemetry, and native queue orchestration belong to engine or integration packages. Optional
pure-Java helpers that do not appear in an engine signature belong in the separate `packages/api-support`
artifact, not in the main contract.
