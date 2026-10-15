"""Stage a verified Mac NEXT Preview with its installer and matching manifest for ZIP/DMG delivery."""
import argparse
from pathlib import Path
import shlex
import shutil
import tempfile

try:
    from .install_mac_preview import copy_app, verify_package
except ImportError:
    from install_mac_preview import copy_app, verify_package


def stage_distribution(app, manifest_path, output):
    app, manifest_path, output = Path(app), Path(manifest_path), Path(output)
    # Refuse an unrelated output before copying. Only our temporary staging is removed.
    if output.exists() or output.is_symlink():
        raise RuntimeError('Choose a new distribution directory')
    manifest = verify_package(app, manifest_path)
    if (manifest.get('profile') != 'preview-next' or manifest.get('signing') != 'local-ad-hoc'
            or manifest.get('notarized') is not False):
        raise RuntimeError('Select the local, non-notarized NEXT Preview')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.mac-distribution-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'package'
        stage.mkdir()
        copy_app(app, stage / app.name)
        shutil.copy2(manifest_path, stage / 'manifest.json')
        verify_package(stage / app.name, stage / 'manifest.json')
        shutil.copy2(Path(__file__).with_name('install_mac_preview.py'), stage / 'install_mac_preview.py')
        command = stage / 'インストール.command'
        command.write_text(
            '#!/bin/bash\nset -eu\ncd -- "$(dirname -- "$0")"\n'
            'if ! command -v python3 >/dev/null 2>&1; then\n'
            '  printf "%s\\n" "Python 3 が必要です / Install Python 3 first: https://www.python.org/downloads/macos/"\n'
            '  exit 1\nfi\n'
            'exec python3 install_mac_preview.py --app ' + shlex.quote(app.name) +
            ' --manifest manifest.json "$@"\n', encoding='utf-8')
        command.chmod(0o755)
        minimum = manifest.get('minimum_system_version', 'manifest.json を確認 / see manifest.json')
        (stage / 'はじめに.txt').write_text(
            f'おとひろい NEXT / Earth Song NEXT — Mac Preview\n\n'
            f'Apple Silicon (M1 or later), macOS {minimum}+\n'
            'インストールには Python 3 が必要です（https://www.python.org/downloads/macos/）。\n'
            'Installation requires Python 3; running the installed app does not.\n\n'
            '1. 開いているChopLabの制作を保存し、アプリを終了します。\n'
            '   Save your work and quit the existing ChopLab app.\n'
            '2. このフォルダー内の「インストール.command」を開きます。\n'
            '   Open インストール.command in this folder.\n'
            '3. ホームフォルダーのApplicationsに入ったアプリを開きます。\n'
            '   Open the installed app in your home folder’s Applications directory.\n\n'
            'アプリとmanifest.jsonは組で保持してください。旧版はApplications/.choplab-backupsに残ります。\n'
            'Keep the app and manifest together. Previous versions remain in Applications/.choplab-backups.\n'
            '制作データや音源ライブラリは変更しません。 / Your projects and library are preserved.\n'
            'このPreviewはローカルad-hoc署名・未公証です。 / This Preview is locally signed and not notarized.\n'
            'macOSに拒否された場合は、配布元を確認してシステム設定の「プライバシーとセキュリティ」を確認してください。\n'
            'If macOS blocks opening, verify the source and review Privacy & Security in System Settings.\n'
            f'\nSource: {manifest["source"]}\n', encoding='utf-8')
        # No recursive replacement of a previously existing destination.
        stage.rename(output)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(stage_distribution(args.app, args.manifest, args.output))
