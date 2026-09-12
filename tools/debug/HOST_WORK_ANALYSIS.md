# External host-work analysis

Record `HostLoop,HostWork,HostCallbackTotals,HostSubmission,Frame,CpuStage,FrameCounter`. Keep captures and interactive
debug requests outside steady-cave measurement windows. Analyze locally with:

```powershell
uv run python tools/debug/analyze_host_work.py recording.jfr --output host-analysis.json
uv run python tools/debug/analyze_host_work.py raw-events.json --rt-only --trim-loops 2
uv run python tools/debug/analyze_host_work.py recording.jfr --rt-only --window next-start --output upper-bound.json
uv run python -m unittest discover -s tools/debug -p test_analyze_host_work.py -v
```

JSON input is the unfiltered `jfr print --json` document, also exported by
`analyze_recording.py --raw`. Preserve `eventThread.javaThreadId`, integer
`startedNanos`, and all event values. Direct JFR input uses `jfr` from PATH or
`--jfr PATH`. No client access or recording mutation occurs.

The default removes the first and last completed HostLoop envelopes. Increase
`--trim-loops` for additional boundary settling; it must be at least one.
Trim happens before the optional `--rt-only` filter. Fewer than three completed
loops produce no samples. This policy cannot detect mid-recording event disabling,
event loss, or workload changes; use a continuous recording with all required events.
Multiple HostLoop threads require an explicit `--thread-id`. Other-thread stages
are counted as ignored, never included in render-thread work.

Each output loop reports the union of HostWork and CpuStage monotonic intervals,
clipped to its half-open envelope. HostWork must match its loop ID; CpuStage frame
IDs are deliberately not used for membership. Nested stages count once. Neither
HostLoop duration nor Frame duration/allocations are added. `occupiedMs` is the
distribution across selected loops, with linearly interpolated quantiles.

`hostWorkAllocations.bytes` sums only whole disjoint scopes. Negative counters,
overlapping scopes, partial scopes, or no observed HostWork produce `null`.
`knownDisjointBytes` retains the available subtotal, excluding partial scopes;
it is also null if scopes overlap. Bytes are never prorated across clipped time.
`observedUnionAllocatedBytes` additionally becomes null when CpuStage contributes
time outside HostWork. Summary `finiteSamples` counts only known totals; its
percentiles do not characterize unknown samples. A known zero remains zero.

`cpuStageOutsideHostWorkNanos` is the time-union coverage gap, not an allocation
estimate. Its bytes are unknown because CpuStage has no scoped allocation delta.
Per-stage gap totals can overlap each other and must not be summed. The difference
between a host-loop envelope and occupied time includes vanilla work and waits;
it cannot identify missing mod cost. Frame allocation subtraction would similarly
mix different windows and vanilla allocations, so is not performed.

With the default `--window loop`, loop-0 HostWork is not assigned to loop percentiles. `loop0.records` preserves
every raw work interval, allocation sentinel, and CPU-clock sentinel. The report
also gives its union and allocations over the entire recording and over the span
from the first retained interior loop's start to the last one's end, including
inter-loop gaps. This span is independent of `--rt-only`. A loop-0 scope crossing
that span's boundary contributes clipped occupied time but unknown scoped bytes.
The all-recording view may include truncated recording-boundary activity.

`--window next-start` instead samples each retained loop's start through the next
completed loop's start. Only consecutive loop IDs qualify. This includes loop-0
HostWork and CpuStage time between loops by monotonic interval; the separate loop-0
report must not be added again. Boundary loops are still trimmed before selection.

`HostCallbackTotals` emits scalar raw counts, measured counts, elapsed sums and
allocation sums for each callback label, including explicit zero-frequency labels.
Nested HostWork or callback observations suppress timing/allocation, but invocation
counts remain visible. Loop-0 totals describe the inter-loop gap and are flushed at
the next loop start. These sums contain no individual callback intervals.
Whole totals outside the selected windows (including gaps after trimmed or filtered
loops) remain in `callbackTotalsOutsideSelectedWindows`. A totals window partly
crossing a selected boundary is rejected; counts, elapsed sums and bytes are never
prorated. Excluded zero-count and unavailable-counter records are preserved too.

`coveredWithCallbacksUpperBoundNanos` adds callback elapsed sums to the occupied
interval union. `hostInclusiveUpperBoundNanos` also adds the HostSubmission union.
Overlap can only widen these conservative bounds. V3 HostSubmission covers the host
encoder's entire `submit()` before and after `awaitSubmitCompletion`, including
packing/native submit, transient-memory bookkeeping, command-pool reset and queue
rotation. The entire explicit await helper is outside; measured mod selectors in
that helper still contribute callback totals. This is shared host cost, not exclusive
mod cost. Missing labels, missing gap totals for next-start windows, or no submission
observation make the corresponding bound null. A below-target percentile with
unknown samples is not a complete result.

