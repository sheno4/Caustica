# New World (2) flight profiling — September 14, 2026

Fog transport/history storage now uses contiguous depth planes. At unchanged quality, this reduced the measured fog GPU cost by approximately 47–49%. Intermittent native presentation stalls remain; this change does not eliminate every FPS spike.

## Workload and evidence

Baseline checkout: `4fdba3610593726082e16ce6171e71240ac8ac08`, with existing local edits retained. Development client, Corretto 25, ZGC, 8 GiB heap, 32-section render distance, 3840 × 2160 framebuffer, DLSS RR Performance, four bounces, frame generation and Reflex disabled. Existing fog settings were enabled, density 4, divisor 4, and 64 samples. Settings were not reduced for the optimization.

Both routes start at `(-1632.065859, 87, -6503.652155)`, yaw `38.972904`, pitch `19.800062`, after 300 rendered warmup frames. Straight flight uses spectator speed 0.2 with sprint. Turning uses speed 0.1, sprint and 35 degrees/second ordinary look input. Each interval lasts 600 client ticks. Keys and speed are restored afterward; positioning and image captures occur outside recordings. World time, entities and streamed workload are live, so matching commanded routes are not deterministic replays.

Local evidence is under `tmp/flight-spikes/`: `clean-manifest.json`, `candidate-manifest.json`, `baseline-repeat-manifest.json`, complete `*-events.json` and `*-jvm.json` exports, `report.json`, launch logs, and baseline/candidate shader snapshots. `flight-spikes-clean.py` and `flight-spikes-report.py` in `tmp/` retain the experiment and analysis. JFR files remain under `run/caustica-debug/` at the paths in each manifest. No offline exports or Nsight activity overlap these six accepted flight intervals.

The exploratory turning recording overlaps offline JFR export and is excluded from acceptance. It contains 153–155 ms native presentation stalls; those are not used as the clean baseline. The failed first candidate setup encountered a newly launched development player's non-spectator mode before flight; its recording is also excluded.

## Results

All completed samples in the accepted intervals are retained. GPU times are queue timestamp intervals; Frame time is the CPU frame envelope. Host cadence is the interval between consecutive HostLoop starts, not physical scanout or generated-frame FPS. Nested GPU scopes must not be summed twice.

| Route/build | Frames | Fog mean / p99 / max (ms) | CPU Frame mean / p99 / max (ms) | Frames >50 ms |
| --- | ---: | --- | --- | ---: |
| Straight, original baseline | 1,194 | 8.430 / 10.763 / 11.091 | 24.159 / 31.657 / 43.011 | 0 |
| Straight, contiguous planes | 1,406 | 4.334 / 6.189 / 6.456 | 20.174 / 28.004 / 50.547 | 1 |
| Straight, repeated baseline | 1,237 | 8.251 / 10.372 / 10.682 | 23.613 / 31.277 / 58.365 | 2 |
| Turning, original baseline | 989 | 8.399 / 9.199 / 9.312 | 29.910 / 38.934 / 66.895 | 9 |
| Turning, contiguous planes | 1,166 | 4.393 / 5.143 / 5.458 | 25.154 / 28.952 / 63.993 | 8 |
| Turning, repeated baseline | 1,010 | 8.260 / 8.983 / 9.221 | 29.386 / 45.185 / 63.862 | 10 |

The repeated baseline and candidate have the same event selection, including HostLoop, HostWork, HostSubmission and HostCallbackTotals. Their mean host cadence improves from 24.512 to 21.502 ms in straight flight and from 30.088 to 26.027 ms while turning. Turning cadence p99 improves from 46.103 to 30.293 ms, while the maxima remain 64.283 and 64.651 ms. The recordings support a throughput gain, not a reliable reduction in rare native hitch frequency.

## Causes and remaining limits

The exploratory straight-flight 51.254 ms frame has 46.875 ms of render-thread CPU time. Execution samples in that interval show chunk packet installation (`ChunkSkyLightSources.fillFrom` / `LevelChunk.replaceWithPacketData`) and skylight propagation (`SkyLightEngine.checkNode` / `LightEngine.runLightUpdates`). This is Minecraft work outside the renderer's small terrain snapshot/dispatch scopes. A concurrent ZGC collection is not itself a stop-the-world pause: the observed GC pause at that time is approximately 0.013 ms.

Clean baseline and candidate hitches also contain render-thread native samples inside `vkQueuePresentKHR`. For example, the candidate's 63.993 ms turning frame has zero CPU time at the platform counter's coarse resolution and a native presentation sample. Another 63.624 ms frame includes section snapshot work and a presentation sample. GPU trace scopes remain much shorter. These samples localize blocking to the host/driver presentation path but do not identify its internal dependency; they do not justify changing GPU lifetime synchronization. Some long intervals lack enough samples for an exact attribution.

The sustained fog cost has a concrete memory-layout cause supported by the repeated shader comparison. Previously, adjacent shader invocations accessed transport values 65 float4s apart and visibility-history values 64 float4s apart. Depth-plane storage places neighboring pixels next to each other. Both producers and consumers now use that layout, including density staging, prefix reconstruction and temporal reprojection. Allocations, history header, visibility rays, integration arithmetic, temporal filtering, sample counts and resolution are unchanged. The observed benefit is approximately four GPU milliseconds per frame at this configuration.

No packet-processing budget is added: delaying the client queue without handling chunk-batch feedback can accumulate packet backlog. Skipping Minecraft lighting or changing presentation synchronization would require separate correctness and latency evidence.

## Nsight Graphics

Nsight Graphics 2026.3.1 was launched with SDK-controlled capture. `nsight-export/manifest.json` records the flight input and acknowledged trigger at frame 399. The three-frame native trace and completed TSV exports are in `nsight-export/`; `analysis.json` contains compact extracted values. There are no dropped-sample or timestamp-exhaustion warnings in this attempt's log.

The original shader's three captured frames have build times 2.745 / 2.742 / 2.862 ms, stable-plane fill 13.605 / 14.264 / 14.028 ms, and scene effects 9.760 / 9.212 / 9.875 ms. These independently confirm the substantial fog cost. Nsight uses instrumented base-clock timing, so the ordinary JFR runs determine the performance comparison. Scope metrics include concurrent work and are not isolated instruction costs. This short trace did not reproduce a worst-case native hitch.

The earlier 60-frame trace in `nsight/` saved successfully but reported dropped periodic samples. Its companion JFR could not be stopped because the profiler terminated the launched client after collection; that incomplete recording is not used. The completed three-frame export is the usable Nsight evidence.

## Validation

Renderer-presentation checks and shader compilation/SPIR-V validation pass. Source inspection verifies that every transport and history access uses the same contiguous-plane indexing, that the five-float4 history header is preserved, and that the existing inter-dispatch barriers and buffer sizes still apply. A rendered candidate capture was inspected at the original cave pose (`candidate-preview.jpg`); this is a limited visual check, not a claim of pixel-identical images across live simulations. The change preserves the mathematical operations and sampling quality.
