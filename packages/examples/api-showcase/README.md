# Caustica 0.8 API showcase

This module is an additive API-consumer package. It is intentionally not a mod and is not wired into
Fabric or NeoForge discovery. It tests whether cooperating Minecraft world-session contributions can express the
current public feature categories without importing renderer implementation packages.

It cannot run as a standalone extension today: the session, scene, program, pass, geometry, light, and
Minecraft lifecycle channels have implementations and tests, but this probe deliberately has no loader entrypoint.
The runnable example is split into reusable content at `packages/examples/gltf-content` and Minecraft lifecycle
integration at `packages/examples/gltf-viewer-minecraft`.

## Use-case matrix

| API surface | Concrete case in this probe | Why it belongs where it is | Pressure-test result |
|---|---|---|---|
| Minecraft registration and world contribution | every client-world epoch creates a selection owner and a geometry/pass consumer with distinct borrowed renderer scopes over one host scene | Minecraft/Vulkan session ownership | Necessary; process objects retain only a CPU rendezvous, never handles from an old device or world. |
| surface plus coverage | opaque metal and alpha-cut foliage | world-program composition | Separate coverage is necessary for traversal; an opaque surface should not pay for it. |
| volume | glass boundary with absorption and a volume-only fog boundary | world-program composition | The narrow absorption ABI fits both surface boundaries and volume-only geometry. |
| initial view medium | explicit vacuum and camera beginning underwater | generic view state plus volume dispatch | `SceneView` carries one homogeneous medium containing the primary-ray origin: either `ViewMedium.Vacuum` or the typed volume binding and instance data selected by the host. |
| environment and dimension sky | select exported overworld, Nether, or End sky content after the program set is ready | Minecraft scene/program boundary | Dimension keys and sky policy stay in `ShowcaseMinecraftSky`; core sees only an environment id and binding. |
| retained geometry | upload one shared vertex/index allocation, then atomically publish its mesh and first placement as independently retired batches | geometry channel | Mandatory grouped publication is necessary when placement must never observe an absent mesh but the mesh allocation must not inherit placement lifetime. |
| retained lights / NEE-AT | contribution A owns parallelogram, circular spot, and distant descriptors; contribution B selects one through a primitive-to-light map | light channel | A handed `LightId` is useful without transferring set/drop authority. NEE-AT remains renderer policy and needs no extension-facing backend token. |
| world-resource pass | record a device-addressable world-mesh upload, then publish it after that frame completes | pass/GPU boundary | This is the real asynchronous handoff needed by a producer that owns upload work while the renderer owns acceleration construction. |
| post effect | read scene color/exposure, acquire a distinct output, and order after optional bloom | pass boundary | Necessary for bloom and colour grading. Stable ids plus one optional anchor avoid relying on extension discovery order. |
| UI pass | draw a screen marker whose tint is gated by an inline query against the entry-scene TLAS | pass boundary | The unplaced overload and acceleration-structure push-index mapping are real; the current marker does not consume the camera/WVP. |
| descriptor heap and retirement | borrowed image/TLAS indices, an owned resource/sampler table, and a retained world buffer | Vulkan boundary | Post/UI recording consumes borrowed descriptors. The descriptor fixture proves write, publish-before-retire replacement, and typed range cleanup; the mesh buffer transfers to geometry retirement after grouped publication. |
| shader objects | create pass-owned compute and graphics shader objects from direct SPIR-V | Vulkan support boundary | The support package supplies descriptor-heap validation, fully dynamic graphics state, push data, dispatch/draw binding, and destruction without moving shader ownership into the renderer. |
| two-scene selection | construct views and isolated retained operations for two distinct host-issued scene ids | generic view boundary | This proves identity targeting only. One trace still selects one TLAS; a future simultaneous portal renderer needs a new trace ABI. |
| settings lookup | snapshot one feature-scoped colour-grade option during pass recording | process API plus settings API | Declaration stays process-scoped; the pass reads a stable snapshot without reaching host storage. |

