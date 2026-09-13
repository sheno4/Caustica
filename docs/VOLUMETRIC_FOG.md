# Volumetric fog checkpoint — 2026-09-13

## Visual and performance target

Outdoor fog should form a spatial medium with smooth biome and height transitions, stronger dawn/night density and lighter noon air. Nearby geometry should remain readable, fog should respect the first physical surface and material visibility, and reflected/transmitted continuation paths should receive their own medium transport. The reference performance target is at least 50 rendered frames per second on RTX 5070 Ti at 3840 × 2160, RR Performance, four bounces and 32 sections, with frame generation off. Measurements below establish the tested scenes and identify approximation limits rather than promising every possible world workload.

## Implementation

The working slice renders a spatially varying outdoor medium before exposure metering. Minecraft supplies an immutable 33 × 13 × 33 lattice at 32-block spacing, with biome density, humidity, sky exposure, and terrain height. Capture visits 32 loaded columns per rendered frame without loading chunks and publishes a complete revision after 35 frames. The center snaps to the nearest 64 blocks. Daily density peaks near dawn, diminishes at noon, and increases overnight; time changes do not rebuild GPU field data by themselves.

The generic renderer combines that field with altitude falloff, a weaker ground layer, and periodic world-anchored noise. It integrates single scattering over at most 48 fixed-distance segments, clips the final segment at the surface, and reuses directional RGB visibility across groups of four samples before reconstructing against depth. Visibility uses the composed world's material traversal, including alpha coverage and transmission. Integer wind harmonics make the 65,536-second animation cycle continuous. The current implementation is a reduced-resolution raymarch, not a temporally reconstructed froxel volume. It uses the captured sun or moon and approximate ambient sky illumination. Density and lighting are kept separate.

`addSceneEffectPass` records before exposure; ordinary post effects follow metering. Both share the reconstructed image's pre-exposure scale. The fog pass uses camera-relative coordinates and the entry-scene TLAS origin. Its field and image revisions are retained until submitted work completes. Shared compute barriers include sampled-image reads.

A separate R32 primary-depth guide records the first camera intersection during the existing stable-plane build, before delta transmission or reflection. Fog stops at that physical boundary rather than at the reconstruction guide's virtual endpoint. No extra rays are needed for this guide. Outdoor fog is skipped when the camera starts inside a volume.

Secondary transport evaluates the same captured spatial medium along reflected, transmitted and diffuse continuation segments. The engine integrates extinction and single scattering with up to eight fixed 32-metre steps and one retained-light visibility sample per segment. Explicit volume interiors use their existing absorption instead of outdoor fog. The primary post pass still owns the camera segment.

## Controls and diagnostics

The Minecraft settings feature (`caustica:minecraft`) exposes `fog.enabled`, `fog.density`, `fog.resolution-divisor` (4 or 8), and `fog.debug` (0: normal, 1: transmittance, 2: scattering).

Debug calls accept a `feature` argument for settings reads and writes. `image.capture` supports `primary-depth` and `scene-color`, the latter being the final post-chain image before display mapping. With fog debug 1 and bloom disabled or below threshold, this signal is dimensionless transmittance: do not divide it by the capture's pre-exposure metadata. Diagnostic modes replace scene color and therefore affect auto-exposure; restore debug 0 and allow exposure to settle before visual comparison or timing.

JFR adds `host.fogCapture`, `frame.sceneEffects`, and GPU `scene effects` scopes. GPU scene effects are nested within post processing and display; do not sum them with that parent.

## Validation

Experiments used copies `caustica-fog-20260913` (workbench) and `caustica-fog-natural-20260913` (natural terrain). Original worlds were not modified. Local screenshots include lake/valley, higher-altitude, dawn/noon, cave, transmittance/scattering, and RR/NRD views. These establish the first slice; they do not establish complete temporal or material correctness.

The primary benchmark used RTX 5070 Ti, driver 616.56, 3840 × 2160 output, 1920 × 1080 trace resolution, DLSS RR Performance, four bounces, 32-section render distance, frame generation off, and a stationary camera near `(-1769.5, 86.62, -6359.5)`, yaw 47, pitch 5, time 1000. Each run warmed up 150 frames and recorded 400 frames with the same JFR event selection. Capture/readback was outside timing.

