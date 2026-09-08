# Agent debugging

Launch a development client with `./gradlew :packages:minecraft-client:runClient -PdebugAgent=true -PquickPlayWorld=YOUR_WORLD` (`gradlew.bat` on Windows). For an installed build use `-Dcaustica.debug.enabled=true`. Prefer a copy of a world for experiments that change it.

Add `-PvulkanValidation=true` to enable Minecraft's Vulkan validation option for a development run. Check startup logs for `VK_LAYER_KHRONOS_validation`; the host reports when the requested layer is unavailable. Validation changes execution overhead, so keep it out of performance comparisons.

Use the repository uv environment for packages and execution: `uv sync --locked`, then `uv run python ...`. Add dependencies with `uv add`; NumPy, OpenEXR and Pillow are declared in the workspace.

The opt-in HTTP service binds to loopback. Connection details are in `run/caustica-debug/session.json`; do not share this file or its token. Run scripts from the repository root, or pass `--session` with an absolute path.

```powershell
uv run python tools/debug/caustica_debug.py status
uv run python tools/debug/caustica_debug.py schema
uv run python tools/debug/caustica_debug.py settings.get
uv run python tools/debug/caustica_debug.py jfr.start
uv run python tools/debug/caustica_debug.py wait '{"frames":300}'
uv run python tools/debug/caustica_debug.py jfr.stop
uv run python tools/debug/caustica_debug.py screenshot
uv run python tools/debug/caustica_debug.py view.set '{"name":"normals"}'
uv run python tools/debug/caustica_debug.py image.capture '{"name":"normal-roughness"}'
uv run python tools/debug/caustica_debug.py view.set '{"name":"off"}'
uv run python tools/debug/measure.py --warmup 120 --frames 300 --output tmp/run.json
```

If shell JSON quoting is awkward, use `--json-file args.json`. Operations complete through jobs; the Python client waits and prints the completed result. `--timeout 180` changes the client deadline; `timeoutMs` in the request changes the game job deadline. Expiry does not undo commands already applied.

## Script control

Add `tools/debug` to Python's import path and use `Client.call(op, **arguments)`. The CLI and imports use the same protocol; no test runner is required.

| Operation | Arguments / result |
| --- | --- |
| `schema` | Available operations, named views and raw image names |
| `status` | World/player/camera, renderer state, settings and latest recorded counters. `screen` is the current screen class name, or an empty string during gameplay. The player includes `currentFlyingSpeed`, `flying`, and `sprinting`. |
| `settings.get` | Minecraft adapter and renderer setting IDs, encoded values and whether JVM overrides apply. Unset optional strings use an empty string; returned values can be passed to `settings.set`. |
| `settings.set` | `values` mapping setting IDs to primitive values; JVM overrides cannot be changed |
| `runtime.set` | Required `enabled` boolean applies the normal Minecraft RT toggle. Returns `requested`, `active`, `frameActive`, and `overridden`; the next client tick starts the lifecycle transition. JVM overrides cannot be changed. `status.runtime` reports the same fields. |
| `resources.reload` | Reloads the currently selected resource packs through Minecraft's public reload entry point. Completes when the reload future completes, or reports its failure. RT recovery happens on subsequent client ticks; wait for active RT separately before capturing. Does not change selected packs. |
| `world.leave` | Leaves through Minecraft's normal disconnect/save/menu flow. Requires a world. Optional `onlyIfTerrainBusy:true` returns `left:false` without leaving when the immediate terrain-build sample is zero, allowing a caller to retry without a separate status/action gap. Returns `terrainBuildsBeforeDisconnect`; workers can advance after the sample. Poll status for no world/player and drained terrain work. `status.terrainOutstandingBuilds` exposes the same counter across epochs, including retired worlds. The process-wide RT session can remain active at menus. |
| `view.set` | `name` from schema; `off` restores normal presentation |
| `screen.close` | Calls the current screen's normal close handler once; returns whether a screen was present. May return to a parent screen or initiate an asynchronous transition. Use this for screens with completion actions, such as End credits; poll status for the resulting screen/world. |
| `input.set` | `forward` and `sprint` booleans (default false); closes the current screen and holds the corresponding game keys for continuous flight. Optional `flyingSpeed` sets the spectator ability speed: a finite number from 0 to 0.2, matching the mouse-wheel range. Omission leaves speed unchanged. Returns `previousFlyingSpeed` and `currentFlyingSpeed` alongside key states. Call with no arguments to release both keys. |
| `wait` | `frames` counts frames with a successfully recorded RT world composite; runtime activity alone does not count. This is a command-recording milestone, not GPU completion or display cadence. `ticks` counts unpaused world ticks. World required, counts must advance. Use ticks while RT is disabled. |
| `command` | `command` without slash; default `target:server` executes on the integrated server with player permissions and returns a result; `target:client` sends through the normal network connection and confirms dispatch only |
| `screenshot` | Presented-target PNG path after completion, including menus without a world. `frameId` is the latest RT telemetry serial, not a menu frame counter. |
| `image.capture` | Raw EXR `name`, or `names` for a same-frame bundle; paths and metadata |
| `jfr.start` | Start a raw recording with JVM profile events and Caustica events; optional `events` array selects Caustica names such as `Frame`, `GpuStage`, `TerrainState` |
| `jfr.dump` | Save current recording without stopping |
| `jfr.stop` | Stop/save recording and return its path |
| `job` | `jobId`; returns pending/completed/failed state |
| `client.stop` | Stop the debug client gracefully after returning acknowledgement |

