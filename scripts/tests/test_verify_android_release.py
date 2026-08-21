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
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application android:debuggable="false">
        <activity android:name=".MainActivity" android:exported="true" />
        <service android:name=".audio.PlaybackCaptureService" android:exported="false" />
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
