# Slow movement and turning: September 13, 2026

Entity upload allocation is reduced substantially. The remaining long native Vulkan calls are unresolved; this checkpoint does not claim hitch-free rendering or a GPU crash fix.

## Reproduction

New World (2), enclosed cave, 3840 × 2054 window, DLSS RR Performance, four bounces. The JVM uses Corretto 25.0.3+9, ZGC and an 8 GiB maximum heap. Comparison recordings leave those settings unchanged.

The debug fixture starts at (-1632.014455, 87, -6503.700000), yaw 45.90098, pitch 15.000088. It applies ordinary forward input with spectator flying speed 0.005 and continuous 24 degrees/second player look input. This is approximately 2.2 blocks/second, below normal walking speed, and does not test fast flying. There is one positioning command before warmup; turns during recording do not use teleport commands. Input is released and flying speed restored afterward.

The initial detailed 90-second recording reproduced a 58.199 ms frame. A 52.967 ms `jdk.ZAllocationStall` on the render thread accounts for most of it; five workers also stalled. Before the associated collection, heap use was 7,396 MiB with 7,950 MiB committed against an 8,192 MiB maximum. The longest GC pause was only 0.0212 ms: the allocation stall, rather than the concurrent collection's entire duration, explains the render delay. The allocation requesting capacity was a capturing function in entity upload preparation, but that location alone does not explain all heap pressure.

Recorded thread-counter deltas in that capture imply approximately 571 MB/sec across observed JVM threads. Entity upload workers account for approximately 191 MB/sec, scene preparation 160 MB/sec, and the render thread 76 MB/sec. Weighted allocation samples identify primitive packing's temporary arrays, vectors, records and buffer slices as a major source. First allocation samples per thread can include bytes predating recording and must be excluded from stack attribution.

## Implementation

- Entity primitive packing writes indexed UV/color values, material descriptors, emission and tangent data directly into the mapped upload buffer. It avoids the per-triangle record object graph and buffer slice.
- Shader-record generation exposes reflected field offsets, array strides and padding clearing, keeping the direct writer tied to the shader ABI.
- Material and texture capture resolve only distinct keys without allocating a capturing function for each triangle. Texture ownership and failure cleanup are unchanged.
- The debug framework supports continuous player look input for reproducible movement tests.

Published mesh arrays remain immutable, and upload jobs retain their existing lifetime contracts. No animation rate, geometry detail, heap size, terrain budget or presentation mode is changed. A separate packet-budget experiment was set aside and is not included.

## Three-minute comparison

Both runs use the same slow input route and lighter JFR event selection: HostLoop, HostWork, HostCallbackTotals, HostSubmission, Frame, FramePreparation, GpuStage, GpuWait, FrameCounter, TerrainJob and TerrainPublication, without per-stage CpuStage events. Both start after 300 rendered warmup frames; terrain catch-up is still active at recording start. Every completed cadence interval is retained in the statistics below.

| Metric | Baseline | Allocation fix |
| --- | ---: | ---: |
| Host cadence samples | 11,302 | 11,341 |
| Mean cadence | 15.948 ms | 15.894 ms |
| P99 cadence | 17.228 ms | 17.182 ms |
| Maximum cadence | 91.293 ms | 108.459 ms |
| Frames over 50 ms | 17 | 19 |
| Mod host-inclusive time bound, P99 | 4.390 ms | 4.033 ms |
| Observed mod allocation per host window, mean | 909,949 bytes | 729,712 bytes |
| Render thread allocation | 70.421 MB/sec | 59.379 MB/sec |
| Entity upload workers, combined allocation | 167.832 MB/sec | 34.359 MB/sec |
| Sum of observed thread allocation rates | 720.741 MB/sec | 596.182 MB/sec |
| ZGC allocation-stall events | 0 | 0 |

Entity upload allocation falls about 79.5%, and observed mod allocation per host window falls about 19.8%. The host bound includes covered work, callback totals and host submission CPU segments, with overlap conservatively widening it; it is neither exact mod-exclusive CPU time nor scanout latency. All analyzed interior windows have complete callback and submission coverage, with no allocation gaps. Native blocking inside covered scopes can inflate that bound.

The live simulation, terrain arrival, entity populations, JIT state and history are not deterministic across launches. Exact thread-counter deltas support allocation-rate comparisons; these runs alone do not establish a reduction in rare hitch frequency. Neither lighter run reproduces the original allocation stall. Large native hitches remain, and their maxima do not improve.

## Remaining native hitches

All 17 baseline frames above 50 ms occur within the first 16 seconds, alongside heavy initial terrain dispatch. Most have 36–76 ms after final host submission; several have render samples inside `vkQueuePresentKHR`. Two instead have approximately 85 ms inside composite, including a sample in `vkBeginCommandBuffer`. The following approximately 164 seconds contain no frames above 50 ms.

The candidate also has early streaming hitches and a later 105.704 ms frame, frame 5553. Its composite scope lasts 103.000 ms with only 22,960 allocated bytes. Render and compute samples both enter native `vkResetCommandPool`; no GC/VM interval overlaps the frame, and no allocation stall occurs. TLAS preparation waits approximately 103 ms, while TLAS packing takes 0.119 ms. The final submission wait is 0.002 ms and the presentation tail 0.333 ms. Timed world/DLSS/post/UI GPU spans total approximately 15.088 ms and omit asynchronous compute and native driver delays.

These observations localize native blocking, but do not establish the driver's internal dependency. The command-pool reset occurs outside the cache's Java return lock. Replacing lifetime synchronization or changing pool reuse without identifying the dependency would not be an evidence-based fix.

Nsight Systems UI launch timed out waiting for computer-use app approval. A Vulkan-only CLI attempt explicitly disabled CPU sampling, CPU context switches and GPU metrics, and failed before launching the game: “Failed to register Vulkan extension JSON file. This operation requires registry writing permissions.” No Windows UAC/admin run or registry modification was attempted. No Nsight capture was produced by that attempt.

## Validation and local evidence

56 focused tests pass: 39 entity tests, six reflected shader ABI tests, five debug-recording tests and six host-telemetry tests. The new binary parity test covers indexed vertices, textures and missing bindings, colored triangles, emission, degenerate UVs, complete padding bytes and untouched trailing storage. Source review found no concrete ABI or ownership regression. A screenshot was captured after measurement for inspection. Both measured clients were closed normally.

Raw evidence is intentionally local and ignored under `tmp/frame-spikes/`:

- `smooth-move-baseline/`, `allocation-pressure-audit.md`, `allocation-pressure-support.json`.
- `allocation-baseline-light/` and `allocation-fix-light/`: JFR, full exports, cadence, host metrics and allocation counters/rates.
- `baseline-light-audit.md`, `baseline-light-support.json`, `candidate-frame-5553-audit.md`, `candidate-frame-5553-support.json`.
- `allocation-baseline/` and `allocation-fix/`: frozen classpath dependencies and hashed launch manifests. The earlier baseline's NGX jar had been rebuilt before the fresh baseline was frozen; that drift is recorded in its manifest. The comparisons above use the fresh frozen baseline.
- `nsys/runs/normal-20260913-165045-907/`: failed non-admin Vulkan-only capture arguments and output.

The standalone helpers retain original results and separate diagnostic captures from performance claims. Exported multi-gigabyte JSON is offline analysis output, not heap retained by the measured game.
