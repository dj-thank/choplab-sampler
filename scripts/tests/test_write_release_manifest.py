from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from scripts.write_release_manifest import write_manifest


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(tempfile.mkdtemp(prefix="choplab-release-manifest-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.directory, ignore_errors=True))
        for name, content in {
            "ChopLab-v0.16.2-android.apk": b"android",
            "ChopLab-v0.16.2-ios-simulator.app.zip": b"ios",
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
        (self.directory / "ChopLab-v0.16.2-android.apk").unlink()

        with self.assertRaisesRegex(ValueError, "exactly one android"):
            self.write()

    def test_rejects_missing_required_checksum_sidecar(self) -> None:
        (self.directory / "ChopLab-v0.16.2-ios-simulator.app.zip.sha256").unlink()

        with self.assertRaisesRegex(ValueError, "Missing checksum sidecar"):
            self.write()

    def test_rejects_checksum_mismatch(self) -> None:
        sidecar = self.directory / "ChopLab-v0.16.2-windows-app-image.zip.sha256"
        sidecar.write_text(
            f"{'0' * 64}  ChopLab-v0.16.2-windows-app-image.zip\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
            self.write()

    def test_rejects_sidecar_that_declares_another_asset(self) -> None:
        sidecar = self.directory / "ChopLab-v0.16.2-android.apk.sha256"
        digest = hashlib.sha256(b"android").hexdigest()
        sidecar.write_text(
            f"{digest}  ChopLab-v0.16.2-ios-simulator.app.zip\n",
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

    def test_rejects_tag_version_mismatch(self) -> None:
        with self.assertRaisesRegex(ValueError, "Tag/version mismatch"):
            self.write(tag="v0.16.1")


if __name__ == "__main__":
    unittest.main()
