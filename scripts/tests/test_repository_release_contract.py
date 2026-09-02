import unittest
from pathlib import Path

from scripts.release_metadata import load_release_metadata
from scripts.write_release_manifest import EXPECTED_BINARY_PATTERNS


ROOT = Path(__file__).resolve().parents[2]


class RepositoryReleaseContractTest(unittest.TestCase):
    def test_project_state_has_one_top_current_snapshot(self) -> None:
        headings = [
            line
            for line in (ROOT / "docs" / "PROJECT_STATE.md").read_text(encoding="utf-8").splitlines()
            if line.startswith("## ")
        ]

        self.assertTrue(headings[0].startswith("## Current snapshot — 2026-09-02"))
        self.assertEqual(1, sum(heading.startswith("## Current snapshot") for heading in headings))

    def test_readme_android_verifier_matches_release_metadata(self) -> None:
        metadata = load_release_metadata(ROOT / "gradle.properties")
        readme = (ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn(f"--version {metadata.version}", readme)
        self.assertIn(f"--version-code {metadata.build_number}", readme)

    def test_local_validator_runs_the_h13_input_suite(self) -> None:
        validator = (ROOT / "scripts" / "validate_project.sh").read_text(encoding="utf-8")

        self.assertIn(":desktop:desktopLongPressUiTest", validator)

    def test_h13_receipt_does_not_link_to_unpublished_local_evidence(self) -> None:
        receipt = (ROOT / "outputs" / "H13-desktop-longpress-20260901.md").read_text(encoding="utf-8")

        self.assertNotIn("](../work/h13-local", receipt)

    def test_public_release_excludes_windows_verification_artifacts(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        windows_start = workflow.index("  build-windows:")
        publish_start = workflow.index("  publish-release:")
        windows = workflow[windows_start:publish_start]
        publish = workflow[publish_start:]
        manifest_script = (ROOT / "scripts" / "write_release_manifest.py").read_text(encoding="utf-8")

        self.assertIn("name: choplab-windows-verification-assets", windows)
        self.assertNotIn("windows-app-image.zip", publish)
        self.assertEqual({"android", "ios_simulator"}, set(EXPECTED_BINARY_PATTERNS))
        self.assertIn("FORBIDDEN_PUBLIC_PATTERNS", manifest_script)


if __name__ == "__main__":
    unittest.main()
