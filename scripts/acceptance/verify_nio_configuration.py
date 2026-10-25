#!/usr/bin/env python3
"""Rebuild all 29 NIO conversion helpers; generated configuration/lint are separate."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_classes(manifest, binary, directory):
    expected = {row['path']: row for row in manifest['classes']}
    if len(expected) != 29 or len(manifest['classes']) != 29:
        raise ValueError('Unexpected class count or duplicate')
    rebuilt = {path.relative_to(directory).as_posix(): path for path in directory.rglob('*.class')}
    if set(expected) != set(rebuilt):
        raise ValueError('Rebuilt class inventory differs')
    with zipfile.ZipFile(binary) as jar:
        if {name for name in jar.namelist() if name.endswith('.class')} != set(expected):
            raise ValueError('Published class inventory differs')
        for name, row in expected.items():
            if sha(jar.read(name)) != row['sha256'] or rebuilt[name].read_bytes() != jar.read(name):
                raise ValueError('Class bytes differ: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--javac8', required=True)
    parser.add_argument('--javac', required=True)
    parser.add_argument('--java', required=True)
    args = parser.parse_args()
    work = args.work.resolve()
    if work.exists():
        raise ValueError('Use a new isolated work directory')
    work.mkdir(parents=True)
    repo = Path(__file__).resolve().parents[2]
    manifest_path = repo / 'config/nio-configuration-source-class-map-20261025.json'
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if manifest['version'] != 1 or manifest['classCount'] != 29 or manifest['compileInputCount'] != 67:
        raise ValueError('Unexpected manifest version/count')

    def fetch(url, name, expected):
        data = urllib.request.urlopen(url, timeout=120).read(16 * 1024 * 1024 + 1)
        if len(data) > 16 * 1024 * 1024 or (expected is not None and sha(data) != expected):
            raise ValueError('Downloaded hash mismatch: ' + name)
        path = work / name
        path.write_bytes(data)
        return path

    # Gitiles changes the tar/gzip envelope across requests. Pin every extracted byte instead.
    source = fetch(manifest['source']['url'], 'source.tar.gz', None)
    binary = fetch(manifest['binary']['url'], 'published.jar', manifest['binary']['sha256'])
    asm = fetch('https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.9.1/asm-9.9.1.jar',
                'asm.jar', '6f3828a215c920059a5efa2fb55c233d6c54ec5cadca99ce1b1bdd10077c7ddd')
    source_root = work / 'source'
    source_files = {}
    with tarfile.open(source) as archive:
        for member in archive.getmembers():
            target = source_root / member.name
            if not target.resolve().is_relative_to(source_root.resolve()) or member.issym() or member.islnk():
                raise ValueError('Unexpected source archive entry')
            if member.isfile():
                if member.name in source_files or member.size > 1024 * 1024 or len(source_files) >= 128:
                    raise ValueError('Unexpected source archive size or duplicate')
                target.parent.mkdir(parents=True, exist_ok=True)
                data = archive.extractfile(member).read()
                source_files[member.name] = sha(data)
                target.write_bytes(data)
            elif not member.isdir():
                raise ValueError('Unexpected source archive type')
    content_digest = sha(json.dumps(source_files, sort_keys=True, separators=(',', ':')).encode('utf-8'))
    if len(source_files) != manifest['source']['fileCount'] or content_digest != manifest['source']['contentDigest']:
        raise ValueError('Source content inventory differs')
    inputs = []
    for row in manifest['inputs']:
        path = source_root / row['path'].removeprefix('src/library_desugar/')
        if sha(path.read_bytes()) != row['sha256']:
            raise ValueError('Source hash mismatch: ' + row['path'])
        if 'BSD-style license' not in path.read_text(encoding='utf-8')[:500]:
            raise ValueError('Unreviewed source header')
        inputs.append(path)
    actual_inputs = set((source_root / 'java').rglob('*.java'))
    if actual_inputs != set(inputs):
        raise ValueError('Source input inventory changed')
    classes = work / 'classes'
    classes.mkdir()
    argfile = work / 'javac8.args'
    argfile.write_text('\n'.join('"' + str(value).replace('\\', '/') + '"' for value in
                      ['-source', '8', '-target', '8', '-encoding', 'UTF-8', '-d', classes, *inputs]), encoding='utf-8')
    with (work / 'build.log').open('w', encoding='utf-8') as log:
        subprocess.run([args.javac8, '-version'], stdout=log, stderr=log, check=True)
        subprocess.run([args.javac8, '@' + str(argfile)], stdout=log, stderr=log, check=True)
        adapter = work / 'adapter'
        adapter.mkdir()
        subprocess.run([args.javac, '-encoding', 'UTF-8', '-cp', str(asm), '-d', str(adapter),
                        str(repo / 'scripts/acceptance/NioConfigurationConversions.java')], stdout=log, stderr=log, check=True)
        import os
        subprocess.run([args.java, '-cp', str(adapter) + os.pathsep + str(asm), 'NioConfigurationConversions',
                        str(classes), str(work / 'rewritten')], stdout=log, stderr=log, check=True)
    verify_classes(manifest, binary, work / 'rewritten')
    receipt = {'helperClassBytesReproduced': 29, 'compileInputs': len(inputs),
               'manifestSha256': sha(manifest_path.read_bytes()),
               'sourceSha256': sha(source.read_bytes()), 'sourceContentDigest': content_digest,
               'binarySha256': sha(binary.read_bytes()),
               'converterSha256': sha((repo / 'scripts/acceptance/NioConfigurationConversions.java').read_bytes()),
               'generatedConfigurationVerified': False, 'generatedLintVerified': False,
               'wholeJarIdentical': False, 'sourcePublicationVerified': False}
    (work / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
