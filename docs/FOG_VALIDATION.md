# Fog validation — 2026-09-14

The revised post fog and path-traced mode were exercised in the disposable save `caustica-fog-review-20260914`. The reproduced flight hitches were traced to grazing transparent shadow rays repeatedly accepting the same triangle, sometimes over 100,000 times. Normal-offset continuation removes that failure in the four measured fixed routes: all five diagnostic counters are zero across 2,436 matched GPU frames, and fill maxima are 6.365–7.021 ms. Post depth and visibility changes address the displaced-plane artifacts; the new path mode remains materially more expensive in dense overhead fog. These runs validate the reproduced failure, not a universal frame-rate guarantee.

## Modes and reproduction

The `caustica:minecraft` settings feature exposes `fog.enabled`, `fog.mode` (`POST_PROCESS` or `PATH_TRACED`), `fog.density`, `fog.resolution-divisor` and `fog.debug`. Disable `fog.enabled` for the control. Path mode evaluates camera and continuation medium transport in the trace; the post camera effect is bypassed. The two modes reuse the captured spatial field. Post debug 1 shows dimensionless transmittance; it is not an exposure-scaled beauty signal.

Post mode de-jitters the physical depth guide, interpolates projected depth within compatible surfaces, and evaluates visibility at each of 96 density samples with quadratically spaced distance intervals. Eight visibility rays per pixel are reused across twelve batches, with full-float accumulation. Secondary single scattering samples a density-weighted position and uses compensated shadow roulette for thin media.

Path mode uses heterogeneous delta tracking, Henyey–Greenstein scattering, direct light sampling and ratio-tracked shadow transmittance. `SpatialMedium` carries the shared field, transport selection and an extinction majorant per scene unit. Explicit glass/water interiors replace outdoor fog. A dedicated primary volume plane leaves two surface planes; NRD and RR reconstruct its noisy lighting. NRD demodulation and remodulation use the same BSDF floor so weak volume lighting survives composition.

This is a bounded real-time volume integrator, with the renderer's bounce limit and a 256-metre medium domain. Stable ballistic transmittance and guide distance use adaptive 1/2/4/8-node quadrature; extremely narrow field features can disagree with stochastic collision sampling. It does not implement all Cycles volume features, and post mode still uses a coarse spatial grid without temporal fog reconstruction.

See [the experiment recipes](FOG_VISUAL_TESTS.md) for `check_fog.py`, fixture creation, restoration and capture semantics. Record JFR separately from image readbacks. Every raw bundle captures its guides and radiance in one submitted frame; its PNG is a separate frame. Local manifests retain settings, poses, paths and checkout state. The checkout revision alone does not identify an already-running binary.

## Final overhead camera — 96-sample quadratic integration

The adopted configuration is recorded in `tmp/fog-review/quadratic96-overhead/`: player `(-1632.065859, 180, -6503.652155)`, yaw 47.52295, pitch 65; 3840×2160 output, 1920×1080 trace, RR Performance, four bounces, frame generation off, density 1 and post divisor 8. Each mode used 300 warmup frames and 400 requested measured frames before separate raw/PNG repeats; all reported zero outstanding terrain builds. Shader diagnostics and Nsight are disabled. Times are GPU milliseconds:

| Mode | World trace mean / p95 / max | Fill mean / max | Scene effects mean / p95 / max | RR mean |
| --- | ---: | ---: | ---: | ---: |
| Off | 15.493 / 15.974 / 17.256 | 11.332 / 13.030 | 0.002 / 0.002 / 0.167 | 3.875 |
| Post | 17.266 / 17.786 / 19.721 | 13.066 / 15.257 | 1.673 / 1.880 / 1.970 | 3.901 |
| Path | 25.066 / 25.771 / 27.873 | 16.518 / 18.850 | 0.001 / 0.001 / 0.001 | 3.842 |

Against the immediately preceding 48-sample baseline below, the adopted post pass costs an additional 0.692 ms on average (0.981 → 1.673 ms). This cost was accepted for the measured improvement in near-camera shaft coverage. World-trace mean changed by −0.071 ms in post mode, while the off control changed by −0.474 ms; these sequential runs do not support a causal percentage speedup claim. World trace and scene effects together average 18.939 ms in post mode, excluding reconstruction and other work. Nested parent/child stages must not be added twice, and these are not complete displayed frame times.

