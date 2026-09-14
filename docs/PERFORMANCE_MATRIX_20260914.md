# Jungle performance investigation

The target is at least 50 FPS in most representative scenes with default settings and fog enabled. Work proceeds through block-light fog validation, rendering and streaming optimization with fog disabled, then fog optimization. GPU attribution uses Nsight Graphics through computer use; CPU attribution uses JFR. The separate glass request follows performance work.

## Evidence and current limits

Checkpoint `f9afcfb0` contains the preceding glass and contiguous fog-storage work. The next retained-light fog changes are not yet visually validated.

The last opened world is `caustica-glass-20260914`, a copy of New World (2). The recovered jungle position is approximately `(-2097.274, 125, -5657.979)`. Exact initial and pre-profiler camera states are retained in `tmp/jungle-initial.json` and `tmp/fog-local/pre-nsight-status.json`.

The completed 300-loop 4K recording is `run/caustica-debug/recording-435e88fb-1fac-4ff4-9e02-776caae981cd.jfr`. Conditions, raw events, and summaries are in `tmp/fog-local/jungle-baseline*`. HostLoop elapsed time averages 209.199 ms, with p99 706.303 ms and maximum 825.675 ms. Render-thread CPU time averages 10.278 ms, using a coarse platform CPU clock. This captures severe degradation but does not capture the subsequently reported sub-1-FPS interval. Terrain work was still active, so it is not a settled-scene throughput benchmark.

A later read-only device snapshot reports 15,673 MiB used of 16,303 MiB, 99% GPU utilization, 2,970 MHz graphics clock, and 127.26 W power. Memory pressure is a hypothesis requiring residency and execution evidence, not an established allocation leak or shader diagnosis.

The CPU sample export contains 556 render-thread samples, of which 362 have `vkWaitSemaphores` under Minecraft's `VulkanCommandEncoder.awaitSubmitCompletion` at the top of the stack. The maximum recorded GC pause is 0.0271 ms. These observations support GPU-completion blocking as the dominant sampled render-thread behavior; they do not identify the GPU dependency or the cause of slow execution. The full export and extracted stacks are `tmp/fog-local/jungle-baseline-jvm.json` and `cpu-attribution.json`.

The initial Nsight UI launch is waiting at an interfering-process prompt. Computer use could not activate its nested modal after refreshing window selection; the user was asked to choose Keep. No new GPU capture has been completed. Subsequent local builds include the retained-light fog changes, so the queued launch must be recorded as a candidate build, not the checkpoint baseline.

Update: computer use recovered by selecting the nested prompt's returned window and its Keep accessibility element. The first launch overlapped JAR replacement and failed class loading. A second launch exposed a stale `renderer-runtime` JAR and failed with `AbstractMethodError`; its `java_2026_09_14_21_56_18` capture is excluded. Explicit runtime JAR tasks completed successfully before the third launch (PID 45880), which rendered the retained-light fog candidate without that error. Do not rebuild runtime artifacts while a launched client uses them.

The third launch produced `tmp/fog-local/jungle-candidate-4k.ngfx-gputrace`, copied from Nsight's `java_2026_09_14_22_03_21` report. Nsight was launched and inspected through computer use. UI capture attempts did not change capture readiness; the injected SDK then acknowledged tracing at renderer frame 7802 (`nsight-trigger.json`). The overlay confirmed Tracing and nonzero VRAM demotions. The capture configuration reserves a 4,000 MiB sampling buffer, so profiler-induced residency pressure must be separated from the original non-profiled slowdown. Nsight reported exhausted hardware-event resources and merged periodic samples. This exploratory capture is not an accepted performance comparison; inspect it for broad attribution and repeat with lower profiler memory overhead.

The reported leaf/vine-edge fog flashing requires comparison of primary depth, reconstructed color, fog transmittance, and fog scattering. The current post-reconstruction composite samples jittered primary depth; inconsistent foreground/background selection is a hypothesis. Final color alone cannot establish it.

## Workloads

### Allocation investigation and compact material checkpoint

The exploratory Nsight trace reports 1.89 GiB demoted VRAM. Its frame duration is 2861.65 ms, including 2521.59 ms in RT scene effects. Shader hotspots concentrate on fog global-memory accesses. The 4 GiB profiler sampling reservation and event-loss warnings prevent using this as a clean timing comparison.

