"""Verify the packaged NEXT editor's files, production roundtrip and normal native close/reopen.

Uses synthetic audio and a temporary profile. The silent endpoint is for lifecycle
verification; this does not certify audible output, microphone, dialogs or human UX.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def run(*command, environment=None, timeout=90):
    try:
        return subprocess.run([str(value) for value in command], env=environment, check=True,
                              timeout=timeout, capture_output=True, text=True)
    except subprocess.CalledProcessError as failure:
        print((failure.stdout + failure.stderr)[-8000:], file=sys.stderr)
        raise


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def snapshot(profile):
    records = [json.loads(path.read_text()) for path in (profile / 'autosave').glob('autosave.*.json')]
    if not records:
        raise RuntimeError('Normal close must preserve a recoverable autosave')
    return max(records, key=lambda item: item['generation'])


def verify_document(profile, expected):
    saved = snapshot(profile)
    if saved['project'] != expected['project'] or saved['revision'] != expected['revision']:
        raise RuntimeError('Native close/reopen changed the restored production')
    for asset in saved['project']['assets']:
        path = profile / 'assets' / (asset['hash'] + '.' + asset['extension'])
        if not path.is_file() or digest(path) != asset['hash']:
            raise RuntimeError('Restored production lost or changed its audio bytes')


def verify(app, java_home):
    manifest = json.loads((app.parent / 'manifest.json').read_text())
    if manifest['profile'] != 'preview-next':
        raise RuntimeError('Select the isolated NEXT package')
    actual = {str(path.relative_to(app)) for path in app.rglob('*') if path.is_file() and not path.is_symlink()}
    if actual != set(manifest['files']):
        raise RuntimeError('App files differ from the package manifest')
    for name, expected in manifest['files'].items():
        path = app / name
        if path.stat().st_size != expected['bytes'] or digest(path) != expected['sha256']:
            raise RuntimeError('Packaged file differs from its manifest: ' + name)
    run('codesign', '--verify', '--deep', '--strict', app)
    libs = app / 'Contents/app'
    java = app / 'Contents/runtime/Contents' / 'Home/bin/java'
    # Do not inherit launch injection or an existing user's data-directory override.
    environment = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LOCALAPPDATA'):
        environment.pop(key, None)
    environment['PATH'] = '/usr/bin:/bin'
    with tempfile.TemporaryDirectory(prefix='choplab-next-acceptance-') as temporary:
        directory = Path(temporary)
        result = run(java, '-cp', libs / '*', 'com.choplab.desktop.next.NextSelfTest',
                     '--self-test', directory, environment=environment)
        receipt = json.loads(result.stdout.strip().splitlines()[-1])
        if receipt['status'] != 'LOCAL_PASS':
            raise RuntimeError('Packaged production self-test did not pass')
        codec_result = run(java, '-Dchoplab.mediaTools=' + str(libs / 'tools'), '-cp', libs / '*',
                           'com.choplab.desktop.next.NextCodecSelfTest', directory / 'codecs', libs / 'tools/ffmpeg',
                           environment=environment, timeout=120)
        codec_receipt = json.loads(codec_result.stdout.strip().splitlines()[-1])
        if codec_receipt['status'] != 'LOCAL_PASS':
            raise RuntimeError('Packaged original codec self-test did not pass')
        print(codec_result.stdout.strip())
        microphone_result = run(java, '-cp', libs / '*', 'com.choplab.desktop.next.NextMicrophoneSelfTest',
                                directory / 'microphone', environment=environment)
        microphone_receipt = json.loads(microphone_result.stdout.strip().splitlines()[-1])
        if microphone_receipt['status'] != 'LOCAL_PASS':
            raise RuntimeError('Packaged microphone source production self-test did not pass')
        print(microphone_result.stdout.strip())
        library_result = run(java, '-Dchoplab.mediaTools=' + str(libs / 'tools'), '-cp', libs / '*',
                             'com.choplab.desktop.next.NextLibrarySelfTest', directory / 'library', environment=environment)
        library_receipt = json.loads(library_result.stdout.strip().splitlines()[-1])
        if library_receipt['status'] != 'LOCAL_PASS':
            raise RuntimeError('Packaged library source production self-test did not pass')
        print(library_result.stdout.strip())
        online_result = run(java, '-Dchoplab.mediaTools=' + str(libs / 'tools'), '-cp', libs / '*',
                            'com.choplab.desktop.next.NextOnlineSelfTest', directory / 'online', environment=environment)
        if json.loads(online_result.stdout.strip().splitlines()[-1])['status'] != 'LOCAL_PASS':
            raise RuntimeError('Packaged online selection production self-test did not pass')
        print(online_result.stdout.strip())
        run(libs / 'tools/yt-dlp', '--version', environment=environment)
        run(libs / 'tools/node', '--version', environment=environment)
        source = next((directory / 'codecs/production').glob('next-self-test-*')) / 'profile'
        expected = snapshot(source)
        profile = directory / 'profile'
        target = profile / 'Library/Application Support/ChopLab Preview/next-v10'
        shutil.copytree(source, target)
        run(java_home / 'bin/javac', '-d', directory, ROOT / 'scripts/acceptance/MacPreviewLifecycle.java')
        agent_manifest = directory / 'agent.mf'
        agent_manifest.write_text('Premain-Class: MacPreviewLifecycle\n\n')
        agent = directory / 'lifecycle.jar'
        run(java_home / 'bin/jar', '--create', '--file', agent, '--manifest', agent_manifest,
            '-C', directory, 'MacPreviewLifecycle.class')
        for iteration in range(2):
            marker = directory / f'window-{iteration}.txt'
            # JAVA_TOOL_OPTIONS supports quoted option values, including temp roots containing spaces.
            options = [f'-Duser.home={profile}', '-Dchoplab.silentSmoke=true', f'-javaagent:{agent}={marker}']
            launch_environment = dict(environment, JAVA_TOOL_OPTIONS=' '.join('"' + option + '"' for option in options))
            result = run(app / 'Contents/MacOS/ChopLab NEXT', environment=launch_environment)
            if not marker.is_file() or 'NATIVE_WINDOW_RESPONSIVE' not in result.stdout:
                raise RuntimeError('Packaged window did not respond and request normal close')
            if 'Exception in thread' in result.stdout + result.stderr:
                raise RuntimeError('Uncaught exception during native lifecycle')
            verify_document(target, expected)
        print(json.dumps({'status': 'LOCAL_PASS', 'scope': 'packaged-next-native-lifecycle',
                          'source': manifest['source'], 'packageFiles': len(manifest['files']),
                          'packageBytes': sum(item['bytes'] for item in manifest['files'].values()),
                          'exportFrames': receipt['exportFrames'], 'normalCloseReopenCycles': 2,
                          'originalCodecFormats': codec_receipt['formats'],
                          'restoredProjectAndAudioMatch': True, 'nativeAudio': False, 'humanAcceptance': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True, help='Build JDK for the lifecycle test agent only')
    args = parser.parse_args()
    verify(args.app.resolve(), args.java_home.resolve())
