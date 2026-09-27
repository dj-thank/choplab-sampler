import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from scripts.package_mac_app import minimum_system_version, native_minimum_versions


class MacNativeMinimumOsTest(unittest.TestCase):
    def native(self, root, name, version):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(bytes.fromhex('cffaedfe') + version.encode())
        return path

    def otool(self, command, **kwargs):
        version = Path(command[-1]).read_bytes()[4:].decode()
        return f'Load command 0\n      cmd LC_BUILD_VERSION\n platform 1\n    minos {version}\n      sdk 27.0\nLoad command 1\n'

    def test_actual_newer_tool_requirement_wins_over_helper_and_runtime(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.native(root, 'Contents/app/helper', '14.0')
            self.native(root, 'Contents/runtime/java', '11.0')
            self.native(root, 'Contents/app/tools/ffmpeg', '27.0')
            with patch('scripts.package_mac_app.subprocess.check_output', side_effect=self.otool):
                self.assertEqual('27.0', minimum_system_version(root))

    def test_native_inside_jar_also_sets_the_os_floor(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.native(root, 'helper', '14.0')
            with zipfile.ZipFile(root / 'skiko.jar', 'w') as archive:
                archive.writestr('native/macos-arm64/library.dylib', bytes.fromhex('cffaedfe') + b'15.2')
                archive.writestr('other/resource.txt', b'not native')
            with patch('scripts.package_mac_app.subprocess.check_output', side_effect=self.otool):
                self.assertEqual('15.2', minimum_system_version(root))

    def test_legacy_and_multiple_architecture_commands_are_read_numerically(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = self.native(Path(temporary), 'fat', 'unused')
            output = ('Load command 0\n cmd LC_VERSION_MIN_MACOSX\n version 10.13\n'
                      'Load command 1\n cmd LC_BUILD_VERSION\n platform 1\n minos 14.0\n sdk 27.0\n'
                      'Load command 2\n')
            with patch('scripts.package_mac_app.subprocess.check_output', return_value=output):
                self.assertEqual(['10.13', '14.0'], native_minimum_versions(path))

    def test_native_without_valid_macos_requirement_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = self.native(Path(temporary), 'broken', 'unused')
            with patch('scripts.package_mac_app.subprocess.check_output', return_value='invalid'):
                with self.assertRaisesRegex(RuntimeError, 'no readable macOS deployment target'):
                    native_minimum_versions(path)


if __name__ == '__main__':
    unittest.main()