`hostSubmissionCoverage` distinguishes legacy `packing-only` observations from
`full-submit-cpu-segments`. `fullHostSubmitUpperBoundNanos` requires exactly one
whole `beforeWait` and one whole `afterWait` segment per submissionId, in order,
with normal completion on the final segment. Failed, missing, duplicate, clipped or
mismatched pairs produce null. V2 packing-only observations retain their narrower
host-inclusive result but never populate the full-submit bound. Inspect finiteSamples
and the coverage counts before interpreting a percentile as the entire sample set.

`hostWorkPlusCallbacksAllocatedBytes` adds available callback deltas to disjoint
HostWork deltas. `hostInclusiveAllocatedBytesUpperBound` additionally adds whole
shared-submission deltas, potentially double-counting allocations inside that
shared envelope. Any unavailable/partial/overlapping submission counters or
CpuStage-only allocation coverage gaps make that bound unknown. No Frame
allocation subtraction is used.

Occupied wall time is a conservative CPU bound only for observed work and includes
waits and some observer overhead. Windows thread-CPU counters may be quantized at
15.625 ms; the analyzer does not derive a precise CPU P99 from them. Interpret the
bound against the active-workload inventory in `tmp/host-telemetry/COVERAGE.md`.
Optional workloads require their own covered hooks. Scope machinery outside its
endpoints is recording overhead, not a claim of unmeasured production callback work.
Live Mixin validation of the measurement candidate remains a separate check.

The combined V3/SER/pooled-engine recording
`54ceb2af-a252-4d81-8a89-827804f89a4b` passed that live check. Its full raw export
produced 906 retained consecutive next-start windows from 908 completed loops,
with complete pre/post-await submission pairs and all scalar labels in every
window. CpuStage contributed zero time outside HostWork; all allocation totals
were available. Inter-loop scalar observations were present with explicit zeros,
and no loop-0 HostWork was observed.

The inventoried steady-cave CPU target passes: `fullHostSubmitUpperBoundMs` has
mean **2.296524 ms**, P99 **2.974005 ms**, and maximum **4.771900 ms**, below the
10 ms P99 target. This is a conservative occupied-wall bound including shared
host submit CPU work, with the explicit await excluded. It is not a precise
measurement from Windows' quantized thread-CPU clock. The host-inclusive
allocation bound has median **291848 bytes/window** and P99 **346696 bytes/window**.

The post-await segment encloses host command-pool reset, destruction/checkpoint
queue rotations and transient-memory setup, as checked against actual host
bytecode. Both segments were observed once per retained loop, with normal final
completion. The former host cleanup gap is therefore covered for this run.
Optional workloads still require their own active-hook inventory; see
`tmp/host-telemetry/COVERAGE.md` for the exact scope and observer-overhead treatment.

Results are saved in `tmp/gpu-investigation/cave-v3-ser-pool-host.json`, with
segment/allocation/counter detail in `cave-v3-ser-pool-coverage-audit.json`.
Selected frames contain 86 entities, 5 particles and 730 cuboids, compared with
94/1/808 in V2. Tick frequency also differs (392/906 versus 490/905 windows).
These measurements establish the absolute target result for the recorded cave;
they do not isolate CPU gains from SER or pool reuse across different workloads.

The final integrated query/diagnostics recording
`ef2bff6d-70ab-41bf-8502-87d2747f5db1` also passes the CPU target: **4.494033 ms
P99**, mean **2.667406 ms**, maximum **9.719100 ms**, across **904/904 complete
retained windows**. All submission pairs, scalar labels and allocation counters
are available, with zero CpuStage-only time. Native-submit diagnostic checks/history
calls are inside beforeWait; native-reset diagnostics are inside afterWait. Their
active costs are included in this host-inclusive bound.

Allocation median is **319288 bytes/window**, P99 **1670060.32 bytes**. This run has
active terrain work (up to 64 snapshots and 651 section copies/frame), unlike the
earlier settled recording. World.capture dominates that allocation tail; the
increase cannot be attributed solely to diagnostics. Runtime.tick still has
median 31504 bytes/call. Unknown counters remain unavailable rather than zero.

Exports and results are in `tmp/shadow-query-diagnostics/`: `events.json`,
`jfr-summary.txt`, `summary.json`, `host.json`, `coverage-audit.json` and
`gpu-cadence.json`. Every raw event count matches the independent JFR summary.
`MEASUREMENT.md` records the exact scope, settings and workload. GPU span averages
20.398211 ms and host cadence averages **47.9803 FPS**, so this final recording
passes the CPU target but does not meet the 60 FPS frame target. GPU stage nesting
is handled by interval union, and host cadence is reported separately from mod cost.