## Refactor decisions proven by the probe

- `ShowcasePrograms` exports six typed IDs spanning seven definitions (two surfaces, one coverage, one volume,
  and three environments) through one atomic `ProgramRegistration`. The registration itself exposes the
  complete set's state, failure, and non-blocking completion callback; closing that same capability is the only
  removal authority.
- `ApiShowcaseExtension` registers two factories in dependency order. `ShowcaseSelectionContribution` owns
  program and light mutation in contribution A. `ShowcaseSession` owns geometry and passes in contribution B.
  `ShowcaseHandoff` keys their ordinary Java value handoff by the host-issued scene identity and removes the
  rendezvous when A stops; it is not renderer state or a new public registry.
- `ShowcaseScene` receives A's surface, volume, environment, and light IDs as non-owning selections. Its
  `GeometryChannel` can set/drop only B's mesh and instance IDs; it has no `ProgramChannel` or `LightChannel`
  with which to mutate A's objects. Lifecycle tests construct distinct renderer scopes and make those forbidden
  capabilities fail immediately if B attempts to access them.
- Mesh streams use retained `VulkanDeviceAddressRange` values instead of four interchangeable primitive
  arguments. Descriptor ranges and borrowed indices preserve resource-versus-sampler type information.
- `ShowcaseSession.vacuumView` and `underwaterView` reuse the single host-issued scene identity. They vary
  only the camera's containing medium and do not imply scene creation, selection, or portal authority.
- `ApiShowcaseExtension` declares its option independently of render-session creation. The post pass uses
  `OptionLookup.snapshot()` before reading the feature-scoped value used for that frame.
- `ShowcasePrograms` records readiness in an atomic flag and supports multiple lightweight readiness consumers.
  Environment selection runs from that completion seam, while the world-resource pass waits for the same
  coherent program set before uploading and publishing program-referencing geometry exactly once.
- `GpuFrameUse.whenComplete` is the frame-scoped callback available for resource retirement and positive
  completion work. The world mesh hands its allocation to the first grouped geometry batch and receives it
  through that batch's retirement callback after drop/session teardown. `ShowcaseDescriptorTable` separately
  allocates and writes typed resource/sampler ranges, publishes the replacement generation, and retires the
  displaced ranges with `GpuDevice.retireAfterUse`. Built-in bloom applies the same rule to resize-driven
  `VmaImage2D` replacement.
- `GeometryPublication` provides native-publication backpressure independently of upload completion. The world
  pass retains the receipt returned by `submitGroup`, polls `isVisible()` on later renderer callbacks, and does
  not report its handoff published until the native scene commit becomes visible.
- Public scene creation/closing remains absent. The implemented Minecraft host owns the scene and brackets
  both showcase contributions with the world-session lifecycle.

## Remaining API pressure

- Post and UI passes have stable stage-local ids. The showcase colour grade uses the proven optional bloom
  anchor. The marker uses the unplaced UI overload because this probe has no published engine UI anchor to name.
- The five identity-checked `ShaderDataType` layers prevent implementation/binding/instance word mixups,
  which is useful, but they make a multi-material mesh require one deliberately shared instance marker.
  Generated schema tokens tied to reflected Java records would preserve the safety with less handwritten
  phantom-type ceremony.
- Scene identity remains host-issued. The live Minecraft contribution uses its one borrowed world scene.
  `ShowcaseSceneSwitch` demonstrates type-level selection between two supplied scene ids, while
  `ShowcaseSceneApiTest` checks isolated operation targeting, retained replacement, and cross-scene placement
  movement with test channels. Renderer tests separately verify per-scene content partitioning. These prove
  future-compatible identity boundaries, not simultaneous portal rendering. A ray portal needs a new
  multi-scene trace ABI because the current trace root selects exactly one TLAS.
