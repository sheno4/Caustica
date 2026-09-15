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

## Retained-build GPU trace and endpoint traversal candidate

Nsight Graphics UI captures of `abf3fe65` use the same Top-Level Triage configuration, real-time shader profiler, one-frame manual trigger and 2287 MHz base graphics clock. The dome has 36,296 settled geometry sections. Trace files, settings and observations are in `tmp/retained-nsight`.

| Dome configuration | Whole frame | Build stable planes | Fill stable planes |
| --- | ---: | ---: | ---: |
| Fog off | 24.94 ms | 7.29 ms | 11.74 ms |
| Default fog | 29.87 ms | 7.17 ms | 13.14 ms |

Other GPU contexts interrupt both traces, particularly the fog-off build and fog-on scene effects; asynchronous TLAS updates also overlap. These are profiled frames, not ordinary means or isolated fog cost. No new event-loss warning was observed. Build hotspots include acceleration-structure access, its traversal call, material evaluation, and writes to the plane/path records. The source hotspot view filters dependency counters to shader code and excludes internal traversal; its percentages must not be compared directly with the full-pipeline table. The client restored settings and stopped normally after capture.

An endpoint-reuse candidate writes a 16-byte geometry/primitive/barycentric hit alongside each 64-byte restart path. Fill uses the accepted build hit instead of tracing the same deterministic endpoint again. Current-frame material evaluation remains in fill. The tradeoff is 94.92 MiB of additional scratch at 1920×1080 with three planes, plus hit stores/loads and longer hit-field lifetime in build. Renderer checks pass for the production change; a later reflected-record field test is included in the archived patch but has not yet been run. The candidate is archived in `tmp/endpoint-reuse-performance/candidate.patch` and is not in the retained worktree.

The first candidate run averages 26.959/23.346/26.723 ms in the dome and 20.900/17.645/21.088 ms in the jungle, for default/off/default-repeat. It is slower and more variable than the prior retained-build sequence. A nearby restored-source control is required before attributing that difference. Exact same-frame cached-hit validation is prepared but deferred until the performance comparison justifies retaining the approach.

The nearby control in `tmp/endpoint-reuse-control` averages **26.142/22.067/26.256 ms** in the dome and **21.018/17.602/20.859 ms** in the jungle. Both runs have the same settled populations and no frame above 33.3 ms. A control GPU observation reports 2910 MHz graphics, 16001 MHz memory, 66°C and 277 W. The control also has wider frame-time variation than the earlier retained run, so that variation is not established as a candidate effect. Endpoint reuse offers no clear jungle benefit and costs approximately 0.47–1.28 ms in the dome across these intervals. The candidate is discarded; the added memory and build live state are not justified by this result. Its prepared correctness diagnostic was not run because the performance criterion failed. The retained source is restored and both clients exited normally.

## Fixed-time control and reordered build tracing

A further candidate applies the existing fill pass's hit-object tracing/reordering policy to build, passing the primary or secondary visibility mask explicitly. Its complete patch, including the new build entry point, is archived in `tmp/build-ser-performance/candidate.patch`. Renderer-raytracing checks pass (`tmp/fog-local/build-ser-check.log`). It is not retained.

Both candidate and restored-source control freeze time at 1000 with `minecraft:advance_time` disabled, run the same dome-to-jungle route at 4K Performance RR, and record 600 RT frames per interval after geometry settles. The original time, advance-time rule, camera, fog and window settings are restored afterward. Both clients exit normally. Means in milliseconds:

| Scene/configuration | Retained control | Reordered build |
| --- | ---: | ---: |
| Dome, default fog | 24.902 | 43.483 |
| Dome, fog off | 21.145 | 39.663 |
| Dome, default fog repeat | 24.961 | 43.477 |
| Jungle, default fog | 19.973 | 21.894 |
| Jungle, fog off | 16.808 | 18.743 |
| Jungle, default fog repeat | 19.990 | 21.893 |

The retained control has no frames above 33.3 ms; its largest frame is 27.447 ms. The candidate's dome frames all exceed 33.3 ms, but none exceed 50 ms. Candidate GPU observations outside timing windows report approximately 2910–2940 MHz graphics and 16001 MHz memory. This is a clear regression, especially for the glass dome; ordinary timing does not establish whether register spilling, reordering overhead or another GPU effect causes it. The retained jungle is approximately 50 FPS and the dome approximately 40 FPS with default fog, leaving the dome below target.

All twelve saved scene-color, normal/roughness and primary-depth buffers across the two builds contain no nonfinite values. Dome color previews were inspected and are visually consistent, but different jitter and temporal history prevent an exact image equivalence claim. Slang v2026.14.1's `HitObject.GetRayDesc()` implementation lowers `TMax` to `OpHitObjectGetRayTMaxNV` or its EXT counterpart; no hit-distance error was established by this investigation. Timing summaries, captures and finite-value checks are in `tmp/build-ser-performance` and `tmp/build-ser-control`.

Earlier sequential comparisons in this document allowed time of day to advance. Their measured values remain valid observations, but changing sun direction is an additional workload confound, including for the material-lifetime comparison. Those percentage differences should not be read as fully isolated effects. Future paired benchmarks freeze time of day as in this control.

## History-cost diagnostic

A temporary diagnostic disables reconstruction-anchor history in `resolveWorldHit` and uses the current hit position as its previous position. This is deliberately unsuitable for production motion guides. It estimates the available benefit before investing in a separate GPU history-preparation pass. The same fixed-time dome-to-jungle sequence averages **24.617/20.995/24.731 ms** in the dome and **19.975/16.737/19.976 ms** in the jungle for default/off/default-repeat, with no frames above 33.3 ms. Against the retained fixed-time control, the dome difference is only approximately 0.15–0.29 ms, and jungle default-fog timings are essentially unchanged. GPU clocks are not locked, so these small differences do not isolate a precise history cost.

This result does not justify building a history-preparation pass as the next performance intervention. Correct history is restored. The diagnostic patch, recording manifest and timing summary are in `tmp/history-ablation`; the client restored its settings and exited normally.

## Independent stable-plane fill

Fill now dispatches one ray-generation invocation per pixel and plane instead of tracing every plane serially in a pixel invocation. A subsequent resolve dispatch combines the plane signals after a memory barrier. Build, ray seeds, path budgets, and the radiance sampling equations remain unchanged. The change adds a dispatch and allows independent paths to be scheduled separately.

Light feedback requires explicit ownership: each path accumulates its own weighted reservoir, stores it in the consumed restart's first two words, and resolve merges the plane reservoirs before writing the per-pixel feedback. Invalid restart records are not read. The restart flags stay intact until the next build. This reuses existing scratch and introduces no additional allocation. The merge changes feedback random choices and aggregation order; it is not bitwise equivalent to the serial feedback stream. An exact rational oracle verifies the unsaturated reservoir distribution in 4,096 cases, including empty planes and zero weights. It does not verify GPU storage or saturated-weight behavior.

A fixed-time candidate and nearby restored-source control repeat the 36,296-section dome and 37,539-section jungle route, with 600 RT frames in each interval. Means:

| Scene/configuration | Serial control | Independent planes |
| --- | ---: | ---: |
| Dome, default fog | 24.787 ms | 21.866 ms |
| Dome, fog off | 21.159 ms | 18.238 ms |
| Dome, default fog repeat | 24.839 ms | 21.885 ms |
| Jungle, default fog | 19.978 ms | 19.047 ms |
| Jungle, fog off | 16.895 ms | 15.502 ms |
| Jungle, default fog repeat | 20.009 ms | 19.083 ms |

All twelve intervals have no frame above 33.3 ms. The candidate's maximum is 22.622 ms. This pair supports approximately 12% lower dome frame time and 5% lower jungle frame time with fog. The dome reaches approximately 55 FPS without fog and 46 FPS with fog; the jungle reaches approximately 52 FPS with fog. The dome still misses the default-fog target. These are ordinary JFR HostLoop measurements, not GPU stage timings; Nsight follow-up is still required to attribute the GPU change.

Candidate scene-color, normal/roughness and primary-depth captures contain no nonfinite values. Dome and jungle previews were inspected and are visually consistent with the control scenes; dynamic guide stability and the broader scene matrix remain to be repeated. Renderer-raytracing and renderer-runtime checks pass. Both benchmark clients restored their camera, time, fog and window settings and exited normally. Artifacts, the complete candidate patch and the feedback oracle are in `tmp/parallel-plane-performance`; nearby control recordings and summary are in `tmp/parallel-plane-control`.

Nsight Graphics UI captures of retained revision `11729255` use the same settled dome at frozen time 1000, Top-Level Triage, real-time shader profiling, and a 2280 MHz base graphics clock. Files and observations are in `tmp/parallel-plane-nsight`.

| Dome configuration | Whole frame | Build | Fill | Resolve |
| --- | ---: | ---: | ---: | ---: |
| Fog off | 20.96 ms | 6.36 ms | 7.37 ms | 1.19 ms |
| Default fog | 27.13 ms | 7.02 ms | 9.66 ms | 1.21 ms |

The fog-off fill plus resolve takes 8.56 ms, compared with 11.74 ms for the earlier serial fill including export. Active threads per warp in fill increase from 18.8 to 22.4, and predicated threads from 17.7 to 21.0. This supports improved scheduling; it does not prove reduced register spilling. Full-stage Flame Graph dependency percentages are global-load/ray-tracing/local-load 45.06/29.76/16.17 for the earlier serial fill and 34.88/37.00/21.43 for independent fill. Work moved into resolve, so these scopes are not identical. The earlier trace also allowed time to advance and includes GPU-context interruptions.

The default-fog capture includes approximately one-millisecond interruptions from another GPU context during fill and reconstruction. Its whole-frame duration is not an ordinary-client mean or an isolated fog-cost measurement. Fill reports 22.4 active threads per warp and dependency percentages of 37.75 global load, 38.81 ray tracing and 16.56 local load. No new event-loss warning was observed. The profiler client restored its initial camera/fog/window and the verified prior time baseline (1417000, advance-time enabled), then exited. The startup time itself was not saved for this profiler run.

A subsequent ordinary-client maximum-speed jungle flight uses spectator speed 0.2 with sprint, level camera, fixed time 1000 and default fog at 4K. It starts with 36,796 resident geometry sections. The 20-second flight averages **15.322 ms**, p99 **22.147 ms**, maximum **71.957 ms**, with two frames above 33.3 ms and one above 50 ms. The 30-second rest averages **14.237 ms**, p99 **16.168 ms**, maximum **88.444 ms**, with one frame above 33.3 ms. Neither interval contains a frame above 100 ms. At flight end 11,135 geometry sections are resident and 54,625 requests pending; after rest 30,958 are resident and all 3,720 remaining requests are neighbor-blocked. This run recovered its streaming backlog. It is not a matched performance comparison with the earlier advancing-time flight.

JFR attributes 66.434 ms of flight loop 869 / frame 20245's 71.957 ms to `world.compositeAndUi`. Nested `frame.composite` takes 66.289 ms, including 13.123 ms in the host-side `vkCmdCopyImage2` recording scope and 2.337 ms in scene effects. The remaining composite time is not covered by enough nested scopes to attribute it. These elapsed CPU scopes include possible driver waits and scheduling; they do not measure GPU copy execution. Recorded GC pauses are only 0.012 and 0.011 ms. The worst rest loop has 4.556 ms in outer mod callbacks; its remaining time is unattributed. Original JFRs, manifest, summaries and scope reports are retained under `tmp/parallel-plane-flight`. The client restored camera, fog, time, speed and window settings and exited normally.

The retained build also repeats six scenes at 4K Performance RR with default fog and fixed time 1000. Each scene settles before a screenshot and three JFR intervals: 15 seconds static, 20 seconds of continuous turning/forward movement, and 15 seconds rest. Means in milliseconds:

| Scene | Static | Turning | Rest |
| --- | ---: | ---: | ---: |
| Lake | 18.35 | 18.93 | 18.54 |
| Cave | 18.61 | 18.50 | 18.62 |
| Indoor room | 18.76 | 18.65 | 18.59 |
| Glass dome | 23.23 | 22.04 | 23.17 |
| Underwater fixture | 16.79 | 17.02 | 16.74 |
| Nether cavern | 19.03 | 19.60 | 19.20 |

