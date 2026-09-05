# High-resolution RT profiling — 2026-09-06

The target is 40 rendered frames per second at **3840 × 2054 windowed output**, with **DLSS Ray Reconstruction Performance**. The NGX startup log confirms **1920 × 1027 internal resolution**, quality enum 0. Frame generation and VSync are disabled. Four bounces and eight light candidates remain enabled throughout the RR benchmarks.

## Workload and measurement

Hardware: RTX 5070 Ti, 16 GB. Minecraft 26.2, copied world `caustica-trace-rewrite-20260905`. Camera: player `(82.814538, -57.9375, -106.380471)`, yaw `-93.75026`, pitch `1.5000023`. The camera faces the indoor hall; this is one workload, not a general Minecraft performance guarantee.

`tmp/perf-4k/measure.py` measures advancement of the debug service's rendered-frame counter over wall time. This measures application frame cadence, not the CPU `Frame.elapsedMs` scope, generated frames, or monitor scanout. It records the same JFR Frame, CpuStage, GpuStage, FrameCounter, Exposure, and NeeFrame events for each run. GPU timestamps perturb execution; comparisons use the same instrumentation. Capture/readback is outside timing.

Initial runs warm up 600 frames and measure 600; later branch/tail experiments warm up 1,200 and measure 900. The repeat context run uses a further 60-frame warm-up in the already warm process. World simulation and desktop GPU scheduling continue, so small differences require repeats. Build, Fill, and local NEE are nested within World/trace; do not add them to their parent interval.

## Implemented changes

- `ab849eb6`: write only changed stable-plane members, clear endpoint validity alone, initialize only inactive tail records, and export immutable guides once in Build. Fill keeps its signal accumulators locally instead of loading a complete record across ray tracing. Build's motion/view-Z remain available to the intervening local NEE bake.
- `fceac38c`: reuse a local sample's selected CDF interval for its PDF, avoiding a hash search for the same light. The global/local mixture formula and random calls remain unchanged.
- `b460a98d`: prepare distribution state, tile base/totals, draw counts, and mixture fractions once per shading vertex. The sampling context ends before visibility tracing.
- `cf2bc097`: use the existing mapped GPU-upload allocation policy for world-push and NRD frame inputs. Writes, flushing, alignment, and frame completion ownership remain unchanged. This small allocation change was measured together with the sampling context, not independently.
- `99250c5e`: invalidate unused stable planes with ID/flags stores, guard record loads in the tracer and NRD, and specialize the three-plane branch loop with fixed enqueue indices. Active records keep the same ABI and complete initialization.

The starting high-resolution baseline already includes the device-local retained-input optimization `7c2c99fe`; the older low-resolution results are documented separately in `RETAINED_INPUT_MEMORY_PERFORMANCE.md`.

## Measurements

GPU stage columns are mean milliseconds. The final two stable-plane runs both measure **38.1 FPS**, versus **23.3 FPS** initially: approximately 64% higher cadence, reducing the observed frame interval from 42.9 to 26.2 ms. The 40 FPS target requires 25 ms and is not met by these runs.

| Run | Rendered FPS | Build | Fill | World/trace | DLSS RR |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 23.30 | 8.763 | 26.970 | 36.507 | 3.833 |
| Selective stores | 30.40 | 6.445 | 21.440 | 28.521 | 3.671 |
| Single guide export + local PDF reuse | 34.47 | 7.043 | 15.865 | 23.745 | 4.315 |
| Sampling context + mapped frame inputs | 35.25 | 7.107 | 15.630 | 23.387 | 4.272 |
| Same process, repeated | 37.60 | 6.698 | 14.792 | 22.116 | 3.888 |
| Fixed-index branches alone | 36.18 | 5.729 | 15.176 | 21.630 | 4.267 |
| Inactive-plane stores alone | 35.07 | 6.794 | 15.830 | 23.550 | 4.250 |
| Inactive-plane stores + fixed-index branches | 38.10 | 5.106 | 15.146 | 21.171 | 4.334 |
| Same process, repeated | 38.12 | 5.114 | 15.173 | 21.205 | 4.336 |

The inactive-plane experiment alone did not establish a cadence improvement. Fixed-index scheduling reduced Build time, which was clearer in GPU timestamps than in the first cadence measurement. Their combination produced the repeatable result above. Other desktop work and GPU clocks were not fixed; stage changes and individual patches should not be interpreted as perfectly isolated causal estimates.

