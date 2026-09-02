from __future__ import annotations

import base64
import hashlib
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from scripts.verify_android_release import (
    VerificationError,
    android_tool_executable_names,
    find_android_tool,
    normalize_fingerprint,
    parse_aapt2_manifest,
    parse_manifest,
    read_manifest,
    read_apksigner_certificate_sha256,
    verify_manifest,
)


BASE_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.choplab.sampler"
    android:versionCode="26"
    android:versionName="0.16.2">
    <permission
        android:name="com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        android:protectionLevel="signature" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" />
    <application android:debuggable="false">
        <activity android:name=".MainActivity" android:exported="true" />
        <service android:name=".audio.PlaybackCaptureService" android:exported="false" />
        <receiver
            android:name="androidx.profileinstaller.ProfileInstallReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />
    </application>
</manifest>
"""

AAPT2_MANIFEST = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=26
    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="0.16.2" (Raw: "0.16.2")
    A: package="com.choplab.sampler" (Raw: "com.choplab.sampler")
      E: permission (line=6)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        A: http://schemas.android.com/apk/res/android:protectionLevel(0x01010009)=0x00000002
      E: uses-permission (line=9)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.RECORD_AUDIO" (Raw: "android.permission.RECORD_AUDIO")
      E: uses-permission (line=10)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.FOREGROUND_SERVICE" (Raw: "android.permission.FOREGROUND_SERVICE")
      E: uses-permission (line=11)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" (Raw: "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
      E: uses-permission (line=12)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.POST_NOTIFICATIONS" (Raw: "android.permission.POST_NOTIFICATIONS")
      E: uses-permission (line=13)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      E: application (line=15)
        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=false
          E: activity (line=17)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.choplab.sampler.MainActivity" (Raw: "com.choplab.sampler.MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=20)
                  E: action (line=21)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: service (line=23)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.choplab.sampler.audio.PlaybackCaptureService" (Raw: "com.choplab.sampler.audio.PlaybackCaptureService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
          E: receiver (line=26)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
"""


