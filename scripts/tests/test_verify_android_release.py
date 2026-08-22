from __future__ import annotations

import unittest

from scripts.verify_android_release import (
    VerificationError,
    normalize_fingerprint,
    parse_manifest,
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


class AndroidReleaseManifestPolicyTest(unittest.TestCase):
    def test_accepts_expected_release_surface(self) -> None:
        verify_manifest(
            parse_manifest(BASE_MANIFEST),
            expected_version="0.16.2",
            expected_version_code=26,
        )

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


if __name__ == "__main__":
    unittest.main()
