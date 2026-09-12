import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import analyze_host_work as host


def event(name, start, end, thread=1, **values):
    return {"type": "dev.comfyfluffy.caustica." + name, "values": {
        "startedNanos": start, "elapsedNanos": end - start,
        "eventThread": {"javaThreadId": thread}, **values}}


def loop(number, start, end, **values):
    return event("HostLoop", start, end, loopId=number, frameIdBefore=99,
                 frameIdAfter=99, rtActive=values.pop("rtActive", True), **values)


def work(number, start, end, allocated=10, **values):
    return event("HostWork", start, end, loopId=number, work="callback",
                 allocatedBytes=allocated, threadCpuNanos=-1, **values)


def stage(start, end, name="stage", **values):
    # Deliberately unrelated to the loop's renderer frame ID.
    return event("CpuStage", start, end, stage=name, frameId=1000, **values)


def envelopes():
    return [loop(1, 0, 100), loop(2, 110, 210), loop(3, 220, 320), loop(4, 330, 430)]


def totals(number, start, end, **observations):
    return [{"type": "dev.comfyfluffy.caustica.HostCallbackTotals", "values": {
        "loopId": number, "callback": label, "windowStartedNanos": start,
        "windowEndedNanos": end, "eventThread": {"javaThreadId": 1},
        "calls": observations.get(label, (0, 0, 0))[0],
        "measuredCalls": observations.get(label, (0, 0, 0))[0],
        "elapsedNanos": observations.get(label, (0, 0, 0))[1],
        "allocatedBytes": observations.get(label, (0, 0, 0))[2]}}
        for label in sorted(host.CALLBACK_LABELS)]


