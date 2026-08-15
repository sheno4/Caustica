# Retained Geometry Decisions

## One retained scene API

Terrain, entities, block entities, and particles submit `Put`, `Drop`, `Place`, and `Remove` operations to
`RtSceneGeometryManager`. Source-qualified resident and instance keys make regrouping safe: a submission
is an independently atomic group, while unrelated groups can prepare concurrently.

The manager keeps the published scene snapshot. A group either replaces every operation in its barrier at
once or leaves that snapshot unchanged. Publication acknowledgements are delivered only after that swap,
so terrain updates its section and light state from the published result rather than from worker completion.

## Asynchronous BLAS ownership

All source BLAS work is prepared and submitted through the manager's asynchronous executor. Static
terrain uses packed geometry, optional opacity micromaps, and optional compaction behind the same API;
those details do not escape to terrain. TLAS assembly remains on the graphics path.

Compatible deforming geometry updates create a fresh destination BLAS and refit from the published BLAS
after its exact last graphics use. The manager publishes it on a later frame and retires the old BLAS after
that frame's graphics use. This permits one frame of delay without a mutable BLAS ring or a build backlog.

Queued work is coalesced only where groups conflict. A healthy running group finishes and publishes;
newer overlapping work waits behind it. A failed group releases its candidate resources without changing
the scene and latches the error for the render thread, rather than leaving a source waiting for an
acknowledgement that will never arrive.

Host queries of the graphics-use timeline and retirement polling are render-thread-affine, ordered with
the graphics submission that signals the timeline. The compute executor submits and waits build work but
never queries the graphics-use timeline.

## Motion and origins

Sources provide current positions and transforms only. The manager retains compatible previous position
buffers plus previous per-instance transforms, and shaders derive motion by comparing current and previous
positions. Rebuilt geometry resets vertex motion because it has no stable vertex correspondence.

`Place` carries the origin in which its transform was authored. The manager rebases transforms for the
current frame, including history, so a scene-origin shift does not require rebuilding geometry.

## Source clearing

Clearing a source removes its published residents and placements immediately from future scene assembly,
cancels its queued and running groups, and retires published GPU resources after their recorded graphics
use. A terminal callback still owns every cancelled candidate until its GPU work is complete. Terrain and
Minecraft entity sources clear their scene-owned state on a world transition, preventing old-world work
from publishing after IDs or section coordinates are reused.
