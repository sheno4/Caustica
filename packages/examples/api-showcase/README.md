# Caustica API showcase

This package exercises the extension API without importing renderer implementation packages. It has no
loader entrypoint. The runnable example is `packages/examples/gltf-viewer-minecraft`, with reusable mesh
and program content in `packages/examples/gltf-content`.

## Contributions and sharing

`ApiShowcaseExtension` registers two Minecraft world contributions for the same host-issued scene.
`ShowcaseSelectionContribution` owns shader registrations, environment selection, and lights.
`ShowcaseSession` owns mesh preparation, placements, and passes. `ShowcaseHandoff` passes typed program and
light selections between them using ordinary Java values keyed by scene identity.

Program and light selections do not transfer mutation authority. Each contribution receives its own
`SceneChannel`; instance and light IDs can only be changed through the channel that issued them.
`ShowcaseScene` uses shared surface and volume implementations and selects a light through a primitive map.

## Mesh preparation and publication

The world-resource callback starts a one-shot upload on `GpuComputeQueue` after the shader set is ready.
After GPU upload completes, `MeshPreparer` builds an immutable `ReadyMesh`. `ShowcaseScene` publishes it
with one direct `SceneEdit.SetInstance`. Its future completes after that edit succeeds.

Replacement keeps the previous placement visible until preparation finishes. Publication uses the latest
placement transform and target scene. Stopping or superseding a request releases its eventual result.
Ready meshes and their opaque shader data remain alive through independently retained scene and frame
claims; closing the producer claim does not invalidate a captured frame.

## Shader and pass examples

- Typed surface, coverage, volume, and environment implementations share one program registration.
- Views demonstrate vacuum and a camera starting inside a homogeneous volume.
- A post effect reads scene color and exposure, acquires a distinct output, and runs after optional bloom.
- A UI pass performs an inline query against the entry-scene TLAS and draws a screen marker.
- `ShowcaseDescriptorTable` demonstrates owned descriptor ranges and deferred replacement cleanup.
- Shader objects and reflected push data remain owned by their pass instances.

Each trace selects one scene TLAS. Examples of switching target scenes demonstrate identity and ownership;
simultaneous portal traversal requires a separate trace implementation.

## Verification

The tests cover typed program handoff, contribution lifetime, delayed mesh readiness, replacement using the
latest transform, rejected edits, stale completion cleanup, and frame ownership surviving producer shutdown.
Run `gradlew examplesCheck` from the repository root.
