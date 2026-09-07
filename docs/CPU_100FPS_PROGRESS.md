# CPU 100 FPS work

The acceptance target is over 100 CPU frames per second in both a settled static
scene and fast continuous flight at 3840×2054 in New World (2), preserving rendering
quality and approximately one-frame ready-content publication. This target is **not
yet achieved**.

## Evidence on September 6

The existing normally launched client was profiled directly through JFR, then its
existing loopback debug service was enabled to capture controlled runs and shut down
cleanly before rebuilding. The uncontrolled attached profile locates allocation
pressure; it does not identify static versus moving intervals.

After excluding the first allocation sample for each thread, whose weights included
allocations before the recording, its sampled allocation estimate was 52.8 GB over
30.07 seconds (about 1.76 GB/s). Render-thread and four preparation-worker estimates
were about 843 and 816 MB/s respectively. These are sampling estimates, not exact
counters. Native presentation waits were kept separate from Java execution samples.

The revised telemetry records thread CPU time and exact allocated-byte deltas for
the frame envelope and each preparation task, plus graphics timeline waits. On this
Windows JVM the CPU clock advances in 15.625 ms steps, making aggregate means more
useful than single-frame CPU percentiles. Frame elapsed time still includes worker
waits and scheduling. Summing maximum worker elapsed time per phase with render CPU
is a diagnostic estimate, not a rigorous critical-path measurement.

## First incremental candidate

Both captures use 32-section view distance, four bounces, DLSS RR Performance input
1920×1027, 3840×2054 output, and no frame generation. After 2400 stationary warmup
frames, record 600 ticks stationary, then hold spectator forward plus sprint, warm
100 moving ticks, and record another 600 ticks. The northward route starts near
(-1204, 110, -2321); the measured flight spans approximately z=-2420 to -3078.
The fast endpoint is inside the mountainous terrain at this altitude; future visual
checks also need an above-ground camera.

| Measurement | Static | Fast flight |
| --- | ---: | ---: |
| Recorded frames | 1477 | 597 |
| Frame elapsed mean | 19.50 ms | 48.18 ms |
| Render CPU mean | 13.23 ms | 33.37 ms |
| Render allocation per frame | 18.42 MB | 43.25 MB |
| Preparation-worker allocation per frame | 30.72 MB | 55.14 MB |

The static recording had only 38 terrain jobs over 30 seconds, so it largely
exercised retained terrain. Entities remained active (169 captured in the final
static frame). Flight averaged 23,727 instances and 97,123 lights. Mean flight
capture/assembly costs were 2.86/4.83 ms, TLAS preparation 5.71 ms, trace preparation
12.95 ms, and light-sampling preparation 4.53 ms. Scopes overlap.

Every matched GPU-ready publication pair was one observed frame in both runs:
static terrain 38/entity 114,250; flight terrain 14,779/entity 19,890. Entity
extraction-to-assembly can span two frames, including extraction and preparation.
These observations describe engine publication/assembly, not monitor scanout.

## Implemented direction

- Immutable scene pages share resource ownership and reuse unchanged capture roots.
- Renderer assembly reuses page translations, geometry records, and motion history.
- Light-sampling plans cache descriptor power, membership mappings, and uploads.
- Completed trace-buffer slots reuse capacity after GPU completion.
- Unchanged light tables and their identity maps reuse previous results.
- Terrain meshing reuses the six directions instead of cloning the enum array for
  every model part.
- Generated struct writers clear reflected padding only; handwritten direct-memory
  substitutions were reverted at the user's request. Any such rewrite requires a
  comparative benchmark before adoption.

Combined engine, Vulkan, renderer, client, and generator tests passed. The successful
live retry shut down cleanly. The first attempt hit the same server precipitation
null-position exception found in ten older July/August crash reports; its cause is
unresolved and no null guard was introduced.

## Next work

### Retained-page experiment

The subsequent candidate adds dense per-kind snapshot ordinals, paged lights,
cached trace plans and packed page residency, and touched-entry terrain publication.
It preserves generated record writers. Two diagnostic runs that rebuilt TLAS every
frame measured static frame envelopes of 10.12/10.95 ms, but flight remained
49.17/49.18 ms. Static render allocation remained 16.50/19.21 MB per frame, dominated
by entity upload and capture. Static preparation-worker allocation fell to
0.08/0.42 MB per frame.

Flight terrain publication averaged 0.76 ms and TLAS preparation 2.18 ms in the first
run. Trace preparation regressed to 17.53 ms and lighting preparation to 8.67 ms.
The trace scheduler counted pages rather than their contained work, allowing large
packing jobs onto the render thread. Missing trace-plan construction also ran there.
The correction uses weighted worker chunks and constructs missing plans on workers.
Light plans now reuse unchanged pages, and packed light bytes reuse the generated
writer's previous output. These corrections require another live measurement.

