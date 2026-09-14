"""Write an analytic noon roof-slit plan and expected illuminated path-length mask; no client access."""

import argparse
import json
from pathlib import Path

import numpy as np


def box_interval(origin, directions, lower, upper):
    """Intersect normalized camera rays with an axis-aligned box in the supplied coordinates."""
    entry = np.zeros(directions.shape[:-1])
    exit = np.full(directions.shape[:-1], np.inf)
    for axis in range(3):
        component = directions[..., axis]
        parallel = component == 0
        first = np.divide(lower[axis] - origin[axis], component,
                          out=np.full_like(component, -np.inf), where=~parallel)
        second = np.divide(upper[axis] - origin[axis], component,
                           out=np.full_like(component, np.inf), where=~parallel)
        entry = np.maximum(entry, np.minimum(first, second))
        exit = np.minimum(exit, np.maximum(first, second))
        if not lower[axis] <= origin[axis] <= upper[axis]:
            exit = np.where(parallel, -np.inf, exit)
    return entry, exit


def captured_rays(projection, width, height, primary_depth=False):
    """World-axis rays for top-down exported pixels; post output has no trace jitter."""
    yy, xx = np.mgrid[:height, :width]
    jx, jy = projection["jitterPixels"] if primary_depth else (0, 0)
    if primary_depth and (width, height) != (projection["renderWidth"], projection["renderHeight"]):
        raise ValueError("Primary-depth dimensions must equal the captured native trace extent")
    clip = np.stack([2 * (xx + .5 + jx) / width - 1,
                     1 - 2 * (yy + .5 - jy) / height,
                     np.full((height, width), .0001), np.ones((height, width))], axis=-1)
    matrix = np.asarray(projection["inverseProjectionView"]).reshape(4, 4, order="F")
    point = clip @ matrix.T
    rays = point[..., :3] / point[..., 3:4]
    return rays / np.linalg.norm(rays, axis=-1, keepdims=True)


def fixture_endpoints(eye, rays):
    """Known open-fixture first hit and whether filling the slit leaves that hit unchanged."""
    endpoint = np.full(rays.shape[:-1], np.inf)
    for lower, upper in [([2, 8, 2], [8, 9, 15]), ([10, 8, 2], [15, 9, 15]),
                         ([2, 1, 14], [15, 9, 15]), ([0, 0, 0], [17, 1, 17])]:
        start, end = box_interval(eye, rays, lower, upper)
        endpoint = np.minimum(endpoint, np.where(end >= start, start, np.inf))
    start, end = box_interval(eye, rays, [8, 8, 2], [10, 9, 14])
    closure = np.where(end >= start, start, np.inf)
    return endpoint, np.isfinite(endpoint) & (closure >= endpoint - 1e-7)


