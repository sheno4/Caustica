# Nsight GPU Trace report investigation — 2026-09-05

Environment: Nsight Graphics 2026.2.0, NVIDIA driver 616.56, RTX 5070 Ti, Windows. Renderer: `rewrite` at `2b81cbf4` with the uncommitted performance changes described in `INDOOR_PERFORMANCE.md`.

## Confirmed failure boundary

The CLI successfully connects, collects GPU Trace data, transfers it, and saves a `.ngfx-gputrace` file. With `--auto-export`, loading that file produces `An SEH exception was thrown while loading the report` and the CLI exits 1. The game continues rendering.

Without `--auto-export`, the CLI exits 0, but opening its report in the GUI produces the same load-error dialog. The GUI itself remains running. Therefore disabling export hides the failure rather than producing a usable report. This is not evidence of a renderer crash during GPU profiling, nor a failure confined to the CLI metric exporter.

The GUI-collected report `tmp/perf-indoor/java_2026_09_05_21_58_06.ngfx-gputrace` opens and exports successfully. Its shader samples and analysis are documented in `INDOOR_PERFORMANCE.md`.

## Controlled checks

The GUI launched Java PID 50180 and captured the enclosed indoor scene successfully. The GUI then disconnected without terminating Java. CLI comparisons attached to that same injected process, preserving its compiled shaders, world, camera, and renderer settings. These CLI comparisons ran outside the Codex filesystem sandbox.

| Test | Result | Local evidence under `tmp/perf-indoor` |
| --- | --- | --- |
| Open the earlier CLI report in the GUI | Same SEH load error; GUI survives | `nsight-basic/java_2026_09_05_21_23_57.ngfx-gputrace` |
| CLI attach using `mc` project, one frame, automatic export | Collection succeeds; report load fails | `cli-mc-attach.log`, `cli-mc-attach/java_2026_09_05_22_19_52.ngfx-gputrace` |
| Same attach with automatic export omitted | Exit 0; saved report fails to load in GUI | `cli-no-export.log`, `cli-no-export/java_2026_09_05_22_21_10.ngfx-gputrace` |
| Run CLI from its own installation directory | Same report load failure | `cli-install-cwd.log`, `cli-install-cwd/java_2026_09_05_22_23_26.ngfx-gputrace` |
| Explicit Blackwell metric set, request no shader-pipeline/external-debug-info collection | Same report load failure | `cli-no-shaders.log`, `cli-no-shaders/java_2026_09_05_22_24_17.ngfx-gputrace` |
| Disable thumbnail collection with `--collect-screenshot 0` | Same report load failure | `cli-no-screenshot.log`, `cli-no-screenshot/java_2026_09_05_22_28_12.ngfx-gputrace` |

The last test used an already injected process; launch-time shader interception may persist, so it does not conclusively eliminate shader metadata as a cause. Likewise, using the same project reduces configuration differences but does not prove that the GUI and CLI interpret every setting identically. The CLI printed a 400 MB PMA buffer, while the GUI's initial launch printed 4,000 MB.

The project uses manual triggering. The attach test initially logged a 1,000 ms countdown but remained armed with the HUD showing `Ready (F11)`; pressing F11 collected the trace. Subsequent tests explicitly used `--start-after-hotkey`.

Representative reproduction after the GUI disconnects from an injected target:

```powershell
$ngfx = 'C:\Program Files\NVIDIA Corporation\Nsight Graphics 2026.2.0\host\windows-desktop-nomad-x64\ngfx.exe'
$project = 'C:\Users\i\Documents\NVIDIA Nsight Graphics\mc\mc.ngfx-proj'
$output = 'C:\Users\i\Developer\mc\dlss-mod\tmp\perf-indoor\cli-repro'
New-Item -ItemType Directory -Force $output | Out-Null
# Replace 50180 with the currently injected Java PID. Trigger F11 in Minecraft.
& $ngfx --project $project --activity 'GPU Trace Profiler' --attach-pid 50180 `
    --start-after-hotkey --limit-to-frames 1 --auto-export --output-dir $output