Five scenes average above 50 FPS in all three intervals; the dome remains approximately 43–45 FPS. The two frames above 33.3 ms are indoor turning (66.936 ms, 1.843 ms in outer mod callbacks) and indoor rest (38.963 ms, 2.576 ms in outer callbacks). No frame exceeds 100 ms. Other intervals' maxima range from 20.25 to 27.78 ms. These are absolute retained-build observations across this route, not paired controls for a specific change. The dome's different route/population and uncontrolled boost clocks also preclude treating its difference from the earlier two-scene benchmark as a regression.

The six captured views were inspected and match their fixtures. The underwater case is a small controlled basin. Fog being enabled does not establish a substantial fog workload in every scene: the captured density coverage uses skylight, and the medium also excludes positions below its coarse terrain height. Nether fog presence is not established by this benchmark. The indoor view still shows the previously identified ambient fog lighting issue; a static screenshot does not validate temporal foliage-edge stability. Settings, time, window and camera were restored, the client returned to the Overworld, and it exited normally. Artifacts are in `tmp/parallel-plane-matrix`.

## Command-pool hitch attribution

Additional JFR scopes separate presentation-resource checks, host submission creation, command acquisition, finalization and submission. The same fixed-time, maximum-speed jungle flight is repeated with these scopes and `HostSubmission` enabled. Streaming analysis also includes the JVM profile's native method samples. The retained instrumented run is in `tmp/command-scopes-flight`; an earlier coarser run is in `tmp/composite-scopes-flight`.

Flight loop 835 / frame 4026 takes **39.682 ms**, including **18.896 ms** acquiring trace commands and **15.369 ms** acquiring post commands. Its native sample is in `vkResetCommandPool`, reached through `CommandPoolCache.acquire`. Finalization and submission are short. Across this flight, acquisition has eight calls above 10 ms, maximum **20.719 ms**, while command finalization peaks at 0.603 ms and submission at 0.347 ms. This identifies command-pool reset as a contributor to a render-thread hitch; it does not attribute every acquisition delay solely to reset.

Other hitches have a different cause. Flight frame 3472 takes **81.550 ms**, with a **69.790 ms** gap bracketing Minecraft's completion await. Rest frames 5162, 5254 and 5070 take 63.230/61.857/60.534 ms, with corresponding await gaps of 58.947/57.485/55.273 ms; native samples are in `vkWaitSemaphores`. These CPU observations establish waiting for graphics completion, not which GPU workload or competing context delayed it. Ordinary mod callbacks are only approximately 3–4 ms in these frames.

A candidate moves completed-pool reset into lease return before publishing availability. Reset runs outside the return lock, and a blocked reset does not block acquiring another pool. Engine tests verify completion-before-reset, no reuse during reset, stale completion, failure retention and shutdown. Engine-vulkan and renderer-runtime checks pass. The candidate is nevertheless **discarded** because ordinary flight results show no useful end-to-end gain:

| Interval | Acquisition-reset control mean / p99 / max | Return-reset candidate mean / p99 / max |
| --- | ---: | ---: |
| 20-second flight | 15.745 / 21.711 / 81.550 ms | 15.792 / 23.624 / 94.732 ms |
| 30-second rest | 14.408 / 15.316 / 63.230 ms | 14.403 / 15.570 / 78.077 ms |

The candidate reduces acquisition p99 from 0.296 to 0.069 ms but still has six acquisition calls above 10 ms, maximum 23.928 ms, and one 21.282 ms copy-command recording scope. Reset samples move primarily to graphics retirement; merely changing the executing thread does not remove driver-side delays. Sparse hitch counts vary, so these short runs do not establish a statistically significant regression either. Both runs recover the terrain backlog after rest and contain no frame above 100 ms. Candidate patch, raw JFR references, summaries and stage reports are in `tmp/retirement-reset-flight`. All clients restore their settings and exit normally. The command-timing scopes remain; pool reset behavior is restored. Reducing command-pool churn and profiling GPU-completion stalls remain next investigations.

A subsequent candidate groups a frame's ordered command buffers under one pool lease and one graphics-completion boundary. Buffers retain independent descriptor state and the existing barriers. The lease grows its cached buffer list as stages are recorded. Engine and renderer checks pass, including tests for whole-frame completion before reuse and failed growth retaining the native pool. The candidate is archived under `tmp/frame-pool-flight` (`candidate.patch` plus the new `OwnedCommandBatch.java`) and is **not retained**.

Its maximum-speed flight averages **15.766 ms**, p99 **24.574 ms**, maximum **72.527 ms**; rest averages **14.403 ms**, p99 **15.580 ms**, maximum **80.499 ms**. Acquisition still has eleven calls above 10 ms, maximum **28.774 ms**, while composite p99 is 9.515 ms. The adjacent instrumented control averages 15.745 ms in flight with acquisition maximum 20.719 ms. These short samples do not justify a speedup or regression claim; sharing the pool did not remove long acquisition calls or improve end-to-end timing. Terrain recovers after rest. The client restored settings and exited; the original pool implementation was restored and rebuilt with engine and renderer checks passing. Further pool restructuring is deferred pending stronger GPU/driver evidence.

## Multi-frame GPU flight capture

Retained revision `40a0c629` was launched through the Nsight Graphics UI with a manual trigger, 1,000 ms duration, no frame-count limit, Top-Level Triage, real-time shader profiling and a 2,287 MHz graphics clock. The copied world starts with 36,796 resident geometry sections at the same fixed-time jungle pose, default fog, 4K Performance RR and spectator speed 0.2 with sprint. The debug helper records a JFR around the bounded flight; the toolbar triggers GPU collection while movement is active.

The first SDK-trigger attempt returned success but produced no trace because this launch used manual triggering; it is retained as failed capture metadata. The first toolbar capture, `java_2026_09_15_06_27_28.ngfx-gputrace`, overflows its 100,000 timestamp allocation (109,774 required), with event detail missing late in the timeline. After restarting with 300,000 timestamps, `java_2026_09_15_06_31_09.ngfx-gputrace` captures **1,016.69 ms** with event detail throughout and no new overflow warning. Both traces and their JFR references are in `tmp/flight-window-nsight`.

The complete timeline shows regular graphics submissions and concurrent terrain compute across the captured second, with brief other-context intervals. It does not capture a clearly isolated long GPU frame that explains the rare ordinary-client hitches. Whole-trace throughput is 43.3% VidL2, 26.4% L1TEX, 25.8% SM and 18.9% VRAM total bandwidth; these averages span mixed graphics and compute workloads and must not be read as stage-specific bottleneck measurements.

The accompanying JFR contains a **7,605.900 ms** host loop with repeated native samples in `vkQueueSubmit2KHR` during profiler collection/transfer. This is a profiler-disturbed interval, not a reproduction of the reported resize failure or an ordinary-client performance sample. No speedup claim is based on these JFR timings. The profiler clients restored camera, fog, speed, time and window settings and exited normally. The persistent below-1-FPS resize issue remains unreproduced; rare driver/graphics-completion hitches and the default-fog dome budget remain unresolved.

## Reuse endpoint attenuation

Build now stores restart throughput after the endpoint segment's homogeneous absorption and spatial-medium attenuation. Fill reuses that throughput and skips integrating the same segment again. Build still owns its scattering and emission; later fill segments retain their existing transport. This changes no buffer layout, sample count or storage allocation. It is distinct from the rejected hit-record cache, which added per-pixel storage.

The candidate passes renderer-raytracing and renderer-runtime checks. Two candidate runs capture finite color, normal/roughness and primary-depth buffers in both scenes; previews were inspected. These are finite-value and visual checks, not pixel-exact equivalence or a complete dynamic glass validation.

The initial control launch failed before measurement with native allocation failure (`run/hs_err_pid27412.log`): system physical memory had 608 MB free and available page-file/commit capacity was 127 MB. The system drive had less than 1 GB free. Closing the saved Nsight session and removing two regenerable JSON exports (5.9 GB total, after verifying their original JFRs) recovered resources. Both raw JFRs and text summaries were kept. This failure is not a GPU hang or proof of the reported resize problem, but motivates correlating future flight hitches with system memory pressure.

The successful control and subsequent candidate repeat use frozen time 1000, settled matching poses, 4K Performance RR and default fog. Each interval contains at least 600 host frames:

| Scene / fog | Control mean | Candidate repeat mean |
| --- | ---: | ---: |
| Dome / default | 22.110 ms | 21.699 ms |
| Dome / off | 18.329 ms | 18.194 ms |
| Dome / default repeat | 22.155 ms | 21.604 ms |
| Jungle / default | 18.937 ms | 19.040 ms |
| Jungle / off | 15.409 ms | 15.405 ms |
| Jungle / default repeat | 19.006 ms | 19.042 ms |

No interval exceeds 33.3 ms. The pair supports about 2% lower dome frame time with fog; jungle performance is effectively unchanged. The initial candidate before resource recovery measured dome 21.499/18.077/21.453 ms and jungle 18.872/15.346/18.921 ms, but background conditions changed, so it is not the primary comparison. There is no new GPU-stage attribution or hitch-reduction claim for this change. The dome still misses 50 FPS with default fog.

Artifacts are under `tmp/restart-attenuation-performance`, `tmp/restart-attenuation-control` and `tmp/restart-attenuation-repeat`. All surviving benchmark clients exited normally. The final repeat restores the camera, fog, window, speed, time and advance-time setting saved before the failed launch, rather than preserving the failed control's temporary frozen-time state.

## Memory-correlated flight and resize

A subsequent recording failed when Windows reported insufficient system resources while JFR created a repository chunk; JFR shut down the JVM. This is separate from the earlier native allocation failure. Its completed flight recording remains available through `tmp/memory-pressure-flight/motion-manifest.json`; the rest interval is incomplete. The original memory sampler saved only during cleanup and lost its samples when restoration failed. The revised sampler writes each observation immediately, independently of client restoration.

At the user's request, cleanup removed 393 expanded JFR JSON and Nsight spreadsheet exports, freeing 174.44 GiB. All 903 raw JFR/Nsight captures inventoried in `tmp` and `run` remained present with unchanged sizes. Summaries, source changes and worlds were preserved. The deletion inventory is `tmp/cleanup-profile-exports-report.json`. Historical references to expanded exports may now require regeneration from the raw captures.

Revision `68ad038c` was then measured with only HostLoop, HostSubmission, GpuWait and TerrainDispatchPlan custom events, plus the JVM profile. Eight recordings total 13.46 MiB. A separate Windows sampler observes process private bytes, working set, total page faults, system physical availability and commit headroom at approximately 100 ms intervals. Its call duration averages 0.267 ms, p99 0.567 ms. These observations are not GPU residency measurements; total faults include soft faults.

The fixed-time, default-fog, 4K jungle flight starts with 36,796 geometry sections and uses spectator speed 0.2 with sprint for 20 seconds. Flight mean/p99/maximum are **16.643 / 21.496 / 38.746 ms**. The 30-second rest measures **15.271 / 16.812 / 62.363 ms**, with two frames above 50 ms. Rest recovers to 30,952 geometry sections, zero dispatched requests, and 3,744 pending requests that are all neighbor-blocked. Minimum sampled physical availability is 16.22 GiB during flight and 16.79 GiB during rest; commit headroom remains at least 6.73 and 8.30 GiB respectively.

Three 1600x900-to-4K cycles peak at **50.302 / 50.192 / 52.386 ms** when returning to 4K, followed by medians of 15.484 / 15.523 / 15.587 ms. Downsize peaks are 61.589 / 56.209 / 55.676 ms. No recorded frame exceeds 100 ms. The persistent below-1-FPS resize failure remains unreproduced. This is not a paired test of cleanup's effect on renderer performance.

Rest frames 4786 and 4968 take 62.363 and 57.816 ms, including completion-await gaps of 58.283 and 53.295 ms, with native samples in `vkWaitSemaphores`. Frame 4876 takes 48.347 ms with a 10.160 ms await gap. HostWork and CpuStage were disabled, so the helper's zero callback totals are unavailable attribution, not measured zero work.

