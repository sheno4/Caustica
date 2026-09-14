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

## Source-density reservoir candidate

Surface NEE now draws reservoir candidates using their source distribution density, then evaluates the local/global mixture only for the winner. Its contribution normalization includes `sourcePdf / mixturePdf`; linked-emitter MIS still receives the mixture density. This follows the source-density reservoir organization inspected in RTXPT's `PathTracerNEE.hlsli`, rather than evaluating cross-distribution densities for all eight candidates. The volume-light sampling path is unchanged. Reservoir selection probabilities and therefore noise change, but the correction preserves the estimator's expectation.

`tmp/fog-local/verify-source-reservoir.py` enumerates all candidate tuples and all possible winners for eight finite-distribution cases. It includes sparse local support, unequal sample counts, different target/contribution functions and occluded lights. Every corrected expectation equals the reference integral within 1e-10; results are in `tmp/source-reservoir-performance/estimator-expectation.json`. This is a mathematical model check, not a GPU image-equivalence test. Renderer-raytracing `check` passes (`tmp/fog-local/source-reservoir-check.log`).

With the cutout candidate still present and the same settled 36,782 sections, three 4K fog-off recordings average **16.191, 16.215 and 16.251 ms**, versus 17.033/17.148/17.152 ms before the sampling change. Default fog averages **19.246 ms** (52.0 FPS), p95 19.600 ms, p99 20.362 ms, max 21.846 ms; 599 of 610 frames are below 20 ms. Dense fog averages 24.498 ms. No measured frame exceeds 33.3 ms. These are ordinary JFR HostLoop measurements, not Nsight timings. The candidate clears the average target for this stationary jungle view; dynamic behavior, noise and the broader scene matrix remain unverified.

The first default-fog flight completed but JFR exhausted disk space during the rest recording and explicitly shut down the JVM. This is a recording failure, not evidence of a GPU crash. Four earlier multi-gigabyte `jvm.json` exports under `tmp/frame-spikes` were removed after verifying their original `recording.jfr` files remained, reclaiming about 29 GB. The incomplete manifest is retained as `motion-interrupted.json`. The next launch restored the original pose, flying speed and fog before replaying.

The completed default-fog replay averages 15.744 ms during 20 seconds of maximum-speed flight, p95 19.031 ms, p99 24.793 ms and max 56.997 ms. It ends with 11,216 geometry sections and 54,390 pending requests. After 30 seconds of rest, 30,958 geometry sections are resident and only 3,720 neighbor-blocked requests remain. Rest averages 14.452 ms, p99 15.729 ms, with one **118.035 ms** hitch. One render-thread execution sample inside that hitch is in `LinearPalette.copy`, called by `RtSectionSnapshots.createRegion` during `RtTerrain.dispatch`. This implicates snapshot preparation for further investigation but does not attribute the entire 118 ms to that sample. Another 56.855 ms hitch samples Minecraft's staged GUI buffer recycling. Raw JFRs are referenced by `motion-manifest.json`; compact results and sampled stacks are in `motion-summary.json` and `rest-hitch-attribution.json`.

Three 1600×900 to 3840×2160 resize cycles with default fog all recover, with maxima 50.574/52.284/52.230 ms and no frames above 100 ms. Medians are 19.160/19.047/19.156 ms. Interval means include lower-resolution transition frames and must not be interpreted as settled 4K performance. The sustained sub-1 FPS collapse was not reproduced. Settings were restored and the client stopped after validation. The source-density and cutout shader candidates remain uncommitted pending further GPU/noise evaluation; the scene matrix and remaining hitches are still outstanding.

Detailed scope correlation revises the 118 ms hitch hypothesis: at HostLoop 1766, frame 5367, `terrain.snapshotDispatch` is only **0.684 ms**, `world.capture` is 3.313 ms and `world.compositeAndUi` is 0.853 ms. Most of the 118.035 ms occurs outside measured Caustica callbacks. The palette-copy execution sample does not account for that delay. A snapshot time budget is therefore not justified as a fix for this particular hitch. Windows thread CPU clocks in these events are quantized in 15.625 ms units, so sub-frame CPU-time comparisons are not meaningful.

## Scene matrix and depth-bounded fog

The source-density/cutout candidates with default fog were measured at 4K Performance RR after terrain population settled. Static / slow circular movement / following rest means, in milliseconds:

| Scene | Static | Circle | Rest |
| --- | ---: | ---: | ---: |
| Lake | 18.249 | 18.531 | 18.251 |
| Natural cave | 20.419 | 19.962 | 20.429 |
| Enclosed stone room | 20.488 | 20.456 | 20.494 |
| Glass hemisphere | 28.032 | 26.511 | 28.032 |
| Controlled underwater basin | 18.558 | 18.933 | 18.502 |

Each sequence records 15 seconds static, 20 seconds moving, then 15 seconds resting. Scene screenshots were captured outside recordings and inspected. The basin is a small controlled fixture, not a complex natural ocean. The hemisphere and basin were built only in the disposable world. Raw recordings, settings, poses and readiness observations are referenced in `tmp/source-scene-matrix` and `tmp/source-optical-matrix`. The matrix does not yet include the Nether. Strong ambient fog in the enclosed room remains a visual limitation: the medium currently adds unoccluded ambient radiance.

Fog now bounds each column using the greatest depth distance over its full bilinear support, including every jittered primary-depth footprint used by composition. It skips density and visibility work only for intervals entirely beyond that bound. One extra prefix plane retains the limit across visibility batches. A depth sample containing sky keeps the full range.

The repeated room sequence averages **19.767 / 19.658 / 19.735 ms**, and the dome **27.058 / 25.677 / 27.092 ms** (`tmp/depth-bounded-matrix`). The approximately 0.7–1.0 ms improvement is useful but does not bring the dome below 20 ms.

An initial comparison with an older jitter-matched capture showed transmittance differences, but did not match the animated wind phase or the terrain field after fixture construction. A temporary diagnostic then integrated full-range and bounded transmittance in the same frame, writing them to separate channels. All 16 raw captures across jungle, room, dome and cave have exactly equal transmittance and no nonfinite pixels at 1600×900, density 4, divisor 4, 64 samples. The diagnostic and manifests remain in `tmp/fog-bounds-same-frame`; production shaders contain no diagnostic changes. This checks transmittance, not stochastic lighting history equivalence. Renderer-presentation `check` passes after restoring production code (`tmp/fog-local/depth-bounded-final-check.log`).

## Nsight after source-density sampling and depth bounding

Three additional traces were collected through the Nsight Graphics UI with the same Top-Level Triage configuration, real-time shader sampling, one-frame limit and base-clock setting. Source-density sampling and the cutout certificate remained present. Trace files and settings/readiness metadata are in `tmp/source-bounded-nsight`.

| Scene | Whole frame | Build stable planes | Fill stable planes | NGX command buffer |
| --- | ---: | ---: | ---: | ---: |
| Jungle, fog off | 18.68 ms | 2.86 ms | 9.90 ms | 4.16 ms |
| Dome, default fog | 32.59 ms | 10.61 ms | 12.82 ms | 4.18 ms |
| Dome, fog off | 25.84 ms | 8.32 ms | 11.74 ms | approximately 4.2 ms |

The jungle has 36,796 geometry sections and the dome 37,187; the additional fixture geometry makes the jungle population slightly different from earlier captures. Other GPU contexts interrupt the fog-on dome build and scene-effects intervals. These are individual profiled frames, not ordinary frame-time averages, and their differences do not isolate fog execution cost. No new event-loss warning appears; the output pane retains warnings from earlier captures. The client was stopped and its original settings and pose restored afterward.

The fog-on dome build's dependency attribution is 41.61% global loads, 37.99% local loads, 11.58% ray tracing and 3.27% local stores. Hotspots include the acceleration-structure access, shadow traversal, material evaluation, fog-field sampling and stable-plane path state. The dome's large build cost persists without fog. A follow-up candidate delays material closure evaluation until after segment-medium integration, avoiding a large live closure across that integration and its shadow traversal. This is a dataflow hypothesis pending ordinary timing measurements; it is not yet a demonstrated fix.

## Material lifetime across medium integration

Build and fill now retain the small intersection record through segment-medium integration and resolve the full material closure afterward. Intersection distance and miss flags already provide everything integration needs. Surface evaluation and branch/lighting operations retain their original inputs; a material closure no longer needs to survive the integration's shadow traversal.

The candidate and a subsequent restored-source control used the same cold-start route, settings, camera poses, and settled populations: 36,296 geometry sections in the dome and 37,539 in the jungle. Each interval records 600 RT frames through JFR HostLoop. This route produces a different population from the earlier scene matrix; use this pair for attribution.