The retained-TLAS experiment encountered repeated null-write GPU device losses. Two
complete routes with forced builds initially passed, but a later always-build run
failed too; this does not establish build skipping as the cause. The next diagnostic
used fresh TLAS allocations and completed two routes. A further variant with fresh
AS handles but pooled buffers failed during its second route. All TLAS pooling and
input-page caching were removed; only a generic writer overload remains, using the
original fresh-resource lifetime. No fault cause is claimed from the null address report.

The asynchronous entity-packing candidate subsequently reproduced the same GPU
fault during its second route even with fresh TLAS resources. TLAS reuse therefore
does not explain the fault. Further optimization is paused for a controlled earlier
checkpoint comparison. This renderer checkpoint is an experiment, not a validated
stability result. Entity packing retains textures on the render thread, runs generated
writers on two workers, then immediately submits mesh preparation; its lifecycle
tests pass and `EntityMeshUpload` records worker queue/CPU/allocation data.

Across the two successful forced-build routes, matched engine-ready publication
delays remained at most one observed frame. An above-ground screenshot showed
terrain rendering, and the client shut down cleanly. Local artifacts are named
`goal-force-tlas-*` and `goal-force-tlas-repeat-*`.

With weighted workers, cached light pages, and fresh TLAS resources, the two flight
envelopes measured 40.17/40.33 ms. The first run averaged 26,267 packed instances
and 108,628 indexed lights per frame, a different resident population from earlier
runs. Light packing processed only 1,191 changed lights per frame; mean lighting
preparation was 3.20 ms. Static envelopes were 10.70/12.29 ms. These results still
miss the acceptance target. Both routes shut down cleanly and an above-ground
capture showed terrain rendering. Artifacts: `goal-fresh-tlas-*` and
`goal-fresh-tlas-repeat-*`.

Flight still invalidates almost every full light list and its downstream tables.
The renderer still packs retained geometry/emitter/SBT data and traverses all TLAS
instances each frame. Reduce that work through stable revisions and changed-page
updates, and measure page occupancy before attributing cost to sparse page keys.
Keep camera-origin, pipeline, motion-history, and GPU resource lifetime dependencies
explicit. Additional workers alone do not remove this work.

Local manifests and exports: `tmp/cpu-optimization/goal-current*`,
`goal-baseline-static*`, `goal-baseline-fast*`, `goal-incremental-static*`, and
`goal-incremental-fast*`. Analysis scripts: `tmp/cpu-goal-flight.py`,
`tmp/cpu-goal-metrics.py`, `tmp/cpu-export.ps1`. Raw recordings stay under
`run/caustica-debug`; these large artifacts are excluded from Git.

## Latest measured checkpoint

The `a75f7803` asynchronous entity candidate's completed `goal-entity-async` route
produced the following JFR means at 3840×2054 in New World (2):

| Metric per frame | Static | Fast flight |
| --- | ---: | ---: |
| CPU envelope, including waits | 8.883 ms | 37.615 ms |
| Render-thread CPU | 8.203 ms | 27.431 ms |
| Render-thread allocation | 9.216 MB | 20.607 MB |
| Frame-preparation worker allocation | 0.814 MB | 17.800 MB |
| Entity-upload worker allocation | 9.899 MB | 3.612 MB |

The static recording contains 2,059 frames; flight contains 757. Entity-upload
queue/work means were 0.791/0.045 ms per job static and 0.109/0.064 ms flying.
Thread CPU measurements on this Windows JVM have 15.625 ms resolution, so individual
short-job CPU percentiles cannot resolve their cost. The static result is promising;
flight fails the 100 CPU FPS target and the subsequent GPU fault remains unresolved.

In flight, 24,402 matched entity-ready publication observations and 17,976 matched
terrain-ready publication observations were all at most one observed frame. This
measures publication, not pixel visibility or extraction-to-ready latency.

The largest sampled preparation-worker allocation origin was emitter-link marking:
`Arrays.copyOf -> BitSet.ensureCapacity -> BitSet.expandTo -> BitSet.set`.
Every page expands its own bitmap to the highest scene-wide dense light index.
After excluding each thread's first allocation sample, this origin accounts for
49.8% of preparation-worker sampled allocation weight. This evidence concerns bitmap
growth; it does not justify replacing generated struct writers with direct stores.

