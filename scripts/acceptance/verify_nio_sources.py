"""Pinned NIO static provenance map; optional rebuilt-JAR comparison. No legal clearance."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

SOURCE_SHA = 'cf0b48cb046b6517ed8e2a816c125116a907e13e48e341882ba6f4016a155d9f'
BINARY_SHA = 'd8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976'
REVISION = '73170c345e6a762fc6a1f0301bb15218850023ef'
TOOL = 'tools/java/com/google/devtools/build/android/'

def sha(data):
    return hashlib.sha256(data).hexdigest()

def source_attribute(data):
    pos = 8
    def read(fmt):
        nonlocal pos
        value = struct.unpack_from(fmt, data, pos)[0]
        pos += struct.calcsize(fmt)
        return value
    cp = {}; count = read('>H'); i = 1
    while i < count:
        tag = read('>B')
        if tag == 1:
            size = read('>H'); cp[i] = data[pos:pos+size].decode('utf-8', errors='replace'); pos += size
        elif tag in (3, 4): pos += 4
        elif tag in (5, 6): pos += 8; i += 1
        elif tag in (7, 8, 16, 19, 20): pos += 2
        elif tag in (9, 10, 11, 12, 17, 18): pos += 4
        elif tag == 15: pos += 3
        else: raise ValueError(tag)
        i += 1
    pos += 6
    interfaces = read('>H'); pos += 2 * interfaces
    for _ in range(2):
        for _ in range(read('>H')):
            pos += 6
            for _ in range(read('>H')):
                read('>H'); length = read('>I'); pos += length
    for _ in range(read('>H')):
        name = cp[read('>H')]; length = read('>I')
        if name == 'SourceFile': return cp[read('>H')]
        pos += length
    return None

def compare(left, right):
    with zipfile.ZipFile(left) as a, zipfile.ZipFile(right) as b:
        ac = {n: sha(a.read(n)) for n in a.namelist() if n.endswith('.class')}
        bc = {n: sha(b.read(n)) for n in b.namelist() if n.endswith('.class')}
        return {'wholeJarIdentical': sha(Path(left).read_bytes()) == sha(Path(right).read_bytes()),
                'publishedClasses': len(ac), 'rebuiltClasses': len(bc),
                'missingClasses': sorted(ac.keys()-bc.keys()), 'extraClasses': sorted(bc.keys()-ac.keys()),
                'differentClassBytes': sorted(n for n in ac.keys() & bc.keys() if ac[n] != bc[n]),
                'sameClassBytes': sum(ac[n] == bc[n] for n in ac.keys() & bc.keys())}

def validate_manifest(manifest, rows, binary):
    if (type(manifest.get('schemaVersion')) is not int or manifest['schemaVersion'] != 1 or
        manifest.get('sourceRevision') != REVISION or manifest.get('sourceArchiveSha256') != SOURCE_SHA or
        manifest.get('publishedJarSha256') != BINARY_SHA or manifest.get('sourcePublicationVerified') is not False):
        raise ValueError('Manifest provenance or publication boundary differs')
    if manifest.get('sourceColumns') != ['path','sha256','headerLicense'] or manifest.get('classColumns') != ['path','sha256','sourceIndex','generated']:
        raise ValueError('Unknown manifest columns')
    sources = manifest.get('sources', [])
    expected = {r['source']: [r['sourceSha256'], 'Apache-2.0' if r['sourceHeaderClassification'] == 'Apache-2.0-header'
                            else 'GPL-2.0-only WITH Classpath-exception-2.0'] for r in rows}
    if (any(not isinstance(s, list) or len(s) != 3 for s in sources) or
        len(sources) != len(expected) or {s[0]: s[1:] for s in sources} != expected):
        raise ValueError('Manifest source header map differs')
    classes = manifest.get('classes', [])
    if (len(classes) != len(rows) or manifest.get('classCount') != len(rows) or manifest.get('sourceFileCount') != len(sources) or
        any(not isinstance(c, list) or len(c) != 4 or type(c[2]) is not int or not 0 <= c[2] < len(sources) or type(c[3]) is not bool for c in classes)):
        raise ValueError('Manifest class map is incomplete')
    actual = {c[0]: [c[1], sources[c[2]][0], c[3]] for c in classes}
    wanted = {r['class']: [sha(binary.read(r['class'])), r['source'], r['mappingKind'] == 'static-generator-origin'] for r in rows}
    if len(actual) != len(classes) or actual != wanted:
        raise ValueError('Manifest class/source/hash binding differs')

def main():
    p = argparse.ArgumentParser(); p.add_argument('--source', type=Path, required=True)
    p.add_argument('--binary', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--rebuilt', type=Path); p.add_argument('--manifest', type=Path)
    a = p.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
    assert sha(a.source.read_bytes()) == SOURCE_SHA, 'source hash mismatch'
    assert sha(a.binary.read_bytes()) == BINARY_SHA, 'binary hash mismatch'
    with zipfile.ZipFile(a.source) as z, zipfile.ZipFile(a.binary) as binary:
        prefix = f'desugar_jdk_libs-{REVISION}/'
        files = {n[len(prefix):]: z.read(n) for n in z.namelist() if n.startswith(prefix) and not n.endswith('/')}
        selector = files[TOOL+'JarFileClassEntrySelector.java'].decode()
        block = selector.split('D8_ANDROID_JDK11_LIB_TOP_LEVEL_TYPE_PATTERNS = {', 1)[1].split('};', 1)[0]
        patterns = re.findall(r'"([^"]+)"', block)
        def selected(name):
            return any(name.startswith(x[:-1]) if x.endswith('*') else name == x+'.class' or name.startswith(x+'$') for x in patterns)
        rows = []
        for name in sorted(n for n in binary.namelist() if n.endswith('.class')):
            attr = source_attribute(binary.read(name)); generated = attr is None
            package, simple = name.rsplit('/', 1)
            if generated:
                assert simple.startswith('Desugar'), name
                attr = simple.removeprefix('Desugar').removesuffix('.class')+'.java'
            suffix = package+'/'+attr
            # BUILD merges selected java.base, then ONLY libcore_addon, then addon.
            if name.startswith('libcore/'):
                path = 'jdk11/src/libcore/luni/src/main/java/'+suffix
                assert '/libcore/api/' in path or path.endswith(('/MimeMap.java','/NonNull.java','/Nullable.java'))
                origin = 'libcore_addon'
            elif name.startswith('desugar/'):
                path = 'jdk11/src/addon/java/'+suffix; origin = 'addon'
            else:
                path = 'jdk11/src/java.base/share/classes/'+suffix; origin = 'java_base_selected'
                assert selected(name), 'not in selector patterns: '+name
            assert path in files, 'source not found: '+path
            content = files[path]; text = content.decode('utf-8'); header = text.split('package ', 1)[0]
            license_class = ('GPL-2.0-with-Classpath-header' if 'GNU General Public License' in header and 'Classpath' in header
                             else 'Apache-2.0-header' if 'Licensed under the Apache License, Version 2.0' in header else 'UNCLASSIFIED')
            assert license_class != 'UNCLASSIFIED', path
            row = {'class': name, 'source': path, 'sourceSha256': sha(content), 'sourceHeaderClassification': license_class,
                   'buildOrigin': origin, 'mappingKind': 'static-generator-origin' if generated else 'SourceFile-plus-BUILD-selection',
                   'bytecodeProvenanceVerified': False}
            if generated:
                assert 'DesugarSupportedApi' in text, path
                row['generatorSources'] = [TOOL+x for x in ['JarFileClassEntrySelector.java','PreScanner.java','DesugarSupportedApiClassGenerator.java','AsmHelpers.java']]
            rows.append(row)
        if a.manifest:
            if a.manifest.stat().st_size > 512 * 1024: raise ValueError('Manifest too large')
            validate_manifest(json.loads(a.manifest.read_text(encoding='utf-8')), rows, binary)
        report = {'sourceRevision': REVISION, 'sourceSha256': SOURCE_SHA, 'binarySha256': BINARY_SHA,
                  'scope': 'Static source-selection/header map; generated mapping is origin inference, not rebuilt bytecode proof or distribution clearance.',
                  'classes': len(rows), 'origins': dict(Counter(r['buildOrigin'] for r in rows)),
                  'headerClasses': dict(Counter(r['sourceHeaderClassification'] for r in rows)),
                  'mappingKinds': dict(Counter(r['mappingKind'] for r in rows)), 'rows': rows}
        report['committedManifestVerified'] = bool(a.manifest)
        if a.rebuilt:
            comparison = compare(a.binary, a.rebuilt)
            report['rebuildComparison'] = comparison
            different = set(comparison['differentClassBytes']) | set(comparison['missingClasses'])
            for row in rows:
                row['bytecodeProvenanceVerified'] = row['class'] not in different
            report['scope'] = 'Source-selection/header map with supplied rebuilt class-byte comparison; not distribution clearance.'
            report['allClassBytesReproduced'] = not (different or comparison['extraClasses'])
        (a.output/'mapping.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        (a.output/'summary.json').write_text(json.dumps({k:v for k,v in report.items() if k != 'rows'}, indent=2)+'\n', encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k != 'rows'}, indent=2))

if __name__ == '__main__': main()
