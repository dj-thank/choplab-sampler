#!/usr/bin/env python3
"""Remove one conflicting link-time alias from the pinned arm64 Python runtime.

All retained nested entries, Python/codec ELF bytes, Java, resources and other
ABIs remain identical. This derivation is paired with the audio FFmpeg candidate,
whose dependency closure does not contain the same unversioned alias.
"""
from __future__ import annotations

import argparse
import copy
import io
import json
from pathlib import Path
import platform
import re
import stat
import subprocess
import tempfile
import zipfile

if __package__:
    from .build_android_audio_runtime import PROFILE, ROOT, fetch, sha, write_zip
else:
    from build_android_audio_runtime import PROFILE, ROOT, fetch, sha, write_zip

MEMBER = "jni/arm64-v8a/libpython.zip.so"
ALIAS = "usr/lib/liblzma.so"
TARGET = "liblzma.so.5.8.1"


def remove_link_alias(content: bytes) -> tuple[bytes, dict]:
    with zipfile.ZipFile(io.BytesIO(content)) as source:
        infos = source.infolist()
        if len(infos) > 2048 or len({i.filename for i in infos}) != len(infos) or sum(i.file_size for i in infos) > 64 * 1024 * 1024:
            raise ValueError("Unexpected Python runtime ZIP bounds or duplicate member")
        alias = source.getinfo(ALIAS)
        if not stat.S_ISLNK(alias.external_attr >> 16) or source.read(alias) != TARGET.encode():
            raise ValueError("Only the pinned unversioned liblzma link may be removed")
        versioned = source.getinfo("usr/lib/liblzma.so.5")
        if not stat.S_ISLNK(versioned.external_attr >> 16) or source.read(versioned) != TARGET.encode():
            raise ValueError("The runtime SONAME link must retain its original target")
        if not source.read("usr/lib/" + TARGET).startswith(b"\x7fELF"):
            raise ValueError("Missing original versioned liblzma ELF")
        output = io.BytesIO()
        entries = []
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as derived:
            for info in sorted(infos, key=lambda item: item.filename):
                if info.filename == ALIAS:
                    continue
                data = source.read(info)
                entry = copy.copy(info)
                entry.date_time = (1980, 1, 1, 0, 0, 0)
                entry.compress_type = zipfile.ZIP_STORED
                derived.writestr(entry, data)
                entries.append({"path": info.filename, "bytes": len(data), "sha256": sha(data), "unixMode": info.external_attr >> 16})
    data = output.getvalue()
    if len(data) == len(content):
        raise ValueError("Derived ZIP must change the upstream size-based cache version")
    manifest = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return data, {"removed": {"path": ALIAS, "target": TARGET, "bytes": alias.file_size, "sha256": sha(TARGET.encode())},
                  "preservedEntryCount": len(entries), "preservedEntriesSha256": sha(manifest),
                  "cacheVersionBefore": len(content), "cacheVersionAfter": len(data),
                  "compression": "stored; APK compression handles the unchanged retained bytes"}


def dependency_audit(archives: dict[str, bytes], readelf: Path, work: Path) -> list[dict]:
    result = []
    with tempfile.TemporaryDirectory(prefix="elf-audit-", dir=work) as temporary:
        path = Path(temporary) / "input.elf"
        for package, content in sorted(archives.items()):
            users = []
            count = 0
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                for name in archive.namelist():
                    data = archive.read(name)
                    if not data.startswith(b"\x7fELF"):
                        continue
                    count += 1
                    path.write_bytes(data)
                    dynamic = subprocess.check_output([readelf, "--dynamic", path], text=True, timeout=30)
                    needed = re.findall(r"Shared library: \[([^]]+)\]", dynamic)
                    if "liblzma.so" in needed:
                        raise ValueError("An upstream ELF requires the conflicting unversioned alias: " + name)
                    if "liblzma.so.5" in needed:
                        users.append({"path": name, "sha256": sha(data), "needed": "liblzma.so.5"})
            result.append({"package": package, "elfCount": count, "unversionedLzmaRequests": 0, "versionedLzmaUsers": users})
    return result


