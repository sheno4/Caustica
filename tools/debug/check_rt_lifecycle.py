"""Exercise RT activation repeatedly in a running, unpaused debug world."""

import argparse
import json
import time
from pathlib import Path

from caustica_debug import Client


def await_runtime(client, enabled, timeout):
    deadline = time.monotonic() + timeout
    counter = "frames" if enabled else "ticks"
    settle_count = 30 if enabled else 20
    first_count = None
    while time.monotonic() < deadline:
        status = client.call("status")
        runtime = status["runtime"]
        if runtime["active"] == enabled and runtime["frameActive"] == enabled:
            if first_count is None:
                first_count = status[counter]
            elif status[counter] >= first_count + settle_count:
                return status
        else:
            first_count = None
        time.sleep(0.25)
    raise TimeoutError(f"RT did not settle to enabled={enabled}: {status['runtime']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cycles", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--output", type=Path, default=Path("tmp/rt-lifecycle.json"))
    args = parser.parse_args()
    client = Client()
    initial = client.call("status")
    if not initial["ready"] or initial["paused"]:
        raise RuntimeError("Open and unpause a world before running this check")
    observations = []
    try:
        for cycle in range(args.cycles):
            for enabled in (False, True):
                client.call("runtime.set", enabled=enabled)
                status = await_runtime(client, enabled, args.timeout)
                capture = client.call("screenshot")
                observations.append({"cycle": cycle + 1, "enabled": enabled,
                                     "status": status, "capture": capture})
                print(f"Cycle {cycle + 1}: enabled={enabled}, frame={status['frames']}", flush=True)
    finally:
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(observations, indent=2), encoding="utf-8")
        finally:
            client.call("runtime.set", enabled=initial["runtime"]["requested"])
            await_runtime(client, initial["runtime"]["requested"], args.timeout)


if __name__ == "__main__":
    main()