The most comparable off/on repeats from checkpoint `9c7c490b`, before the coverage and sampling revision below, are shown here. Times are milliseconds.

| Observation | Fog off | Fog on, divisor 8 |
| --- | ---: | ---: |
| GPU scene effects mean / median / p95 | 0.0010 / 0.0010 / 0.0012 | 0.1976 / 0.1818 / 0.3372 |
| GPU post processing and display mean | 0.5660 | 0.7455 |
| GPU world resources and trace mean | 10.9172 | 10.9274 |
| CPU fog capture mean / p95 | 0.0427 / 0.0571 | 0.0438 / 0.0593 |
| CPU scene effects mean / p95 | 0.0071 / 0.0095 | 0.0405 / 0.0625 |
| Observed rendered-frame start cadence | 64.12 FPS | 63.42 FPS |

Divisor 4 measured GPU scene effects mean 0.2585 ms, median 0.2372 ms, p95 0.4052 ms. CPU fog capture currently continues with the effect disabled, so the off/on difference isolates rendering rather than all producer work. Cadence is derived from consecutive recorded frame-start timestamps, not the inverse of CPU scope duration or generated-frame counts. One stationary scene meeting 50 FPS is not a general gameplay guarantee.

Raw transmittance at frame 5578 had 8,294,400 finite pixels, all within [0,1]: minimum 0.83545, median 0.97217, p95 0.98877, maximum 0.99121. Primary and virtual depth were finite and differed at 48.05% of pixels in this water-heavy view, confirming the importance of separate physical depth.

Nsight Graphics 2026.3.1 successfully captured, loaded, and exported three frames at 4K. The `RT scene effects` intervals were 0.205088, 0.205696, and 0.210752 ms. Nsight locked GPU clocks to base; these instrumented frames corroborate the stage cost and do not replace repeated JFR measurements.

Earlier overlapping-client measurements are excluded. An old client failed shutdown after its development JARs changed; it was identified and terminated before the clean run. An unregistered new telemetry scope was also corrected before the completed recordings. Future iterations must stop clients and confirm exit before rebuilding their dependencies.

Affected API, engine, Minecraft input/client, Vulkan synchronization, renderer runtime, and presentation tests passed. Ray-tracing checks include SPIR-V validation, reflection ABI tests, generation reproducibility, and JAR verification. The added primary-depth ABI word required updating the expected reflected offsets.

Local evidence, intentionally excluded from Git:

- `tmp/fog/measured/`: off/on/quality/repeat conditions, JFR paths and raw exports, `comparison.json`, `transmittance-analysis.json`, RR/NRD screenshots.
- `tmp/fog/validation/`: initial time, altitude, cave and diagnostic captures.
- `tmp/fog/nsight-checkpoint/java_2026_09_13_19_33_43.ngfx-gputrace` and `BASE/D3DPERF_EVENTS.xls`: native trace and exported tabular timings.

## Coverage and sampling iteration

The initial field's edge fade could enter the 256-block integration range and jump when a revision recentered. The 512-block field radius accounts for nearest-center offset, edge fade, and travel while both the published and following capture finish. Geometry tests establish at least 70 blocks of margin at 50 rendered FPS and 87.1 blocks/second, assuming loaded field samples. This is a coverage bound, not a guarantee during chunk streaming or teleportation.

Fixed-distance integration prevents changing surface depth from moving all preceding samples. It does not stabilize samples against camera motion, and short paths receive fewer samples. Tests also cover wind periodicity over positive/negative cycle boundaries. Minecraft client tests and presentation checks passed before the validation client launched.

The revised stationary lake run used the same 4K/RR Performance conditions for 400 frames. GPU fog mean/p95 was 0.2013/0.3448 ms; CPU field capture was 0.0880/0.1413 ms. Completed-frame start cadence was 63.82 FPS. A 300-frame level spectator flight at height 140, dawn, density multiplier 2 measured GPU fog 0.2060/0.3586 ms and CPU capture 0.1237/0.2058 ms. Its 68.59 FPS reflects a different trace workload, not a fog speedup. Full exports and conditions are in `tmp/fog/stability/`, including `summary.json`.

