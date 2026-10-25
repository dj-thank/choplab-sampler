from pathlib import Path
import unittest

from scripts.mac_tool_policy import media_file_sets, matching_policy


class MacToolPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policies = media_file_sets(Path(__file__).resolve().parents[2])
        self.old, self.new, self.openssl4, self.sqlite354, self.oct24, self.oct25 = self.policies.values()

    def test_all_observed_native_dependency_graphs_are_accepted_exactly(self):
        self.assertEqual(40, len(self.old))
        self.assertEqual(40, len(self.new))
        self.assertEqual(40, len(self.openssl4))
        self.assertEqual(40, len(self.sqlite354))
        self.assertEqual(40, len(self.oct24))
        self.assertEqual(40, len(self.oct25))
        for name, files in self.policies.items():
            self.assertEqual(name, matching_policy(files, self.policies))

    def test_change_is_limited_to_the_two_reviewed_node_dependencies(self):
        self.assertEqual({'libhdr_histogram.6.3.3.dylib', 'libsimdjson.33.0.0.dylib'}, self.old - self.new)
        self.assertEqual({'libhdr_histogram.6.4.4.dylib', 'libsimdjson.34.0.0.dylib'}, self.new - self.old)

    def test_mixed_versions_are_rejected(self):
        mixed = self.old - {'libhdr_histogram.6.3.3.dylib'} | {'libhdr_histogram.6.4.4.dylib'}
        self.assertIsNone(matching_policy(mixed, self.policies))
        for old, new in [('libcrypto.3.dylib', 'libcrypto.4.dylib'), ('libssl.3.dylib', 'libssl.4.dylib')]:
            self.assertIsNone(matching_policy(self.new - {old} | {new}, self.policies))

    def test_openssl_update_only_replaces_the_reviewed_library_pair(self):
        self.assertEqual({'libcrypto.3.dylib', 'libssl.3.dylib'}, self.new - self.openssl4)
        self.assertEqual({'libcrypto.4.dylib', 'libssl.4.dylib'}, self.openssl4 - self.new)
        self.assertIsNone(matching_policy(self.openssl4 - {'libssl.4.dylib'} | {'libssl.5.dylib'}, self.policies))

    def test_sqlite_update_only_replaces_the_reviewed_library(self):
        self.assertEqual({'libsqlite3.3.53.4.dylib'}, self.openssl4 - self.sqlite354)
        self.assertEqual({'libsqlite3.3.54.0.dylib'}, self.sqlite354 - self.openssl4)
        self.assertIsNone(matching_policy(self.sqlite354 | {'libsqlite3.3.53.4.dylib'}, self.policies))
        self.assertIsNone(matching_policy(self.sqlite354 - {'libsqlite3.3.54.0.dylib'} |
                                         {'libsqlite3.3.55.0.dylib'}, self.policies))
        self.assertIsNone(matching_policy(self.new - {'libsqlite3.3.53.4.dylib'} |
                                         {'libsqlite3.3.54.0.dylib'}, self.policies))

    def test_missing_added_and_unreviewed_future_versions_are_rejected(self):
        for approved in self.policies.values():
            self.assertIsNone(matching_policy(approved - {'node'}, self.policies))
            self.assertIsNone(matching_policy(approved | {'unreviewed.dylib'}, self.policies))
        for files in [self.new - {'node'}, self.new | {'unreviewed.dylib'},
                      self.new - {'libsimdjson.34.0.0.dylib'} | {'libsimdjson.35.0.0.dylib'}]:
            with self.subTest(files=files):
                self.assertIsNone(matching_policy(files, self.policies))

    def test_oct25_requires_all_three_icu_libraries_and_rejects_mixed_abis(self):
        old = {f'libicu{part}.78.3.dylib' for part in ('data', 'i18n', 'uc')}
        new = {f'libicu{part}.79.1.dylib' for part in ('data', 'i18n', 'uc')}
        self.assertEqual(old, self.oct24 - self.oct25)
        self.assertEqual(new, self.oct25 - self.oct24)
        for part in ('data', 'i18n', 'uc'):
            self.assertIsNone(matching_policy(self.oct24 - {f'libicu{part}.78.3.dylib'} | {f'libicu{part}.79.1.dylib'}, self.policies))
            self.assertIsNone(matching_policy(self.oct25 - {f'libicu{part}.79.1.dylib'} | {f'libicu{part}.80.1.dylib'}, self.policies))

    def test_oct24_requires_the_complete_reviewed_six_file_update(self):
        old = {'libbrotlicommon.1.2.0.dylib', 'libbrotlidec.1.2.0.dylib',
               'libbrotlienc.1.2.0.dylib', 'libcares.2.19.7.dylib',
               'libhdr_histogram.6.4.4.dylib', 'libsqlite3.3.54.0.dylib'}
        new = {'libbrotlicommon.1.2.1.dylib', 'libbrotlidec.1.2.1.dylib',
               'libbrotlienc.1.2.1.dylib', 'libcares.2.19.8.dylib',
               'libhdr_histogram.6.5.4.dylib', 'libsqlite3.3.54.1.dylib'}
        self.assertEqual(old, self.sqlite354 - self.oct24)
        self.assertEqual(new, self.oct24 - self.sqlite354)
        for original, changed in zip(sorted(old), sorted(new)):
            self.assertIsNone(matching_policy(self.sqlite354 - {original} | {changed}, self.policies))
            self.assertIsNone(matching_policy(self.oct24 - {changed} | {original}, self.policies))


if __name__ == '__main__':
    unittest.main()
