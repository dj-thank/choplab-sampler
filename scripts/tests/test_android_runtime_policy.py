from dataclasses import replace
import hashlib
from io import BytesIO
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from scripts import check_public_surface as policy
from scripts.android_runtime_policy import RuntimePin, load_pins, valid_resource_table


class AndroidRuntimeAdmissionTest(unittest.TestCase):
    native = 'lib/arm64-v8a/libpython.so'
    content = b'\x7fELF' + bytes(range(128))

    def setUp(self):
        self.pin = RuntimePin('io.github.junkfood02.youtubedl-android:library:0.18.1',
                              'jni/arm64-v8a/libpython.so', self.native, 'native_elf',
                              len(self.content), hashlib.sha256(self.content).hexdigest())

    def scan(self, members, *, pin=None, suffix='.apk', **kwargs):
        with tempfile.TemporaryDirectory() as temporary:
            archive_path = Path(temporary) / ('fixture' + suffix)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_STORED) as archive:
                    for name, content in members:
                        archive.writestr(name, content)
            with patch.object(policy, 'load_pins', return_value=(pin or self.pin,)):
                return policy.scan_zip(archive_path, **kwargs)

    def test_exact_pin_has_typed_admission_without_raising_generic_limits(self):
        with patch.object(policy, 'APK_BINARY_MEMBER_SCAN_LIMIT', 32), patch.object(policy, 'APK_BINARY_TOTAL_SCAN_LIMIT', 64):
            self.assertEqual([], self.scan([(self.native, self.content)]))
            findings = self.scan([('lib/arm64-v8a/libunknown.so', self.content)])
            self.assertTrue(any('member scan limit' in f for f in findings), findings)

    def test_wrong_hash_size_or_private_payload_never_inherits_filename_admission(self):
        private = ('-----BEGIN ' + 'PRIVATE KEY-----').encode()
        for content in (self.content[:-1], self.content + b'extra', b'X' + self.content[1:], private.ljust(len(self.content), b'x')):
            with self.subTest(size=len(content)):
                findings = self.scan([(self.native, content)])
                self.assertTrue(any('runtime' in f for f in findings), findings)

    def test_path_scope_abi_traversal_and_noncanonical_paths_are_rejected(self):
        for name in ('assets/libpython.so', 'lib/x86/libpython.so', '../' + self.native,
                     'lib//arm64-v8a/libpython.so', 'other/' + self.native):
            with self.subTest(name=name):
                self.assertTrue(self.scan([(name, self.content)]))

    def test_duplicate_pin_and_symbolic_link_are_rejected(self):
        findings = self.scan([(self.native, self.content), (self.native, self.content)])
        self.assertTrue(any('duplicate pinned' in f for f in findings), findings)
        link = zipfile.ZipInfo(self.native)
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.assertTrue(self.scan([(link, self.content)]))

    def test_apk_supplied_manifest_cannot_vouch_for_tampered_member(self):
        changed = b'X' + self.content[1:]
        manifest = json.dumps({'sha256': {self.native: hashlib.sha256(changed).hexdigest()}})
        self.assertTrue(self.scan([(self.native, changed), ('runtime.json', manifest)]))

    def test_type_is_checked_even_after_hash_match(self):
        wrong = replace(self.pin, kind='native_zip')
        self.assertTrue(self.scan([(self.native, self.content)], pin=wrong))

    def test_pins_do_not_exempt_a_generic_or_nested_zip(self):
        with patch.object(policy, 'ZIP_BINARY_SECRET_MEMBER_LIMIT', 32):
            self.assertTrue(self.scan([(self.native, self.content)], suffix='.zip'))
        nested = BytesIO()
        with zipfile.ZipFile(nested, 'w') as archive:
            archive.writestr(self.native, self.content)
        with patch.object(policy, 'APK_BINARY_MEMBER_SCAN_LIMIT', 32):
            self.assertTrue(self.scan([('nested.apk', nested.getvalue())]))

    def test_pinned_content_still_consumes_root_archive_work_budget(self):
        budget = policy.ZipCandidateScanBudget(expanded_output_limit=len(self.content) - 1)
        findings = self.scan([(self.native, self.content)], _candidate_budget=budget)
        self.assertTrue(any('aggregate scan limit' in f for f in findings), findings)

    def test_obfuscated_raw_script_is_hash_bound_and_single_copy(self):
        content = b'#!/usr/bin/env python3\n' + b'print("fixture")\n' * 10
        pin = RuntimePin(self.pin.coordinate, 'res/raw/ytdlp', None, 'raw_ytdlp', len(content), hashlib.sha256(content).hexdigest())
        self.assertEqual([], self.scan([('res/Nc', content)], pin=pin))
        self.assertTrue(self.scan([('res/Nc', content), ('res/Qa', content)], pin=pin))
        self.assertTrue(self.scan([('res/Nc', content[:-1] + b'x')], pin=pin))
        self.assertTrue(self.scan([('assets/ytdlp', content)], pin=pin))
        self.assertTrue(self.scan([('res/unknown', b'#!/bin/sh\necho unknown')], pin=pin))

    def test_real_pin_file_is_finite_and_sources_are_external_to_apk(self):
        pins = load_pins()
        self.assertEqual(33, len(pins))
        self.assertEqual(33, len({pin.identity for pin in pins}))
        self.assertEqual(335520529, sum(pin.size for pin in pins))


class AndroidResourceTableTest(unittest.TestCase):
    @staticmethod
    def table(payload=b''):
        child = struct.pack('<HHI', 0x200, 8, len(payload) + 8) + payload
        return struct.pack('<HHII', 2, 12, len(child) + 12, 1) + child

    def test_arsc_uses_existing_binary_budget_and_is_still_secret_scanned(self):
        test = AndroidRuntimeAdmissionTest()
        test.setUp()
        content = self.table(b'public fixture')
        with patch.object(policy, 'ZIP_MEMBER_SCAN_LIMIT', 8):
            self.assertEqual([], test.scan([('resources.arsc', content)], member_scan_limit=8))
        token = ('github_pat_' + 'a' * 24).encode()
        findings = test.scan([('resources.arsc', self.table(token))])
        self.assertTrue(any('secret' in f for f in findings), findings)
        self.assertNotIn(token.decode(), '\n'.join(findings))
        with patch.object(policy, 'APK_BINARY_TOTAL_SCAN_LIMIT', 16):
            self.assertTrue(test.scan([('resources.arsc', content)]))

    def test_malformed_unknown_chunks_and_trailing_data_are_rejected(self):
        good = self.table()
        self.assertTrue(valid_resource_table(good))
        for content in (good[:-1], good+b'junk', b'not arsc', good[:12]+b'\xff\xff'+good[14:]):
            self.assertFalse(valid_resource_table(content))
