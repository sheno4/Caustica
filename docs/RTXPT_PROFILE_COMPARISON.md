# RTXPT comparison — 2026-09-06

Inputs: `run/nsight-profile/20206-9-6-0-22/{top-down,bottom-up,hotspots,live-state}.csv`, the two supplied Nsight screenshots, and RTXPT 1.8.1 source at `f08d1c739071e0faad0c7c274d861124c511abab`. Renderer sources contain pre-existing uncommitted changes. Source snippets in the CSV identify the captured implementation; current source line numbers may differ.

## What the capture establishes

The most actionable shader cost is NEE light sampling and its memory dependencies. These are inclusive shares of all samples in the renderer export; nested rows must not be added together.

| Region | Samples |
| --- | ---: |
| Fill ray generation | 35.19% |
| `traceDirect` inside Fill | 27.31% |
| `sampleRetainedLight` inside Fill | 23.22% |
| `sampleRetainedLightIndex` inside Fill | 15.82% |
| `neeAtProposalPdf` inside Fill | 4.56% |
| Ray traversal | 12.87% |
| Closest hit | 9.41% |
| RT scheduler | 7.68% |
| Build ray generation | 7.50% |
| Any hit | 3.85% |
| NGX | 5.37% |

In the hotspot export, the light-type checks at captured `retained_lights.slang:149` and `:187` account for 6.93% and 5.91% respectively. Over 99.8% of their samples are LGSB. The global PDF numerator load at captured line 61 has 3.48% of samples, 97.6% LGSB. An expensive comparison in Instruction Mix can therefore be a consumer waiting for a load, not expensive integer computation. Nsight explicitly attributes scoreboard stalls to the consumer; inspect the producer and its memory access. [NVIDIA Shader Profiler documentation](https://docs.nvidia.com/nsight-graphics/UserGuide/shader-profiler.html).

These exports do not establish milliseconds, a cross-application speed ratio, cache-miss rates, memory-heap placement, achieved occupancy, or bandwidth saturation. Their instruction-execution counters are zero and some headers/rows have mismatched field counts; do not derive IPC or active-lane measurements from those fields. The named sample columns used above align with their row values. Source-level attribution around inlining and dependent branches is approximate.

The screenshots use different APIs and sample populations: renderer Vulkan, RTXPT D3D12. RTXPT has 27.53% closest-hit samples, 22.81% Fill raygen, 6.72% Build raygen, and 25.02% NGX. A lower closest-hit share in Caustica does not establish cheaper surface shading: Caustica returns a full surface to raygen and performs BSDF/NEE there, while RTXPT places more transport work in its hit-shader implementation. Compare corresponding work, not just shader-stage names. NGX's much larger percentage also changes every other percentage's denominator.

## Concrete implementation differences

1. **Light-memory placement needs verification first.** `RtRetainedSceneBackend.createTraceSlot` creates mapped light and geometry records. `VulkanDeviceContext.createBuffer(..., true, ...)` uses `VMA_MEMORY_USAGE_AUTO` with `HOST_ACCESS_RANDOM_BIT`; it does not request device-local placement. That policy serves CPU-cached random access and can select system memory on a discrete GPU. The captured LGSB pattern is consistent with expensive memory accesses, but does not prove PCIe reads. Query each allocation's memory type/property flags before deciding. If non-device-local, stage uploads into GPU-local records or deliberately select suitable mapped device-local memory; preserve frame ownership and flush/synchronization rules. Do not change readback allocations globally. [VMA usage guidance](https://gpuopen-librariesandsdks.github.io/VulkanMemoryAllocator/html/usage_patterns.html).
2. **More PDF work per candidate.** Caustica calls `neeAtProposalPdf` for every valid candidate, loading global density and possibly searching local density. RTXPT `GenerateLightSample` gets the sampled distribution's PDF directly from `SampleGlobal`/`SampleLocal`; `LATE_WRS_MIS` defers the other distribution's density and correction to the selected, visible sample in `ProcessLightSample`. This is a measured target: the Caustica Fill proposal path alone has 4.56% of samples. Port the full selection/weighting scheme, including BSDF-hit MIS; merely moving the current PDF evaluation after reservoir selection would change the estimator.
3. **Record size.** Caustica's `RetainedLightRecord` is 80 bytes. RTXPT's base `PolymorphicLightInfo` is 32 bytes, with a separate 16-byte extension loaded when shaping is enabled. RTXPT packs directions, scalars, and radiometry. Smaller records can reduce the footprint of scattered candidate loads, but shader instructions/cache metrics are needed to quantify the benefit; record size is not a direct bandwidth multiplier.
4. **Candidate count and local sampling.** Caustica currently fixes eight candidates. RTXPT's UI default is five, subject to scene/preset/runtime overrides; the supplied screenshot does not establish its actual value. RTXPT local sampling directly indexes a fixed proxy list and returns its PDF, while Caustica binary-searches a CDF and separately looks up its hash-table PDF. The local-PDF path is small in this capture, so do not prioritize that search over the measured record/global-PDF stalls.
5. **Raygen state and shadow transport.** Caustica's Fill tree reaches 127 live registers. Its live-state export reports a 708-byte/70-value `OpTraceRayKHR` callsite and an 84-byte/21-value reorder callsite; these are different quantities from allocated registers or proven spill bytes. Shadow traversal uses `WorldPayload` and ordered boundary handling for volume transmission. A narrower shadow payload and shorter live ranges deserve investigation, while preserving that behavior. The RTXPT screenshot's 77 Fill-raygen registers and 124 closest-hit registers are clues, not an occupancy comparison.

## Optimized build for the next capture

Isolated source: `tmp/rtxpt-profile-src`; build tree: `tmp/rtxpt-profile-build`; executable: `tmp/rtxpt-profile-src/bin/Rtxpt.exe`. Build configuration is **Release**, D3D12, Shader Model 6.9, runtime shader optimization `-O3`.

Two profiling-only changes in the isolated RTXPT copy:

- Preserve `--embedPDB` when adding `-m 6_9` in `Rtxpt/CMakeLists.txt`.
- Set `PIPELINE_BAKER_EMBED_PDBS` to 1 in `Rtxpt/SampleCommon/PTPipelineBaker.cpp`, causing runtime path-tracing shaders to use `-Zi -Qembed_debug -O3`.

The unmodified runtime compiler already requests `-Zi` but writes separate PDBs. Missing source in the downloaded binary's capture may therefore be symbol discovery rather than compilation without debug information. Embedding removes that dependency.

Assets are linked through `bin/Assets` to the supplied downloaded 1.8.1 folder. The original downloaded executable and shaders were not changed. Build/configuration logs are `tmp/rtxpt-profile-{configure,build}.log`. Release build succeeded. A generated `TestRaygenPP` DXIL container was checked and includes `ILDB`, `ILDN`, and `DXIL` chunks, confirming embedded runtime shader debug data.

A startup smoke run against `convergence-test.scene.json` generated 16 runtime shader binaries; a second invocation exited with code 0. The requested screenshot was not found, so this is startup/shader-generation evidence, not verified visual output or a performance measurement. Nsight source resolution still needs confirmation in the user's capture.

Launch this executable through the existing Nsight D3D12 project with working directory `tmp/rtxpt-profile-src/bin`. Let scene loading and shader compilation finish before capturing. Export Top-Down, Bottom-Up, Hotspots, RT Live State, and Shader Pipelines if available, plus GPU pass durations from the timeline. Keep shader-pipeline collection enabled.

Record scene/camera, internal and output resolution, DLSS mode, bounces, candidates/full light samples, active stable planes, SER, OMM and ReSTIR settings. Disable frame generation for the comparison. Different scenes remain different workloads even with matching settings; this capture will compare architectural costs, not establish equal-quality throughput by itself.

Prioritize allocation residency, per-candidate PDF traffic, compact light records, then ray state. Validate any implementation change with the same renderer scene/camera/settings and GPU timestamps; shader sample percentages alone are not a speedup measurement.