Resize and resource reload recovered successfully. The early reload screenshot lacked distant terrain; a later capture after 600 further frames restored the lake view. Raw transmittance after reload/resize contained 8,294,400 finite pixels in [0,1], with minimum 0.79834, median 0.97266, and maximum 0.99170. These captures verify recovery and bounded output; they do not establish temporal stability along an entire flight. An earlier pitched flight entered terrain and is retained in `tmp/fog/motion/` as exploratory evidence only. Nsight timings above apply to the preceding checkpoint; this revision was measured with JFR.

## Material-aware visibility iteration

`PostEffectFrame.traceVisibility()` records a generic GPU ray batch against the captured world. Each 48-byte input contains TLAS-relative origin/minimum distance, normalized direction/maximum distance, and initial absorption/IOR; each 16-byte output contains RGB transmission. Callers retain buffers, and the renderer supplies compute/RT barriers and the captured material/geometry roots. A third composed raygen invokes the same `traceVisibilityRay()` helper used by surface direct lighting. The world root ABI is 160 bytes. Position-based coverage seeds repeat over the renderer's procedural domain, independent of frame number and dispatch ordering.

Fog prepares up to twelve rays per reduced-resolution pixel, traces the batch, then reads its results during integration. Inputs currently start in vacuum; exact interior occupancy remains a separate requirement. The ray and result allocations total about 23.7 MiB at 4K/RR Performance/divisor 8, or 94.9 MiB at divisor 4, excluding allocation overlap during resize. Visibility retains RGB material transmission rather than forcing every surface opaque.

The copied flat workbench fixture places a backstop along the camera direction and an off-axis wall across morning sunlight. The wall avoids the fog field's sampled columns. Air, stone, glass and persistent oak leaves were compared with frozen ticks, density multiplier 2, and divisor 4 at 1920 × 1080 output. Central-crop raw transmittance differed by at most 0.0000216 on average and primary depth by at most 8.74e-11. After dividing scattering by capture pre-exposure and subtracting the stone baseline, glass retained 52.32% of the air sunlight contribution and leaves retained 3.92%. The repeated air reference drifted by at most 0.0061%. These are fixture-specific material results, not universal transmission constants. Evidence is in `tmp/fog/visibility/fixture/analysis.json`.

A 400-frame requested lake benchmark at 4K/RR Performance measured GPU scene effects mean/median/p95 of 0.3872/0.3484/0.6121 ms. Completed-frame start cadence was 67.35 FPS, but the trace workload differed from previous runs, so total FPS is not a causal before/after comparison. The increased fog-stage cost is measured directly. CPU field capture mean/p95 was 0.1311/0.1975 ms; CPU scene effects was 0.1039/0.1816 ms. API, engine, runtime, presentation and ray-tracing checks passed, including actual visibility-shader compilation and SPIR-V validation. Local logs and JFR exports are in `tmp/fog/visibility/`.

Nsight independently captured three 4K frames with GPU scene-effects intervals of 0.400896, 0.402816 and 0.395488 ms. The nested material-visibility dispatch measured 0.072063, 0.073343 and 0.066815 ms. The native capture and exports are in `tmp/fog/nsight-visibility/`. Its clock-locking instrumentation makes JFR the primary gameplay comparison.

The JFR run contained seven frame-start intervals of 43–58 ms. Each overlapped a render-thread native sample in `vkQueuePresentKHR`; fog GPU time stayed at 0.341–0.358 ms and no GC pause or safepoint overlapped. This points to presentation-side waiting rather than fog execution spikes, but does not identify the driver/compositor cause. Detailed timestamps and sampled stacks are retained in `tmp/fog/visibility/hitch-analysis.json`. The long-frame behavior remains a performance limitation despite acceptable average cadence.

## Shared density and secondary-path baseline

Density evaluation now lives in `caustica_fog_medium.slang`, parameterized by `FogMedium` rather than a global post-pass push block. It contains the existing lattice interpolation, height profile, boundary coverage and periodic noise. The primary pass supplies its existing values through an adapter. This provides one implementation for the planned spatial-volume shader; secondary integration itself is not implemented yet. Presentation compilation, reflection, SPIR-V validation and tests passed after extraction.

