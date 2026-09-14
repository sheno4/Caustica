# 64-byte material candidate

Terrain triangle material records shrink from 96 to 64 bytes by deriving the normal-map tangent basis from the object-space edges already available at the surface hit and stored UV gradients. A handedness flag preserves terrain source-normal orientation. Entity vertex colors retain their full-precision optional payload. This removes two padded vectors per triangle without extra position loads.

Affected Minecraft rendering/client, raytracing renderer and shader API checks passed (`tmp/fog-local/basis-final-check.log`). Runtime shader compilation and reflected packing were exercised by the development client. Exact-jitter raw guide comparisons are in `tmp/basis-matched/guide-comparison.json`: both room views have identical normals and roughness across 360,000 matching pixels each. Jungle normal differences stay below 0.002 for 280,604 of 280,606 matching pixels; roughness p99 difference is zero. Neither capture has nonfinite values. Unmatched-jitter captures are excluded.

## Settled jungle at 4K

`tmp/basis-performance` retains settings, readiness, device memory, allocator inventories and raw JFR data. Output is 3840x2160 with RR Performance at 1920x1080, render distance 32, simulation distance 12. Default fog means density 1, divisor 8 and 64 samples; this does not assert every renderer option is at its default. The scene contains 3,649 loaded columns and 37,400 resident geometry sections throughout accepted intervals. The 3,360 neighbor-blocked requests are at the loaded boundary. Explicit `terrain.status` snapshots avoid interpreting stale asynchronous JFR state counts as an empty scene.

| Case | Mean host loop (ms) | p99 (ms) | Maximum (ms) |
| --- | ---: | ---: | ---: |
| Fog off | 17.198 | 18.595 | 20.081 |
| Default fog | 20.585 | 21.351 | 23.320 |
| Fog off repeat | 17.126 | 18.553 | 18.919 |
| Density 4, divisor 4 fog | 26.214 | 27.622 | 33.221 |
| Fog off final | 17.129 | 18.056 | 18.702 |

Each interval waits for 600 recorded RT frames and contains 602–608 host loops. Device memory is 14,157–14,554 MiB of 16,303 MiB. The first fog-off allocator inventory reports 9,640,752,080 allocated bytes and 10,603,349,152 reserved bytes; it excludes Minecraft/NGX/profiler allocations. The earlier severe collapse did not recur during these toggles. Repeated resize cycles remain untested. Default fog averages about 48.6 FPS here, below the target.

## Maximum-speed flight without fog

`tmp/basis-flight` contains two 20-second straight spectator flights with sprint and ability speed 0.2 from the same jungle pose. The camera travels about 1.7 km. Cold/repeated labels identify route order, not equal populated geometry.

| Route | Loops | Mean (ms) | p99 (ms) | Max (ms) | >20 ms | >33.3 ms | >50 ms | >100 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| First | 1,616 | 12.447 | 18.952 | 43.201 | 14 | 4 | 0 | 0 |
| Repeat | 1,882 | 10.734 | 19.463 | 77.520 | 19 | 8 | 4 | 0 |

These are not successful streaming results: the repeated route ends with 68,085 pending section requests and only 6,471 resident geometry sections. Higher flight FPS partly reflects missing geometry. CPU attribution, return-to-rest latency and turning routes are still required. The full scene matrix, matched main comparison and leaf/vine temporal artifact diagnosis remain open.

The repeated flight starts before the teleport destination finishes loading (13 columns, 24 geometry sections), so it is not a warmed-route comparison. In `tmp/basis-flight/cpu-attribution.json`, samples overlapping the first route's 43.2 ms hitch show `ChunkSkyLightSources.fillFrom` beneath chunk packet replacement. The repeated route's 77.5 ms hitch includes packet-buffer reads, `ClientPacketListener.enableChunkLight` and `LightEngine.runLightUpdates`; its 61.6 ms hitch includes skylight-source initialization and section light-state updates. Sparse execution samples identify work in these intervals, not exclusive causes for their whole duration. Entity extraction is also prominent in aggregate samples.

## Nsight Graphics captures

Nsight Graphics 2026.3.1 was configured, launched, captured and inspected through computer use. Both captures used the GPU Trace Profiler, Blackwell Top-Level Triage, locked base clocks, 1,000 ms maximum trace duration and one frame. The UI requires real-time shader profiling for this metric set. Its minimum accepted PM bandwidth is 500 MB/sec, producing a 500 MiB PMA buffer instead of the previous 4,000 MiB. Hardware Event System was disabled. Steam remained running.

Local copies are `tmp/basis-nsight/fog-off.ngfx-gputrace` and `default-fog.ngfx-gputrace`. This run settles at 3,649 columns and 36,782 geometry sections, slightly different from the ordinary benchmark population. The live HUD reports zero demoted VRAM, and neither report shows the prior demotion warning. The output pane retains historical event-loss messages from the old report; no new event-loss message appeared for these captures. Both frames contain brief other-context intervals, so their absolute timing is still not an isolated-device benchmark.