def expected_lengths(width, height, vertical_fov=None, camera_x=4.0, *, projection=None, fixture_origin=None):
    eye = np.array([camera_x, 4.5, 0.5])
    yy, xx = np.mgrid[:height, :width]
    tangent = np.tan(np.deg2rad(vertical_fov or 70) * 0.5)
    # Minecraft yaw zero faces +Z; screen right is -X.
    rays = np.stack([-(2 * (xx + 0.5) / width - 1) * tangent * width / height,
                     -(2 * (yy + 0.5) / height - 1) * tangent,
                     np.ones((height, width))], axis=-1)
    rays /= np.linalg.norm(rays, axis=-1, keepdims=True)
    if projection is not None:
        eye = np.asarray(projection["cameraWorld"]) - np.asarray(fixture_origin)
        rays = captured_rays(projection, width, height)
    endpoint, common_endpoint = fixture_endpoints(eye, rays)
    slope = np.tan(np.deg2rad(30.0))
    transformed_eye = eye.copy()
    transformed_eye[2] -= slope * eye[1]
    transformed_rays = rays.copy()
    transformed_rays[..., 2] -= slope * rays[..., 1]
    start, end = box_interval(transformed_eye, transformed_rays,
                              [8, 1, 2 - 8 * slope], [10, 8, 14 - 9 * slope])
    known_endpoint = common_endpoint
    length = np.maximum(0, np.minimum(end, endpoint) - start)
    return np.where(known_endpoint, length, 0), known_endpoint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True, help="JSON recipe written by fog_fixtures.py")
    parser.add_argument("--capture-metadata", type=Path, help="Capture-image JSON response or metadata object containing projection")
    parser.add_argument("--vertical-fov", type=float, help="Actual settled camera vertical FOV in degrees")
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--camera-x", type=float, default=4.0, help="Camera X relative to the roof origin; use 4.25 for the lateral repeat")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if (args.capture_metadata is None and (args.vertical_fov is None or not 1 < args.vertical_fov < 179)) or min(args.width, args.height) < 1 or not np.isfinite(args.camera_x):
        parser.error("Provide positive dimensions and a finite FOV between 1 and 179 degrees")
    recipe = json.loads(args.fixtures.read_text(encoding="utf-8"))
    fixture = next(item for item in recipe["fixtures"] if item["name"] == "roof-slit")
    x, y, z = fixture["bounds"][:3]
    projection = None
    if args.capture_metadata:
        metadata = json.loads(args.capture_metadata.read_text(encoding="utf-8"))
        metadata = metadata.get("metadata", metadata)
        projection = metadata["projection"]
        args.width, args.height = metadata["width"], metadata["height"]
    length, known = expected_lengths(args.width, args.height, args.vertical_fov, args.camera_x,
                                     projection=projection, fixture_origin=[x, y, z])
    args.output.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(args.output / "expected-shaft.npz", illuminatedLength=length,
                        commonFixtureEndpoint=known)
    from PIL import Image
    preview = np.zeros((args.height, args.width, 3), dtype=np.uint8)
    preview[..., 0] = np.where(known, 0, 100)
    preview[..., 1] = np.clip(length / 14 * 255, 0, 255).astype(np.uint8)
    Image.fromarray(preview).save(args.output / "expected-shaft.png")
    plan = {
        "copiedWorld": recipe["copiedWorld"], "fixtureOrigin": [x, y, z],
        "cameraEye": [x + args.camera_x, y + 4.5, z + 0.5],
        "playerTeleport": f"tp @s {x + args.camera_x} {y + 2.88} {z + 0.5} 0 0",
        "timeCommand": "time set 6000", "sunDirection": [0, 0.8660254037844386, 0.5],
        "minecraftSettings": {"sky.sun-noon-south-tilt-degrees": 30,
                              "sky.sun-angular-radius-degrees": 0.1,
                              "fog.enabled": True, "fog.density": 4,
                              "fog.resolution-divisor": 4},
        "rendererSettings": {"exposure.mode": "manual", "exposure.manual-ev": -12},
        "closeSlit": f"fill {x + 8} {y + 8} {z + 2} {x + 9} {y + 8} {z + 13} minecraft:stone",
        "restoreSlit": f"fill {x + 8} {y + 8} {z + 2} {x + 9} {y + 8} {z + 13} minecraft:air",
        "projection": projection or {"width": args.width, "height": args.height, "verticalFov": args.vertical_fov},
        "maskMeaning": "Green intensity: analytic metres of direct-lit primary ray inside the slit beam, ending at a known fixture surface unchanged by closing the slit. Red: unknown or changed endpoint; exclude.",
        "litBeamAtOriginYPlus4": {"x": [x + 8, x + 10],
                                  "z": [z + 2 - 4 / np.sqrt(3), z + 14 - 5 / np.sqrt(3)]},
        "notes": ["No commands are executed. See docs/FOG_SHAFT_TEST.md for acceptance and restoration.",
                  "Sun model assumes the unmodified vanilla26.2 day timeline at noon and configured30-degree tilt.",
                  "Confirm status.camera matches cameraEye; the player teleport assumes1.62-metre spectator eye height.",
                  "Mask uses captured unjittered projection when supplied; otherwise supplied actual FOV.",
                  "Length is geometry-only, not predicted radiance. Density, phase and transmittance weight real scattering.",
                  "Scene geometry outside the authored fixture can add occlusion; validate the noon light reaches the open slot."]}
    if projection is not None:
        plan["cameraEye"] = projection["cameraWorld"]
        plan.pop("playerTeleport")
    (args.output / "plan.json").write_text(json.dumps(plan, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
