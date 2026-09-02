import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
DESKTOP_WORKFLOW = (ROOT / ".github" / "workflows" / "desktop.yml").read_text(encoding="utf-8")


def workflow_jobs(path: Path) -> dict[str, dict[str, object]]:
    content = path.read_text(encoding="utf-8")
    matches = re.findall(r"(?m)^  ([A-Za-z0-9_-]+):\n    name: ([^\n]+)$", content)
    return {job_id: {"name": name} for job_id, name in matches}


def job_body(name: str, next_name: str) -> str:
    start = WORKFLOW.index(f"  {name}:")
    end = WORKFLOW.index(f"  {next_name}:", start)
    return WORKFLOW[start:end]


class ReleaseWorkflowContractTest(unittest.TestCase):
    def test_android_release_runs_shared_host_contract(self) -> None:
        android = job_body("build-android", "build-ios")

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

    def test_platform_required_jobs_have_explicit_unique_names(self) -> None:
        paths = [
            ROOT / ".github" / "workflows" / "android.yml",
            ROOT / ".github" / "workflows" / "ios.yml",
            ROOT / ".github" / "workflows" / "desktop.yml",
            ROOT / ".github" / "workflows" / "security.yml",
        ]
        names = [
            str(next(iter(workflow_jobs(path).values()))["name"])
            for path in paths
        ]

        self.assertEqual(4, len(set(names)))
        self.assertTrue(all(names))

    def test_pr_checks_run_once_per_pull_request_and_main_pushes_still_run(self) -> None:
        for filename in ("android.yml", "ios.yml", "desktop.yml", "security.yml"):
            workflow = (ROOT / ".github" / "workflows" / filename).read_text(encoding="utf-8")
            self.assertRegex(workflow, r"(?m)^  push:\n    branches:\n      - main\s*$")
            self.assertRegex(workflow, r"(?m)^  pull_request:\s*(?:#.*)?$")

    def test_desktop_required_check_is_not_suppressed_by_a_pull_request_path_filter(self) -> None:
        self.assertRegex(DESKTOP_WORKFLOW, r"(?m)^  pull_request:\s*$")
        self.assertNotRegex(DESKTOP_WORKFLOW, r"(?m)^  pull_request:\n\s+paths:")

    def test_release_checks_both_annotated_tag_boundaries_and_history_monotonicity(self) -> None:
        metadata = job_body("metadata", "build-android")
        publish = WORKFLOW[WORKFLOW.index("  publish-release:") :]

        for body in (metadata, publish):
            self.assertIn("--verify-annotated-tag", body)
            self.assertIn("--commit", body)
        self.assertIn("--check-monotonic", metadata)
        self.assertIn("--check-monotonic", publish)
        self.assertIn("fetch-depth: 0", publish)

    def test_public_debug_apk_is_bound_to_prebuild_debug_keystore_identity(self) -> None:
        android = job_body("build-android", "build-ios")
        capture = android.index("Capture debug signing identity before build")
        build = android.index(":app:assembleDebug")
        verify = android.index("--allow-debug-preview")

        self.assertLess(capture, build)
        self.assertLess(build, verify)
        self.assertIn("debug.keystore", android)
        self.assertIn("read_android_debug_certificate.py", android)
        self.assertIn("--expected-cert-sha256", android)
        self.assertIn("CHOPLAB_DEBUG_CERT_SHA256", android)
        self.assertNotIn("keytool -exportcert", android)


if __name__ == "__main__":
    unittest.main()