class AndroidReleaseManifestPolicyTest(unittest.TestCase):
    def test_windows_tool_resolution_accepts_exe_build_tools(self) -> None:
        names = android_tool_executable_names("zipalign", platform="nt")
        self.assertIn("zipalign.exe", names)

    def test_sdk_build_tools_take_precedence_over_ambient_path(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            executable = "apksigner.exe" if os.name == "nt" else "apksigner"
            expected = Path(temp_dir) / "build-tools" / "36.0.0" / executable
            expected.parent.mkdir(parents=True)
            expected.touch()

            with (
                mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": temp_dir}),
                mock.patch(
                    "scripts.verify_android_release.shutil.which",
                    return_value="ambient/apksigner",
                ) as which,
            ):
                self.assertEqual(str(expected), find_android_tool("apksigner"))

            which.assert_not_called()

    def test_accepts_expected_release_surface(self) -> None:
        verify_manifest(
            parse_manifest(BASE_MANIFEST),
            expected_version="0.16.2",
            expected_version_code=26,
        )

    def test_accepts_expected_aapt2_release_surface(self) -> None:
        verify_manifest(
            parse_aapt2_manifest(AAPT2_MANIFEST),
            expected_version="0.16.2",
            expected_version_code=26,
        )

    def test_aapt2_parser_rejects_orphan_attribute(self) -> None:
        with self.assertRaisesRegex(VerificationError, "orphan attribute"):
            parse_aapt2_manifest(
                "N: android=http://schemas.android.com/apk/res/android (line=2)\n"
                "  A: package=\"com.choplab.sampler\"\n"
            )

    def test_aapt2_parser_rejects_malformed_relevant_line(self) -> None:
        with self.assertRaisesRegex(VerificationError, "Unsupported aapt2 manifest line"):
            parse_aapt2_manifest(AAPT2_MANIFEST + "      BROKEN: permission\n")

    def test_aapt2_parser_rejects_duplicate_attribute(self) -> None:
        duplicate = AAPT2_MANIFEST.replace(
            '    A: package="com.choplab.sampler" (Raw: "com.choplab.sampler")',
            '    A: package="com.choplab.sampler" (Raw: "com.choplab.sampler")\n'
            '    A: package="example.other" (Raw: "example.other")',
        )
        with self.assertRaisesRegex(VerificationError, "duplicate attribute"):
            parse_aapt2_manifest(duplicate)

    def test_aapt2_parser_rejects_multiple_roots(self) -> None:
        with self.assertRaisesRegex(VerificationError, "multiple roots"):
            parse_aapt2_manifest(AAPT2_MANIFEST + "  E: manifest (line=99)\n")

    def test_aapt2_surface_rejects_missing_package(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            '    A: package="com.choplab.sampler" (Raw: "com.choplab.sampler")\n',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "Unexpected application ID"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_version_mismatch(self) -> None:
        with self.assertRaisesRegex(VerificationError, "versionName mismatch"):
            verify_manifest(
                parse_aapt2_manifest(AAPT2_MANIFEST),
                expected_version="0.16.3",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_missing_application(self) -> None:
        manifest = AAPT2_MANIFEST[: AAPT2_MANIFEST.index("      E: application")]
        with self.assertRaisesRegex(VerificationError, "no application element"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_unexpected_permission(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            "      E: application (line=15)",
            "      E: uses-permission (line=14)\n"
            "        A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"android.permission.READ_PHONE_STATE\" (Raw: \"android.permission.READ_PHONE_STATE\")\n"
            "      E: application (line=15)",
        )
        with self.assertRaisesRegex(VerificationError, "outside the allowlist"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_debuggable_application(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            "android:debuggable(0x0101000f)=false",
            "android:debuggable(0x0101000f)=true",
        )
        with self.assertRaisesRegex(VerificationError, "debuggable"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_unexpected_permission_declaration(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            "      E: uses-permission (line=9)",
            "      E: permission (line=8)\n"
            "        A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"com.choplab.sampler.ADMIN\" (Raw: \"com.choplab.sampler.ADMIN\")\n"
            "      E: uses-permission (line=9)",
        )
        with self.assertRaisesRegex(VerificationError, "declares permissions"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_unprotected_profile_receiver(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            '            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")\n',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "must require android.permission.DUMP"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_aapt2_surface_rejects_unexpected_exported_component(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            "          E: receiver (line=26)",
            "          E: service (line=25)\n"
            "            A: http://schemas.android.com/apk/res/android:name(0x01010003)=\"com.choplab.sampler.PublicService\" (Raw: \"com.choplab.sampler.PublicService\")\n"
            "            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true\n"
            "          E: receiver (line=26)",
        )
        with self.assertRaisesRegex(VerificationError, "outside the allowlist"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_prefers_apkanalyzer_when_available(self) -> None:
        result = mock.Mock(stdout=BASE_MANIFEST)
        with (
            mock.patch(
                "scripts.verify_android_release.find_android_tool_optional",
                return_value="sdk/apkanalyzer",
            ),
            mock.patch("scripts.verify_android_release.find_android_tool") as required,
            mock.patch("scripts.verify_android_release.run", return_value=result) as command,
        ):
            root, backend = read_manifest(Path("candidate.apk"))

        self.assertEqual("apkanalyzer", backend)
        self.assertEqual("manifest", root.tag)
        command.assert_called_once_with(
            ["sdk/apkanalyzer", "manifest", "print", "candidate.apk"]
        )
        required.assert_not_called()

    def test_uses_aapt2_only_when_apkanalyzer_is_unavailable(self) -> None:
        result = mock.Mock(stdout=AAPT2_MANIFEST)
        with (
            mock.patch(
                "scripts.verify_android_release.find_android_tool_optional",
                return_value=None,
            ),
            mock.patch(
                "scripts.verify_android_release.find_android_tool",
                return_value="sdk/aapt2",
            ) as required,
            mock.patch("scripts.verify_android_release.run", return_value=result) as command,
        ):
            root, backend = read_manifest(Path("candidate.apk"))

        self.assertEqual("aapt2", backend)
        self.assertEqual("manifest", root.tag)
        required.assert_called_once_with("aapt2")
        command.assert_called_once_with(
            ["sdk/aapt2", "dump", "xmltree", "candidate.apk", "--file", "AndroidManifest.xml"]
        )

    def test_does_not_hide_apkanalyzer_command_failure_with_fallback(self) -> None:
        with (
            mock.patch(
                "scripts.verify_android_release.find_android_tool_optional",
                return_value="sdk/apkanalyzer",
            ),
            mock.patch("scripts.verify_android_release.find_android_tool") as required,
            mock.patch(
                "scripts.verify_android_release.run",
                side_effect=VerificationError("primary failed"),
            ),
        ):
            with self.assertRaisesRegex(VerificationError, "primary failed"):
                read_manifest(Path("candidate.apk"))

        required.assert_not_called()

    def test_fails_when_neither_manifest_tool_is_available(self) -> None:
        with (
            mock.patch(
                "scripts.verify_android_release.find_android_tool_optional",
                return_value=None,
            ),
            mock.patch(
                "scripts.verify_android_release.find_android_tool",
                side_effect=VerificationError("Cannot find Android SDK tool: aapt2"),
            ),
        ):
            with self.assertRaisesRegex(VerificationError, "Cannot find Android SDK tool: aapt2"):
                read_manifest(Path("candidate.apk"))

    def test_rejects_unprotected_profile_installer_receiver(self) -> None:
        manifest = BASE_MANIFEST.replace(
            '            android:permission="android.permission.DUMP" />',
            "            />",
        )
        with self.assertRaisesRegex(VerificationError, "must require android.permission.DUMP"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_rejects_debuggable_application(self) -> None:
        manifest = BASE_MANIFEST.replace('android:debuggable="false"', 'android:debuggable="true"')
        with self.assertRaisesRegex(VerificationError, "debuggable"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_explicit_debug_preview_accepts_only_known_debug_surface(self) -> None:
        manifest = BASE_MANIFEST.replace(
            'android:debuggable="false"',
            'android:debuggable="true"',
        ).replace(
            "</application>",
            '<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />\n'
            '<activity android:name="androidx.activity.ComponentActivity" android:exported="true" />\n'
            "    </application>",
        )

        verify_manifest(
            parse_manifest(manifest),
            expected_version="0.16.2",
            expected_version_code=26,
            allow_debug_preview=True,
        )

    def test_debug_preview_rejects_unknown_non_exported_tooling_component(self) -> None:
        manifest = BASE_MANIFEST.replace(
            'android:debuggable="false"',
            'android:debuggable="true"',
        ).replace(
            "</application>",
            '<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />\n'
            '<activity android:name="androidx.activity.ComponentActivity" android:exported="true" />\n'
            '<service android:name="androidx.compose.ui.tooling.UnexpectedService" android:exported="false" />\n'
            "    </application>",
        )

        with self.assertRaisesRegex(VerificationError, "debug/test tooling"):
            verify_manifest(
                parse_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
                allow_debug_preview=True,
            )

    def test_rejects_ambiguous_manifest_boolean(self) -> None:
        manifest = BASE_MANIFEST.replace(
            'android:debuggable="false"',
            'android:debuggable="@bool/release_debuggable"',
        )
        with self.assertRaisesRegex(VerificationError, "must be a literal boolean"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_aapt2_surface_rejects_ambiguous_exported_boolean(self) -> None:
        manifest = AAPT2_MANIFEST.replace(
            "android:exported(0x01010010)=true",
            "android:exported(0x01010010)=@0x7f050001",
            1,
        )
        with self.assertRaisesRegex(VerificationError, "must be a literal boolean"):
            verify_manifest(
                parse_aapt2_manifest(manifest),
                expected_version="0.16.2",
                expected_version_code=26,
            )

    def test_rejects_unexpected_permission(self) -> None:
        manifest = BASE_MANIFEST.replace(
            "<application",
            '<uses-permission android:name="android.permission.READ_PHONE_STATE" />\n    <application',
        )
        with self.assertRaisesRegex(VerificationError, "outside the allowlist"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_rejects_other_package_receiver_permission(self) -> None:
        manifest = BASE_MANIFEST.replace(
            "com.choplab.sampler.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "example.other.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        with self.assertRaisesRegex(VerificationError, "outside the allowlist"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_rejects_unexpected_permission_declaration(self) -> None:
        manifest = BASE_MANIFEST.replace(
            "<application",
            '<permission android:name="com.choplab.sampler.ADMIN" android:protectionLevel="signature" />\n    <application',
        )
        with self.assertRaisesRegex(VerificationError, "declares permissions"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_rejects_exported_tooling_component(self) -> None:
        manifest = BASE_MANIFEST.replace(
            "</application>",
            '<activity android:name="androidx.compose.ui.tooling.PreviewActivity" android:exported="true" />\n    </application>',
        )
        with self.assertRaisesRegex(VerificationError, "debug/test tooling"):
            verify_manifest(parse_manifest(manifest), expected_version="0.16.2", expected_version_code=26)

    def test_normalizes_certificate_fingerprint(self) -> None:
        value = ":".join(["ab"] * 32)
        self.assertEqual("ab" * 32, normalize_fingerprint(value))

    def test_reads_apksigner_certificate_from_stderr(self) -> None:
        fingerprint = ":".join(["ab"] * 32)
        self.assertEqual(
            "ab" * 32,
            read_apksigner_certificate_sha256(
                "",
                f"Signer #1 certificate SHA-256 digest: {fingerprint}\n",
            ),
        )

    def test_accepts_duplicate_apksigner_certificate_across_streams(self) -> None:
        fingerprint = "ab" * 32
        line = f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
        self.assertEqual(fingerprint, read_apksigner_certificate_sha256(line, line))

    def test_rejects_conflicting_apksigner_certificates(self) -> None:
        stdout = f"Signer #1 certificate SHA-256 digest: {'ab' * 32}\n"
        stderr = f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n"
        with self.assertRaisesRegex(VerificationError, "conflicting"):
            read_apksigner_certificate_sha256(stdout, stderr)

    def test_reads_apksigner_certificate_from_pem_when_label_is_absent(self) -> None:
        certificate = b"synthetic signer certificate DER"
        pem = (
            "-----BEGIN CERTIFICATE-----\n"
            f"{base64.b64encode(certificate).decode('ascii')}\n"
            "-----END CERTIFICATE-----\n"
        )
        self.assertEqual(hashlib.sha256(certificate).hexdigest(), read_apksigner_certificate_sha256(pem))

    def test_accepts_matching_apksigner_label_and_pem(self) -> None:
        certificate = b"matching signer certificate DER"
        fingerprint = hashlib.sha256(certificate).hexdigest()
        output = (
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
            "-----BEGIN CERTIFICATE-----\n"
            f"{base64.b64encode(certificate).decode('ascii')}\n"
            "-----END CERTIFICATE-----\n"
        )
        self.assertEqual(fingerprint, read_apksigner_certificate_sha256(output))

    def test_rejects_conflicting_apksigner_label_and_pem(self) -> None:
        certificate = b"different signer certificate DER"
        output = (
            f"Signer #1 certificate SHA-256 digest: {'ab' * 32}\n"
            "-----BEGIN CERTIFICATE-----\n"
            f"{base64.b64encode(certificate).decode('ascii')}\n"
            "-----END CERTIFICATE-----\n"
        )
        with self.assertRaisesRegex(VerificationError, "conflicting"):
            read_apksigner_certificate_sha256(output)

    def test_rejects_malformed_apksigner_pem(self) -> None:
        output = "-----BEGIN CERTIFICATE-----\nnot-base64!\n-----END CERTIFICATE-----\n"
        with self.assertRaisesRegex(VerificationError, "malformed PEM"):
            read_apksigner_certificate_sha256(output)

    def test_rejects_multiple_identical_apksigner_pem_certificates(self) -> None:
        certificate = b"repeated signer certificate DER"
        pem = (
            "-----BEGIN CERTIFICATE-----\n"
            f"{base64.b64encode(certificate).decode('ascii')}\n"
            "-----END CERTIFICATE-----\n"
        )
        with self.assertRaisesRegex(VerificationError, "multiple PEM"):
            read_apksigner_certificate_sha256(pem + pem)


if __name__ == "__main__":
    unittest.main()
