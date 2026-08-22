from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.release_metadata import load_release_metadata, validate_tag


class ReleaseMetadataTest(unittest.TestCase):
    def write_properties(self, content: str) -> Path:
        temporary = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False)
        with temporary:
            temporary.write(content)
        return Path(temporary.name)

    def test_reads_version_and_build_number(self) -> None:
        path = self.write_properties("choplabVersion=0.16.2\nchoplabBuildNumber=26\n")
        self.addCleanup(path.unlink)

        metadata = load_release_metadata(path)

        self.assertEqual("0.16.2", metadata.version)
        self.assertEqual(26, metadata.build_number)
        self.assertEqual("v0.16.2", metadata.tag)

    def test_rejects_prerelease_suffix_that_jpackage_cannot_embed_exactly(self) -> None:
        path = self.write_properties("choplabVersion=0.16.2-preview.1\nchoplabBuildNumber=26\n")
        self.addCleanup(path.unlink)

        with self.assertRaisesRegex(ValueError, "numeric SemVer"):
            load_release_metadata(path)

    def test_rejects_non_positive_build_number(self) -> None:
        path = self.write_properties("choplabVersion=0.16.2\nchoplabBuildNumber=0\n")
        self.addCleanup(path.unlink)

        with self.assertRaisesRegex(ValueError, "positive"):
            load_release_metadata(path)

    def test_tag_must_match_exactly(self) -> None:
        path = self.write_properties("choplabVersion=0.16.2\nchoplabBuildNumber=26\n")
        self.addCleanup(path.unlink)
        metadata = load_release_metadata(path)

        validate_tag(metadata, "v0.16.2")
        with self.assertRaisesRegex(ValueError, "tag mismatch"):
            validate_tag(metadata, "v0.16.1")


if __name__ == "__main__":
    unittest.main()