All 541,209,600 raw scalar guide/radiance components inspected in the final overhead repeats are finite. Using pre-exposure removal, explicit 2×2 area reduction and the stable-depth mask described below, reconstructed repeat differences are 1.14–1.38% off, 1.04–1.24% post and 1.06–1.37% path. The saved 1600-pixel post/path previews show coherent broad overhead fog without an obvious displaced plane; fine foliage retains noise. These stationary checks do not establish flicker-free motion.

### Earlier 48-sample overhead measurements
`tmp/fog-review/final-overhead-4k/` contains an earlier 48-sample overhead run before the shadow-continuation fix: player `(-1632.065859, 180, -6503.652155)`, yaw 47.52295, pitch 65; 3840×2160 output, 1920×1080 trace, RR Performance, four bounces, frame generation off, density 1 and post divisor 8. Each condition used 300 warmup frames, 400 requested measured frames and three subsequent raw/PNG repeats. The GPU is RTX 5070 Ti. Times below are milliseconds from JFR GPU stages.

| Condition | World trace mean / p95 | Build mean | Fill mean | Scene effects mean / p95 | RR mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| Off | 15.892 / 16.538 | 3.466 | 11.700 | 0.001 / 0.001 | 3.894 |
| Post | 17.395 / 18.013 | 3.515 | 13.146 | 0.996 / 1.252 | 3.904 |
| Path | 25.720 / 26.495 | 7.955 | 16.904 | 0.001 / 0.001 | 3.869 |

Build/fill are nested in world trace; scene effects are nested in post processing. Do not sum parent and child scopes. Post increases the measured trace mean by 1.503 ms and adds about 0.995 ms in scene effects. Path increases trace mean by 9.828 ms. A single sequential set does not provide a confidence interval, and these stages are not complete displayed frame times.

The initial same-camera post baseline measured 19.726 ms world trace and 0.416 ms scene effects, against a fog-off control of 15.991 ms. That intermediate post run measured 17.395 and 0.996 ms respectively: world tracing improves about 12%, while the combined non-overlapping world/scene-effect scopes improve about 9%. The more accurate primary visibility pass costs more; cheaper secondary transport supplies the net reduction. Controls stayed within about 0.6% for world trace. These are sequential live-world measurements, not a statistical performance guarantee.

All 541,209,600 inspected scalar guide/radiance values were finite. For stationary reconstructed repeats, RGB was divided by the captured pre-exposure and explicitly reduced with a 2×2 area average to native primary-depth resolution. A mask accepted relative reverse-Z changes ≤0.1%, with an absolute floor of 1e-8, excluding sky/geometry transitions. Mean absolute RGB difference divided by reference mean absolute RGB was 1.55–1.94% off, 1.07–1.32% post and 1.13–1.41% path. This includes stochastic transport, animated foliage/water and reconstruction variation; it is not a measurement of perceived display flicker. Native noisy-trace differences were much larger and are not substituted for reconstructed-image stability.

### Uninstrumented 48-sample baseline before the adopted 96-sample integration

`tmp/fog-review/production-overhead/` measures the normal binary after the shadow-continuation fix and before the adopted 96-sample quadratic post-fog integration change. This is a comparison baseline, not the final candidate result. The camera is the same overhead pose above, at 3840×2160 with RR, 300 warmup frames and 400 requested measured frames per mode. Every condition reports zero outstanding terrain builds before measurement. JFR recording precedes the image readbacks, and shader traversal diagnostics are disabled.

| Mode | World trace mean / p95 / max | Fill mean / max | Scene effects mean / max | RR mean |
| --- | ---: | ---: | ---: | ---: |
| Off | 15.967 / 16.459 / 17.576 | 11.809 / 13.044 | 0.001 / 0.111 | 3.909 |
| Post | 17.337 / 17.937 / 18.822 | 13.124 / 14.674 | 0.981 / 1.415 | 3.899 |
| Path | 25.477 / 26.044 / 28.476 | 16.871 / 19.317 | 0.001 / 0.002 | 3.840 |

The post mode increases world-trace mean by 1.370 ms and adds about 0.980 ms in scene effects. Path increases world-trace mean by 9.510 ms. These are nested GPU stages, not displayed frame times. The stationary overhead workload does not replace the raw-counter flight proof of the grazing-ray fix.

## Natural scenes and motion