```

These checks point toward a CLI-specific collection/configuration/serialization problem whose output triggers the shared report loader. They do not identify the malformed field, failing native instruction, or exact NVIDIA defect. No matching Windows Application Error event was found in the queried two-week interval; the observed SEH is caught and reported by Nsight, so an unhandled-crash event is not assured. A native exception stack or a vendor analysis of the failing and working reports is still needed for an exact cause.

The usable workflow on this installation is GUI GPU Trace collection and GUI analysis export. No renderer change was made to work around the CLI failure.

### Attempt to read the known-good report through the CLI

Passing the GUI-created `java_2026_09_05_21_58_06.ngfx-gputrace` directly to `ngfx.exe` fails argument validation with `Activity is required to start CLI`. Adding `--activity 'GPU Trace Profiler' --auto-export` instead produces `Application path is required to start CLI`. The file does not reach a report reader in either attempt. Logs: `cli-open-good.log` and `cli-open-good-activity.log`.

The installed CLI help exposes automatic export of newly collected traces, but no standalone report-load/export mode. There is no `GPUTrace.pyd` in this installation; [NVIDIA confirms its removal from public releases](https://forums.developer.nvidia.com/t/nsight-graphics-2026-1-does-not-include-gputrace-pyd/369682). Consequently, **the known-good GUI report has not been tested in a CLI report reader**, and no claim that it fails there is supported.

## Separate live frame capture/replay regression

The user reports that live frame capture/replay consistently crashes Nsight on both `main` and `rewrite`, with the last working revision possibly around July 15. GPU Trace collection does not establish that resource capture and replay are correct.

The nearest saved frame captures are July 7, July 8, and July 22; there is no July 15 frame capture in the `mc` directory. Their timestamps are not proof of successful replay. The July captures were recorded using Nsight 2026.2 build 37991608, with drivers 610.62 (July 7/8) and 610.74 (July 22). The current driver is 616.56.

The July 22 capture was replayed using `ngfx-replay --present-hidden --loop-count 3 --verbose`. It crashed after resource initialization. The July 7 capture also exits 1 after resource initialization on today's setup. The July 22 test with `--no-ngx-replay` also fails. Logs are `tmp/nsight-replay/july22.log`, `july07.log`, and `july22-no-ngx.log`.

Earlier captures were then tested with the same three-loop hidden replay settings, adding `--no-crash-reporting` to avoid a submission dialog:

| Saved capture | Replay on driver 616.56 | Captured features | Local log |
| --- | --- | --- | --- |
| June 12, 15:41:55 | Exit 0, `Replay completed successfully` | General Vulkan rendering | `tmp/nsight-replay/june12-first.log` |
| June 18, 20:42:40 | Exit 0, `Replay completed successfully` | Acceleration structures, ray dispatch, NGX ray reconstruction | `tmp/nsight-replay/june18.log` |
| July 7, 00:22:52 | Exit 1 after initialization | Above plus opacity micromaps | `tmp/nsight-replay/july07.log` |
| July 22, 00:24:50 | Native read access violation | Above plus opacity micromaps | `tmp/nsight-replay/july22.log` |

The June captures used driver 610.47 and the same Nsight build as the July captures. June 18 is therefore a working RT+RR capture on the current installation. This brackets the tested capture artifacts between June 18 and July 7; it is not yet a commit bisect, because scenes, settings, and capture-time drivers differ. Relevant changes in that window include BDA-indirected world inputs (`cc599f18`, June 20) and opacity micromaps (`35cbc654`, June 21). OMM is a concrete feature difference in the metadata, not a proven cause; its absence from current rewrite also prevents attributing all reported failures to OMM alone.

The July 22 crash reporter's minidump was saved locally as `tmp/nsight-replay/july22.dmp`; nothing was sent to NVIDIA. Its exception stream identifies:

- Module: `ngfx-replay.exe` 2026.2.0.0, relative instruction offset `0x2674F4`.
- Exception: `0xC0000005`, read access violation at `0x8`.
- Instruction: `mov rsi, [rax + rcx*8]`, with `RAX=0` and `RCX=1`.

This is a CPU null-pointer dereference inside the Nsight replayer. It does not identify the underlying trigger. The dump contains the exception context for thread 18908 but only a stack record for thread 6868, so the crashing-thread backtrace cannot be reconstructed from this dump. July 7 already failing means the later July SER, AS compaction, and compute-queue changes cannot explain that capture's failure.

Read-only history inspection identified candidate boundaries, not diagnosed bugs:

- `0036c1bc`: shader execution reordering; `5d6bf622`: removal of the NV fallback and transition to the EXT path with wavefront passes.
- `22505f5c`: off-thread BLAS preparation; `6c420028`: dedicated compute queue.
- `0d432ad6`: BLAS compaction on main. Current rewrite lacks that compaction path, so it cannot by itself explain both branches.
- `40c63be1`: descriptor heaps on rewrite. Main does not use these heaps, so they cannot by themselves explain both branches.

NVIDIA's [2026.2 release notes](https://developer.nvidia.com/nsight-graphics/getting-started/release-note-v2026.2) describe new descriptor-heap capture/replay support and a beta-driver requirement. This is a compatibility check, not proof of the reported regression. Absence of explicit application capture-replay allocation flags is also not sufficient evidence of an application bug: capture tools arrange relevant replay behavior.

### Fresh captures of checked-out revisions

Built and ran actual revisions in `tmp/nsight-bisect`, using the copied enclosed indoor world, driver 616.56, and Nsight 2026.2. Each RT capture had DLSS-RR active at 427x240 -> 854x480. Historical revisions used their own compiled Java/shaders and revision-matching NGX shim; no renderer source patches were applied. Optional 26.2 mods were excluded from this isolated setup. Historical renderer behavior and defaults differ, so these are replay compatibility checks, not performance comparisons.

Initial fresh launches of both June 18 and current HEAD crashed before entering the world at `nvoglv64.dll+0xe19bd0` during presentation. Validation reported image views created from transfer-only swapchain images, followed by null framebuffer errors. Removing debug labels and hiding the HUD did not fix this. A process-local `VK_LOADER_LAYERS_DISABLE=VK_LAYER_KHRONOS_validation` attempt was ineffective: the loader logged the filter but subsequently inserted the validation DLL. After the user disabled validation, the next launch's layer log confirmed its absence and the same June 18 launch reached the world. This isolates a capture-startup interaction with validation; it does not establish which component introduced the invalid calls.

Fresh captured files were tested with `ngfx-replay --present-hidden --loop-count 3 --no-crash-reporting --verbose`:

| Checked-out commit | Capture settings | Fresh replay result | Capture directory under `tmp/nsight-replay` |
| --- | --- | --- | --- |
| `9594939f`, June 18 | Raster baseline, validation off | Completed successfully | `june18-validation-disabled-retry` |
| `9594939f`, June 18 | RT + RR, validation off | Completed successfully | `june18-rt-rr` |
| `b4094ae6`, June 19 | Position fetch, RT + RR, validation off | Exit 0, completed successfully | `june19-position-fetch` |
| `cc599f18`, June 20 | BDA-indirected world inputs, RT + RR, validation off | Exit 0, completed successfully | `june20-bda-world` |
| `d78a6453`, July 6 | RT + RR, OMM on, validation off | Exit `-1073741819` (`0xC0000005`) after resource initialization | `july06-fresh` |
| `d78a6453`, July 6 | Same launch with `-Dupscaler.rt.omm=false` | Exit 0, completed successfully | `july06-no-omm` |
| `2b81cbf4`, current rewrite | Stable-plane RT + RR, no OMM, validation off | Exit 0, completed successfully after continuing past compatibility warning | `current-validation-off` |

The July 6 OMM on/off experiment identifies OMM as a trigger for that fresh capture's replay failure. It does not explain every historical capture or the reported current GUI live-replay failure. Position fetch and BDA world-input indirection alone passed these experiments.

The current capture, `current-validation-off/java_2026_09_05_23_29_50.ngfx-capture`, reports `VK_EXT_ray_tracing_invocation_reorder` version 2 versus Nsight's supported version 1. Default replay waits at an incompatibility dialog. Re-running with `--no-block-on-incompatibility` proceeds and completes three loops; see `current-validation-off-replay-unblocked.log`. This establishes successful CLI resource replay for this frame despite the warning, not general support for every SER operation or verified replay image correctness. GUI live replay has not yet been retested with this fresh file.

Replay logs are `june18-fresh-raster-replay.log`, `june18-fresh-rt-rr-replay.log`, `june19-fresh-replay.log`, `june20-fresh-replay.log`, `july06-fresh-replay.log`, `july06-no-omm-replay.log`, and `current-validation-off-replay-unblocked.log`. The isolated worktree is left detached at `2b81cbf4`; captures and launch/build logs remain available under `tmp/nsight-replay`. No renderer workaround was applied to the main working tree. These results remain separate from GPU Trace report collection/loading failures.
