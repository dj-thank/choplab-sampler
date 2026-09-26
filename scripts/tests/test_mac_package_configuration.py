import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('package_mac_app', Path(__file__).parents[1] / 'package_mac_app.py')
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class ReachedJpackage(Exception):
    pass


class MacPackageConfigurationTest(unittest.TestCase):
    def package_arguments(self, client):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'desktop/build').mkdir(parents=True)
            (root / 'gradle.properties').write_text('choplabVersion=0.18.0\nchoplabBuildNumber=30\n')
            tools = root / 'tools'
            tools.mkdir()
            for name in ('ffmpeg', 'ffprobe', 'yt-dlp', 'node', 'manifest.json'):
                (tools / name).touch()
            commands = []

            def run(*args, **kwargs):
                commands.append([str(a) for a in args])
                if Path(args[0]).name == 'jpackage':
                    raise ReachedJpackage()

            with patch.object(PACKAGE, 'ROOT', root), patch.object(PACKAGE, 'run', side_effect=run), \
                    patch.dict(os.environ, {'CHOPLAB_SPOTIFY_CLIENT_ID': client}, clear=True):
                with self.assertRaises(ReachedJpackage):
                    PACKAGE.build(root / 'jdk', tools)
            return commands[-1]

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


if __name__ == '__main__':
    unittest.main()
