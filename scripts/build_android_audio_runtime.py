#!/usr/bin/env python3
"""Build an opt-in arm64 audio FFmpeg from pinned source and unchanged AAR dependencies.

The original AAR remains the trust source for reused shared libraries. This tool
does not update the repository's admitted runtime pins, sign an APK, or establish
Android execution, provider, license-publication, or audible acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "config/android-ffmpeg-audio.json"
EXTERNAL_LIBRARIES = (
    "gnutls", "libgme", "libmp3lame", "libopencore-amrnb", "libopencore-amrwb",
    "libopenmpt", "libopus", "librubberband", "libsoxr", "libsrt", "libssh",
    "libvo-amrwbenc", "libvorbis", "libxml2", "libzmq", "bzlib", "lzma", "iconv",
)
SYSTEM_LIBRARIES = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", "libstdc++.so", "libmediandk.so", "libandroid.so"}
# Supplied by the separately pinned, unchanged youtubedl Python runtime, whose
# LD_LIBRARY_PATH precedes FFmpeg's directory in YoutubeDL 0.18.1.
PYTHON_LIBRARIES = {"libc++_shared.so", "libcrypto.so.3", "libssl.so.3", "libandroid-support.so"}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_changed(path: Path, data: bytes) -> None:
    if not path.is_file() or path.read_bytes() != data:
        path.write_bytes(data)


def copy_changed(source: Path, target: Path) -> None:
    write_changed(Path(target), Path(source).read_bytes())


def fetch(source: dict, destination: Path) -> Path:
    if not destination.exists():
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + ".part")
        try:
            with urllib.request.urlopen(source["url"], timeout=90) as response:
                data = response.read(source["bytes"] + 1)
            if len(data) != source["bytes"] or sha(data) != source["sha256"]:
                raise ValueError("Downloaded source identity mismatch")
            temporary.write_bytes(data)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    data = destination.read_bytes()
    if len(data) != source["bytes"] or sha(data) != source["sha256"]:
        raise ValueError("Cached source identity mismatch")
    return destination


def extract_source(archive: Path, destination: Path) -> Path:
    # Extraction is bounded by the pinned compressed source. Python's data
    # filter also rejects archive paths/links escaping this private build tree.
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        members = source.getmembers()
        if len(members) > 65_536 or sum(m.size for m in members) > 256 * 1024 * 1024:
            raise ValueError("Source archive expansion exceeds its bound")
        if not any(destination.iterdir()):
            source.extractall(destination, filter="data")
        allowed = {str(PurePosixPath(member.name)) for member in members}
        allowed.update(str(parent) for name in tuple(allowed) for parent in PurePosixPath(name).parents)
        if any(str(path.relative_to(destination)) not in allowed for path in destination.rglob("*")):
            raise ValueError("Cached source contains entries outside its pinned archive")
        # Reusing a build directory must not silently turn a locally edited
        # header/source into a receipt claiming the pinned, unmodified source.
        for member in members:
            target = destination / member.name
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError("Cached source path escapes its archive")
            if member.isfile():
                if target.is_symlink() or not target.is_file() or target.read_bytes() != source.extractfile(member).read():
                    raise ValueError("Cached source differs from its pinned archive: " + member.name)
            elif member.issym():
                if not target.is_symlink() or os.readlink(target) != member.linkname:
                    raise ValueError("Cached source symlink differs from its pinned archive")
    children = list(destination.iterdir())
    if len(children) != 1 or not children[0].is_dir():
        raise ValueError("Expected one source root")
    return children[0]


def prepare_headers(sources: dict[str, Path], include: Path, metadata: Path) -> None:
    """Install public ABI headers; do not rebuild or replace external libraries.

    Template substitutions are the Android LP64 public header values for these
    exact upstream versions. No private library structs are synthesized.
    """
    def copy(package: str, source: str, target: str) -> None:
        src, dst = sources[package] / source, include / target
        dst.parent.mkdir(parents=True, exist_ok=True)
        if src.is_dir():
            shutil.copytree(src, dst, dirs_exist_ok=True, copy_function=copy_changed)
        else:
            copy_changed(src, dst)

    def generate(package: str, source: str, target: str, values: dict) -> None:
        text = (sources[package] / source).read_text()
        for key, value in values.items():
            text = text.replace("@" + key + "@", str(value))
        if re.search(r"@[A-Za-z_0-9]+@", text):
            raise ValueError("Unresolved upstream public header template")
        dst = include / target
        dst.parent.mkdir(parents=True, exist_ok=True)
        write_changed(dst, text.encode())

    copy("libgnutls", "lib/includes/gnutls", "gnutls")
    generate("libgnutls", "lib/includes/gnutls/gnutls.h.in", "gnutls/gnutls.h", {
        "VERSION": "3.8.10", "MAJOR_VERSION": 3, "MINOR_VERSION": 8,
        "PATCH_VERSION": 10, "NUMBER_VERSION": "0x03080a",
        "DEFINE_IOVEC_T": "typedef struct iovec giovec_t;",
    })
    copy("game-music-emu", "gme/gme.h", "gme/gme.h")
    copy("libmp3lame", "include/lame.h", "lame/lame.h")
    for header in ("interf_dec.h", "interf_enc.h"):
        copy("libopencore-amr", "amrnb/" + header, "opencore-amrnb/" + header)
    for header in ("dec_if.h", "if_rom.h"):
        copy("libopencore-amr", "amrwb/" + header, "opencore-amrwb/" + header)
    copy("libopenmpt", "libopenmpt", "libopenmpt")
    copy("libopus", "include", "opus")
    copy("rubberband", "rubberband", "rubberband")
    copy("libsoxr", "src/soxr.h", "soxr.h")
    for header in (sources["libsrt"] / "srtcore").glob("*.h"):
        copy("libsrt", "srtcore/" + header.name, "srt/" + header.name)
    generate("libsrt", "srtcore/version.h.in", "srt/version.h", {
        "SRT_VERSION_MAJOR": 1, "SRT_VERSION_MINOR": 5, "SRT_VERSION_PATCH": 4,
        "SRT_VERSION": "1.5.4", "CI_BUILD_NUMBER_STRING": "",
    })
    srt = include / "srt/version.h"
    write_changed(srt, srt.read_text().replace("#cmakedefine SRT_VERSION_BUILD ", "/* no CI build suffix */").encode())
    copy("libssh", "include/libssh", "libssh")
    generate("libssh", "include/libssh/libssh_version.h.cmake", "libssh/libssh_version.h", {
        "libssh_VERSION_MAJOR": 0, "libssh_VERSION_MINOR": 11, "libssh_VERSION_PATCH": 3,
    })
    copy("libvo-amrwbenc", "enc_if.h", "vo-amrwbenc/enc_if.h")
    copy("libvorbis", "include/vorbis", "vorbis")
    copy("libogg", "include/ogg", "ogg")
    generate("libogg", "include/ogg/config_types.h.in", "ogg/config_types.h", {
        "INCLUDE_INTTYPES_H": 1, "INCLUDE_STDINT_H": 1, "INCLUDE_SYS_TYPES_H": 1,
        **{f"{prefix}SIZE{bits}": f"{ctype}{bits}_t" for prefix, ctype in (("", "int"), ("U", "uint")) for bits in (16, 32, 64)},
    })
    copy("libxml2", "include/libxml", "libxml2/libxml")
    values = {"VERSION": "2.14.6", "LIBXML_VERSION_EXTRA": "", "LIBXML_VERSION_NUMBER": 21406, "MODULE_EXTENSION": ".so"}
    template = (sources["libxml2"] / "include/libxml/xmlversion.h.in").read_text()
    values.update({key: int(key not in {"WITH_ICU", "WITH_THREAD_ALLOC"}) for key in re.findall(r"@(WITH_\w+)@", template)})
    generate("libxml2", "include/libxml/xmlversion.h.in", "libxml2/libxml/xmlversion.h", values)
    copy("libzmq", "include/zmq.h", "zmq.h")
    copy("libbz2", "bzlib.h", "bzlib.h")
    copy("liblzma", "src/liblzma/api/lzma.h", "lzma.h")
    copy("liblzma", "src/liblzma/api/lzma", "lzma")
    generate("libiconv", "include/iconv.h.in", "iconv.h", {
        "DLL_VARIABLE": "", "EILSEQ": 84, "ICONV_CONST": "", "USE_MBSTATE_T": 1, "BROKEN_WCHAR_H": 0,
    })
    metadata.mkdir(parents=True, exist_ok=True)
    for package, version, library, suffix in (
        ("gnutls", "3.8.10", "gnutls", ""), ("libgme", "0.6.4", "gme", ""),
        ("libopenmpt", "0.8.3", "openmpt", ""), ("opus", "1.5.2", "opus", "/opus"),
        ("rubberband", "4.0.0", "rubberband", ""), ("libssh", "0.11.3", "ssh", ""),
        ("srt", "1.5.4", "srt", ""), ("vorbis", "1.3.7", "vorbis", ""),
        ("vorbisenc", "1.3.7", "vorbisenc", ""), ("libxml-2.0", "2.14.6", "xml2", "/libxml2"),
        ("libzmq", "4.3.5", "zmq", ""),
    ):
        (metadata / (package + ".pc")).write_text(
            f"Name: {package}\nDescription: pinned upstream public ABI\nVersion: {version}\n"
            f"Libs: -L../android-origin/usr/lib -l{library}\nCflags: -I../android-sdk/include{suffix}\n"
        )


def audio_options(source: Path) -> list[str]:
    codecs = (source / "libavcodec/allcodecs.c").read_text()
    audio = codecs[codecs.index("/* audio codecs */"):codecs.index("/* subtitles */")]
    filters = (source / "libavfilter/allfilters.c").read_text()
    result = ["--disable-decoders", "--disable-encoders", "--disable-filters"]
    for kind in ("decoder", "encoder"):
        names = sorted(set(re.findall(r"ff_(\w+)_" + kind, audio)))
        result.append("--enable-" + kind + "=" + ",".join(names))
    # amovie lives with movie, and concat supports both audio and video; neither
    # follows the otherwise useful audio-only registration prefix.
    names = sorted(set(re.findall(r"ff_a(?:f|src|sink)_(\w+)", filters)) | {"amovie", "concat"})
    result.append("--enable-filter=" + ",".join(names))
    return result


def verify_audio_configuration(source: Path, build: Path) -> dict[str, list[str]]:
    enabled = set(re.findall(r"^#define (CONFIG_\w+) 1$", (build / "config_components.h").read_text(), re.M))
    enabled.update(re.findall(r"^#define (CONFIG_\w+) 1$", (build / "config.h").read_text(), re.M))
    expected = {
        kind: option.split("=", 1)[1].split(",")
        for kind in ("decoder", "encoder")
        for option in audio_options(source) if option.startswith("--enable-" + kind + "=")
    }
    expected["decoder"] += ["libopencore_amrnb", "libopencore_amrwb", "libopus", "libvorbis",
                            "aac_mediacodec", "amrnb_mediacodec", "amrwb_mediacodec", "mp3_mediacodec"]
    expected["encoder"] += ["libmp3lame", "libopencore_amrnb", "libopus", "libvo_amrwbenc", "libvorbis"]
    expected["protocol"] = ["file", "pipe", "http", "https", "tls", "tcp", "udp", "libsrt", "libssh", "libzmq"]
    expected["filter"] = ["aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat", "amovie"]
    expected["demuxer"] = ["wav", "flac", "mp3", "aac", "ogg", "mov", "aiff", "matroska", "hls", "dash"]
    missing = [name + "_" + kind for kind, names in expected.items() for name in names
               if "CONFIG_" + name.upper() + "_" + kind.upper() not in enabled]
    missing += [name for name in EXTERNAL_LIBRARIES if "CONFIG_" + name.upper().replace("-", "_") not in enabled]
    if missing:
        raise ValueError("Required audio/runtime configuration missing: " + ", ".join(sorted(missing)))
    return {kind: sorted(set(names)) for kind, names in expected.items()}


def extract_native(source: zipfile.ZipFile, destination: Path) -> None:
    entries = source.infolist()
    if len(entries) > 1024 or sum(e.file_size for e in entries) > 256 * 1024 * 1024:
        raise ValueError("Upstream native ZIP exceeds bound")
    if len({e.filename for e in entries}) != len(entries):
        raise ValueError("Duplicate upstream native entry")
    for entry in entries:
        relative = PurePosixPath(entry.filename)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Unsafe upstream native path")
        target = destination.joinpath(*relative.parts)
        if entry.is_dir():
            target.mkdir(parents=True, exist_ok=True)
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        data = source.read(entry)
        if stat.S_ISLNK(entry.external_attr >> 16):
            link = data.decode("utf-8")
            if not (target.parent / link).resolve().is_relative_to(destination.resolve()):
                raise ValueError("Unsafe upstream native symlink")
            if not target.is_symlink():
                target.symlink_to(link)
        else:
            target.write_bytes(data)


def write_zip(path: Path, entries: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, content)


def write_derived_aar(source: Path, output: Path, replacements: dict[str, bytes]) -> dict:
    expected = {"jni/arm64-v8a/" + name for name in ("libffmpeg.so", "libffprobe.so", "libffmpeg.zip.so")}
    if set(replacements) != expected:
        raise ValueError("Only the three arm64 FFmpeg members may be replaced")
    with zipfile.ZipFile(source) as original:
        members = [entry for entry in original.infolist() if not entry.is_dir()]
        if len({e.filename for e in members}) != len(members) or not expected.issubset(e.filename for e in members):
            raise ValueError("Original AAR has duplicate or missing members")
        contents = {entry.filename: original.read(entry) for entry in members}
    unchanged = [{"path": name, "bytes": len(data), "sha256": sha(data)}
                 for name, data in sorted(contents.items()) if name not in replacements]
    contents.update(replacements)
    write_zip(output, contents)
    return {"unchangedEntryCount": len(unchanged),
            "unchangedEntriesSha256": sha(json.dumps(unchanged, separators=(",", ":")).encode()),
            "bytes": output.stat().st_size, "sha256": sha(output.read_bytes())}


def dependency_closure(frontends: list[Path], libraries: dict[str, Path], readelf: Path) -> dict[str, Path]:
    selected: dict[str, Path] = {}
    pending = list(frontends)
    visited = set()
    while pending:
        path = pending.pop()
        if path.resolve() in visited:
            continue
        visited.add(path.resolve())
        dynamic = subprocess.check_output([readelf, "--dynamic", path], text=True)
        for name in re.findall(r"Shared library: \[([^]]+)\]", dynamic):
            if name in SYSTEM_LIBRARIES or name in PYTHON_LIBRARIES:
                continue
            if name not in libraries:
                raise ValueError("Unresolved native runtime dependency: " + name)
            selected[name] = libraries[name]
            pending.append(libraries[name])
    return selected


def prepare_build_source(build: Path, source: Path) -> None:
    # FFmpeg 7.1.1 configure chooses an absolute SRC_PATH on its first
    # out-of-tree run unless src/configure already exists. That path reaches
    # __FILE__ strings in libavcodec and prevents reproducing the admitted bytes.
    build.mkdir(exist_ok=True)
    link = build / "src"
    if not link.exists() and not link.is_symlink():
        link.symlink_to(os.path.relpath(source, build), target_is_directory=True)
    if not link.is_symlink() or link.resolve() != source.resolve():
        raise ValueError("Unexpected FFmpeg build source link")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--aar", type=Path, required=True)
    parser.add_argument("--python-aar", type=Path, required=True)
    parser.add_argument("--work-dir", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4, choices=range(1, 9))
    args = parser.parse_args()
    profile = json.loads(PROFILE.read_text())
    if sha(args.aar.read_bytes()) != profile["upstream"]["sha256"]:
        raise ValueError("Original FFmpeg AAR identity differs from repository pin")
    runtime_pins = json.loads((ROOT / "scripts/android_runtime_pins.json").read_text())
    python_pin = next(s for s in runtime_pins["sources"] if s["coordinate"] == "io.github.junkfood02.youtubedl-android:library:0.18.1")
    if sha(args.python_aar.read_bytes()) != python_pin["sha256"]:
        raise ValueError("Original Python runtime AAR identity differs from repository pin")
    import io
    with zipfile.ZipFile(args.python_aar) as python_aar:
        with zipfile.ZipFile(io.BytesIO(python_aar.read("jni/arm64-v8a/libpython.zip.so"))) as python_zip:
            python_dependencies = [{"path": "usr/lib/" + name, "bytes": python_zip.getinfo("usr/lib/" + name).file_size,
                                    "sha256": sha(python_zip.read("usr/lib/" + name))} for name in sorted(PYTHON_LIBRARIES)]
    properties = (args.ndk / "source.properties").read_text()
    if not re.search(r"Pkg.Revision\s*=\s*" + re.escape(profile["ndk"]["revision"]) + r"\s*$", properties, re.M):
        raise ValueError("Requires the pinned NDK revision")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if host is None:
        raise ValueError("Build this Android derivation on macOS or Linux")
    tools = args.ndk.resolve() / "toolchains/llvm/prebuilt" / host / "bin"
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    ffmpeg = fetch(profile["ffmpeg"], work / "downloads/ffmpeg-7.1.1.tar.xz")
    source = extract_source(ffmpeg, work / "source")
    # Stable relative configure paths also prevent host/user paths in binary strings.
    source_link = work / "ffmpeg-7.1.1"
    if not source_link.exists():
        source_link.symlink_to(source.relative_to(work))
    if not source_link.is_symlink() or source_link.resolve() != source.resolve():
        raise ValueError("Unexpected pinned FFmpeg source link")
    header_sources = {}
    for item in profile["headers"]:
        suffix = next(s for s in (".tar.gz", ".tar.xz", ".tar.bz2") if s in item["url"])
        archive = fetch(item, work / "downloads" / (item["package"] + "-" + item["version"] + suffix))
        header_sources[item["package"]] = extract_source(archive, work / "headers-source" / item["package"])
    prepare_headers(header_sources, work / "android-sdk/include", work / "android-sdk/pkgconfig")
    with zipfile.ZipFile(args.aar) as original:
        nested = original.read("jni/arm64-v8a/libffmpeg.zip.so")
        with zipfile.ZipFile(io.BytesIO(nested)) as native:
            extract_native(native, work / "android-origin")
    build = work / "android-audio"
    prepare_build_source(build, source_link)
    flags = [
        "../ffmpeg-7.1.1/configure", "--prefix=/data/youtubedl-android/usr", "--target-os=android", "--arch=aarch64",
        "--enable-cross-compile", "--cc=aarch64-linux-android29-clang", "--cxx=aarch64-linux-android29-clang++",
        "--ar=llvm-ar", "--ranlib=llvm-ranlib", "--nm=llvm-nm", "--strip=llvm-strip",
        "--disable-autodetect", "--enable-shared", "--disable-static", "--disable-debug", "--disable-doc",
        "--disable-ffplay", "--disable-postproc", "--disable-swscale", "--disable-hwaccels",
        "--disable-indevs", "--disable-outdevs", "--enable-indev=lavfi", "--enable-jni", "--enable-mediacodec",
        "--enable-zlib", "--enable-gpl", "--enable-version3", "--disable-symver", "--enable-small",
        "--extra-cflags=-Os -ffunction-sections -fdata-sections -I../android-sdk/include",
        "--extra-ldflags=-Wl,--gc-sections -Wl,-z,max-page-size=16384 -L../android-origin/usr/lib -Wl,-rpath-link,../android-origin/usr/lib",
        # Bionic API 29 also exports iconv; these upstream headers deliberately
        # use GNU libiconv's names, so bind their existing library explicitly.
        "--extra-libs=-liconv",
        *audio_options(source), *("--enable-" + name for name in EXTERNAL_LIBRARIES),
        "--enable-decoder=libopencore_amrnb,libopencore_amrwb,libopus,libvorbis,aac_mediacodec,amrnb_mediacodec,amrwb_mediacodec,mp3_mediacodec",
        "--enable-encoder=libmp3lame,libopencore_amrnb,libopus,libvo_amrwbenc,libvorbis",
    ]
    (work / "configure.json").write_text(json.dumps(flags, indent=2) + "\n")
    env = {**os.environ, "PATH": str(tools) + os.pathsep + os.environ["PATH"],
           "PKG_CONFIG_LIBDIR": str(work / "android-sdk/pkgconfig"), "PKG_CONFIG_PATH": "",
           "SOURCE_DATE_EPOCH": "1741651200", "LC_ALL": "C", "TZ": "UTC"}
    subprocess.run(flags, cwd=build, env=env, check=True)
    required_features = verify_audio_configuration(source, build)
    subprocess.run(["make", f"-j{args.jobs}"], cwd=build, env=env, check=True)
    libraries = {p.name: p for p in (work / "android-origin/usr/lib").iterdir() if p.is_file()}
    rebuilt = {p.name: p for p in build.glob("lib*/*.so*") if p.is_file()}
    libraries.update(rebuilt)
    frontends = [build / "ffmpeg", build / "ffprobe"]
    closure = dependency_closure(frontends, libraries, tools / "llvm-readelf")
    entries = {"usr/lib/" + name: path.read_bytes() for name, path in closure.items()}
    entries["licenses/FFmpeg-COPYING.GPLv3"] = (source / "COPYING.GPLv3").read_bytes()
    output = work / "output"
    output.mkdir(exist_ok=True)
    native_zip = output / "libffmpeg.zip.so"
    write_zip(native_zip, entries)
    for src, name in zip(frontends, ("libffmpeg.so", "libffprobe.so")):
        shutil.copyfile(src, output / name)
    replacements = {"jni/arm64-v8a/" + name: (output / name).read_bytes() for name in ("libffmpeg.so", "libffprobe.so", "libffmpeg.zip.so")}
    aar_receipt = write_derived_aar(args.aar, output / "ffmpeg-audio-arm64.aar", replacements)
    receipt = {
        "schema": 1, "status": "CANDIDATE", "upstream": profile["upstream"],
        "pythonRuntime": {"upstream": python_pin, "sharedDependencies": python_dependencies},
        "source": profile["ffmpeg"], "headerSources": profile["headers"], "ndk": profile["ndk"], "configure": flags,
        "requiredFeatures": required_features, "sourceTreeVerified": True,
        "aar": aar_receipt,
        "members": [{"path": name, "bytes": len(data), "sha256": sha(data)} for name, data in sorted(replacements.items())],
        "entries": [{"path": name, "bytes": len(data), "sha256": sha(data),
                     "origin": "recompiled-ffmpeg" if name.removeprefix("usr/lib/") in rebuilt else "pinned-upstream-aar" if name.startswith("usr/lib/") else "pinned-ffmpeg-source"}
                    for name, data in sorted(entries.items())],
        "targetRuntimeVerified": False, "providerVerified": False, "sourcePublicationVerified": False,
    }
    (output / "ffmpeg-audio-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"status": "CANDIDATE_BUILT", "nativeBytes": native_zip.stat().st_size,
                      "libraryCount": len(closure), "targetRuntimeVerified": False}))


if __name__ == "__main__":
    main()