| Scene/configuration | Previous shader mean | New shader mean |
| --- | ---: | ---: |
| Dome, default fog | 28.717 ms | 25.477 ms |
| Dome, fog off | 24.906 ms | 22.022 ms |
| Dome, default fog repeat | 28.895 ms | 25.803 ms |
| Jungle, default fog | 20.585 ms | 19.592 ms |
| Jungle, fog off | 17.570 ms | 16.633 ms |
| Jungle, default fog repeat | 20.587 ms | 19.512 ms |

This pair supports approximately 11% lower dome frame time and 5% lower jungle frame time. Captured scene color, normal/roughness and primary depth contain no nonfinite values in either candidate scene. Source review verifies the reordered material evaluation consumes the same immutable frame/intersection inputs. Renderer-raytracing checks pass. Artifacts are in `tmp/material-liveness-performance` and `tmp/material-liveness-baseline`, including the candidate patch and original JFR references. The candidate is retained; its register/spill changes have not yet been measured in a follow-up Nsight trace. The dome remains above the 20 ms target.

## Nether, flight replay, and withdrawn candidates

The material-lifetime build with the source-density and cutout candidates completed a crimson-forest Nether sequence at 4K Performance RR. Twenty seconds each of stationary view, slow circular flight and rest averaged **20.429 / 20.784 / 20.467 ms**; respective maxima were 22.326 / 25.943 / 22.386 ms, with no frames above 33.3 ms. The view is an open cavern with crimson stems, hanging vines and nearby emissive blocks. Artifacts are in `tmp/nether-matrix`. This is an enabled-setting performance test, not proof of visible Nether fog: the Minecraft field derives coverage from sky light, and the density evaluator multiplies by that coverage.

The subsequent maximum-speed jungle replay averaged **15.513 ms** during 20 seconds of flight and **14.088 ms** during 30 seconds of rest. Flight p99/max were 23.289/63.727 ms, with six frames above 33.3 ms and two above 50 ms. Rest p99/max were 15.151/69.909 ms, with one frame above 50 ms. Neither interval exceeded 100 ms. Planner p99/max were 1.597/3.403 ms during flight. The flight ended with 11,619 geometry sections and rest with 30,965. These populations and the new world history differ from earlier runs, so the modest mean improvement does not isolate an optimization. Original recordings and metadata are in `tmp/liveness-flight`.

The source-density candidate is withdrawn despite its measured mean frame-time gain. Exact enumeration now checks the second moment as well as expectation: mixed-source cases can increase variance by approximately 15–178%, while another case decreases it by 34%. These synthetic cases do not quantify rendered-image noise, but establish that the estimator change is not a free speedup. The original mixture-weight estimator is restored. The cutout blocker certificate is also withdrawn because ordinary timings showed no clear gain. Their combined patch is preserved in `tmp/source-reservoir-performance/withdrawn-candidates.patch`, with exact expectation/variance results alongside it. Earlier timings that include those candidates are not final retained-build measurements.

After withdrawal, checks pass for renderer-raytracing, renderer-presentation, minecraft-rendering and api (`tmp/fog-local/retained-performance-check.log`). The material-lifetime and conservative fog-depth changes remain retained.

The clean retained build repeats the dome-to-jungle route with the same 36,296/37,539 settled geometry populations. Each interval records 600 RT frames:

| Scene | Default fog | Fog off | Default fog repeat |
| --- | ---: | ---: | ---: |
| Glass dome | 24.768 ms | 21.168 ms | 24.858 ms |
| Jungle | 20.000 ms | 16.836 ms | 20.041 ms |

All six intervals have no frame above 33.3 ms. Six captured color/normal/primary-depth buffers contain no nonfinite values. Artifacts are in `tmp/retained-performance`. These are the current retained-build absolute timings, not a matched control for withdrawing each candidate: both candidates were removed together and GPU clocks were not locked. The jungle is approximately at 50 FPS; the dome remains approximately 40 FPS with default fog. The client restored its pose/settings/window and exited normally.

Streaming JFR analysis avoids large expanded JSON files and correlates outer callbacks with the flight replay's worst loops (`tmp/fog-local/HitchScopes.java`). Flight loop 3331 / frame 37081 lasts **42.186 ms**, including a **21.141 ms** render-thread park in `CompletableFuture.join` called by Minecraft `SoundEngine.play`; outer mod callbacks total 7.568 ms. This identifies an audio wait contributing to one hitch, not the cause of every spike. The 63.727 ms flight hitch has only 5.009 ms of outer mod callbacks, and the 69.909 ms rest hitch only 3.226 ms. The rest hitch's recorded GC pause is 0.0145 ms. Their remaining time is not attributed by these events. Scope reports are saved beside the motion manifest; isolated execution samples are observations, not duration attribution.
