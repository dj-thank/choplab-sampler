"""Harness admission/failure coverage; the subprocesses below are synthetic, not Windows acceptance."""
from contextlib import ExitStack, redirect_stdout
import io
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

from scripts import run_windows_acceptance as acceptance


class WindowsAcceptanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def archive(self, members=None, *, omit_self_test=None):
        archive = self.root / 'ChopLab-windows-preview.zip'
        if members is None:
            members = [('ChopLab Preview/' + name, b'fixture') for name in (
                'ChopLab Preview.exe', 'runtime/bin/java.exe', 'tools/ffmpeg.exe', 'tools/ffprobe.exe',
                'tools/yt-dlp.exe', 'tools/qjs.exe')]
            members.append(('ChopLab Preview/tools/runtime.json', b'{}'))
            members.append(('ChopLab Preview/app/ChopLab Preview.cfg',
                b'app.mainclass=com.choplab.desktop.next.LinkedPreviewMainKt\njava-options=-Dchoplab.preview=true\n'))
            jar = io.BytesIO()
            with zipfile.ZipFile(jar, 'w') as classes:
                for _, main, _ in acceptance.SELF_TESTS:
                    if main != omit_self_test:
                        classes.writestr('com/choplab/desktop/next/' + main + '.class', b'synthetic class inventory')
            members.append(('ChopLab Preview/app/desktop.jar', jar.getvalue()))
        with zipfile.ZipFile(archive, 'w') as output:
            for name, data in members:
                output.writestr(name, data)
        return archive

    def test_extraction_refuses_traversal_aliases_case_collisions_and_symlinks(self):
        link = zipfile.ZipInfo('ChopLab Preview/link')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        invalid = (
            [('Other/app.jar', b'x')], [('ChopLab Preview/../outside', b'x')],
            [('ChopLab Preview/file:stream', b'x')], [('ChopLab Preview\\outside', b'x')],
            [('ChopLab Preview//alias', b'x')], [('ChopLab Preview/a', b'x'), ('ChopLab Preview/A', b'y')],
            [(link, b'../outside')],
        )
        for members in invalid:
            with self.subTest(members=str(members)):
                with self.assertRaises(ValueError):
                    acceptance.extract_image(self.archive(members), self.root / 'image')
                self.assertFalse((self.root / 'image').exists())

    def test_legacy_preview_and_conflicting_launcher_flags_do_not_prove_next(self):
        app = acceptance.extract_image(self.archive(), self.root / 'image')
        acceptance.verify_linked_entry(app)
        cfg = app / 'app/ChopLab Preview.cfg'
        for value in (
            'app.mainclass=com.choplab.desktop.DesktopAppKt\njava-options=-Dchoplab.preview=true\n',
            'app.mainclass=com.choplab.desktop.next.LinkedPreviewMainKt\njava-options=-Dchoplab.preview=false\n',
            'app.mainclass=com.choplab.desktop.next.LinkedPreviewMainKt\njava-options=-Dchoplab.preview=true\njava-options=-Dchoplab.preview=false\n',
        ):
            cfg.write_text(value)
            with self.assertRaises(ValueError):
                acceptance.verify_linked_entry(app)

    def test_receipt_must_be_unique_success_for_the_requested_scope(self):
        valid = json.dumps({'scope': 'fixture', 'status': 'LOCAL_PASS'})
        self.assertEqual('LOCAL_PASS', acceptance.json_receipt('log line\n' + valid, 'fixture')['status'])
        for output in (valid, valid + '\n' + valid, valid.replace('LOCAL_PASS', 'FAIL')):
            with self.subTest(output=output), self.assertRaises(ValueError):
                acceptance.json_receipt(output, 'other' if output == valid else 'fixture')

    def test_environment_isolated_from_existing_profiles_and_tool_overrides(self):
        ambient = {key: 'ambient' for key in ('LOCALAPPDATA', 'APPDATA', 'USERPROFILE', 'HOME', 'TEMP', 'TMP',
                   'PATH', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CHOPLAB_MEDIA_TOOLS',
                   'CHOPLAB_SEPARATOR_MODELS', 'PYTHONPATH', 'PYTHONHOME')}
        with patch.dict(os.environ, dict(ambient, SystemRoot=str(self.root / 'Windows'))):
            environment = acceptance.isolated_environment(self.root / 'profile')
        for key in ambient:
            self.assertNotEqual('ambient', environment.get(key))
        self.assertEqual(str(self.root / 'profile/Local'), environment['LOCALAPPDATA'])
        self.assertEqual(str(self.root / 'Windows/System32'), environment['PATH'])

    def test_windows_uppercase_systemroot_preserves_the_isolated_system_path(self):
        with patch.dict(os.environ, {'SYSTEMROOT': str(self.root / 'Windows')}, clear=True):
            environment = acceptance.isolated_environment(self.root / 'uppercase-profile')
        self.assertEqual(str(self.root / 'Windows/System32'), environment['PATH'])
        self.assertEqual(str(self.root / 'uppercase-profile/Local'), environment['LOCALAPPDATA'])

    def run_fixture(self, damage=None, *, omit_self_test=None):
        archive = self.archive(omit_self_test=omit_self_test)
        pwsh = self.root / 'pwsh.exe'
        pwsh.write_bytes(b'fixture')
        args = SimpleNamespace(archive=archive, sha256=acceptance.digest(archive), source_revision='a' * 40,
            version='0.18.0', output=self.root / 'run', pwsh=pwsh, ejs_python=Path('fixture-python'),
            drum_model=None, allow_drum_model_download=False, four_stem_model=None)
        self.args, self.launch_profiles, self.commands = args, [], []

        def command(runner, name, argv, environment, cwd, timeout=180):
            self.commands.append(name)
            argv = list(map(str, argv))
            if name == 'windows-metadata':
                acceptance.write_json(args.output / 'windows-metadata.json', {'authenticode_status': 'NotSigned', 'public_release_verified': False})
            elif name == 'java-runtime':
                return f'java.home = {cwd / "runtime"}\njava.specification.version = 21\n'
            elif name.startswith('version-'):
                return acceptance.prepare_media_tools.TOOL_VERSIONS['ytDlp'] if name == 'version-yt-dlp.exe' else 'fixture version'
            elif name == 'quickjs':
                acceptance.write_json(args.output / 'quickjs.json', {'status': 'LOCAL_PASS', 'networkUsed': False, 'providerVerified': False})
            elif name.startswith('normal-close-'):
                profile = Path(argv[argv.index('-ProfileRoot') + 1])
                self.launch_profiles.append(profile)
                if damage == 'asset':
                    next((profile / 'Local/ChopLab Preview/next-v10/assets').iterdir()).write_bytes(b'tampered')
                elif damage == 'old-data':
                    (profile / 'Local/ChopLab/preferences.sentinel').write_text('tampered')
                elif damage == 'package':
                    (cwd / 'tools/qjs.exe').write_bytes(b'tampered')
                acceptance.write_json(Path(argv[argv.index('-MetadataOutput') + 1]),
                    {'status': 'LOCAL_PASS', 'profile_retained': True, 'all_owned_processes_exited_zero': True})
            else:
                scope = next(scope for item, _, scope in acceptance.SELF_TESTS if item == name)
                if name == 'codecs':
                    directory = args.output / 'codecs/production/next-self-test-fixture/profile'
                    (directory / 'assets').mkdir(parents=True)
                    (directory / 'autosave').mkdir()
                    data = directory / 'assets/input.wav'
                    data.write_bytes(b'synthetic fixture audio bytes')
                    sha = acceptance.digest(data)
                    data.rename(data.with_name(sha + '.wav'))
                    acceptance.write_json(directory / 'autosave/autosave.0.json', {'generation': 0, 'revision': 17,
                        'project': {'assets': [{'hash': sha, 'extension': 'wav'}]}})
                return json.dumps({'status': 'LOCAL_PASS', 'scope': scope})
            return ''

        with ExitStack() as patches, redirect_stdout(io.StringIO()):
            patches.enter_context(patch.object(acceptance.platform, 'system', return_value='Windows'))
            patches.enter_context(patch.dict(os.environ, SystemRoot=str(self.root / 'Windows')))
            patches.enter_context(patch.object(acceptance.check_public_surface, 'scan_zip', return_value=[]))
            patches.enter_context(patch.object(acceptance.measure_distribution, 'measure', return_value={'sizeGate': 'PASS'}))
            patches.enter_context(patch.object(acceptance.prepare_media_tools, 'cache_valid', return_value=True))
            patches.enter_context(patch.object(acceptance.Acceptance, 'command', command))
            acceptance.verify(args)
        return acceptance.read_json(args.output / 'acceptance.json')

    def test_two_normal_reopens_share_profile_and_do_not_invent_unrun_model_or_feature_results(self):
        receipt = self.run_fixture()
        self.assertEqual('COMPLETED', receipt['status'])
        self.assertEqual([self.args.output / 'profile'] * 2, self.launch_profiles)
        self.assertTrue(receipt['syntheticOldDataSentinelUnchanged'])
        self.assertFalse(receipt['allFeaturesVerified'])
        self.assertFalse(receipt['audioDeviceVerified'])
        self.assertFalse(receipt['publicReleaseVerified'])
        checks = {row['name']: row for row in receipt['checks']}
        self.assertEqual('PASS', checks['self-test-entrypoints']['status'])
        self.assertEqual(len(acceptance.SELF_TESTS), checks['self-test-entrypoints']['result']['uniquePackagedClasses'])
        for name in ('drums', 'four-stem', 'installer'):
            self.assertEqual('NOT_RUN', checks[name]['status'])
        self.assertEqual('PASS', checks['whole-creation']['status'])
        self.assertEqual('packaged-headless-whole-creation', checks['whole-creation']['scope'])
        for name in ('normal-close-0', 'normal-close-1'):
            self.assertEqual('PASS', checks[name]['status'])
            self.assertTrue(checks[name]['result']['preservedProjectAndAudioMatch'])
            self.assertFalse(checks[name]['result']['visibleRestoredDocumentVerified'])
        for row in receipt['checks']:
            self.assertEqual(self.args.sha256, row['archiveSha256'])
            self.assertEqual(self.args.source_revision, row['sourceRevision'])

    def test_missing_required_fixture_fails_before_execution_including_whole_creation(self):
        for _, main, _ in acceptance.SELF_TESTS:
            with self.subTest(main=main), tempfile.TemporaryDirectory() as temporary:
                self.root = Path(temporary)
                with self.assertRaisesRegex(ValueError, 'missing=' + main):
                    self.run_fixture(omit_self_test=main)
                receipt = acceptance.read_json(self.args.output / 'acceptance.json')
                checks = {row['name']: row for row in receipt['checks']}
                self.assertEqual('FAILED', receipt['status'])
                self.assertEqual('FAIL', checks['self-test-entrypoints']['status'])
                self.assertFalse(receipt['allFeaturesVerified'])
                self.assertEqual([], self.commands)
                for name, _, _ in acceptance.SELF_TESTS:
                    self.assertEqual('NOT_RUN', checks[name]['status'])

    def test_duplicate_entrypoint_cannot_hide_another_packaged_version(self):
        app = acceptance.extract_image(self.archive(), self.root / 'image')
        with zipfile.ZipFile(app / 'app/stale.jar', 'w') as archive:
            archive.writestr('com/choplab/desktop/next/NextWholeCreationSelfTest.class', b'stale fixture')
        with self.assertRaisesRegex(ValueError, 'duplicate=NextWholeCreationSelfTest'):
            acceptance.verify_self_test_entrypoints(app / 'app')

    def test_fake_lifecycle_success_cannot_hide_changed_audio_old_data_or_package(self):
        for damage in ('asset', 'old-data', 'package'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory() as temporary:
                self.root = Path(temporary)
                with self.assertRaises((RuntimeError, ValueError)):
                    self.run_fixture(damage)
                receipt = acceptance.read_json(self.args.output / 'acceptance.json')
                self.assertEqual('FAILED', receipt['status'])
                self.assertTrue(any(row['status'] == 'FAIL' for row in receipt['checks']))
                self.assertFalse(receipt['allFeaturesVerified'])
                if damage != 'package':
                    self.assertEqual(1, len(self.launch_profiles))
                    remaining = next(row for row in receipt['checks'] if row['name'] == 'normal-close-1')
                    self.assertEqual('NOT_RUN', remaining['status'])

    def test_wrong_platform_or_zip_hash_fails_before_creating_a_profile(self):
        archive = self.archive()
        args = SimpleNamespace(archive=archive, sha256='0' * 64, source_revision='a' * 40, output=self.root / 'run')
        for system in ('Darwin', 'Windows'):
            with self.subTest(system=system), patch.object(acceptance.platform, 'system', return_value=system):
                with self.assertRaises(ValueError):
                    acceptance.verify(args)
                self.assertFalse(args.output.exists())


if __name__ == '__main__':
    unittest.main()
