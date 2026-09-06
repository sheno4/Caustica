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
