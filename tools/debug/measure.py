"""Warm up, record a frame interval, and save paths/settings for an external comparison."""

import argparse
import json
from pathlib import Path
import subprocess

from caustica_debug import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", default="run/caustica-debug/session.json")
    parser.add_argument("--warmup", type=int, default=120)
    parser.add_argument("--frames", type=int, default=300)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--events", default="Frame,CpuStage,FrameCounter,GpuStage,Exposure",
                        help="Comma-separated Caustica event names; add TerrainState/TerrainJob for streaming diagnosis")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.warmup < 0 or args.frames < 1:
        parser.error("warmup must be nonnegative and frames positive")
    client = Client(args.session, args.timeout)
    if args.warmup:
        client.call("wait", frames=args.warmup, timeoutMs=int(args.timeout * 1000))
    result = {"start": client.call("status"), "requestedFrames": args.frames}
    revision = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True)
    result["revision"] = revision.stdout.strip()
    result["workingTree"] = subprocess.run(["git", "status", "--short"], capture_output=True, text=True).stdout
    result["events"] = args.events.split(",")
    client.call("jfr.start", events=result["events"])
    try:
        result["intervalEnd"] = client.call("wait", frames=args.frames, timeoutMs=int(args.timeout * 1000))
        result["end"] = client.call("status")
    finally:
        result["recording"] = client.call("jfr.stop")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result["recording"], indent=2))


if __name__ == "__main__":
    main()
