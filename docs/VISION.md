# Vision

This document defines the project's architectural commitments and invariants. Existing APIs, package boundaries, and implementation architecture are not compatibility constraints. Other implementation choices may change while preserving these commitments.

## Goal

Caustica is a Vulkan-native, Minecraft-independent path-tracing platform built on Vulkan 1.4, ray tracing, and descriptor heaps.

Minecraft extracts content, converts it to scene vocabulary, requests rendering, and connects the output to its presentation path. It uses the same engine capabilities available to other consumers.

Public boundaries may expose Vulkan, LWJGL, and VMA concepts when they are the actual contract.

## Core concepts

These are responsibilities and data concepts, not prescribed packages or interface hierarchies.

- **Engine:** maintains retained scene state, prepares rendering resources, and records rendering work for the consumer.
- **Scene:** owns instances, primitive lights, and an environment selection.
- **Mesh:** an immutable, ready geometry revision with its acceleration structure and resource dependencies. Meshes can be shared across scenes.
- **Material:** a pluggable shader implementation and its data for surfaces, volumes, or environments. Materials can be shared across scenes.
- **Frame:** stable inputs captured for one rendering request, retaining the scene, mesh, and material revisions it uses.
- **Platform:** provides Vulkan device services, asynchronous compute, and submission/completion integration.
- **Producers and extensions:** supply content, materials, and rendering passes. Minecraft is one producer and host.

## Scene edits and publication

Scenes retain their content until its owner replaces or removes it. Multiple scenes may exist simultaneously. Instance identity persists across updates so rendering can track motion independently of mesh revision identity.

Scene edits update the retained database directly. Calls are thread-safe; this does not require lock-free mutation or affect an already captured frame.

Resource preparation and scene publication are separate. Uploads, shader preparation, and mesh builds produce usable revisions before an edit publishes references to them. Scene mutation performs no GPU allocation, compilation, or submission and does not wait for GPU preparation.

An explicit edit group publishes all its changes atomically. A snapshot sees either the state before the group or the state after it, never a subset. A group may update multiple instances and lights, including entries in multiple scenes when necessary.

Minecraft needs this for neighboring sections: independently prepared replacement meshes must become visible together to avoid temporary holes at section boundaries. The old group remains visible until every required replacement is ready. Unrelated updates need not wait for that group.

The scenes requested for a frame are captured at one coherent snapshot boundary. No accepted render work may observe partially published state. Completed work that has been superseded or whose target was removed must not restore obsolete content. Producers decide which prepared results are still wanted; every intermediate result need not be published.

## Materials and opaque shader data

Materials use Slang to supply surface, volume, and environment behavior. A surface shader produces the supported subset of OpenPBR parameters; the engine owns BSDF evaluation and light sampling. Volume and environment shaders use contracts appropriate to those domains.

Engine-visible color values use ACEScg.

Mesh, instance, and material producers may supply an opaque `u64` value forwarded unchanged to the shader. It may be a GPU pointer, packed data, or another producer-defined value. The engine does not interpret its representation. A shader may dereference a buffer and use descriptor-heap indices stored there to access textures.

Opaque data uses the same shared ownership model as other resources. Its value is accompanied by an owning reference whose provider supplies the final-release callback. That owner keeps every reachable buffer, texture, and descriptor allocation valid; it may retain other shared owners internally. The engine need not understand that dependency graph.

Published data and its dependencies remain stable while readers retain the revision. Updates publish a new revision. A value with no external dependencies needs no resource owner.

## Ownership and GPU lifetime

Resources exist independently of scene membership. Removing a scene or instance releases its ownership claims; it does not invalidate resources retained by another scene, a frame, a producer, or a job.

Ownership follows an `Arc`-like model:

```text
Frame -> Mesh -> BLAS and geometry buffers
      -> Material / instance data -> provider-owned resources
Build job -> input resources and any source BLAS
```

Each owner retains its dependencies. Accepted GPU work retains everything it can access through completion. Temporal history also retains any previous resources it still needs. Snapshot-specific acceleration structures such as TLAS remain valid for every submitted use of that snapshot.

The engine releases its references when its uses end. Final release occurs only after all ownership claims end, including the provider's claims. The provider's destruction callback runs exactly once for the shared owner; destruction work is delivered off the render thread. A callback may release dependencies that are still independently retained elsewhere.

Shared ownership establishes lifetime. GPU synchronization separately establishes readiness and legal access ordering.

## Asynchronous compute and mesh preparation

The platform exposes an asynchronous compute service. A producer supplies command-recording work and ownership references that keep dependencies alive through execution. Completion notification permits the producer to consume the result or publish ready content. This service does not need scene or extension lifecycle concepts.

The engine's mesh preparation accepts geometry inputs suitable for acceleration-structure construction and produces a ready mesh revision. It may build a new BLAS or refit from an available compatible revision. Source resources stay alive through the build; published revisions remain unchanged.

Work required by the current frame belongs in that frame's ordered rendering work. Background preparation does not force the frame to wait for an unfinished replacement.

## Frames and rendering stages

Keep three responsibilities distinct:

- **Frame inputs:** captured scene revisions, camera, settings, and selected resource revisions.
- **Frame execution:** command recording and ownership through GPU completion.
- **View history:** previous transforms, denoiser history, and other temporal state for a rendered view.

Immutable inputs do not imply immutable render targets or deep copies of every resource. Separate views maintain separate temporal histories.

```text
Snapshot scenes
-> Resource Build
-> Trace
-> Denoise / Upscale
-> Post Processing
-> UI
-> Display Transform
-> Present through the host
```

Trace may contain multiple passes, including light and guide passes. Resource Build records work consumed later in the same frame, such as environment LUT generation.

The engine provides injection points at Resource Build, Post Processing, UI, and Display Transform. Extensions own their pass implementations and pass-local resources. Stage contracts define input/output color spaces, resolution, ordering, and synchronization responsibilities. UI composition follows the engine's SDR/HDR color contract.

## Simplicity

- Use direct scene edits and one explicit atomic grouping mechanism. Avoid distinct publication protocols for each combination of geometry, lights, and transforms.
- Start with a lock protecting retained database edits and snapshot capture. Keep expensive preparation outside it; change the strategy only when measurements justify it.
- Replace mesh references on affected instances explicitly. Automatic propagation through mutable mesh handles requires a concrete producer need.
- Use shared ownership consistently for mesh, material, opaque data, job, and frame dependencies. Additional lifetime mechanisms must represent a distinct requirement.
- Start with an ordered rendering sequence and small stage hooks. A general render graph or additional public tracing hooks require concrete consumers.
- Keep Minecraft lifecycle, content conventions, configuration persistence, and presentation integration outside the renderer core.
- Future scenarios identify constraints but do not automatically require present machinery. Multiple scenes are required; portal traversal is a separate feature.
- Judge simplification by fewer mutable representations, lifecycle protocols, and concepts a producer must understand. File count and line count are not the objective.

## Performance targets

Target configuration:

- 4K output with DLSS Ray Reconstruction in Performance mode.
- 32-section render distance without LOD.
- NVIDIA RTX 5070 Ti.

Targets:

- Low render-thread CPU cost, with heavy preparation on workers or asynchronous GPU compute.
- Approximately one frame of retained mesh/light update latency when preparation completes in time.
- 50+ FPS in representative gameplay scenes.

Optimization should be justified by measured cost. These targets do not prescribe caches, buffering schemes, or concurrent data structures.
