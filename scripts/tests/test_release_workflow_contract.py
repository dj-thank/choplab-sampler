import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")


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


if __name__ == "__main__":
    unittest.main()
