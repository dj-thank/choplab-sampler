from pathlib import Path
import unittest

from scripts.mac_tool_policy import media_file_sets, matching_policy


class MacToolPolicyTest(unittest.TestCase):
    def setUp(self):
        self.policies = media_file_sets(Path(__file__).resolve().parents[2])
        self.old, self.new = self.policies.values()

    def test_both_observed_native_dependency_graphs_are_accepted_exactly(self):
        self.assertEqual(40, len(self.old))
        self.assertEqual(40, len(self.new))
        for name, files in self.policies.items():
            self.assertEqual(name, matching_policy(files, self.policies))

    def test_change_is_limited_to_the_two_reviewed_node_dependencies(self):
        self.assertEqual({'libhdr_histogram.6.3.3.dylib', 'libsimdjson.33.0.0.dylib'}, self.old - self.new)
        self.assertEqual({'libhdr_histogram.6.4.4.dylib', 'libsimdjson.34.0.0.dylib'}, self.new - self.old)

    def test_mixed_versions_are_rejected(self):
        mixed = self.old - {'libhdr_histogram.6.3.3.dylib'} | {'libhdr_histogram.6.4.4.dylib'}
        self.assertIsNone(matching_policy(mixed, self.policies))

    def test_missing_added_and_unreviewed_future_versions_are_rejected(self):
        for files in [self.new - {'node'}, self.new | {'unreviewed.dylib'},
                      self.new - {'libsimdjson.34.0.0.dylib'} | {'libsimdjson.35.0.0.dylib'}]:
            with self.subTest(files=files):
                self.assertIsNone(matching_policy(files, self.policies))


if __name__ == '__main__':
    unittest.main()