- Public `SceneView` carries the typed medium containing the camera origin. It remains separate from `Camera`
  because camera pose/projection and the host's sampled containing medium have different ownership. The host
  selects Minecraft water; no public volume-selection feature channel is needed while only the host creates
  rendered views.
## GPU recording

The post and UI pass factories load package-owned SPIR-V and create their shader objects once for the render
session. The post pass acquires a distinct chain output, writes reflected push data, and dispatches across every
output pixel with bounds checks. The UI pass begins dynamic rendering with `LOAD`/`STORE` on the borrowed
`GENERAL` layer, maps its conventional acceleration-structure binding to the pushed entry-scene TLAS heap
index, performs an inline query, draws a small screen-space marker, and ends rendering. Each pass destroys its
shader objects from `Pass.close()`, after the host has stopped callbacks and drained their GPU uses. The
world-resource pass needs no shader object: it records a buffer update and synchronization2 barrier. A later
world-resource callback submits the typed buffer ranges after that upload frame has completed, then continues
polling the returned publication receipt until the native scene commit is visible.

`ShowcasePasses.worldResource(...)` allocates one device-addressable VMA buffer and records its tiny demonstration
mesh with `vkCmdUpdateBuffer`. Its frame-completion callback only marks the upload ready; a later pass callback
publishes the typed vertex/current/previous/index ranges. The mesh and placement arrive in one `submitGroup`,
while only the mesh batch owns the buffer retirement callback. The main API
deliberately has no CPU mesh or texture uploader; a larger glTF consumer still needs reusable staging and
texture upload support. `vulkan-support.VmaImage2D` and `features-builtin.BloomPass` cover the useful
owned-image/descriptor allocation and replace-retire path.
Shader-object creation is covered by `vulkan-support` and is consumed directly by the post and UI passes.

## Minecraft boundary

The implemented Minecraft API supplies one host-owned `SceneId`, renderer contribution scope, resource epoch,
dimension key, and environment-selection slot to each world contribution. `ShowcaseMinecraftSky` maps that
Minecraft-owned key to one of three exported sky implementations and carries the applied resource-pack epoch in
its binding data. The main API continues to know only `SceneId`,
`SceneView`, and `EnvironmentBinding`; extensions receive no engine scene-administration capability.

The host also owns primary-origin camera-volume classification. `ShowcaseSession.underwaterView` shows the
result of that classification as one homogeneous typed `ViewMedium.Volume`; it does not query Minecraft blocks
or choose water itself. A
standalone execution cannot manufacture the host scene, descriptor heap, command buffer, or world epoch. Once a
host opens `ShowcaseSession`, however, its registered post/UI passes are real recording paths rather than guarded
API sketches. The glTF viewer packages provide the independently launchable content/host split.

## Slang reflection reuse

The independently publishable `dev.comfyfluffy.caustica.slang-tooling` Gradle plugin provides reusable
compile, reflection, and typed-record generation tasks. This package applies it directly: `check` compiles
the world-model validation probe and the world-resource, post, and UI shader entry points against the public `shader-api` modules,
then validates Vulkan 1.4 SPIR-V 1.6. `GenerateShaderRecords` reflects `ShowcasePostPush` and `ShowcaseUiPush`
from the package-owned `showcase_pass_types` module into typed Java records consumed by `ShowcasePasses`; no
hand-maintained byte offsets remain in the example. Generated Java and validation binaries remain build outputs.

Each package owns its probes, generated Java namespace, and concrete record manifest. The tooling plugin
owns compiler discovery, target/profile conventions, reflection parsing, and reproducible task inputs and
outputs, so extension packages can reuse compile-time validation without receiving the engine's runtime
Slang compiler through `RenderSessionContext`.

The world-resource shader remains compile-validation coverage for package-owned compute entry points; the
corresponding pass uses transfer commands because its concrete resource handoff does not require computation.
The post and UI binaries are shader entry points exercised by live recording code.
