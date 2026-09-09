"""Inspect a raw EXR capture. Run with uv run python; workspace dependencies include NumPy, OpenEXR and Pillow."""

import argparse
import json
from pathlib import Path

import numpy as np
import OpenEXR


def inspect(path):
    with OpenEXR.File(str(path)) as image:
        # The decoded NumPy array owns its storage beyond the file context.
        pixels = image.channels()["RGBA"].pixels
        metadata = {key: value for key, value in image.header().items() if key.startswith("caustica")}
    channels = {}
    for index, name in enumerate("RGBA"):
        values = pixels[:, :, index]
        finite = values[np.isfinite(values)]
        channels[name] = {"nonfinite": int(values.size - finite.size)}
        if finite.size:
            channels[name].update(min=float(finite.min()), max=float(finite.max()), mean=float(finite.mean()))
    result = {"path": str(path), "shape": list(pixels.shape), "metadata": metadata, "channels": channels}
    if metadata.get("causticaBuffer") == "normal-roughness":
        lengths = np.linalg.norm(pixels[:, :, :3], axis=2)
        nonzero = lengths[np.isfinite(lengths) & (lengths > 0)]
        if nonzero.size:
            result["normalLengthPercentiles"] = np.percentile(nonzero, [0, 50, 99, 100]).tolist()
        result["equalRgbPixels"] = int(np.count_nonzero(
            (pixels[:, :, 0] == pixels[:, :, 1]) & (pixels[:, :, 1] == pixels[:, :, 2]) & (lengths > 0)))
    return result, pixels


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--preview", type=Path)
    parser.add_argument("--view", choices=["normal", "rgb", "r", "alpha"], default="rgb")
    parser.add_argument("--scale", type=float, default=1.0, help="Preview multiplier; raw statistics are unchanged")
    args = parser.parse_args()
    result, pixels = inspect(args.image)
    print(json.dumps(result, indent=2, allow_nan=False))
    if args.preview:
        from PIL import Image
        display = pixels[:, :, :3] * args.scale
        if args.view == "normal":
            display = display * .5 + .5
        elif args.view in {"r", "alpha"}:
            component = 0 if args.view == "r" else 3
            display = np.repeat(pixels[:, :, component:component + 1], 3, axis=2) * args.scale
        display = np.nan_to_num(display, nan=1, posinf=1, neginf=0)
        Image.fromarray((np.clip(display, 0, 1) * 255).astype(np.uint8)).save(args.preview)


if __name__ == "__main__":
    main()
