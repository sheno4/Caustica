# Retained Geometry Decisions

## Packed static geometry is coordinated before its public declaration is widened

Terrain produces pre-packed streams, triangle-class splits, and optional opacity micromaps. Repacking
those streams through the public `TriangleMesh` shape would discard the worker-built representation and
would make terrain synchronous or consumer-specific again. The engine therefore owns a generic packed
geometry coordinator that accepts a packed build candidate, schedules its upload and BLAS lifecycle,
publishes stable geometry-table records, and retires all associated resources.

`RtTerrain` remains responsible only for Minecraft-specific snapshotting, meshing, desired-window
membership, cancellation tokens, and light extraction. It does not allocate, submit, compact, publish,
or retire GPU geometry resources directly. This preserves asynchronous terrain extraction and opacity
micromap/BLAS-compaction behavior while moving those GPU concerns behind an engine API.

Build candidates and terminal executor tokens are opaque outside the coordinator. A source accepts a
completed candidate only after its own cancellation/epoch checks; the coordinator then marks the hidden
build token published. This keeps source-side validity decisions separate from engine-side GPU lifetime
tracking without exposing a second resource-management API.

The retained table implementation is used by the coordinator. Terrain, entities, block entities and
particles all join that same contract rather than adding consumer-owned resource paths.

## Renderer session owns the packed coordinator

The packed coordinator is acquired by `RtSceneGeometryManager`, whose lifetime is the renderer session.
Terrain attaches to that renderer-owned instance before it begins streaming and detaches only after idle
teardown. Packed records enter the same frame table as a retained prefix; dynamic geometry appends its
manager-owned suffix without introducing another coordinator.

## One frame table and instance stream

`RtSceneGeometryManager` now opens a dynamic frame suffix after it has assembled retained geometry.
It assigns dynamic record indices, owns table capacity and flushes, and owns the corresponding instance
list. `RtSceneSource` does not carry retained geometry, per-frame instances, a geometry-table address,
raw BLAS operations, or graphics lifetime manifests.

The merged particle mesh declares
`REBUILT`, passes canonical packed arrays plus its visibility mask and motion input, and the manager
owns its buffer, fast-build BLAS, record, instance, and post-graphics retirement. This retains the
existing merged-particle behavior and primary-only mask while avoiding a particle-specific resource path.

## Keyed residents cover block entities and deforming entities

The dynamic-frame coordinator now owns two keyed resident forms in addition to one-frame rebuilt
geometry. Cached residents replace a complete static mesh under a source key, which preserves the
block-entity mesh-hash policy without exposing buffers, BLAS backing, or retirement to the Minecraft
source. Deforming residents use a fixed engine-owned in-flight ring. The source declares the topology
version with each capture and retains only CPU capture/fitting data plus an opaque reference for rigid
reuse; the manager selects refit or rebuild, owns all scratch and backing allocations, and retires every
slot after its last graphics use.

The table and instance stream are singular: each keyed resident appends through the same dynamic frame
suffix as particles. This deliberately leaves generic build policies available while removing
consumer-specific GPU state from `RtEntities`.

## Environment sources do not carry geometry products

`RtSceneSource` is limited to source environment state, bindless texture lifecycle, and CPU capture into
an injected manager-owned dynamic frame. Its frame manifest no longer exposes raw BLAS build operations
or graphics-use lifetimes. The renderer records only the manager's pending builds before TLAS creation.

The renderer binds the scene geometry manager into `ProviderManager` when a runtime activation starts. Minecraft then
obtains the manager from that provider boundary for terrain-coordinator attachment and teardown, avoiding
a Minecraft-provider dependency on the `RtComposite` singleton.

## Dynamic frame inputs remain opaque

`DynamicFrame` accepts only packed submissions, keyed resident operations, transforms, visibility masks,
and opaque motion inputs. Raw geometry records, acceleration-structure instances, build lists, table
addresses, and graphics-use attachment stay behind manager orchestration. This prevents a source adapter
from recreating a consumer-specific GPU path while preserving one table and one TLAS assembly point.

Material invalidation increments the manager's dynamic-resident generation. On the next frame with a GPU
context, cached and deforming resident maps are cleared and every resource is retired against its recorded
graphics use. Opaque deforming references carry the generation that created them and the active
manager-owned resident identity. A reference is rejected after material invalidation or after its owner
is released and retired, rather than reusing geometry against new bindings or a retired ring. Minecraft
clears its corresponding CPU caches at resource reload.

Deforming topology declarations include vertex count as well as packed indices and class counts. The
manager validates packed index bounds, class coverage, texture-coordinate cardinality, and primitive
record cardinality before any GPU upload, then requires both version and vertex count for refit legality.
