"""Analyze complete Caustica JFR data externally; the recorder does no statistical filtering."""

import argparse
from collections import Counter, defaultdict
import json
import math
import re
from pathlib import Path
import shutil
import subprocess


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lo = math.floor(position)
    hi = math.ceil(position)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (position - lo)


def summarize(values):
    finite = [value for value in values if isinstance(value, (float, int)) and math.isfinite(value)]
    if not finite:
        return {"samples": len(values), "finiteSamples": 0}
    return {"samples": len(values), "finiteSamples": len(finite), "min": min(finite),
            "mean": sum(finite) / len(finite), "p50": percentile(finite, .5),
            "p95": percentile(finite, .95), "p99": percentile(finite, .99), "max": max(finite)}


def milliseconds(value):
    if isinstance(value, (int, float)):
        return value / 1e6
    # JDK jfr print serializes @Timespan fields as ISO-8601 durations.
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value)
    if not match:
        raise ValueError(f"Unsupported JFR duration: {value}")
    hours, minutes, seconds = (float(part or 0) for part in match.groups())
    return (hours * 3600 + minutes * 60 + seconds) * 1000


def analyze(events):
    counts = Counter()
    fields = defaultdict(list)
    exposures = []
    for event in events:
        name = event["type"].rsplit(".", 1)[-1]
        counts[name] += 1
        values = event["values"]
        group = name + ("." + str(values["stage"]) if "stage" in values else "")
        if "counter" in values:
            group += "." + values["counter"]
        for key, value in values.items():
            if isinstance(value, (int, float)) and not isinstance(value, bool) and key not in {
                "frameId", "observedFrameId", "sampleId", "extractionFrameId", "resetSequence"
            } and not key.endswith("Nanos"):
                fields[group + "." + key].append(value)
            elif key in {"elapsedNanos", "totalNanos", "durationNanos"}:
                fields[group + "." + key.removesuffix("Nanos") + "Ms"].append(milliseconds(value))
        if name == "Exposure":
            exposures.append(values)
    exposures.sort(key=lambda item: item["frameId"])
    steps = []
    for before, after in zip(exposures, exposures[1:]):
        a, b = before["absoluteExposure"], after["absoluteExposure"]
        if (after["frameId"] == before["frameId"] + 1 and before["mode"] == after["mode"]
                and before.get("resetSequence") == after.get("resetSequence")
                and a > 0 and b > 0 and math.isfinite(a) and math.isfinite(b)):
            steps.append({"frameId": after["frameId"], "deltaStops": math.log2(b / a)})
    return {"eventCounts": dict(counts),
            "fields": {name: summarize(values) for name, values in sorted(fields.items())},
            "largestExposureSteps": sorted(steps, key=lambda item: abs(item["deltaStops"]), reverse=True)[:20]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--jfr", default=shutil.which("jfr"), help="JDK jfr executable")
    parser.add_argument("--raw", type=Path, help="Also save every exported event as JSON")
    args = parser.parse_args()
    if args.recording.suffix.lower() == ".json":
        data = json.loads(args.recording.read_text(encoding="utf-8"))
    else:
        if not args.jfr:
            parser.error("jfr executable not found; use --jfr PATH or pass exported JSON")
        output = subprocess.run([args.jfr, "print", "--json", "--events",
                                 "dev.comfyfluffy.caustica.*", str(args.recording)],
                                check=True, capture_output=True, text=True, encoding="utf-8")
        data = json.loads(output.stdout)
    if args.raw:
        args.raw.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(json.dumps(analyze(data["recording"]["events"]), indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
