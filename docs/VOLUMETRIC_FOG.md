# Volumetric fog checkpoint — 2026-09-13

## Implementation

The working slice renders a spatially varying outdoor medium before exposure metering. Minecraft supplies an immutable 33 × 13 × 33 lattice at 32-block spacing, with biome density, humidity, sky exposure, and terrain height. Capture visits 32 loaded columns per rendered frame without loading chunks and publishes a complete revision after 35 frames. The center snaps to the nearest 64 blocks. Daily density peaks near dawn, diminishes at noon, and increases overnight; time changes do not rebuild GPU field data by themselves.

The generic renderer combines that field with altitude falloff, a weaker ground layer, and periodic world-anchored noise. It integrates single scattering over at most 48 fixed-distance segments, clips the final segment at the surface, and reuses directional visibility across groups of four samples before reconstructing against depth. Integer wind harmonics make the 65,536-second animation cycle continuous. The current implementation is a reduced-resolution raymarch, not a temporally reconstructed froxel volume. It uses the captured sun or moon and approximate ambient sky illumination. Density and lighting are kept separate.

`addSceneEffectPass` records before exposure; ordinary post effects follow metering. Both share the reconstructed image's pre-exposure scale. The fog pass uses camera-relative coordinates and the entry-scene TLAS origin. Its field and image revisions are retained until submitted work completes. Shared compute barriers include sampled-image reads.

A separate R32 primary-depth guide records the first camera intersection during the existing stable-plane build, before delta transmission or reflection. Fog stops at that physical boundary rather than at the reconstruction guide's virtual endpoint. No extra rays are needed for this guide. Outdoor fog is skipped when the camera starts inside a volume.

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

## Remaining iteration targets

- Replace the coarse integration/reconstruction with a froxel volume and explicit temporal reprojection if moving-camera captures justify it. Sparse visibility sampling can band, and thin silhouettes can exceed the current spatial resolution.
- Handle foliage alpha and transmissive visibility instead of treating shadow intersections as opaque. Local lights and accurate sky visibility are not integrated yet.
- Improve terrain and shelter coverage. A 32-block height/light lattice approximates slopes, overhangs, rooms, and biome boundaries. It cannot establish exact interior occupancy.
- Integrate fog along reflected/refracted segments. First-hit composition correctly covers the air before glass/water, but does not fog the subsequent transport segments.
- Extend high-speed travel and scene-rebasing validation with consecutive raw captures, night lighting, denser biome fixtures, and additional representative 32-section scenes. Reload/resize recovery and wind-wrap continuity have initial coverage.
- Quantify peak fog allocation residency and publication hitches. The primary guide costs about 7.9 MiB at this trace resolution; field and reduced-resolution images are small, but replacement overlap and driver allocation overhead were not measured separately.

The broader fog goal remains active. This checkpoint provides a measured first implementation, not completion of the full visual target.


## Planned material visibility and secondary transport

The following changes are planned and are not part of this checkpoint.

Material-aware sunlight visibility should reuse `traceShadowQuery<TComposition>()` in `trace_transport.slang`. It already evaluates coverage, certified opaque blockers, thin transmission, and volume boundaries. Extract the ray traversal loop from `traceVisibility()` into a shared helper taking a ray, initial absorption/IOR, and random seed. Surface lighting supplies its normal-offset origin; fog supplies its sample position. Preserve RGB transmission, the secondary-geometry mask, and scene-relative coordinates.

The standalone fog compute shader does not have the composed coverage/surface/volume dispatch or retained geometry roots. Add a fog-lighting raygen through `WorldShaderCompiler` and `RtPipeline`, with sample inputs and RGB visibility outputs retained by the captured frame. Fog integration and composition can remain compute passes. Keep material interpretation inside the renderer; do not duplicate Minecraft alpha sampling in the fog pass. Measure traversal cost and foliage stability before selecting lighting resolution and temporal update frequency.

Secondary fog needs a generic retained spatial-medium binding in the world frame roots. Evaluate segment scattering and transmittance in `build_stable_planes.slang` before delta branches continue, and in `runFillStablePlanes()` in `path_tracer.slang` for later traced segments. Add incoming throughput times segment scattering to radiance, then multiply throughput by segment transmittance.

Assign every segment to exactly one stage: fill retraces the build endpoint, so it must not integrate that segment twice. While camera-segment fog remains a post effect, transport integration must exclude the first camera segment. Delta-chain scattering belongs in stable radiance; stochastic continuation must follow the appropriate reconstruction signal. Use the existing `PATH_ENDPOINT_VOLUME` state with explicit medium selection to avoid adding outdoor fog inside water or other closed volumes. Validate glass, water, mirrors, foliage, and mixed transmission against the opaque-terrain baseline before expanding light sampling.