| GPU interval | Fog off (ms) | Default fog (ms) |
| --- | ---: | ---: |
| Whole captured frame | 21.43 | 25.02 |
| Build stable planes | 4.12 | 2.86 |
| Fill stable planes | 10.40 | 13.41 |
| NGX command buffer | 5.30 | 4.20 |
| Fog scene effects | — | 2.98 |

These single-frame pass differences include scheduling interference and stochastic work; do not attribute the fill-pass difference to the later fog pass. Fog's direct-light queries appear as separate roughly 0.20–0.21 ms trace batches. In the fog-off fill pass, sampled throughput is 65.0% VidL2, 35.9% VRAM bandwidth, 21.9% RT core and 20.7% SM, with about 20.9 active threads per warp. The default-fog fill selection attributes 51.01% of sampled input dependencies to ray tracing and 33.94% to global-memory loads; its leading source hotspot is `traceShadowQuery` at `RayQuery.Proceed`. Retained-light indexing and coverage evaluation also appear. These point toward traversal/lighting and occupancy investigation, rather than establishing a single bandwidth bottleneck.

Fog settings were restored and the profiling client stopped after capture. Current and main Gradle properties both target Minecraft 26.2; the earlier 26.2-rc-1 source-cache filename is not the runtime version.

## Main comparison

An isolated checkout of `main` at `330acd2d` uses a copy of the stopped test world, mods, resource packs, options and config. Both clients target Minecraft 26.2, 4K output, RR Performance (1920×1080 internal), distance 32, simulation distance 12, four bounces and an 8 GiB ZGC heap. Main lacks the new fog pass and ignores the new feature namespaces. Its rendering and lighting algorithms differ, so this is an end-to-end baseline rather than an isolated optimization comparison. It settles at 36,563 geometry sections and 3,649 columns, versus the candidate's 36,782–37,400 geometry sections.

Local-only main modifications initialize an any-hit material header for the installed Slang compiler and add a loopback control/JFR probe. The probe uses the same host-loop boundaries as the candidate, with no per-frame I/O or terrain scans. Legacy CSV telemetry is disabled because it references a missing stage. Each recording's first event is excluded: enabling recording mid-loop leaves its begin timestamp zero or stale. The HTTP server also kept the JVM alive after graceful game shutdown, producing a shutdown-watchdog report after all measurements; that report is unrelated to measured rendering hitches.

Artifacts: `tmp/main-comparison/{manifest,motion-manifest,summary}.json`, per-run CPU exports and source JFR paths in the manifests.

| Main workload | Mean (ms) | p99 (ms) | Max (ms) | >33.3 ms |
| --- | ---: | ---: | ---: | ---: |
| Settled jungle 4K, 20 s | 11.946 | 12.505 | 13.980 | 0 |
| Repeat, 20 s | 11.971 | 12.529 | 13.154 | 0 |
| Highest-speed straight flight, 20 s | 12.033 | 30.536 | 44.981 | 7 |
| Following rest, 30 s | 8.909 | 12.998 | 33.768 | 1 |

Three 1600×900 → 3840×2160 cycles recover to approximately 11.96–11.97 ms at 4K. Each resize produces one 36–45 ms spike; no frame exceeds 50 ms. The main flight ends with 16,008 resident geometry sections, 41,023 missing requests and 192 in flight; after 30 seconds of rest it reaches 30,867 geometry sections with 3,954 missing and none in flight. Candidate flight FPS must be assessed together with its much smaller loaded geometry population. Main's settled ~84 FPS versus the candidate's ~58 FPS without fog establishes a substantial remaining performance gap.

## Hardware-opacity shadow experiment

Allowing the inline shadow query to use geometry opacity, while resolving committed transmissive surfaces in distance order, did not improve this jungle workload. Three fog-off runs average 17.090, 17.121 and 17.149 ms; default fog averages 20.572 ms and dense fog 26.419 ms. The shader experiment passed renderer checks but was reverted because its measured benefit was negligible. Results remain in `tmp/shadow-hardware-performance`.

That build also completes three resize cycles without persistent collapse: 4K interval means are 16.61–16.78 ms with individual resize maxima of 48–65 ms. Its 20-second flight averages 13.300 ms, p99 18.909 ms, max 38.504 ms; following rest averages 11.952 ms, p99 13.284 ms, max 86.791 ms. Flight ends with 9,685 resident geometry sections and 58,789 pending requests. After 30 seconds of rest, it has 30,951 resident geometry sections and 3,763 neighbor-blocked requests. This capture omits the earlier expensive periodic `TerrainState` scan and adds `TerrainDispatchPlan`; it is not an instrumentation-identical comparison with `tmp/basis-flight`.

The new CPU recording exposes dispatch planning as a loading bottleneck: 1,316 selections average 3.789 ms, with p95 38.451 ms, p99 52.665 ms and maximum 65.427 ms. Samples concentrate in `neighborsLoaded` and `TreeSet` insertion/comparison during camera-driven reranking. These are worker selection durations, not render-thread frame durations. The planner recomputes neighbor availability and inserts every pending section into a tree whenever the camera changes section. Ranking columns and resolving section order only within equal-distance columns can preserve the full ordering while substantially reducing movement work.

## Column-ranked terrain dispatch