Each of these three rest hitches overlaps a sampled increase of approximately **256 MiB in process private bytes and 65,800 process page faults**, with little working-set change. The bracketing samples span approximately 110 ms, so this establishes correlation rather than exact ordering or an allocation owner. Ample available memory rules out sampled commit exhaustion during these hitches; it does not rule out driver residency work. The recorded ZGC page-allocation events are 2 MiB requests with zero newly committed bytes and do not identify these 256 MiB changes. Allocation-level attribution is the next investigation before changing pool sizing or ownership.

Raw recording references, memory observations and compact analysis are under `tmp/memory-pressure-clean`. The client restored the original camera, fog, speed, window and time settings and exited normally. No production behavior changed for this measurement.

## VMA growth attribution

Opt-in `GpuMemoryBlock` JFR events identify actual allocation/free callbacks from the renderer's VMA allocator. The callback identifies the requesting stack and memory type; it does not measure native allocation duration. Renderer allocator callbacks remain alive through allocator destruction. Engine-vulkan and renderer checks pass with the instrumentation.

In `tmp/memory-block-flight`, four rest hitches of 58.007 / 56.272 / 48.865 / 48.037 ms occur within approximately 18 ms after separate **256 MiB type-4 allocations**. All four originate in `RtNeeAtBackend.prepareLightRevision` through mapped GPU uploads. The two longest have completion-await gaps of 53.400 and 52.849 ms. A 49.653 ms flight hitch overlaps three type-4 block frees on resource retirement, originating in terrain-buffer destruction. The worst flight frame, 81.011 ms, has no nearby block event and remains a separate case. Flight mean/p99 are 16.649 / 22.988 ms; rest mean/p99 are 15.418 / 16.899 ms.

A candidate reuses released light-table buffers using the existing reader-owned slot pool, with geometrically rounded capacity. It never writes storage while a revision, frame or history reader retains it. The first candidate flight/rest peak at 30.335 / 19.991 ms with no frame above 33.3 ms, and no VMA block allocation during rest. However, the repeat still has three rest hitches at 51.135 / 49.341 / 42.060 ms. Each coincides with another 256 MiB type-4 allocation, now originating in `MinecraftVulkanTerrainUploader` on a terrain worker. Repeat flight peaks at 28.540 ms; rest allocates 768 MiB. Average frame times are effectively unchanged across these short runs.

This identifies shared allocator growth as the broader target: the light-table call was a trigger for a block shared with other uploads, not sufficient evidence that light tables themselves require a gigabyte. The buffer-reuse candidate is **discarded** rather than retained based on its first favorable sample. Its patch, raw recordings and summaries are under `tmp/light-storage-flight` and `tmp/light-storage-repeat`. The repeat's color, normal/roughness and depth captures are finite; its preview was inspected. The allocator inventory contains about 3,208 MiB of named terrain uploads; renderer buffer labels are not VMA allocation names, so this inventory does not isolate the light-table storage cost. All clients restored settings and exited normally.

The retained allocator policy sets VMA's preferred large-heap block size to **64 MiB**. This changes ordinary growth granularity; it is not a cap on individual allocations or a preallocated memory budget. No memory type, mapping flags, shader inputs or ownership rules change. The original light-table implementation is restored. Engine-vulkan, renderer-raytracing and renderer-runtime checks pass.

| Interval | 256 MiB control mean / p99 / max | 64 MiB first mean / p99 / max | 64 MiB repeat mean / p99 / max |
| --- | ---: | ---: | ---: |
| 20-second flight | 16.649 / 22.988 / 81.011 ms | 16.619 / 21.899 / 31.992 ms | 16.654 / 23.194 / 45.969 ms |
| 30-second recovery | 15.418 / 16.899 / 58.007 ms | 15.324 / 17.212 / 24.612 ms | 15.264 / 17.069 / 24.596 ms |

The smaller-block recovery intervals have zero frames above 33.3 ms, compared with four in the instrumented control. They still perform 26 and 31 block allocations, all 64 MiB (1,664 and 1,984 MiB total), so the result is not explained by avoiding all allocation. Their allocation counts and cumulative allocated bytes are higher than the control's four 256 MiB allocations; cumulative bytes do not measure simultaneous residency. Recovery p99 is slightly higher, and average timing is effectively unchanged. The result supports reducing the large allocation-associated tail, not a general FPS gain or elimination of every hitch.

The repeat's single 45.969 ms flight hitch has only a 0.020 ms completion-await gap and repeated execution samples in Minecraft's `ChunkSkyLightSources.findLowestSourceY` / `fillFrom`, reached through chunk packet installation. Its nearest allocation callback is approximately 163 ms after the loop starts, outside the hitch. This is a remaining CPU workload, distinct from the measured allocation/completion stalls. Samples identify the active work but do not establish its entire duration.

Both smaller-block runs recover to 30,952 geometry sections with zero dispatched requests and all 3,744 remaining requests neighbor-blocked. The repeat's three returns to 4K peak at 50.663 / 51.093 / 52.766 ms, followed by medians of 15.437 / 15.527 / 15.588 ms. Downsizes peak at 61.268 / 56.240 / 55.712 ms. No interval exceeds 100 ms. The persistent below-1-FPS resize report remains unreproduced and is not claimed fixed. Artifacts are `tmp/small-block-flight` and `tmp/small-block-repeat`; settings were restored and both clients exited normally. These are JFR host timings and allocator observations, not new Nsight GPU-stage measurements.

## Skylight initialization scope

The retained allocator policy is measured with a targeted `ChunkSkylight` event around Minecraft's `ChunkSkyLightSources.fillFrom`. The event is disabled by default and includes coordinates and the executing thread. Client checks pass and the mixin runs successfully in the development client. This adds measurement only; skylight behavior is unchanged.

In `tmp/chunk-skylight-flight`, the 20-second flight records 9,265 render-thread initializations: mean **0.0432 ms**, p99 **0.1109 ms**, maximum **0.4807 ms**, total **400.607 ms**. Their overlap with host loops averages **0.329 ms**, p99 **3.325 ms**, maximum **4.298 ms**. The 30-second rest records 153 render-thread initializations totaling 5.314 ms. Events also occur on C2ME workers, which are excluded from these render-thread statistics.

Flight mean/p99/maximum are 16.573 / 21.709 / 27.387 ms; rest is 15.264 / 16.928 / 24.543 ms. There is no frame above 33.3 ms, so this run does not reproduce or fully explain the earlier 45.969 ms skylight-sampled hitch. The source shows that chunk packet installation loads heightmaps before initializing skylight; using per-column world-surface bounds could skip known air, but no optimization is introduced on this evidence. Typical initialization cost is small, and a packet burst or scheduling effect remains possible. The diagnostic is retained for future hitch attribution while larger GPU/fog costs remain the priority. The client restored settings and exited normally; raw JFRs and compact analysis are preserved.

## Dependent stable-plane build dispatch experiment

The retained dome capture was reopened in Nsight Graphics through Computer Use. Its 7.02 ms build reports 25.4 active threads per warp, 19.2% ray-generation warp occupancy and 73.9% VidL2 throughput. Build hotspots include binding access, shadow traversal and stable-plane/path-record stores. This capture predates endpoint-attenuation reuse; it motivates a hypothesis rather than directly measuring the current binary. UI observations are saved in `tmp/split-build-performance/nsight-review.json`. Nsight was closed before ordinary timing.

A candidate builds one dependent plane per dispatch, using two additional ray-generation entries and barriers. Plane zero's unused specular fields temporarily hold the count and full-precision accumulated stable radiance; finalization clears them before guide export. Existing queued branches retain order and the sampling seeds. Camera direction is recomputed in each dispatch. There is no additional scratch allocation. Renderer checks pass, all three entries compile in the live client, and the six captured color/normal/depth buffers are finite. Dome candidate and control previews were inspected; this is not pixel-exact equivalence or dynamic-guide validation.

The candidate is **discarded**. A subsequent restored control uses the same frozen-time poses, scene populations, 4K Performance RR, fog-on/off/on sequence and at least 600 host frames per interval:

| Scene / fog | Three-dispatch build mean | Restored build mean |
| --- | ---: | ---: |
| Dome / default | 30.170 ms | 26.134 ms |
| Dome / off | 26.330 ms | 21.970 ms |
| Dome / default repeat | 30.181 ms | 26.237 ms |
| Jungle / default | 22.057 ms | 22.749 ms |
| Jungle / off | 17.822 ms | 18.444 ms |
| Jungle / default repeat | 22.037 ms | 22.763 ms |

The dome regresses by approximately 4 ms; the small jungle improvement does not justify retaining the split. The candidate has one 38.219 ms frame; all control frames are below 33.3 ms. No new GPU-stage capture isolates the cause of the regression. The complete patch and two additional entry files remain under `tmp/split-build-performance`; control artifacts are `tmp/split-build-control`. The original renderer is restored and checks pass. Both clients restored settings and exited normally.

The restored control is itself slower than the earlier endpoint-attenuation repeat despite identical recorded scene populations and similar sampled GPU clocks (approximately 2.9 GHz). This invalidates comparisons against the older absolute timings as a measure of the dispatch experiment.

The allocator reversal in `tmp/default-block-static` restores 256 MiB growth blocks with the original renderer. Dome default/off/repeat means are 25.917 / 21.656 / 26.233 ms; jungle means are 22.757 / 18.437 / 22.695 ms. All intervals contain at least 600 host loops and no frame above 33.3 ms. The run restores settings and exits normally. These timings are close to the 64 MiB control and do not recover the older 21.6 ms dome / 19.0 ms jungle means. The 64 MiB policy is restored; this comparison does not support allocator granularity as the cause of the steady slowdown. Recorded renderer settings, camera and output dimensions also match the older repeat. The cause remains unresolved; background GPU activity and other unrecorded conditions are not excluded.

## Current steady GPU capture and resource monitoring

Nsight Graphics was launched through Computer Use against the restored build for a settled 4K Performance RR dome with fog off. The bounded 1,031.69 ms capture is retained at `tmp/current-steady-nsight/dome-off.ngfx-gputrace` (about 151 MB); `dome-off.json` records conditions and `review.json` records UI observations. Frame 17 spans 26.61 ms, including a 7.69 ms stable-plane build and 10.25 ms fill. Repeated `Other` GPU contexts interrupt rendering. One spans approximately 497.53–498.40 ms (displayed duration 0.86 ms), with 45.4% Screen Pipe throughput and 50.1% VRAM bandwidth. These pass durations include interruptions and are not isolated shader execution costs. The sampled graphics clock is 2,280 MHz under Nsight, so direct comparisons to ordinary 2.9 GHz runs are inappropriate.

After the client exits, Windows GPU Engine counters identify `dwm.exe` at approximately 20.5% 3D utilization. After Nsight also exits, repeated samples still show 21.7–21.8%; the ChatGPT GPU process is approximately 1.45%. This establishes background compositor activity, but does not conclusively identify the trace's `Other` context or prove it caused the earlier ordinary-run slowdown. A same-resolution presentation-mode comparison is the next diagnostic; system processes are left running.

The profiling client is watched with bounded two-second memory/disk samples in `resources.jsonl`. The helper requests a normal client stop if available physical memory or commit headroom falls below 4 GiB, or disk space falls below 20 GiB. The measured run stays above those floors; settings are restored and the client exits before loading the trace. The watchdog is then stopped. No expanded trace export is generated, and Nsight is closed after analysis. Resource monitoring is part of subsequent profiling runs.

## Same-resolution presentation comparison

The debug service now exposes `window.fullscreen` through Minecraft's normal toggle. Client checks pass, and `tmp/presentation-comparison` verifies live windowed/fullscreen/windowed operation with an unchanged 3840×2160 framebuffer, Performance RR, fixed dome camera, time and settled scene population. Each interval records at least 600 host loops after 120 warmup frames. Fog-on means are 23.093 / 22.370 / 22.972 ms; fog-off means are 19.487 / 18.954 / 19.548 ms. All measured frames are below 33.3 ms. The client restores its original window, camera, fog, time and input settings and exits normally.