`tmp/fog-review/final-scenes/` contains 34 captures: lake, dawn, noon, cave, overhead and high sky in all three modes, followed by stationary/turn/settled sequences with NRD/SR and RR. Output was 1920×1080 with a 960×540 trace. All 528,768,000 inspected scalar values were finite. The explicitly area-reduced reconstructed repeat metric above was 0.57–1.04% under NRD/SR and 0.91–1.10% under RR.

Contact sheets and 1600-pixel previews show coherent broad fog in the inspected lake, dawn, overhead and turning views without an obvious displaced illuminated plane. Fine foliage and water retain noise. Sparse stills do not establish flicker-free motion or correct placement of every narrow shaft. Cave beauty differences are not a controlled lighting comparison: exposure was adapting, and the off capture still had 169 outstanding terrain builds. Lake off/post captures also preceded terrain drain. High-sky captures show the environment while terrain work remained outstanding. Those observations cannot establish complete geometry convergence.

## Flight hitch diagnosis and fix

The opt-in shader counters in `tmp/fog-review/shadow-flight/` identify repeated acceptance of a transparent triangle across shadow-query restarts. Independent raw-event analysis in `independent-correlation.json` matches 1,581 frames across three routes. All 13 fill intervals above 100 ms have 50,515–162,930 repeated-primitive events; all 1,517 zero-repeat frames remain below 9.271 ms. Fill duration versus maximum restart count has Pearson correlation 0.993–0.995. Single-query traversal-threshold counters and unchanged-whole-origin counters remain zero: the origin moves tangentially while its plane-normal component fails to advance at grazing incidence.

`traceVisibilityRay` now offsets an accepted hit along the geometric normal toward the outgoing ray side, using up to twice the physical trace epsilon. Only the actual offset's projection along the ray consumes `TMax` and the medium-distance budget. The old medium owns the interval before the boundary; the new medium's absorption owns the forward offset. This fixes continuation progress without a traversal cap or forced opaque cutoff.

`tmp/fog-review/shadow-fixed-flight/independent-verification.json` independently recomputes all counters and fill timings from the four raw fixed recordings. All five diagnostic counters are zero across 2,436 matched frames (2,439 diagnostic records). The three repeated routes compare as follows; times are milliseconds:

| Route | Before fill p99 / max | Fixed fill p99 / max |
| --- | ---: | ---: |
| Off, yaw −43 | 77.496 / 164.925 | 5.771 / 6.597 |
| Off, yaw 47.52295 | 92.946 / 285.462 | 6.119 / 7.020 |
| Post, yaw 47.52295 | 91.848 / 155.630 | 6.133 / 6.364 |

The additional fixed path route at yaw −137 has fill p99/max 6.601/6.771 ms and zero counters. Both sides use the same opt-in diagnostic instrumentation; it can alter GPU cost, and restarted terrain-streaming states are not identical. The before/after disappearance of repeated hits together with the disappearance of long fill intervals supports this specific fix. Uninstrumented performance is measured separately.

### Final uninstrumented flights

`tmp/fog-review/production-fixed-flight/` records the adopted normal binary with diagnostics disabled and no Nsight. The four routes show no recurrence of the measured hundred-millisecond traversal hitches:

| Route | Fill mean / p99 / max | World trace max |
| --- | ---: | ---: |
| Off, yaw 47.52295 | 4.467 / 5.663 / 6.077 | 9.368 |
| Post, yaw 47.52295 | 4.942 / 6.498 / 7.584 | 11.275 |
| Off, yaw −43 | 4.173 / 5.595 / 15.492 | 18.809 |
| Path, yaw −137 | 2.768 / 6.499 / 6.946 | 15.248 |

Post scene effects average 1.632 ms with maximum 2.227 ms. A remaining 15.492 ms fill outlier means these recordings do not establish zero hitches of every kind. Different streamed geometry and mode workloads prevent treating the routes as paired absolute-speed comparisons. The final restoration log and clean client shutdown record restoration of settings and pose.

### Earlier measurements before the continuation fix

`tmp/fog-review/final-flight/` records four approximately 665–669-block routes at 4K and their returns. The routes use different yaws and different streamed geometry; their absolute costs are not a paired fog-only comparison.