class HostWorkAnalysisTest(unittest.TestCase):
    def test_full_submit_pairs_exclude_wait_and_allow_multiple_invocations(self):
        events = envelopes() + [work(2, 120, 130, 10)] + totals(2, 110, 210)
        for identity, start in [(7, 140), (8, 180)]:
            events += [event("HostSubmission", start, start + 2, loopId=2, allocatedBytes=4,
                             submissionId=identity, segment="beforeWait", submissionCompleted=False),
                       event("HostSubmission", start + 15, start + 18, loopId=2, allocatedBytes=8,
                             submissionId=identity, segment="afterWait", submissionCompleted=True)]
        sample = host.analyze(list(reversed(events)))["loops"][0]
        self.assertEqual(sample["hostSubmissionCoverage"], "full-submit-cpu-segments")
        self.assertEqual(sample["hostSubmissionNanos"], 10)
        self.assertEqual(sample["fullHostSubmitUpperBoundNanos"], 20)
        self.assertEqual(sample["hostInclusiveAllocatedBytesUpperBound"], 34)

    def test_incomplete_failed_mismatched_and_clipped_submit_pairs_cannot_certify_full_scope(self):
        before = event("HostSubmission", 120, 125, loopId=2, allocatedBytes=4,
                       submissionId=7, segment="beforeWait", submissionCompleted=False)
        after = event("HostSubmission", 160, 165, loopId=2, allocatedBytes=-1,
                      submissionId=7, segment="afterWait", submissionCompleted=True)
        cases = [[before], [after], [before, before, after],
                 [before, {**after, "values": {**after["values"], "submissionCompleted": False}}],
                 [before, {**after, "values": {**after["values"], "submissionId": 8}}],
                 [before, {**after, "values": {**after["values"], "elapsedNanos": 60}}]]
        for segments in cases:
            with self.subTest(segments=segments):
                report = host.analyze(envelopes() + [work(2, 130, 140, 0)] + totals(2, 110, 210) + segments)
                sample = report["loops"][0]
                self.assertEqual(sample["hostSubmissionCoverage"], "incomplete-segments")
                self.assertIsNone(sample["fullHostSubmitUpperBoundNanos"])
        complete = host.analyze(envelopes() + totals(2, 110, 210) + [work(2, 130, 140, 0), before, after])["loops"][0]
        self.assertEqual(complete["hostSubmissionCoverage"], "full-submit-cpu-segments")
        self.assertIsNone(complete["hostInclusiveAllocatedBytesUpperBound"])

    def test_legacy_packing_observation_remains_useful_without_claiming_full_submit(self):
        events = envelopes() + totals(2, 110, 210) + [work(2, 120, 130, 0),
                  event("HostSubmission", 160, 170, loopId=2, allocatedBytes=0)]
        report = host.analyze(events)
        sample = report["loops"][0]
        self.assertEqual(sample["hostSubmissionCoverage"], "packing-only")
        self.assertEqual(sample["hostInclusiveUpperBoundNanos"], 20)
        self.assertIsNone(sample["fullHostSubmitUpperBoundNanos"])
        self.assertEqual(report["fullHostSubmitUpperBoundMs"]["finiteSamples"], 0)

    def test_callback_and_shared_sums_widen_bound_even_when_the_intervals_overlap(self):
        events = envelopes() + [work(2, 120, 180, 40), stage(125, 175),
                                event("HostSubmission", 160, 190, loopId=2, allocatedBytes=12)]
        events += totals(2, 110, 210, VULKAN_RESULT=(2, 20, 8))
        sample = host.analyze(events)["loops"][0]
        self.assertEqual(sample["occupiedNanos"], 60)
        self.assertEqual(sample["coveredWithCallbacksUpperBoundNanos"], 80)
        self.assertEqual(sample["hostInclusiveUpperBoundNanos"], 110)
        self.assertEqual(sample["hostWorkPlusCallbacksAllocatedBytes"], 48)
        self.assertEqual(sample["hostInclusiveAllocatedBytesUpperBound"], 60)

    def test_next_start_windows_include_gap_work_and_gap_callback_totals_once(self):
        events = envelopes() + [work(2, 120, 140, 10), work(0, 212, 218, 5),
                                stage(214, 225),
                                event("HostSubmission", 190, 200, loopId=2, allocatedBytes=3)]
        events += totals(2, 110, 210, DIRTY_SECTION=(1, 2, 4))
        events += totals(0, 210, 220, SERIAL_SUBMIT=(1, 3, 6))
        events += totals(3, 220, 320) + totals(0, 320, 330)
        report = host.analyze(events, window_policy="next-start")
        first, second = report["loops"]
        self.assertEqual(first["endedNanos"], 220)
        self.assertEqual(first["occupiedNanos"], 28)  # [120,140) plus [212,220)
        self.assertEqual(second["occupiedNanos"], 5)
        self.assertEqual(first["hostWorkPlusCallbacksAllocatedBytes"], 25)
        self.assertEqual(first["hostInclusiveUpperBoundNanos"], 43)
        self.assertIsNone(first["hostInclusiveAllocatedBytesUpperBound"])  # uncovered stage bytes unknown
        self.assertEqual(report["loop0"]["allRecordCount"], 1)
        self.assertTrue(first["callbackTotalsComplete"])

    def test_zero_totals_prove_no_calls_but_missing_labels_or_gap_totals_do_not(self):
        events = envelopes() + [work(2, 120, 130, 0),
                                event("HostSubmission", 190, 200, loopId=2, allocatedBytes=0)]
        zero = totals(2, 110, 210)
        sample = host.analyze(events + zero)["loops"][0]
        self.assertEqual(sample["hostInclusiveAllocatedBytesUpperBound"], 0)
        self.assertEqual(sample["hostInclusiveUpperBoundNanos"], 20)
        self.assertIsNone(host.analyze(events + zero[:-1])["loops"][0]["hostInclusiveUpperBoundNanos"])
        self.assertIsNone(host.analyze(events + zero, window_policy="next-start")["loops"][0]["hostInclusiveUpperBoundNanos"])
        self.assertIsNone(host.analyze(envelopes() + zero)["loops"][0]["hostInclusiveUpperBoundNanos"])

    def test_unknown_callback_or_shared_counter_never_becomes_zero(self):
        events = envelopes() + [work(2, 120, 130, 0),
                                event("HostSubmission", 190, 200, loopId=2, allocatedBytes=-1)]
        sample = host.analyze(events + totals(2, 110, 210, DIRTY_SECTION=(1, 2, -1)))["loops"][0]
        self.assertIsNone(sample["hostWorkPlusCallbacksAllocatedBytes"])
        self.assertIsNone(sample["hostInclusiveAllocatedBytesUpperBound"])
        self.assertEqual(sample["hostInclusiveUpperBoundNanos"], 22)

    def test_next_start_omits_windows_with_incomplete_intervening_loops(self):
        events = [loop(1, 0, 100), loop(2, 110, 210), loop(4, 330, 430), loop(5, 440, 540)]
        report = host.analyze(events, window_policy="next-start")
        self.assertEqual(report["discontinuousWindowsRemoved"], 1)
        self.assertEqual([v["loopId"] for v in report["loops"]], [4])

    def test_callback_sum_cannot_be_prorated_across_a_window(self):
        with self.assertRaisesRegex(ValueError, "boundary"):
            host.analyze(envelopes() + totals(2, 100, 220))

    def test_trailing_gap_after_trimmed_loop_is_reported_outside_not_assigned_to_last_window(self):
        events = envelopes() + totals(2, 110, 210) + totals(0, 210, 220)
        events += totals(3, 220, 320) + totals(0, 320, 330)
        events += totals(0, 430, 440, DIRTY_SECTION=(3, 7, -1))
        report = host.analyze(events, window_policy="next-start")
        self.assertEqual(report["callbackTotalsMissingLoops"], 0)
        self.assertEqual(report["eventsOutsideSelectedLoops"]["HostCallbackTotals"], len(host.CALLBACK_LABELS))
        excluded = {v["callback"]: v for v in report["callbackTotalsOutsideSelectedWindows"]}
        self.assertEqual(excluded["DIRTY_SECTION"]["calls"], 3)
        self.assertEqual(excluded["DIRTY_SECTION"]["allocatedBytes"], -1)
        self.assertEqual(report["loops"][-1]["callbackExtraNanos"], 0)

    def test_partial_gap_still_cannot_be_prorated_into_retained_window(self):
        with self.assertRaisesRegex(ValueError, "boundary"):
            host.analyze(envelopes() + totals(0, 310, 340, DIRTY_SECTION=(1, 2, 8)), window_policy="next-start")

    def test_nested_and_crossing_stages_union_clipping_and_unknown_gap_bytes(self):
        events = envelopes() + [work(2, 120, 150, 30), work(2, 180, 190, 50),
                                stage(125, 145, "nested"), stage(140, 185, "crossing"),
                                stage(170, 230, "boundary")]
        report = host.analyze(list(reversed(events)))
        first, second = report["loops"]
        self.assertEqual(first["occupiedNanos"], 90)  # [120,210)
        self.assertEqual(first["hostWorkNanos"], 40)
        self.assertEqual(first["cpuStageOutsideHostWorkNanos"], 50)
        self.assertEqual(first["hostWorkAllocations"]["bytes"], 80)
        self.assertIsNone(first["observedUnionAllocatedBytes"])
        self.assertEqual(second["occupiedNanos"], 10)  # [220,230)
        self.assertIsNone(second["observedUnionAllocatedBytes"])
        self.assertEqual(report["cpuStageOutsideHostWorkByStageNanos"]["nested"], 0)
        self.assertEqual(report["boundaryLoopsRemoved"], 2)
        self.assertEqual(first["frameIdBefore"], first["frameIdAfter"])

    def test_nested_stages_and_frame_allocations_never_added(self):
        report = host.analyze(envelopes() + [work(2, 120, 180, 40), stage(130, 160),
                                            stage(140, 150),
                                            event("Frame", 110, 210, allocatedBytes=999999)])
        sample = report["loops"][0]
        self.assertEqual(sample["occupiedNanos"], 60)
        self.assertEqual(sample["observedUnionAllocatedBytes"], 40)
        self.assertFalse(sample["allocationGap"])

    def test_unavailable_and_zero_allocations_remain_distinct(self):
        report = host.analyze(envelopes() + [work(2, 120, 130, 0), work(2, 140, 150, -1),
                                            work(3, 230, 240, 0)])
        first, second = report["loops"]
        self.assertIsNone(first["observedUnionAllocatedBytes"])
        self.assertEqual(first["hostWorkAllocations"]["knownDisjointBytes"], 0)
        self.assertEqual(first["hostWorkAllocations"]["unavailableScopes"], 1)
        self.assertEqual(second["observedUnionAllocatedBytes"], 0)
        self.assertEqual(report["observedUnionAllocatedBytes"]["finiteSamples"], 1)
        json.dumps(report, allow_nan=False)

    def test_overlapping_hostwork_invalidates_allocation_sum_but_not_time_union(self):
        sample = host.analyze(envelopes() + [work(2, 120, 160), work(2, 140, 180)])["loops"][0]
        self.assertEqual(sample["occupiedNanos"], 60)
        self.assertTrue(sample["hostWorkAllocations"]["overlappingScopes"])
        self.assertIsNone(sample["hostWorkAllocations"]["knownDisjointBytes"])
        self.assertIsNone(sample["observedUnionAllocatedBytes"])

    def test_partial_scope_bytes_not_prorated(self):
        sample = host.analyze(envelopes() + [work(2, 105, 125, 200)])["loops"][0]
        self.assertEqual(sample["occupiedNanos"], 15)
        self.assertEqual(sample["hostWorkAllocations"]["partialScopes"], 1)
        self.assertEqual(sample["hostWorkAllocations"]["knownDisjointBytes"], 0)
        self.assertIsNone(sample["observedUnionAllocatedBytes"])

    def test_boundary_trim_and_loop_zero_gaps_are_explicit(self):
        report = host.analyze(envelopes() + [work(1, 0, 100), work(4, 330, 430),
                                            work(0, 105, 115, 70), work(0, 212, 218, -1),
                                            work(0, 325, 328, 90), work(999, 440, 450)])
        self.assertEqual([s["loopId"] for s in report["loops"]], [2, 3])
        self.assertEqual(report["occupiedMs"]["max"], 0)
        zero = report["loop0"]
        self.assertEqual(zero["allRecordCount"], 3)
        self.assertEqual(zero["retainedRecordCount"], 2)
        self.assertEqual(zero["retainedOccupiedNanos"], 11)
        self.assertEqual(zero["retainedAllocations"]["partialScopes"], 1)
        self.assertEqual(zero["records"][0]["threadCpuNanos"], -1)
        self.assertIsNone(zero["retainedAllocations"]["bytes"])
        self.assertEqual(report["eventsOutsideSelectedLoops"]["HostWork"], 3)

    def test_thread_filter_prevents_worker_scope_inclusion_and_requires_identity(self):
        report = host.analyze(envelopes() + [stage(110, 210, thread=2)])
        self.assertEqual(report["occupiedMs"]["max"], 0)
        self.assertEqual(report["ignoredOtherThreadEvents"]["CpuStage"], 1)
        with self.assertRaisesRegex(ValueError, "--thread-id"):
            host.analyze(envelopes() + [loop(10, 0, 100, thread=2)])
        self.assertEqual(host.analyze(envelopes(), thread_id=1)["sampledLoops"], 2)
        invalid = stage(120, 140)
        del invalid["values"]["eventThread"]
        with self.assertRaisesRegex(ValueError, "eventThread"):
            host.analyze(envelopes() + [invalid])

    def test_identity_mismatch_and_overlapping_envelopes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "loopId"):
            host.analyze(envelopes() + [work(3, 120, 130)])
        with self.assertRaisesRegex(ValueError, "disjoint"):
            host.analyze([loop(1, 0, 200), loop(2, 100, 300)])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            host.analyze([loop(1, 0, 100), loop(1, 110, 210)])

    def test_short_recording_and_rt_filter_do_not_bypass_boundary_trim(self):
        self.assertEqual(host.analyze(envelopes()[:2])["sampledLoops"], 0)
        report = host.analyze([loop(1, 0, 100), loop(2, 110, 210, rtActive=False),
                               loop(3, 220, 320), loop(4, 330, 430)], rt_only=True)
        self.assertEqual([s["loopId"] for s in report["loops"]], [3])
        self.assertEqual(report["rtFilteredLoops"], 1)
        with self.assertRaises(ValueError):
            host.analyze(envelopes(), trim_loops=0)

    def test_exact_timespans_and_large_signed_monotonic_start(self):
        self.assertEqual(host.nanos("PT-0.000000001S"), -1)
        self.assertEqual(host.nanos("PT1H2M3.000000007S"), 3723000000007)
        offset = -(2 ** 60)
        events = envelopes() + [work(2, 120, 130), stage(125, 140)]
        for e in events:
            e["values"]["startedNanos"] += offset
            e["values"]["elapsedNanos"] = f"PT0.{e['values']['elapsedNanos']:09d}S"
        self.assertEqual(host.analyze(events)["loops"][0]["occupiedNanos"], 20)

    def test_zero_duration_scopes_retain_bytes_at_half_open_window_boundary(self):
        report = host.analyze(envelopes() + [work(2, 110, 110, 16), work(2, 210, 210, 99),
                                            work(0, 110, 110, 8)])
        self.assertEqual(report["loops"][0]["occupiedNanos"], 0)
        self.assertEqual(report["loops"][0]["observedUnionAllocatedBytes"], 16)
        self.assertEqual(report["loop0"]["retainedAllocations"]["bytes"], 8)

    def test_union_matches_discrete_occupancy_for_all_small_interval_pairs(self):
        spans = [(a, b) for a in range(-3, 4) for b in range(a, 4)]
        for first in spans:
            for second in spans:
                expected = set(range(*first)) | set(range(*second))
                self.assertEqual(host.length([first, second, first]), len(expected))

    def test_cli_reads_raw_json_and_writes_finite_report(self):
        with tempfile.TemporaryDirectory() as directory:
            raw, output = Path(directory) / "raw.json", Path(directory) / "analysis.json"
            raw.write_text(json.dumps({"recording": {"events": envelopes()}}), encoding="utf-8-sig")
            with patch("sys.argv", ["analyze_host_work", str(raw), "--output", str(output)]):
                host.main()
            report = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(report["sampledLoops"], 2)
            self.assertEqual(report["allocationGapLoops"], 2)


if __name__ == "__main__":
    unittest.main()
