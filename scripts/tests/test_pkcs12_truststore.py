from contextlib import redirect_stderr
from io import BytesIO, StringIO
from pathlib import PurePosixPath
import unittest
from unittest.mock import patch
import zipfile

from scripts import check_public_surface as policy


def der(tag, body):
    size = len(body)
    width = max(1, (size.bit_length() + 7) // 8)
    encoded = bytes([size]) if size < 128 else bytes([0x80 + width]) + size.to_bytes(width, 'big')
    return bytes([tag]) + encoded + body


def fixture(bag_kind=3, encrypted=False, extra_attribute=b'', certificate=None):
    certificate = certificate or der(0x30, der(0x30, bytes.fromhex('06092a864886f70d010101')) + der(0x03, b'\x00fixture'))
    attributes = der(0x30, bytes.fromhex('06092a864886f70d010914') + der(0x31, der(0x1e, 'fixture'.encode('utf-16-be'))))
    attributes += der(0x30, bytes.fromhex('060c6086480186f966adca7b0101') + der(0x31, bytes.fromhex('0604551d2500')))
    bag = der(0x30, bytes.fromhex('060b2a864886f70d010c0a01') + bytes([bag_kind]) + der(0xa0, der(0x30, bytes.fromhex('060a2a864886f70d01091601') + der(0xa0, der(0x04, certificate)))) + der(0x31, attributes + extra_attribute))
    data_oid = bytes.fromhex('06092a864886f70d010701')
    safe_oid = bytes.fromhex('06092a864886f70d010706') if encrypted else data_oid
    safe = der(0x30, safe_oid + der(0xa0, der(0x04, der(0x30, bag))))
    auth = der(0x30, data_oid + der(0xa0, der(0x04, der(0x30, safe))))
    return der(0x30, b'\x02\x01\x03' + auth)


class Pkcs12TruststoreTest(unittest.TestCase):
    path = PurePosixPath('ChopLab Preview/runtime/lib/security/cacerts')

    def test_structure_and_java_verification_are_both_required(self):
        content = fixture()
        self.assertEqual(1, policy.pkcs12_trusted_certificate_count(content))
        with patch.object(policy, 'verify_pkcs12_trust_with_java', return_value=True) as java:
            self.assertTrue(policy.is_trusted_jdk_cacerts(self.path, content))
            java.assert_called_once_with(content, 1)
        with patch.object(policy, 'verify_pkcs12_trust_with_java', return_value=False):
            self.assertFalse(policy.is_trusted_jdk_cacerts(self.path, content))

    def test_filename_does_not_allow_private_shrouded_secret_or_unknown_bags(self):
        for kind in (1, 2, 4, 5, 6, 99):
            with self.subTest(kind=kind), patch.object(policy, 'verify_pkcs12_trust_with_java') as java:
                content = fixture(bag_kind=kind)
                self.assertFalse(policy.is_trusted_jdk_cacerts(self.path, content))
                java.assert_not_called()
                buffer = BytesIO()
                with zipfile.ZipFile(buffer, 'w') as archive:
                    archive.writestr(str(self.path), content)
                self.assertTrue(policy.scan_zip(BytesIO(buffer.getvalue()), label='renamed-key.zip'))

    def test_malformed_encrypted_trailing_and_unknown_attributes_fail_closed(self):
        unknown = der(0x30, bytes.fromhex('06092a864886f70d010915') + der(0x31, der(0x04, b'concealed')))
        for content in (b'not a store', fixture()[:-1], fixture()+b'extra', fixture(encrypted=True), fixture(extra_attribute=unknown)):
            with self.subTest(size=len(content)), patch.object(policy, 'verify_pkcs12_trust_with_java') as java:
                self.assertFalse(policy.is_trusted_jdk_cacerts(self.path, content))
                java.assert_not_called()

    def test_der_private_key_inside_certificate_field_is_rejected(self):
        key = der(0x30, b'\x02\x01\x00' + der(0x30, bytes.fromhex('06092a864886f70d010101')) + der(0x04, b'synthetic-private-key'))
        with patch.object(policy, 'verify_pkcs12_trust_with_java') as java:
            self.assertFalse(policy.is_trusted_jdk_cacerts(self.path, fixture(certificate=key)))
            java.assert_not_called()

    def test_certificate_shape_outside_runtime_truststore_is_not_exempt(self):
        with patch.object(policy, 'verify_pkcs12_trust_with_java') as java:
            self.assertFalse(policy.is_trusted_jdk_cacerts(PurePosixPath('cacerts'), fixture()))
            java.assert_not_called()

    def setUp(self):
        policy._JAVA_TRUSTED_STORES.clear()

    @staticmethod
    def answer(code=0, stdout=b'TRUSTED:1', stderr=b''):
        return policy.subprocess.CompletedProcess(['java'], code, stdout, stderr)

    def test_java_unavailable_or_timeout_is_not_success(self):
        with patch('shutil.which', return_value=None), redirect_stderr(StringIO()):
            self.assertFalse(policy.verify_pkcs12_trust_with_java(fixture(), 1))
        report = StringIO()
        with patch('shutil.which', return_value='java'), redirect_stderr(report), \
                patch.object(policy.subprocess, 'run', side_effect=policy.subprocess.TimeoutExpired('java', 60)) as run:
            self.assertFalse(policy.verify_pkcs12_trust_with_java(fixture(), 1))
        self.assertEqual(policy.JAVA_TRUSTSTORE_ATTEMPTS, run.call_count)
        self.assertIn('timed out after 60 s (attempt 2 of 2)', report.getvalue())

    def test_a_slow_or_failed_check_is_tried_once_more_and_reported(self):
        for first in (policy.subprocess.TimeoutExpired('java', 60), self.answer(1, b'', b'Picked up options\nError: VM could not start')):
            policy._JAVA_TRUSTED_STORES.clear()
            report = StringIO()
            with self.subTest(first=type(first).__name__), patch('shutil.which', return_value='java'), redirect_stderr(report), \
                    patch.object(policy.subprocess, 'run', side_effect=[first, self.answer()]) as run:
                self.assertTrue(policy.verify_pkcs12_trust_with_java(fixture(), 1))
                self.assertEqual(2, run.call_count)
                self.assertIn('(attempt 1 of 2)', report.getvalue())
        self.assertIn('exited 1 without TRUSTED:1 (attempt 1 of 2): Error: VM could not start', report.getvalue())

    def test_an_answer_other_than_trusted_fails_after_both_attempts(self):
        for answer in (self.answer(stdout=b'TRUSTED:2'), self.answer(1, b'TRUSTED:1'), self.answer(stdout=b'TRUSTED:1\n')):
            with self.subTest(answer=answer), patch('shutil.which', return_value='java'), redirect_stderr(StringIO()), \
                    patch.object(policy.subprocess, 'run', return_value=answer) as run:
                self.assertFalse(policy.verify_pkcs12_trust_with_java(fixture(), 1))
                self.assertEqual(policy.JAVA_TRUSTSTORE_ATTEMPTS, run.call_count)

    def test_an_accepted_store_is_not_checked_again(self):
        with patch('shutil.which', return_value='java'), redirect_stderr(StringIO()), \
                patch.object(policy.subprocess, 'run', return_value=self.answer()) as run:
            self.assertTrue(policy.verify_pkcs12_trust_with_java(fixture(), 1))
            self.assertTrue(policy.verify_pkcs12_trust_with_java(fixture(), 1))
            self.assertEqual(1, run.call_count)
            # Other content, or another expected count, is checked on its own.
            self.assertFalse(policy.verify_pkcs12_trust_with_java(fixture(), 2))
            self.assertEqual(1 + policy.JAVA_TRUSTSTORE_ATTEMPTS, run.call_count)