A copied-workbench baseline places glass at Z=4100 between a camera near `(4096.5, 69.62, 4048.5)` and a backstop at Z=4160. With frozen ticks and density multiplier 4, the central transmittance diagnostic averaged 0.934839 without glass, 0.960603 with glass, and 0.934842 after removing it. Raw physical and virtual depths diverged with glass. This establishes the current first-physical-segment cutoff, not the correctness of the full transmitted path. Normal-color differences also include the glass material and cannot isolate secondary scattering.

The fixture was repeated after the shared-module extraction and remained finite and bounded. Its game time advanced from 2476127 to 2476606 between clients, changing the animated density phase, so those images are not an exact numerical equivalence comparison. Baseline and repeated raw metadata are retained in `tmp/fog/secondary/` and `tmp/fog/shared-medium/`.

## Captured medium binding iteration

`SceneView` now carries an optional typed `SpatialMedium` independently of the homogeneous medium containing the camera. `FogVolume` prepares immutable field and 128-byte frame-parameter buffers before frame capture. Each parameter owner retains its field revision; accepted frames retain their own binding and instance handles through GPU completion. Primary fog consumes this captured binding rather than invoking a fresh input supplier during post processing. The registered volume model still uses default volume methods: secondary integration is not implemented by this binding change.

The binding stores its coordinate origin as doubles. Runtime may select an older completed TLAS revision with another origin, so density evaluation uses camera coordinates relative to the captured medium origin while visibility rays use the actual TLAS origin. World roots carry the corresponding origin delta for future spatial evaluation. Primary fog and roots share the same resolved-active check, preventing one from using a volume absent from the completed program. The world root ABI is now 200 bytes; the fog push block is 176 bytes. Shared shader modules are packaged for runtime composition, and explicit parameter padding makes physical-pointer and reflected buffer layouts agree.

API, engine, runtime, presentation, ray-tracing and Minecraft tests/checks passed. Coverage includes producer-handle closure, partial-retention failure, captured-origin preservation, root clearing and offsets, shader-source availability, registration, and physical-unit packing. The live copied-world run completed stationary rendering, fast flight, resource reload and resize. A later reload capture restored terrain missing from the early image. These establish successful lifetime recovery, not full temporal-stability proof.

JFR at 4K/RR Performance measured stationary GPU fog mean/p95 0.3945/0.6134 ms and flight 0.4149/0.5718 ms. The new `host.fogPrepare` scope measured 0.0553/0.0851 ms stationary and 0.0561/0.1189 ms in flight. Capture plus preparation averaged 0.1917 and 0.2412 ms respectively. Completed-frame cadence was 66.22 and 70.03 FPS; longest intervals were 120.93 and 45.53 ms, and fog scopes did not account for those delays. Different trace workloads prevent attributing overall FPS changes to this edit. The preparation scope had a 3.68 ms outlier; further stack attribution is needed before choosing an allocation-pooling change.

Raw transmittance contained 8,294,400 finite pixels in [0,1], minimum 0.80664, median 0.97217 and maximum 0.99121. Conditions, raw captures, JFR exports and `summary.json` are under `tmp/fog/captured-medium/`. Nsight numbers in the preceding section apply to the prior visibility implementation; this binding iteration was measured with JFR.

## Remaining iteration targets

- Replace the coarse integration/reconstruction with a froxel volume and explicit temporal reprojection if moving-camera captures justify it. Sparse visibility sampling can band, and thin silhouettes can exceed the current spatial resolution.
- Validate foliage coverage during motion and more complex transmission stacks. Primary fog still uses only captured sun/moon lighting and approximate ambient sky; secondary local-light sampling is coarse.
- Improve terrain and shelter coverage. A 32-block height/light lattice approximates slopes, overhangs, rooms, and biome boundaries. It cannot establish exact interior occupancy.
- Improve and validate secondary integration against controlled transport references. Its coarse density quadrature and shared midpoint lighting can miss narrow layers or shadows; the measured 4K scene has little performance headroom.
- Extend high-speed travel and scene-rebasing validation with consecutive raw captures, night lighting, denser biome fixtures, and additional representative 32-section scenes. Reload/resize recovery and wind-wrap continuity have initial coverage.
- Quantify peak fog allocation residency and publication hitches. The primary guide costs about 7.9 MiB at this trace resolution; field and reduced-resolution images are small, but replacement overlap and driver allocation overhead were not measured separately.

