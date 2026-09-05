# Retained input memory — 2026-09-06

Baseline checkpoint: `1937f195`. Optimization checkpoint: `7c2c99fe`.

## Change and confirmed cause

Retained light records and NEE state were allocated in memory type 3 with property flags `0xE`: host-visible, coherent, and cached, without device-local residency. A short diagnostic queried `vmaGetAllocationMemoryProperties` and `VmaAllocationInfo.memoryType()` on the actual allocations. These were not inferred from the shader stalls.

The new `VulkanDeviceContext.createMappedGpuUploadBuffer` selects `VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE` and `VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT`, keeping persistent mapping. It is used for retained geometry, light records, primitive-light indices, aligned hit SBT, NEE state, and the NEE identity plan. These consumers write from the CPU and read from the GPU. CPU readback allocation policy, alignment, flushes, reuse waits, and frame ownership are unchanged.

On the RTX 5070 Ti, the new allocations select type 4 with flags `0x7`: device-local, host-visible, coherent. This is a preference policy, not a guarantee that every device has the same memory types. No device-only fallback is enabled: these callers require a mapped pointer.

The temporary diagnostic was removed before timing. Evidence is saved under `tmp/perf-rtxpt/residency-{baseline,optimized}-summary.txt`, with full diagnostic logs beside them. No shader or sampling-quality setting changed.

## Controlled timing

Minecraft 26.2, RTX 5070 Ti, copied indoor world `caustica-trace-rewrite-20260905`; position `(82.814538, -57.9375, -106.380471)`, yaw `-93.75026`, pitch `1.5000023`. Internal resolution 427x240, output 854x480, DLSS-RR, four bounces, eight NEE candidates. Each launch restores the saved camera/settings, waits 1,200 frames, then each recording waits another 300 frames and records 1,200 frames. JFR captures GPU timestamps and the same CPU/frame events in each run. Screenshot readback happens outside timing.

Times are milliseconds. World resources and trace includes the individual Build, Fill, and local NEE intervals; do not sum them with their parent. The CPU frame envelope is not display FPS.

| Run | Fill mean | Fill median | Fill p95 | World/trace mean | World/trace median | CPU frame mean |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | 1.505 | 1.423 | 1.905 | 2.191 | 2.094 | 8.588 |
| Optimized 1 | 1.473 | 1.325 | 2.402 | 2.147 | 1.990 | 8.525 |
| Optimized 2 | 1.462 | 1.321 | 2.398 | 2.137 | 1.988 | 8.249 |
| Baseline policy repeated afterward | 1.604 | 1.421 | 2.554 | 2.283 | 2.097 | 9.264 |

The original allocation policy was restored temporarily after the optimized runs, rebuilt into the runtime JAR, and measured again. Fill's median returned to 1.421 ms and world/trace to 2.097 ms, closely matching the first baseline. The committed optimized policy was then restored and rebuilt.

Across this reversal, optimized median Fill is about 7% lower and median world/trace about 5% lower. Means and p95 vary substantially between runs; the optimized tails are higher than the first baseline but lower than the repeated baseline. This does not demonstrate a frame-rate gain or explain the entire RTXPT performance gap. Concurrent desktop GPU work was not controlled; these distributions include scheduling effects. Baseline and optimized screenshots show the same room/camera without an obvious new visual defect, but are not a pixel-equivalence test.

## Nsight CLI capture

The installed version is Nsight Graphics **2026.3.1**, build 38722833. Attaching to a JVM launched without Nsight failed with `Cannot find process`. Launching Java directly through `ngfx.exe` with injection succeeded, and **both captures loaded and exported successfully**. The 2026.2 report-loader failure in `NSIGHT_DIAGNOSTICS.md` did not recur. The CLI terminates its launched client after export.

The existing `mc - NVIDIA Nsight Graphics` window was also inspected with Computer Use. Successful CLI collection made GUI capture unnecessary.

| Single-frame Nsight measurement | Baseline | Optimized |
| --- | ---: | ---: |
| Build stable planes (ms) | 0.535 | 0.531 |
| Fill stable planes (ms) | 1.925 | 1.343 |
| TLAS, 3,586 instances (ms) | 0.150 | 0.152 |
| Fill `syslts__t_sectors_aperture_sysmem_realtime.sum` | 92,179,400 | 5,756,100 |

The reported Fill system-memory sector counter falls about 94%. Treat this as Nsight's sampled/estimated counter, not an exact byte count. The single captured Fill duration falls about 30%; it should not replace the repeated unprofiled timing distributions above. Nested `RT trace rays` exports duplicate the parent interval and must not be counted twice.

Artifacts under `tmp/perf-rtxpt`:

- `nsight-baseline/java_2026_09_06_01_09_28.ngfx-gputrace`
- `nsight-optimized/java_2026_09_06_01_18_07.ngfx-gputrace`
- Each capture directory's `BASE/D3DPERF_EVENTS.xls` and `BASE/GPUTRACE_REGIMES.xls` (tab-separated text despite the extension).
- `baseline.json`, `optimized-1.json`, `optimized-2.json`, `baseline-repeat.json`, corresponding `*-analysis.json` and `*-events.json`; recording paths and full settings are in the run JSON files.
- `upload-tests.log`: 131 tests passed across `:packages:engine-vulkan:test` and `:packages:renderer-raytracing:test`.

## Remaining work suggested by evidence

Per-candidate proposal PDF evaluation and scattered light-record loads remain shader targets. RTXPT's late distribution correction requires porting the complete reservoir/MIS estimator; it is not a safe mechanical hoist. Compact light records also require encoding/precision validation. Small world-push/NRD input buffers still use the general allocation policy, but their cost was not isolated. Descriptor heaps merit a separate audit of native descriptor encoding before changing their host-access declaration.
