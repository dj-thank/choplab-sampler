import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
DESKTOP_WORKFLOW = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")


def job_body(name: str, next_name: str) -> str:
    start = WORKFLOW.index(f"  {name}:")
    end = WORKFLOW.index(f"  {next_name}:", start)
    return WORKFLOW[start:end]


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_source_notice_sidecar_is_required_scanned_and_in_both_exact_allowlists(self):
        publish = WORKFLOW.split("  publish-release:", 1)[1]
        self.assertIn("scripts/prepare_source_notices.py --platform all --require-committed", publish)
        self.assertEqual(2, publish.count('            "ChopLab-${RELEASE_TAG}-source-notices.zip"\n'))
        self.assertEqual(2, publish.count('            "ChopLab-${RELEASE_TAG}-source-notices.zip.sha256"\n'))
        self.assertIn('--archive "dist/ChopLab-${RELEASE_TAG}-source-notices.zip"', publish)
        self.assertLess(publish.index("scripts/prepare_source_notices.py"), publish.index("Scan final public archives"))
        self.assertLess(publish.index("scripts/write_release_manifest.py"), publish.index("- name: Attest build provenance"))

    def test_android_release_runs_shared_host_contract(self) -> None:
        android = job_body("build-android", "build-windows")

        self.assertIn(":shared:testAndroidHostTest", android)
        self.assertLess(
            android.index(":shared:testAndroidHostTest"),
            android.index(":app:assembleRelease"),
        )

    def test_windows_release_runs_shared_desktop_contract(self) -> None:
        windows = job_body("build-windows", "publish-release")

        self.assertIn(":shared:desktopTest", windows)
        self.assertLess(
            windows.index(":shared:desktopTest"),
            windows.index(":desktop:packageWindows"),
        )

    def test_windows_release_runs_h13_input_contract(self) -> None:
        windows = job_body("build-windows", "publish-release")

        self.assertIn(":desktop:desktopLongPressUiTest", windows)
        self.assertLess(
            windows.index(":desktop:desktopLongPressUiTest"),
            windows.index(":desktop:packageWindows"),
        )

    def test_desktop_pr_workflow_runs_h13_input_contract(self) -> None:
        self.assertIn(":desktop:desktopLongPressUiTest", DESKTOP_WORKFLOW)
        self.assertLess(
            DESKTOP_WORKFLOW.index(":desktop:desktopLongPressUiTest"),
            DESKTOP_WORKFLOW.index(":desktop:packageWindows"),
        )


if __name__ == "__main__":
    unittest.main()
