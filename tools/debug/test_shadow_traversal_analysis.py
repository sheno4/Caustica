import unittest

from analyze_shadow_traversal import ANOMALY, COUNTS, MAXIMA, analyze


def shadow(frame, pass_name, maxima=(0, 0, 0), counts=(0, 0), anomaly=None):
    values = {"frameId": frame, "pass": pass_name, **dict(zip(MAXIMA, maxima)),
              **dict(zip(COUNTS, counts)), **dict.fromkeys(ANOMALY, 0)}
    if anomaly:
        values.update(anomaly)
    return {"type": "dev.comfyfluffy.caustica.ShadowTraversal", "values": values}


def gpu(frame, stage, duration):
    return {"type": "dev.comfyfluffy.caustica.GpuStage",
            "values": {"frameId": frame, "stage": stage, "elapsedNanos": duration}}


class ShadowTraversalAnalysisTest(unittest.TestCase):
    def test_eight_visibility_dispatches_aggregate_without_mixing_fill(self):
        events = [gpu(741, "scene effects", "PT0.012S"), gpu(741, "fill stable planes", 5000000),
                  shadow(741, "fill stable planes", (100, 900, 1000), (7, 9))]
        events += [shadow(741, "material visibility", (33 + i, 257 + i, 513 + i),
                          (i, 2 * i), {"firstAnomalyFlags": 2, "firstAnomalyPrimitive": 100 + i})
                   for i in range(8)]
        report = analyze(events)
        visibility = next(row for row in report["frames"] if row["pass"] == "material visibility")
        self.assertEqual(8, visibility["dispatchCount"])
        self.assertEqual(40, visibility[MAXIMA[0]])
        self.assertEqual(264, visibility[MAXIMA[1]])
        self.assertEqual(520, visibility[MAXIMA[2]])
        self.assertEqual(28, visibility[COUNTS[0]])
        self.assertEqual(56, visibility[COUNTS[1]])
        self.assertEqual(12, visibility["stageEnvelopeMs"])
        self.assertEqual(list(range(100, 108)), [row["firstAnomaly"]["firstAnomalyPrimitive"]
                                              for row in visibility["dispatches"]])
        self.assertEqual(5, report["frames"][0]["stageEnvelopeMs"])

    def test_source_frame_join_missing_timing_and_unsigned_counts(self):
        events = [gpu(2, "scene effects", "PT0.070S"),
                  shadow(1, "material visibility", counts=(4294967295, 1)),
                  gpu(1, "scene effects", "PT0.003S"),
                  shadow(1, "material visibility", counts=(2, 0)),
                  shadow(3, "material visibility")]
        report = analyze(events)
        one, three = report["frames"]
        self.assertEqual(3, one["stageEnvelopeMs"])
        self.assertEqual(4294967297, one[COUNTS[0]])
        self.assertIsNone(three["stageEnvelopeMs"])
        self.assertIsNone(three["dispatches"][0]["firstAnomaly"])
        summary = report["summary"]["material visibility"]
        self.assertEqual(0, summary["thresholdFrames"])
        self.assertEqual(1, summary["anomalyFrames"])
        self.assertEqual(1, summary["stageEnvelopeMs"]["samples"])
        self.assertEqual(3, summary["dispatches"])


if __name__ == "__main__":
    unittest.main()