The planner now retains candidates within columns, caches halo availability until a loaded-column delta affects it, and reranks columns only for horizontal camera changes. Selection handles visible replacements first and orders sections across equal-distance column groups by the existing vertical-distance and identity rules. Reset, request coalescing and asynchronous plan publication retain their existing semantics. A test compares selection against full sorting of 2,904 requests with visible replacements, removal and camera movement, including vertical-only movement.

The 4K straight-flight replay in `tmp/column-planner-performance` starts with the same 36,782 geometry sections and no actionable backlog. Planner p95 falls from **38.451 ms to 0.935 ms**, p99 from 52.665 to 1.401 ms, and maximum from 65.427 to 2.999 ms. Mean selection cost falls from 3.789 to 0.259 ms. End-of-flight geometry increases from **9,685 to 12,687 sections** (31%), while pending requests fall from 58,789 to 50,221. After 30 seconds of rest it reaches 30,978 geometry sections, leaving only 3,670 neighbor-blocked requests.

Frame-time improvement is not established: with more terrain populated, the new flight averages 13.654 ms, p99 20.153 ms and max 62.410 ms; rest averages 11.964 ms, p99 13.286 ms and max 69.965 ms. No frame exceeds 100 ms. The prior comparison build contained the neutral hardware-opacity shader experiment, now reverted; the CPU planner change is the retained optimization. This improves loading selection substantially but does not close the GPU frame-time gap or complete the broader scene matrix.

Both candidate flights end at exactly x=-614.1883476165475, y=125, z=-4787.073165875047. Minecraft client `check`, including the planner behavior suite, passes (`tmp/fog-local/column-planner-check.log`). Input, flying speed, pose and fog settings were restored and the development client stopped.

## Restart-hit experiment and fog coverage diagnosis

Retaining the build pass's intersection in an 80-byte endpoint record removed the fill pass's first traversal but added 94.9 MiB at 1920×1080 with three planes. It did not improve the settled jungle: fog-off means were 17.370, 17.359 and 17.296 ms, default fog 20.757 ms, dense fog 26.463 ms. Renderer checks passed, but the experiment was reverted. Artifacts remain in `tmp/restart-hit-performance`.

`tmp/fog-edge-sequence` captures 16 stationary frames at 1600×900, dense fog and transmittance-only diagnostics, with same-frame primary depth and reconstructed color. It contains 14 distinct jitter phases. Of the 45,448 pixels whose transmittance standard deviation exceeds 0.01, 99.9% also change depth by more than 5%. Mean transmittance standard deviation is 0.00678 at changing-depth pixels versus 0.000115 at stable-depth pixels. The variation image outlines leaf holes and silhouettes. This identifies the nearest-surface depth choice in post-fog composition as a temporal artifact source, independently of sampled lighting noise.

The coverage filter blends transport from each of the four depth samples at discontinuities instead of blending depth or choosing one surface. Continuous surfaces keep affine projected-depth interpolation. The new 16-frame sequence has 13 distinct jitter phases: p99 transmittance deviation falls from 0.03158 to 0.02041 and the count exceeding 0.01 falls from 45,448 to 30,670. These are sampled sequences with different jitter/time histories, not an exact per-frame comparison; residual flicker remains. Artifacts are in `tmp/fog-coverage-sequence`.

In `tmp/fog-coverage-performance`, the settled 4K means are 17.033/17.039/17.063 ms without fog, 20.554 ms with default fog and 26.375 ms with dense fog. Default-fog p99 is 21.391 ms. There is no material frame-time penalty in this workload, but the 50 FPS goal is still not established. Renderer-presentation checks pass (`tmp/fog-local/fog-coverage-check.log`).

## Certified cutout shadow experiment

The candidate certifies zero-transmission material surfaces independently of coverage. Shadow queries still evaluate coverage and reject holes, but an accepted certified hit terminates visibility before full surface evaluation. API, terrain, ray-tracing and client checks pass, including uploader coverage variants. This remains an uncommitted experiment: ordinary 4K fog-off means are 17.033/17.148/17.152 ms, default fog 20.509 ms and dense fog 26.157 ms, with wider frame-time variation than the preceding runs. These averages do not establish an improvement (`tmp/cutout-blocker-performance`).

Nsight Graphics UI captures use the same settled 36,782 geometry sections, 4K output, Performance RR, base-clock lock and bounded one-frame Top-Level Triage configuration. The fog-off trace is 19.75 ms with build 2.84 ms, fill 10.74 ms and NGX command buffer 4.47 ms. Another GPU context interrupts the fill interval, so its elapsed time is not isolated shader execution. Fill dependency samples are 42.78% ray tracing, 34.20% global loads, 11.55% local loads and 5.14% local stores. The default-fog trace is 24.65 ms, with build 4.05 ms, fill 10.07 ms and NGX buffer 4.49 ms; other GPU contexts also appear. No new event-loss warning appears for these captures. Reports and warmup/settings metadata are in `tmp/cutout-blocker-nsight`. Fog settings were restored and the client stopped after collection. The captures confirm traversal and memory dependencies remain dominant; they do not establish an overall speedup.
