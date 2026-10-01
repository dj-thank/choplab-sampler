import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from scripts import android_runtime_policy as policy
from scripts import prepare_android_python_runtime as build


class AndroidPythonLinkageTest(unittest.TestCase):
    def fixture(self, *, alias_target=build.TARGET, alias_mode=stat.S_IFLNK, include_runtime=True):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content, mode in (
                (build.ALIAS, alias_target.encode(), alias_mode | 0o777),
                ('usr/lib/liblzma.so.5', build.TARGET.encode(), stat.S_IFLNK | 0o777),
                ('usr/lib/' + build.TARGET, b'\x7fELF unchanged codec bytes', stat.S_IFREG | 0o755),
                ('usr/lib/python3.12/module.py', b'unchanged Python', stat.S_IFREG | 0o644),
                ('usr/etc/tls/cert.pem', b'unchanged trust roots', stat.S_IFREG | 0o644),
            ):
                if not include_runtime and name.endswith(build.TARGET):
                    continue
                info = zipfile.ZipInfo(name)
                info.create_system = 3
                info.external_attr = mode << 16
                archive.writestr(info, content)
        return data.getvalue()

    def test_only_alias_is_removed_with_versioned_codec_python_tls_and_symlink_semantics_preserved(self):
        original = self.fixture()
        derived, receipt = build.remove_link_alias(original)
        self.assertNotEqual(len(original), len(derived))
        self.assertEqual((len(original), len(derived)), (receipt['cacheVersionBefore'], receipt['cacheVersionAfter']))
        with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(io.BytesIO(derived)) as result:
            self.assertEqual(set(source.namelist()) - {build.ALIAS}, set(result.namelist()))
            for name in result.namelist():
                self.assertEqual(source.read(name), result.read(name))
                self.assertEqual(source.getinfo(name).external_attr, result.getinfo(name).external_attr)
            self.assertTrue(stat.S_ISLNK(result.getinfo('usr/lib/liblzma.so.5').external_attr >> 16))
        self.assertEqual(derived, build.remove_link_alias(original)[0])

    def test_different_alias_or_missing_versioned_runtime_cannot_be_admitted(self):
        for options in ({'alias_target': 'other.so'}, {'alias_mode': stat.S_IFREG}, {'include_runtime': False}):
            with self.subTest(options=options), self.assertRaises((ValueError, KeyError)):
                build.remove_link_alias(self.fixture(**options))

    def test_any_elf_requiring_the_removed_alias_fails_the_dependency_audit(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(build.subprocess, 'check_output') as elf:
            elf.return_value = 'Shared library: [liblzma.so.5]'
            result = build.dependency_audit({'python': self.fixture()}, Path('readelf'), Path(temporary))
            self.assertEqual(1, result[0]['elfCount'])
            self.assertEqual('liblzma.so.5', result[0]['versionedLzmaUsers'][0]['needed'])
            elf.return_value = 'Shared library: [liblzma.so]'
            with self.assertRaisesRegex(ValueError, 'requires the conflicting unversioned alias'):
                build.dependency_audit({'python': self.fixture()}, Path('readelf'), Path(temporary))

    def test_derived_python_member_cannot_change_abi_source_or_reuse_old_cache_size(self):
        upstream = json.loads(policy.PIN_FILE.read_text())
        profile = json.loads(build.PROFILE.read_text())
        original = next(row for row in upstream['members'] if row['aar_member'] == build.MEMBER)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'config').mkdir()
            for changed in ('path', 'upstream', 'size'):
                candidate = json.loads(json.dumps(profile))
                if changed == 'path': candidate['pythonLinkage']['member']['path'] = 'jni/x86_64/libpython.zip.so'
                elif changed == 'upstream': candidate['pythonLinkage']['upstream']['sha256'] = '0' * 64
                else: candidate['pythonLinkage']['member']['bytes'] = original['size']
                (root / 'config/android-ffmpeg-audio.json').write_text(json.dumps(candidate))
                with self.subTest(changed=changed), patch.object(policy, 'PIN_FILE', root / 'scripts/android_runtime_pins.json'):
                    with self.assertRaises(ValueError): policy.load_audio_derivation_pins(upstream)


if __name__ == '__main__':
    unittest.main()
