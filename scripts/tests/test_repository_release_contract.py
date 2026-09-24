import unittest
from scripts.write_release_manifest import EXPECTED_BINARY_PATTERNS


class RepositoryReleaseContractTest(unittest.TestCase):
    def test_only_android_and_windows_are_public_platforms(self):
        self.assertEqual({"android", "windows"}, set(EXPECTED_BINARY_PATTERNS))
        self.assertIn("android-release", EXPECTED_BINARY_PATTERNS["android"].pattern)


if __name__ == "__main__":
    unittest.main()