The broader fog goal remains active. This checkpoint provides a measured first implementation, not completion of the full visual target.


## Secondary transport iteration

`IVolumeModel.evaluateSpatialMedium()` supplies extinction per scene unit, scattering albedo, anisotropy and an ambient/emission source per scene unit. Its default is vacuum, preserving existing homogeneous models. The engine dispatches using the captured volume binding and adds the captured-origin delta to TLAS positions. `FogVolumeModel` evaluates the shared density definition; the engine owns light selection and visibility.

Secondary integration uses fixed 32-metre steps, clips the last step, and stops at 256 metres. A single global retained-light sample supplies incident lighting at the segment midpoint, with material-aware visibility capped at 256 metres. Sampling is lazy when scattering coefficients are zero. The position-based seed avoids frame-random noise in build radiance, which bypasses temporal reconstruction. This is a coarse single-scattering approximation: local-light falloff, light direction and visibility are reused across the segment, and fog extinction along light paths is omitted.

Build saves restart throughput before segment attenuation, adds incoming throughput times scattering, then attenuates endpoint emission and child branches. Fill applies the restart segment's attenuation to its alternative contribution but skips scattering and emission already owned by build. Later fill segments add scattering to their diffuse or specular signal. Scattering-weighted sample distances supply finite guides even when the segment ends at the sky. Depth-zero segments and explicit volume interiors are excluded.

Public shader API, API, presentation, ray-tracing, Minecraft rendering and client checks passed, including mixed spatial/homogeneous program compilation and SPIR-V validation. The shadow-routing test verifies both surface and spatial query sites retain the secondary mask and material traversal flags. Runtime composition also compiled and rendered successfully.

The copied workbench captured air, glass and water twice at density multipliers 0, 1 and 4, with fog game time fixed at 2476998. Pre-fog, pre-bloom `reconstructed-color` provides the useful control: glass density 4 increased mean RGB radiance by 2.7–6.1% relative to density zero, while glass density-4 repeat drift stayed below 0.40% per channel. The opaque air baseline changed by roughly 0.05–0.92%, consistent with secondary diffuse paths also receiving fog. These establish a secondary-transport response, not exact energy correctness. Water waves use renderer time and continued moving despite frozen game ticks, weakening that control. Post-chain inversion using diagnostic T/S remains provisional because bloom was not explicitly disabled. Full raw metadata, upstream analysis and labeled contacts are in `tmp/fog/secondary-transport/`.

Two paired off/on JFR repeats used the natural lake at 4K, RR Performance, four bounces and 32 sections, with 180 warmup frames and 400 requested measured frames per condition. Fog-off cadence was 58.99/59.36 FPS; fog-on was 52.44/52.34 FPS. Mean trace time increased by 1.7906 ms, split approximately 0.3932 ms in build and 1.4068 ms in fill. Primary scene effects measured about 0.3826 ms enabled. The non-overlapping trace-plus-post difference was 2.1592 ms; nested scene-effects time is not added again. CPU fog preparation increased by 0.0210 ms. Maximum enabled frame-start intervals were 20.01/19.89 ms. The scene meets 50 FPS with limited headroom; it does not establish a general gameplay bound. Complete exports and `comparison.json` are in `tmp/fog/secondary-benchmark/`.

Nsight captured three 4K frames in `tmp/fog/nsight-secondary/java_2026_09_13_20_57_30.ngfx-gputrace`. Build measured 4.657/4.338/4.635 ms, fill 11.635/11.593/11.813 ms and primary scene effects 0.4065/0.4068/0.4314 ms. These include all build/fill work, not isolated secondary-fog timings. Instrumented base-clock measurements corroborate that continuation tracing dominates this slice; paired JFR remains the performance comparison.

## Controlled variation and motion validation

Checkpoint `e3807029` was exercised without implementation changes. The copied flat workbench used a constant wall, bloom disabled, density multiplier 2, and frozen game time 2477998. Bounded `/fillbiome` edits supplied desert, swamp and a lateral desert/swamp boundary; cleanup restored plains. Every capture had zero outstanding terrain builds after waiting through multiple complete field publications. The common-wall ROI was X45–55%, Y47–50%, excluding the floor. Its physical depth agreed within 8.73e-11 across all nine conditions, so the comparisons isolate the intended input rather than a changed path length.

