import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

from scripts.measure_distribution import measure


class DistributionSizeTest(unittest.TestCase):
    def test_android_is_arm64_only_and_counts_actual_zip_and_expanded_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "unsigned.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("lib/arm64-v8a/test.so", b"fixture" * 100)
                archive.writestr("classes.dex", b"dex fixture")
            report = measure(apk, "android-release")
            self.assertEqual(apk.stat().st_size, report["bytes"])
            self.assertEqual(711, report["expandedBytes"])
            self.assertEqual("PASS", report["sizeGate"])
            probe = measure(apk, "android-next-size-probe")
            self.assertTrue(probe["candidateOnly"])
            self.assertEqual(50_000_000, probe["limitBytes"])
            self.assertEqual(report["sha256"], probe["sha256"])
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/test.so", b"emulator")
            with self.assertRaisesRegex(ValueError, "exactly arm64"):
                measure(apk, "android-release")

    def test_windows_initial_and_final_budgets_are_distinct(self):
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "windows.zip"
            with zipfile.ZipFile(package, "w") as archive:
                archive.writestr("ChopLab/app/fixture.jar", "synthetic")
            report = measure(package, "windows")
            self.assertEqual(200_000_000, report["limitBytes"])
            self.assertEqual(150_000_000, report["finalTargetBytes"])
            self.assertEqual(9, report["components"]["app"]["expandedBytes"])

    def test_named_windows_next_candidate_requires_final_150mb_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "windows.zip"
            with zipfile.ZipFile(package, "w") as archive:
                archive.writestr("ChopLab Preview/app/fixture.jar", "synthetic")
            for size, expected in ((150_000_000, "PASS"), (150_000_001, "FAIL")):
                with self.subTest(size=size), patch.object(Path, "stat", return_value=SimpleNamespace(st_size=size)):
                    report = measure(package, "windows-next-candidate")
                    self.assertEqual(expected, report["sizeGate"])
                    self.assertTrue(report["candidateOnly"])
                    self.assertEqual(150_000_000, report["limitBytes"])
                    self.assertEqual("PASS", measure(package, "windows")["sizeGate"])
