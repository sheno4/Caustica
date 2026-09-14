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