def prepare(python_aar: Path, ffmpeg_aar: Path, ndk: Path, work: Path) -> dict:
    profile = json.loads(PROFILE.read_text())
    pins = json.loads((ROOT / "scripts/android_runtime_pins.json").read_text())
    python_pin = next(item for item in pins["sources"] if item["coordinate"].endswith(":library:0.18.1"))
    if sha(python_aar.read_bytes()) != python_pin["sha256"] or sha(ffmpeg_aar.read_bytes()) != profile["upstream"]["sha256"]:
        raise ValueError("Only the repository-pinned upstream AARs may be derived")
    if profile["pythonLinkage"]["upstream"] != python_pin:
        raise ValueError("Python source identity differs from the original runtime pin")
    if not re.search(r"Pkg.Revision\s*=\s*" + re.escape(profile["ndk"]["revision"]) + r"\s*$",
                     (ndk / "source.properties").read_text(), re.M):
        raise ValueError("Use the pinned NDK for the dependency audit")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if host is None:
        raise ValueError("Prepare on macOS/Linux; actual Android execution is a separate gate")
    readelf = ndk / "toolchains/llvm/prebuilt" / host / "bin/llvm-readelf"
    work.mkdir(parents=True, exist_ok=True)
    java_source = fetch(profile["pythonLinkage"]["javaSource"], work / "library-0.18.1-sources.jar")
    with zipfile.ZipFile(java_source) as source:
        initialization = source.read("com/yausername/youtubedl_android/YoutubeDL.kt").decode()
        for required in ("val pythonSize = pythonLib.length().toString()", "shouldUpdatePython(appContext, pythonSize)",
                         "FileUtils.deleteQuietly(pythonDir)", "updatePython(appContext, pythonSize)"):
            if required not in initialization:
                raise ValueError("Pinned cache update contract differs from the reviewed source")
    with zipfile.ZipFile(python_aar) as source, zipfile.ZipFile(ffmpeg_aar) as ffmpeg:
        entries = {info.filename: source.read(info) for info in source.infolist()}
        if len(entries) != len(source.infolist()):
            raise ValueError("Duplicate AAR member")
        audit = dependency_audit({"python": entries[MEMBER], "ffmpeg": ffmpeg.read("jni/arm64-v8a/libffmpeg.zip.so")}, readelf, work)
        data, change = remove_link_alias(entries[MEMBER])
        entries[MEMBER] = data
        output = work / "library-linkage-arm64.aar"
        write_zip(output, entries)
        # Data equality includes Java, consumer rules, yt-dlp, certificates and all other ABIs.
        with zipfile.ZipFile(output) as derived:
            if set(derived.namelist()) != set(source.namelist()) or any(
                    derived.read(name) != source.read(name) for name in source.namelist() if name != MEMBER):
                raise ValueError("Unrelated upstream AAR bytes changed")
    receipt = {"schema": 1, "status": "CANDIDATE", "upstream": python_pin,
               "javaSource": profile["pythonLinkage"]["javaSource"], "ffmpegUpstream": profile["upstream"],
               "ndk": profile["ndk"], "dependencyAudit": audit, "change": change,
               "member": {"path": MEMBER, "bytes": len(data), "sha256": sha(data)},
               "aar": {"bytes": output.stat().st_size, "sha256": sha(output.read_bytes()), "unchangedEntryCount": len(entries) - 1},
               "targetRuntimeVerified": False, "providerVerified": False, "sourcePublicationVerified": False}
    (work / "android-python-linkage-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-aar", type=Path, required=True)
    parser.add_argument("--ffmpeg-aar", type=Path, required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    arguments = parser.parse_args()
    receipt = prepare(arguments.python_aar, arguments.ffmpeg_aar, arguments.ndk, arguments.work_dir)
    print(json.dumps({"status": receipt["status"], "aar": receipt["aar"], "targetRuntimeVerified": False}))
