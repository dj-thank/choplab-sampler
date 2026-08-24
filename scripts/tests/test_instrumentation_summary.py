from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

from scripts.instrumentation_summary import parse_instrumentation_summary


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

    def test_avd_runner_uses_the_parser_instead_of_a_fixed_count(self) -> None:
        runner = (ROOT / "scripts" / "run-choplab-review-avd-tests.ps1").read_text(encoding="utf-8")

        self.assertIn("instrumentation_summary.py", runner)
        self.assertNotRegex(runner, r"OK \\\([0-9]+ tests?\\\)")


if __name__ == "__main__":
    unittest.main()
