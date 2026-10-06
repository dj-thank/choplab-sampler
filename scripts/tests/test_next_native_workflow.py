from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/next-native-candidate.yml").read_text()


def job(name):
    return re.search(
        rf"^  {name}:\n.*?(?=^  [a-z-]+:\n|\Z)", WORKFLOW, re.M | re.S).group()


class NextNativeWorkflowTest(unittest.TestCase):
    def test_only_explicit_candidate_artifacts_and_no_release_mutation(self):
        trigger = WORKFLOW.split("on:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual("  workflow_dispatch:", trigger.strip("\n"))
        self.assertIn("  contents: read", WORKFLOW)
        for forbidden in ("contents: write", "secrets.", "gh release", ":app:assembleRelease", ":desktop:packageWindows ", "--measurement-only"):
            self.assertNotIn(forbidden, WORKFLOW)
        for platform in ("android", "windows"):
            self.assertIn(f"name: choplab-{platform}-next-native-candidate", WORKFLOW)
        for action in re.findall(r"uses: ([^\s#]+)", WORKFLOW):
            self.assertRegex(action, r"@[0-9a-f]{40}$")

    def test_android_rebuilds_both_inputs_and_checks_matching_pair_before_upload(self):
        body = job("android-next")
        self.assertIn("runs-on: macos-15", body)
        self.assertIn("prepare_next_native_candidate.py android", body)
        self.assertIn("-PchoplabAndroidAudioRuntime=work/next-native-android/candidate/ffmpeg-audio-arm64.aar", body)
        self.assertIn("-PchoplabAndroidPythonRuntime=work/next-native-android/candidate/library-linkage-arm64.aar", body)
        self.assertIn("-PchoplabNextSizeProbe=true", body)
        self.assertIn(":app:assemblePreview :app:assemblePreviewAndroidTest", body)
        self.assertIn("--test-configuration app/build/outputs/mapping/previewAndroidTest/configuration.txt", body)
        self.assertLess(body.index("verify_android_next_probe.py"), body.index("uses: actions/upload-artifact"))
        self.assertLess(body.index("check_public_surface.py"), body.index("uses: actions/upload-artifact"))
        self.assertIn("app-preview-unsigned.apk", body)

    def test_windows_transfer_and_150mb_gate_precede_named_upload(self):
        self.assertIn("runs-on: macos-15", job("windows-native"))
        body = job("windows-next")
        self.assertIn("needs: windows-native", body)
        self.assertLess(body.index("unpack-windows"), body.index(":desktop:packageWindowsLinkedPreview"))
        self.assertIn("-PchoplabWindowsAudioRuntime=work/next-native-runtime", body)
        self.assertIn("scripts/acceptance/windows_audio_tools.py", body)
        self.assertIn("-CompressionLevel Optimal", body)
        self.assertIn("--kind windows-next-candidate", body)
        self.assertIn("scripts/run_windows_acceptance.py --archive $zip.FullName --sha256 $archiveSha --source-revision $env:GITHUB_SHA", body)
        self.assertIn("--ejs-python $acceptancePython", body)
        self.assertIn("--text-file dist/windows-next-native-candidate/packaged-acceptance.json", body)
        self.assertLess(body.index("measure_distribution.py"), body.index("uses: actions/upload-artifact"))
        self.assertLess(body.index("run_windows_acceptance.py"), body.index("Copy-Item work/windows-next-acceptance/acceptance.json"))
        self.assertLess(body.index("packaged-acceptance.json"), body.index("uses: actions/upload-artifact"))
        self.assertLess(body.index("check_public_surface.py"), body.index("uses: actions/upload-artifact"))
        self.assertIn("-SilentAudio", body)

    def test_every_candidate_contains_a_scanned_committed_notice_sidecar(self):
        for name in ("android-next", "windows-native", "windows-next"):
            with self.subTest(job=name):
                body = job(name)
                self.assertIn("scripts/prepare_source_notices.py", body)
                self.assertIn("--require-committed", body)
                self.assertRegex(body, r"--archive [^\s]+/source-notices\.zip")
                self.assertLess(body.index("scripts/prepare_source_notices.py"), body.index("uses: actions/upload-artifact"))
                self.assertLess(body.rindex("check_public_surface.py"), body.index("uses: actions/upload-artifact"))


if __name__ == "__main__":
    unittest.main()
