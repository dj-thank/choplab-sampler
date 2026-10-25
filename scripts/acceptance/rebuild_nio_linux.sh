#!/usr/bin/env bash
# Candidate toolchain experiment; NOT a claim about the publisher's original toolchain.
# Usage: bash rebuild-linux.sh /absolute/new-run-dir [local-source.zip] [local-nio.jar]
# Requires Linux x86_64, python3, curl, unzip, zip, timeout and a C/C++ toolchain.
# Network downloads and build/cache outputs are confined to new-run-dir.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
run="${1:?provide a new absolute private run directory}"
[[ "$run" == /* && ! -e "$run" ]] || { echo 'run directory must be absolute and new' >&2; exit 2; }
for tool in python3 curl unzip zip timeout gcc; do command -v "$tool" >/dev/null; done
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]]
mkdir -p "$run"/{downloads,home,tmp,sdk,cache,receipts}
export TMPDIR="$run/tmp" XDG_CACHE_HOME="$run/cache" COURSIER_CACHE="$run/cache/coursier"
export ANDROID_HOME="$run/sdk" ANDROID_SDK_ROOT="$run/sdk"
export PYTHONUTF8=1 TZ=UTC
exec > >(tee "$run/receipts/run.log") 2>&1
date -u +%FT%TZ
git -C "$here/../.." rev-parse HEAD > "$run/receipts/harness-revision.txt"
uname -a
python3 --version
gcc --version
export RUN="$run" SOURCE_INPUT="${2:-}" BINARY_INPUT="${3:-}"
python3 - <<'PY'
import hashlib, json, os, pathlib, shutil, urllib.request, zipfile
import xml.etree.ElementTree as ET
r=pathlib.Path(os.environ['RUN']); d=r/'downloads'
def fetch(url, dest):
    with urllib.request.urlopen(url, timeout=120) as response, open(dest,'wb') as out: shutil.copyfileobj(response,out)
def pinned(local, url, name, digest):
    dest=d/name
    if local: shutil.copyfile(local,dest)
    else: fetch(url,dest)
    assert hashlib.sha256(dest.read_bytes()).hexdigest()==digest, name
    return dest
source=pinned(os.environ['SOURCE_INPUT'],'https://codeload.github.com/google/desugar_jdk_libs/zip/73170c345e6a762fc6a1f0301bb15218850023ef','source.zip','cf0b48cb046b6517ed8e2a816c125116a907e13e48e341882ba6f4016a155d9f')
pinned(os.environ['BINARY_INPUT'],'https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs_nio/2.1.5/desugar_jdk_libs_nio-2.1.5.jar','published.jar','d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976')
with zipfile.ZipFile(source) as z: z.extractall(r/'source')
# Bazel is a candidate version, because this source tree does not pin one.
burl='https://github.com/bazelbuild/bazel/releases/download/6.5.0/bazel-6.5.0-linux-x86_64'
fetch(burl,d/'bazel'); fetch(burl+'.sha256',d/'bazel.sha256')
assert hashlib.sha256((d/'bazel').read_bytes()).hexdigest()==(d/'bazel.sha256').read_text().split()[0]
(d/'bazel').chmod(0o755)
# Save the exact SDK repository metadata and verify every archive against it.
fetch('https://dl.google.com/android/repository/repository2-1.xml',d/'repository.xml')
tree=ET.parse(d/'repository.xml'); packages=[]
for path,destination in [('platforms;android-35','platforms/android-35'),('build-tools;30.0.3','build-tools/30.0.3'),('platform-tools','platform-tools')]:
    pkg=next(x for x in tree.getroot().iter() if x.tag.endswith('remotePackage') and x.attrib.get('path')==path)
    archives=pkg.find('archives').findall('archive')
    archive=next(x for x in archives if x.findtext('host-os') in (None,'linux'))
    complete=archive.find('complete'); url=complete.findtext('url'); expected=complete.findtext('checksum')
    digest_type=complete.find('checksum').attrib.get('type','sha1'); target=d/path.replace(';','-')
    fetch('https://dl.google.com/android/repository/'+url,target)
    assert hashlib.new(digest_type,target.read_bytes()).hexdigest()==expected
    dest=r/'sdk'/destination; dest.mkdir(parents=True)
    with zipfile.ZipFile(target) as z:
        for info in z.infolist():
            parts=pathlib.PurePosixPath(info.filename).parts[1:]
            if not parts: continue
            assert '..' not in parts
            output=dest.joinpath(*parts)
            if info.is_dir(): output.mkdir(parents=True,exist_ok=True)
            else:
                output.parent.mkdir(parents=True,exist_ok=True); output.write_bytes(z.read(info))
                mode=info.external_attr>>16
                if mode: output.chmod(mode & 0o777)
    packages.append({'package':path,'url':url,'checksumType':digest_type,'checksum':expected})
(r/'receipts/sdk-packages.json').write_text(json.dumps(packages,indent=2)+'\n')
PY
src="$run/source/desugar_jdk_libs-73170c345e6a762fc6a1f0301bb15218850023ef"
cd "$src"
bazel=("$run/downloads/bazel" --batch "--output_user_root=$run/cache/bazel" --nosystem_rc --nohome_rc)
opts=(--repository_cache="$run/cache/repository" --python_path="$(command -v python3)" --java_language_version=11 --tool_java_language_version=11 --java_runtime_version=remotejdk_11 --tool_java_runtime_version=remotejdk_11 --jobs=4)
"${bazel[@]}" version > "$run/receipts/bazel-version.txt"
set +e
timeout --signal=TERM --kill-after=30s 30m "${bazel[@]}" build "${opts[@]}" //:maven_release_jdk11_nio --build_event_json_file="$run/receipts/build-events.json"
status=$?
set -e
printf '%s\n' "$status" > "$run/receipts/build-exit-code.txt"
[[ $status == 0 ]] || exit "$status"
"${bazel[@]}" aquery "${opts[@]}" 'deps(//:maven_release_jdk11_nio)' --output=jsonproto > "$run/receipts/actions.json"
cp bazel-bin/desugar_jdk_libs_jdk11_nio.zip "$run/receipts/"
cp bazel-bin/jdk11/src/{libjava_base_all.jar,d8_java_base_selected.jar,d8_java_base_selected_with_addon.jar} "$run/receipts/"
cp bazel-bin/jdk11/src/libcore/liblibcore_addon.jar "$run/receipts/"
cp bazel-bin/jdk11/src/addon/addon.jar "$run/receipts/"
python3 "$here/verify_nio_sources.py" --source "$run/downloads/source.zip" --binary "$run/downloads/published.jar" --rebuilt "$run/receipts/d8_java_base_selected_with_addon.jar" --output "$run/receipts/comparison"
date -u +%FT%TZ