The earlier `f2bce1e9` checkpoint completed two separate static/flight recordings
without device loss. The second process subsequently exited cleanly. These runs do
not yet isolate the candidate's GPU fault, which has often required a second route
within the same process. The working branch was restored to `rewrite` after exit.
Narrow local reports are `goal-entity-async-{static,fast}-hotspot-report.txt` and
`goal-entity-async-fast-latency-report.txt` under `tmp/cpu-optimization`.

The next change uses a primitive set while packing each page and retains only its
unique linked indices in an array. The scene combines those arrays into one bitmap.
Renderer tests pass, including sparse high indices, duplicates, and replaced links;
runtime allocation and timing effects remain to be measured. The user subsequently
requested default resolution, so the 3840×2054 warmup was stopped before recording
and both window overrides were restored to zero. Subsequent measurements must report
the actual default window dimensions and must not attribute resolution differences
to the emitter change.

At default 854×480 output, `805a4a10` completed two static/flight routes in the same
process and shut down cleanly. Static render CPU means were 9.367/9.120 ms; flight
means were 29.633/27.948 ms. Flight preparation-worker allocation was
13.298/11.843 MB per frame. The former bitmap-growth allocation origin is absent;
primitive-set growth appears instead. These are not a same-resolution A/B timing
comparison with the preceding checkpoint. Static CPU envelopes were 9.519/9.206 ms;
flight envelopes were 39.356/36.814 ms. Flight still fails the acceptance target.

The repeated flight's light-index rebuild averaged 3.781 ms on the render thread,
despite being reported as a `FramePreparation` phase. It ran synchronously. This
identifies a concrete next scheduling change: build the identity map on a worker
alongside trace planning, traverse light pages directly, and join before packing.

The parallel-index change implements that split using a caller-owned batch scope.
The identity map is allocated and built by a leaf worker while the render thread
dispatches missing trace plans. Every accepted task is joined before its borrowed
inputs can be released, including on caller, submission, or worker failure. Renderer
tests pass, including latch-controlled draining and page-order index replacement.

Two default-resolution routes (`goal-parallel-index-default` and its `repeat`)
completed in one process without GPU errors and shut down cleanly. JFR attributes
all light-index jobs to preparation workers; their mean work was 2.597/2.469 ms.
Flight render CPU was 25.393/24.392 ms, render allocation 17.943/18.065 MB per frame,
and trace preparation 10.496/9.717 ms. The map's approximately 3.1 MB allocation per
flight frame moved to workers; it was not eliminated. Static render CPU was
8.142/8.369 ms. CPU envelopes were 8.301/8.518 ms static and 36.945/35.464 ms flying.
The workloads share the route and settings, but live scene populations can differ.
Flight still fails the target.

In the repeat flight, all 19,969 matched entity-ready publication observations and
18,982 terrain-ready publication observations were within one observed frame.
The first launch before these successful recordings failed with the known server
precipitation exception and supplied no benchmark result. The cache audit found no
concrete GPU address/lifetime defect; if device loss returns, forcing trace-slot
bytes to be rewritten while retaining the same slot lifetimes is the next controlled
isolation. No GPU fault fix is claimed.

`tmp/CpuJfrMetrics.java` streams raw JFR events directly and reports thread names for
preparation phases. Its output matches the JSON-based means on the prior repeat
flight and avoids large JSON exports for routine timing/allocation checks.

## Same-frame TLAS packing overlap

TLAS reservation retains fresh Vulkan resources on the render thread. A leaf worker
then packs and flushes the existing LWJGL instance records while trace preparation
runs. The joined world-geometry token is finalized only after lighting preparation;
TLAS build, barrier, lighting, and tracing command order is preserved. Allocation,
descriptor updates, command recording, and GraphicsUse registration remain on the
render thread. No TLAS pooling or handwritten struct stores were introduced.

Renderer and runtime tests pass, including native paged packing with a trailing
canary and a real-JFR stage test. Live testing exposed missing registration for the
new timing stages; this was fixed before the successful recordings. An earlier
launch also hit the known precipitation exception. Neither supplied performance data.

Two default-resolution routes, `goal-overlap-tlas-default` and its `repeat`, completed
in one process without GPU errors and shut down cleanly:

| Mean per frame | Static runs | Fast-flight runs |
| --- | --- | --- |
| Render-thread CPU | 6.992 / 7.112 ms | 20.763 / 19.869 ms |
| CPU envelope including waits | 7.946 / 8.007 ms | 34.739 / 34.251 ms |
| Render-thread allocation | 5.730 / 6.344 MB | 15.072 / 15.371 MB |
| TLAS packing worker elapsed | 1.642 / 1.530 ms | 4.453 / 4.473 ms |
| TLAS reservation on render | 0.023 / 0.016 ms | 0.090 / 0.044 ms |

