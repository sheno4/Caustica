"""Local PaperTracker OSC/UDP gaze publisher simulator for testing dynamic foveation without eye-tracking hardware."""

from __future__ import annotations

import argparse
import math
import socket
import struct
import time

SUBSCRIBE_MSG = b"/papertracker/gaze/v1/subscribe\x00,\x00\x00\x00"
UNSUBSCRIBE_MSG = b"/papertracker/gaze/v1/unsubscribe\x00\x00\x00,\x00\x00\x00"
DATA_HEADER = b"/papertracker/gaze/v1\x00\x00\x00,iiiffffff\x00\x00"

FLAG_LEFT_VALID = 1 << 0
FLAG_RIGHT_VALID = 1 << 1
FLAG_FUSED_VALID = 1 << 2
FLAG_ALL_MEASURED = (1 << 3) | (1 << 4) | (1 << 5)
LEASE_SECONDS = 3.0
MAX_SUBSCRIBERS = 16


def encode_packet(
    sequence: int,
    flags: int,
    sample_age_us: int,
    left_yaw: float,
    left_pitch: float,
    right_yaw: float,
    right_pitch: float,
    fused_yaw: float,
    fused_pitch: float,
) -> bytes:
    payload = struct.pack(
        ">iiiffffff",
        sequence,
        flags,
        sample_age_us,
        left_yaw,
        left_pitch,
        right_yaw,
        right_pitch,
        fused_yaw,
        fused_pitch,
    )
    return DATA_HEADER + payload


def sample_pattern(pattern: str, t: float, amplitude: float) -> tuple[float, float, int]:
    if pattern == "hold":
        return 0.0, 0.0, FLAG_LEFT_VALID | FLAG_RIGHT_VALID | FLAG_FUSED_VALID | FLAG_ALL_MEASURED
    if pattern == "circle":
        ang = t * 1.5
        return (
            amplitude * math.cos(ang),
            amplitude * math.sin(ang),
            FLAG_LEFT_VALID | FLAG_RIGHT_VALID | FLAG_FUSED_VALID | FLAG_ALL_MEASURED,
        )
    if pattern == "figure8":
        ang = t * 1.2
        return (
            amplitude * math.sin(ang),
            0.6 * amplitude * math.sin(2.0 * ang),
            FLAG_LEFT_VALID | FLAG_RIGHT_VALID | FLAG_FUSED_VALID | FLAG_ALL_MEASURED,
        )
    if pattern == "saccade":
        step = int(t / 1.5) % 5
        targets = [(0.0, 0.0), (amplitude, 0.0), (-amplitude, 0.0), (0.0, amplitude * 0.7), (0.0, -amplitude * 0.7)]
        yaw, pitch = targets[step]
        return yaw, pitch, FLAG_LEFT_VALID | FLAG_RIGHT_VALID | FLAG_FUSED_VALID | FLAG_ALL_MEASURED
    if pattern == "drop":
        phase = t % 6.0
        if phase < 3.0:
            return amplitude * 0.5, 0.0, FLAG_LEFT_VALID | FLAG_RIGHT_VALID | FLAG_FUSED_VALID | FLAG_ALL_MEASURED
        return 0.0, 0.0, 0
    raise ValueError(f"Unknown pattern: {pattern}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Mock PaperTracker OSC/UDP gaze publisher")
    parser.add_argument("--port", type=int, default=9945, help="UDP port to listen on (default: 9945)")
    parser.add_argument("--hz", type=float, default=90.0, help="Publish rate in Hz (default: 90)")
    parser.add_argument(
        "--pattern",
        choices=["circle", "saccade", "figure8", "hold", "drop"],
        default="circle",
        help="Gaze trajectory pattern",
    )
    parser.add_argument("--amplitude", type=float, default=20.0, help="Peak angle in degrees (default: 20)")
    parser.add_argument(
        "--dropout",
        action="store_true",
        help="Simulate a 2-second packet dropout every 8 seconds to test stale/lost fallback",
    )
    args = parser.parse_args()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("127.0.0.1", args.port))
    sock.setblocking(False)
    print(f"Mock PaperTracker listening on 127.0.0.1:{args.port} ({args.pattern}, {args.hz} Hz, +/-{args.amplitude} deg)")

    subscribers: dict[tuple[str, int], float] = {}
    sequence = 0
    interval = 1.0 / max(args.hz, 1.0)
    start = time.monotonic()
    next_send = start

    try:
        while True:
            now = time.monotonic()
            while True:
                try:
                    data, addr = sock.recvfrom(256)
                except BlockingIOError:
                    break
                if data == SUBSCRIBE_MSG:
                    if addr in subscribers or len(subscribers) < MAX_SUBSCRIBERS:
                        subscribers[addr] = now + LEASE_SECONDS
                elif data == UNSUBSCRIBE_MSG:
                    subscribers.pop(addr, None)

            expired = [addr for addr, deadline in subscribers.items() if deadline < now]
            for addr in expired:
                del subscribers[addr]

            if now >= next_send:
                next_send = max(next_send + interval, now)
                elapsed = now - start
                if not (args.dropout and (elapsed % 8.0) >= 6.0):
                    yaw, pitch, flags = sample_pattern(args.pattern, elapsed, args.amplitude)
                    sequence += 1
                    pkt = encode_packet(sequence, flags, 500, yaw, pitch, yaw, pitch, yaw, pitch)
                    for addr in list(subscribers.keys()):
                        sock.sendto(pkt, addr)

            time.sleep(0.002)
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()


if __name__ == "__main__":
    main()
