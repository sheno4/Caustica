# Volumetric fog checkpoint — 2026-09-13

## Implementation

The first working slice renders a spatially varying outdoor medium before exposure metering. Minecraft supplies an immutable 17 × 13 × 17 lattice at 32-block spacing, with biome density, humidity, sky exposure, and terrain height. Capture visits eight loaded columns per rendered frame without loading chunks and publishes a complete revision after 37 frames. Daily density peaks near dawn, diminishes at noon, and increases overnight; time changes do not rebuild GPU field data by themselves.

The generic renderer combines that field with altitude falloff, a weaker ground layer, and periodic world-anchored noise. It integrates single scattering over 48 samples, reusing directional visibility across groups of four samples, then reconstructs the result against depth. The current implementation is a reduced-resolution raymarch, not a temporally reconstructed froxel volume. It uses the captured sun or moon and approximate ambient sky illumination. Density and lighting are kept separate.

`addSceneEffectPass` records before exposure; ordinary post effects follow metering. Both share the reconstructed image's pre-exposure scale. The fog pass uses camera-relative coordinates and the entry-scene TLAS origin. Its field and image revisions are retained until submitted work completes. Shared compute barriers include sampled-image reads.

A separate R32 primary-depth guide records the first camera intersection during the existing stable-plane build, before delta transmission or reflection. Fog stops at that physical boundary rather than at the reconstruction guide's virtual endpoint. No extra rays are needed for this guide. Outdoor fog is skipped when the camera starts inside a volume.

## Controls and diagnostics

The Minecraft settings feature (`caustica:minecraft`) exposes `fog.enabled`, `fog.density`, `fog.resolution-divisor` (4 or 8), and `fog.debug` (0: normal, 1: transmittance, 2: scattering).

Debug calls accept a `feature` argument for settings reads and writes. `image.capture` supports `primary-depth` and `scene-color`, the latter being the final post-chain image before display mapping. With fog debug 1 and bloom disabled or below threshold, this signal is dimensionless transmittance: do not divide it by the capture's pre-exposure metadata. Diagnostic modes replace scene color and therefore affect auto-exposure; restore debug 0 and allow exposure to settle before visual comparison or timing.

JFR adds `host.fogCapture`, `frame.sceneEffects`, and GPU `scene effects` scopes. GPU scene effects are nested within post processing and display; do not sum them with that parent.

## Validation

Experiments used copies `caustica-fog-20260913` (workbench) and `caustica-fog-natural-20260913` (natural terrain). Original worlds were not modified. Local screenshots include lake/valley, higher-altitude, dawn/noon, cave, transmittance/scattering, and RR/NRD views. These establish the first slice; they do not establish complete temporal or material correctness.

The primary benchmark used RTX 5070 Ti, driver 616.56, 3840 × 2160 output, 1920 × 1080 trace resolution, DLSS RR Performance, four bounces, 32-section render distance, frame generation off, and a stationary camera near `(-1769.5, 86.62, -6359.5)`, yaw 47, pitch 5, time 1000. Each run warmed up 150 frames and recorded 400 frames with the same JFR event selection. Capture/readback was outside timing.

The most comparable off/on repeats are below. Times are milliseconds.

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

## Remaining iteration targets

- Replace the coarse integration/reconstruction with a froxel volume and explicit temporal reprojection if moving-camera captures justify it. Sparse visibility sampling can band, and thin silhouettes can exceed the current spatial resolution.
- Handle foliage alpha and transmissive visibility instead of treating shadow intersections as opaque. Local lights and accurate sky visibility are not integrated yet.
- Improve terrain and shelter coverage. A 32-block height/light lattice approximates slopes, overhangs, rooms, and biome boundaries. It cannot establish exact interior occupancy.
- Integrate fog along reflected/refracted segments. First-hit composition correctly covers the air before glass/water, but does not fog the subsequent transport segments.
- Validate high-speed travel, scene rebasing, reload/resize, night lighting, denser biome fixtures, and additional representative 32-section scenes. Current wind animation wraps after 65,536 seconds and needs continuity across that boundary.
- Quantify peak fog allocation residency and publication hitches. The primary guide costs about 7.9 MiB at this trace resolution; field and reduced-resolution images are small, but replacement overlap and driver allocation overhead were not measured separately.

The broader fog goal remains active. This checkpoint provides a measured first implementation, not completion of the full visual target.
