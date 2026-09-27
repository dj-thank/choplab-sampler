"""Prepare a private macOS tool bundle, relocating Homebrew dylibs.

Source versions/licenses are recorded; this is not a public redistribution approval.
No installed binary is modified. yt-dlp is an upstream standalone, hash-pinned build.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import urllib.request

YTDLP_VERSION = '2026.08.19'
YTDLP_SHA256 = '0f192b7ec147ab6288885d6351d9ab67367640029b4377576ef46dd79cf7b202'
YTDLP_URL = f'https://github.com/yt-dlp/yt-dlp/releases/download/{YTDLP_VERSION}/yt-dlp_macos'
ALLOWED_FILES = frozenset((Path(__file__).resolve().parents[1] / 'config/mac-media-tool-files.txt').read_text().splitlines())


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True).strip()


def dependencies(path):
    return [line.strip().split(' (compatibility version', 1)[0]
            for line in run('otool', '-L', path).splitlines()[1:]]


def locate(name):
    candidates = [shutil.which(name), f'/opt/homebrew/bin/{name}', f'/usr/local/bin/{name}']
    if name == 'node':
        candidates += ['/opt/homebrew/opt/node@24/bin/node', '/usr/local/opt/node@24/bin/node']
    for value in candidates:
        if value and Path(value).is_file():
            return Path(value).resolve()
    raise RuntimeError(f'{name} is required; install it before preparing the Mac bundle')


def prepare(out):
    if platform.system() != 'Darwin':
        raise RuntimeError('Mac tools must be prepared on macOS')
    out.mkdir(parents=True, exist_ok=True)
    # A valid generated bundle is reusable; never mutate an installed or partial bundle.
    if any(out.iterdir()):
        manifest = out / 'manifest.json'
        if manifest.is_file():
            data = json.loads(manifest.read_text())
            files = data.get('files', {})
            if (data.get('platform') == 'macos-' + platform.machine()
                    and data.get('versions', {}).get('yt-dlp') == YTDLP_VERSION
                    and set(files) == {p.name for p in out.iterdir()} - {'manifest.json'}
                    and set(files) == ALLOWED_FILES
                    and all((out / name).is_file() and sha(out / name) == item['sha256']
                            for name, item in files.items())):
                print('Existing Mac tool bundle verified')
                return
        raise RuntimeError('Use an empty staging directory or a complete hash-verified Mac tool bundle')
    copied = {}
    origins = {}
    edges = {}

    def add(source, executable_directory=None):
        source = source.resolve()
        executable_directory = executable_directory or source.parent
        name = source.name
        if name in origins:
            if origins[name] != source and sha(origins[name]) != sha(source):
                raise RuntimeError(f'Different libraries share a name: {name}')
            return name
        origins[name] = source
        destination = out / name
        shutil.copy2(source, destination)
        destination.chmod(0o755)
        copied[name] = {'sha256_before_relocation': sha(source)}
        edges[name] = []
        for dep in dependencies(source):
            if dep.startswith(('/System/Library/', '/usr/lib/')):
                continue
            if dep.startswith('@loader_path/'):
                resolved = source.parent / dep.removeprefix('@loader_path/')
            elif dep.startswith('/'):
                resolved = Path(dep)
            elif dep.startswith('@rpath/'):
                paths = re.findall(r'cmd LC_RPATH\s+cmdsize \d+\s+path (.*?) \(offset', run('otool', '-l', source))
                candidates = [Path(value.replace('@loader_path', str(source.parent))
                                   .replace('@executable_path', str(executable_directory))) / dep.removeprefix('@rpath/')
                              for value in paths]
                resolved = next((value for value in candidates if value.is_file()), None)
                if resolved is None:
                    raise RuntimeError(f'Unresolved rpath dependency: {name}: {dep}')
            else:
                raise RuntimeError(f'Unresolved runtime dependency: {name}: {dep}')
            if resolved.resolve() == source:
                continue
            edges[name].append((dep, resolved.resolve().name))
            add(resolved, executable_directory)
        return name

    versions = {}
    for name, flag in [('ffmpeg', '-version'), ('ffprobe', '-version'), ('node', '--version')]:
        source = locate(name)
        versions[name] = run(source, flag).splitlines()[0]
        add(source)
    for name in sorted(copied):
        destination = out / name
        if name.endswith('.dylib'):
            run('install_name_tool', '-id', '@loader_path/' + name, destination)
        for old, new in edges[name]:
            run('install_name_tool', '-change', old, '@loader_path/' + new, destination)
        # Relocation invalidates the original signature. Explicit local/ad-hoc signing only.
        run('codesign', '--force', '--sign', '-', destination)
        unresolved = [dep for dep in dependencies(destination)
                      if not dep.startswith(('/System/Library/', '/usr/lib/', '@loader_path/'))]
        if unresolved:
            raise RuntimeError(f'Nonportable dependency left in {name}')
        copied[name]['sha256'] = sha(destination)
    target = out / 'yt-dlp'
    with urllib.request.urlopen(YTDLP_URL, timeout=90) as response, target.open('wb') as stream:
        shutil.copyfileobj(response, stream)
    if sha(target) != YTDLP_SHA256:
        target.unlink()
        raise RuntimeError('yt-dlp checksum mismatch')
    target.chmod(0o755)
    versions['yt-dlp'] = run(target, '--version')
    copied['yt-dlp'] = {'sha256': YTDLP_SHA256, 'source': YTDLP_URL}
    if set(copied) != ALLOWED_FILES:
        raise RuntimeError('Native dependency set changed; review config/mac-media-tool-files.txt and license/source obligations')
    # Execute with no Homebrew PATH; loader references must be self-contained.
    environment = dict(os.environ, PATH='/usr/bin:/bin')
    for name, flag in [('ffmpeg', '-version'), ('ffprobe', '-version'), ('node', '--version'), ('yt-dlp', '--version')]:
        subprocess.run([str(out / name), flag], env=environment, check=True, stdout=subprocess.DEVNULL)
    (out / 'manifest.json').write_text(json.dumps({
        'platform': 'macos-' + platform.machine(), 'versions': versions,
        'files': copied, 'distribution': 'local-acceptance-only',
        'license_review': 'NOTICE.md; corresponding third-party source and licenses required before public release',
    }, indent=2) + '\n')
    print(json.dumps({'tools': versions, 'native_files': len(copied)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    prepare(parser.parse_args().out.resolve())