For repeatable flight routes, use spectator mode and a level camera. Ability speed defaults to `0.05`; `0.2` is the maximum mouse-wheel speed. With sprint held, steady horizontal speeds are approximately 21.78 and 87.11 blocks per second respectively at 20 ticks per second. The ability value is an acceleration parameter, not blocks per second. Read `status.player.currentFlyingSpeed` to record the actual configuration; a previous mouse-wheel adjustment can change it.

Save the `previousFlyingSpeed` returned by the initial `input.set` call. In the benchmark's `finally` block, call `input.set` with `forward:false`, `sprint:false`, and `flyingSpeed` set to that saved value while still in spectator mode. Releasing keys alone leaves the ability speed unchanged. The override uses the same client ability setter as spectator mouse-wheel input.

For RT lifecycle checks, save `status.runtime.requested`, call `runtime.set` with `enabled:false`, wait for ticks and `status.runtime.active:false`, and inspect vanilla terrain at the current camera. Restore the saved value afterward and wait for `active:true` when re-enabling RT. Perform toggles and image capture outside performance recordings. Omit a JVM `caustica.rt` override when the run needs this toggle.

After resource reload, successful composites do not imply that distant terrain preparation has finished. Keep the camera fixed and inspect both early and later captures before diagnosing missing geometry. A fixed frame or tick count is an observation interval, not a terrain-completion signal.

Direct clients POST JSON `{ "op": "status" }` to the discovery `baseUrl` plus `/api`, with `Authorization: Bearer TOKEN`. Replies are `{ok:true,jobId}` or `{ok:true,result}`; poll `job` for asynchronous completion. Errors have `ok:false,error` or a failed job. Discovery includes `protocolVersion` and process ID.

## Record, then analyze

The game emits raw observations without hitch thresholds, percentile filtering or CSV logging. Warm up, start a recording, wait a useful number of frames, stop, then capture images outside the measured interval. Repeat comparable runs when performance differences are small.

```powershell
uv run python tools/debug/analyze_recording.py run/caustica-debug/recording-ID.jfr --raw tmp/events.json
```

This external Python analysis computes descriptive statistics and exposure changes. It requires the JDK `jfr` executable on PATH (or `--jfr PATH`). The exported JSON retains all Caustica events for ad hoc analysis. Use JDK `jfr print` separately for JVM events. Record settings, camera, workload and build revision alongside measurements; wall time includes scheduling and waits.

If a measurement fails after recording starts, its JSON retains the available conditions even when the client cannot stop JFR. Missing `end` or `recording` fields indicate an incomplete experiment, not a completed timing sample.

For JVM hotspot attribution, export with `jfr print --json --stack-depth 64 --events jdk.ExecutionSample,jdk.ObjectAllocationSample RECORDING.jfr`.
The command's default stack display depth is five frames and can hide application callers even when the recording contains deeper stacks.

`measure.py` selects frame, CPU/GPU stage, counter and exposure events by default. Terrain state snapshots traverse section state and can substantially perturb a large-world CPU profile; enable them for streaming diagnosis with `--events` or a full `jfr.start`. All enabled events are retained. Query pools warm up when GPU recording first starts; account for initial samples in comparisons.

