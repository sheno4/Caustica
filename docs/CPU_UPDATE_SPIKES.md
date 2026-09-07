# CPU update-spike investigation — 2026-09-08

## Workload

New World (2), default window resolution (854×480), DLSS RR Performance
(427×240), 32 chunks, Ryzen 9 9950X and RTX 5070 Ti. Each normal route starts
at (-1204, 110, -2321), yaw 180, pitch 0, warms for 2400 frames, captures
600 stationary ticks, then holds spectator forward+sprint for 100 warmup ticks
and 600 recorded ticks. Normal flying speed is 0.05; maximum-speed captures
explicitly use 0.2 and restore the previous setting afterward. World simulation
and streaming remain live, so populations and individual outliers vary.

Frame intervals below are JFR start-to-start intervals, not GPU or presentation
timings. CPU envelopes include scheduling and waits. Windows thread CPU time
has a 15.625 ms quantum here; per-frame CPU quantiles are not useful. Publication
latency measures arrival at assembly, not visible pixels. No screenshots, builds,
or JFR exports run during timed captures.

## Iterations

| Candidate / normal flight | Mean interval | p95 | p99 | Maximum | Geometry preparation | Packing allocation/frame |
|---|---:|---:|---:|---:|---:|---:|
| Worker publication baseline, `8920aede` | 8.70 ms | 15.32 ms | 25.51 ms | 199.16 ms | 2.03 ms | 4.092 MB |
| Individual ranges, flat validation | 12.58 ms | 20.85 ms | 30.65 ms | 49.22 ms | 5.62 ms | 0.244 MB |
| Individual ranges, batch validation | 8.33 ms | 15.25 ms | 26.33 ms | 77.58 ms | 2.71 ms | 0.207 MB |
| Shared batches, cheaper terrain selection | 8.06 ms | 15.55 ms | 25.64 ms | 80.98 ms | 2.46 ms | 0.170 MB |
| Separate emitter layout | 7.14 ms | 15.48 ms | 25.67 ms | 81.29 ms | 1.94 ms | 0.191 MB |
| Completed TLAS slot reuse | 7.35 ms | 15.20 ms | 31.34 ms | 82.30 ms | 2.00 ms | 0.213 MB |
| TLAS slot reuse with changed-page input copies | 7.08 ms | 15.50 ms | 25.74 ms | 79.32 ms | 1.87 ms | 0.207 MB |

The first individual-range implementation regressed CPU performance despite
reducing packing. JFR showed full-instance identity-map/residency scans on the
render thread. Batch validation skips unchanged storage groups while retaining
individual GPU range generations. These measurements do not establish that all
update spikes are eliminated.

The baseline's two longest render envelopes included 189.65 and 115.54 ms in
lighting preparation. Native JFR stacks identify `vkWaitSemaphores` through
`RtNeeAtBackend.SceneState.ensure`: capacity growth waited for old frame resources.
Growth now allocates a fresh pair with spare light capacity and retires the old
pair after both graphics uses complete. Normal mapped-buffer reuse still respects
its graphics-use dependency. These long growth waits were absent in the two
individual-range captures.

Batch validation at flying speed 0.2 produced 2034 frames: mean interval 14.83 ms,
p95 25.43 ms, p99 32.64 ms, maximum 48.68 ms. Terrain dispatch averaged 2.52 ms,
geometry preparation 3.53 ms, and assembly 1.33 ms. Render-thread allocation
samples attribute 35.2% to vanilla sky/block light map clones under
`ClientLevel.update` / `LevelLightEngine.runLightUpdates`; these are not retained
scene database copies. Dispatch also repeatedly checked neighbor availability
for requests unable to outrank selected work.

Terrain-ready to assembly was 0–1 frames in the batch captures (62,960 normal,
130,110 maximum-speed events). Entity publication reached the current assembly
in the matched events. This does not measure meshing/upload time or presentation.

## Local evidence

