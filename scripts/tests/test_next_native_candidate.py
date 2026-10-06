import hashlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import prepare_next_native_candidate as candidate
from scripts import windows_audio_runtime


class NextNativeCandidateTest(unittest.TestCase):
    def test_upstream_download_is_bounded_verified_and_reuses_only_matching_cache(self):
        data = b"owned upstream fixture"
        pin = {"url": "https://example.invalid/pinned.aar", "sha256": hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "upstream.aar"
            with patch.object(candidate.urllib.request, "urlopen", return_value=io.BytesIO(data)) as request:
                self.assertEqual(target, candidate.fetch_upstream(pin, target))
            request.assert_called_once()
            with patch.object(candidate.urllib.request, "urlopen", side_effect=AssertionError("must use cache")):
                self.assertEqual(target, candidate.fetch_upstream(pin, target))
            target.write_bytes(b"changed cache")
            with self.assertRaisesRegex(ValueError, "Cached upstream"):
                candidate.fetch_upstream(pin, target)
            self.assertEqual(b"changed cache", target.read_bytes())
            target.unlink()
            with patch.object(candidate.urllib.request, "urlopen", return_value=io.BytesIO(b"wrong download")):
                with self.assertRaisesRegex(ValueError, "Downloaded upstream"):
                    candidate.fetch_upstream(pin, target)
            with patch.object(candidate, "UPSTREAM_LIMIT", 4), \
                 patch.object(candidate.urllib.request, "urlopen", return_value=io.BytesIO(data)):
                with self.assertRaisesRegex(ValueError, "download bound"):
                    candidate.fetch_upstream(pin, target)
            self.assertEqual([], list(target.parent.iterdir()))

    def test_unowned_work_and_unknown_rebuilt_bytes_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "user-data").write_text("preserve")
            with self.assertRaisesRegex(ValueError, "not owned"):
                candidate.own_work(root)
            self.assertEqual("preserve", (root / "user-data").read_text())
            work = root / "private-build"
            candidate.own_work(work)
            candidate.own_work(work)
            output = work / "rebuilt.aar"
            output.write_bytes(b"new compiler output")
            pin = candidate.identity(output)
            candidate.require_identity(output, pin)
            for changed in ({**pin, "bytes": pin["bytes"] + 1}, {**pin, "sha256": "0" * 64}):
                with self.assertRaisesRegex(ValueError, "differs from repository pins"):
                    candidate.require_identity(output, changed)
            linked = root / "linked-work"
            linked.symlink_to(work, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "private native build"):
                candidate.own_work(linked)

    def windows_fixture(self, root, change=None):
        profile = {"schema": 1, "id": "owned-fixture", "source": "reviewed-source"}
        payload = {"ffmpeg.exe": b"fixture ffmpeg", "ffprobe.exe": b"fixture ffprobe", "codec.dll": b"fixture codec",
                   "FFmpeg-runtime.json": b"{}", "FFmpeg-SOURCES.json": json.dumps(profile).encode(),
                   "FFmpeg-LICENSE.txt": b"fixture license"}
        pins = {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()} for name, data in payload.items()}
        profile["derived"] = {"profileInputsSha256": windows_audio_runtime.inputs_hash(profile), "files": pins}
        archive = root / "candidate.zip"
        if change == "missing":
            payload.pop("codec.dll")
        elif change == "content":
            payload["codec.dll"] = b"changed codec"
        elif change == "extra":
            payload["../escape"] = b"unsafe"
        with zipfile.ZipFile(archive, "w") as target:
            for name, data in payload.items():
                item = zipfile.ZipInfo(name)
                item.external_attr = ((stat.S_IFLNK if change == "symlink" and name == "codec.dll" else stat.S_IFREG) | 0o644) << 16
                target.writestr(item, data)
        profile["derived"]["archive"] = candidate.identity(archive)
        return archive, profile

    def test_windows_transfer_requires_outer_pin_complete_inner_pins_and_new_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive, profile = self.windows_fixture(root)
            output = root / "runtime"
            candidate.unpack_windows(archive, output, profile)
            self.assertEqual(set(profile["derived"]["files"]), set(windows_audio_runtime.validate_directory(output, profile)))
            (output / "sentinel").write_text("preserve")
            with self.assertRaisesRegex(ValueError, "new Windows candidate"):
                candidate.unpack_windows(archive, output, profile)
            self.assertEqual("preserve", (output / "sentinel").read_text())
            archive.write_bytes(archive.read_bytes() + b"unreviewed")
            with self.assertRaisesRegex(ValueError, "differs from repository pins"):
                candidate.unpack_windows(archive, root / "changed", profile)
            self.assertFalse((root / "changed").exists())

    def test_self_reported_archive_hash_cannot_authorize_bad_members_or_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for change in ("missing", "extra", "content", "symlink"):
                with self.subTest(change=change):
                    archive, profile = self.windows_fixture(root, change)
                    with self.assertRaises(ValueError):
                        candidate.unpack_windows(archive, root / change, profile)
                    self.assertFalse((root / change).exists())
            self.assertFalse((root / "escape").exists())

    def test_unreviewed_linux_compiler_is_not_a_windows_build_fallback(self):
        with patch.object(candidate.platform, "system", return_value="Linux"), \
             patch.object(candidate.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "requires macOS"):
                candidate.build_windows(Path("unused"), 2)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
