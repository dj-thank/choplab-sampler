from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.instrumentation_summary import (
    parse_instrumentation_summary,
    parse_instrumentation_xml,
    summarize_instrumentation_xml_files,
)


ROOT = Path(__file__).resolve().parents[2]


class InstrumentationSummaryTest(unittest.TestCase):
    def test_accepts_one_positive_summary_without_fixing_the_suite_size(self) -> None:
        for count in (1, 5, 6):
            with self.subTest(count=count):
                noun = "test" if count == 1 else "tests"
                summary = parse_instrumentation_summary(
                    f"INSTRUMENTATION_STATUS_CODE: 0\n\nOK ({count} {noun})\n\n"
                    "INSTRUMENTATION_CODE: -1\n"
                )

                self.assertTrue(summary.passed)
                self.assertEqual(count, summary.testCount)
                self.assertIsNone(summary.reason)

    def test_rejects_zero_tests(self) -> None:
        summary = parse_instrumentation_summary("OK (0 tests)\n")

        self.assertFalse(summary.passed)
        self.assertEqual(0, summary.testCount)
        self.assertIn("zero tests", summary.reason or "")

    def test_rejects_missing_or_multiple_summaries(self) -> None:
        missing = parse_instrumentation_summary("INSTRUMENTATION_CODE: -1\n")
        multiple = parse_instrumentation_summary("OK (5 tests)\nOK (5 tests)\n")

        self.assertFalse(missing.passed)
        self.assertIn("missing", missing.reason or "")
        self.assertFalse(multiple.passed)
        self.assertIn("multiple", multiple.reason or "")

    def test_rejects_junit_failure_even_if_an_ok_line_is_present(self) -> None:
        transcript = "OK (5 tests)\nFAILURES!!!\nTests run: 5, Failures: 1\n"
        summary = parse_instrumentation_summary(transcript)

        self.assertFalse(summary.passed)
        self.assertIsNone(summary.testCount)
        self.assertIn("failures", (summary.reason or "").lower())

    def test_rejects_junit_error_even_if_an_ok_line_is_present(self) -> None:
        transcript = "OK (5 tests)\nTests run: 5, Failures: 0, Errors: 1\n"
        summary = parse_instrumentation_summary(transcript)

        self.assertFalse(summary.passed)
        self.assertIsNone(summary.testCount)
        self.assertIn("error", (summary.reason or "").lower())

    def test_rejects_instrumentation_failure_abort_and_crash_markers(self) -> None:
        for marker in (
            "INSTRUMENTATION_FAILED: java.lang.IllegalStateException",
            "INSTRUMENTATION_ABORTED: System has crashed",
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
        ):
            with self.subTest(marker=marker):
                summary = parse_instrumentation_summary(f"OK (5 tests)\n{marker}\n")
                self.assertFalse(summary.passed)

    def test_cli_emits_machine_readable_json(self) -> None:
        result = subprocess.run(
            [sys.executable, str(ROOT / "scripts" / "instrumentation_summary.py")],
            input="Time: 1.234\n\nOK (5 tests)\n",
            text=True,
            capture_output=True,
            check=True,
        )

        self.assertEqual(
            {"passed": True, "testCount": 5, "reason": None},
            json.loads(result.stdout),
        )

    def test_accepts_junit_xml_only_when_actual_tests_are_positive_and_clean(self) -> None:
        summary = parse_instrumentation_xml(
            """<?xml version="1.0" encoding="UTF-8"?>
            <testsuites>
              <testsuite name="waveform" tests="2" failures="0" errors="0" skipped="0">
                <testcase classname="Waveform" name="renders" />
                <testcase classname="Waveform" name="accessibility" />
              </testsuite>
            </testsuites>
            """
        )

        self.assertTrue(summary.passed)
        self.assertEqual(2, summary.tests)
        self.assertEqual(0, summary.failures)
        self.assertEqual(0, summary.errors)
        self.assertEqual(0, summary.skips)
        self.assertIsNone(summary.reason)

    def test_rejects_junit_xml_failure_error_skip_and_zero_test_paths(self) -> None:
        cases = {
            "failure": '<testcase name="bad"><failure /></testcase>',
            "error": '<testcase name="bad"><error /></testcase>',
            "skip": '<testcase name="bad"><skipped /></testcase>',
            "zero": "",
        }
        for label, testcase in cases.items():
            with self.subTest(label=label):
                xml = (
                    '<testsuite name="suite" tests="1" failures="0" errors="0" skipped="0">'
                    f"{testcase}</testsuite>"
                    if label != "zero"
                    else '<testsuite name="suite" tests="0" failures="0" errors="0" skipped="0" />'
                )
                summary = parse_instrumentation_xml(xml)

                self.assertFalse(summary.passed)
                self.assertIsNotNone(summary.reason)

    def test_rejects_positive_declared_tests_without_testcase_results(self) -> None:
        summary = parse_instrumentation_xml(
            '<testsuite name="suite" tests="1" failures="0" errors="0" skipped="0" />'
        )

        self.assertFalse(summary.passed)
        self.assertIn("testcase", summary.reason or "")

    def test_rejects_nested_suite_aggregate_counter_mismatch(self) -> None:
        summary = parse_instrumentation_xml(
            """
            <testsuites tests="2" failures="0" errors="0" skipped="0">
              <testsuite tests="2" failures="0" errors="0" skipped="0">
                <testsuite tests="1" failures="0" errors="0" skipped="0">
                  <testcase name="only" />
                </testsuite>
              </testsuite>
            </testsuites>
            """
        )

        self.assertFalse(summary.passed)
        self.assertIn("does not match", summary.reason or "")

    def test_rejects_xml_declared_counts_that_do_not_match_actual_cases(self) -> None:
        summary = parse_instrumentation_xml(
            '<testsuite name="suite" tests="2" failures="0" errors="0" skipped="0">'
            '<testcase name="only" /></testsuite>'
        )

        self.assertFalse(summary.passed)
        self.assertIn("does not match", summary.reason or "")

    def test_treats_skips_and_disabled_xml_counters_as_nonzero_skips(self) -> None:
        alias = parse_instrumentation_xml(
            '<testsuite tests="1" failures="0" errors="0" skips="1">'
            '<testcase name="one" /></testsuite>'
        )
        disabled = parse_instrumentation_xml(
            '<testsuite tests="1" failures="0" errors="0" skipped="0" disabled="1">'
            '<testcase name="one" /></testsuite>'
        )

        self.assertFalse(alias.passed)
        self.assertFalse(disabled.passed)

    def test_aggregates_multiple_xml_files_and_fails_on_missing_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            first = Path(directory) / "first.xml"
            second = Path(directory) / "second.xml"
            first.write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                '<testcase name="one" /></testsuite>',
                encoding="utf-8",
            )
            second.write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                '<testcase name="two" /></testsuite>',
                encoding="utf-8",
            )

            summary = summarize_instrumentation_xml_files([first, second])
            self.assertTrue(summary.passed)
            self.assertEqual(2, summary.tests)

            missing = summarize_instrumentation_xml_files([first, Path(directory) / "missing.xml"])
            self.assertFalse(missing.passed)
            self.assertIn("missing", missing.reason or "")

    def test_xml_cli_emits_machine_readable_counts_and_nonzero_on_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "result.xml"
            xml.write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                '<testcase name="one" /></testsuite>',
                encoding="utf-8",
            )
            result = subprocess.run(
                [sys.executable, str(ROOT / "scripts" / "instrumentation_summary.py"), "--xml", str(xml)],
                text=True,
                capture_output=True,
                check=True,
            )

            self.assertEqual(
                {
                    "passed": True,
                    "tests": 1,
                    "failures": 0,
                    "errors": 0,
                    "skips": 0,
                    "reason": None,
                },
                json.loads(result.stdout),
            )

    def test_avd_runner_uses_the_parser_instead_of_a_fixed_count(self) -> None:
        runner = (ROOT / "scripts" / "run-choplab-review-avd-tests.ps1").read_text(encoding="utf-8")

        self.assertIn("instrumentation_summary.py", runner)
        self.assertNotRegex(runner, r"OK \\\([0-9]+ tests?\\\)")

    def test_android_workflow_verifies_machine_readable_instrumentation_xml(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "android.yml").read_text(encoding="utf-8")

        self.assertIn("instrumentation_summary.py", workflow)
        self.assertIn("--xml", workflow)
        self.assertIn("androidTest-results", workflow)


if __name__ == "__main__":
    unittest.main()
