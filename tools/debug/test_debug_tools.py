from contextlib import chdir
from io import StringIO
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import Mock, call, patch

from caustica_debug import Client
from analyze_recording import analyze, summarize, milliseconds
import check_rt_lifecycle
import measure


class DebugToolsTest(unittest.TestCase):
    def test_lifecycle_rejects_empty_runs_and_invalid_timeouts_before_client_access(self):
        for arguments in (["--cycles", "0"], ["--cycles", "-1"],
                          ["--timeout", "0"], ["--timeout", "-1"],
                          ["--timeout", "nan"], ["--timeout", "inf"]):
            with self.subTest(arguments=arguments), \
                    patch("sys.argv", ["check_rt_lifecycle", *arguments]), \
                    patch("sys.stderr", new_callable=StringIO), \
                    patch.object(check_rt_lifecycle, "Client") as client:
                with self.assertRaises(SystemExit) as caught:
                    check_rt_lifecycle.main()
                self.assertEqual(caught.exception.code, 2)
                client.assert_not_called()

    def test_measurement_records_its_checkout_when_called_from_another_directory(self):
        checkout = Path(measure.__file__).resolve().parents[2]
        expected = subprocess.run(["git", "rev-parse", "HEAD"], cwd=checkout,
                                  check=True, capture_output=True, text=True).stdout.strip()
        client = Mock()
        client.call.return_value = {}
        with tempfile.TemporaryDirectory() as directory, chdir(directory):
            output = Path(directory) / "measurement.json"
            with patch("sys.argv", ["measure", "--warmup", "0", "--output", str(output)]), \
                    patch.object(measure, "Client", return_value=client):
                measure.main()
            report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(report["revision"], expected)

    def test_measurement_does_not_start_when_git_metadata_cannot_be_read(self):
        with tempfile.TemporaryDirectory() as directory, \
                patch("sys.argv", ["measure", "--output", "unused.json"]), \
                patch.object(measure, "Client") as client, \
                patch.object(measure, "__file__", str(Path(directory) / "tools/debug/measure.py")):
            with self.assertRaises(subprocess.CalledProcessError):
                measure.main()
        client.assert_not_called()

    def test_measurement_preserves_conditions_when_client_disconnects(self):
        client = Mock()
        conditions = {"window": {"width": 3840, "height": 2130}, "frames": 120}
        client.call.side_effect = [conditions, {}, ConnectionResetError("client crashed"),
                                   ConnectionRefusedError("client stopped")]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "measurement.json"
            with patch("sys.argv", ["measure", "--warmup", "0", "--frames", "600", "--output", str(output)]), \
                    patch.object(measure, "Client", return_value=client), \
                    patch.object(measure.subprocess, "run", side_effect=[Mock(stdout="revision\n"), Mock(stdout="")]):
                with self.assertRaises(ConnectionRefusedError):
                    measure.main()
            report = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(report["start"], conditions)
        self.assertEqual(report["revision"], "revision")
        self.assertEqual(report["requestedFrames"], 600)
        self.assertNotIn("recording", report)
        self.assertNotIn("end", report)

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

    def test_cpu_durations_are_summarized_without_timestamps_or_unavailable_samples(self):
        events = [{"type": "dev.comfyfluffy.caustica." + name, "values": values}
                  for name, values in [
                      ("Frame", {"threadCpuNanos": "PT0.002S", "startedNanos": 987654321}),
                      ("Frame", {"threadCpuNanos": "PT-0.000000001S"}),
                      ("TerrainPublication", {"cpuNanos": 3000000}),
                      ("TerrainPublication", {"cpuNanos": -1}),
                      ("EntityMeshUpload", {"cpuNanos": "PT0.004S", "queuedNanos": 123456789}),
                  ]]
        fields = analyze(events)["fields"]
        self.assertEqual(set(fields), {"Frame.threadCpuMs", "TerrainPublication.cpuMs", "EntityMeshUpload.cpuMs"})
        for field, expected in [("Frame.threadCpuMs", 2), ("TerrainPublication.cpuMs", 3)]:
            self.assertEqual(fields[field]["samples"], 2)
            self.assertEqual(fields[field]["finiteSamples"], 1)
            self.assertEqual(fields[field]["mean"], expected)
        self.assertEqual(fields["EntityMeshUpload.cpuMs"]["mean"], 4)

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
