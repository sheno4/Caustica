"""Analyze raw HostLoop/HostWork/CpuStage JFR intervals outside the game.

Only interior completed HostLoop envelopes form samples. Envelopes themselves
are never mod work; frame IDs do not determine interval membership. Loop-0 work
is reported separately, without assigning it to a renderer frame or host loop.
"""

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter, defaultdict
from decimal import Decimal
import json
from pathlib import Path
import re
import shutil
import subprocess

from analyze_recording import summarize

CALLBACK_LABELS = {"TRACKER_ROTATION", "EXPECTED_CHUNKS", "DIRTY_SECTION", "PARTICLES",
                   "WEATHER", "BLOCK_ENTITY", "VULKAN_RESULT", "SERIAL_SUBMIT"}


def nanos(value):
    """Decode JFR @Timespan JSON without rounding large monotonic timestamps."""
    if isinstance(value, int):
        return value
    match = re.fullmatch(r"PT(?:(-?\d+(?:\.\d+)?)H)?(?:(-?\d+(?:\.\d+)?)M)?(?:(-?\d+(?:\.\d+)?)S)?", value)
    if not match or not any(match.groups()):
        raise ValueError(f"Unsupported JFR duration: {value}")
    h, m, s = (Decimal(part or 0) for part in match.groups())
    return int((h * 3600 + m * 60 + s) * 1_000_000_000)


def interval(values):
    start = values["startedNanos"]
    duration = nanos(values["elapsedNanos"])
    if not isinstance(start, int) or duration < 0:
        raise ValueError("Expected integer monotonic start and nonnegative elapsedNanos")
    return start, start + duration


def union(intervals):
    merged = []
    for start, end in sorted(intervals):
        if end <= start:
            continue
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def length(intervals):
    return sum(end - start for start, end in union(intervals))


def clip(span, window):
    return max(span[0], window[0]), min(span[1], window[1])


def intersects(span, window):
    # Zero-duration scopes can still carry allocation deltas.
    return span[0] < window[1] and (span[1] > window[0] or span[0] == span[1] == window[0])


def allocations(records, window=None):
    """Only whole, disjoint HostWork deltas are additive; never prorate bytes."""
    spans = [interval(v) for v in records]
    overlap = sum(end - start for start, end in spans) != length(spans)
    partial = sum(window is not None and clip(span, window) != span for span in spans)
    unavailable = sum(v["allocatedBytes"] < 0 for v in records)
    known = None if overlap else sum(v["allocatedBytes"] for v, span in zip(records, spans)
                                    if v["allocatedBytes"] >= 0
                                    and (window is None or clip(span, window) == span))
    complete = bool(records) and not (overlap or partial or unavailable)
    return {"bytes": known if complete else None, "knownDisjointBytes": known,
            "unavailableScopes": unavailable, "partialScopes": partial,
            "overlappingScopes": overlap, "scopeCount": len(records)}


def submission_coverage(records, window):
    """Full host submit requires both whole CPU segments of every invocation."""
    if not records:
        return "missing"
    if all("submissionId" not in v for v in records):
        return "packing-only"
    invocations = defaultdict(list)
    for values in records:
        if "submissionId" not in values or clip(interval(values), window) != interval(values):
            return "incomplete-segments"
        invocations[values["submissionId"]].append(values)
    for segments in invocations.values():
        segments = sorted(segments, key=lambda v: (interval(v)[0], v["segment"] != "beforeWait"))
        if (len(segments) != 2
                or [v["segment"] for v in segments] != ["beforeWait", "afterWait"]
                or interval(segments[0])[1] > interval(segments[1])[0]
                or segments[0]["submissionCompleted"] or not segments[1]["submissionCompleted"]):
            return "incomplete-segments"
    return "full-submit-cpu-segments"


