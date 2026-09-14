"""Summarize check_fog captures and make a labeled PNG contact sheet; no timing from readbacks."""

import argparse
import json
from pathlib import Path

import numpy as np


def statistics(values):
    finite = values[np.isfinite(values)]
    result = {"samples": int(values.size), "nonfinite": int(values.size - finite.size)}
    if finite.size:
        result.update(min=float(finite.min()), max=float(finite.max()), mean=float(finite.mean()),
                      percentiles=np.percentile(finite, [1, 50, 99]).tolist())
    return result


def shaft_false_dark(delta_scattering, lit_mask):
    """Near-zero response inside a precomputed geometric lit mask, separate from placement."""
    if delta_scattering.shape != (*lit_mask.shape, 3):
        raise ValueError("Scattering RGB and lit mask must have matching dimensions")
    response = delta_scattering[lit_mask].mean(axis=-1)
    if not response.size or not np.isfinite(response).all():
        raise ValueError("Require nonempty finite lit response")
    mean = float(response.mean())
    threshold = .01 * mean
    applicable = mean > 0
    count = int(np.count_nonzero(response < threshold)) if applicable else None
    return {"litPixels": int(response.size), "meanLitDelta": mean,
            "thresholdFractionOfMean": .01, "threshold": threshold,
            "responsePresent": applicable, "falseDarkPixels": count,
            "falseDarkFraction": count / response.size if applicable else None}


def stationary_difference(first, second, first_depth, second_depth):
    """Compare native-resolution radiance only where the physical depth stays stable."""
    if first.shape != second.shape or first_depth.shape != first.shape[:2] or second_depth.shape != first_depth.shape:
        raise ValueError("Radiance and depth must have matching native dimensions")
    valid = np.isfinite(first_depth) & np.isfinite(second_depth)
    valid &= np.abs(first_depth - second_depth) <= np.maximum(1e-8, np.abs(first_depth) * 0.001)
    # Both sky samples have zero depth. Sky/geometry transitions are excluded.
    valid &= (first_depth > 0) == (second_depth > 0)
    valid &= np.all(np.isfinite(first) & np.isfinite(second), axis=-1)
    difference = statistics(np.abs(first[valid] - second[valid]))
    reference_mean = float(np.abs(first[valid]).mean()) if valid.any() else 0.0
    return {"stableDepthPixels": int(valid.sum()), "absoluteDifference": difference,
            "referenceMeanAbsoluteRadiance": reference_mean,
            "meanAbsoluteDifferenceRelativeToReference": difference["mean"] / reference_mean
            if reference_mean > 0 else None}


def analyze(directory):
    import OpenEXR
    from PIL import Image, ImageDraw

    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    report = {"samples": {}, "notes": ["Radiance is divided by each capture's preExposure.",
              "Raw beauty pixels alone do not establish fog causality.",
              "No pixel differences are computed across moving cameras or different modes."]}
    tiles = []
    stationary_reference = {}
    for sample in manifest["samples"]:
        if "raw" not in sample:
            continue
        result = {}
        native = {}
        for capture in sample["raw"]["images"]:
            meta = capture["metadata"]
            with OpenEXR.File(capture["path"]) as image:
                pixels = image.channels()["RGBA"].pixels.astype(np.float32)
            values = pixels[..., :3] / meta["preExposure"] if meta["name"] in {
                "trace-color", "reconstructed-color", "scene-color"} else pixels[..., 0]
            result[meta["name"]] = {"metadata": meta, "statistics": statistics(values)}
            if meta["name"] in {"trace-color", "primary-depth"}:
                native[meta["name"]] = values
        if "-saved-repeat-" in sample["label"]:
            mode = sample["label"].split("-saved-repeat-")[0]
            if mode in stationary_reference:
                reference = stationary_reference[mode]
                result["stationaryTraceDifference"] = stationary_difference(
                    reference["trace-color"], native["trace-color"],
                    reference["primary-depth"], native["primary-depth"])
            else:
                stationary_reference[mode] = native
        report["samples"][sample["label"]] = result
        if "screenshot" in sample:
            with Image.open(sample["screenshot"]["path"]) as image:
                preview = image.convert("RGB")
                preview.thumbnail((384, 216))
                tiles.append((sample["label"], preview.copy()))
    if tiles:
        sheet = Image.new("RGB", (384 * 4, 244 * ((len(tiles) + 3) // 4)), (20, 20, 20))
        draw = ImageDraw.Draw(sheet)
        for index, (label, preview) in enumerate(tiles):
            x, y = (index % 4) * 384, (index // 4) * 244
            draw.text((x + 4, y + 6), label, fill="white")
            sheet.paste(preview, (x, y + 28))
        sheet.save(directory / "contact.png")
    (directory / "image-summary.json").write_text(json.dumps(report, indent=2, allow_nan=False), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    analyze(parser.parse_args().directory)
