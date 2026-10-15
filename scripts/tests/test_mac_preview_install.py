import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts.install_mac_preview import install_package, require_idle, verify_package


class MacPreviewInstallTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'DMG' / 'ChopLab NEXT.app'
        self.source.mkdir(parents=True)
        (self.source / 'audio.jar').write_bytes(b'new bytes')
        self.manifest = self.source.parent / 'manifest.json'
        self.manifest.write_text(json.dumps({'source': 'a' * 40, 'working_tree_dirty': False,
            'profile': 'preview-next', 'files': {'audio.jar': {'bytes': 9,
            'sha256': hashlib.sha256(b'new bytes').hexdigest()}}}))
        self.destination = self.root / 'Applications'
        self.target = self.destination / self.source.name
        self.sidecar = self.destination / '.choplab-manifests' / (self.target.name + '.json')
        self.profile = self.root / 'UserMusic'
        self.profile.mkdir()
        (self.profile / 'private-project').write_bytes(b'keep production')

    def old(self):
        self.target.mkdir(parents=True)
        (self.target / 'audio.jar').write_bytes(b'old bytes')
        self.sidecar.parent.mkdir(exist_ok=True)
        self.sidecar.write_text('old manifest preserved even if from a manual installation')

    def install(self, **kwargs):
        with patch('scripts.install_mac_preview.subprocess.run'):
            return install_package(self.source, self.manifest, self.destination, idle=kwargs.pop('idle', lambda _: None),
                                   copy_bundle=lambda a, b: shutil.copytree(a, b), **kwargs)

    def assert_current(self):
        self.assertEqual(b'new bytes', (self.target / 'audio.jar').read_bytes())
        self.assertEqual(self.manifest.read_bytes(), self.sidecar.read_bytes())
        self.assertEqual(b'keep production', (self.profile / 'private-project').read_bytes())

    def test_first_install_has_exact_app_and_manifest(self):
        receipt = self.install()
        self.assert_current()
        self.assertFalse(receipt['previousApplicationPreserved'])
        self.assertFalse((self.destination / '.choplab-install-lock').exists())

    def test_update_preserves_old_pair_and_never_changes_user_music(self):
        self.old()
        old_manifest = self.sidecar.read_bytes()
        receipt = self.install()
        self.assert_current()
        backup = Path(receipt['backup'])
        self.assertEqual(b'old bytes', (backup / self.target.name / 'audio.jar').read_bytes())
        self.assertEqual(old_manifest, (backup / 'manifest.json').read_bytes())

    def test_running_app_is_refused_before_copy_and_when_opened_during_copy(self):
        self.old()
        for reject_after in (1, 2):
            calls = []
            def idle(_):
                calls.append(1)
                if len(calls) == reject_after:
                    raise RuntimeError('running')
            with self.assertRaisesRegex(RuntimeError, 'running'):
                self.install(idle=idle)
            self.assertEqual(b'old bytes', (self.target / 'audio.jar').read_bytes())
            self.assertTrue(self.sidecar.read_text().startswith('old manifest'))

    def test_failed_manifest_publish_restores_both_halves(self):
        self.old()
        previous = self.sidecar.read_bytes()
        def fail_manifest(source, target):
            if Path(source).name == 'manifest.json':
                raise OSError('disk full')
            os.replace(source, target)
        with self.assertRaisesRegex(OSError, 'disk full'):
            self.install(replace=fail_manifest)
        self.assertEqual(b'old bytes', (self.target / 'audio.jar').read_bytes())
        self.assertEqual(previous, self.sidecar.read_bytes())

    def test_failed_final_verification_rolls_back_first_install_and_update(self):
        for existing in (False, True):
            if existing:
                self.old()
            calls = []
            def verify(app, manifest):
                calls.append(app)
                if len(calls) == 3:
                    raise RuntimeError('bad final copy')
                return verify_package(app, manifest)
            with self.assertRaisesRegex(RuntimeError, 'bad final copy'):
                self.install(verify=verify)
            self.assertEqual(existing, self.target.exists())
            self.assertEqual(existing, self.sidecar.exists())
            if existing:
                self.assertEqual(b'old bytes', (self.target / 'audio.jar').read_bytes())
                self.assertTrue(self.sidecar.read_text().startswith('old manifest'))

    def test_modified_app_or_symlink_destination_is_refused(self):
        (self.source / 'audio.jar').write_bytes(b'tampered!')
        with self.assertRaisesRegex(RuntimeError, 'bytes differ'):
            self.install()
        self.assertFalse(self.destination.exists())
        (self.source / 'audio.jar').write_bytes(b'new bytes')
        self.destination.symlink_to(self.profile, target_is_directory=True)
        with self.assertRaisesRegex(RuntimeError, 'regular directory'):
            self.install()
        self.assertEqual(['private-project'], [p.name for p in self.profile.iterdir()])

    def test_lsof_failure_or_live_process_never_means_idle(self):
        self.old()
        for code, output, error in ((0, '123\n', ''), (2, '', 'failed'), (1, '', 'cannot inspect')):
            with patch('scripts.install_mac_preview.subprocess.run') as run, \
                    patch('scripts.install_mac_preview.time.monotonic', side_effect=[0, 0, 30, 30]), \
                    patch('scripts.install_mac_preview.time.sleep'):
                run.return_value.returncode = code
                run.return_value.stdout = output
                run.return_value.stderr = error
                with self.assertRaises(RuntimeError):
                    require_idle(self.target)

    def test_transient_reader_must_release_all_handles_before_update(self):
        self.old()
        probes = [subprocess.CompletedProcess([], 0, '123\n456\n', ''),
                  subprocess.CompletedProcess([], 0, '456\n', ''),
                  subprocess.CompletedProcess([], 1, '', '')]
        with patch('scripts.install_mac_preview.subprocess.run', side_effect=probes) as run, \
                patch('scripts.install_mac_preview.time.monotonic', side_effect=[0, 0, 1, 1.25, 2, 2.25]), \
                patch('scripts.install_mac_preview.time.sleep') as sleep:
            require_idle(self.target)
        self.assertEqual(3, run.call_count)
        self.assertEqual(2, sleep.call_count)
        self.assertEqual([30, 28.75, 27.75], [call.kwargs['timeout'] for call in run.call_args_list])

    def test_reader_that_outlives_original_budget_is_refused(self):
        self.old()
        with patch('scripts.install_mac_preview.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 0, '123\n', '')) as run, \
                patch('scripts.install_mac_preview.time.monotonic', side_effect=[0, 0, 29.9, 30]), \
                patch('scripts.install_mac_preview.time.sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, 'still in use'):
                require_idle(self.target)
        self.assertEqual(1, run.call_count)
        self.assertAlmostEqual(0.1, sleep.call_args.args[0])

    def test_failed_probe_after_busy_result_is_not_retried_or_accepted(self):
        self.old()
        probes = [subprocess.CompletedProcess([], 0, '123\n', ''),
                  subprocess.CompletedProcess([], 1, '', 'cannot inspect')]
        with patch('scripts.install_mac_preview.subprocess.run', side_effect=probes) as run, \
                patch('scripts.install_mac_preview.time.monotonic', side_effect=[0, 0, 1, 1.25]), \
                patch('scripts.install_mac_preview.time.sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, 'Could not confirm'):
                require_idle(self.target)
        self.assertEqual(2, run.call_count)
        self.assertEqual(1, sleep.call_count)


if __name__ == '__main__':
    unittest.main()
