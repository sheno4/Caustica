import unittest
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np

from analyze_fog import statistics, stationary_difference
from check_fog import distance, validate_world, run
from fog_fixtures import apply_block_command, fixtures


class FogAnalysisTest(unittest.TestCase):
    def test_open_basin_recipe_stays_bounded_and_has_a_free_water_exit(self):
        origin = (-1830, 65, -6360)
        recipe = next(item for item in fixtures(origin) if item["name"] == "open-water-basin")
        low = recipe["bounds"][:3]
        high = recipe["bounds"][3:]
        self.assertEqual(recipe["bounds"], [-1638, 65, -6360, -1622, 77, -6344])
        cells = {}
        for command in recipe["commands"]:
            operation, *arguments = command.split()
            self.assertEqual(operation, "fill")
            start = list(map(int, arguments[:3]))
            end = list(map(int, arguments[3:6]))
            block = arguments[6]
            for axis in range(3):
                self.assertLessEqual(low[axis], start[axis])
                self.assertLessEqual(start[axis], end[axis])
                self.assertLessEqual(end[axis], high[axis])
            for x in range(start[0], end[0] + 1):
                for y in range(start[1], end[1] + 1):
                    for z in range(start[2], end[2] + 1):
                        cells[x, y, z] = block
        below = recipe["cameras"]["below-surface"]
        eye = (int(np.floor(below["x"])), int(np.floor(below["y"] + 1.62)),
               int(np.floor(below["z"])))
        self.assertEqual(cells[eye], "minecraft:water")
        self.assertLess(below["pitch"], -60)
        for y in range(origin[1] + 6, high[1] + 1):
            self.assertEqual(cells[eye[0], y, eye[2]], "minecraft:air")
        above = recipe["cameras"]["outside-above"]
        self.assertGreater(above["y"], origin[1] + 6)
        self.assertGreater(above["pitch"], 60)
        self.assertNotIn("minecraft:glass", cells.values())

    def test_idempotent_fill_is_journaled_without_hiding_other_failures(self):
        client = Mock()
        client.call.side_effect = RuntimeError("java.util.concurrent.CompletionException: "
                                              "com.mojang.brigadier.exceptions.CommandSyntaxException: No blocks were filled")
        self.assertEqual(apply_block_command(client, "fill 0 64 0 4 68 4 minecraft:air"),
                         {"result": 0, "noChange": True})
        client.call.side_effect = RuntimeError("CommandSyntaxException: That position is not loaded")
        with self.assertRaisesRegex(RuntimeError, "not loaded"):
            apply_block_command(client, "fill 0 64 0 4 68 4 minecraft:air")

    def test_nonfinite_samples_are_counted_not_hidden(self):
        result = statistics(np.array([0, 1, np.nan, np.inf]))
        self.assertEqual(result["nonfinite"], 2)
        self.assertEqual(result["mean"], 0.5)

    def test_stable_depth_difference_excludes_disocclusion(self):
        first = np.ones((2, 2, 3))
        second = first.copy()
        second[0, 0] = 100
        second[1, 1] = 2
        depth = np.ones((2, 2)) * 0.001
        moved_depth = depth.copy()
        moved_depth[0, 0] = 0
        result = stationary_difference(first, second, depth, moved_depth)
        self.assertEqual(result["stableDepthPixels"], 3)
        self.assertAlmostEqual(result["absoluteDifference"]["mean"], 1 / 3)
        self.assertAlmostEqual(result["meanAbsoluteDifferenceRelativeToReference"], 1 / 3)

    def test_reverse_z_non_sky_discontinuity_is_excluded(self):
        first = np.ones((1, 2, 3))
        second = first * 2
        result = stationary_difference(first, second, np.array([[0.001, 0.001]]),
                                       np.array([[0.002, 0.0010001]]))
        self.assertEqual(result["stableDepthPixels"], 1)
        self.assertEqual(result["absoluteDifference"]["mean"], 1)

    def test_native_resolution_mismatch_requires_explicit_resampling(self):
        with self.assertRaises(ValueError):
            stationary_difference(np.ones((4, 4, 3)), np.ones((4, 4, 3)),
                                  np.ones((2, 2)), np.ones((2, 2)))

    def test_wrong_world_rejected_before_mutation(self):
        status = {"ready": True, "paused": False, "frameActive": True,
                  "world": {"directory": "Original"}}
        with self.assertRaises(RuntimeError):
            validate_world(status, "Copied")
        status["world"]["directory"] = "Copied"
        validate_world(status, "Copied")

    def test_flight_distance_uses_all_axes(self):
        self.assertEqual(distance(dict(x=0, y=0, z=0), dict(x=3, y=4, z=12)), 13)

    def test_capture_failure_stops_recording_and_restores_state(self):
        status = {"ready": True, "paused": False, "frameActive": True,
                  "world": {"directory": "Copied"},
                  "player": dict(x=1, y=90, z=3, yaw=4, pitch=65, currentFlyingSpeed=0.05),
                  "settings": {"composite.debug-view": {"value": 0}}}
        client = Mock()
        def response(op, **args):
            if op == "status":
                return status
            if op == "settings.get":
                return {"fog.enabled": {"value": True}, "fog.debug": {"value": 0}}
            if op == "command":
                if args["command"] == "time query time":
                    return {"result": 1000}
                return {"result": 1}
            if op == "image.capture":
                raise RuntimeError("capture failed")
            return {}
        client.call.side_effect = response
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(session="unused", timeout=60, copied_world="Copied",
                                   output=Path(directory), modes=["off"], cases=["stationary"],
                                   warmup=1, frames=1, repeats=1, time=None)
            with patch("check_fog.Client", return_value=client):
                with self.assertRaisesRegex(RuntimeError, "capture failed"):
                    run(args)
            manifest = json.loads((Path(directory) / "manifest.json").read_text())
        self.assertEqual(manifest["cleanupErrors"], [])
        self.assertIn("final", manifest)
        self.assertEqual(manifest["savedDaytime"], 1000)
        operations = [call.args[0] for call in client.call.call_args_list]
        self.assertLess(operations.index("jfr.stop"), operations.index("image.capture"))
        commands = [call.kwargs["command"] for call in client.call.call_args_list if call.args[0] == "command"]
        self.assertEqual(commands[-1], "gamemode creative")
        self.assertIn("tp @s 1 90 3 4 65", commands)
        self.assertIn("time set 1000", commands)
        self.assertNotIn("time query day", commands)


if __name__ == "__main__":
    unittest.main()