def analyze(events, trim_loops=1, thread_id=None, rt_only=False, window_policy="loop"):
    if trim_loops < 1:
        raise ValueError("Trim at least one completed loop at each recording boundary")
    if window_policy not in ("loop", "next-start"):
        raise ValueError("Expected loop or next-start window policy")
    groups = defaultdict(list)
    for event in events:
        if event["type"] not in {"dev.comfyfluffy.caustica." + name
                                 for name in ("HostLoop", "HostWork", "CpuStage", "HostCallbackTotals", "HostSubmission")}:
            continue
        values = event["values"]
        if event["type"].endswith(".HostCallbackTotals"):
            if (values["windowEndedNanos"] < values["windowStartedNanos"]
                    or nanos(values["elapsedNanos"]) < 0
                    or not 0 <= values["measuredCalls"] <= values["calls"]):
                raise ValueError("Invalid callback totals")
        else:
            interval(values)
        if "javaThreadId" not in (values.get("eventThread") or {}):
            raise ValueError("Raw events must retain eventThread.javaThreadId")
        groups[event["type"].rsplit(".", 1)[-1]].append(values)
    threads = {v["eventThread"]["javaThreadId"] for v in groups["HostLoop"]}
    if thread_id is None:
        if len(threads) != 1:
            raise ValueError("Expected one HostLoop thread; specify --thread-id for multiple threads")
        thread_id = next(iter(threads))
    ignored = Counter()
    for name, records in groups.items():
        ignored[name] = sum(v["eventThread"]["javaThreadId"] != thread_id for v in records)
        groups[name] = [v for v in records if v["eventThread"]["javaThreadId"] == thread_id]
    loops = sorted(groups["HostLoop"], key=lambda v: interval(v)[0])
    if not loops:
        raise ValueError("No completed HostLoop events on the selected thread")
    if len({v["loopId"] for v in loops}) != len(loops):
        raise ValueError("Duplicate HostLoop IDs; analyze a single recording/session")
    for before, after in zip(loops, loops[1:]):
        if interval(before)[1] > interval(after)[0] or before["loopId"] >= after["loopId"]:
            raise ValueError("HostLoop intervals must be disjoint with increasing IDs")
    interior = loops[trim_loops:-trim_loops]
    successors = {before["loopId"]: after for before, after in zip(loops, loops[1:])}
    continuous = [v for v in interior if window_policy == "loop"
                  or successors[v["loopId"]]["loopId"] == v["loopId"] + 1]
    selected = [v for v in continuous if not rt_only or v["rtActive"]]
    windows = [(interval(v)[0], successors[v["loopId"]]["startedNanos"])
               if window_policy == "next-start" else interval(v) for v in selected]
    starts = [span[0] for span in windows]
    buckets = [{"HostWork": [], "CpuStage": [], "HostSubmission": [], "totals": [], "gapTotals": []}
               for _ in selected]
    loop_zero = [v for v in groups["HostWork"] if v["loopId"] == 0]
    outside = Counter()
    for name in ("HostWork", "CpuStage", "HostSubmission"):
        for values in groups[name]:
            if name in ("HostWork", "HostSubmission") and values["loopId"] == 0 and window_policy == "loop":
                continue
            start, end = interval(values)
            index = max(0, bisect_right(starts, start) - 1)
            assigned = False
            while index < len(windows) and windows[index][0] <= end:
                if intersects((start, end), windows[index]):
                    if (name in ("HostWork", "HostSubmission") and values["loopId"] != 0
                            and values["loopId"] != selected[index]["loopId"]):
                        raise ValueError("HostWork loopId disagrees with its monotonic interval")
                    buckets[index][name].append(values)
                    assigned = True
                index += 1
            if not assigned:
                outside[name] += 1
    selected_ids = {v["loopId"]: i for i, v in enumerate(selected)}
    excluded_totals = []
    for values in groups["HostCallbackTotals"]:
        span = values["windowStartedNanos"], values["windowEndedNanos"]
        if values["loopId"] == 0:
            index = bisect_left(starts, span[1]) - 1
            key = "gapTotals"
            if window_policy != "next-start":
                outside["HostCallbackTotals"] += 1
                excluded_totals.append(values)
                continue
        else:
            index = selected_ids.get(values["loopId"], -1)
            key = "totals"
        if index < 0 or index >= len(windows):
            outside["HostCallbackTotals"] += 1
            excluded_totals.append(values)
        elif clip(span, windows[index]) != span:
            if values["loopId"] == 0 and not intersects(span, windows[index]):
                outside["HostCallbackTotals"] += 1
                excluded_totals.append(values)
            else:
                raise ValueError("Callback totals window crosses its selected sampling boundary")
        else:
            buckets[index][key].append(values)
    samples = []
    uncovered_by_stage = defaultdict(int)
    for loop, window, bucket in zip(selected, windows, buckets):
        work = [clip(interval(v), window) for v in bucket["HostWork"]]
        stages = [clip(interval(v), window) for v in bucket["CpuStage"]]
        work_ns = length(work)
        occupied_ns = length(work + stages)
        uncovered = occupied_ns - work_ns
        by_stage = defaultdict(list)
        for v in bucket["CpuStage"]:
            by_stage[v["stage"]].append(clip(interval(v), window))
        for stage, spans in by_stage.items():
            uncovered_by_stage[stage] += length(work + spans) - work_ns
        allocation = allocations(bucket["HostWork"], window)
        callback_complete = all(
            len(bucket[key]) == len(CALLBACK_LABELS)
            and {v["callback"] for v in bucket[key]} == CALLBACK_LABELS
            for key in (["totals", "gapTotals"] if window_policy == "next-start" else ["totals"]))
        callback_rows = bucket["totals"] + bucket["gapTotals"]
        callback_ns = sum(nanos(v["elapsedNanos"]) for v in callback_rows)
        callback_bytes = (sum(v["allocatedBytes"] for v in callback_rows)
                          if callback_complete and all(v["allocatedBytes"] >= 0 for v in callback_rows) else None)
        submission_ns = length([clip(interval(v), window) for v in bucket["HostSubmission"]])
        submission_allocation = allocations(bucket["HostSubmission"], window)
        submission_scope = submission_coverage(bucket["HostSubmission"], window)
        callback_allocation = (allocation["bytes"] + callback_bytes
                               if allocation["bytes"] is not None and callback_bytes is not None else None)
        upper_ns = occupied_ns + callback_ns if callback_complete else None
        shared_upper_ns = upper_ns + submission_ns if upper_ns is not None and bucket["HostSubmission"] else None
        shared_upper_bytes = (callback_allocation + submission_allocation["bytes"]
                              if callback_allocation is not None and submission_allocation["bytes"] is not None
                              and uncovered == 0 else None)
        samples.append({"loopId": loop["loopId"], "frameIdBefore": loop["frameIdBefore"],
                        "frameIdAfter": loop["frameIdAfter"], "rtActive": loop["rtActive"],
                        "startedNanos": window[0], "endedNanos": window[1],
                        "hostWorkNanos": work_ns, "cpuStageNanos": length(stages),
                        "occupiedNanos": occupied_ns, "cpuStageOutsideHostWorkNanos": uncovered,
                        "hostWorkAllocations": allocation,
                        "observedUnionAllocatedBytes": allocation["bytes"] if uncovered == 0 else None,
                        "allocationGap": bool(uncovered or allocation["bytes"] is None),
                        "callbackTotalsComplete": callback_complete,
                        "callbackTotals": callback_rows, "callbackExtraNanos": callback_ns,
                        "hostSubmissionNanos": submission_ns,
                        "hostSubmissionCoverage": submission_scope,
                        "hostSubmissionAllocations": submission_allocation,
                        "hostWorkPlusCallbacksAllocatedBytes": callback_allocation,
                        "coveredWithCallbacksUpperBoundNanos": upper_ns,
                        "hostInclusiveUpperBoundNanos": shared_upper_ns,
                        "fullHostSubmitUpperBoundNanos": shared_upper_ns
                            if submission_scope == "full-submit-cpu-segments" else None,
                        "hostInclusiveAllocatedBytesUpperBound": shared_upper_bytes})
    retained_span = (interval(interior[0])[0], interval(interior[-1])[1]) if interior else None
    zero_retained = [v for v in loop_zero if retained_span is not None
                     and intersects(interval(v), retained_span)]
    zero_spans = [clip(interval(v), retained_span) for v in zero_retained]
    return {
        "policy": {
            "sampling": "Complete HostLoop envelopes; trim first/last N before optional RT filter",
            "trimLoopsEachEnd": trim_loops, "rtOnly": rt_only, "javaThreadId": thread_id,
            "windowPolicy": window_policy,
            "time": "Union of monotonic HostWork and CpuStage intervals clipped to each loop",
            "allocations": "Whole disjoint HostWork only; null means unknown; no Frame subtraction or proration",
            "loop0": "Included by interval in next-start windows; also reported separately. Never add the separate report again.",
            "upperBound": "Occupied interval union PLUS callback elapsed sums PLUS host submission union. "
                          "Overlap can only widen this host-inclusive bound; it is not an exact union or mod-exclusive time. "
                          "Missing per-label totals (including explicit zero counts) or submission observations yield null.",
            "limits": "Occupied wall time bounds covered CPU work only; includes waits/observer overhead. "
                      "Windows CPU clocks may be quantized. Full-submit bounds require complete pre/post-await pairs; "
                      "legacy packing-only bounds exclude encoder cleanup. Interpret coverage for the inventoried active workload.",
        },
        "completedLoops": len(loops), "boundaryLoopsRemoved": len(loops) - len(interior),
        "discontinuousWindowsRemoved": len(interior) - len(continuous),
        "rtFilteredLoops": len(continuous) - len(selected), "sampledLoops": len(samples),
        "ignoredOtherThreadEvents": dict(ignored), "eventsOutsideSelectedLoops": dict(outside),
        "callbackTotalsOutsideSelectedWindows": excluded_totals,
        "occupiedMs": summarize([s["occupiedNanos"] / 1e6 for s in samples]),
        "cpuStageOutsideHostWorkMs": summarize([s["cpuStageOutsideHostWorkNanos"] / 1e6 for s in samples]),
        "observedUnionAllocatedBytes": summarize([s["observedUnionAllocatedBytes"] for s in samples]),
        "allocationGapLoops": sum(s["allocationGap"] for s in samples),
        "callbackTotalsMissingLoops": sum(not s["callbackTotalsComplete"] for s in samples),
        "coveredWithCallbacksUpperBoundMs": summarize([
            s["coveredWithCallbacksUpperBoundNanos"] / 1e6
            if s["coveredWithCallbacksUpperBoundNanos"] is not None else None for s in samples]),
        "hostInclusiveUpperBoundMs": summarize([
            s["hostInclusiveUpperBoundNanos"] / 1e6
            if s["hostInclusiveUpperBoundNanos"] is not None else None for s in samples]),
        "hostSubmissionCoverage": dict(Counter(s["hostSubmissionCoverage"] for s in samples)),
        "fullHostSubmitUpperBoundMs": summarize([
            s["fullHostSubmitUpperBoundNanos"] / 1e6
            if s["fullHostSubmitUpperBoundNanos"] is not None else None for s in samples]),
        "hostWorkPlusCallbacksAllocatedBytes": summarize([s["hostWorkPlusCallbacksAllocatedBytes"] for s in samples]),
        "hostInclusiveAllocatedBytesUpperBound": summarize([s["hostInclusiveAllocatedBytesUpperBound"] for s in samples]),
        "cpuStageOutsideHostWorkByStageNanos": dict(sorted(uncovered_by_stage.items())),
        "stageBreakdownNote": "Per-stage uncovered intervals can overlap; do not sum across stage labels. Gap bytes unknown.",
        "loop0": {"allRecordCount": len(loop_zero), "allOccupiedNanos": length([interval(v) for v in loop_zero]),
                  "allAllocations": allocations(loop_zero),
                  "retainedSpan": retained_span, "retainedRecordCount": len(zero_retained),
                  "retainedOccupiedNanos": length(zero_spans),
                  "retainedAllocations": allocations(zero_retained, retained_span),
                  "records": [{"work": v["work"], "startedNanos": interval(v)[0],
                               "endedNanos": interval(v)[1], "allocatedBytes": v["allocatedBytes"],
                               "threadCpuNanos": nanos(v["threadCpuNanos"])} for v in loop_zero]},
        "loops": samples,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path, help="JFR file or unfiltered jfr print --json export")
    parser.add_argument("--jfr", default=shutil.which("jfr"))
    parser.add_argument("--trim-loops", type=int, default=1)
    parser.add_argument("--thread-id", type=int)
    parser.add_argument("--rt-only", action="store_true")
    parser.add_argument("--window", choices=("loop", "next-start"), default="loop",
                        help="next-start includes between-loop work; requires consecutive completed loop IDs")
    parser.add_argument("--output", type=Path, help="Write analysis JSON here instead of stdout")
    args = parser.parse_args()
    if args.recording.suffix.lower() == ".json":
        data = json.loads(args.recording.read_text(encoding="utf-8-sig"))
    else:
        if not args.jfr:
            parser.error("jfr executable not found; pass --jfr PATH or exported JSON")
        exported = subprocess.run([args.jfr, "print", "--json", "--events",
                                   "dev.comfyfluffy.caustica.*", str(args.recording)],
                                  check=True, capture_output=True, text=True, encoding="utf-8")
        data = json.loads(exported.stdout)
    try:
        report = analyze(data["recording"]["events"], args.trim_loops, args.thread_id, args.rt_only, args.window)
    except ValueError as error:
        parser.error(str(error))
    rendered = json.dumps(report, indent=2, allow_nan=False)
    if args.output:
        args.output.write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)


if __name__ == "__main__":
    main()