Fullscreen saves approximately 0.6–0.7 ms with fog and 0.5–0.6 ms without it, relative to the surrounding windowed intervals. Windows GPU Engine samples show compositor utilization approximately 3.15–3.18% windowed and 1.36% fullscreen. Windowed timing is also faster than the prior approximately 26 ms baseline under higher background compositor activity, but that cross-run comparison is not a controlled attribution. Fullscreen is not made the default and does not meet the dome's 20 ms target. Further renderer optimization remains necessary.

Resource minima are 16.41 GiB available physical memory, 7.22 GiB commit headroom and 201.63 GiB free disk space. The monitor never reaches its stopping floors. Only raw JFRs, a HostLoop-only transient analysis and compact summaries are used; no full expanded JFR export is saved.

## Bounce-dependent indirect-path sampling candidate

RTXPT's `PathTracer.hlsli::HandleRussianRoulette` combines a perceptual throughput term with increasing termination probability after 40% of the bounce limit. The fill candidate applies that policy after scattering, compensates surviving throughput, and retains a 5% survival floor. Stable-plane build and the configured bounce limit are unchanged. The terminal emission segment remains outside this roulette, matching the previous implementation's boundary. This changes the sampling distribution rather than discarding unweighted lighting.

`tmp/bounce-roulette` and the subsequent restored `tmp/bounce-roulette-control` use the same settled 4K Performance RR dome/jungle cameras, frozen daylight and default/off/default fog sequence. Every interval contains at least 600 host loops:

| Scene / fog | Candidate mean | Control mean |
| --- | ---: | ---: |
| Dome / default | 22.738 ms | 23.091 ms |
| Dome / off | 19.096 ms | 19.474 ms |
| Dome / default repeat | 22.771 ms | 23.069 ms |
| Jungle / default | 17.699 ms | 20.120 ms |
| Jungle / off | 14.440 ms | 16.381 ms |
| Jungle / default repeat | 17.828 ms | 20.099 ms |

No measured frame exceeds 33.3 ms. Jungle improves approximately 12%; dome only approximately 1.4%. Outside timing, each run captures 16 unexposed raw trace-color frames per scene, separated by eight frames. Candidate/control mean luminance differs by +0.14% in dome and −0.03% in jungle. Mean temporal variance changes by −3.85% / +5.15%, respectively; jungle median temporal standard deviation increases approximately 16.7%. These short, independently seeded sequences include animated foliage and do not prove equal noise everywhere or flicker-free motion. Raw radiance and all captured final-color/normal/depth buffers are finite. Jungle final-color previews were inspected and show consistent gross lighting and geometry; they do not replace dynamic validation.

The sampling candidate is reinstated for broader dynamic validation, with renderer checks passing. Both clients restore settings and exit normally. Minimum available physical memory is 17.78 / 17.92 GiB, commit headroom 7.16 / 7.34 GiB and disk space 200.32 / 199.00 GiB. Monitoring floors are never reached. Raw captures and compact summaries are retained without full JFR exports. The six-scene dynamic matrix and foliage flicker checks remain outstanding for this candidate.

## Sampling candidate scene and flight validation

`tmp/roulette-scene-matrix` completes the six fixture routes plus maximum-speed jungle flight on `dc046061`, at 4K Performance RR with default fog enabled. Static and recovery intervals are 15 seconds, moving intervals 20 seconds; jungle recovery is 30 seconds. The fixture routes use slow continuous movement and turning; jungle uses speed 0.2, sprint and a level camera. Each scene settles before timing. All 21 raw recordings are complete, settings are restored and the client exits normally.

| Scene | Static mean | Moving mean | Recovery mean |
| --- | ---: | ---: | ---: |
| Lake | 17.13 ms | 17.47 ms | 17.14 ms |
| Cave | 16.60 ms | 16.62 ms | 16.59 ms |
| Indoor | 17.23 ms | 17.13 ms | 17.27 ms |
| Glass dome | 22.92 ms | 22.01 ms | 22.94 ms |
| Underwater | 17.21 ms | 17.49 ms | 17.19 ms |
| Nether | 15.28 ms | 15.63 ms | 15.37 ms |
| Jungle | 16.44 ms | 15.51 ms | 14.59 ms |

Five of the six fixtures, plus jungle, average above 50 FPS. Dome remains approximately 44–45 FPS. There are six host loops above 33.3 ms: one indoor recovery frame at 50.183 ms, four jungle-flight frames (maximum 46.929 ms), and one jungle recovery frame at 38.628 ms. None exceeds 100 ms. Jungle flight p99 is 21.54 ms; recovery p99 is 16.30 ms. At recovery end, dispatched terrain work is zero and all 3,696 pending requests are neighbor-blocked, with 30,974 resident geometry sections. This run does not show persistent streaming failure, but transient hitches remain.

Streaming JFR analysis in `*-hitches.txt` finds 32.231 ms in `world.capture` for a 41.822 ms flight loop, and 37.196 ms in `world.capture` for the recovery hitch. The latter contains a render-thread sample in `RtSectionSnapshots.Cache.put` → `Long2ObjectLinkedOpenHashMap.removeFirst/shiftKeys`, reached during terrain region creation. Other flight-hitch samples include Minecraft skylight map copying, entity extraction and fog-column capture. The indoor hitch includes a native sample in `vkEndCommandBuffer` while importing completed compute writes. Sampling does not establish the duration or sole cause of any sampled operation. The matrix did not enable nested `CpuStage` events; a focused flight repeat with those existing scopes is needed before changing the cache or capture architecture.

The seven-scene screenshot contact sheet was inspected. It confirms the intended fixtures but does not establish temporal visual quality or substantial fog in every dimension. The indoor ceiling still shows the previously noted unoccluded-looking fog illumination; foliage flashing remains unverified. Available physical memory never falls below 17.57 GiB, commit headroom below 6.72 GiB, or disk space below 198.64 GiB. Sampled GPU usage peaks at 14,140 MiB in the checks taken during this run; this is not a continuous peak measurement. No resource floor is reached.

## Focused capture scopes and profiler safepoint interference

`tmp/capture-scopes-flight` repeats maximum-speed jungle flight with nested CpuStage events. Flight: 1,282 host loops, mean 15.684 ms, p99 24.357 ms, maximum 95.323 ms, five above 33.3 ms. Recovery: 2,061 loops, mean 14.552 ms, p99 16.435 ms, maximum 29.873 ms. The largest frame contains 87.954 ms in entity.capture, but overlaps an 83.434 ms jdk.SafepointBegin event. The GC pause itself is only 0.0157 ms. Two other hitches overlap 24.348 / 27.208 ms safepoint-entry intervals. A single texture-capture sample therefore does not establish expensive texture work.

`tmp/safepoint-flight` repeats the route using the existing compiled debug artifacts with `-XX:+SafepointTimeout -XX:SafepointTimeoutDelay=20 -Xlog:safepoint=debug`. The JVM names **JFR Recorder Thread** as the thread failing to reach a safepoint within 20 ms. At JVM uptime 91.172–91.256 seconds, ZMarkEndYoung takes 83.818 ms reaching the safepoint and only 0.0243 ms at it. The matching host loop is 97.605 ms. This demonstrates profiler interference in these densely instrumented runs; it does not explain every gameplay hitch or prove the earlier run had the same delayed thread.

Repeat flight: 1,283 loops, mean 15.665 ms, p99 24.255 ms, maximum 97.605 ms, five above 33.3 ms. Recovery: 2,070 loops, mean 14.482 ms, p99 16.666 ms, maximum 24.375 ms. The two other longest loops contain 25.793 / 23.425 ms frame.composite scopes without long safepoint-entry events. Earlier matrix hitches also lack long safepoint-entry events. Those remain separate leads; render-thread stack samples must not be interpreted as operation durations.

The repeat helper and client both exit successfully after restoring settings. Recovery has zero dispatched requests; all 3,720 pending requests are neighbor-blocked. Minimum physical availability is 17.93 GiB, commit headroom 6.09 GiB, and disk space 201.31 GiB. Raw JFR files, compact hitch reports, and the safepoint log are retained locally. No renderer behavior change is justified by this attribution alone. Continue using lower-volume recordings for representative timing and bounded detailed scopes for diagnosis, checking safepoint entry and monitor contention alongside GC pauses before attributing sampled CPU stacks.

Source files changed externally during this investigation after the initial clean renderer status check. This task did not rebuild or modify those renderer files. The repeat is evidence for JVM safepoint attribution, not a controlled comparison of those concurrent source changes.


## Rejected single-command-buffer compute batches

The remaining detailed flight hitches include 9–20 ms command acquisition scopes. A bounded candidate records all jobs in an asynchronous compute batch into one command buffer instead of one per job. It preserves the existing batch submission, timeline wait, resource ownership and completion callbacks. The candidate temporarily updates the compute API's command-state contract; the production contract and implementation are restored after comparison. Candidate patch: `tmp/compute-batch-flight/candidate.patch`.

Candidate and restored control each run a settled, 20-second maximum-speed jungle flight followed by 30 seconds of recovery at 4K Performance RR with default fog. Both use HostLoop, HostWork, HostSubmission, GpuWait, TerrainDispatchPlan, GpuMemoryBlock and ChunkSkylight events, omitting the densely emitted CpuStage/Frame/FrameCounter events. Both clients and helpers exit successfully and restore temporary settings. Engine-Vulkan checks pass before the candidate; the restored control builds and runs successfully.

| Implementation | Flight mean / p99 / max | Flight >33.3 ms | Recovery mean / p99 / max | Recovery >33.3 ms |
| --- | --- | ---: | --- | ---: |
| Single command buffer | 15.577 / 22.104 / 38.369 ms | 3 / 1,290 | 14.707 / 17.011 / 29.082 ms | 0 / 2,040 |
| Original per-job buffers | 15.515 / 24.094 / 49.319 ms | 3 / 1,293 | 14.419 / 16.287 / 34.327 ms | 1 / 2,078 |

There is no mean-throughput gain. A lower maximum in one short candidate run does not establish reduced rare-hitch frequency, so the candidate is rejected. The candidate still samples native semaphore waits and descriptor-heap binding during hitches; the control samples vkQueuePresentKHR in its 49.319 ms flight and 34.327 ms recovery hitches. These observations do not identify the driver's internal dependencies. They do show that combining background command buffers does not eliminate native host stalls.

Raw recordings and compact hitch reports are retained in `tmp/compute-batch-flight` and `tmp/compute-batch-control`. Available physical memory stays above 17.35 / 17.40 GiB, commit headroom above 6.74 / 6.81 GiB, and disk space above 201.29 / 201.36 GiB. All candidate changes are reverted; the retained roulette renderer remains the production implementation. GPU architecture and fog visual limitations remain open.

## Shadowed ambient fog lighting checkpoint

The enclosed-room baseline reproduces a bright ceiling-shaped scattering field despite no visible light source. Source inspection finds Minecraft's approximate sky radiance supplied through SpatialMediumProperties.ambientSource, whose contract is emitted source radiance, and added directly in primary post fog. Neither ambient contribution tests material visibility.

The implementation separates distant isotropic ambientRadiance from emitted ambientSource. Primary and secondary fog use one shared retained-light/ambient reservoir estimator. Eight retained-light candidates in primary fog (one in secondary) estimate direct lighting; one phase-sampled ambient candidate estimates the constant distant radiance integral. Its reservoir weight accounts for the retained-candidate average. Only the selected candidate traces visibility, so the estimator does not add a second shadow ray per evaluated sample. Emissive spatial-medium sources keep their existing behavior. The post-lighting input record expands from 48 to 64 bytes, with producer stride, buffer allocation, consumer layout and API documentation changed together.

`tmp/ambient-baseline` and `tmp/ambient-shadow` each contain default/off/default-repeat timing and raw normal/scattering/transmittance captures for indoor and jungle fixtures at 4K Performance RR. All six intervals per build complete, with at least 300 host loops each and no loop above 33.3 ms.

| Scene / mode | Baseline mean | Shadowed ambient mean |
| --- | ---: | ---: |
| Indoor default | 16.816 ms | 16.637 ms |
| Indoor off | 14.536 ms | 14.609 ms |
| Indoor default repeat | 16.747 ms | 16.814 ms |
| Jungle default | 17.817 ms | 18.296 ms |
| Jungle off | 14.612 ms | 14.643 ms |
| Jungle default repeat | 17.832 ms | 18.328 ms |

