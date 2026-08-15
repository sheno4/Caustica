# Retained Geometry Decisions

## One retained scene API

Terrain, entities, block entities, particles, clouds, and public scene providers submit `Put`, `Drop`, `Place`, and
`Remove` operations containing immutable `SceneMesh` data through `SceneGeometrySink`. Providers use
`SceneGeometrySink.submit(groupKey, operations)`; the provider manager qualifies each group with its source and
assigns monotonic revisions. `SceneGeometryKey` separates source-local identity domains. A submission is one
independently atomic group, while unrelated groups can prepare concurrently.

The manager keeps the published scene snapshot. A group either replaces every operation in its barrier at
once or leaves that snapshot unchanged. Older revisions for a stable group key are ignored. Publication
acknowledgements are delivered only after that swap, so terrain updates its section and light state from the
published result rather than from worker completion. Placement-only groups update TLAS instances without
rebuilding a BLAS.

## Submission cadence

Update-cadence retained groups are submitted before startup readiness is evaluated and before the manager
progresses pending publications. Frame-cadence groups carry render capture such as entities, particles, and
clouds. Both cadences use the same source-qualified atomic `SceneGeometrySink` operations and publication
acknowledgements; cadence changes when a source captures data, not how its retained geometry is committed.

## Asynchronous BLAS ownership

All source BLAS work is packed, prepared, and submitted through the manager's asynchronous executor. The manager
resolves source material and texture identities, selects either an out-of-place compatible update or a rebuild,
and periodically rebuilds compatible residents. TLAS assembly remains on the graphics path. Placement-only groups
change instances without rebuilding a BLAS.

Each `Put` carries independent memory-minimization and opacity-acceleration preferences. Both default off. The
default keeps an update-capable resident and permits compatible out-of-place refits. Memory minimization selects
an immutable build followed by an asynchronous compacted copy. Opacity acceleration selects a fresh immutable
build even when compatible motion history exists. Terrain requests both preferences; other built-in and public
providers retain the default unless they opt in per geometry. The renderer does not infer policy from source
identity.

## Opacity micromaps

Opacity micromaps are renderer-owned and apply only to final `CUTOUT` bindings. Classification runs after mesh
packing and material resolution, over the class-sorted triangle order, actual indexed or per-corner texture
coordinates, and the resolved binding's texture slot and cutoff. Stochastic coverage, transmissive surfaces,
textureless definitions, and bindings whose base texture was replaced with a provider texture remain unknown.
Unsupported devices use the ordinary cutout any-hit path; that fallback does not change compaction policy.

The material epoch stores a mip-zero temporal alpha range for every canonical texel. Each texel contains the
minimum and maximum alpha across every unique animation frame; static textures have equal bounds. The material
record also stores the whole-material range as a fast path. Classification never samples a current animation
frame and never rebuilds per frame. Inputs without an immutable spatial range remain unknown.
Every catalog texture currently receives a complete four-image canonical page bundle so the temporal-alpha page
has the same stable material coordinates even when the other three OpenPBR images are neutral.

One compute invocation owns every packed output word for a triangle. It subdivides the triangle barycentrically,
maps each microtriangle's three UV corners, and scans every mip-zero texel in the conservative footprint. A
microtriangle is fully opaque only when every texel's temporal minimum passes the resolved cutoff, and fully
transparent only when every temporal maximum fails it; all other, non-finite, wrapped-across-a-seam, or unsupported
footprints are unknown. The classifier implements the same nearest, repeat, mip-zero sampling contract as any-hit.

The async command order is classification, a compute-write-to-micromap-read barrier, micromap build, a
micromap-write-to-acceleration-read barrier, BLAS build, optional compacted-size query, and optional compact copy.
Only the final build or compact-copy token may publish. The material tables and temporal pages are shared with the
graphics and reserved compute queue families. Resource reload cancels geometry and drains accepted executor work
before destroying an epoch's classifier, descriptors, tables, or pages.

A compactable candidate has two executor phases: BUILD writes the compacted-size query, then COMPACT copies into
the exactly sized resident allocation. The group remains unpublished between them and marks only the compact-copy
completion for graphics visibility. UPDATE results never enter this path. Build, query, allocation, cancellation,
or copy failure destroys every unpublished acceleration allocation and leaves the published snapshot unchanged.

Compatible deforming geometry updates create a fresh destination BLAS and refit from the published BLAS
after its exact last graphics use. The manager publishes it on a later frame and retires the old BLAS after
that frame's graphics use. This permits one frame of delay without a mutable BLAS ring or a build backlog.

Queued revisions coalesce only for the same group key. Different overlapping groups remain independently atomic
and serialize through resident and instance reservations. A healthy running group finishes and publishes;
newer overlapping work waits behind it. A failed internal-source group releases its candidate resources without
changing the scene and latches the error for the render thread. A failed public-provider group instead disables
and clears only that provider source, so one extension cannot fail the retained renderer.

Host queries of the graphics-use timeline and retirement polling are render-thread-affine, ordered with
the graphics submission that signals the timeline. The compute executor submits and waits build work but
never queries the graphics-use timeline.

## Motion and origins

Sources provide current positions and transforms only. The manager retains compatible previous position buffers
plus previous per-instance transforms, and shaders derive motion by comparing current and previous positions.
Topology-incompatible geometry resets vertex motion because it has no stable vertex correspondence. Sources keep texture
identities rather than renderer binding indices; material resolution and bindless-slot pairing remain renderer-owned.

`Place` carries the origin in which its transform was authored. The manager rebases transforms for the
current frame, including history, so a scene-origin shift does not require rebuilding geometry.

## Source clearing

Clearing a source removes its published residents and placements immediately from future scene assembly,
cancels its queued and running groups, and retires published GPU resources after their recorded graphics
use. A terminal callback still owns every cancelled candidate until its GPU work is complete. Provider manager
clears public-provider sources on disable, stop, world transition, resource-pack detach, and session end; providers
must re-submit retained mesh data after a clear. Terrain and Minecraft entity sources clear their scene-owned state
on a world transition, preventing old-world work from publishing after IDs or section coordinates are reused.