| Flight | World trace mean / p99 / max | Scene effects mean / max | Return trace mean |
| --- | ---: | ---: | ---: |
| Post, yaw 137 | 8.229 / 10.536 / 30.784 | 0.964 / 1.891 | 16.593 |
| Off, yaw −43 | 8.320 / 35.022 / 205.930 | 0.001 / 0.018 | 14.897 |
| Path, yaw −137 | 10.963 / 15.957 / 79.460 | 0.002 / 0.252 | 24.249 |
| Post, yaw 47.52295 | 10.212 / 109.174 / 206.587 | 0.984 / 2.102 | 17.064 |

The longest off/post spikes are in world tracing, principally fill, while the post effect stays below 2.11 ms in these recordings. This rules out attributing those entire spikes to the primary post pass. It does not by itself identify their cause or prove a general upper bound. Returned trace costs are comparable to the fixed overhead workloads, without a persistent new increase in these intervals.

CPU timing and native stacks support a GPU completion wait rather than a preceding CPU compilation stall. In `tmp/fog-review/final-flight/off-yaw-43-flight-events.json`, frame 39050 has 202.925 ms GPU fill but only 10.069 ms CPU-frame elapsed; the following CPU frame takes 210.475 ms elapsed and 15.625 ms CPU time. In `tmp/fog-review/nsight-sdk-flight-initialized/events.json`, GPU frame 1141 has 329.734 ms fill, its CPU frame takes 11.710 ms, and CPU frame 1142 takes 337.815 ms elapsed with 15.625 ms CPU time. The same capture's `jvm-samples.json` contains 16 render-thread native samples in `vkWaitSemaphores → VulkanCommandEncoder.awaitSubmitCompletion` and 16 compute-thread samples in `vkWaitSemaphores → RtGpuExecutor.waitTimeline` during 02:39:16.95–02:39:18.10. Both hosts were waiting for submitted GPU work. This narrowed the investigation to GPU execution or scheduling; the later shader counters above identify the repeated-triangle traversal failure.

A separate client repeated the same four routes with the asynchronous preparation batch cap reduced from 128 jobs to 16. `tmp/fog-review/batch16-flight/` retains its conditions and raw events. Fill p99/max on the fog-off yaw −43 route changed from 31.672/202.925 ms to 34.949/168.071 ms; the post yaw 47.52295 route changed from 105.760/203.219 ms to 75.323/329.172 ms. Post yaw 137 fill maxima improved from 27.277 to 7.138 ms, and path yaw −137 build maxima improved from 78.145 to 8.716 ms. These mixed results do not establish a consistent hitch remedy, and restarted streaming workloads are not identical. The batch-cap change was discarded; the implementation retains 128 jobs per batch.

A final one-job batch experiment in `tmp/fog-review/batch1-flight/` tested the minimum number of recorder-created BLAS/OMM scratch allocations held until batch completion. Fill p99/max remained 7.448/122.328 ms on post yaw 137, 45.203/180.368 ms with fog off at yaw −43 and 77.698/260.944 ms on post yaw 47.52295. The poses and routes are comparable, but streaming states are unmatched: outstanding terrain builds before the routes were 141/120/128 and after their returns 74/39/20. The one-job limit increased backlog without eliminating hitches, so it was also discarded. Executor source and binary were restored to the original 128-job policy.

## Flat-wall diagnostic

Six same-frame post-transmittance/primary-depth bundles in `tmp/fog-review/final-fixtures/diagnostics.json` test the close oblique planar wall. Transmittance was read directly, without pre-exposure division. All 12,441,600 T samples were finite and in [0,1], with minimum 0.839844, maximum 0.989746 and per-frame means 0.981560–0.981638. The nontrivial attenuation makes this a useful fog test rather than an all-vacuum result.

Relative to the first frame, T mean absolute differences were 0.0000201–0.0000826 and p99 was 0.00048828125. Maxima reached 0.08496–0.08643 near the lower/right wall edge; 148–182 pixels per repeat exceeded 0.01. None were in the central 80% rectangle, whose maximum difference was one half-float step, 0.00048828125. An affine fit of native reverse-Z over the central wall had RMS residual about 2.21e-9 and maximum residual below 1.67e-8, with stable slopes across repeats. This supports the planar primary-guide contract and stationary central-wall stability; it does not resolve every disocclusion edge.