The room's unexposed scattering diagnostic mean falls from 0.512990 to 0.0000001133, with candidate p99 zero. Both room previews were inspected: the ceiling haze disappears and the enclosed unlit room is dark. All decoded scene-color diagnostics are finite. Mean room transmittance is 0.999116 / 0.999503. Game-time procedural animation continues despite fixed daylight, so this is not a pixel-identical density comparison. The unchanged density implementation and near-unity transport are consistent with a lighting correction; no exact-transmittance-equivalence claim is made.

Outdoor previews preserve visible fog and gross scene lighting. The candidate raw scattering image has stronger visible sampling noise, which needs temporal assessment; this checkpoint does not resolve foliage flashing. Jungle pays approximately 0.48–0.50 ms for the shadowed ambient estimator, but both tested scenes remain above 50 FPS. Block-light regression, transmitted-path visuals, dynamic foliage, and the full scene matrix remain required for this shader change.

Shader API, ray-tracing and presentation checks pass, including compilation, shader reflection and SPIR-V validation (`tmp/fog-local/ambient-shadow-check.log`). An initial missing exported helper was corrected before the passing check and live candidate run. Both helpers restore settings and both clients exit successfully. Physical availability remains above 17.42 / 17.37 GiB, commit headroom above 7.08 / 5.83 GiB, and disk space above 200.52 / 199.36 GiB. Raw capture manifests, compact image statistics, previews and JFR recordings remain local. This is a retained visual-correctness checkpoint with the stated performance/noise tradeoff, not completion of the broader goal.

## Block-light regression and conservative terminal-cell clipping

`tmp/ambient-block-light` validates the shadowed-ambient checkpoint in the enclosed fixture at 1600×900, fog density 4/divisor 4/64 samples, midnight, fixed manual exposure and bloom disabled. Original stone emitter-panel and air blocker-plane blocks are verified before modification. After settling, initially advancing game time is verified and then frozen; all dark/lit/blocked/lit-repeat/dark-repeat samples use the latched game time. Three same-frame scene-color/primary-depth/reconstructed-color bundles are captured per condition. The center ROI is output pixels x=400..1199, y=225..674; scattering is divided by the recorded pre-exposure.

Dark and dark-repeat ROI scattering are exactly zero. Lit means are 0.179919–0.180230 and repeated lit means 0.180033–0.181376. Two blocked captures are zero, but the third has mean 0.0001153 and maximum 0.610365. Inspecting the full raw scattering image locates 256 nonzero pixels in one 16×16 patch, x=956..971/y=354..369. This is the footprint of one low-resolution fog texel, not broad ambient leakage. The initial helper completes all captures but its cleanup stops on an idempotent fill error. `restore-ambient-block.py` completes exact fixture/settings restoration, unfreezes ticks and stops the client; the manifest records successful restoration. The repeat uses the existing idempotent-fill helper.

The integration previously allowed a terminal cell whose start precedes the conservative depth limit to sample density and lighting across the entire cell, including beyond the limit. The retained change clips the cell width for both density/visibility generation and integration. Prefix reconstruction normalizes partial transport against that same clipped width. The conservative full-support depth bound is preserved; this does not claim exact per-surface visibility for mixed foreground/background fog texels.

`tmp/fog-terminal-cell` repeats all five frozen-time controls after the change. Dark, blocked, and dark-repeat scattering are zero in all three ROI captures each; lit means are 0.175830–0.177322 and repeated lit means 0.175175–0.176215. Every analyzed ROI value is finite. Normal-color comparison images were inspected and show a lit room with an exposed glowstone panel and darkness when removed or blocked. Procedural time is fixed within each run but differs between runs, so the small lit-mean difference is not an exact estimator comparison. The short repeated sequence supports the boundary correction but cannot prove rare leakage or foliage flashing eliminated everywhere.

Presentation checks, shader compilation/reflection and SPIR-V validation pass (`tmp/fog-local/terminal-cell-check.log`). The repeat helper and client exit successfully after restoring original fixture blocks, clock, exposure, bloom, fog settings and window/pose. Available physical memory stays above 17.37 GiB, commit headroom above 8.98 GiB and disk space above 197.96 GiB. The clipped-cell shader still needs full-resolution performance and moving-foliage validation; the broader performance goal remains open.

## Corrected-fog motion and frozen foliage diagnostics

`tmp/fog-corrected-motion` validates c9fac420 at 4K Performance RR with default fog in the dome and jungle. Each scene settles before a 15-second stationary interval, 20 seconds of circle/maximum-speed flight, and 15/30 seconds of recovery. Raw HostLoop/HostWork/HostSubmission/TerrainDispatchPlan recordings exclude image readback and dense CpuStage events.

| Scene | Static mean | Motion mean / p99 / max | Recovery mean |
| --- | ---: | --- | ---: |
| Glass dome | 22.799 ms | 21.910 / 23.521 / 26.539 ms | 22.815 ms |
| Jungle | 17.062 ms | 15.917 / 19.580 / 39.604 ms | 15.041 ms |

Only jungle flight exceeds 33.3 ms, on three of 1,260 loops; no interval exceeds 50 ms. Dome remains approximately 44–46 FPS, below target. Jungle recovery ends with zero dispatched requests, 30,963 resident geometry sections and all 3,720 pending requests neighbor-blocked. Both helper and client exit successfully after restoration. Physical availability stays above 17.47 GiB, commit headroom above 6.88 GiB, and disk space above 197.83 GiB. Terrain populations differ from earlier runs, so these results validate current behavior rather than establish a precise before/after speedup.

`tmp/fog-foliage-frozen` separately captures 16 transmittance and 16 scattering frames, eight rendered frames apart, at the jungle canopy pose and 4K. Game time is latched at 4,970,957 after initially advancing ticks are frozen. Camera, daylight, density, and procedural time stay fixed; frame-index sampling and reconstruction jitter continue. Bloom and pre-exposure are disabled during these diagnostic captures. The helper restores settings and unfreezes ticks; both processes exit successfully. Raw images are analyzed one at a time rather than retained as an image stack.

The diagnostic masks are heuristic, not geometric ground truth: a native-depth texel is stable when its 16-frame range is at most 1% of its maximum and its minimum exceeds 1e-5; an edge has a range over 10% of its maximum. Masks are nearest-expanded to output resolution. In transmittance, temporal standard-deviation p99 is 0.000577 on stable-depth pixels and 0.018144 at depth edges (edge maximum 0.047076). In log1p(raw scattering luminance), p99 temporal standard deviation is 0.207211 on stable-depth pixels and 0.984908 at depth edges. These are different quantities and must not be compared as common physical units. All decoded raw buffers are finite.

The mean/standard-deviation/edge-mask contact sheet was inspected. Transmittance instability follows depth boundaries, while scattering noise also occupies stable-depth foliage interiors. This identifies separate reconstruction and lighting-estimator concerns under a fixed scene; it does not establish moving-scene acceptance or a before/after flicker improvement. Current post fog is composited after scene reconstruction, so exploring earlier integration is justified for coverage instability, alongside a separate investigation of lighting variance. No additional shader change is made from these measurements alone.

Diagnostic physical availability stays above 16.53 GiB, commit headroom above 7.16 GiB and disk space above 192.89 GiB. Compact outputs are `summary.json` and `temporal-summary.json`; raw recordings/images and manifests remain local. Native flight hitches, dome throughput, moving foliage, other fixture regressions and the full glass request remain open.

## Fog before reconstruction prototype: 4K motion and route smoke checks

The uncommitted prototype applies scene effects at trace resolution before RR, or after NRD and before SR. This lets temporal reconstruction process fog together with scene radiance. Two owned RGBA16F targets add 31.64 MiB at 1920x1080. Exposure metering remains downstream. The prototype is not yet accepted: moving foliage visual quality and block-light regression remain to be checked.

Raw evidence: `tmp/early-fog-motion/manifest.json`, `summary.json`, raw JFR paths in the manifest, and `resources.jsonl`. Default fog, 3840x2160 Performance RR, copied world, dome circle and maximum-speed jungle flight use the same scripted routes as `tmp/fog-corrected-motion`. Both client and helper exited successfully; the manifest records restoration.

| Scene / interval | Mean host loop ms | p99 ms | Maximum ms | Frames >33.3 ms |
| --- | ---: | ---: | ---: | ---: |
| Dome static | 22.705 | 23.951 | 24.413 | 0/664 |
| Dome circle | 21.816 | 23.765 | 26.654 | 0/926 |
| Dome recovery | 22.815 | 23.868 | 25.521 | 0/659 |
| Jungle static | 16.850 | 18.305 | 20.465 | 0/894 |
| Jungle flight | 15.769 | 22.759 | 46.733 | 2/1280 |
| Jungle recovery | 14.951 | 16.822 | 29.525 | 0/2005 |

These means are close to the previous pipeline. This change has not resolved the dome's 20 ms budget shortfall. Resource minima were 17.463 GiB available physical memory, 6.864 GiB commit headroom, and 191.125 GiB disk space.

`tmp/early-fog-routes/manifest.json`, `image-summary.json`, and `routes.jpg` record a separate 1600x900 dome smoke test of RAW, NRD+SR (Reblur), and RR. All 12 captured color/depth/normal buffers are finite. RAW uses 1600x900 effect inputs; both reconstructed routes use 800x450 inputs and 1600x900 outputs. Screenshots show the dome in each route, with expected RAW noise; host chat overlays contaminate the presentation screenshots. This is not glass material acceptance or a temporal visual test. Minecraft configures SR whenever the temporal-denoiser route is selected, so NRD without SR was not exercised. Both processes exited successfully and settings were restored.

Earlier frozen diagnostic evidence remains in `tmp/early-fog-foliage`. Reconstructed T depth-edge p99 temporal standard deviation was 0.002715 versus 0.018144 in the prior pipeline; reconstructed log1p scattering stable-depth p99 was 0.060749 versus 0.207211. The reconstruction filters diagnostic signals and the two runs used different frozen procedural phases, so these numbers are observations, not an exact transport comparison or proof of moving-scene stability. `scene-effect-color` retains the pre-reconstruction diagnostic signal for analysis.

### Block-light regression through reconstruction

`tmp/early-fog-block-light` repeats the frozen midnight room fixture with density 4, divisor 4, 64 fog samples, manual EV -10, bloom disabled, and Performance RR at 1600x900. Each of dark, lit, blocked, lit-repeat, and dark-repeat has three same-frame raw bundles. Both raw `scene-effect-color` and reconstructed `scene-color` are analyzed with the central half-width/half-height ROI, dividing by pre-exposure. Both processes exited successfully; the helper restored the verified stone/air fixture, world clock, camera, resolution, and settings.

Raw pre-reconstruction scattering is exactly zero throughout the ROI in all nine dark/blocked/dark-repeat captures. Exposed glowstone gives raw means 0.207989–0.208934 and repeat means 0.208033–0.208694. Reconstructed lit means are 0.205756–0.205825 and repeat means 0.205713–0.205827. All decoded color arrays are finite. The reconstructed diagnostic adds a roughly 0.00012207 floor in the dark ROI; blocked means are 0.000123140–0.000123921, with maxima declining from 0.001112 to 0.000862 across the three captures. These nonzero reconstructed values must not be described as raw transport leakage: corresponding raw inputs are zero. Nor does this stationary test establish that history residuals are harmless during motion.

The normal screenshot comparison was inspected: exposed glowstone illuminates the room, and dark/occluded conditions look dark. Host chat overlays are present. The raw comparison is authoritative for scattering response; normal images also include surface lighting. Resource minima: 17.508 GiB physical available, 9.554 GiB commit headroom, 190.136 GiB disk. Prototype acceptance still requires moving-foliage visual evidence.

### Retained scene-effect ordering and sampled motion

The scene-effect-before-reconstruction implementation is retained based on reduced frozen diagnostic variation, unchanged measured 4K throughput, finite RAW/NRD+SR/RR route outputs, and preserved raw block-light occlusion. This does not resolve the dome throughput shortfall or establish that brief foliage flashes are gone.

