#!/usr/bin/env python3
"""Cross-build the opt-in Windows audio tools, preserving pinned source receipts.

Python 3.14, pkg-config, make, Meson 1.9.0 and Ninja 1.13.0 are host tools.
The compiler, assembler, source and Windows libraries are fetched by exact pins.
This script does not install, sign, publish, or execute Windows programs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / "config/windows-ffmpeg-audio.json"
MARKER = ".choplab-windows-audio-build"
SYSTEM_DLLS = {
    "advapi32.dll", "avrt.dll", "bcrypt.dll", "crypt32.dll", "d3d11.dll",
    "dxgi.dll", "gdi32.dll", "iphlpapi.dll", "kernel32.dll", "msvcrt.dll",
    "ncrypt.dll", "ole32.dll", "oleaut32.dll", "psapi.dll", "rpcrt4.dll",
    "secur32.dll", "setupapi.dll", "shell32.dll", "shlwapi.dll", "user32.dll",
    "userenv.dll", "version.dll", "winmm.dll", "ws2_32.dll", "wsock32.dll",
}


def digest(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def identity(path: Path) -> dict:
    return {"bytes": path.stat().st_size, "sha256": digest(path)}


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def profile_inputs(profile: dict) -> dict:
    return {key: value for key, value in profile.items() if key != "derived"}


def inputs_hash(profile: dict) -> str:
    data = json.dumps(profile_inputs(profile), sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(data).hexdigest()


def fetch(pin: dict, directory: Path) -> Path:
    path = directory / pin["filename"]
    directory.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_name(path.name + ".part")
        try:
            with urllib.request.urlopen(pin["url"], timeout=120) as response, temporary.open("wb") as out:
                remaining = pin["bytes"]
                while block := response.read(min(1024 * 1024, remaining + 1)):
                    remaining -= len(block)
                    if remaining < 0:
                        raise ValueError("Download exceeds its pinned size")
                    out.write(block)
            if identity(temporary) != {key: pin[key] for key in ("bytes", "sha256")}:
                raise ValueError("Downloaded artifact identity mismatch: " + path.name)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)
    if path.is_symlink() or identity(path) != {key: pin[key] for key in ("bytes", "sha256")}:
        raise ValueError("Cached artifact identity mismatch: " + path.name)
    return path


def extract(archive: Path, destination: Path, prefix: str | None = None) -> None:
    with tarfile.open(archive) as source:
        for member in source:
            if prefix is None or member.name.startswith(prefix):
                source.extract(member, destination, filter="data")


def run(args: list[str], cwd: Path, env: dict, log) -> None:
    result = subprocess.run(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed ({result.returncode}); see {log.name}")


def verify_sources(packages: list[dict], sources: Path) -> None:
    for package in packages:
        pin = package["source"]
        with tarfile.open(sources / pin["filename"]) as archive:
            entries = [item for item in archive if item.isfile() and item.name.endswith("/.SRCINFO")]
            if len(entries) != 1:
                raise ValueError("Missing source package identity")
            lines = archive.extractfile(entries[0]).read().decode().splitlines()
            fields = {key.strip(): value.strip() for line in lines if "=" in line for key, value in [line.split("=", 1)]}
            version = fields["pkgver"] + "-" + fields["pkgrel"]
            if version != package["version"] or pin["filename"] != fields["pkgbase"] + "-" + version + ".src.tar.zst":
                raise ValueError("Source and binary package versions differ")


def verify_configuration(profile: dict, build: Path) -> dict:
    headers = (build / "config.h").read_text() + (build / "config_components.h").read_text()
    enabled = set(re.findall(r"^#define (CONFIG_\w+) 1$", headers, re.M))
    required = {}
    for kind in ("decoder", "encoder"):
        flag = next(value for value in profile["configure"] if value.startswith("--enable-" + kind + "="))
        required[kind] = flag.split("=", 1)[1].split(",")
    required.update({
        "filter": ["aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat", "amovie", "azmq"],
        "protocol": ["file", "pipe", "http", "https", "tls", "tcp", "udp", "libsrt", "libssh", "libzmq"],
        "demuxer": ["wav", "flac", "mp3", "aac", "ogg", "mov", "aiff", "matroska", "hls", "dash", "libgme", "libopenmpt"],
        "indev": ["dshow", "lavfi", "openal"],
    })
    missing = [name + "_" + kind for kind, names in required.items() for name in names
               if "CONFIG_" + name.upper() + "_" + kind.upper() not in enabled]
    missing += [name for name in ("GNUTLS", "GMP", "BZLIB", "LZMA", "ZLIB", "ICONV", "LIBXML2", "LIBRUBBERBAND", "X86ASM", "MEDIAFOUNDATION", "D3D11VA")
                if "CONFIG_" + name not in enabled]
    # x86 assembly is a HAVE rather than CONFIG switch in FFmpeg's headers.
    if "X86ASM" in missing and re.search(r"^#define HAVE_X86ASM 1$", headers, re.M):
        missing.remove("X86ASM")
    if missing:
        raise ValueError("Missing required audio feature: " + ", ".join(missing))
    return required


def pe_imports(tool: Path, binary: Path) -> dict[str, list[str]]:
    text = subprocess.check_output([str(tool), "--coff-imports", str(binary)], text=True)
    result = {}
    for block in re.findall(r"(?:Delay)?Import \{(.*?)\n\}", text, re.S):
        name = re.search(r"^  Name: (.+)$", block, re.M)
        if name:
            result[name.group(1).lower()] = re.findall(r"^  Symbol: (.*?) \(\d+\)$", block, re.M)
    return result


def dll_closure(tool: Path, roots: list[Path], candidates: dict[str, Path]) -> dict[str, Path]:
    selected, pending = {}, list(roots)
    while pending:
        path = pending.pop()
        name = path.name.lower()
        if name in selected:
            continue
        selected[name] = path
        for dependency in pe_imports(tool, path):
            if dependency in candidates:
                pending.append(candidates[dependency])
            elif dependency not in SYSTEM_DLLS and not re.fullmatch(r"api-ms-win-crt-[a-z0-9-]+\.dll", dependency):
                raise ValueError(f"Unresolved DLL: {path.name} -> {dependency}")
    # An import library from a different C++ release must not silently introduce
    # unresolved entry points even though the DLL filename happens to match.
    exports = {}
    for name, path in selected.items():
        text = subprocess.check_output([str(tool), "--coff-exports", str(path)], text=True)
        exports[name] = set(re.findall(r"^  Name: (.+)$", text, re.M))
    for path in selected.values():
        for dependency, symbols in pe_imports(tool, path).items():
            if dependency in exports:
                missing = set(symbols) - exports[dependency]
                if missing:
                    raise ValueError(f"Unresolved imports from {path.name} in {dependency}: {sorted(missing)}")
    return selected


def collect_notices(work: Path, output: Path, packages: list[dict], toolchain: Path) -> None:
    notices = output / "ffmpeg-notices"
    for package in packages:
        short = package["name"].removeprefix("mingw-w64-clang-x86_64-")
        destination = notices / short
        destination.mkdir(parents=True, exist_ok=True)
        existing = work / "sysroot/clang64/share/licenses" / short
        if existing.is_dir():
            for item in existing.rglob("*"):
                if item.is_file():
                    target = destination / (item.relative_to(existing).as_posix().replace("/", "-") + ".txt")
                    shutil.copyfile(item, target)
        if any(destination.iterdir()):
            continue
        # GPL/LGPL packages often refer to MSYS2's common license directory.
        # Preserve the corresponding source's own copyright/license texts.
        with tarfile.open(work / "sources" / package["source"]["filename"]) as outer:
            for entry in outer:
                if not entry.isfile() or not re.search(r"\.tar\.(?:gz|xz|bz2|zst)$", entry.name):
                    continue
                with tarfile.open(fileobj=outer.extractfile(entry), mode="r|*") as inner:
                    for item in inner:
                        leaf = Path(item.name).name
                        if item.isfile() and len(Path(item.name).parts) <= 2 and re.match(r"^(COPYING|COPYRIGHT|LICENSE|LICENCE|NOTICE|AUTHORS)(?:[._-].*)?$", leaf, re.I):
                            if item.size > 512 * 1024:
                                raise ValueError("License unexpectedly exceeds the public text limit")
                            (destination / (leaf + ".txt")).write_bytes(inner.extractfile(item).read())
        if not any(destination.iterdir()):
            raise ValueError("No corresponding license text for " + short)
    ffmpeg = notices / "ffmpeg"
    ffmpeg.mkdir(parents=True, exist_ok=True)
    for path in (work / "ffmpeg-8.1.2").glob("COPYING*"):
        shutil.copyfile(path, ffmpeg / (path.name + ".txt"))
    shutil.copyfile(work / "ffmpeg-8.1.2/COPYING.GPLv3", output / "FFmpeg-LICENSE.txt")
    (notices / "rubberband").mkdir(exist_ok=True)
    shutil.copyfile(work / "rubberband-4.0.0/COPYING", notices / "rubberband/COPYING.txt")
    shutil.copyfile(toolchain / "LICENSE.TXT", notices / "LLVM-MinGW-LICENSE.txt")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=4, choices=range(1, 17))
    parser.add_argument("--collect-only", action="store_true", help="Read back an already built, owned candidate")
    args = parser.parse_args()
    work = args.work.resolve()
    if work.exists() and any(work.iterdir()) and not (work / MARKER).is_file():
        raise ValueError("Refusing a work directory without this recipe's ownership marker")
    work.mkdir(parents=True, exist_ok=True)
    (work / MARKER).write_text("Generated Windows audio runtime build; no user projects.\n")
    profile = json.loads(PROFILE.read_text())
    downloads, sources = work / "downloads", work / "sources"
    for key in ("ffmpeg", "rubberband", "toolchain", "nasm"):
        archive = fetch(profile[key], downloads)
        extract(archive, work)
    for package in profile["packages"]:
        extract(fetch(package["binary"], downloads), work / "sysroot", "clang64/")
        fetch(package["source"], sources)
    verify_sources(profile["packages"], sources)
    toolchain = work / "llvm-mingw-20260922-ucrt-macos-universal"
    env = {**os.environ, "PATH": os.pathsep.join([str(work / "host-tools/bin"), str(toolchain / "bin"), os.environ["PATH"]]),
           "SOURCE_DATE_EPOCH": str(profile["sourceDateEpoch"]), "LC_ALL": "C", "TZ": "UTC",
           "PKG_CONFIG_PATH": "", "PKG_CONFIG_LIBDIR": "", "PKG_CONFIG_SYSROOT_DIR": ""}
    if not args.collect_only:
        for tool in ("meson", "ninja"):
            actual = subprocess.check_output([tool, "--version"], text=True).strip()
            # The pinned PyPI Ninja distribution adds its jobserver build suffix
            # to the upstream version; the package version remains 1.13.0.
            if actual.split(".git.", 1)[0] != profile["buildTools"][tool]:
                raise ValueError("Unexpected host tool version: " + tool)
        with (work / "build.log").open("w") as log:
            run(["./configure", "--prefix=" + str(work / "host-tools")], work / "nasm-2.16.03", env, log)
            run(["make", "-j" + str(args.jobs)], work / "nasm-2.16.03", env, log)
            run(["make", "install"], work / "nasm-2.16.03", env, log)
            cross = "[binaries]\n" + "\n".join(f"{name} = '{toolchain}/bin/{binary}'" for name, binary in {
                "c": "x86_64-w64-mingw32-clang", "cpp": "x86_64-w64-mingw32-clang++", "ar": "llvm-ar",
                "strip": "llvm-strip", "windres": "x86_64-w64-mingw32-windres"}.items())
            cross += f"\npkg-config = '{shutil.which('pkg-config')}'\n[host_machine]\nsystem = 'windows'\ncpu_family = 'x86_64'\ncpu = 'x86_64'\nendian = 'little'\n[properties]\nneeds_exe_wrapper = true\nsys_root = '{work}/sysroot'\npkg_config_libdir = '{work}/sysroot/clang64/lib/pkgconfig'\n[built-in options]\ncpp_args = ['-nostdinc++', '-isystem{work}/sysroot/clang64/include/c++/v1', '-ffile-prefix-map={work}=/choplab-native']\ncpp_link_args = ['-L{work}/sysroot/clang64/lib']\n"
            (work / "rubberband-cross.ini").write_text(cross)
            run(["meson", "setup", "rubberband-build", "rubberband-4.0.0", "--cross-file", "rubberband-cross.ini",
                 "--prefix=/choplab/native", "--default-library=shared", "--buildtype=release", "-Dfft=fftw", "-Dresampler=libsamplerate",
                 "-Djni=disabled", "-Dladspa=disabled", "-Dlv2=disabled", "-Dvamp=disabled", "-Dcmdline=disabled", "-Dtests=disabled"], work, env, log)
            run(["ninja", "-C", "rubberband-build", "-j" + str(args.jobs)], work, env, log)
            run(["meson", "install", "-C", "rubberband-build", "--destdir", str(work / "rubberband-install")], work, env, log)
            pc = work / "pkgconfig"
            pc.mkdir(exist_ok=True)
            for path in (work / "sysroot/clang64/lib/pkgconfig").glob("*.pc"):
                (pc / path.name).write_text(path.read_text().replace("/clang64", "../sysroot/clang64"))
            path = work / "rubberband-install/choplab/native/lib/pkgconfig/rubberband.pc"
            (pc / path.name).write_text(path.read_text().replace("/choplab/native", "../rubberband-install/choplab/native"))
            build = work / "windows-audio"
            build.mkdir(exist_ok=True)
            # FFmpeg otherwise embeds the absolute source path on its first
            # out-of-tree configure, while subsequent runs use this link.
            link = build / "src"
            if not link.exists():
                link.symlink_to("../ffmpeg-8.1.2", target_is_directory=True)
            if link.resolve() != (work / "ffmpeg-8.1.2").resolve():
                raise ValueError("Unexpected FFmpeg source link")
            env.update(PKG_CONFIG_LIBDIR=str(pc), PKG_CONFIG_PATH="", PKG_CONFIG_SYSROOT_DIR="")
            run(profile["configure"], build, env, log)
            verify_configuration(profile, build)
            run(["make", "-j" + str(args.jobs)], build, env, log)
    build = work / "windows-audio"
    features = verify_configuration(profile, build)
    candidates = {path.name.lower(): path for path in (work / "sysroot/clang64/bin").glob("*.dll")}
    candidates.update({path.name.lower(): path for path in build.glob("lib*/*-*.dll")})
    candidates.update({path.name.lower(): path for path in (work / "rubberband-install/choplab/native/bin").glob("*.dll")})
    selected = dll_closure(toolchain / "bin/llvm-readobj", [build / "ffmpeg.exe", build / "ffprobe.exe"], candidates)
    if "librubberband-3.dll" not in selected or "librubberband-2.dll" in selected:
        raise ValueError("The candidate must use the source-built Rubber Band 4 library")
    output = work / "output"
    if output.exists():
        shutil.rmtree(output)  # Only this recipe's generated subdirectory, under its owned work tree.
    output.mkdir()
    for path in selected.values():
        shutil.copyfile(path, output / path.name)
        if path.is_relative_to(build) or path.is_relative_to(work / "rubberband-install"):
            subprocess.run([str(toolchain / "bin/llvm-strip"), "--strip-unneeded", str(output / path.name)], check=True)
            data = (output / path.name).read_bytes()
            if any(prefix in data for prefix in (b"/Users/", b"/home/", b"\\Users\\")):
                raise ValueError("A derived binary contains a host home path")
    dll_closure(toolchain / "bin/llvm-readobj", [output / "ffmpeg.exe", output / "ffprobe.exe"],
                {path.name.lower(): path for path in output.glob("*.dll")})
    collect_notices(work, output, profile["packages"], toolchain)
    write_json(output / "FFmpeg-SOURCES.json", profile_inputs(profile))
    files = {path.relative_to(output).as_posix(): identity(path) for path in sorted(output.rglob("*")) if path.is_file()}
    imports = {path.name: pe_imports(toolchain / "bin/llvm-readobj", path) for path in selected.values()}
    receipt = {"schema": 1, "scope": "cross-build-only", "windowsExecutionVerified": False,
               "audioDeviceVerified": False, "providerVerified": False, "publicDistributionVerified": False,
               "profileInputsSha256": inputs_hash(profile), "files": files, "configuredFeatures": features,
               "imports": {name: sorted(value) for name, value in imports.items()},
               "checkedImportSymbols": sum(len(symbols) for value in imports.values() for symbols in value.values())}
    write_json(output / "FFmpeg-runtime.json", receipt)
    files["FFmpeg-runtime.json"] = identity(output / "FFmpeg-runtime.json")
    archive = work / "ffmpeg-windows-audio.zip"
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
        for name in sorted(files):
            item = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o100644 << 16
            zipped.writestr(item, (output / name).read_bytes(), compresslevel=6)
    derived = {"profileInputsSha256": inputs_hash(profile), "files": files,
               "payloadBytes": sum(value["bytes"] for value in files.values()), "archive": identity(archive)}
    write_json(work / "derived.json", derived)
    if profile.get("derived") is not None and derived != profile["derived"]:
        raise ValueError("Rebuilt candidate differs from admitted bytes; inspect derived.json before changing any pin")
    print(json.dumps({"status": "CROSS_BUILD_ONLY", "files": len(files), "payloadBytes": derived["payloadBytes"],
                      "zip": derived["archive"], "windowsExecutionVerified": False}))


if __name__ == "__main__":
    main()
