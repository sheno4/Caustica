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