`tmp/early-fog-moving-foliage` contains a normal 4K default-fog jungle turn at 6 degrees/second (24 screenshots), followed by 12 stationary recovery screenshots and raw guide/color bundles before and after. The contact sheets were inspected: scene/fog boundaries remain coherent in the sampled images, without obvious persistent trails. Screenshot/readback overhead yields 67–72 rendered frames between images, approximately 1.23–1.44 seconds. This sparse sequence cannot detect or exclude single-frame flashes and is not a performance measurement. The thumbnail recovery absolute RGB delta mean is 0.003878 and p99 0.039216 (normalized display RGB, central cropped thumbnail); camera-static variation includes ordinary scene animation, lighting, reconstruction, and fog, so it is not a fog-only stability metric. Both processes exited successfully and settings/camera/window were restored. The next performance work must address the remaining trace cost; the original fast-flash report remains open.

## Shelved single-endpoint fill experiment

`tmp/sampled-plane-motion/candidate.patch` preserves an experiment that samples one valid stable-plane endpoint per pixel/frame rather than tracing all valid endpoints. Selection probability is proportional to the square root of residual throughput luminance (floored at 0.001 before sqrt), and only newly traced diffuse/specular contributions receive inverse-probability compensation. Build-owned radiance and guides remain unchanged. Skipped endpoints clear light feedback. The experiment retains the three-layer launch and skips unselected invocations; it is not a compacted queue implementation. The raytracing check passed, followed by a resource-guarded 4K default-fog Performance RR dome/jungle run. Source was restored after the client and helper exited successfully.

Compared with `tmp/early-fog-motion`, dome static mean changed from 22.705 to 21.938 ms, circle 21.816 to 21.083 ms, and recovery 22.815 to 21.990 ms. Candidate dome intervals had no loops above 33.3 ms. Jungle means were essentially unchanged: static 16.838 ms, flight 15.687 ms (p99 20.666, max 37.888, 3/1284 above 33.3), recovery 14.845 ms (max 37.129, 1/2021 above 33.3). The dome screenshot was inspected and is coherent, but this single image does not establish the variance cost. No matched multi-frame variance comparison or NRD acceptance was performed. The approximately 3.4% dome gain and negligible jungle gain do not justify adopting this estimator change without further quality evidence; it is shelved, not retained. Raw JFR and color/guide snapshots are referenced by the manifest.

Resource minima: 17.133 GiB physical available, 6.512 GiB commit headroom, 188.518 GiB disk. The copied-world camera/settings were restored. Remaining trace work should address a larger cost than the number of filled endpoints alone, while retaining the deterministic guide construction required by reflective/refractive surfaces.

## Base-color texture evaluation diagnostic

A matched retained control and temporary shader ablation are recorded in `tmp/base-texture-control` and `tmp/base-texture-ablation`. The ablation replaces only the base-color fetch inside `evaluateMinecraftMaterial` with `(0.5, 0.5, 0.5, 1)`. Coverage/any-hit evaluation, authored transmission weights, normal/roughness maps, geometry, and light selection stay active. This still changes base/transmission tint and therefore throughput, roulette, and potentially path distribution: differences are not isolated texture-fetch durations or an exact upper bound on material-evaluation cost. The appearance-changing shader was archived as `candidate.patch` and restored after measurement.

| Scene / interval | Control mean ms | Ablation mean ms |
| --- | ---: | ---: |
| Dome default fog | 22.785 | 21.175 |
| Dome fog off | 19.129 | 17.887 |
| Dome default repeat | 22.812 | 21.184 |
| Jungle default fog | 18.030 | 17.408 |
| Jungle fog off | 14.555 | 14.080 |
| Jungle default repeat | 18.087 | 17.447 |

The fixed jungle pose uses pitch 28.65, unlike the pitch-zero flight workload. Each interval records at least 300 HostLoop samples after warmup at 3840x2160 Performance RR; no measured interval exceeds 33.3 ms. The diagnostic's limited gain, despite altering appearance and tint, does not support base-texture removal or a base-texture-only redesign as the solution to the throughput gap. No optimization is retained. Minecraft-rendering check passed for the diagnostic, and both client/helper pairs exited successfully with settings restored. Resource minima control/ablation: physical 17.534/17.152 GiB, commit headroom 6.678/6.842 GiB, disk 188.090/187.901 GiB.

The preceding Nsight UI review is recorded in `tmp/current-steady-nsight/shader-review-20260915.json`. Entire-trace aggregate input dependency samples were global load 55.07%, ray tracing 17.76%, local load 16.32%, texture fetch 4.30%. These include other passes and NGX and are not exclusive time attribution. The historical trace predates current fog ordering. The build function tooltip showed 13.38% of total samples and 59.19% long-scoreboard stalls within that function's sample scope. Source navigation temporarily became unresponsive; Nsight private memory reached 9.83 GiB before a normal close completed. No new large trace export was generated. The earlier history ablation remains a reason not to repeat instance-history preprocessing as the next intervention.

## Compact stable-plane guide storage candidate

The uncommitted candidate in `tmp/packed-guides-performance/candidate.patch` separates the logical guide record from its reflected 96-byte physical storage contract (previously 112 bytes). Unit normals use two 16-bit octahedral coordinates; diffuse/specular BSDF estimates use half components. Positions, motion positions, roughness, throughput luminance, radiance, and hit distances remain float32. Queued branch matrices and motion use raw full-precision words until the record is finalized, avoiding guide quantization during branch construction. Three planes at 1920x1080 save 94.92 MiB. This changes guide precision, not the path estimator, and requires NRD and dynamic validation before acceptance.

Raytracing and runtime checks passed (`tmp/fog-local/packed-guides-check.log`), including the reflected capacity check. A resource-guarded 4K Performance RR run uses the same dome and pitch-28.65 jungle stationary script as `tmp/base-texture-control`, with the base texture restored. Dome default/off/default-repeat means are 21.844/18.316/21.921 ms versus control 22.785/19.129/22.812 ms. Jungle means are 17.978/14.487/17.995 ms versus 18.030/14.555/18.087 ms. All six intervals remain below 33.3 ms maximum. The dome still exceeds the 20 ms default-fog budget.

All ten captured scene-color, normal/roughness, primary-depth, diffuse-albedo, and specular-albedo arrays are finite. Exported normal length error p99 is 0.000616 in the dome and 0.000593 in the jungle; this includes the existing half-float guide image representation and is not an angular error bound or an original-versus-packed comparison. Both scene previews were inspected and are coherent. This does not yet verify moving guide stability, NRD, bright/custom-material BSDF estimate range, or exact-jitter guide differences. Both client/helper processes exited successfully and settings were restored. Resource minima: 17.382 GiB physical available, 7.089 GiB commit headroom, 187.230 GiB disk free. Candidate remains uncommitted pending those checks.

### Candidate disposition: shelved

The compact format was removed from the working tree after range review. `traceLobes` multiplies thick transmission by the squared incident/transmitted IOR ratio; branch throughput then scales the BSDF estimates. The supported material contract does not bound these values to the half-float maximum of 65504. Finite Minecraft captures do not establish validity for all supported materials. The approximately 4% dome gain and negligible jungle gain do not justify this range restriction, and no compact-format optimization is retained. Full candidate source is preserved in `tmp/packed-guides-performance/final-candidate.patch`.

The separate `tmp/packed-guides-routes` smoke test completed and restored settings; its client log ends with BUILD SUCCESSFUL. RAW, Reblur with SR, and RR color/depth/normal/albedo captures are finite (`image-summary.json`). The contact sheet was inspected, but the presentation includes chat overlays and the NRD image contains horizontal dark marks, so it is not clean visual acceptance or evidence that glass is correct. No dynamic precision comparison was performed.

The next architectural investigation should measure the cost of the endpoint restart boundary: build already resolves the endpoint material, while fill traces a narrow interval around that same hit and resolves the material again before direct lighting and scattering. A previous geometry-cache experiment failed to improve performance, so merely adding another large endpoint cache is not supported. Moving first-endpoint shading into build would require measuring the increased build live state and preserving per-plane direct-light signals, feedback, and guide ownership; it remains a hypothesis, not an implemented optimization.

## Rejected fused endpoint shading

`tmp/global-proposal-control` and `tmp/fused-endpoints-candidate` compare separate versus fused build/fill with the global light proposal in both. Local proposals depend on current depth/motion and are baked between the ordinary passes; using the global proposal avoids consuming an unprepared local distribution in fused shading. This is a diagnostic configuration, not a recommendation to remove local sampling.

The fused candidate shades each endpoint immediately after build stores its guides, reusing the resolved hit and avoiding the restart trace/material evaluation. A completed-endpoint flag keeps feedback valid for resolve while preventing the subsequent fill dispatch from shading it again. The normal fill fallback remains compiled. Branches run serially within each build invocation. The full patch is archived in `tmp/fused-endpoints-candidate/candidate.patch`; all three source files were restored after the client stopped.

| Scene / fog | Separate mean ms | Fused mean ms |
| --- | ---: | ---: |
| Dome default | 22.481 | 45.223 |
| Dome off | 19.066 | 41.470 |
| Dome default repeat | 22.453 | 45.227 |
| Jungle default | 17.712 | 23.361 |
| Jungle off | 14.413 | 19.720 |
| Jungle default repeat | 17.705 | 23.401 |

Both runs use the stationary 4K Performance RR dome and pitch-28.65 jungle protocol, at least 300 HostLoop samples per interval, with raw JFR referenced in the manifests. The fused dome repeat reaches 52.139 ms maximum; control maxima stay below 24.122 ms. This design substantially regresses both scenes despite removing repeated endpoint work. The experiment does not isolate serial branch execution from increased shader live state or other GPU costs; no new Nsight capture was taken for this rejected variant.

Raytracing and runtime checks pass in `tmp/fog-local/fused-endpoints-check3.log`. Initial checks failed when the prototype removed the normal fill shader; the final candidate preserves that fallback and its traversal contracts. All six raw scene-color/normal/depth arrays per run are finite. The control dome/jungle preview and candidate dome preview were inspected without gross scene corruption; this is not a temporal, NRD, water, or glass correctness acceptance. Both helper/client pairs exited successfully and restored settings. Available physical/commit/disk minima were 17.598/6.985/189.647 GiB for control and 17.366/6.781/189.273 GiB for the candidate. Neither global-only sampling nor fused shading is retained.

## Fill shader review after the fusion experiment

Nsight Computer Use successfully reopened the saved `d317a1dc` fog-off dome trace after a fresh-capture attempt failed during launch-window recovery. The fresh attempt produced no timing data; its late-starting client was stopped normally and its process disappearance verified. The saved trace's Top-Down shader table, merged by source across aggregate regions (3,661,479 Vulkan samples), reports fill main at 18.22% and `runFillStablePlanes` at 18.19%. Within fill, `traceDirect` is 9.64%, `resolveWorldHit` 3.38%, self 1.98%, and `traceScatter` 1.25%. Direct-light children include `sampleRetainedLight` 3.78%, `traceVisibility` 3.64%, and `traceSelectLight` 1.25%. These hierarchical inclusive values overlap; neither summing them nor converting them into current frame milliseconds is justified. Driver traversal/scheduler and NGX are separate categories. Displayed live-register values are 125 for fill/direct visibility, 126 for build, and 109 for fill hit resolution; this does not establish the cause of the fused regression.

`tmp/current-steady-nsight/fill-review-20260915.json` retains the observations. They support investigating amortized light-proposal generation ahead of another endpoint material cache or fused pass. Current `sampleRetainedLight` selects a light, evaluates its shape, and computes the full local/global mixture density at every candidate draw. A presampled proposal bank could share distribution work, but any prototype must preserve mixture and reverse PDFs, support all light types, and measure correlation/variance rather than treating unchanged marginal means as sufficient quality evidence. No such optimization is yet implemented. Nsight private memory reached 9.35 GiB; normal close succeeded and process disappearance was verified. No expanded export or new raw capture was created.

## Exact local proxy table: shelved

