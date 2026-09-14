"""Join completion-owned shadow diagnostics to source-frame GPU timing from raw JFR JSON."""
import argparse
from collections import defaultdict
import json
from pathlib import Path

from analyze_recording import milliseconds, summarize

MAXIMA = ("maxShadowRestartsAbove32", "maxQueryProceedAbove256", "maxShadowProceedAbove512")
COUNTS = ("unchangedOriginEvents", "repeatedAcceptedPrimitiveEvents")
ANOMALY = ("firstAnomalyPixelX", "firstAnomalyPixelY", "firstAnomalyGeometryRecord",
           "firstAnomalyPrimitive", "firstAnomalyRestarts", "firstAnomalyProceedSum", "firstAnomalyFlags")
PASS_STAGE = {"fill stable planes": "fill stable planes", "material visibility": "scene effects"}


def analyze(events):
    timings = defaultdict(lambda: defaultdict(list))
    groups = defaultdict(list)
    for event in events:
        value = event["values"]
        if event["type"] == "dev.comfyfluffy.caustica.GpuStage":
            timings[value["frameId"]][value["stage"]].append(milliseconds(value["elapsedNanos"]))
        elif event["type"] == "dev.comfyfluffy.caustica.ShadowTraversal":
            groups[(value["frameId"], value["pass"])].append(value)
    frames = []
    by_pass = defaultdict(list)
    for (frame_id, pass_name), dispatches in sorted(groups.items()):
        maxima = {key: max(value[key] for value in dispatches) for key in MAXIMA}
        counts = {key: sum(value[key] for value in dispatches) for key in COUNTS}
        # Source IDs join the event even when completion emits it after later CPU frames.
        gpu = {stage: sum(values) for stage, values in timings[frame_id].items()}
        record = {"frameId": frame_id, "pass": pass_name, "dispatchCount": len(dispatches),
                  **maxima, **counts, "gpuStagesMs": gpu,
                  "stageEnvelopeMs": gpu.get(PASS_STAGE[pass_name]),
                  "dispatches": [{"ordinal": i, **{key: value[key] for key in MAXIMA + COUNTS},
                                  "firstAnomaly": {key: value[key] for key in ANOMALY}
                                  if value["firstAnomalyFlags"] else None}
                                 for i, value in enumerate(dispatches)]}
        frames.append(record)
        by_pass[pass_name].append(record)
    summary = {}
    for pass_name, records in sorted(by_pass.items()):
        summary[pass_name] = {"frames": len(records),
                             "dispatches": sum(record["dispatchCount"] for record in records),
                             "thresholdFrames": sum(any(record[key] for key in MAXIMA) for record in records),
                             "anomalyFrames": sum(any(record[key] for key in COUNTS) for record in records),
                             **{key: max(record[key] for record in records) for key in MAXIMA},
                             **{key: sum(record[key] for record in records) for key in COUNTS},
                             "stageEnvelopeMs": summarize([record["stageEnvelopeMs"] for record in records
                                                           if record["stageEnvelopeMs"] is not None])}
    return {"interpretation": "Zero maxima mean no threshold exceedance, not no traversal. "
            "Material visibility timing is the whole scene-effects envelope, not individual dispatch timing. "
            "Dispatch ordinal is recording order, not GPU batch identity.",
            "thresholds": {MAXIMA[0]: 32, MAXIMA[1]: 256, MAXIMA[2]: 512},
            "summary": summary, "frames": frames}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path, help="JSON written by analyze_recording.py --raw")
    parser.add_argument("--output", type=Path, help="Write report here instead of stdout")
    args = parser.parse_args()
    result = analyze(json.loads(args.raw.read_text())["recording"]["events"])
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
