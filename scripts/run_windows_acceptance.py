"""Run bounded, synthetic acceptance against an exact Windows Linked Preview ZIP.

Uses the packaged Java/tools and the existing production self-tests. The caller
supplies CI/release source provenance and an independently obtained ZIP digest.
No install, user profile, microphone, audible endpoint or provider is used.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import subprocess
import sys
import time
import zipfile

if __package__:
    from . import check_public_surface, measure_distribution, prepare_media_tools, prepare_separator_model
    from .run_mac_next_acceptance import digest, snapshot, verify_document
else:
    import check_public_surface, measure_distribution, prepare_media_tools, prepare_separator_model
    from run_mac_next_acceptance import digest, snapshot, verify_document

ROOT = Path(__file__).resolve().parents[1]
PROFILE_MARKER = 'choplab-windows-acceptance-v1'
SELF_TESTS = (
    ('studio', 'NextSelfTest', 'headless-file-studio-engine'),
    ('whole-creation', 'NextWholeCreationSelfTest', 'packaged-headless-whole-creation'),
    ('codecs', 'NextCodecSelfTest', 'packaged-original-codecs'),
    ('microphone-synthetic', 'NextMicrophoneSelfTest', 'microphone-source-production'),
    ('library', 'NextLibrarySelfTest', 'library-source-production'),
    ('online-synthetic', 'NextOnlineSelfTest', 'online-source-production'),
)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def json_receipt(output, scope):
    records = []
    for line in output.splitlines():
        try:
            value = json.loads(line)
        except ValueError:
            continue
        if isinstance(value, dict) and value.get('scope') == scope:
            records.append(value)
    if len(records) != 1 or records[0].get('status') != 'LOCAL_PASS':
        raise ValueError('Missing or unsuccessful self-test receipt: ' + scope)
    return records[0]


def tree_manifest(directory):
    rows = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink() or (getattr(path.lstat(), 'st_file_attributes', 0) & 0x400):
            raise ValueError('Reparse/symlink in acceptance tree')
        if path.is_file():
            rows[path.relative_to(directory).as_posix()] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    return rows


def extract_image(archive, destination):
    """The existing archive policy must pass before this function is called."""
    if destination.exists():
        raise ValueError('Use a new acceptance extraction directory')
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if len(members) > 4096 or sum(m.file_size for m in members) > 1024 ** 3:
            raise ValueError('Windows image exceeds the extraction budget')
        seen = set()
        for member in members:
            path = PurePosixPath(member.filename)
            if (not path.parts or path.parts[0] != 'ChopLab Preview' or path.is_absolute()
                    or '..' in path.parts or '\\' in member.filename or ':' in member.filename
                    or str(path).rstrip('/') != member.filename.rstrip('/')
                    or stat.S_ISLNK(member.external_attr >> 16)):
                raise ValueError('Unsafe or unexpected Windows image path')
            key = str(path).casefold()
            if key in seen:
                raise ValueError('Duplicate/case-colliding Windows image path')
            seen.add(key)
        source.extractall(destination)
    return destination / 'ChopLab Preview'


def verify_linked_entry(app):
    text = (app / 'app/ChopLab Preview.cfg').read_text(encoding='utf-8-sig')
    if (re.findall(r'^app\.mainclass=(.+)$', text, re.M) != ['com.choplab.desktop.next.LinkedPreviewMainKt']
            or re.findall(r'^java-options=-Dchoplab\.preview=(.+)$', text, re.M) != ['true']):
        raise ValueError('This is not the Linked Preview launcher; legacy Preview cannot prove NEXT restart')
    for name in ('ChopLab Preview.exe', 'runtime/bin/java.exe', 'tools/ffmpeg.exe', 'tools/ffprobe.exe', 'tools/yt-dlp.exe', 'tools/qjs.exe'):
        if not (app / name).is_file():
            raise ValueError('Missing packaged file: ' + name)


def verify_self_test_entrypoints(libraries):
    """Fail before execution when the selected source has not integrated a required fixture."""
    required = {'com/choplab/desktop/next/' + main + '.class': main for _, main, _ in SELF_TESTS}
    occurrences = {name: 0 for name in required}
    for jar in sorted(libraries.glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name in occurrences:
                    occurrences[name] += 1
    missing = [required[name] for name, count in occurrences.items() if count == 0]
    duplicate = [required[name] for name, count in occurrences.items() if count > 1]
    if missing or duplicate:
        raise ValueError('Required packaged self-test entrypoints are incomplete: missing=' + ','.join(missing)
                         + '; duplicate=' + ','.join(duplicate)
                         + '. Integrate the matching source fixtures and rebuild; no required self-test may be skipped.')
    return {'requiredClasses': list(required.values()), 'uniquePackagedClasses': len(required)}


def isolated_environment(profile):
    environment = dict(os.environ)
    # Windows os.environ normalizes keys to uppercase; a plain dict does not.
    system_root = environment.get('SYSTEMROOT') or environment.get('SystemRoot')
    if not system_root:
        raise ValueError('Windows SystemRoot is unavailable')
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CHOPLAB_MEDIA_TOOLS',
                'CHOPLAB_SEPARATOR_MODELS', 'PYTHONPATH', 'PYTHONHOME'):
        environment.pop(key, None)
    temporary = profile / 'Temporary'
    temporary.mkdir(parents=True, exist_ok=True)
    environment.update(LOCALAPPDATA=str(profile / 'Local'), APPDATA=str(profile / 'Roaming'),
                       USERPROFILE=str(profile), HOME=str(profile), TEMP=str(temporary), TMP=str(temporary),
                       PATH=str(Path(system_root) / 'System32'),
                       POWERSHELL_TELEMETRY_OPTOUT='1', POWERSHELL_UPDATECHECK='Off')
    return environment


class Acceptance:
    def __init__(self, directory, source_revision, archive_hash):
        self.directory = directory
        self.receipt = {'schemaVersion': 1, 'status': 'RUNNING', 'scope': 'windows-packaged-synthetic-and-lifecycle',
                        'sourceRevision': source_revision, 'archiveSha256': archive_hash,
                        'sourceBinding': 'Caller-provided CI/release identity; ZIP SHA-256 checked independently.',
                        'startedAtUtc': datetime.now(timezone.utc).isoformat(), 'platform': platform.platform(),
                        'checks': [], 'audioDeviceVerified': False, 'providerVerified': False,
                        'publicReleaseVerified': False, 'humanAcceptance': False, 'allFeaturesVerified': False}
        self.persist()

    def persist(self):
        write_json(self.directory / 'acceptance.json', self.receipt)

    def check(self, name, scope, operation):
        started = time.monotonic()
        row = {'name': name, 'scope': scope, 'sourceRevision': self.receipt['sourceRevision'],
               'archiveSha256': self.receipt['archiveSha256']}
        self.receipt['checks'].append(row)
        try:
            result = operation()
            row.update(status='PASS', result=result)
            return result
        except Exception as failure:
            row.update(status='FAIL', errorType=type(failure).__name__, reason=str(failure))
            raise
        finally:
            row['milliseconds'] = round((time.monotonic() - started) * 1000)
            self.persist()

    def skip(self, name, scope, reason):
        self.receipt['checks'].append({'name': name, 'scope': scope, 'status': 'NOT_RUN', 'reason': reason,
                                       'sourceRevision': self.receipt['sourceRevision'], 'archiveSha256': self.receipt['archiveSha256']})
        self.persist()

    def command(self, name, command, environment, cwd, timeout=180):
        output = self.directory / (name + '.log')
        with output.open('w', encoding='utf-8') as log:
            result = subprocess.run(list(map(str, command)), env=environment, cwd=cwd, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f'{name} exited {result.returncode}; see {output.name}')
        if output.stat().st_size > 1024 * 1024:
            raise RuntimeError('Acceptance output exceeded 1 MiB: ' + output.name)
        return output.read_text(encoding='utf-8-sig')


def verify(args):
    if platform.system() != 'Windows':
        raise ValueError('Run this harness on Windows; tests on another host are separate evidence')
    if not re.fullmatch('[0-9a-f]{40}', args.source_revision) or not re.fullmatch('[0-9a-f]{64}', args.sha256):
        raise ValueError('Supply full lowercase source revision and independently obtained ZIP SHA-256')
    if digest(args.archive) != args.sha256:
        raise ValueError('ZIP digest differs from the selected artifact')
    args.output.mkdir(parents=True, exist_ok=False)
    acceptance = Acceptance(args.output, args.source_revision, args.sha256)
    profile = args.output / 'profile'
    profile.mkdir()
    (profile / '.choplab-acceptance-profile').write_text(PROFILE_MARKER + '\n', encoding='utf-8')
    # A synthetic old stable profile is adjacent to Preview, never a real user's directory.
    stable = profile / 'Local/ChopLab'
    (stable / 'projects').mkdir(parents=True)
    (stable / 'projects/keep.sentinel').write_text('owned synthetic old project sentinel\n')
    (stable / 'preferences.sentinel').write_text('owned synthetic old preferences sentinel\n')
    old_data = tree_manifest(stable)
    environment = isolated_environment(profile)
    environment['GITHUB_SHA'] = args.source_revision
    completed = False
    try:
        def inspect_archive():
            findings = check_public_surface.scan_zip(args.archive, label=args.archive.name,
                nested_archive_count_limit=check_public_surface.explicit_archive_nested_limit(args.archive))
            if findings:
                raise ValueError('Archive policy rejected the candidate: ' + '; '.join(findings[:3]))
            report = measure_distribution.measure(args.archive, 'windows')
            write_json(args.output / 'size.json', report)
            if report['sizeGate'] != 'PASS':
                raise ValueError('Windows initial ZIP exceeds 200,000,000 bytes')
            return report
        acceptance.check('archive', 'exact-zip-hash-size-and-public-surface', inspect_archive)
        def image():
            extracted = extract_image(args.archive, args.output / 'image')
            verify_linked_entry(extracted)
            return {'entry': 'com.choplab.desktop.next.LinkedPreviewMainKt', 'preview': True}
        acceptance.check('image', 'linked-preview-entry-and-isolated-data-identity', image)
        app = args.output / 'image/ChopLab Preview'
        payload = tree_manifest(app)
        write_json(args.output / 'payload.json', payload)
        java, libraries, tools = app / 'runtime/bin/java.exe', app / 'app', app / 'tools'
        acceptance.check('self-test-entrypoints', 'required-packaged-self-test-class-inventory',
                         lambda: verify_self_test_entrypoints(libraries))
        pwsh = args.pwsh or Path(shutil.which('pwsh') or '')
        if not pwsh.is_file():
            raise ValueError('PowerShell 7 is required; specify --pwsh if it is not on PATH')
        pwsh = pwsh.resolve()

        def metadata():
            destination = args.output / 'windows-metadata.json'
            acceptance.command('windows-metadata', [pwsh, '-NoProfile', '-NonInteractive', '-File', ROOT / 'scripts/verify-windows-artifact.ps1',
                '-AppImage', app, '-ExecutableName', 'ChopLab Preview.exe', '-ExpectedVersion', args.version,
                '-MetadataOutput', destination], environment, app)
            result = read_json(destination)
            if result['authenticode_status'] not in ('Valid', 'NotSigned'):
                raise ValueError('Unexpected Authenticode state: ' + result['authenticode_status'])
            return result
        acceptance.check('metadata', 'version-and-authenticode-state-only', metadata)
        java_arguments = [java, '-Xmx1536m', '-Duser.home=' + str(profile), '-Djava.io.tmpdir=' + str(profile / 'Temporary'),
                          '-Dchoplab.preview=true', '-Dchoplab.mediaTools=' + str(tools), '-cp', libraries / '*']

        def runtime():
            output = acceptance.command('java-runtime', [java, '-Duser.home=' + str(profile), '-XshowSettings:properties', '-version'], environment, app)
            actual = re.search(r'^\s*java\.home\s*=\s*(.+)$', output, re.M)
            if not actual or Path(actual[1].strip()).resolve() != (app / 'runtime').resolve():
                raise ValueError('Java did not use the packaged runtime')
            if not re.search(r'^\s*java\.specification\.version\s*=\s*21\s*$', output, re.M):
                raise ValueError('Packaged runtime is not the required Java 21')
            tool_receipt = read_json(tools / 'runtime.json')
            audio_inputs = tool_receipt.get('ffmpegAudioInputsSha256')
            audio_profile = prepare_media_tools.windows_audio_runtime.load_profile() if audio_inputs else None
            if not prepare_media_tools.cache_valid(tools, audio_profile):
                raise ValueError('Packaged media tools/notices differ from their receipts and QuickJS pin')
            versions = {}
            for name, option in (('yt-dlp.exe', '--version'), ('ffmpeg.exe', '-version'), ('ffprobe.exe', '-version'), ('qjs.exe', '--version')):
                versions[name] = acceptance.command('version-' + name, [tools / name, option], environment, app).strip().splitlines()[0]
            if versions['yt-dlp.exe'] != prepare_media_tools.TOOL_VERSIONS['ytDlp']:
                raise ValueError('Packaged yt-dlp version differs from the offline adapter')
            return {'packagedJavaHomeVerified': True, 'javaSha256': digest(java), 'toolVersions': versions,
                    'toolReceiptSha256': digest(tools / 'runtime.json'), 'ffmpegAudioInputsSha256': audio_inputs,
                    'payloadFiles': len(payload)}
        acceptance.check('runtime', 'packaged-java-tools-and-pinned-derivations', runtime)
        for name, main, scope in SELF_TESTS:
            directory = args.output / name
            arguments = ['--self-test', directory] if main in ('NextSelfTest', 'NextWholeCreationSelfTest') else [directory]
            if main == 'NextCodecSelfTest':
                arguments.append(tools / 'ffmpeg.exe')
            acceptance.check(name, scope, lambda name=name, main=main, scope=scope, arguments=arguments:
                json_receipt(acceptance.command(name, java_arguments + ['com.choplab.desktop.next.' + main] + arguments,
                                              environment, app, timeout=240), scope))

        def quickjs():
            receipt = args.output / 'quickjs.json'
            acceptance.command('quickjs', [args.ejs_python, ROOT / 'scripts/acceptance/quickjs_offline.py', '--qjs', tools / 'qjs.exe',
                '--receipt', receipt], environment, app, timeout=120)
            result = read_json(receipt)
            if result['status'] != 'LOCAL_PASS' or result['networkUsed'] or result['providerVerified']:
                raise ValueError('Offline EJS fixture did not establish the intended scope')
            result['adapterOrigin'] = 'isolated same-version yt-dlp Python package; packaged qjs.exe'
            return result
        acceptance.check('quickjs', 'offline-yt-dlp-ejs-quickjs-fixture', quickjs)

        models = profile / 'Local/ChopLab Preview/models'
        if (models / prepare_separator_model.MODEL_FILE).exists():
            raise ValueError('Non-separation work unexpectedly created the lazy model cache')
        if any(app.rglob('*.onnx')):
            raise ValueError('Windows initial image still contains a bundled model')
        if args.drum_model or args.allow_drum_model_download:
            model = models / prepare_separator_model.MODEL_FILE
            if args.drum_model:
                if digest(args.drum_model) != prepare_separator_model.MODEL_SHA256:
                    raise ValueError('Supplied drum model differs from the pinned asset')
                models.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(args.drum_model, model)
            for iteration in range(2):
                offline = ['-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=9'] if iteration or args.drum_model else []
                # JVM flags precede the classpath; a fresh JVM proves cache reuse.
                command = java_arguments[:1] + offline + java_arguments[1:] + [
                    'com.choplab.desktop.next.NextSeparationProductionSelfTest', args.output / f'drums-{iteration}']
                scope = 'separation-source-production'
                def drums(command=command, iteration=iteration, offline=offline):
                    result = json_receipt(acceptance.command(f'drums-{iteration}', command, environment, app, timeout=600), scope)
                    if not model.is_file() or digest(model) != prepare_separator_model.MODEL_SHA256:
                        raise ValueError('Model was not published at the shared private cache with its pinned bytes')
                    result.update(modelSha256=digest(model), modelBytes=model.stat().st_size, bundled=False,
                                  freshJvm=iteration == 1, externalDownloadAllowed=not offline,
                                  cacheOrigin='verified-supplied-model' if args.drum_model else 'first-use-download')
                    return result
                acceptance.check(f'drums-{iteration}', scope, drums)
        else:
            acceptance.skip('drums', 'separation-source-production', 'No --drum-model or --allow-drum-model-download; inference/cache reuse unverified')
        if args.four_stem_model:
            scope = 'four-stem-production'
            acceptance.check('four-stem', scope, lambda: json_receipt(acceptance.command('four-stem',
                java_arguments + ['com.choplab.desktop.next.NextFourStemProductionSelfTest', args.four_stem_model],
                environment, app, timeout=600), scope))
        else:
            acceptance.skip('four-stem', 'four-stem-production', 'No --four-stem-model; drum-model results do not cover four stems')

        source = next((args.output / 'codecs/production').glob('next-self-test-*')) / 'profile'
        expected = snapshot(source)
        target = profile / 'Local/ChopLab Preview/next-v10'
        shutil.copytree(source, target)
        for iteration in range(2):
            def lifecycle(iteration=iteration):
                path = args.output / f'normal-close-{iteration}.json'
                acceptance.command(f'normal-close-{iteration}', [pwsh, '-NoProfile', '-NonInteractive', '-File',
                    ROOT / 'scripts/smoke-windows-app.ps1', '-AppImage', app, '-ExecutableName', 'ChopLab Preview.exe',
                    '-SilentAudio', '-ProfileRoot', profile, '-MetadataOutput', path], environment, app, timeout=120)
                result = read_json(path)
                if result['status'] != 'LOCAL_PASS' or not result['profile_retained'] or not result['all_owned_processes_exited_zero']:
                    raise ValueError('Normal close did not preserve the owned profile')
                verify_document(target, expected)
                if tree_manifest(stable) != old_data:
                    raise ValueError('Synthetic old stable profile changed')
                # The normal launcher preserves the selected production. The existing
                # production self-tests separately exercise loading it into the editor;
                # unchanged autosave bytes alone cannot prove its visible UI contents.
                result.update(preservedProjectAndAudioMatch=True, syntheticOldDataSentinelUnchanged=True,
                              visibleRestoredDocumentVerified=False)
                return result
            acceptance.check(f'normal-close-{iteration}', 'same-production-exe-reopen-and-normal-close', lifecycle)
        def unchanged():
            if tree_manifest(app) != payload or tree_manifest(stable) != old_data:
                raise ValueError('Candidate execution changed packaged bytes or the synthetic old stable profile')
            return {'packageBytesUnchanged': True, 'syntheticOldDataSentinelUnchanged': True}
        acceptance.check('unchanged-bytes', 'package-and-synthetic-old-stable-data-preservation', unchanged)
        acceptance.skip('installer', 'stable-user-install', 'Linked Preview is not installed; existing test-install-windows-app.ps1 covers stable installer separately')
        completed = True
    except Exception as failure:
        acceptance.receipt['failure'] = {'type': type(failure).__name__, 'reason': str(failure)}
        raise
    finally:
        recorded = {row['name'] for row in acceptance.receipt['checks']}
        pending = [(name, scope) for name, _, scope in SELF_TESTS] + [
            ('runtime', 'packaged-java-tools-and-pinned-derivations'), ('quickjs', 'offline-yt-dlp-ejs-quickjs-fixture'),
            ('drums', 'separation-source-production'), ('four-stem', 'four-stem-production'),
            ('normal-close-0', 'same-production-exe-reopen-and-normal-close'),
            ('normal-close-1', 'same-production-exe-reopen-and-normal-close')]
        for name, scope in pending:
            if name not in recorded and not (name == 'drums' and 'drums-0' in recorded):
                acceptance.skip(name, scope, 'An earlier required stage did not complete')
        acceptance.receipt['status'] = 'COMPLETED' if completed else 'FAILED'
        acceptance.receipt['finishedAtUtc'] = datetime.now(timezone.utc).isoformat()
        acceptance.receipt['syntheticOldDataSentinelUnchanged'] = tree_manifest(stable) == old_data
        acceptance.persist()
    print(json.dumps({'status': 'COMPLETED', 'checks': len(acceptance.receipt['checks']),
                      'receipt': str(args.output / 'acceptance.json'), 'allFeaturesVerified': False}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--source-revision', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True, help='A new owned run directory; retained for readback, never an existing profile')
    parser.add_argument('--pwsh', type=Path)
    parser.add_argument('--ejs-python', type=Path, default=Path(sys.executable), help='Isolated Python with yt-dlp[default]==2026.8.19')
    parser.add_argument('--drum-model', type=Path, help='Optional pinned existing model; copied, never modified')
    parser.add_argument('--allow-drum-model-download', action='store_true', help='Allow the production worker to fetch its pinned public model')
    parser.add_argument('--four-stem-model', type=Path, help='Separate optional pinned four-stem model; verified by its existing production self-test')
    args = parser.parse_args()
    for name in ('archive', 'output', 'ejs_python', 'pwsh', 'drum_model', 'four_stem_model'):
        value = getattr(args, name)
        if value is not None:
            setattr(args, name, value.resolve())
    verify(args)


if __name__ == '__main__':
    main()
