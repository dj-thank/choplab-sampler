from __future__ import annotations

import unittest
from pathlib import Path, PurePosixPath

from scripts.check_public_surface import scan_text, suspicious_path


class PublicSurfacePolicyTest(unittest.TestCase):
    def test_rejects_signing_and_audio_paths(self) -> None:
        self.assertEqual("signing or private-key material", suspicious_path(PurePosixPath("keys/release.jks")))
        self.assertEqual("audio asset", suspicious_path(PurePosixPath("samples/user.wav")))

    def test_rejects_secret_shaped_text(self) -> None:
        token = "github_pat_" + "a" * 24
        self.assertTrue(scan_text("fixture", token))

    def test_accepts_documentation_and_source_paths(self) -> None:
        self.assertIsNone(suspicious_path(PurePosixPath("docs/release-signing.md")))
        self.assertIsNone(suspicious_path(PurePosixPath("app/src/main/MainActivity.kt")))

    def test_desktop_source_snapshot_is_scanned_before_archive_and_upload(self) -> None:
        workflow = (
            Path(__file__).resolve().parents[2] / ".github" / "workflows" / "desktop.yml"
        ).read_text(encoding="utf-8")

        policy_tests = workflow.index("python -m unittest discover -s scripts/tests")
        public_scan = workflow.index("python scripts/check_public_surface.py")
        source_archive = workflow.index("git archive --format=zip")
        source_upload = workflow.index("name: choplab-source-snapshot")

        self.assertLess(policy_tests, public_scan)
        self.assertLess(public_scan, source_archive)
        self.assertLess(source_archive, source_upload)


if __name__ == "__main__":
    unittest.main()
