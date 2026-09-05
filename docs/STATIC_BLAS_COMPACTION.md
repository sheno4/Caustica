# Static BLAS compaction

## Implementation

`MeshBuild.BuildPolicy` explicitly selects `STATIC` or `REFITTABLE`. Terrain, immutable glTF primitives, and the API showcase use static builds. Minecraft entity geometry retains out-of-place refits. Instance transform changes do not require changing this policy.

Static BLAS builds use `PREFER_FAST_TRACE`, `ALLOW_COMPACTION`, and `ALLOW_DATA_ACCESS` for ray tracing position fetch. After the build completes, the renderer reads its compacted-size query and submits a compacting copy when the result is smaller. The ready mesh future publishes only after that copy completes. Input ownership and the original BLAS survive until completion; cancellation releases both generations after their GPU work terminates. Identical static revisions can share a ready BLAS, while changed static geometry rebuilds.

RTXPT uses the same static/deforming distinction in `Rtxpt/SampleCommon/AccelerationStructureUtil.h`: static meshes allow compaction and skinned meshes allow updates. `Sample.cpp` calls `compactBottomLevelAccelStructs()` for completed builds.

The optional JFR event `dev.comfyfluffy.caustica.BlasCompaction` records mesh identity, original build bytes, and retained bytes after successful preparation. Enable it explicitly in a JFR configuration when measuring startup builds. Summed events describe completed builds, not a live allocation census.

## Measurement conditions

Measured on 2026-09-06 with RTX 5070 Ti, driver 616.56, at 3840 x 2054 output and 1920 x 1027 internal resolution. DLSS RR Performance, four bounces, eight NEE candidates, frame generation off. Camera: `(82.814538, -57.9375, -106.380471)`, yaw `-93.75026`, pitch `1.5000023`. Shader binaries are unchanged from baseline `b4474d5e`.

Each short benchmark advances approximately 1,200 frames after warmup. FPS is application frame count divided by elapsed wall time. GPU values are timestamp durations; parent world stages include Build and Fill. Desktop load and GPU clocks were not locked.

The fresh baseline was already above 40 FPS. These results do not establish a frame-rate improvement from compaction or attribute the difference from the earlier 38.1 FPS session to this change.

| Run | FPS | Build mean ms | Fill mean ms | World/trace mean ms | RR mean ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Fresh baseline | 42.392 | 4.603 | 14.153 | 19.390 | 3.632 |
| Compacted | 42.434 | 4.574 | 13.987 | 19.316 | 3.641 |
| Compacted, restarted | 40.865 | 4.581 | 14.084 | 19.295 | 3.634 |
| Compacted, after streaming, 5,005 frames | 42.324 | 4.595 | 14.092 | 19.381 | 3.652 |

The longer interval lasted 118.25 seconds. The restarted client also survived the preceding benchmark, image captures, and streaming exercise, for approximately five minutes of active rendering before an explicit stop. There was no further device loss in that run. Implementation checkpoint: `2e3e6278`.

## Nsight capture

Nsight Graphics 2026.3.1 CLI captured GPU Trace Profiler with real-time shader profiling and exported both reports successfully:

- Baseline: `tmp/perf-compaction/nsight-baseline/java_2026_09_06_02_57_02.ngfx-gputrace`.
- Compacted: `tmp/perf-compaction/nsight-compacted/java_2026_09_06_03_13_23.ngfx-gputrace`.

Both captures contain 3,586 TLAS instances. Baseline/compacted Build durations are 4.664/4.669 ms; Fill durations are 14.883/14.579 ms. The compacted instrumented GPU frame is 25.340 ms. Single captured frames include scheduling and instrumentation effects; the repeated wall-time measurements above are the performance conclusion. The CLI terminated its client after export, and no development Minecraft process remained.

## Memory result

The startup recording contains 5,621 completed static builds. Their requested BLAS storage totals fall from **469.846 MiB to 127.564 MiB**, a **72.85% reduction**. Every recorded build obtained a smaller compacted result. This is cumulative build storage, including revisions that can retire; it is not a claim that simultaneously resident GPU memory fell by 342 MiB.

## Validation and limits

Affected API, engine, raytracing, runtime, Minecraft rendering, and client tests passed: **453 tests, zero failures**. The tests cover static/refittable reuse rules, Vulkan build flags, publication ordering, cancellation, failed copies, and destination allocation failure. Module and example jars were rebuilt for runtime verification.

Baseline and compacted captures contain finite normal/roughness, depth, motion, diffuse/specular albedo, and trace radiance. Their screenshots show the same hall without an obvious new defect. Frame jitter, stochastic lighting, and simulation changes prevent claiming pixel equivalence. Streaming was exercised by moving 512 blocks in each direction, then restoring the camera.

One initial compacted run lost the Vulkan device after completing its benchmark. The diagnostic reports a null GPU write, without identifying a compaction operation or faulting BLAS. Memory usage was well below budget. Native synchronization and ownership review found no concrete defect, but the cause remains unresolved; successful later runs do not explain that failure. Both baseline and compacted clients have an independent shutdown exception, `Too many views removed from texture`; the processes still exit.

Raw artifacts remain local in `tmp/perf-compaction`, `tmp/perf-4k/compaction-baseline*`, `tmp/perf-4k/compacted*`, and `run/caustica-debug`. The failing run is preserved in `tmp/perf-compaction/compacted-launch.log` and `run/crash-reports/crash-2026-09-06_03.02.19-client.txt`.