The emitter-layout candidate reduced static geometry preparation from 1.02 to
0.56 ms. Its normal-flight render envelope averaged 6.38 ms, with 4.39 MB allocated
on the render thread per frame. Active geometry counts were comparable: 46,658
versus 46,855 at baseline. Geometry-packing allocation fell about 95%.

At maximum flying speed, shared batches/selection averaged 14.05 ms per interval
(p99 32.33 ms); emitter layout averaged 14.17 ms (p99 33.50 ms). There is no
demonstrated maximum-speed tail improvement from emitter layout. Dispatch averaged
1.95 ms versus 2.52 ms before selection changes. The final maximum-speed capture
had no recorded ZGC allocation stalls; 136,128 terrain-ready observations reached
assembly within 0–1 frames. Publication-worker duration averaged 0.0188 ms per
event, p99 0.1066 ms, maximum 8.18 ms.

The final normal-flight capture still contains expensive mod frames: one 54.64 ms
frame spent 43.72 ms in TLAS reservation; other slow frames spent 29–30 ms in
geometry preparation. Preparation-worker jobs in those frames reached 12–15 ms
for TLAS packing and 5–8 ms for geometry/emitter packing. These are elapsed times,
including scheduling. Native samples do not establish the cause of the individual
43.72 ms reservation. Fresh TLAS allocation on every frame is a concrete next
reuse target. Two recorded 12 ms monitor waits were on the graphics retirement
thread waiting for the renderer backend, not the render thread waiting for it.

Validation for this candidate: 140 renderer-raytracing, 49 renderer-runtime, and
154 Minecraft-client tests pass (343 total). Coverage includes unchanged-record
identity, numeric range replacement, page regrouping, scene removal, motion history,
emitter membership/reindexing, light capacity, and terrain selection equivalence.
The client completed all captures and clean shutdown. A post-capture terrain image
was inspected; this is not a quantitative visual-latency test.

## TLAS storage reuse

The following iteration shares the existing completion-slot pool between trace
tables and TLAS storage. Only completed slots are reusable. Each TLAS slot retains
its acceleration structure, scratch, and mapped instance buffer with power-of-two
capacity headroom; actual build counts remain independent of capacity. Closing the
pool retires completed storage and handles late returns without waiting. At 46,658
instances this reserves 65,536 entries; at 27,355 it reserves 32,768. This trades
bounded capacity headroom for avoiding repeated GPU allocation.

Pool-only normal-flight reservation averaged 0.002 ms versus 0.062 ms before reuse.
Its maximum-speed interval averaged 14.19 ms, p99 35.23 ms. These results demonstrate
lower reservation cost, not an overall tail improvement. The first pool launch
failed before profiling with the same server-side `Biome.shouldFreeze` null-position
crash previously seen at baseline; the retry completed the captures and shutdown.

Pooled instance inputs also retain immutable CPU page identities. Changed page
identities or byte offsets trigger copying; unchanged pages retain their mapped
bytes. Direct packing invalidates this cache. A failed write/flush cannot publish
new cache state. Empty or smaller builds use their actual count, so they do not
read stale tail bytes. TLAS construction itself still runs for every frame.

The combined candidate measured static geometry preparation at 0.494 ms and
TLAS packing at 0.032 ms/job. Normal-flight TLAS packing averaged 0.255 ms/job
versus 0.345 ms for pool-only input copying. Reservation remained 0.002 ms.
Normal-flight active geometry was 46,741, close to baseline's 46,855; mean interval
was about 19% lower than baseline, but p99 was unchanged within observed variation.
At maximum speed the combined candidate averaged 14.26 ms per interval,
p95 24.79 ms, p99 33.64 ms, maximum 51.43 ms. Maximum-speed geometry preparation
still averaged 3.74 ms; this remains a material cost. There is no demonstrated
elimination of update spikes.

In the final maximum-speed capture, 135,035 terrain-ready observations reached
assembly within 0–1 frames. Publication-worker duration averaged 0.0176 ms,
p99 0.0898 ms, maximum 6.66 ms. No ZGC allocation stalls were recorded. Worker
execution samples still emphasize emitter-index lookup/remapping and terrain
meshing; render samples emphasize residency lookups and pending-terrain ranking.

