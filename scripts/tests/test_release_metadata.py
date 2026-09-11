from __future__ import annotations

import tempfile
import subprocess
import unittest
from pathlib import Path

from scripts.release_metadata import (
    ReleaseMetadata,
    load_release_metadata,
    parse_release_tag,
    validate_annotated_tag,
    validate_annotated_tag_in_repository,
    validate_monotonic_release_metadata,
    validate_reachable_tag_monotonicity,
    validate_tag,
)


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

    def test_release_tag_parser_accepts_historical_preview_suffix(self) -> None:
        self.assertEqual((0, 16, 1), parse_release_tag("v0.16.1-preview.1"))
        self.assertEqual((0, 17, 0), parse_release_tag("v0.17.0"))

    def test_annotated_tag_requires_tag_object_and_exact_peeled_commit(self) -> None:
        validate_annotated_tag(
            tag="v0.17.1",
            expected_commit="a" * 40,
            tag_object_type="tag",
            peeled_commit="a" * 40,
        )

        with self.assertRaisesRegex(ValueError, "annotated"):
            validate_annotated_tag(
                tag="v0.17.1",
                expected_commit="a" * 40,
                tag_object_type="commit",
                peeled_commit="a" * 40,
            )
        with self.assertRaisesRegex(ValueError, "peeled commit"):
            validate_annotated_tag(
                tag="v0.17.1",
                expected_commit="a" * 40,
                tag_object_type="tag",
                peeled_commit="b" * 40,
            )

    def test_repository_tag_check_rejects_lightweight_and_wrong_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(
                ["git", "init", "--initial-branch", "main"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=repository, check=True
            )
            source = repository / "gradle.properties"
            source.write_text(
                "choplabVersion=0.17.1\nchoplabBuildNumber=28\n",
                encoding="utf-8",
            )
            subprocess.run(["git", "add", "gradle.properties"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-m", "fixture"], cwd=repository, check=True)
            commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repository,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            subprocess.run(
                ["git", "tag", "-a", "v0.17.1", "-m", "release"],
                cwd=repository,
                check=True,
            )

            validate_annotated_tag_in_repository(
                repository,
                tag="v0.17.1",
                expected_commit=commit,
            )
            with self.assertRaisesRegex(ValueError, "peeled commit"):
                validate_annotated_tag_in_repository(
                    repository,
                    tag="v0.17.1",
                    expected_commit="b" * 40,
                )

            subprocess.run(["git", "tag", "v0.17.2"], cwd=repository, check=True)
            with self.assertRaisesRegex(ValueError, "annotated"):
                validate_annotated_tag_in_repository(
                    repository,
                    tag="v0.17.2",
                    expected_commit=commit,
                )

            blob = subprocess.run(
                ["git", "hash-object", "-w", "--stdin"],
                cwd=repository,
                input="not a commit",
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            subprocess.run(
                ["git", "tag", "-a", "v0.17.3", blob, "-m", "invalid release target"],
                cwd=repository,
                check=True,
            )
            with self.assertRaisesRegex(ValueError, "commit object"):
                validate_annotated_tag_in_repository(
                    repository,
                    tag="v0.17.3",
                    expected_commit=commit,
                )

    def test_release_metadata_must_increase_version_and_build(self) -> None:
        previous = [
            ReleaseMetadata(version="0.16.1", build_number=25),
            ReleaseMetadata(version="0.17.0", build_number=27),
        ]
        validate_monotonic_release_metadata(
            ReleaseMetadata(version="0.17.1", build_number=28), previous
        )
        validate_monotonic_release_metadata(
            ReleaseMetadata(version="0.17.0", build_number=28),
            [ReleaseMetadata(version="0.17.0", build_number=27)],
        )

        with self.assertRaisesRegex(ValueError, "monotonic"):
            validate_monotonic_release_metadata(
                ReleaseMetadata(version="0.17.1", build_number=26), previous
            )
        with self.assertRaisesRegex(ValueError, "monotonic"):
            validate_monotonic_release_metadata(
                ReleaseMetadata(version="0.16.0", build_number=28), previous
            )

    def test_current_repository_metadata_is_ahead_of_reachable_release_tags(self) -> None:
        root = Path(__file__).resolve().parents[2]

        validate_reachable_tag_monotonicity(
            root,
            load_release_metadata(root / "gradle.properties"),
            reachable_ref="HEAD",
        )

    def test_xcodegen_defaults_match_release_metadata(self) -> None:
        root = Path(__file__).resolve().parents[2]
        metadata = load_release_metadata(root / "gradle.properties")
        xcodegen = (root / "ios" / "project.yml").read_text(encoding="utf-8")

        self.assertIn(f'MARKETING_VERSION: "{metadata.version}"', xcodegen)
        self.assertIn(f'CURRENT_PROJECT_VERSION: "{metadata.build_number}"', xcodegen)


if __name__ == "__main__":
    unittest.main()
