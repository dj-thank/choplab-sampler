import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts.check_public_surface import is_packaged_runtime_binary_path, packaged_runtime_digest_findings


class WindowsQuickJsAdmissionTest(unittest.TestCase):
    def test_exact_upstream_bytes_receipt_and_license_are_required(self):
        binary, license_bytes = b'MZ fixture binary', b'fixture upstream MIT notice'
        digest = lambda data: hashlib.sha256(data).hexdigest()
        pin = {'version': 'fixture', 'windows': {'bytes': len(binary), 'sha256': digest(binary)},
               'license': {'filename': 'QuickJS-LICENSE.txt', 'bytes': len(license_bytes), 'sha256': digest(license_bytes)}}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root/'config').mkdir()
            (root/'config/windows-quickjs.json').write_text(json.dumps(pin))
            entry = PurePosixPath('ChopLab Preview/tools/qjs.exe')
            self.assertTrue(is_packaged_runtime_binary_path(entry, None))
            self.assertFalse(is_packaged_runtime_binary_path(PurePosixPath('Other/tools/qjs.exe'), None))
            self.assertFalse(is_packaged_runtime_binary_path(entry, PurePosixPath('nested.zip')))

            def inspect(content=binary, notice=license_bytes, receipt=pin):
                stream = io.BytesIO()
                with zipfile.ZipFile(stream, 'w') as archive:
                    archive.writestr('ChopLab Preview/tools/runtime.json', json.dumps({'sha256': {'qjs.exe': digest(content)}, 'quickjsSource': receipt}))
                    if notice is not None:
                        archive.writestr('ChopLab Preview/tools/QuickJS-LICENSE.txt', notice)
                stream.seek(0)
                with zipfile.ZipFile(stream) as archive, patch('scripts.check_public_surface.REPOSITORY_ROOT', root):
                    return packaged_runtime_digest_findings(archive, entry, content, len(content), 'fixture')

            self.assertEqual([], inspect())
            self.assertTrue(inspect(content=b'MZ changed bytes'))
            self.assertTrue(inspect(notice=None))
            self.assertTrue(inspect(notice=b'changed license'))
            self.assertTrue(inspect(receipt={}))


if __name__ == '__main__':
    unittest.main()
