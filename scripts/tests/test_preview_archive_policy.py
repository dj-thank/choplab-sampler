import hashlib
from io import BytesIO
import json
from pathlib import PurePosixPath
import unittest
import zipfile

from scripts.check_public_surface import is_packaged_runtime_binary_path, packaged_runtime_digest_findings


class PreviewArchivePolicyTest(unittest.TestCase):
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
