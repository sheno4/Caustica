# Caustica 0.8 API showcase

This module is an additive, compile-only design probe. It is intentionally not a mod and is not wired into
Fabric or NeoForge discovery. It tests whether one API consumer can express the current public feature
categories without importing renderer implementation packages.

It cannot run against the repository today: the 0.8 session, scene, program, pass, geometry, and light
channels have contracts and unit tests in `packages/api`, but this probe deliberately has no loader entrypoint.
The runnable example is split into reusable content at `packages/examples/gltf-content` and Minecraft lifecycle
integration at `packages/examples/gltf-viewer-minecraft`.

## Use-case matrix

| API surface | Concrete case in this probe | Why it belongs where it is | Pressure-test result |
|---|---|---|---|
| process registration and session contribution | device recreation creates a fresh `ShowcaseSession` | Vulkan session ownership | Necessary; process objects must not retain handles from an old device. |
| surface plus coverage | opaque metal and alpha-cut foliage | world-program composition | Separate coverage is necessary for traversal; an opaque surface should not pay for it. |
| volume | glass boundary with absorption and a volume-only fog boundary | world-program composition | The narrow absorption ABI fits both surface boundaries and volume-only geometry. |
| initial view medium | explicit vacuum and camera beginning underwater | generic view state plus volume dispatch | `SceneView` carries either `ViewMedium.Vacuum` or the typed volume binding and instance data selected by the host. |
| environment | one exported gradient-sky binding | scene/program boundary | Necessary; the Minecraft dimension selector applies the binding to its host-owned scene. |
| retained geometry | one mesh with opaque, cutout, surface+volume, and volume-only slices | geometry channel | Issued IDs and atomic batches fit shared resident meshes and many placements. |
| retained lights | rectangle, circular spot, and distant descriptors | light channel | The three shapes map directly to emissive faces, the helmet light, and sun/moon. |
| world-resource pass | publish a replacement descriptor/table root before tracing | pass/GPU boundary | Stage is necessary for GPU state that tracing must consume in the same frame; `PassFrame` already carries `SceneView` and time for camera-dependent uploads. |
| post effect | read scene color/exposure, acquire a distinct output, and order after optional bloom | pass boundary | Necessary for bloom and colour grading. Stable ids plus one optional anchor avoid relying on extension discovery order. |
| UI pass | draw a world marker using camera and entry-scene TLAS | pass boundary | Necessary for outlines/name tags and correctly separate from scene-linear color. |
| descriptor heap and retirement | typed resource/sampler ranges, borrowed descriptors, per-frame and prior-use cleanup | Vulkan boundary | Necessary for independent passes; raw VMA allocation still benefits from an optional Vulkan support package. |
| shader object | create a pass-owned compute shader from direct SPIR-V | Vulkan support boundary | `ShaderObjectCompute` supplies descriptor-heap validation, creation, binding, push data, dispatch, and destruction without moving shader ownership into the renderer. |
| settings lookup | snapshot one feature-scoped colour-grade option during pass recording | process API plus settings API | Declaration stays process-scoped; the pass reads a stable snapshot without reaching host storage. |

## Refactor decisions proven by the probe

- `ShowcasePrograms` declares its four shader implementations through one atomic `ProgramRegistration` and
  exports one typed ID record. The registration itself exposes the complete set's state, failure, and
  non-blocking completion callback; closing that same capability is the only removal authority.
- `ShowcaseScene` receives that export record and a host-issued `SceneId`, proving the intended explicit
  cross-contribution handoff shape. It can select those surface/volume IDs but cannot close their program
  registration or administer the scene.
- Mesh streams use retained `VulkanDeviceAddressRange` values instead of four interchangeable primitive
  arguments. Descriptor ranges and borrowed indices preserve resource-versus-sampler type information.
- `ShowcaseSession.vacuumView` and `underwaterView` reuse the single host-issued scene identity. They vary
  only the camera's containing medium and do not imply scene creation, selection, or portal authority.
- `ApiShowcaseExtension` declares its option independently of render-session creation. The post pass uses
  `OptionLookup.snapshot()` before reading the feature-scoped value used for that frame.
- `GpuFrameUse.whenComplete` is the frame-scoped callback for both resource retirement and positive
  completion work, such as publishing a mesh after its staging upload completes. It follows this frame's
  recorded commands, runs once on the renderer thread, and does not wait for later or unrelated GPU work.
- Public scene creation/closing is absent. `ShowcaseSession.openScene` and `closeScene` model the notifications
  a future Minecraft API supplies, while the environment binding is exported back to the host selector.

## Remaining API pressure

- Post and UI passes have stable stage-local ids and may declare one optional `before` or `after` anchor.
  The showcase colour grade follows bloom when bloom is installed, and its marker precedes Minecraft's
  world overlay when that pass is installed. Missing anchors leave a pass unconstrained and activate if the
  anchor arrives later; ids are stage-local and unconstrained ties retain global acceptance order.
- The three identity-checked `ShaderDataType` layers prevent implementation/binding/instance word mixups,
  which is useful, but they make a multi-material mesh require one deliberately shared instance marker.
  Generated schema tokens tied to reflected Java records would preserve the safety with less handwritten
  phantom-type ceremony.
- Scene identity remains host-issued and non-owning. The showcase intentionally demonstrates one scene
  referenced consistently by geometry, lights, environment bindings, and views, with no scene-control API.
- Public `SceneView` carries the typed medium containing the camera origin. It remains separate from `Camera`
  because camera pose/projection and the host's sampled containing medium have different ownership. The host
  selects Minecraft water; no public volume-selection feature channel is needed while only the host creates
  rendered views.
## Deliberate runtime guards

`ShowcasePasses.runtimeGpuRecordingEnabled()` always returns false. The guarded branches touch the borrowed
image, descriptor, output-chain, settings-snapshot, and retirement APIs but do not record valid Vulkan
commands. Enabling them would violate the post-effect full-write contract. `createComputeShader` demonstrates
the supported shader-object construction boundary; a runnable pass still needs packaged SPIR-V, push-data
records, output writes, and owned Vulkan resources.

`ShowcaseScene.publishMesh(...)` accepts externally uploaded device addresses. The main API deliberately
has no CPU mesh or texture uploader. A real glTF consumer therefore needs to write substantial VMA,
staging, descriptor, and retirement code before it can submit a `MeshBuild`. That is a strong case for a
separate reusable upload support package, not for putting upload policy in the main API. Shader-object
creation is already covered by `vulkan-support` and is consumed directly by this probe.

## Missing Minecraft boundary

A Minecraft extension needs a host-owned `SceneId` for each loaded dimension and notifications for scene
open/close, active-view changes, and resource reload. The main API should continue to know only `SceneId` and
`SceneView`. A separate Minecraft API should expose a read-only dimension-to-scene directory and a narrow
environment/sky contribution channel; it should not expose the engine's scene administration capability.

## Slang reflection reuse

The independently publishable `dev.comfyfluffy.caustica.slang-tooling` Gradle plugin provides reusable
compile, reflection, and typed-record generation tasks. This package applies it directly: `check` compiles
the world-model validation probe and all three pass entry points against the public `shader-api` modules,
then validates Vulkan 1.4 SPIR-V 1.6. The generated validation binaries remain build outputs and are not
added to the showcase JAR.

Each package owns its probes, generated Java namespace, and concrete record manifest. The tooling plugin
owns compiler discovery, target/profile conventions, reflection parsing, and reproducible task inputs and
outputs, so extension packages can reuse compile-time validation without receiving the engine's runtime
Slang compiler through `RenderSessionContext`.
