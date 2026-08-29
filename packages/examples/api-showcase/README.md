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
| volume | glass boundary with absorption and an invisible fog-boundary proposal | world-program composition | The narrow absorption ABI fits; independent registration and volume-only geometry remain provisional until rendered. |
| initial view volume | camera beginning underwater | internal frame ingress plus generic volume dispatch | Required by the existing renderer. Minecraft chooses water; engine transport consumes it. It is intentionally not public while extensions cannot submit rendered views. |
| environment | one exported gradient-sky binding | scene/program boundary | Necessary; the Minecraft dimension selector applies the binding to its host-owned scene. |
| retained geometry | one mesh with opaque, cutout, surface+volume, and volume-only slices | geometry channel | Issued IDs and atomic batches fit shared resident meshes and many placements. |
| retained lights | rectangle, circular spot, and distant descriptors | light channel | The three shapes map directly to emissive faces, the helmet light, and sun/moon. |
| world-resource pass | publish a replacement descriptor/table root before tracing | pass/GPU boundary | Stage is necessary. The current frame lacks `SceneView` and time, which a camera-dependent atmosphere upload needs. |
| post effect | read scene color/exposure and acquire a distinct output | pass boundary | Necessary for bloom. Cross-extension registration order is not a stable ordering contract. |
| UI pass | draw a world marker using camera and root-scene TLAS | pass boundary | Necessary for outlines/name tags and correctly separate from scene-linear color. |
| descriptor heap and retirement | typed resource/sampler ranges, borrowed descriptors, per-frame and prior-use cleanup | Vulkan boundary | Necessary for independent passes; raw VMA allocation still benefits from an optional Vulkan support package. |

## Refactor decisions proven by the probe

- `ShowcasePrograms` declares its four shader implementations through one atomic `ProgramRegistration` and
  exports one typed ID record. One readiness result covers the complete set, and closing the registration is
  the only removal authority.
- `ShowcaseScene` receives that export record and a host-issued `SceneId`, proving the intended explicit
  cross-contribution handoff shape. It can select those surface/volume IDs but cannot close their program
  registration or administer the scene.
- Mesh streams use retained `VulkanDeviceAddressRange` values instead of four interchangeable primitive
  arguments. Descriptor ranges and borrowed indices preserve resource-versus-sampler type information.
- Public scene creation/closing is absent. `ShowcaseSession.openScene` and `closeScene` model the notifications
  a future Minecraft API supplies, while the environment binding is exported back to the host selector.

## Remaining API pressure

- Post and UI passes compose in registration order, but extension discovery order is not a stable semantic
  order. Bloom-before-colour-grade and marker-before-HUD are real ordering requirements. Pass registration
  needs stable IDs plus constrained `before`/`after` relationships, or a smaller set of named anchors.
- The three identity-checked `ShaderDataType` layers prevent implementation/binding/instance word mixups,
  which is useful, but they make a multi-material mesh require one deliberately shared instance marker.
  Generated schema tokens tied to reflected Java records would preserve the safety with less handwritten
  phantom-type ceremony.
- `GpuFrameUse.retire` is also the only completion callback available for a staging upload. Using a method
  named “retire” to publish a newly ready mesh is legal in shape but unclear. A neutral `whenComplete` name,
  with retirement as a documented use, better represents both cases.
- Public scene creation remains deferred. A future ray portal justifies keeping `SceneId` in geometry, lights,
  and views, but it will also need a destination transform, lifetime link, hop policy, and shader-visible TLAS
  selection. Add scene creation and a retained portal-link capability together with that implementation.
- Public `SceneView` does not express that primary rays start inside water, even though the current Minecraft
  adapter and renderer implement that case. Add one optional typed initial-volume binding to internal
  `FrameInput`, carrying the registered volume plus its binding and instance words. Keep it out of `Camera`,
  because camera pose/projection and the host's sampled containing medium have different ownership. Do not add
  a public volume-selection feature channel while only the host can create a rendered view.
## Deliberate runtime guards

`ShowcasePasses.runtimeGpuRecordingEnabled()` always returns false. The guarded branches touch the borrowed
image, descriptor, output-chain, and retirement APIs but do not record valid Vulkan commands. Enabling them
would violate the post-effect full-write contract. Real commands require pipelines and resources which are
outside a compile-only probe.

`ShowcaseScene.publishMesh(...)` accepts externally uploaded device addresses. The main API deliberately
has no CPU mesh or texture uploader. A real glTF consumer therefore needs to write substantial VMA,
staging, descriptor, and retirement code before it can submit a `MeshBuild`. That is a strong case for a
separate reusable `vulkan-support` package, not for putting upload policy in the main API.

## Missing Minecraft boundary

A Minecraft extension needs a host-owned `SceneId` for each loaded dimension and notifications for scene
open/close, active-view changes, and resource reload. The main API should continue to know only `SceneId` and
`SceneView`. A separate Minecraft API should expose a read-only dimension-to-scene directory and a narrow
environment/sky contribution channel; it should not expose the engine's scene administration capability.

## Slang reflection reuse

The repository's reflection generators are currently hard-coded `buildSrc` tasks with engine package names
and fixed probe lists. Move the reflection parser/generator into a reusable Gradle plugin package with a
declarative per-module manifest: source roots, entry point, reflected struct, Java package/class, layout,
and whether a reader is required. Publish the public Slang ABI as an input configuration. The plugin should
generate records/serializers plus a compiler-option and source-hash manifest, and validate all four world
interfaces and independent pass entry points at build time. This lets extension packages reuse reflection
without exposing the engine's runtime Slang compiler through `RenderSessionContext`.