Source inspection found global selection already uses an indexed proxy lookup. The candidate instead appended at most 64 `(light, mass)` entries to each local 128-slot hash/CDF tile. Each accepted pixel contributes one vote, so integer proxy expansion preserves the local distribution exactly without reusing random shape samples. The original hash/CDF remained available for full mixture and reverse PDFs. Tile storage grew by 15.82 MiB at 1920x1080. Bake amortized the CDF search before fill; each local shading draw used one indexed load.

Fresh stationary 4K Performance RR results (HostLoop mean milliseconds, default/off/default-repeat):

| Scene | Control | Candidate |
| --- | --- | --- |
| Dome | 22.857 / 19.218 / 22.953 | 22.757 / 19.131 / 22.808 |
| Jungle | 18.277 / 14.743 / 18.277 | 18.196 / 14.679 / 18.173 |

These 0.06–0.15 ms differences do not establish a worthwhile gain and leave the dome above the 20 ms fog budget. The candidate was archived to `tmp/local-proxy-candidate/candidate.patch` and all five source/test files restored. No local-proxy optimization is retained. This rules out this implementation as a useful next step; it does not isolate bake overhead from fill savings or prove all proposal preparation ineffective. Larger shading/traversal costs remain the next investigation target.

`tmp/local-proxy-control` and `tmp/local-proxy-candidate` retain manifests, compact summaries, resource samples, and references to raw JFR and six raw image arrays per run. All arrays are finite; this is not visual or temporal correctness acceptance. Raytracing checks, shader ABI/SPIR-V checks, and property tests passed (`tmp/fog-local/local-proxy-check.log`), including exact vote histograms and reverse PDFs with colliding IDs for totals 1–64. Both helper/client pairs exited successfully and restored settings. Minimum physical/commit/disk headroom was 17.425/6.723/191.049 GiB for control and 17.483/6.746/190.551 GiB for candidate. The retained shader package was rebuilt successfully afterward. No new Nsight capture was taken for this rejected candidate.

## Current retained GPU capture after local-proxy rejection

A fresh Nsight Graphics UI capture succeeded on e5044cb7 at the copied dome, 3840x2160 Performance RR with fog disabled and time frozen at 1000. Six consecutive terrain samples held 36,296 resident geometry sections. Capture settings were manual trigger, 1000 ms maximum, Top Level Triage with real-time shader profiling, 20,000 KB event buffers, 300,000 timestamps, and VSync off. The trace spans 1028.12 ms and contains 3,662,242 Vulkan shader samples. `tmp/current-gpu-review-e5044cb7/dome-off.ngfx-gputrace` is 158,654,311 bytes; `observations.json` records the UI readings. No expanded exports were generated.

Top-Down, merged by source across aggregate regions, reports NGX 20.98%, fill main 17.05%, build main 13.62%, driver traversal 13.22%, driver scheduler 7.63%, and resolve main 5.49%. Fill includes direct lighting 9.41%, hit resolution 2.95%, self 1.91%, and scattering 1.04%. Direct lighting includes light sampling 3.79%, visibility 3.59%, and selection 1.21%. Sampling includes shape evaluation 1.87%, local CDF selection 0.91%, global PDF 0.47%, local PDF 0.38%, and global index selection 0.04%. These inclusive sample percentages overlap and are not elapsed pass times. Displayed live registers are fill 125, build 126, resolve 55, and light sampling 111. Whole-trace input dependencies are global load 52.22%, local load 17.95%, ray tracing 17.23%, global store 4.29%, and texture fetch 4.02%; these aggregate values do not identify which source allocation causes stalls.

This current evidence reinforces abandoning global index-lookup work. The next architectural candidate is separating visibility traversal from the shading continuation, rather than fusing more work into build. A separately compiled callable was already rejected, so a new experiment must change dispatch/queue organization. The current visibility loop updates RNG state before scattering and feedback is visibility-weighted; a deferred design must preserve the estimator, ordered transmission/absorption, per-plane ownership, and completion barriers. A queue covering every pixel, plane, and bounce would be excessive; capacity and record lifetime must be designed before allocation. This remains a hypothesis, not an accepted optimization.

The helper restored settings and exited successfully; client PID 58508 disappeared before analysis. Nsight private memory reached 9.79 GiB; normal close completed and PID 26432 disappeared. Disk remained about 190.22 GiB free. Ordinary performance remains established by the separate JFR comparisons above, not by this instrumented capture.

## Rejected first-endpoint visibility dispatch split

The bounded prototype stored the first endpoint's unshadowed diffuse/specular direct contribution and visibility ray in a 96-byte record per stable plane. A separate raygeneration dispatch traced the existing ordered colored visibility transport, applied visibility, combined diffuse distance moments and specular distance precedence, updated feedback, and preceded normal resolve with a barrier. Later bounces remained inline. Storage appended to the existing completion-owned scratch buffer added 569.53 MiB at 1920x1080. Visibility used a separate random stream; this preserves the intended estimator but changes sample sequences and feedback order. Full optical/variance acceptance was not established.

`tmp/endpoint-visibility-candidate` contains the frozen ten-file patch, manifests, raw JFR references, compact summaries, and six raw image arrays. The same stationary dome/jungle script as `tmp/local-proxy-control` produced these HostLoop means (default/off/default-repeat): dome 25.963/22.383/25.950 ms versus control 22.857/19.218/22.953; jungle 19.820/16.593/19.844 versus 18.277/14.743/18.277. All candidate interval maxima were below 28.043 ms. The substantial regressions reject this implementation; no split is retained and no candidate Nsight trace was collected. The comparison does not isolate buffer traffic, dispatch overhead, changed shader scheduling, or changed random streams.

Raytracing/runtime checks passed in `tmp/fog-local/endpoint-visibility-check.log`. An additional shader test explicitly compiled the new entry point and validated its Vulkan SPIR-V/routing (`endpoint-visibility-shader-check.log`). Raw image arrays are finite, which is not visual correctness acceptance. Resource minima were 17.984 GiB physical available, 5.870 GiB commit headroom, and 190.029 GiB disk free; one live GPU sample used 10,470 MiB of 16,303 MiB. The helper restored settings and both helper/client handles exited successfully. All prototype hunks were archived and reversed after exact-file review; the retained renderer was rebuilt successfully.

## Shading before visibility without a new buffer: shelved

The follow-up prepared the direct BSDF/MIS contribution and scattering continuation before tracing visibility. A separate visibility random stream allowed the original ordered shadow loop to run after the surface closure's last use. No new buffer or dispatch was added. The two-file prototype is archived in `tmp/shading-before-visibility-candidate/candidate.patch` and has been reverted.

Stationary default/off/default-repeat HostLoop means were 22.552/19.021/22.580 ms in the dome and 18.122/14.576/18.122 in the jungle. The preceding retained control measured 22.857/19.218/22.953 and 18.277/14.743/18.277 respectively. These modest differences leave the dome over budget and do not justify retaining a changed random sequence without further optical and variance validation. Raytracing checks and client compilation passed; six raw arrays were finite, which does not establish visual correctness. Both processes exited successfully, settings were restored, and the retained renderer was rebuilt. No candidate Nsight capture was taken.

## Resumed maximum-speed streaming investigation

The eastbound route in `tmp/current-jungle-streaming-7e2946ab` completed before a user-requested disk cleanup interrupted westbound recording. It used the retained renderer, 4K Performance RR, default fog, spectator speed 0.2 with sprint, a level camera, and 15/30/45-second static/flight/rest intervals. Eastbound HostLoop means were 17.115/15.227/15.604 ms; flight p99 was 21.628 ms and maximum 30.158 ms. None of the three intervals exceeded 33.3 ms. This route did not reproduce the reported serious frame spike.

Streaming throughput did fall behind: published sections dropped from 83,064 to 34,388 during flight, with 53,110 pending requests at flight end, of which 3,912 were neighbor-blocked. After 45 seconds at rest, 83,928 sections were published and only 3,648 neighbor-blocked requests remained. Raw JFR worker samples prominently identify `TerrainOpacityBaker.classifyRegion` and repeated material-opacity lookup during micromap construction. The worker dispatch planner averaged 0.286 ms over 1,965 events, with a 4.277 ms maximum. `flight-audit.txt` retains the compact sample ranking. These observations motivate amortizing identical material/UV micromap construction; they do not establish an accepted optimization or explain every reported stall.

Cleanup reclaimed 97.46 GiB through identical-capture consolidation, transparent NTFS compression, and verified gzip compression of three old logs. All 56,310 capture paths and byte lengths were checked, and consolidated sources were hash-verified. Worlds and source changes were preserved. The eastbound resource log was empty, so it provides no memory-headroom evidence; the resumed westbound run uses a verified nonempty resource log.

The completed westbound repeat is in `tmp/current-jungle-west-7e2946ab`. Static/flight/rest means were 16.972/15.675/16.394 ms. Flight p99 was 27.047 ms, maximum 53.698 ms, with eight of 1,922 loops above 33.3 ms and three above 50 ms. Static and rest stayed below 33.3 ms. The two largest loops contain a 16.580 ms render-thread park while `ResourceDirectory.retire` submits to its executor's `LinkedBlockingQueue`, and a 15.719 ms monitor wait in `TerrainDispatchPlanner.request`, respectively. Their final submission waits were 0.007 and 0.016 ms. Other hitches have different signatures; these observations do not attribute every hitch to those locks or establish why the lock owners were delayed. `hitches.txt` preserves extended stacks and overlapping host/JVM events.

Westbound loaded-window columns fell from 3,649 to 1,664 during flight. At flight end, 29,664 of 39,936 wanted sections were published and 10,136 requests remained pending; after rest, all 3,649 columns had returned and 83,760 of 87,576 wanted sections were published, with the remaining 3,816 requests neighbor-blocked. Thus upstream chunk coverage as well as renderer preparation limits visible streaming. Micromap classification remained prominent in worker samples; planner mean/max was 0.357/5.797 ms over 1,871 events. Minimum physical/commit/disk headroom was 17.638/6.323/286.793 GiB. Temporary settings were restored and both helper and client handles exited successfully. No renderer change is retained from these measurements. The next candidate is bounded reuse of identical material/UV micromaps, with exact packed-byte equivalence tests, followed by both routes; render-thread retirement and planner lock waits remain separate follow-ups.

## Bounded triangle micromap reuse

`TerrainOpacityCache` reuses complete packed triangle micromaps across section builds on each terrain worker. Its key includes the immutable alpha-bounds object, cutoff, and all six original atlas UV coordinates. Cache misses run the unchanged conservative classifier, preserving its floating-point operations, filtering footprint, animation extrema, and bird-curve packing. Known and fully unknown results are both reusable. Each worker retains at most 4,096 entries and clears them when its material lookup changes. Meshes receive their own immutable packed arrays. Material lookup now occurs once per triangle rather than once per microtriangle.

The candidate completed both 15/30/45-second static/flight/rest routes in `tmp/micromap-cache-streaming`. Its integration was then temporarily reversed, rebuilt, and measured again in `tmp/micromap-cache-control-repeat` to reduce the earlier westbound world-generation confound. Both client/helper pairs exited successfully and restored settings before integration was restored.

| Flight | Control mean / p99 / max ms | Cache mean / p99 / max ms | Control / cache worker execution samples | Control / cache samples with opacity baker in stack |
| --- | --- | --- | --- | --- |
| East | 15.229 / 21.760 / 48.013 | 15.206 / 21.500 / 47.410 | 4,949 / 2,838 | 2,480 / 0 |
| West | 16.172 / 25.588 / 51.401 | 16.111 / 26.831 / 44.590 | 3,818 / 2,197 | 1,291 / 0 |

Worker execution sample counts fall about 42% on both routes. Counts are sampling evidence, not measured CPU milliseconds or proof that misses never occur. Frame-time gains are negligible, and westbound p99 does not improve. Eastbound ends with effectively the same backlog: control 53,167 pending / 34,331 published versus cache 53,160 / 34,316. This is a retained CPU-work reduction, not a solution to the streaming backlog or a general FPS improvement. The per-frame 64-section dispatch budget, including empty sections, remains a candidate throughput limit; its contribution needs direct job/dispatch evidence before changing scheduling. Lock stalls and static dome GPU performance also remain unresolved. No new Nsight capture was needed to assess this CPU-only byte-preserving change.