The original fixture beauty sequence is excluded: positive manual exposure clipped both the displayed images and much of the raw radiance. Its replacement, `tmp/fog-review/final-fixtures-corrected/`, contains 15 beauty captures at fixed manual −12 EV outdoors and −5 EV for the night emitter. All inspected raw components are finite with no values at the fp16 ceiling. The textured wall, roof slit, glass exterior and exposed/occluded emitter are visible in the corrected previews. They show coherent fixture boundaries without an obvious displaced fog plane, but the roof/light alignment does not establish a narrow directional-shaft acceptance result. A further six wall-T captures remain nontrivial and finite: range 0.842285–0.990234, means 0.981907–0.981977, repeat MAD 0.0000193–0.0000701 and p99 one half-float step. The directory `tmp/fog-review/final-overhead/` is excluded because it captured the cave instead of the intended overhead camera.

The glass-tank underwater camera is nearly black and does not pass transmission validation. Separate controls in `tmp/fog-review/water-control/` reproduce this with fog off, post and path, both with RR and without reconstruction. Excluding the lower diagnostic overlay, raw means are 1.22e-5/1.63e-5/1.47e-5, with median and p99 zero and over 99.5% of RGB components zero. RR reconstructed means are approximately 0.000495 in all three modes. This supports a failure independent of the new fog mode; it does not establish the precise material/boundary cause. The exterior and air-side views remain useful, but no successful underwater exit transport is claimed for that glass enclosure.

An independent open sandstone basin removes the glass contact and exposes a free water-to-air boundary. Six captures in `tmp/fog-review/open-water/` show visible sky through the surface from below and visible basin geometry from above in all three fog modes. Every inspected raw component is finite and below the fp16 ceiling. Cropped reconstructed scene-linear RGB means below the surface are 4268/4960/4847 for off/post/path, and above are 3688/3795/3540. These establish nonblack transport across the free surface, not exact agreement: water animation, stochastic sampling and fog contributions differ between captures. The failure in the enclosed tank therefore does not generalize to all underwater viewing.

## Final NRD/RR controls

`tmp/fog-review/final-nrd/` contains 22 captures from the binary with the NRD demodulation/remodulation floor correction: zero-density off/post/path controls, density-4 path repeats, turns and settled views for RR and NRD/SR. All raw color and normal/roughness components are finite and below the fp16 ceiling. Against the zero-density off control, the explicitly area-reduced, stable-depth reconstructed difference metric is 0.299% post / 0.045% path for RR and 1.063% / 1.114% for NRD/SR. Dense stationary repeat differences are 1.26–1.65% for RR and 0.316–0.389% for NRD/SR. Water animation and stochastic sampling remain active, so these comparisons do not assert pixel identity or equality of the denoisers. The fixed −12 EV dawn views are dark; their finite signals and small repeat metrics are useful numerical checks, but the displayed stills provide limited sensitivity to subtle dark-volume artifacts.

## Nsight observations before the continuation fix

The SDK-triggered capture in `tmp/fog-review/nsight-sdk-flight-initialized/` establishes actual flight overlap: the in-process start call acknowledged tracing at renderer frame 1056, wall time 1789321155710 ms, 4.180 seconds after flight began. All 120 exported fill durations uniquely match JFR frames 1058–1177 (mean absolute timing difference 0.001715 ms; maximum 0.008478 ms). It captures fill spikes of 329.738 ms at frame 1141, 276.178 ms at frame 1145 and 155.438 ms at frame 1152; maximum GPU frame duration is 339.085 ms. Build remains at or below 5.207 ms and scene effects at or below 2.737 ms. The completed export reports dropped periodic samples: hardware metrics are unavailable after column 83 and incomplete through the first large hitch. Near-100% asynchronous queue activity and low sampled SM/RT throughput in that interval therefore do not distinguish preemption, memory stalls or a shader tail. VRAM commitment is 8,901 of 15,227 MiB with zero demotion. This capture established the hitch location; the later shader-counter experiment above identified its cause.

The final bounded attempt, `tmp/fog-review/nsight-sdk-low-sampling/`, requested a 65,536-cycle PC sampling interval, sixteen times the earlier interval. The SDK acknowledged tracing at frame 830, but Nsight reported that hardware-event resources were exhausted and the render thread remained unresponsive beyond the three-minute control deadline. No native capture or completed export was produced, so actual applied sampling settings and metric coverage could not be verified. This attempt is excluded from performance conclusions. Its owned profiler/client processes were terminated, then an ordinary client restored the original settings, view, pose and clock and received a clean stop request. No further profiling attempt was made.

