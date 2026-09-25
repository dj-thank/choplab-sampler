"""Host-only execution-control tests; these never run Gradle or an emulator."""

from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import run_android_instrumentation_gate as gate


GREEN = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="ok"/></testsuite>'


class AndroidInstrumentationGateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="choplab gate ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.result = self.root / "app/build/outputs/androidTest-results/connected/debug/TEST-AVD with spaces.xml"

    def write_xml(self, text: str, path: Path | None = None) -> None:
        destination = path or self.result
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")

    def run_gate(self, gradle_status: int = 0) -> tuple[int, str, str]:
        output, errors = io.StringIO(), io.StringIO()
        with (
            patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], gradle_status)) as run,
            redirect_stdout(output),
            redirect_stderr(errors),
        ):
            result = gate.run_gate(self.root)
        run.assert_called_once_with(
            ["./gradlew", "--stacktrace", ":app:connectedDebugAndroidTest"],
            cwd=self.root,
            check=False,
        )
        return result, output.getvalue(), errors.getvalue()

    def test_green_xml_and_gradle_success_pass(self) -> None:
        self.write_xml(GREEN)
        status, output, _ = self.run_gate()
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output)["tests"], 1)

    def test_xml_is_discovered_after_gradle_produces_it(self) -> None:
        def complete(*args: object, **kwargs: object) -> subprocess.CompletedProcess:
            self.write_xml(GREEN)
            return subprocess.CompletedProcess([], 0)
        with patch.object(gate.subprocess, "run", side_effect=complete), redirect_stdout(io.StringIO()):
            self.assertEqual(gate.run_gate(self.root), 0)

    def test_gradle_failure_cannot_be_hidden_by_green_xml(self) -> None:
        self.write_xml(GREEN)
        status, _, errors = self.run_gate(7)
        self.assertEqual(status, 7)
        self.assertIn("do not override", errors)

    def test_gradle_signal_is_nonzero(self) -> None:
        self.write_xml(GREEN)
        self.assertEqual(self.run_gate(-15)[0], 143)

    def test_missing_xml_fails(self) -> None:
        self.assertEqual(self.run_gate()[0], 1)

    def test_missing_xml_preserves_gradle_failure(self) -> None:
        self.assertEqual(self.run_gate(9)[0], 9)

    def test_invalid_empty_failed_errored_and_skipped_xml_fail(self) -> None:
        invalid_results = (
            "not XML",
            '<testsuite tests="0"/>',
            '<testsuite tests="1"><testcase><failure/></testcase></testsuite>',
            '<testsuite tests="1"><testcase><error/></testcase></testsuite>',
            '<testsuite tests="1"><testcase><skipped/></testcase></testsuite>',
            '<testsuite tests="2"><testcase name="only-one"/></testsuite>',
        )
        for text in invalid_results:
            with self.subTest(xml=text):
                self.write_xml(text)
                self.assertEqual(self.run_gate()[0], 1)

    def test_invalid_utf8_fails_closed(self) -> None:
        self.write_xml(GREEN)
        self.result.write_bytes(b"\xff\xfe\xfa")
        self.assertEqual(self.run_gate()[0], 1)

    def test_host_unit_xml_does_not_count_as_instrumentation(self) -> None:
        self.write_xml(GREEN, self.root / "app/build/test-results/testDebugUnitTest/TEST-unit.xml")
        self.assertEqual(self.run_gate()[0], 1)

    def test_both_connected_locations_are_aggregated(self) -> None:
        self.write_xml(GREEN)
        other = self.root / "app/build/test-results/connected/TEST-other.xml"
        self.write_xml(GREEN, other)
        status, output, _ = self.run_gate()
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output)["tests"], 2)

    def test_one_bad_xml_cannot_hide_behind_another_green_file(self) -> None:
        self.write_xml(GREEN)
        self.write_xml('<testsuite tests="0"/>', self.result.with_name("TEST-bad.xml"))
        self.assertEqual(self.run_gate()[0], 1)

    def test_start_failure_is_not_success(self) -> None:
        with patch.object(gate.subprocess, "run", side_effect=OSError("cannot execute")), redirect_stderr(io.StringIO()):
            self.assertEqual(gate.run_gate(self.root), 1)

    def test_xml_read_failure_does_not_hide_gradle_failure(self) -> None:
        with patch.object(gate, "summarize_instrumentation_xml_files", side_effect=OSError("cannot read")):
            self.assertEqual(self.run_gate(5)[0], 5)

    def test_workflow_uses_one_external_command(self) -> None:
        workflow = (SCRIPTS.parent / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        marker = "name: Instrumentation, accessibility, and XML result verification on API 36 emulator"
        step = workflow.split(marker, 1)[1].split("      - name:", 1)[0]
        lines = step.split("          script:", 1)[1].strip().splitlines()
        self.assertEqual(lines, ["python3 scripts/run_android_instrumentation_gate.py"])


if __name__ == "__main__":
    unittest.main()