`TerrainOpacityCacheTest` compares complete micromap values with direct baking over 4,300 randomized sliced inputs, repeated lookups, eviction pressure, epoch changes, multiple animation bounds, cutoffs, and unknown materials; a focused test distinguishes identical UVs with different coverage and cutoff. Initial rendering checks and client compilation passed in `tmp/fog-local/micromap-cache-check.log`. After restoring integration, full Minecraft client and rendering checks passed in `tmp/fog-local/micromap-cache-final-check.log`, including client tests and embedded API verification. Candidate physical/commit/disk minima were 17.659/6.543/286.656 GiB; control minima were 17.576/6.551/286.549 GiB. Raw JFR references, compact summaries, and per-route sampling audits remain in the two artifact roots.

## Empty-section dispatch diagnostic and inline completion

`tmp/terrain-job-audit-720fa819` adds bounded TerrainJob/State/Publication recordings to a 3/10/15-second stationary/flight/recovery route. During flight, 16,904 of 27,561 dispatched sections produced empty geometry (61.3%). Dispatches per observed frame had median and p95 both 64. Worker state samples were usually idle despite tens of thousands of undispatched requests. Empty extraction averaged 0.087 ms and nonempty extraction 0.739 ms, while GPU preparation averaged 12.624 ms. This directly identifies wasted dispatch budget rather than sustained tessellation saturation after micromap reuse.

The prototype selects a bounded vertical-section expansion of each geometry batch. It recognizes all-air center palettes using the existing invalidation-aware snapshot cache, bypasses halo capture and tessellation for those requests, and completes them through the same retained request/group publication path. Only asynchronous tessellation consumes the configured geometry dispatch budget; the 192 in-flight geometry limit is unchanged. Debug worlds retain normal extraction because their blocks are synthesized independently of stored palettes. Non-air sections that happen to produce no visible geometry still use normal extraction.

The same diagnostic in `tmp/empty-inline-job-audit` processed 49,652 requests: 24,447 inline empty completions, 4,978 worker-produced empty results, and 20,227 GPU preparation starts. Median dispatch count rose to 96 per observed frame. End-of-flight pending requests fell from 51,311 to 31,423; published sections rose from 36,160 to 55,928 and resident geometry from 14,124 to 22,490. Mean flight time increased from 16.543 to 17.215 ms while rendering more geometry; each interval had one loop above 33.3 ms. GPU preparation latency rose to 67.784 ms mean and in-flight builds to 132 median, showing that preparation throughput becomes the next constraint. These observed latencies include queueing and do not isolate GPU execution cost.

Client checks passed in `tmp/fog-local/empty-inline-final-check.log`. Added tests verify that an empty revision publishes while the tessellation worker is occupied without using a GPU preparation slot, and that invalidated empty revisions cannot publish. Existing ownership, cancellation, and publication checks also pass. Long-route validation is recorded separately in `tmp/empty-inline-streaming`; it uses the ordinary JFR event set rather than the more detailed lifecycle diagnostic.

The full 15/30/45-second routes completed and the change is retained. Relative to `tmp/micromap-cache-streaming`, eastbound pending requests fell from 53,160 to 38,765 and resident geometry rose from 12,057 to 16,993. Westbound pending requests fell from 57,394 to 31,344 and resident geometry rose from 7,895 to 15,044; both westbound observations had all 3,649 loaded columns, avoiding the earlier partial-column confound. These are roughly 27% and 45% reductions in pending work, while maximum-speed motion still outruns preparation.

Eastbound flight mean/p99/max was 15.927/21.339/34.722 ms, with one of 1,892 loops above 33.3 ms; westbound was 16.781/23.867/63.476 ms, with four of 1,797 loops above 33.3 ms. The prior cache-only means were 15.206 and 16.111 ms while rendering substantially less terrain. All static and recovery intervals stayed below 33.3 ms. The 63.476 ms westbound outlier remains unexplained: measured outer callbacks total only 2.381 ms and the final submission wait gap is 0.002 ms. `west-hitches.txt` preserves the evidence; this is not a stutter-resolution claim. Both routes recovered to only neighbor-blocked requests during rest. The helper restored settings and both helper/client handles exited successfully. Minimum physical/commit/disk headroom was 17.384/5.947/286.372 GiB. Future investigation should separate asynchronous mesh preparation queueing from execution, and continue investigating the remaining host-frame gaps and static-scene GPU budget.

## Compute host timing after empty-section completion

The disabled-by-default `ComputeBatch` JFR event separates host pool acquisition, command recording, submission, timeline waiting, and terminal callbacks. It also records job count and the oldest executable job's queue delay, with an unavailable sentinel for jobs accepted before recording starts. Queue delay precedes the batch; it is not part of its duration. Timeline wait is a host measurement including device progress and host scheduling, not GPU execution time. The event is exposed through the debug service, with no change to compute scheduling or synchronization.

`tmp/compute-host-audit-07672038` retains the short 3/10/15-second diagnostic, raw JFR references, and compact stage summaries. During flight, 494 successful batches processed 47,757 jobs. Median and p95 batch size were both 128, with mean 96.7. Aggregate host batch duration was 9.989 seconds: pool acquisition 0.253 s, recording 1.724 s, submission 0.040 s, timeline wait 6.997 s, callbacks 0.970 s. Mean oldest-job queue delay was 20.421 ms. Recovery processed 31,966 jobs in 1,861 successful batches; aggregate duration was 6.904 s, of which 5.620 s was timeline wait. No batch failures occurred. Values in the compact `*-compute.txt` timing summaries are converted to milliseconds.

The current single compute worker waits for each batch before recording another. These measurements justify investigating bounded overlap of recording with completion waiting; they do not prove a GPU throughput gain or distinguish graphics contention from compute work. The next GPU step is a short Nsight queue-overlap trace before changing synchronization. Any candidate must retain leases through completion, preserve submission dependencies and callback ordering, and correctly drain callbacks that enqueue dependent compaction jobs.

Engine Vulkan checks and client compilation passed in `tmp/fog-local/compute-batch-telemetry-check.log`. The helper restored settings, both process handles exited successfully, and minimum physical/commit/disk headroom was 17.480/6.068/286.321 GiB. No new GPU trace or pipeline optimization is claimed from this CPU diagnostic.

## Confirmed in-flight compute queue trace

`tmp/compute-flight-nsight-held/flight.ngfx-gputrace` is a 152,647,619-byte Nsight Graphics 2026.3.1 capture collected through the UI with maximum-speed spectator input held throughout collection. The helper's flight interval was 15:33:42.469–15:34:39.499 JST; Nsight reported collection beginning at 15:34:12, preparing transfer at 15:34:16, and saving at 15:34:19. The trace spans 998.02 ms. Configuration was the settled eastbound jungle start, 4K Performance RR, and default fog. The prior `tmp/compute-flight-nsight-7af48869` capture lacked reliable flight alignment: the inspected compute work was a frame TLAS build, so it is not used as terrain queue evidence.

The confirmed trace contains sustained mesh preparation on Vulkan Compute Q:3 concurrently with graphics on Q:0. One observed compute submission spans 155.23–183.43 ms, followed by a submission at 190.86–232.15 ms. This leaves a 7.43 ms interval between those submissions while graphics work continues. An inspected marker is `RT ready mesh 487403 build`, containing an acceleration-structure build. These are elapsed GPU timeline ranges under profiling, including scheduling effects, not isolated execution costs. One gap does not establish its prevalence or prove that removing it improves displayed frame times.

Companion JFR (`manifest.json` and `compute.txt`) covers the entire held flight: 1,714 successful batches and 178,276 jobs, with median/p95 batch size 128. Aggregate pool/record/wait/callback host time was 1.126/5.624/39.976/3.322 seconds. Submission includes a 6.584-second outlier during the profiling run; these timings must not be treated as ordinary gameplay performance. The evidence supports testing at most two outstanding compute batches so recording can overlap completion waiting, followed by ordinary east/west route comparisons. It does not justify unbounded submission or removing completion dependencies. Completion callbacks, dependent compaction submissions, cancellation, shutdown, and pool leases must remain correct before performance testing.

The helper restored settings and exited successfully; Minecraft PID 38304 and Nsight PID 57672 were verified absent after normal shutdown. Minimum physical/commit/disk headroom during capture was 15.493/4.147/285.778 GiB. No renderer scheduling change is retained from this trace review. The incremental cleanup compressed 33 new captures without changing their paths or lengths and reclaimed another 117 MiB; future captures remain bounded and raw.

## Bounded overlap of compute recording and completion

The retained compute worker permits at most two submitted batches. It records the next batch before waiting for the oldest, retains each command-pool lease through GPU completion, and uses the last submitted timeline value for the next batch's device dependency. Completed values are still published in submission order. Recording and terminal callbacks remain on one worker. An empty producer queue drains outstanding submissions so a lone batch cannot remain unfinished. Explicit draining also consumes work enqueued by terminal callbacks, including dependent BLAS compaction. Device-idle failures are latched without skipping the accepted batch's failure callbacks.

The validation-enabled run in `tmp/compute-overlap-validation` requested the Khronos validation layer, exercised 67,289 successful compute jobs with two overlapping batch lifetimes, and disconnected with 192 terrain builds outstanding. Outstanding builds reached zero and both client/helper exited successfully. No Vulkan validation errors or device-loss errors were reported. This exercises normal completion and cancellation during disconnect; native device-failure branches were not fault-injected. Engine checks and client compilation passed again in `tmp/fog-local/compute-overlap-final-check.log`.

Ordinary 4K Performance RR/default-fog comparisons use `tmp/compute-overlap-streaming` and the fresh single-batch control `tmp/compute-overlap-control-repeat`. Both use identical JFR event selections, 15/30/45-second static/flight/recovery intervals, and all 3,649 loaded columns at each flight endpoint. The eastbound endpoints differ by approximately 4.4 blocks over a roughly 2.6 km route. The first attempted control in `tmp/compute-overlap-control` was stopped and restored because the patch reversal had failed; it is excluded from comparisons.

| Route / measure | Single batch | Two batches |
| --- | ---: | ---: |
| East flight mean / p99 / max (ms) | 16.428 / 21.508 / 48.152 | 16.867 / 21.122 / 36.980 |
| East flight loops above 33.3 ms | 1 / 1,832 | 1 / 1,788 |
| East pending requests | 38,468 | 25,418 |
| East resident geometry sections | 17,115 | 21,630 |
| West flight mean / p99 / max (ms) | 17.441 / 24.132 / 51.885 | 17.693 / 24.391 / 38.013 |
| West flight loops above 33.3 ms | 2 / 1,729 | 4 / 1,703 |
| West pending requests | 30,604 | 21,603 |
| West resident geometry sections | 15,254 | 17,772 |

Pending work falls by approximately 34% eastbound and 29% westbound; resident geometry rises by 26% and 17%. Mean flight times increase by 0.439 and 0.252 ms while rendering more terrain. Static means are nearly unchanged: east 17.975 versus 18.069 ms, west 17.838 versus 17.855 ms. Recovery means are 16.271 versus 16.347 ms east and 17.525 versus 17.569 ms west; all static/recovery intervals stay below 33.3 ms. Lower observed maxima do not establish a stutter fix, especially with the higher westbound hitch count. Candidate `west-hitches.txt` preserves the remaining host/GPU submission evidence.

Eastbound compute jobs increase from 143,048 to 174,000, and mean oldest-job queue delay falls from 23.327 to 10.888 ms. JFR verifies maximum overlapping batch lifetimes of one for the control and two for the candidate. Event lifetimes now overlap and must not be summed as serial worker utilization; `docs/DEBUGGING.md` documents the changed timing interpretation. All recorded compute batches succeeded. Minimum physical/commit/disk headroom was 17.249/5.455/285.661 GiB for the candidate and 16.987/5.867/285.577 GiB for the control. Both helpers restored settings and all process handles exited successfully. This change improves streaming throughput but does not keep up with the complete view window at maximum spectator speed or resolve the remaining static-scene GPU budget and visual issues.
