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

Each `Put` carries build preferences. The default keeps an update-capable resident and permits compatible
out-of-place refits. The memory-minimizing preference selects an immutable build followed by an asynchronous
compacted copy; terrain uses it, while entities, block entities, particles, clouds, and public providers retain the
default unless they opt in per geometry. The renderer does not infer policy from source identity.

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
