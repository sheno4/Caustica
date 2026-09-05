---
name: caustica-debug
description: Control a running Caustica development client, capture renderer buffers, and record raw JFR data to investigate visual bugs, streaming, mesh latency, and performance changes in this repository.
---

Read [the debug quickstart](../../../docs/DEBUGGING.md) for launch instructions, operation arguments and measurement semantics. Run `uv run python tools/debug/caustica_debug.py schema` to discover available operations, views and buffers. Use the same module's `Client` from Python for multi-step experiments.

Use the repository uv environment for all Python execution and package management (`uv run`, `uv add`, `uv sync --locked`). Dependencies belong in the workspace pyproject.toml/uv.lock.

Reproduce with commands/settings and observe a short run before changing code. Prefer named views for visual inspection, raw EXR bundles for numerical or same-frame guide inspection, and JFR for temporal/performance questions. Keep percentile/hitch/stability analysis in external Python; `tools/debug/analyze_recording.py` exports full data and provides a starting summary.

CPU scope time includes waits and nested stages overlap. Geometry assembly is not GPU completion or visible pixels. Exposure readbacks have a source frame distinct from their observation frame. Under NRD, captured per-plane guides/signals are the final plane 0 values; inspect encoding metadata rather than assuming ordinary scene radiance or material normals. Capture/readback disturbs timing: do it outside benchmark intervals.

For a fix, repeat a comparable camera/workload/settings experiment and report measured evidence separately from hypotheses. Use a copy of a world when modifying it, restore temporary settings/views, and keep recordings/images local. Do not claim a visual diagnosis from final color alone: inspect upstream guides/radiance and the relevant shader/sampler contract.
