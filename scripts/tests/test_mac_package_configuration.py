import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import plistlib
import tempfile
import unittest
from unittest.mock import patch

from scripts import prepare_source_notices as NOTICES
from scripts.tests.test_source_notices import committed_git


SPEC = importlib.util.spec_from_file_location('package_mac_app', Path(__file__).parents[1] / 'package_mac_app.py')
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class ReachedJpackage(Exception):
    pass


class MacPackageConfigurationTest(unittest.TestCase):
    def package_commands(self, client, linked=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'desktop/build').mkdir(parents=True)
            (root / 'gradle.properties').write_text('choplabVersion=0.18.0\nchoplabBuildNumber=30\n')
            tools = root / 'tools'
            tools.mkdir()
            for name in ('ffmpeg', 'ffprobe', 'yt-dlp', 'node', 'manifest.json'):
                (tools / name).touch()
            (root / 'work/separator-models').mkdir(parents=True)
            (root / 'work/separator-models/fixture.onnx').write_bytes(b'model')
            commands = []

            def run(*args, **kwargs):
                commands.append([str(a) for a in args])
                if Path(args[0]).name == 'jpackage':
                    raise ReachedJpackage()

            with patch.object(PACKAGE, 'ROOT', root), patch.object(PACKAGE, 'run', side_effect=run), \
                    patch.dict(os.environ, {'CHOPLAB_SPOTIFY_CLIENT_ID': client}, clear=True):
                with self.assertRaises(ReachedJpackage):
                    PACKAGE.build(root / 'jdk', tools, linked=linked)
            return commands

    def package_arguments(self, client):
        return self.package_commands(client)[-1]

    def test_registered_public_client_is_embedded_in_finder_launch_configuration(self):
        client = '0123456789abcdef0123456789abcdef'
        args = self.package_arguments(client)
        option = '-Dchoplab.spotifyClientId=' + client
        self.assertEqual(args.count(option), 1)
        self.assertEqual(args[args.index(option) - 1], '--java-options')

    def test_unconfigured_build_does_not_override_the_runtime_environment(self):
        self.assertFalse(any(a.startswith('-Dchoplab.spotifyClientId=') for a in self.package_arguments('')))

    def test_invalid_configuration_fails_before_build_or_replacing_an_app(self):
        for client in ('too-short', 'a' * 129, 'a' * 32 + '\n', 'a' * 32 + ' -Xmx1g'):
            with self.subTest(client=client), patch.dict(os.environ, {'CHOPLAB_SPOTIFY_CLIENT_ID': client}, clear=True), \
                    patch.object(PACKAGE, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'CHOPLAB_SPOTIFY_CLIENT_ID'):
                    PACKAGE.build(Path('unused-jdk'), Path('unused-tools'))
                run.assert_not_called()

    def test_linked_editor_has_tools_pinned_model_and_public_spotify_configuration(self):
        commands = self.package_commands('0123456789abcdef0123456789abcdef', linked=True)
        # The same pinned model preparer precedes packaging for NEXT.
        self.assertEqual(2, len(commands))
        self.assertIn("prepare_separator_model.py", commands[0][1])
        args = commands[-1]
        self.assertEqual('com.choplab.desktop.next.LinkedPreviewMainKt', args[args.index('--main-class') + 1])
        self.assertEqual('ChopLab NEXT', args[args.index('--name') + 1])
        self.assertEqual('com.choplab.sampler.preview.next', args[args.index('--mac-package-identifier') + 1])
        self.assertIn('-Dchoplab.preview=true', args)
        self.assertIn('-Dchoplab.mediaTools=$APPDIR/tools', args)
        self.assertIn('-Dchoplab.systemAudioHelper=$APPDIR/choplab-sck-audio', args)
        self.assertIn('-Dchoplab.separatorModels=$APPDIR/models', args)
        self.assertEqual(1, args.count('-Dchoplab.spotifyClientId=0123456789abcdef0123456789abcdef'))
        self.assertFalse(any('secret' in a.lower() or 'token' in a.lower() for a in args))

    def test_linked_editor_is_never_built_as_a_signed_release(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(PACKAGE, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'local ad-hoc'):
                PACKAGE.build(Path('unused-jdk'), None, signed=True, linked=True)
            run.assert_not_called()

    def test_ad_hoc_signing_first_removes_the_vendor_signature_from_the_launcher(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'desktop/build/install/desktop/lib').mkdir(parents=True)
            (root / 'gradle.properties').write_text('choplabVersion=0.18.0\nchoplabBuildNumber=30\n')
            for name in ('LICENSE', 'NOTICE.md'):
                (root / name).write_text(name)
            tools = root / 'tools'
            tools.mkdir()
            for name in ('ffmpeg', 'ffprobe', 'yt-dlp', 'node', 'manifest.json'):
                (tools / name).touch()
            (root / 'work/separator-models').mkdir(parents=True)
            (root / 'work/separator-models/fixture.onnx').write_bytes(b'model')
            commands = []

            def run(*args, **kwargs):
                args = [str(a) for a in args]
                commands.append(args)
                if Path(args[0]).name == 'jpackage':
                    # The smallest app image jpackage would leave behind.
                    app = Path(args[args.index('--dest') + 1]) / (args[args.index('--name') + 1] + '.app')
                    (app / 'Contents/MacOS').mkdir(parents=True)
                    (app / 'Contents/MacOS' / args[args.index('--name') + 1]).write_bytes(b'launcher')
                    (app / 'Contents/app').mkdir()
                    (app / 'Contents/runtime/Contents' / 'Home' / 'legal').mkdir(parents=True)
                    with (app / 'Contents/Info.plist').open('wb') as stream:
                        plistlib.dump({}, stream)
                elif len(args) > 1 and Path(args[1]).name == 'prepare_source_notices.py':
                    self.assertEqual('mac', args[args.index('--platform') + 1])
                    with patch.object(NOTICES, 'git', side_effect=committed_git):
                        NOTICES.stage(NOTICES.ROOT, Path(args[args.index('--out') + 1]), 'mac')

            with patch.object(PACKAGE, 'ROOT', root), patch.object(PACKAGE, 'run', side_effect=run), \
                    patch.object(PACKAGE.subprocess, 'check_output', return_value=''), \
                    patch.object(PACKAGE, 'minimum_system_version', return_value='27.0'), \
                    patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(io.StringIO()):
                PACKAGE.build(root / 'jdk', tools, linked=True)
            codesign = [c[1:] for c in commands if c[0] == 'codesign']
            launcher = str(root / 'desktop/build')
            removed = [i for i, c in enumerate(codesign) if c[0] == '--remove-signature']
            signed_app = [i for i, c in enumerate(codesign) if c[:3] == ['--force', '--sign', '-'] and c[3].endswith('ChopLab NEXT.app')]
            self.assertEqual(1, len(removed))
            self.assertTrue(codesign[removed[0]][1].startswith(launcher))
            self.assertEqual(('ChopLab NEXT.app', 'Contents', 'MacOS', 'ChopLab NEXT'), Path(codesign[removed[0]][1]).parts[-4:])
            self.assertEqual(1, len(signed_app))
            self.assertLess(removed[0], signed_app[0])
            plist_path = root / 'desktop/build/mac-linked-preview-app-image/ChopLab NEXT.app/Contents/Info.plist'
            with plist_path.open('rb') as stream:
                plist = plistlib.load(stream)
                self.assertEqual('27.0', plist['LSMinimumSystemVersion'])
                self.assertEqual('1', plist['LSEnvironment']['ORT_DISABLE_TELEMETRY'])
            manifest = json.loads((plist_path.parents[2] / 'manifest.json').read_text())
            self.assertEqual('27.0', manifest['minimum_system_version'])
            application = plist_path.parent / 'app'
            source_index = json.loads((application / 'SOURCE-INDEX.json').read_text())
            self.assertFalse(source_index['publicPass'])
            for row in source_index['files']:
                key = 'Contents/app/' + row['path']
                self.assertEqual(row['sha256'], manifest['files'][key]['sha256'])
            stage_index = next(i for i, command in enumerate(commands)
                               if len(command) > 1 and Path(command[1]).name == 'prepare_source_notices.py')
            sign_index = next(i for i, command in enumerate(commands) if command[0] == 'codesign')
            self.assertLess(stage_index, sign_index)


if __name__ == '__main__':
    unittest.main()
