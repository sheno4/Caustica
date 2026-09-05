# Indoor performance pass — 2026-09-05

Baseline: commit `2b81cbf`, with the same additional per-pass GPU timestamps used for the optimized recordings. The workload is the enclosed room in `caustica-trace-rewrite-20260905`: player position `(82.814538, -57.9375, -106.380471)`, yaw `-93.75026`, pitch `1.5000023`. Render resolution is 427x240, display 854x480, DLSS-RR, four bounces, eight NEE candidates, approximately 4,787 retained lights. Hardware: Ryzen 9 9950X and RTX 5070 Ti.

The camera and settings were restored explicitly after restarts. Each controlled run warmed up for at least 1,200 frames after teleporting, then recorded roughly 1,200 frames. Screenshots and EXR readbacks happened outside timing intervals. One earlier pass-level recording (`before-passes`) used a different spawn location and is excluded.

## Measured results

Milliseconds, arithmetic means. Two final optimized runs are reported separately to expose variability.

| Scope | Baseline | Final run 1 | Final run 2 |
| --- | ---: | ---: | ---: |
| CPU frame envelope | 13.916 | 11.554 | 10.874 |
| CPU trace preparation | 2.423 | 1.862 | 1.836 |
| GPU world resources and trace | 2.269 | 2.197 | 2.225 |
| GPU BuildStablePlanes | 0.478 | 0.475 | 0.460 |
| GPU FillStablePlanes | 1.571 | 1.502 | 1.552 |
| GPU local NEE bake | 0.010 | 0.010 | 0.010 |

The CPU frame envelope fell 17–22%, and trace preparation fell 23–24%. Two intermediate optimized runs also measured lower CPU frame times, at 10.933 and 11.351 ms. GPU differences are small relative to run variability; these measurements do not establish a substantial GPU speedup. The GPU parent includes the named trace passes; do not sum parent and child intervals. CPU envelope time is not display cadence or generated-frame FPS.

## Changes

- Terrain dispatch iterates current pending requests rather than every resident section. Superseding groups removes their old requests from the pending index; dispatched/completed requests leave it; synchronous submission failure requeues its request. Group publication semantics remain intact.
- Emitter packing resolves a retained light identity once per intersecting primitive range, then writes its dense index over that range. Gaps and removed lights keep `-1`.
- Opaque shadow hits return before surface reconstruction, texture sampling, and BSDF closure construction. Coverage has already accepted the hit; ordered volume traversal retains its material/medium evaluation.
- NEE proposal evaluation returns the global PDF immediately when no local candidates are drawn. Later incoherent bounces avoid an unused local hash-table search. Candidate count, random draws, and shared forward/reverse PDF semantics stay the same.
- Build, local bake, and Fill now have opt-in JFR GPU timestamps within the existing command buffer. Pools are recycled after the graphics use completes, with no added telemetry wait.

The initial JFR had terrain dispatch at 191 of 1,241 render-thread execution-sample leaves. After the pending-request change it disappeared from the leading samples. Trace preparation also improved. Remaining sampled costs include geometry-record planning, scene/resource snapshot capture, and TLAS instance preparation; replacing per-frame reconstruction with retained revision-based data is the next CPU area to investigate.

## Nsight CLI results

Nsight Graphics 2026.2 captured two GPU reports. Both automatic metric exports failed in its report loader with `An SEH exception was thrown while loading the report`. The second capture disabled hardware-event collection and reduced the duration/frame count, but had the same failure. Reports remain local for inspection:

- `tmp/perf-indoor/nsight-before/java_2026_09_05_21_20_48.ngfx-gputrace`
- `tmp/perf-indoor/nsight-basic/java_2026_09_05_21_23_57.ngfx-gputrace`

Nsight Systems 2026.3.1 could not register its Vulkan extension JSON because registry-write permissions were unavailable. Those CLI attempts provided no usable Nsight metric export. GPU numbers above come from completed Vulkan timestamp queries exported through JFR. Logs are `tmp/perf-indoor/ngfx-launch.log`, `ngfx-basic.log`, and `nsys-before.log`.

## Nsight GUI profile

The existing `mc` project successfully launched GPU Trace Profiler through the GUI, using the same Java client directly in place of its PowerShell launcher. The indoor camera and renderer settings were restored and warmed for 1,200 frames before manually collecting one frame with the project's shader profiling settings. The new report opened successfully, and Trace Analysis exported successfully to YAML.

- Report: `tmp/perf-indoor/java_2026_09_05_21_58_06.ngfx-gputrace` (also saved in the `mc` Nsight project).
- Analysis: `tmp/perf-indoor/nsight-gui-analysis.yaml`.
- Camera verification: `run/screenshots/caustica-debug-0d7c18da-d22c-44c2-b65b-3d2779358228.png`.

The captured ranges were Build 0.53 ms, local NEE bake approximately 0.01 ms, Fill 1.76 ms, and frame TLAS build 0.15 ms. Fill remains the dominant trace pass. The 22.71 ms frame interval includes other desktop GPU contexts and gaps, so this single instrumented capture is hotspot evidence, not a replacement for the controlled before/after measurements above.

| Nsight metric | Build | Fill |
| --- | ---: | ---: |
| L1TEX hit rate | 77.40% | 52.78% |
| L2 hit rate | 85.89% | 76.87% |
| Long-scoreboard L1 stall metric | 6.57% | 14.09% |
| Local/global instruction-queue throttle metric | 20.90% | 0.98% |
| VRAM throughput metric | 35.91% | 37.11% |

These percentages retain Nsight's metric definitions; stall percentages are not percentages of frame time. Trace Analysis flags VRAM traffic and L1TEX dependency latency. Fill's lower cache hit rates and higher long-scoreboard metric support investigating dependent buffer/texture reads and working-set size. Build's local/global queue throttle supports investigating its buffer traffic. Neither result establishes a specific source-line cause or a measured gain from a further optimization. Other GPU contexts were active, which also limits attribution of shared memory-system counters. Nsight's estimated speedup factors are not measured improvements.

## Verification and local artifacts

The renderer-raytracing checks, renderer-runtime tests, and selected Minecraft terrain tests passed: 152 tests total. Shader compilation, SPIR-V validation, ABI checks, and resource packaging passed. Added tests cover pending dispatch/retry/supersession/removal and emitter-range clipping/missing-light behavior.

Raw, NRD, and DLSS-RR color/normal/signal captures had zero nonfinite components. The final 1,210-frame recording kept local NEE history enabled throughout. The client remains running in the enclosed room with the original settings restored.

Controlled measurements and exported events live under `tmp/perf-indoor`:

- `baseline-indoor.json` / `baseline-indoor-analysis.json`
- `final-gpu.json` / `final-gpu-analysis.json`
- `final-repeat.json` / `final-repeat-analysis.json`
- `final-routes.json`

Each measurement JSON records settings, camera, working-tree state, and its JFR path. The `*-events.json` files retain raw events. Use `tools/debug/measure.py` and `tools/debug/analyze_recording.py` to reproduce measurements; see `DEBUGGING.md` for event semantics.