A final projected-area arithmetic experiment replaced `dot(normalize(cross(U,V)), -direction) * 4 * length(cross(U,V))` with `4 * dot(cross(U,V), -direction)` in both PDF directions. It measured 37.91 FPS, offering no improvement over the two 38.1 FPS runs, and was reverted. The final shader retains the original arithmetic and random-sampling sequence. The local patch, timing, and numerical comparison are retained under `tmp/perf-4k/area-*` for reference.

## RTXPT source comparison

RTXPT `Rtxpt/Shaders/PathTracer/Lighting/LightSampler.hlsli` returns a sampled light and its probability together. The local-PDF reuse adopts that idea while keeping this renderer's CDF and mixture estimator. RTXPT's complete late-WRS correction is a different estimator and was not mechanically substituted.

RTXPT stores queued stable branches as five packed `uint4` values in its stable-plane UAV (`StablePlanes.hlsli`, `StoreExplorationStart`). This renderer's larger local branch array motivated a separate fixed-index scheduling experiment. No packed ABI change is part of these optimizations. Ordered volume shadow transmission is preserved.

## Nsight evidence

Nsight Graphics 2026.3.1 CLI successfully launches Java with injection, captures GPU Trace Profiler with real-time shader profiling, loads the capture, and exports TSV tables. `--attach-pid` to an ordinary JVM does not work. The CLI terminates its launched client after export.

Local artifacts:

- Baseline: `tmp/perf-4k/nsight-baseline/java_2026_09_06_02_17_49.ngfx-gputrace`.
- Through `fceac38c`: `tmp/perf-4k/nsight-combined/java_2026_09_06_02_25_02.ngfx-gputrace`.
- Final `99250c5e`: `tmp/perf-4k/nsight-final/java_2026_09_06_02_46_31.ngfx-gputrace`.
- Each directory contains `BASE/D3DPERF_EVENTS.xls` and `BASE/GPUTRACE_REGIMES.xls`, which are tab-separated text despite the extension.

The first two captures report Build/Fill durations of 10.335/26.984 ms and 9.427/16.229 ms respectively. These single instrumented captures include overlapping async mesh work and desktop scheduling; use repeated frame measurements for the performance conclusion.

The final capture loaded and exported successfully: Build **6.002 ms**, Fill **16.815 ms**, GPU frame **29.624 ms** under Nsight instrumentation. Its settings snapshot again confirms RR Performance and the original camera. The Nsight-launched Minecraft process was verified stopped after export; its stale debug discovery file was removed.

Top Down and Hotspots were inspected in the GUI. The baseline Top Down reports Fill 30.63% with 127 live registers, Build 13.85% with 126, and closest hit 9.14% with 123. The optimized Hotspots view still puts `stablePlaneStoreRecord` first, followed by visibility and light sampling locations, motivating the invalid-tail experiment. Shader sample shares are attribution within a capture, not elapsed-time speedups. CLI auto-export omits these shader tables; the GUI remains useful for them.

## Correctness checks

Runtime shader compilation succeeded for each measured candidate. Renderer raytracing, runtime, and presentation tests were run for their affected changes. An independent review checked branch termination, dominance ownership, stale-record guards, local PDF equivalence, RNG order, and frame-input ownership.

The final affected-module test run passed **145 tests, zero failures**. NRD/SR Performance was exercised at the same internal/output resolution after the invalid-record loader change, then RR was restored. Its normals, view Z, diffuse/specular signals, depth, and motion are finite. A sky-facing camera exercise produced finite guides, zero sky depth, and finite radiance. The original indoor camera and RR settings were restored afterward. These checks cover the tested views; they do not constitute exhaustive material/volume validation.

The stored-member and single-export candidates have six same-frame upstream buffers captured: normal/roughness, depth, motion, diffuse/specular albedo, and trace radiance. All components are finite. Normals remain unit length within FP16 precision; stationary motion is near zero. Images show the same hall with no obvious new defect. Different frame jitter, lighting samples, and simulation time prevent claiming pixel equivalence.

All measurements, diffs, camera/settings snapshots, JFR paths, and image metadata remain local under `tmp/perf-4k`. Raw captures are excluded from Git.

## Remaining frame budget

The retained configuration spends about 5.1 ms in Build, 15.2 ms in Fill, and 4.3 ms in RR. World/trace plus reconstruction and display is already approximately 25.8 ms; measured cadence is about 26.2 ms. Reaching 40 FPS consistently still needs roughly 1.2 ms per frame in this scene. Compact branch/plane storage, smaller live trace state, and RTXPT's complete light estimator are larger follow-up candidates. The present measurements do not establish their speedup or visual equivalence, and no bounce/candidate reduction was used to meet a frame-rate number.
