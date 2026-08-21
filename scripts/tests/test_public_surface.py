from __future__ import annotations

import unittest
from pathlib import PurePosixPath

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


if __name__ == "__main__":
    unittest.main()