- `Frame`: CPU envelope, including RT tick preparation when present; not display cadence or generated-frame FPS.
- `Frame.threadCpuNanos` and `allocatedBytes`: render-thread CPU and allocated-byte deltas over the envelope. CPU-clock resolution is platform-dependent; these exclude other threads. `FramePreparation` also reports task CPU/allocation deltas and its monotonic start time.
- `GpuWait`: explicit graphics timeline wait intervals. These do not include every host presentation or driver wait.
- `CpuStage`: each elapsed CPU scope, with stage and frame ID. Nested scopes overlap; do not sum everything.
- `GpuStage`: command-stage timestamps on the graphics queue; query pools are reused after existing completion. No telemetry wait is added. Named build/local-bake/fill intervals sit inside `world resources and trace`; do not add them to that parent interval. These intervals are not utilization counters or a total display frame time. Recording GPU timestamps still perturbs execution; compare runs with the same instrumentation.
- `FrameCounter`: raw per-frame count or gauge. A latest snapshot is only updated while frame collection is enabled by a recording.
- `GeometryVisibility`: raw extraction/publication/assembly timestamps. Assembly is not GPU completion or a guarantee that the object contributes visible pixels.
- `EntityMeshFrame`: source and assembled mesh revisions/frames, including prior values. Superseded revisions need not appear.
- `EntityMeshPublication`: ready and publication timestamps for a prepared entity mesh. Correlate with frame start times to separate engine publication delay from entity extraction and GPU preparation.
- `EntityMeshUpload`: queue/start/finish timestamps, worker CPU time, and allocated bytes for entity packing and mesh submission. GPU preparation and retained publication occur afterward; correlate by entity key and timestamps.
- `FramePreparation`: elapsed time for each preparation chunk, with phase and workload size. `scene-revision` measures retained CPU scene assembly; `trace-revision` measures the complete geometry/light preparation, including GPU completion. Both run on the serial scene worker. `tlas-reserve`, `tlas-pack`, and `tlas-ready` separate allocation, input packing, and waiting for the GPU build on that worker. `plan` and `pack` report instance counts; `light-index` and `lights` report light counts. Nested and parallel worker scopes overlap; do not add them to render-thread time.
- `TraceRanges`: active geometry-record and emitter-byte counts versus the highest addressed range ends. Their difference measures holes in the stable per-scene layout; actual buffer allocations may be larger because retired revision storage retains capacity for reuse.
- `TerrainState`: actual section/resident, neighbor-wait and worker/queue state each client tick. `observedFrameId` is the last render serial, not a claim that the tick has appeared on screen.
- `TerrainJob`: dispatch, CPU completion, GPU preparation, publication and supersession observations keyed by epoch/revision/section coordinates. Derive queue and update latency externally; a superseded revision need not publish.
- `TerrainPublication`: worker publication duration, CPU time and allocated bytes, with group/section counts and publication/failure flags. Include it explicitly in recordings that select events. It covers edit preparation and atomic commit on the publication worker. Render capture retains the published root through a separate short mutex. Abandoned attempts are retained with `published: false`.
- `TerrainDispatchPlan`: worker candidate ranking duration and changed, retained, and selected candidate counts. The render thread consumes completed selections and validates request identity before palette extraction. `terrain.paletteCapture` measures that Minecraft extraction inside `terrain.snapshotDispatch`.
- `NeeFrame`: retained light counts, candidate count and history validity; `rendererFrameId` uses the renderer's separate frame namespace.
- `Exposure`: source `frameId`, later `observedFrameId`, latched pre-exposure and completed controller values. `controllerResidualExposure` is derived from controller state, not sampled from the display image. Compare auto samples across consecutive source frames within one reset sequence. The last few GPU readbacks can arrive after recording stops; retain padding frames when measuring a bounded interval.

JFR timestamps describe event emission; explicit frame IDs and `System.nanoTime` fields describe original work. JFR JSON represents annotated elapsed times as ISO durations. Keep CPU, GPU, controller and display measurements distinct.

## Images and debugging recipes

PNG captures show the presented render target. Debug views are convenient visualizations; raw EXRs retain native-resolution float components without display mapping. Read each EXR's returned/embedded encoding and pre-exposure metadata before comparing values. Capture synchronizes GPU readback and perturbs frame timing.

For raw inspection, run `uv run python tools/debug/inspect_buffer.py CAPTURE.exr --view normal --preview tmp/normals.png`. It reports finite ranges and normal statistics; preview scaling never changes the raw data. The normal view maps signed XYZ to RGB, and is not a color-managed beauty-image preview.

Under the NRD temporal route, normal/roughness and NRD signal buffers contain the final plane 0 inputs after per-plane preparation. They do not contain every plane or original material texture values. NRD signal radiance uses the documented fixed scale in capture metadata, not the ordinary trace pre-exposure scale. Generic motion/depth/albedo remain trace guides.

- **NRD guides:** capture normal/roughness, NRD view Z and diffuse/specular signals together. Inspect finite values, edges and packing before blaming denoising. Named views help inspect spatial errors quickly.
- **Periodic darkness:** record stationary-camera exposure; find jumps in Python, then compare trace/reconstructed radiance. Stable controller exposure alone does not prove stable lighting. Use short capture bursts only for visual diagnosis.
- **Streaming:** issue repeated teleports with tick waits, inspect screenshots and geometry queue/resident counters, then stop and let work drain. Counts can suggest leaks or stalls but do not prove exact chunk coverage.
- **Material filtering:** inspect a known sharp normal/emission texture boundary near and far. Compare raw guides/radiance with display output, then inspect shader sampling. Linear filtering and linear color encoding are different questions.
- **Mesh delay:** correlate raw geometry and entity revision events in Python. Separate submit-to-ready from assembly delay, and validate apparent changes with a visible object.

Restore changed settings and debug view after experiments. Report observed data separately from suspected causes; keep recordings/images out of Git.
