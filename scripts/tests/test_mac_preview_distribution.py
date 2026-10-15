import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from scripts.install_mac_preview import install_package
from scripts.stage_mac_preview_distribution import stage_distribution


class MacPreviewDistributionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.app = self.root / 'build' / 'ChopLab NEXT.app'
        self.app.mkdir(parents=True)
        (self.app / 'audio.jar').write_bytes(b'new bytes')
        self.manifest = self.app.parent / 'manifest.json'
        self.manifest.write_text(json.dumps({
            'source': 'a' * 40, 'working_tree_dirty': False, 'profile': 'preview-next',
            'signing': 'local-ad-hoc', 'notarized': False, 'minimum_system_version': '14.0',
            'files': {'audio.jar': {'bytes': 9, 'sha256': hashlib.sha256(b'new bytes').hexdigest()}},
        }))
        self.output = self.root / 'distribution'
        codesign = patch('scripts.install_mac_preview.subprocess.run')
        self.codesign = codesign.start()
        self.addCleanup(codesign.stop)

    def stage(self, copy=shutil.copytree):
        with patch('scripts.stage_mac_preview_distribution.copy_app', side_effect=copy):
            return stage_distribution(self.app, self.manifest, self.output)

    def test_distribution_is_self_contained_and_installs_and_updates_the_exact_pair(self):
        self.stage()
        self.assertEqual({'ChopLab NEXT.app', 'manifest.json', 'install_mac_preview.py',
                          'インストール.command', 'はじめに.txt'}, {p.name for p in self.output.iterdir()})
        self.assertEqual(self.manifest.read_bytes(), (self.output / 'manifest.json').read_bytes())
        self.assertIn('macOS 14.0+', (self.output / 'はじめに.txt').read_text(encoding='utf-8'))
        self.assertEqual((Path(__file__).parents[1] / 'install_mac_preview.py').read_bytes(),
                         (self.output / 'install_mac_preview.py').read_bytes())
        if os.name != 'nt':
            self.assertTrue(os.access(self.output / 'インストール.command', os.X_OK))
        destination = self.root / 'Applications'
        for previous in (False, True):
            receipt = install_package(self.output / self.app.name, self.output / 'manifest.json', destination,
                                      idle=lambda _: None, copy_bundle=shutil.copytree)
            self.assertEqual(previous, receipt['previousApplicationPreserved'])
            self.assertEqual(b'new bytes', (destination / self.app.name / 'audio.jar').read_bytes())
            self.assertEqual(self.manifest.read_bytes(),
                             (destination / '.choplab-manifests' / (self.app.name + '.json')).read_bytes())
            if previous:
                self.assertEqual(b'new bytes', (Path(receipt['backup']) / self.app.name / 'audio.jar').read_bytes())
                self.assertEqual(self.manifest.read_bytes(), (Path(receipt['backup']) / 'manifest.json').read_bytes())

    def test_bad_source_or_copy_is_not_published(self):
        (self.app / 'audio.jar').write_bytes(b'bad bytes')
        with self.assertRaisesRegex(RuntimeError, 'bytes differ'):
            self.stage()
        self.assertFalse(self.output.exists())
        (self.app / 'audio.jar').write_bytes(b'new bytes')

        def corrupt_copy(source, target):
            shutil.copytree(source, target)
            (target / 'audio.jar').write_bytes(b'bad bytes')

        with self.assertRaisesRegex(RuntimeError, 'bytes differ'):
            self.stage(copy=corrupt_copy)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.mac-distribution-*')))

    def test_existing_output_is_preserved(self):
        self.output.mkdir()
        sentinel = self.output / 'private-project'
        sentinel.write_bytes(b'keep')
        with self.assertRaisesRegex(RuntimeError, 'new distribution directory'):
            self.stage()
        self.assertEqual(b'keep', sentinel.read_bytes())
        self.codesign.assert_not_called()

    def test_dirty_source_is_not_presented_as_a_download(self):
        value = json.loads(self.manifest.read_text())
        value['working_tree_dirty'] = True
        self.manifest.write_text(json.dumps(value))
        with self.assertRaisesRegex(RuntimeError, 'clean source revision'):
            self.stage()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
