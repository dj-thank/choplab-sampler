import base64
from pathlib import Path
import tempfile
import unittest

from scripts.configure_android_signing import configure


class SigningConfigurationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.env = {'RUNNER_TEMP': str(self.root), 'GITHUB_ENV': str(self.root / 'env')}
        for suffix, value in {
            'KEYSTORE_BASE64': base64.b64encode(b'fake-keystore').decode(),
            'STORE_PASSWORD': 'fixture-store', 'KEY_ALIAS': 'fixture-alias',
            'KEY_PASSWORD': 'fixture-key', 'CERT_SHA256': 'a' * 64,
        }.items():
            self.env['CHOPLAB_PREVIEW_' + suffix] = value

    def test_writes_only_expected_key_and_environment(self):
        path = configure('preview', self.env)
        self.assertEqual(b'fake-keystore', path.read_bytes())
        env = (self.root / 'env').read_text(encoding='utf-8')
        self.assertIn('CHOPLAB_PREVIEW_KEYSTORE=', env)
        self.assertNotIn('BASE64', env)

    def test_missing_secret_never_generates_fallback_key(self):
        for field in ('KEYSTORE_BASE64', 'STORE_PASSWORD', 'KEY_ALIAS', 'KEY_PASSWORD', 'CERT_SHA256'):
            with self.subTest(field=field):
                environment = dict(self.env)
                del environment['CHOPLAB_PREVIEW_' + field]
                with self.assertRaisesRegex(ValueError, 'required signing secret'):
                    configure('preview', environment)
                self.assertFalse((self.root / 'choplab-preview.jks').exists())

    def test_invalid_base64_and_fingerprint_fail_before_writing(self):
        for field, value in (('KEYSTORE_BASE64', '%%%'), ('CERT_SHA256', 'not-a-hash')):
            environment = dict(self.env)
            environment['CHOPLAB_PREVIEW_' + field] = value
            with self.assertRaises(ValueError):
                configure('preview', environment)
            self.assertFalse((self.root / 'choplab-preview.jks').exists())

    def test_environment_line_injection_is_rejected(self):
        self.env['CHOPLAB_PREVIEW_KEY_ALIAS'] = 'alias\nEXTRA=value'
        with self.assertRaises(ValueError):
            configure('preview', self.env)

    def test_existing_file_is_never_overwritten(self):
        target = self.root / 'choplab-preview.jks'
        target.write_bytes(b'preserve')
        with self.assertRaises(FileExistsError):
            configure('preview', self.env)
        self.assertEqual(b'preserve', target.read_bytes())
