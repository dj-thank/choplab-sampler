"""Install a verified local Mac Preview app and its matching manifest as one recoverable update.

Only application bundles and installer-owned sidecars/backups are touched. Production
profiles, music libraries, running processes and system security settings are never changed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def verify_package(app, manifest_path):
    if app.is_symlink() or not app.is_dir() or manifest_path.is_symlink():
        raise RuntimeError('Select the original app and manifest from the mounted DMG')
    if not manifest_path.is_file() or manifest_path.stat().st_size > 10 * 1024 * 1024:
        raise RuntimeError('Missing or oversized package manifest')
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    names = {'preview': 'ChopLab Preview.app', 'preview-next': 'ChopLab NEXT.app'}
    if names.get(manifest.get('profile')) != app.name:
        raise RuntimeError('The app and manifest profiles do not match')
    if not re.fullmatch(r'[0-9a-f]{40}', manifest.get('source', '')) or manifest.get('working_tree_dirty') is not False:
        raise RuntimeError('The package must identify a clean source revision')
    actual = {str(p.relative_to(app)) for p in app.rglob('*') if p.is_file() and not p.is_symlink()}
    if actual != set(manifest['files']):
        raise RuntimeError('The app file list differs from its manifest')
    for name, expected in manifest['files'].items():
        path = app / name
        if path.stat().st_size != expected['bytes'] or digest(path) != expected['sha256']:
            raise RuntimeError('The app bytes differ from its manifest')
    subprocess.run(['/usr/bin/codesign', '--verify', '--deep', '--strict', str(app)],
                   check=True, capture_output=True, timeout=60)
    return manifest


def require_idle(app):
    if not app.exists():
        return
    check = subprocess.run(['/usr/sbin/lsof', '-t', '+D', str(app)],
                           capture_output=True, text=True, timeout=30)
    if check.returncode not in (0, 1) or check.stderr.strip():
        raise RuntimeError('Could not confirm the app is closed. Close it and try again.')
    if check.stdout.strip():
        raise RuntimeError('The app is running. Save your work and quit the app, then try again.')


def copy_app(source, target):
    subprocess.run(['/usr/bin/ditto', str(source), str(target)], check=True, capture_output=True, timeout=180)


def plain_directory(path):
    if path.is_symlink() or (path.exists() and not path.is_dir()):
        raise RuntimeError('The installation folder must be a regular directory')
    path.mkdir(parents=True, exist_ok=True)


def install_package(source, manifest_path, destination, *, verify=verify_package,
                    idle=require_idle, copy_bundle=copy_app, replace=os.replace):
    source = Path(source).absolute()
    manifest_path = Path(manifest_path).absolute()
    destination = Path(destination).expanduser().absolute()
    verify(source, manifest_path)
    target = destination / source.name
    if target.is_symlink() or (target.exists() and not target.is_dir()):
        raise RuntimeError('The existing application is not a regular bundle')
    if target.resolve() == source.resolve():
        raise RuntimeError('Select the app on the DMG, not the installed app')
    idle(target)
    plain_directory(destination)
    manifests = destination / '.choplab-manifests'
    backups = destination / '.choplab-backups'
    plain_directory(manifests)
    plain_directory(backups)
    installed_manifest = manifests / (source.name + '.json')
    if installed_manifest.is_symlink() or (installed_manifest.exists() and not installed_manifest.is_file()):
        raise RuntimeError('The installed manifest is not a regular file')
    lock = destination / '.choplab-install-lock'
    try:
        lock.mkdir()
    except FileExistsError:
        raise RuntimeError('Another installation is active. Keep its backups and finish it before retrying.') from None
    stage = None
    try:
        stage = Path(tempfile.mkdtemp(prefix='.choplab-stage-', dir=destination))
        copy_bundle(source, stage / source.name)
        shutil.copy2(manifest_path, stage / 'manifest.json')
        verify(stage / source.name, stage / 'manifest.json')
        # Recheck after the potentially long copy. Never terminate a user's session.
        idle(target)
        backup = backups / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ-') + uuid.uuid4().hex[:8])
        backup.mkdir()
        had_app = target.exists()
        had_manifest = installed_manifest.exists()
        if had_manifest:
            shutil.copy2(installed_manifest, backup / 'manifest.json')
        moved_old = False
        installed_new = False
        try:
            if had_app:
                replace(target, backup / source.name)
                moved_old = True
            replace(stage / source.name, target)
            installed_new = True
            replace(stage / 'manifest.json', installed_manifest)
            verified = verify(target, installed_manifest)
        except BaseException:
            # Both halves go back, even if publishing or verifying the new manifest failed.
            if installed_new and target.exists():
                os.replace(target, backup / 'failed-candidate.app')
            if moved_old:
                os.replace(backup / source.name, target)
            if had_manifest:
                shutil.copy2(backup / 'manifest.json', stage / 'restore-manifest.json')
                os.replace(stage / 'restore-manifest.json', installed_manifest)
            elif installed_manifest.exists():
                installed_manifest.unlink()
            raise
        receipt = {'application': target.name, 'source': verified['source'], 'profile': verified['profile'],
                   'backup': str(backup), 'previousApplicationPreserved': had_app,
                   'previousManifestPreserved': had_manifest, 'files': len(verified['files'])}
        # The receipt is advisory; the exact manifest beside the installed app remains authoritative.
        try:
            (backup / 'installation.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        except OSError:
            pass
        return receipt
    finally:
        if stage is not None:
            shutil.rmtree(stage, ignore_errors=True)
        lock.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--destination', type=Path, default=Path.home() / 'Applications')
    args = parser.parse_args()
    try:
        receipt = install_package(args.app, args.manifest, args.destination)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        print('インストールできませんでした / Installation could not finish: ' + str(error))
        print('開いているアプリを保存して終了し、空き容量とアクセス権を確認して再試行してください。')
        print('既存の制作データは変更していません。 / Existing project data was not changed.')
        return 1
    print('インストール完了 / Installed: ' + str(args.destination / receipt['application']))
    print('旧アプリと検証情報の保存先 / Previous app and manifest: ' + receipt['backup'])
    print('Applications内のアプリを開いてください。 / Open the app in Applications.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
