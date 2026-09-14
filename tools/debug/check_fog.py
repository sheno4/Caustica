"""Capture fog camera/motion regressions and isolated JFR intervals in a selected copied world."""

import argparse
import json
import math
from pathlib import Path
import subprocess

from caustica_debug import Client

FEATURE = "caustica:minecraft"
MODES = {"off": {"fog.enabled": False},
         "post": {"fog.enabled": True, "fog.mode": "POST_PROCESS"},
         "path": {"fog.enabled": True, "fog.mode": "PATH_TRACED"}}
BUFFERS = ["primary-depth", "depth", "trace-color", "reconstructed-color", "scene-color"]
EVENTS = ["Frame", "CpuStage", "GpuStage", "FrameCounter", "Exposure"]


def validate_world(status, copied_world):
    if not status["ready"] or status["paused"] or not status["frameActive"]:
        raise RuntimeError("Open and unpause the copied world with RT active")
    if status["world"].get("directory") != copied_world:
        raise RuntimeError(f"Expected copied world {copied_world!r}; got {status['world']}")


def distance(a, b):
    return math.sqrt(sum((a[key] - b[key]) ** 2 for key in ("x", "y", "z")))


def run(args):
    checkout = Path(__file__).resolve().parents[2]
    revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=checkout,
                              check=True, capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--short"], cwd=checkout,
                           check=True, capture_output=True, text=True).stdout
    client = Client(args.session, args.timeout)
    initial = client.call("status")
    validate_world(initial, args.copied_world)
    fog = client.call("settings.get", feature=FEATURE)
    saved_fog = {key: entry["value"] for key, entry in fog.items() if key.startswith("fog.")}
    # A baseline binary can run off/post cases before the path mode is installed.
    for mode in args.modes:
        if mode == "path" and "fog.mode" not in saved_fog:
            raise RuntimeError("This client does not expose fog.mode; use --modes off post for baseline")
    def command(text):
        return client.call("command", command=text)["result"]

    advance = bool(command("gamerule minecraft:advance_time"))
    # The default clock query returns total ticks; timeline queries return ticks within that timeline.
    daytime = command("time query time")
    gamemode = command("data get entity @s playerGameType")
    original = initial["player"]
    saved_view = initial["settings"]["composite.debug-view"]["value"]
    manifest = {"revision": revision, "workingTree": dirty, "initial": initial,
                "arguments": {**vars(args), "output": str(args.output)},
                "savedFog": saved_fog, "savedDaytime": daytime,
                "savedAdvanceTime": advance, "savedGamemode": gamemode, "samples": [],
                "notes": ["Revision describes checkout, not proof of the loaded binary.",
                          "Readbacks occur outside JFR. Moving captures are sparse observations.",
                          "PNG and raw bundle are different frames. Raw bundle is same-frame.",
                          "Warmup is an observation interval, not a terrain completion guarantee."]}
    args.output.mkdir(parents=True, exist_ok=True)

    def save():
        (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    def wait(frames):
        client.call("wait", frames=frames, timeoutMs=int(args.timeout * 1000))

    def pose(pitch=None, yaw=None):
        command(f"tp @s {original['x']} {original['y']} {original['z']} "
                f"{original['yaw'] if yaw is None else yaw} {original['pitch'] if pitch is None else pitch}")

    def capture(label):
        sample = {"label": label, "before": client.call("status")}
        manifest["samples"].append(sample)
        save()
        sample["raw"] = client.call("image.capture", names=BUFFERS)
        sample["screenshot"] = client.call("screenshot")
        sample["after"] = client.call("status")
        save()
        print(label, flush=True)

    def record(label, workload):
        sample = {"label": label, "before": client.call("status"), "events": EVENTS}
        manifest["samples"].append(sample)
        save()
        client.call("jfr.start", events=EVENTS)
        try:
            workload()
            sample["after"] = client.call("status")
        finally:
            try:
                sample["recording"] = client.call("jfr.stop")
            finally:
                save()

    def flight():
        start = client.call("status")["player"]
        client.call("input.set", forward=True, sprint=True, flyingSpeed=0.2)
        try:
            # The bound prevents an obstructed/nonmoving route becoming an endless run.
            for _ in range(60):
                client.call("wait", ticks=10, timeoutMs=int(args.timeout * 1000))
                if distance(start, client.call("status")["player"]) >= args.flight_distance:
                    return
            raise RuntimeError("Flight did not reach the requested distance in 600 ticks")
        finally:
            client.call("input.set", flyingSpeed=original["currentFlyingSpeed"])

    try:
        client.call("input.set")
        client.call("view.set", name="off")
        command("gamemode spectator")
        command("gamerule minecraft:advance_time false")
        if args.time is not None:
            command(f"time set {args.time}")
        for mode in args.modes:
            values = {**MODES[mode], "fog.debug": 0}
            if "fog.mode" not in saved_fog:
                values.pop("fog.mode", None)
            client.call("settings.set", feature=FEATURE, values=values)
            pose()
            wait(args.warmup)
            if "stationary" in args.cases:
                record(f"{mode}-saved-performance", lambda: wait(args.frames))
                for index in range(args.repeats):
                    capture(f"{mode}-saved-repeat-{index}")
                    wait(2)
            if "pitch" in args.cases:
                for pitch in (0, 45, 89, -45, -89):
                    pose(pitch=pitch)
                    wait(args.warmup)
                    record(f"{mode}-pitch-{pitch}-performance", lambda: wait(args.frames))
                    capture(f"{mode}-pitch-{pitch}")
            if "yaw" in args.cases:
                pose(pitch=0)
                wait(args.warmup)
                client.call("input.set", turnDegreesPerSecond=args.turn)
                try:
                    for index in range(args.repeats):
                        wait(2)
                        capture(f"{mode}-moving-yaw-{index}")
                finally:
                    client.call("input.set")
                wait(args.warmup)
                capture(f"{mode}-yaw-settled")
            if "flight" in args.cases:
                pose(pitch=0)
                wait(args.warmup)
                record(f"{mode}-flight-performance", flight)
                capture(f"{mode}-flight-end")
                pose()
                capture(f"{mode}-return-early")
                wait(args.warmup)
                record(f"{mode}-return-warm-performance", lambda: wait(args.frames))
                capture(f"{mode}-return-warm")
                wait(args.settle)
                record(f"{mode}-return-settled-performance", lambda: wait(args.frames))
                capture(f"{mode}-return-settled")
    finally:
        # Attempt each independent restore even if another request fails.
        cleanup = [("input.set", {"flyingSpeed": original["currentFlyingSpeed"]}),
                   ("settings.set", {"feature": FEATURE, "values": saved_fog}),
                   ("settings.set", {"values": {"composite.debug-view": saved_view}}),
                   ("command", {"command": f"time set {daytime}"}),
                   ("command", {"command": "gamerule minecraft:advance_time " + str(advance).lower()}),
                   ("command", {"command": f"tp @s {original['x']} {original['y']} {original['z']} {original['yaw']} {original['pitch']}"}),
                   ("command", {"command": "gamemode " + ["survival", "creative", "adventure", "spectator"][gamemode]})]
        manifest["cleanupErrors"] = []
        for op, arguments in cleanup:
            try:
                client.call(op, **arguments)
            except Exception as error:
                manifest["cleanupErrors"].append({"op": op, "error": str(error)})
        try:
            manifest["final"] = client.call("status")
        finally:
            save()
        if manifest["cleanupErrors"]:
            raise RuntimeError(f"Cleanup failed: {manifest['cleanupErrors']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--session", default="run/caustica-debug/session.json")
    parser.add_argument("--copied-world", required=True, help="Exact save directory name of the disposable copied world")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--modes", nargs="+", choices=list(MODES), default=list(MODES))
    parser.add_argument("--cases", nargs="+", choices=["stationary", "pitch", "yaw", "flight"],
                        default=["stationary", "pitch", "yaw", "flight"])
    parser.add_argument("--warmup", type=int, default=180)
    parser.add_argument("--settle", type=int, default=1200)
    parser.add_argument("--frames", type=int, default=300)
    parser.add_argument("--repeats", type=int, default=4)
    parser.add_argument("--flight-distance", type=float, default=768)
    parser.add_argument("--turn", type=float, default=12)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--time", type=int, help="Optional absolute daytime; omission freezes current daytime")
    args = parser.parse_args()
    if min(args.warmup, args.settle, args.frames, args.repeats) < 1:
        parser.error("frame counts and repeats must be positive")
    if not math.isfinite(args.flight_distance) or not 512 < args.flight_distance <= 2000:
        parser.error("flight distance must exceed 512 and be at most 2000 blocks")
    if not math.isfinite(args.turn) or not 0 < abs(args.turn) <= 360:
        parser.error("turn must be nonzero and at most 360 degrees/second")
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        parser.error("timeout must be finite and positive")
    run(args)


if __name__ == "__main__":
    main()