The three-frame flight capture `tmp/fog-review/nsight-three-frame-flight/java_2026_09_14_01_41_24.ngfx-gputrace` and exported `analysis.json` show build 2.928–3.164 ms, fill 6.583–8.234 ms, scene effects 0.986–1.516 ms and GPU frames 17.141–18.775 ms. Exported intervals include concurrent work; 213 asynchronous ready-mesh event rows appear in the trace. The large local-memory traffic and overlapping asynchronous activity were insufficient to identify the repeated-triangle failure; they are not isolated fog-instruction costs. These three instrumented frames are not substituted for the separate JFR comparisons or claimed as a capture of a worst-case hitch.

A subsequent 120-frame native capture and completed TSV export are retained in `tmp/fog-review/nsight-long-flight/`. Nsight reported dropped periodic samples and timestamp overflow: 145,840 timestamps were needed but 100,000 were allocated. The actual report configuration records a 1,000 ms maximum duration. All 120 GPU frame durations are finite (median 18.421 ms, maximum 78.845 ms), but named-pass rows contain only 37 populated frame columns followed by placeholder timings. Those populated columns show build 2.792–4.206 ms, fill 6.145–9.212 ms and scene effects 0.974–2.135 ms. The accompanying JFR contains 136–313 ms fill intervals shortly after the scheduled capture start, but exported timing lacks a reliable absolute alignment and the missing trace data prevents attributing those hitches. Reported VRAM commitment is 11,878 of 13,911 MiB, with zero demoted MiB; this does not support a residency-paging diagnosis. Shader local-memory traffic is a separate observation. No hitch root cause is established by this capture.

The separate delayed capture in `tmp/fog-review/nsight-timeline-flight-valid/` used 3,000 ms and one million timestamps, producing 120 valid pass timings without overflow: fill maximum 50.480 ms and GPU frame maximum 61.542 ms. Its separate JFR 224.777 ms fill hitch was absent from exported timings. It reported 512 MiB VRAM demotion, but no frame-local demotion timeline. The actual report still enabled real-time shader profiling despite omission of the CLI flag. Launch-clock scheduling was not a verified capture boundary; the SDK capture above corrects that timing limitation. This delayed capture cannot identify the cause of uninstrumented hitches.

The Python suite currently passes 60 tests, including cleanup/idempotent fixture behavior and independent analytical/statistical references for RGB medium weighting, phase sampling, shadow roulette and heterogeneous delta/ratio tracking. The adopted presentation check passes, including shader compilation. The mathematical references do not execute the GPU shader; live captures are a separate validation layer. Media remain bounded by the implemented transport distance and captured field, and path sampling cost/noise can be substantial in dense scenes.


### Controlled narrow-shaft placement and sampling coverage

`tmp/fog-review/roof-acceptance-frozen/` and `roof-quadratic96/` each contain 24 same-frame primary-depth/scattering/transmittance bundles: two camera positions separated by 0.25 m, open/closed roof slit, two diagnostic signals and three repeats. The world ticks were frozen and game time verified, with noon lighting, density 4, divisor 4, bloom off and fixed exposure. The analytic mask uses captured projection, excludes first surfaces changed by closing the slit, checks measured native depth, and erodes boundaries. The earlier `roof-acceptance-exact` sequence is excluded from strict acceptance because procedural medium time advanced.

Both integrators pass the within-run T and lit/dark mean-placement controls. The 96-sample quadratic candidate has median T 0.993652 and open/closed T p99 difference 0 in both views. Lit delta means are 12.8705 and 13.1965; adjacent dark means remain within five repeat standard errors of zero.

| Sampling | False-dark pixels / eligible lit pixels, first view | Lateral view |
| --- | ---: | ---: |
| 48 uniform steps | 59,121 / 326,724 (18.0951%) | 74,710 / 344,033 (21.7159%) |
| 96 quadratic steps | 0 / 326,678 (0%) | 0 / 343,712 (0%) |

A false-dark pixel has signed mean-RGB open-minus-closed scattering below 1% of that view's mean lit response. This measures holes separately from average placement. Inspected candidate diagnostic previews retain discrete intensity bands, despite eliminating holes by this definition. Baseline and candidate froze different procedural game times: each roof pair isolates visibility, but their brightness difference cannot be attributed solely to integration. These two poses do not establish perfect shaft reconstruction, flicker-free motion, or path-mode direct-scattering placement. See [FOG_SHAFT_TEST.md](FOG_SHAFT_TEST.md) for the reusable protocol and limitations. Final candidate performance is measured separately.
