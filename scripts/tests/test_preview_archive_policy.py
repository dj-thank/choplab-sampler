import hashlib
from io import BytesIO
import json
from pathlib import PurePosixPath
import unittest
import zipfile

from scripts.check_public_surface import is_packaged_runtime_binary_path, packaged_runtime_digest_findings, is_jdk_cacerts_path, is_mac_skiko_runtime, scan_zip


class PreviewArchivePolicyTest(unittest.TestCase):
    def test_mac_layout_does_not_expand_unrelated_truststore_or_skiko_paths(self):
        self.assertTrue(is_jdk_cacerts_path(PurePosixPath('ChopLab Preview.app/Contents/runtime/Contents/Home/lib/security/cacerts')))
        self.assertFalse(is_jdk_cacerts_path(PurePosixPath('Other.app/Contents/runtime/Contents') / 'Home/lib/security/cacerts'))
        jar = PurePosixPath('ChopLab Preview.app/Contents/app/skiko-awt-runtime-macos-arm64-0.144.6.jar')
        self.assertTrue(is_mac_skiko_runtime(jar, None))
        self.assertFalse(is_mac_skiko_runtime(jar, PurePosixPath('nested.zip')))
        self.assertFalse(is_mac_skiko_runtime(PurePosixPath('Other.app/Contents/app/' + jar.name), None))

    def test_mac_skiko_jar_still_scans_nested_content(self):
        nested = BytesIO()
        with zipfile.ZipFile(nested, 'w') as archive:
            archive.writestr('fixture.txt', 'ghp_' + 's' * 36)
        package = BytesIO()
        with zipfile.ZipFile(package, 'w') as archive:
            archive.writestr('ChopLab Preview.app/Contents/app/skiko-awt-runtime-macos-arm64-0.144.6.jar', nested.getvalue())
        self.assertTrue(any('secret-shaped' in item.lower() for item in scan_zip(BytesIO(package.getvalue()), label='fixture')))

    def test_mac_runtime_scope_is_exact_and_never_nested(self):
        root = 'ChopLab Preview.app/Contents/app/'
        for tail in ('tools/node', 'tools/libcrypto.3.dylib', 'models/htdemucs_ft_drums_fp16weights.onnx', 'onnxruntime-1.29.0.jar'):
            entry = PurePosixPath(root + tail)
            self.assertTrue(is_packaged_runtime_binary_path(entry, None))
            self.assertFalse(is_packaged_runtime_binary_path(entry, PurePosixPath('nested.zip')))
        for name in ('Other.app/Contents/app/tools/node', root + 'tools/unknown.dylib', root + 'tools/user.wav', root + 'models/other.onnx'):
            self.assertFalse(is_packaged_runtime_binary_path(PurePosixPath(name), None))

    def test_mac_binary_requires_matching_local_manifest_and_complete_bytes(self):
        content = b'synthetic-runtime-fixture'
        entry = PurePosixPath('ChopLab Preview.app/Contents/app/tools/node')
        manifest = {'files': {'node': {'sha256': hashlib.sha256(content).hexdigest()}}}
        with zipfile.ZipFile(BytesIO(), 'w') as archive:
            archive.writestr(str(entry.parent / 'manifest.json'), json.dumps(manifest))
            self.assertEqual([], packaged_runtime_digest_findings(archive, entry, content, len(content), 'fixture'))
            self.assertTrue(packaged_runtime_digest_findings(archive, entry, b'changed', 7, 'fixture'))
            self.assertTrue(packaged_runtime_digest_findings(archive, entry, content, len(content) + 1, 'fixture'))
        with zipfile.ZipFile(BytesIO(), 'w') as archive:
            archive.writestr('ChopLab.app/Contents/app/tools/manifest.json', json.dumps(manifest))
            self.assertTrue(packaged_runtime_digest_findings(archive, entry, content, len(content), 'fixture'))

    def test_mac_model_still_requires_repository_pin(self):
        content = b'synthetic-not-the-model'
        entry = PurePosixPath('ChopLab Preview.app/Contents/app/models/htdemucs_ft_drums_fp16weights.onnx')
        with zipfile.ZipFile(BytesIO(), 'w') as archive:
            archive.writestr(str(entry.parent / 'manifest.json'), json.dumps({'model': entry.name, 'sha256': hashlib.sha256(content).hexdigest()}))
            findings = packaged_runtime_digest_findings(archive, entry, content, len(content), 'fixture')
            self.assertTrue(any('pinned in the repository' in finding for finding in findings))

    def test_only_exact_product_roots_receive_runtime_policy(self):
        for name in ('ChopLab', 'ChopLab Preview'):
            self.assertTrue(is_packaged_runtime_binary_path(PurePosixPath(name + '/tools/node.exe'), None))
        self.assertFalse(is_packaged_runtime_binary_path(PurePosixPath('Other/tools/node.exe'), None))
        self.assertFalse(is_packaged_runtime_binary_path(PurePosixPath('ChopLab Preview/tools/node.exe'), PurePosixPath('nested.zip')))

    def test_preview_binary_is_bound_to_its_own_manifest(self):
        content = b'fixture-executable'
        entry = PurePosixPath('ChopLab Preview/tools/node.exe')
        with zipfile.ZipFile(BytesIO(), 'w') as archive:
            archive.writestr('ChopLab Preview/tools/runtime.json', json.dumps({'sha256': {'node.exe': hashlib.sha256(content).hexdigest()}}))
            self.assertEqual([], packaged_runtime_digest_findings(archive, entry, content, len(content), 'fixture'))
            self.assertTrue(packaged_runtime_digest_findings(archive, entry, b'tampered', 8, 'fixture'))

    def test_formal_manifest_cannot_substitute_for_preview_manifest(self):
        content = b'fixture-executable'
        with zipfile.ZipFile(BytesIO(), 'w') as archive:
            archive.writestr('ChopLab/tools/runtime.json', json.dumps({'sha256': {'node.exe': hashlib.sha256(content).hexdigest()}}))
            self.assertTrue(packaged_runtime_digest_findings(archive, PurePosixPath('ChopLab Preview/tools/node.exe'), content, len(content), 'fixture'))
