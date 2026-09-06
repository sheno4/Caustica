# CPU flight profiling — 2026-09-06

## Workload and measurement limits

New World (2), 3840×2054 output, 1920×1027 DLSS Ray Reconstruction Performance input,
four bounces, 32-section render distance, frame generation disabled. Hardware reported
by JFR and Vulkan: Ryzen 9 9950X and RTX 5070 Ti. No rendering-quality settings were
reduced for these changes.

Each flight starts at approximately (-1204, 110, -2321), faces north with pitch zero,
holds spectator forward input without sprinting, warms up for 100 moving ticks,
then records 600 ticks of uninterrupted flight: approximately z=-2370 to z=-2700.
The initial experiments use 300 stationary warmup frames. The bulk-upload experiment
and final light-preparation run use 1600 to allow the initial terrain load to settle. Input is released after each run;
screenshots and terrain-state inspection are outside the timed intervals.

JFR records JVM execution/native samples, CPU scopes, frame counters, terrain jobs,
entity revisions and geometry publication. The final implementation also records
worker preparation phases and entity GPU-ready/publication timestamps. Elapsed CPU
scopes include scheduling and waits. They are not GPU timings or displayed FPS.

Scene population differs between runs: removing publication throttles makes more
terrain resident. The first parallel run averaged 16,973 instances, the cache
verification 22,500, and the warmed repeat 25,850. Consequently the raw frame means
are not a controlled estimate of frame-rate improvement. Ordinary server simulation,
world streaming and desktop scheduling were not frozen.

## Changes

- Terrain workers start upload and GPU preparation directly after meshing, removing
  a render-thread handoff that averaged 109 ms in the baseline.
- Ready terrain completions drain at the next frame boundary. Complete atomic
  neighbor groups publish together without completion or publication quotas.
  A ready-group set avoids scanning unfinished groups every frame.
- Immutable dependency groups replace per-frame mesh/data ownership remapping.
  Instance and light snapshots and mesh program resolution are reused until their respective
  edit or program-publication revision changes. Previous frame inputs retain motion
  resources through GPU completion.
- Four bounded workers plan and pack ordered trace tables. Each writes its own
  ranges; every accepted task finishes before flush, submission, or failure cleanup.
  Sorted emitter ranges use binary search, and hit records use bulk copies.
- Light indexing runs alongside geometry planning; light records pack in parallel
  after emitter links are resolved. Immutable light snapshots are created on edits,
  avoiding per-frame light-record allocation in the engine snapshot.
- Mesh resolution plans reuse unchanged snapshot identities. TLAS CPU staging
  reuses capacity; the actual TLAS and GPU input storage remain frame-owned.
- Each terrain worker caches decoded block states for a section and its one-block
  halo, preserving the full immutable neighboring-section snapshot for other reads.
- Publication telemetry now uses the cutoff captured with scene inputs, preventing
  later publications from being credited to an older snapshot.
- Minecraft texture-view releases return to the render thread. Its view counter and
  destruction queue are not thread-safe; worker releases could lose counter updates
  and cause a texture-atlas shutdown failure.

## CPU measurements

Mean elapsed milliseconds per observed frame (scopes overlap and include waits):

| Run | Instances | Frame | Capture | Assemble | TLAS preparation | Trace preparation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | Not instrumented | 61.26 | Not instrumented | Not instrumented | 1.57 | 13.73 |
| Parallel preparation | 16,973 | 44.59 | 5.65 | 6.77 | 2.28 | 9.91 |
| Mesh cache verification | 22,500 | 57.86 | 8.91 | 6.49 | 3.69 | 15.00 |
| Warm repeat | 25,850 | 70.09 | 11.68 | 8.72 | 4.22 | 18.89 |
| Final light preparation | 26,917 | 66.46 | 9.34 | 10.02 | 5.11 | 14.85 |

The baseline's publication backlog makes its lower scene workload unsuitable for a
direct frame-rate comparison. The improvements remove measured handoffs, allocations,
and serial work; the table does not establish an overall percentage speedup.