Ordinary candidate recordings (`tmp/fog-local/candidate-4k-*`) observed mean host-loop times of 26.44 ms with fog off, 81.24 ms on, and 48.83 ms off again. They were not stationary: the world continued loading. An allocation snapshot subsequently showed 5480.92 MiB across 91316 terrain upload allocations, all in the device-local host-visible memory type. Fog rays/results occupied 63.28 MiB, and the spatial field 0.22 MiB in that same type. This rules out a simple assumption that fog's mapped buffers necessarily reside in host-only memory.

The material record now stores a relative offset to optional full-precision vertex colors. Zero means uniform white, as used by terrain. Records shrink from 144 to 96 bytes; entity color payloads remain lossless. Packing, shader compilation, reflected ABI and generation checks passed. The running jungle screenshot is retained at `tmp/fog-compact/jungle.jpg`.

The compact candidate's first off/on/off recordings averaged 17.71/28.34/17.92 ms, with p99 19.22/30.80/19.33 ms. These are provisional, not accepted settled comparisons: JFR shows loaded columns and resident geometry still increasing despite repeatedly observing zero outstanding builds. The later default-fog interval averaged 230.18 ms, p99 258.07 ms. Renderer allocation bytes grew from 10.99 to 11.24 billion and reserved block bytes from 11.64 to 12.25 billion between snapshots. Allocation inventory and JFR files are in `tmp/fog-compact`. This work has not resolved the memory-pressure threshold or met the broad 50 FPS goal.

Subsequent warmup must establish stable loaded-column and resident-geometry counts, not merely zero active workers. Compare equivalent scene populations and keep resize/settings-transition measurements separate. `memory.capture` provides renderer VMA allocation evidence; it excludes Minecraft, NGX and profiler allocations.

Each row needs a fixed camera interval, a repeatable movement interval, and a return-to-rest interval. Record exact poses, world copy, revision, actual loaded build, settings, dimensions, warmup state, event selection, and profiler configuration. A frame wait is not proof that terrain has settled.

| Scene | Stationary workload | Moving workload | Purpose |
| --- | --- | --- | --- |
| Jungle | Recovered canopy view and view through leaves/vines | Straight and turning flight at maximum spectator speed | Streaming stalls, cutout traversal, foliage-edge fog stability |
| Open outdoor | Long terrain and sky view | Fixed straight route across chunk boundaries | Base rendering throughput and visibility distance |
| Indoor | Enclosed emissive room | Walk and turn through doorway | Local lighting and disocclusion |
| Cave | Dark passage with emissive blocks | Enter and leave illuminated area | Light selection, fog falloff and occlusion |
| Glass dome | Interior and oblique exterior views | Orbit and traverse boundary | Reflection/refraction path cost and stable planes |
| Underwater | Shallow and deep viewpoints | Cross water surface and swim | Medium transitions and transmission |
| Nether | Emissive terrain with enclosed and open views | Fixed high-speed route | Dense local lights and chunk preparation |

Construct fixtures only in disposable copies, away from the user's active area. Use actual emissive blocks and material-aware shadow transport for block-light fog validation. Compare lights present/absent and visible/occluded while preserving camera, density, exposure interpretation, and other conditions.

## Measurement and acceptance

Run ordinary timing intervals separately from captures, offline JFR exports, compilation, and Nsight startup. Use JFR execution samples and explicit CPU stages for CPU attribution. Use Nsight's timeline, memory activity, and shader metrics for GPU attribution. Do not sum nested stages or equate a queue timestamp interval with hardware utilization.

Compare matching settings against an isolated `main` build. Treat RTXPT as a separate reference workload; different scenes and transport settings cannot establish an equal-work performance comparison. Preserve image quality and report any configuration differences.

For each accepted interval, report host cadence distribution and counts above 20, 33.3, 50, 100, and 1,000 ms, along with terrain readiness/publication delays. Report settled throughput separately from streaming behavior. A mean above 50 FPS alone does not satisfy the spike goal.

If shader-level improvements do not materially close the gap, investigate retained geometry/light storage, resource residency, scene revision lifetime, and pass architecture. Prepared revisions must remain atomic and GPU resources must remain alive until their last submitted use completes.
