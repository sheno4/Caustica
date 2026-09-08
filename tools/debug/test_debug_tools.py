import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, call, patch

from caustica_debug import Client
from analyze_recording import analyze, summarize, milliseconds
import check_rt_lifecycle


class DebugToolsTest(unittest.TestCase):
    def test_lifecycle_restores_rt_when_report_write_fails(self):
        client = Mock()
        client.call.side_effect = lambda op, **kwargs: (
            {"ready": True, "paused": False, "runtime": {"requested": False}}
            if op == "status" else {})
        failure = OSError("report storage unavailable")
        with tempfile.TemporaryDirectory() as directory:
            output = str(Path(directory) / "lifecycle.json")
            with patch("sys.argv", ["check_rt_lifecycle", "--cycles", "1", "--output", output]), \
                    patch.object(check_rt_lifecycle, "Client", return_value=client), \
                    patch.object(check_rt_lifecycle, "await_runtime", return_value={"frames": 42}) as settle, \
                    patch.object(Path, "write_text", side_effect=failure):
                with self.assertRaises(OSError) as caught:
                    check_rt_lifecycle.main()
        self.assertIs(caught.exception, failure)
        self.assertEqual([entry for entry in client.call.call_args_list
                          if entry.args[0] == "runtime.set"], [
            call("runtime.set", enabled=False),
            call("runtime.set", enabled=True),
            call("runtime.set", enabled=False),
        ])
        settle.assert_called_with(client, False, 120)

    def test_jfr_timespan_json_units(self):
        self.assertEqual(milliseconds("PT0.0125S"), 12.5)
        self.assertEqual(milliseconds(12500000), 12.5)

    def test_client_waits_for_job_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            session = Path(directory) / "session.json"
            session.write_text(json.dumps({"baseUrl": "http://127.0.0.1:1234", "token": "test"}))
            client = Client(session)
            with patch.object(client, "request", side_effect=[
                {"ok": True, "jobId": "capture"},
                {"ok": True, "result": {"state": "pending"}},
                {"ok": True, "result": {"state": "completed", "result": {"path": "capture.png"}}},
            ]) as request:
                self.assertEqual(client.call("screenshot"), {"path": "capture.png"})
                request.assert_called_with("job", jobId="capture")

    def test_statistics_retain_invalid_sample_count(self):
        result = summarize([1, 2, 3, float("nan")])
        self.assertEqual(result["samples"], 4)
        self.assertEqual(result["finiteSamples"], 3)
        self.assertEqual(result["p50"], 2)

    def test_exposure_steps_follow_source_frames_and_skip_resets(self):
        events = [{"type": "dev.comfyfluffy.caustica.Exposure", "values": {
            "frameId": frame, "observedFrameId": observed, "mode": "auto",
            "resetSequence": reset, "absoluteExposure": exposure}}
            for frame, observed, reset, exposure in [(2, 8, 1, 4), (1, 7, 1, 2), (3, 9, 2, 32)]]
        self.assertEqual(analyze(events)["largestExposureSteps"], [{"frameId": 2, "deltaStops": 1.0}])


if __name__ == "__main__":
    unittest.main()