The workers were profiled too. In the warm repeat, each trace planning task averaged
3.11 ms and each packing task 5.56 ms. Nonempty terrain meshing averaged 0.73 ms,
versus 0.69 ms in the baseline; different sections prevent attributing that difference
to the decoded block-state cache. The CPU-ready/upload-start handoff fell from
109 ms to approximately 0.001 ms.

A separate heap-serialization/bulk-upload experiment showed no improvement
(82.99 ms frame mean at 26,020 instances) and was reverted. The final implementation
writes geometry tables directly into disjoint mapped ranges.

The final run averaged 107,639 lights. Worker light indexing averaged 1.11 ms;
geometry planning 3.79 ms per task; geometry packing 5.71 ms; light packing 2.05 ms.
Terrain snapshot/dispatch averaged 0.94 ms on the render thread; nonempty worker
meshing averaged 0.67 ms. The final CPU frame p95 was 94.06 ms.

Remaining render-thread samples concentrate in retained snapshot capture, frame
assembly, TLAS instance preparation, and NEE-AT light planning (5.07 ms mean elapsed).
Worker samples concentrate in geometry/emitter table packing and terrain meshing/
upload. Graphics-retirement samples include releasing captured resource groups.
JVM/native sample counts are hotspot evidence, not measured thread CPU percentages.
The mod's CPU frame cost is still substantial; this work does not establish that the
requested small render-thread budget has been reached.

## Publication latency

The final light-preparation run measured 10,240 matched terrain GPU-ready/publication
pairs and 12,535 ordinary entity pairs. Every pair published one observed frame after
readiness. Terrain extraction-to-assembly had p95/p99 of one frame (maximum two);
entity extraction-to-assembly had median one and p95/p99/maximum two frames.

The earlier cache verification measured 11,454 matched terrain GPU-ready/publication
pairs and 15,106 ordinary entity pairs. Every pair published one observed frame after
readiness. The warmed repeat measured 9,825 terrain pairs, all within zero or one
frame, and 11,508 ordinary entity pairs, all one frame.

Terrain extraction-to-assembly was also one frame at p95 and p99 in the parallel run.
Entity extraction-to-assembly was one frame at the median and two at p95: extraction
and GPU preparation are additional to ready-content publication. Rigid entity
placement updates appeared in the same assembled frame as their extraction.

These observations establish retained publication and engine frame assembly latency.
They do not establish the exact time a pixel reaches the monitor. A screenshot of the
flight endpoint was inspected, but this is not a pixel-equivalence or exhaustive
visibility test.

## Validation and local artifacts

Affected engine, Minecraft client/rendering, raytracing and renderer-runtime test
suites passed. Coverage includes ownership across independent captures and program
removal, atomic neighbor publication, supersession, draining 192 completions,
worker teardown, decoded snapshot isolation, ordered parallel packing, joining failed
writers, hit-record padding, real-JFR entity publication timestamps, light snapshot
replacement/removal, parallel light packing, and texture-release thread confinement.
The final live flight rendered successfully and the client exited cleanly after
restoring the initial camera position and releasing flight input.

Baseline source checkpoint: `a9c65705` plus debug input controls. Implementation
checkpoints: `11e5e164` and `6c7a9165`, followed by the final cache and light-preparation changes.

Run manifests, raw exported events, JVM samples, summaries and analysis scripts remain
local under `tmp/cpu-optimization` (scripts: `tmp/cpu-flight.py`, `tmp/cpu-latency.py`,
`tmp/cpu-samples.py`). Original JFR files remain under `run/caustica-debug`.
`baseline.json`, `optimized.json`, `final.json`, `verified.json`, `repeat.json` and
`bulk-upload.json` and `lights.json` contain the recording paths and camera/settings snapshots.
The final recording is `recording-c4bc9ec6-c9a8-4599-b037-bfa9c7afffb4.jfr`.
Raw recordings and screenshots are excluded from Git.
