import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts.run_mac_next_acceptance import verify_package


class MacNextAcceptanceManifestTest(unittest.TestCase):
    def fixture(self, root, manifest_path):
        app = root / 'ChopLab NEXT.app'
        app.mkdir()
        (app / 'sample.jar').write_bytes(b'packaged bytes')
        manifest = {'profile': 'preview-next', 'files': {'sample.jar': {
            'bytes': 14, 'sha256': hashlib.sha256(b'packaged bytes').hexdigest()}}}
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest))
        return app, manifest

    def test_staging_default_and_explicit_installed_manifest_both_verify(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            default = root / 'manifest.json'
            app, expected = self.fixture(root, default)
            explicit = root / '.choplab-manifests/ChopLab NEXT.app.json'
            with patch('scripts.run_mac_next_acceptance.run') as signing:
                self.assertEqual(expected, verify_package(app))
                explicit.parent.mkdir(exist_ok=True)
                default.rename(explicit)
                self.assertEqual(expected, verify_package(app, explicit))
                self.assertEqual(2, signing.call_count)
                signing.assert_called_with('codesign', '--verify', '--deep', '--strict', app)

    def test_explicit_missing_manifest_never_falls_back_to_staging(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app, _ = self.fixture(root, root / 'manifest.json')
            with patch('scripts.run_mac_next_acceptance.run') as signing:
                with self.assertRaises(FileNotFoundError):
                    verify_package(app, root / 'missing.json')
                signing.assert_not_called()

    def test_changed_hash_extra_file_and_wrong_profile_are_rejected(self):
        for failure in ('hash', 'extra', 'profile'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                path = root / 'saved.json'
                app, manifest = self.fixture(root, path)
                if failure == 'hash':
                    (app / 'sample.jar').write_bytes(b'changed! bytes')
                elif failure == 'extra':
                    (app / 'extra').write_bytes(b'not packaged')
                else:
                    manifest['profile'] = 'preview'
                    path.write_text(json.dumps(manifest))
                with patch('scripts.run_mac_next_acceptance.run') as signing:
                    with self.assertRaises(RuntimeError):
                        verify_package(app, path)
                    signing.assert_not_called()


if __name__ == '__main__':
    unittest.main()
