from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.write_release_manifest import write_manifest
from scripts import prepare_source_notices as notices
from scripts.tests.test_source_notices import committed_git


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="choplab-release-manifest-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.directory, ignore_errors=True))
        with tempfile.TemporaryDirectory() as output, patch.object(notices, "git", side_effect=committed_git):
            source_archive = self.directory / "ChopLab-v0.16.2-source-notices.zip"
            notices.stage(Path(__file__).resolve().parents[2], Path(output), "all", archive=source_archive)
        source_archive.with_suffix(".zip.sha256").write_text(
            f"{hashlib.sha256(source_archive.read_bytes()).hexdigest()}  {source_archive.name}\n")
        for name, content in {
            "ChopLab-v0.16.2-android-release.apk": b"android",
            "ChopLab-v0.16.2-windows-app-image.zip": b"windows",
            "ChopLab-v0.16.2-sbom.cdx.json": b"{}",
        }.items():
            (self.directory / name).write_bytes(content)
            digest = hashlib.sha256(content).hexdigest()
            (self.directory / f"{name}.sha256").write_text(
                f"{digest}  {name}\n",
                encoding="utf-8",
            )

    def write(self, **overrides: object) -> dict[str, object]:
        values: dict[str, object] = {
            "directory": self.directory,
            "output": self.directory / "ChopLab-v0.16.2-release-manifest.json",
            "version": "0.16.2",
            "build_number": 26,
            "tag": "v0.16.2",
            "commit": "a" * 40,
            "repository": "dj-thank/choplab-sampler",
            "workflow_run_id": "123",
        }
        values.update(overrides)
        with patch.object(notices, "git", side_effect=committed_git):
            return write_manifest(**values)  # type: ignore[arg-type]

    def test_writes_sorted_hash_bound_assets(self) -> None:
        payload = self.write()

        assets = payload["assets"]
        self.assertIsInstance(assets, list)
        names = [asset["name"] for asset in assets]  # type: ignore[index]
        self.assertEqual(sorted(names), names)
        self.assertNotIn("ChopLab-v0.16.2-release-manifest.json", names)
        self.assertEqual("a" * 40, payload["source"]["commit"])  # type: ignore[index]

    def test_rejects_missing_platform_artifact(self) -> None:
        (self.directory / "ChopLab-v0.16.2-android-release.apk").unlink()

        with self.assertRaisesRegex(ValueError, "exactly one android"):
            self.write()

    def test_rejects_missing_required_checksum_sidecar(self) -> None:
        (self.directory / "ChopLab-v0.16.2-windows-app-image.zip.sha256").unlink()

        with self.assertRaisesRegex(ValueError, "Missing checksum sidecar"):
            self.write()

    def test_rejects_sbom_with_text_after_the_exact_version(self) -> None:
        original = self.directory / "ChopLab-v0.16.2-sbom.cdx.json"
        original_sidecar = self.directory / "ChopLab-v0.16.2-sbom.cdx.json.sha256"
        extra = self.directory / "ChopLab-v0.16.2-extra-sbom.cdx.json"
        extra_sidecar = self.directory / "ChopLab-v0.16.2-extra-sbom.cdx.json.sha256"
        original.rename(extra)
        original_sidecar.rename(extra_sidecar)
        extra_sidecar.write_text(
            f"{hashlib.sha256(b'{}').hexdigest()}  {extra.name}\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "Expected exact release SBOM"):
            self.write()

    def test_rejects_checksum_mismatch(self) -> None:
        sidecar = self.directory / "ChopLab-v0.16.2-android-release.apk.sha256"
        sidecar.write_text(
            f"{'0' * 64}  ChopLab-v0.16.2-android-release.apk\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self.write()

    def test_rejects_debug_binary_on_the_public_surface(self) -> None:
        name = "ChopLab-v0.16.2-android-debug.apk"
        content = b"windows"
        (self.directory / name).write_bytes(content)
        (self.directory / f"{name}.sha256").write_text(
            f"{hashlib.sha256(content).hexdigest()}  {name}\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "Forbidden public debug_android binary"):
            self.write()

    def test_rejects_sidecar_that_declares_another_asset(self) -> None:
        sidecar = self.directory / "ChopLab-v0.16.2-android-release.apk.sha256"
        digest = hashlib.sha256(b"android").hexdigest()
        sidecar.write_text(
            f"{digest}  ChopLab-v0.16.2-windows-app-image.zip\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "filename mismatch"):
            self.write()

    def test_rejects_orphan_checksum_sidecar(self) -> None:
        (self.directory / "orphan.bin.sha256").write_text(
            f"{'0' * 64}  orphan.bin\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "no published target"):
            self.write()

    def test_rejects_unexpected_release_file(self):
        (self.directory / "extra.txt").write_text("unreviewed", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unexpected release asset"):
            self.write()

    def test_rejects_missing_source_notice_checksum(self):
        (self.directory / "ChopLab-v0.16.2-source-notices.zip.sha256").unlink()
        with self.assertRaisesRegex(ValueError, "Missing checksum sidecar"):
            self.write()

    def test_rejects_source_notice_from_other_commit(self):
        with self.assertRaisesRegex(ValueError, "Source-notice revision"):
            self.write(commit="b" * 40)

    def test_rejects_directory_entry(self):
        (self.directory / "unexpected").mkdir()
        with self.assertRaisesRegex(ValueError, "Non-regular release asset"):
            self.write()

    def test_rejects_tag_version_mismatch(self) -> None:
        with self.assertRaisesRegex(ValueError, "Tag/version mismatch"):
            self.write(tag="v0.16.1")


if __name__ == "__main__":
    unittest.main()
