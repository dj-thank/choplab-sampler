from io import BytesIO
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import prepare_windows_runtime as runtime
from scripts.check_public_surface import packaged_runtime_digest_findings


class WindowsRuntimeTest(unittest.TestCase):
    def fixture(self, directory):
        source = directory / "upstream.jar"
        with zipfile.ZipFile(source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in {
                "ai/onnxruntime/OnnxTensor.class": b"API unchanged",
                "META-INF/maven/pom.xml": b"provenance",
                "ThirdPartyNotices.txt": b"attribution unchanged",
                "ai/onnxruntime/native/win-x64/onnxruntime.dll": b"windows unchanged",
                "ai/onnxruntime/native/linux-x64/libonnxruntime.so": b"other platform",
                "ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib": b"other platform",
            }.items(): archive.writestr(name, data)
        rows = runtime.selected_entries(source)
        derived = directory / "sample.jar"; runtime.write_jar(rows, derived)
        entries = runtime.entry_manifest(rows)
        pins = {"upstream": {"bytes": source.stat().st_size, "sha256": runtime.sha256(source.read_bytes())},
                "windows": {"filename": "onnxruntime-test.jar", "bytes": derived.stat().st_size,
                            "sha256": runtime.sha256(derived.read_bytes()), "entryCount": len(entries),
                            "entriesSha256": runtime.entries_digest(entries)}}
        return source, pins

    def test_keeps_all_java_metadata_notice_and_target_native_bytes_and_is_reproducible(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); source, pins = self.fixture(directory)
            output = directory / "derived.jar"; receipt = directory / "receipt.json"
            runtime.derive(source, output, receipt, pins)
            with zipfile.ZipFile(source) as upstream, zipfile.ZipFile(output) as derived:
                expected = {name for name in upstream.namelist() if not name.startswith(runtime.NATIVE) or "/win-x64/" in name}
                self.assertEqual(expected, set(derived.namelist()))
                for name in expected: self.assertEqual(upstream.read(name), derived.read(name))
            before = output.read_bytes()
            runtime.derive(source, output, receipt, pins)
            self.assertEqual(before, output.read_bytes())
            source.write_bytes(source.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "upstream artifact"):
                runtime.derive(source, output, receipt, pins)
            self.assertEqual(before, output.read_bytes(), "Refusal preserves the prior valid output")

    def test_runtime_scanner_binds_bytes_and_entry_receipt_to_the_repository_pin(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary); source, pins = self.fixture(directory)
            output = directory / pins["windows"]["filename"]; receipt = directory / "receipt.json"
            runtime.derive(source, output, receipt, pins)
            root = directory / "repo"; (root / "config").mkdir(parents=True)
            (root / "config/windows-onnxruntime.json").write_text(json.dumps(pins))
            entry = PurePosixPath("ChopLab/app/") / output.name
            with patch("scripts.check_public_surface.REPOSITORY_ROOT", root), zipfile.ZipFile(BytesIO(), "w") as archive:
                self.assertTrue(packaged_runtime_digest_findings(archive, entry, output.read_bytes(), output.stat().st_size, "fixture"))
                archive.writestr("ChopLab/app/onnxruntime-windows.json", receipt.read_bytes())
                self.assertEqual([], packaged_runtime_digest_findings(archive, entry, output.read_bytes(), output.stat().st_size, "fixture"))
                changed = output.read_bytes()[:-1] + b"!"
                self.assertTrue(packaged_runtime_digest_findings(archive, entry, changed, len(changed), "fixture"))
                self.assertTrue(packaged_runtime_digest_findings(archive, entry.with_name("onnxruntime-other.jar"), output.read_bytes(), output.stat().st_size, "fixture"))

    def test_missing_target_and_duplicate_or_signed_entries_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bad.jar"
            with zipfile.ZipFile(path, "w") as archive: archive.writestr("test.class", b"fixture")
            with self.assertRaisesRegex(ValueError, "Windows native"):
                runtime.selected_entries(path)
            with zipfile.ZipFile(path, "w") as archive: archive.writestr("META-INF/SIGNER.SF", b"fixture")
            with self.assertRaisesRegex(ValueError, "Signed JAR"):
                runtime.selected_entries(path)
