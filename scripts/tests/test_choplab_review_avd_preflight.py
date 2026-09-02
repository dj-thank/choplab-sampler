import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt" and shutil.which("pwsh"), "Windows PowerShell contract")
class ChopLabReviewAvdPreflightTest(unittest.TestCase):
    def setUp(self) -> None:
        self.root = Path(__file__).resolve().parents[2]
        self.script = self.root / "scripts" / "check-choplab-review-avd.ps1"
        self.temp = tempfile.TemporaryDirectory(prefix="choplab-avd-preflight-")
        self.base = Path(self.temp.name)
        self.sdk = self.base / "sdk"
        self.avd_home = self.base / "avd"
        (self.sdk / "system-images" / "android-36" / "google_apis_playstore" / "x86_64").mkdir(
            parents=True,
        )
        self.avd_home.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_preflight(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "pwsh",
                "-NoProfile",
                "-File",
                str(self.script),
                "-AndroidSdkRoot",
                str(self.sdk),
                "-AvdHome",
                str(self.avd_home),
            ],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def write_complete_avd(self) -> None:
        name = "choplab_review_api36_play"
        avd = self.avd_home / f"{name}.avd"
        avd.mkdir()
        (avd / "config.ini").write_text(
            "\n".join(
                [
                    f"avd.id={name}",
                    f"avd.name={name}",
                    "PlayStore.enabled=yes",
                    "hw.lcd.width=1080",
                    "hw.lcd.height=2400",
                    "hw.lcd.density=420",
                    "hw.ramSize=4096",
                    "image.sysdir.1=system-images\\android-36\\google_apis_playstore\\x86_64\\",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        (self.avd_home / f"{name}.ini").write_text(
            "avd.ini.encoding=UTF-8\n",
            encoding="utf-8",
        )

    def test_complete_pinned_avd_can_run_without_provisioning_tool(self) -> None:
        self.write_complete_avd()

        result = self.run_preflight()

        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(result.stdout)
        self.assertEqual("READY_TO_PROVISION_OR_RUN", receipt["status"])
        self.assertTrue(receipt["existingPinnedAvd"])
        self.assertFalse(receipt["avdManagerPresent"])
        self.assertEqual([], receipt["reasons"])

    def test_missing_avd_still_requires_provisioning_tool(self) -> None:
        result = self.run_preflight()

        self.assertEqual(2, result.returncode)
        receipt = json.loads(result.stdout)
        self.assertEqual("BLOCKED", receipt["status"])
        self.assertFalse(receipt["existingPinnedAvd"])
        self.assertIn("avdmanager.bat is absent", receipt["reasons"])


if __name__ == "__main__":
    unittest.main()
