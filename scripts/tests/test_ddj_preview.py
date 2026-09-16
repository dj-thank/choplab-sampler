import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stage_ddj_preview import ANDROID, PACKAGE, PERMISSIONS, VerificationError, verify_preview_manifest


def manifest():
    root = ET.Element('manifest', {'package': PACKAGE, ANDROID+'versionName': '0.18.0-ddj200-preview', ANDROID+'versionCode': '30'})
    ET.SubElement(root, 'uses-sdk', {ANDROID+'minSdkVersion': '29', ANDROID+'targetSdkVersion': '36'})
    for name in sorted(PERMISSIONS):
        attrs = {ANDROID+'name': name}
        if name.rsplit('.', 1)[-1] in {'BLUETOOTH', 'BLUETOOTH_ADMIN', 'ACCESS_FINE_LOCATION'}:
            attrs[ANDROID+'maxSdkVersion'] = '30'
        if name.endswith('.BLUETOOTH_SCAN'):
            attrs[ANDROID+'usesPermissionFlags'] = 'neverForLocation'
        ET.SubElement(root, 'uses-permission', attrs)
    app = ET.SubElement(root, 'application', {ANDROID+'debuggable': 'true', ANDROID+'allowBackup': 'false', ANDROID+'usesCleartextTraffic': 'false'})
    ET.SubElement(app, 'activity', {ANDROID+'name': 'com.choplab.sampler.MainActivity', ANDROID+'exported': 'true'})
    return root


class DdjPreviewTest(unittest.TestCase):
    def check(self, root):
        verify_preview_manifest(root, '0.18.0', 30)

    def test_isolated_package_and_known_permissions_pass(self):
        self.check(manifest())

    def test_production_package_cannot_be_published_as_preview(self):
        r = manifest(); r.set('package', 'com.choplab.sampler')
        with self.assertRaises(VerificationError): self.check(r)

    def test_version_name_and_code_must_match(self):
        for key, value in [('versionName', '0.18.0'), ('versionCode', '29')]:
            r = manifest(); r.set(ANDROID+key, value)
            with self.assertRaises(VerificationError): self.check(r)

    def test_sdk_contract_is_preserved(self):
        r = manifest(); r.find('uses-sdk').set(ANDROID+'minSdkVersion', '28')
        with self.assertRaises(VerificationError): self.check(r)

    def test_no_background_location_or_install_permission(self):
        for name in ['ACCESS_BACKGROUND_LOCATION', 'REQUEST_INSTALL_PACKAGES']:
            r = manifest(); ET.SubElement(r, 'uses-permission', {ANDROID+'name': 'android.permission.'+name})
            with self.assertRaises(VerificationError): self.check(r)

    def test_bluetooth_permissions_are_required(self):
        r = manifest()
        r.remove(next(e for e in r.findall('uses-permission') if e.get(ANDROID+'name').endswith('BLUETOOTH_CONNECT')))
        with self.assertRaises(VerificationError): self.check(r)

    def test_legacy_location_is_scoped(self):
        r = manifest()
        next(e for e in r.findall('uses-permission') if e.get(ANDROID+'name').endswith('ACCESS_FINE_LOCATION')).attrib.pop(ANDROID+'maxSdkVersion')
        with self.assertRaises(VerificationError): self.check(r)

    def test_scan_is_never_for_location(self):
        r = manifest()
        next(e for e in r.findall('uses-permission') if e.get(ANDROID+'name').endswith('BLUETOOTH_SCAN')).attrib.pop(ANDROID+'usesPermissionFlags')
        with self.assertRaises(VerificationError): self.check(r)

    def test_backup_cleartext_and_debug_contract(self):
        for key, value in [('allowBackup', 'true'), ('usesCleartextTraffic', 'true'), ('debuggable', 'false')]:
            r = manifest(); r.find('application').set(ANDROID+key, value)
            with self.assertRaises(VerificationError): self.check(r)

    def test_unknown_exported_service_rejected(self):
        r = manifest()
        ET.SubElement(r.find('application'), 'service', {ANDROID+'name': 'com.example.PublicService', ANDROID+'exported': 'true'})
        with self.assertRaises(VerificationError): self.check(r)

    def test_profiler_requires_signature_permission(self):
        r = manifest()
        receiver = ET.SubElement(r.find('application'), 'receiver', {ANDROID+'name': 'androidx.profileinstaller.ProfileInstallReceiver', ANDROID+'exported': 'true'})
        with self.assertRaises(VerificationError): self.check(r)
        receiver.set(ANDROID+'permission', 'android.permission.DUMP'); self.check(r)

    def test_no_publication_from_pull_requests_or_main(self):
        text = (Path(__file__).resolve().parents[2] / '.github/workflows/ddj200-apk.yml').read_text()
        self.assertNotIn('pull_request', text)
        self.assertIn("github.ref == 'refs/heads/feat/ddj200-controller-20260916'", text)
        self.assertIn('needs: build', text)
        self.assertIn('--draft --prerelease --latest=false', text)
        self.assertNotIn('secrets.', text)
        self.assertNotIn('--clobber', text)
        build, publish = text.split('  publish:', 1)
        self.assertNotIn('contents: write', build)
        self.assertIn('contents: write', publish)
        self.assertIn(':app:testDdjPreviewUnitTest', build)
        self.assertIn(':app:lintDdjPreview', build)


if __name__ == '__main__':
    unittest.main()
