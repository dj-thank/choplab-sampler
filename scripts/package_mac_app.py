"""Build an isolated, relocatable Mac Preview; signing is explicit and fails closed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = 'java.base,java.desktop,java.instrument,java.logging,java.management,java.net.http,java.naming,jdk.httpserver,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.charsets'


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def build(java_home, tools, signed=False):
    identity = os.environ.get('CHOPLAB_MAC_SIGNING_IDENTITY', '')
    if signed:
        if not identity or not identity.startswith('Developer ID Application: '):
            raise RuntimeError('CHOPLAB_MAC_SIGNING_IDENTITY must name a Developer ID Application certificate')
        identities = subprocess.check_output(['security', 'find-identity', '-v', '-p', 'codesigning'], text=True)
        if f'"{identity}"' not in identities:
            raise RuntimeError('The requested valid signing identity is not available in the keychain')
    for name in ('ffmpeg', 'ffprobe', 'yt-dlp', 'node', 'manifest.json'):
        if not (tools / name).is_file():
            raise RuntimeError(f'Prepare the complete Mac tools bundle first: missing {name}')
    run('python3', ROOT / 'scripts/prepare_separator_model.py', '--out', ROOT / 'work/separator-models')
    version = re.search(r'^choplabVersion=(.+)$', (ROOT / 'gradle.properties').read_text(), re.M).group(1)
    build_number = re.search(r'^choplabBuildNumber=(\d+)$', (ROOT / 'gradle.properties').read_text(), re.M).group(1)
    build_root = ROOT / 'desktop/build'
    parent = build_root / ('mac-signed-preview-app-image' if signed else 'mac-preview-app-image')
    image_name = 'ChopLab Preview'
    destination = parent / f'{image_name}.app'
    if destination.exists():
        processes = subprocess.check_output(['ps', '-axo', 'comm='], text=True).splitlines()
        if any(value.strip().startswith(str(destination / 'Contents/MacOS') + '/') for value in processes):
            raise RuntimeError('Close this exact Preview before rebuilding it')
    with tempfile.TemporaryDirectory(prefix='mac-package-', dir=build_root) as temporary:
        stage = Path(temporary)
        run(java_home / 'bin/jpackage', '--type', 'app-image', '--name', image_name,
            '--input', build_root / 'install/desktop/lib', '--main-jar', f'desktop-{version}.jar',
            '--main-class', 'com.choplab.desktop.DesktopAppKt', '--dest', stage / 'image',
            '--add-modules', MODULES, '--jlink-options', '--strip-debug --no-header-files --no-man-pages --compress=2',
            '--app-version', build_number, '--vendor', 'ChopLab', '--description', 'Earth Song / おとひろい Preview',
            '--mac-package-identifier', 'com.choplab.sampler.preview', '--mac-package-name', 'おとひろい Preview',
            '--mac-app-category', 'music', '--java-options', '-Dchoplab.preview=true',
            '--java-options', '-Dfile.encoding=UTF-8', '--java-options', '-XX:-UsePerfData',
            '--java-options', '-Dchoplab.mediaTools=$APPDIR/tools',
            '--java-options', '-Dchoplab.separatorModels=$APPDIR/models',
            '--java-options', '-Dchoplab.systemAudioHelper=$APPDIR/choplab-sck-audio')
        app = stage / 'image' / f'{image_name}.app'
        application = app / 'Contents/app'
        shutil.copytree(tools, application / 'tools')
        shutil.copytree(ROOT / 'work/separator-models', application / 'models')
        for name in ('LICENSE', 'NOTICE.md'):
            shutil.copy2(ROOT / name, application / name)
        # jlink uses ../ links for repeated licenses. Materialize only in-bundle legal
        # documents so the archive retains the same strict no-parent-traversal policy.
        runtime = app / 'Contents/runtime'
        for path in (runtime / 'Contents' / 'Home' / 'legal').rglob('*'):
            if path.is_symlink():
                resolved = path.resolve()
                if not resolved.is_file() or not resolved.is_relative_to(runtime.resolve()):
                    raise RuntimeError('Runtime legal link escapes bundle')
                content = resolved.read_bytes()
                path.unlink()
                path.write_bytes(content)
        plist_path = app / 'Contents/Info.plist'
        with plist_path.open('rb') as stream:
            plist = plistlib.load(stream)
        plist['NSMicrophoneUsageDescription'] = '声や音を録音して、音源やボーカルとして制作に使います。'
        plist['CFBundleDisplayName'] = 'おとひろい Preview'
        plist['CFBundleShortVersionString'] = version
        plist['CFBundleVersion'] = build_number
        with plist_path.open('wb') as stream:
            plistlib.dump(plist, stream)
        if signed:
            # jpackage signs the populated bundle including the helper and runtime.
            run(java_home / 'bin/jpackage', '--type', 'app-image', '--app-image', app,
                '--mac-sign', '--mac-signing-key-user-name', identity.removeprefix('Developer ID Application: '))
            # Signing changes native bytes. Bind the tool manifest to the final signed
            # bytes, then reseal the outer app while preserving jpackage's entitlements.
            tool_manifest_path = application / 'tools/manifest.json'
            tool_manifest = json.loads(tool_manifest_path.read_text())
            for name, item in tool_manifest['files'].items():
                with (application / 'tools' / name).open('rb') as stream:
                    item['sha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
            tool_manifest_path.write_text(json.dumps(tool_manifest, indent=2) + '\n')
            run('codesign', '--force', '--sign', identity, '--timestamp',
                '--preserve-metadata=entitlements,requirements,flags', app)
            run('codesign', '--verify', '--deep', '--strict', app)
        else:
            # Explicit local Preview identity; this is never promoted to a signed release.
            run('codesign', '--force', '--sign', '-', runtime)
            run('codesign', '--force', '--sign', '-', app)
            run('codesign', '--verify', '--deep', '--strict', app)
        manifest = {
            'source': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'working_tree_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
            'version': version, 'profile': 'preview',
            'signing': 'developer-id' if signed else 'local-ad-hoc', 'notarized': False,
            'files': {},
        }
        for path in sorted(app.rglob('*')):
            if path.is_symlink():
                if not path.resolve().is_relative_to(app.resolve()):
                    raise RuntimeError('Bundle symlink escapes app')
                continue
            if path.is_file():
                with path.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                manifest['files'][str(path.relative_to(app))] = {'sha256': digest, 'bytes': path.stat().st_size}
        parent.mkdir(parents=True, exist_ok=True)
        # Generated app-only destination; never touches Application Support or installed apps.
        backup = stage / 'previous.app'
        if destination.exists():
            destination.rename(backup)
        try:
            app.rename(destination)
        except BaseException:
            if backup.exists():
                backup.rename(destination)
            raise
        (parent / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'app': str(destination), 'files': len(manifest['files']),
                      'bytes': sum(item['bytes'] for item in manifest['files'].values()),
                      'signing': manifest['signing'], 'notarized': False}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--signed', action='store_true')
    args = parser.parse_args()
    build(args.java_home.resolve(), args.tools.resolve(), args.signed)
