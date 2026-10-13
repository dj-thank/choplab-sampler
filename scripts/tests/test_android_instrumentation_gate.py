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

    def run_gate(self, gradle_status: int = 0, *, produces_reports: bool = True) -> tuple[int, str, str]:
        # Existing fixtures describe the reports produced by this Gradle invocation.
        reports = {path: path.read_bytes() for path in self.root.rglob("*.xml")} if produces_reports else {}
        def complete(*args: object, **kwargs: object) -> subprocess.CompletedProcess:
            for path, content in reports.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            return subprocess.CompletedProcess([], gradle_status)
        output, errors = io.StringIO(), io.StringIO()
        with (
            patch.object(gate.subprocess, "run", side_effect=complete) as run,
            redirect_stdout(output),
            redirect_stderr(errors),
        ):
            result = gate.run_gate(self.root)
        run.assert_called_once_with(
            ["./gradlew", "--stacktrace", ":app:connectedDebugAndroidTest",
             "-Pandroid.testInstrumentationRunnerArguments.notClass=com.choplab.sampler.audio.AndroidNativeCodecTest"],
            cwd=self.root,
            check=False,
        )
        return result, output.getvalue(), errors.getvalue()

    def test_green_xml_and_gradle_success_pass(self) -> None:
        self.write_xml(GREEN)
        status, output, _ = self.run_gate()
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output)["tests"], 1)

    def test_opt_in_codec_failure_if_discovered_is_still_a_failed_gate(self) -> None:
        self.write_xml(
            '<testsuite tests="1" failures="1" errors="0" skipped="0">'
            '<testcase classname="com.choplab.sampler.audio.AndroidNativeCodecTest" name="packagedTools">'
            '<failure>org.junit.AssumptionViolatedException: codec was not explicitly selected</failure>'
            '</testcase></testsuite>'
        )
        status, output, errors = self.run_gate()
        self.assertEqual(1, status)
        self.assertEqual(1, json.loads(output)["failures"])
        self.assertIn("FAILURE: com.choplab.sampler.audio.AndroidNativeCodecTest.packagedTools", errors)
        self.assertIn("AssumptionViolatedException", errors)

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

    def test_old_green_xml_cannot_pass_a_run_that_produces_no_reports(self) -> None:
        self.write_xml(GREEN)
        other = self.root / "app/build/test-results/connected/TEST-old.xml"
        self.write_xml(GREEN, other)

        status, output, _ = self.run_gate(produces_reports=False)

        self.assertEqual(status, 1)
        self.assertEqual(json.loads(output)["tests"], 0)
        self.assertFalse(self.result.exists())
        self.assertFalse(other.exists())

    def test_old_failed_xml_does_not_contaminate_fresh_green_results(self) -> None:
        old = self.root / "app/build/test-results/connected/TEST-old.xml"
        self.write_xml('<testsuite tests="1"><testcase name="old"><failure/></testcase></testsuite>', old)
        def complete(*args: object, **kwargs: object) -> subprocess.CompletedProcess:
            self.write_xml(GREEN)
            return subprocess.CompletedProcess([], 0)
        output = io.StringIO()
        with patch.object(gate.subprocess, "run", side_effect=complete), redirect_stdout(output):
            self.assertEqual(gate.run_gate(self.root), 0)
        self.assertEqual(json.loads(output.getvalue())["tests"], 1)

    def test_report_cleanup_preserves_host_reports_and_other_build_files(self) -> None:
        host = self.root / "app/build/test-results/testDebugUnitTest/TEST-unit.xml"
        nested = self.root / "app/build/unrelated/test-results/connected/TEST-other.xml"
        other = self.result.with_name("device-log.txt")
        self.write_xml(GREEN, host)
        self.write_xml(GREEN, nested)
        self.write_xml("keep diagnostic", other)
        self.write_xml(GREEN)

        self.assertEqual(self.run_gate(produces_reports=False)[0], 1)
        self.assertEqual(host.read_text(encoding="utf-8"), GREEN)
        self.assertEqual(nested.read_text(encoding="utf-8"), GREEN)
        self.assertEqual(other.read_text(encoding="utf-8"), "keep diagnostic")

    def test_report_symlinks_are_preserved_and_do_not_count_as_fresh_reports(self) -> None:
        external = self.root / "saved-results/TEST-saved.xml"
        self.write_xml(GREEN, external)
        self.result.parent.mkdir(parents=True, exist_ok=True)
        self.result.symlink_to(external)
        linked_directory = self.root / "app/build/test-results/connected"
        linked_directory.parent.mkdir(parents=True, exist_ok=True)
        linked_directory.symlink_to(external.parent, target_is_directory=True)

        self.assertEqual(self.run_gate(produces_reports=False)[0], 1)
        self.assertTrue(self.result.is_symlink())
        self.assertTrue(linked_directory.is_symlink())
        self.assertEqual(external.read_text(encoding="utf-8"), GREEN)

    def test_symlinked_app_or_build_is_rejected_before_touching_external_reports(self) -> None:
        for relative in (Path("app"), Path("app/build")):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory() as directory:
                root = Path(directory) / "repo"
                linked = root / relative
                external = Path(directory) / "external"
                report_root = external / "build" if relative == Path("app") else external
                reports = [report_root / path for path in (
                    "outputs/androidTest-results/connected/debug/TEST-saved.xml",
                    "test-results/connected/TEST-saved.xml",
                )]
                for report in reports:
                    report.parent.mkdir(parents=True, exist_ok=True)
                    report.write_text(GREEN, encoding="utf-8")
                linked.parent.mkdir(parents=True, exist_ok=True)
                linked.symlink_to(external, target_is_directory=True)

                with (
                    patch.object(gate.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run,
                    redirect_stderr(io.StringIO()),
                    redirect_stdout(io.StringIO()),
                ):
                    self.assertEqual(gate.run_gate(root), 1)

                self.assertTrue(linked.is_symlink())
                for report in reports:
                    self.assertTrue(report.is_file(), "External reports must be preserved")
                    self.assertEqual(report.read_text(encoding="utf-8"), GREEN)
                run.assert_not_called()

    def test_cleanup_failure_stops_before_gradle_is_started(self) -> None:
        self.write_xml(GREEN)
        with (
            patch.object(Path, "unlink", side_effect=OSError("cannot remove previous report")),
            patch.object(gate.subprocess, "run") as run,
            redirect_stderr(io.StringIO()) as errors,
        ):
            self.assertEqual(gate.run_gate(self.root), 1)
        run.assert_not_called()
        self.assertIn("previous", errors.getvalue())
        self.assertEqual(self.result.read_text(encoding="utf-8"), GREEN)

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


class FailingCaseReportTests(unittest.TestCase):
    """A red emulator run names the failing test and its message in the job log."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="choplab gate report ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def run_gate_with(self, xml: str) -> tuple[int, str, str]:
        result = self.root / "app/build/outputs/androidTest-results/connected/debug/TEST-AVD.xml"
        def complete(*args: object, **kwargs: object) -> subprocess.CompletedProcess:
            result.parent.mkdir(parents=True, exist_ok=True)
            result.write_text(xml, encoding="utf-8")
            return subprocess.CompletedProcess([], 1)
        output, errors = io.StringIO(), io.StringIO()
        with (
            patch.object(gate.subprocess, "run", side_effect=complete),
            redirect_stdout(output),
            redirect_stderr(errors),
        ):
            status = gate.run_gate(self.root)
        return status, output.getvalue(), errors.getvalue()

    def test_failed_case_name_message_and_first_stack_lines_reach_the_log(self) -> None:
        xml = (
            '<testsuite tests="2" failures="1" errors="0" skipped="0">'
            '<testcase classname="com.choplab.sampler.next.NextEditorDeviceTest" name="editorStarts"/>'
            '<testcase classname="com.choplab.sampler.next.NextEditorDeviceTest" name="importsAWav">'
            '<failure message="expected:&lt;a.wav&gt; but was:&lt;b.wav&gt;">java.lang.AssertionError: expected\n'
            '\tat org.junit.Assert.fail(Assert.java:89)</failure></testcase></testsuite>'
        )
        status, output, errors = self.run_gate_with(xml)
        self.assertNotEqual(status, 0)
        self.assertEqual(1, json.loads(output)["failures"], "The machine-readable line is unchanged")
        self.assertIn("FAILURE: com.choplab.sampler.next.NextEditorDeviceTest.importsAWav", errors)
        self.assertIn("expected:<a.wav> but was:<b.wav>", errors)
        self.assertIn("at org.junit.Assert.fail", errors)
        self.assertNotIn("editorStarts", errors)

    def test_report_is_bounded(self) -> None:
        body = "line\n" * 100
        cases = "".join(
            f'<testcase classname="C" name="t{index}"><error message="boom">{body}</error></testcase>'
            for index in range(30)
        )
        _, _, errors = self.run_gate_with(f'<testsuite tests="30" failures="0" errors="30" skipped="0">{cases}</testsuite>')
        self.assertEqual(10, errors.count("ERROR: C.t"))
        self.assertLessEqual(errors.count("line"), 10 * 25)