Final validation: 149 renderer-raytracing, 49 renderer-runtime, and 154
Minecraft-client tests pass (352 total). New tests cover completion-only reuse,
late shutdown returns, rejected reservations, capacity growth/shrink, untouched
packing headroom, and page-copy identity/offset changes. The final client completed
all captures and clean shutdown. After the post-flight teleport, an immediate
image still showed loading terrain; after 200 ticks, the expected terrain view
was present and was inspected. The original camera and temporary flight speed
were restored; default resolution remains in use.

Manifests and reports are under ignored `tmp/cpu-optimization/`; raw JFR files
are under `run/caustica-debug/`.

- `worker-publication-baseline-{static,fast}`: baseline; fast recording
  `recording-433e8e84-7e6a-442d-800a-928884c2cde3.jfr`.
- `instance-delta-{static,fast}`: regressed flat-validation experiment.
- `instance-batch-{static,fast}`: batch validation; fast recording
  `recording-c6feb5d6-0d7a-4db1-9cf2-b82389d12b6c.jfr`.
- `instance-batch-max`: flying speed 0.2, recording
  `recording-f276a640-71bf-463f-b6fe-b8722a3fb463.jfr`.
- `shared-batch-{static,fast,max}`: shared plan/translation arrays and selection.
- `emitter-layout-{static,fast,max}`: final emitter-specific invalidation; fast
  `recording-08bbee11-0f86-4774-bd4d-4e0f9530759a.jfr`, maximum-speed
  `recording-f9e0f598-02e4-4dbd-bc55-fc62fd98813e.jfr`.
- `tlas-pool-{static,fast,max}`: completed TLAS storage reuse; fast
  `recording-6c8bb1d1-4b8e-49bc-a7cf-59f241875e6d.jfr`, maximum-speed
  `recording-3c9ed091-7df2-4f2f-8005-b3e772271329.jfr`.
- `tlas-delta-{static,fast,max}`: final combined candidate; fast
  `recording-bf1ba9e6-61b9-4475-863f-69e247c5c567.jfr`, maximum-speed
  `recording-c7a22b37-9469-44c0-97d4-dd0de6036c66.jfr`.

## Stable views and remaining work

A frame retains stable published revisions. Unchanged instance records, ranges,
plans, and TLAS source pages are shared. Storage pages group change detection;
they do not require every member's geometry to be repacked. Generated structure
writers remain in use; no handwritten memory layout optimization is claimed.

Emitter membership has its own revision, so unrelated nonemitting changes retain
linked-light results. Production plan resolution no longer flattens all instances;
unchanged page translation maps are shared. Remaining costs include TLAS growth
and changed descriptor copying, emitter remapping on actual light changes, page-directory
assembly, publication synchronization, Minecraft extraction and vanilla light updates. Further iterations
must measure tails and worker costs rather than rely on mean CPU frame rate.

The remaining synchronization is architectural: `RtFramePreparation.Batch.close`
joins work launched after frame capture. Its tasks borrow frame roots and mapped
slots, so deleting the joins would permit rendering or retirement while writes
continue. The next boundary is preparation of a ready atomic edit group before
logical publication. A worker can prepare mesh-invariant templates, changed
instance/trace batches, matching light indices/emitter mappings, and independently
owned buffer revisions against a retained base. Final generation validation and
one atomic commit install the group. Superseded work releases its claims; previous
committed revisions remain owned by frames and motion history. This must use the
engine's scene grouping contract, rather than a separate terrain-only scene path.

Camera inputs, accepted-frame motion history, graphics-use reservation, command
recording, TLAS construction, and screen-space lighting/history remain frame-specific.
Ready work can reach the next capture; work that is not ready leaves the previous
coherent content visible. Measure preparation-ready, logical-publication, assembly,
and presentation separately so moving work earlier does not conceal update latency.