JFR attributes every `tlas-pack` job to preparation workers. In flight, 97.13/96.60%
of TLAS packing elapsed time overlaps the union of other preparation-job intervals.
The approximately 2.6–2.7 MB of TLAS packing allocation moved to workers; it was not
eliminated. Static and flight scene populations are live and can vary between runs.
The new `frame.prepareWorldGeometry` stage includes joined TLAS and trace preparation;
it replaces the separate runtime TLAS/trace preparation scopes. `frame.finishTrace`
measures final descriptor and light-table binding.

In the repeat flight, all 21,565 matched entity-ready publication observations and
19,669 matched terrain-ready publication observations were within one observed frame.
This verifies publication, not pixels. Flight still misses 100 CPU FPS. Scene assembly
remains approximately 5 ms and combined geometry preparation approximately 12.6 ms;
reducing unchanged-page reconstruction and repacking is the next substantial task.

## Stable geometry and emitter ranges

Each retained instance page now owns stable geometry and emitter offsets. Removed
pages release their numeric ranges; first-fit allocation coalesces adjacent gaps and
shrinks unused tails. A new occupant has a fresh range identity, and completed trace
slots cache by that identity. Captured frames retain their own layout and resources,
so numeric reuse does not change an in-flight frame. The geometry/SBT tables may
contain holes; only explicitly addressed TLAS records are consumed. Light tables and
their dense indices remain unchanged. The allocator enforces the stricter 24-bit SBT
offset limit and checks emitter range capacity.

Renderer/runtime tests pass: prefix insertion/removal preserves surviving offsets
and cached plans, reused ranges receive new generations, history remains immutable,
and 3,000 randomized allocation/release operations maintain nonoverlapping ranges.
The opt-in `TraceRanges` event reports active counts and highest addressed ends.

At 854×480, two same-process routes (`goal-stable-ranges-default` and its `repeat`)
completed without GPU errors and stopped cleanly at the benchmark start:

| Mean per frame | Static runs | Fast-flight runs |
| --- | --- | --- |
| Render-thread CPU | 6.019 / 6.520 ms | 18.818 / 16.983 ms |
| CPU envelope including waits | 6.656 / 7.358 ms | 30.179 / 27.678 ms |
| Render-thread allocation | 5.712 / 6.272 MB | 14.084 / 13.371 MB |
| Combined geometry preparation | 1.313 / 1.590 ms | 10.043 / 9.626 ms |
| Packing-worker allocation | 0.359 / 0.747 MB | 6.689 / 6.338 MB |

Flight range capacity peaked at 1.220/1.206 times active geometry and 1.206/1.181
times active emitter bytes. Actual Vulkan buffers can retain larger rounded capacity.
An above-ground screenshot showed the terrain scene after the first route; it is
saved as `goal-stable-ranges-visual.json` locally. The earlier GPU fault remains
unexplained; successful runs do not establish its cause. The first launch hit the
known precipitation exception before recording. Future test launches use the same
benchmark-start camera; the original camera is retained in `goal-initial.json` for
restoration after the optimization work.

The repeat flight has 23,979 matched entity-ready and 19,999 terrain-ready publication
observations, all within one observed frame. Publication-to-assembly adds zero frames
for all 23,937 matched entity and 54,083 terrain-ready observations. These events
measure engine assembly, not pixel presentation. Flight still misses 100 CPU FPS.

The preceding raw JFR also attributes substantial allocation to vanilla block/sky
light map clones. A bytecode audit found no redundant updates: the client already
batches queued packets and skips snapshots with no changes. Structural sharing of
that snapshot index would be a separate compatibility change, not a safe whole-engine
off-thread move. Another remaining renderer issue is that NEE type-count telemetry
is recomputed for changed light lists even when its event is disabled; this scan is
visible in the render-thread JFR samples and should be gated by demand.

## Lazy NEE telemetry

Light-type totals are now computed only when `NeeFrame` is enabled. Rendering still
updates the required environment-light flag. Starting a recording after ordinary
frames correctly produced 105 events with populated totals (79,805 parallelograms,
one distant light, 79,806 retained lights in the first event).

Two default-resolution routes (`goal-lazy-nee-default` and `repeat`) measured lighting
preparation at 0.887/1.094 ms in flight, compared with 2.377/2.327 ms before the gate.
Flight render-thread CPU was 17.308/18.335 ms and the CPU envelope 28.972/32.889 ms.
Static render-thread CPU was 5.934/6.406 ms. Resident workloads vary; these runs show
less telemetry work, not a demonstrated overall flight speedup. The client stopped
cleanly. Renderer tests passed before the recordings. The 100 CPU FPS flight target
remains unmet.