| Controlled input | Mean primary transmittance | Mean optical depth |
| --- | ---: | ---: |
| Desert, time 1000, player Y68 | 0.996394 | 0.003612 |
| Swamp, time 1000, player Y68 | 0.947856 | 0.053554 |
| Swamp, dawn 23000 | 0.919993 | — |
| Swamp, noon 6000 | 0.982672 | — |
| Swamp, night 18000 | 0.940838 | — |
| Swamp, time 1000, player Y90 | 0.983648 | 0.016488 |
| Swamp, time 1000, player Y120 | 0.993879 | 0.006140 |

Swamp optical depth was 14.83 times desert. Dawn and night were 4.77 and 3.49 times noon. Raising player Y68 to Y90/Y120 reduced optical depth by 69.2%/88.5%. Returning to the original swamp/time/height condition after the time cycle changed mean T by 2.31e-7, with maximum pixel difference one half-float step. All whole-frame T values were finite and in [0,1]. The sampled lateral boundary profile is smooth; it does not establish every biome transition. Raw captures, normal/T contacts and the profile plot are in `tmp/fog/variation/`.

A fixed-position dawn yaw test at 1920 × 1080 captured 12 moving T/depth bundles and four stationary repeats, with density 2, divisor 4 and bloom disabled. It held game time at 4590203. T covered 9.56 degrees of yaw, while a separate normal-color sequence covered 12.11 degrees. The first normal sequence was rejected for insufficient exposure settling after diagnostic mode; the repeated sequence waited 1200 frames. Across 33,177,600 scalar T samples there were no nonfinite or out-of-range values; the observed range was [0.534668,0.975586]. Stationary mean absolute differences were 0.000126–0.000181, with p99 at most 0.001953. Larger differences above 0.01 affected 0.149–0.195% of pixels; more than 99.68% of those were near depth edges under the analysis mask. This associates the outliers with edges/jitter but does not prove their sole cause, since water animation continued. Reviewed contacts show coherent broad fog through the turn. Evidence is in `tmp/fog/motion-secondary-settled/`.

A separate 16-bundle translation sequence covered 482.1 metres at player Y140, yaw47, maximum spectator speed, dawn and density2. All 33,177,600 T samples were finite within [0.694336,0.999023]. The reviewed sequence shows broad density changes and terrain silhouettes without an obvious hard slab seam. Time advanced by 118 ticks; these readbacks are sparse visual observations, not a performance test or proof of a particular TLAS rebase. Field/TLAS origins were not captured. Evidence is in `tmp/fog/translation-secondary/`.

Before those readbacks, a separate 4K/RR Performance flight recorded 607 frame events over 8.235 seconds and approximately 717 scene units. Average cadence was 73.59 FPS, with p95/p99 intervals 16.60/19.89 ms and a maximum of 87.69 ms. Four intervals exceeded 25 ms, so this is not hitch-free rendering. Mean GPU world tracing was 8.1476 ms; primary fog scene effects were 0.4062 ms. CPU field capture/preparation averaged 0.1663/0.0519 ms. At the largest hitch, GPU tracing measured 7.42 ms, fog GPU 0.354 ms and CPU field capture 0.219 ms; these scopes do not explain the delay. Outstanding terrain builds rose from 1 to 62 during travel. This supports the average performance target in a moving workload, without a causal comparison to the stationary lake. Complete JFR exports and `benchmark-summary.json` are in the same directory.

Offline quadrature comparison also quantified an existing secondary-quality limit. Eight uniform 32-metre midpoint steps underestimate a vertical exponential layer's integrated optical depth by 12.05% at an 18-metre scale and 62.76% at a 6-metre scale over 256 metres. Concentrating samples near the origin improves those cases but worsens distant biome ramps; eight-point Gauss–Legendre is more accurate in the sampled fields but moves all nodes with endpoint distance and increases short-path work. No sampling change was adopted without runtime quality/performance evidence. Scripts and dense-reference results are in `tmp/fog/quadrature-analysis.py` and `.json`.
