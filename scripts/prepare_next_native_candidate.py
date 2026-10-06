#!/usr/bin/env python3
"""Connect the reviewed native recipes to explicitly named NEXT build inputs.

Rebuilding never admits new bytes: repository pins must match before staging.
This command does not sign, install, publish, or establish target acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

if __package__:
    from . import windows_audio_runtime
else:
    import windows_audio_runtime

ROOT = Path(__file__).resolve().parents[1]
MARKER = ".choplab-next-native-candidate"
UPSTREAM_LIMIT = 256 * 1024 * 1024


def identity(path: Path) -> dict:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Candidate input must be a regular file")
    with path.open("rb") as stream:
        return {"bytes": path.stat().st_size, "sha256": hashlib.file_digest(stream, "sha256").hexdigest()}


def require_identity(path: Path, pin: dict) -> dict:
    actual = identity(path)
    if actual != {key: pin[key] for key in ("bytes", "sha256")}:
        raise ValueError("Rebuilt candidate differs from repository pins: " + path.name)
    return actual


def fetch_upstream(pin: dict, target: Path) -> Path:
    """Use only the URL and SHA already admitted by the runtime source policy."""
    if not pin["url"].startswith("https://"):
        raise ValueError("Pinned upstream must use HTTPS")
    if not target.exists() and not target.is_symlink():
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=target.parent, prefix=target.name, delete=False) as stream:
                temporary = Path(stream.name)
                with urllib.request.urlopen(pin["url"], timeout=120) as response:
                    count = 0
                    while block := response.read(min(1024 * 1024, UPSTREAM_LIMIT - count + 1)):
                        count += len(block)
                        if count > UPSTREAM_LIMIT:
                            raise ValueError("Upstream AAR exceeds download bound")
                        stream.write(block)
            # Close before readback/rename, including on Windows policy tests.
            if identity(temporary)["sha256"] != pin["sha256"]:
                raise ValueError("Downloaded upstream AAR differs from repository pin")
            temporary.replace(target)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    actual = identity(target)
    if actual["bytes"] > UPSTREAM_LIMIT or actual["sha256"] != pin["sha256"]:
        raise ValueError("Cached upstream AAR differs from repository pin")
    return target


def own_work(work: Path) -> None:
    if work.is_symlink() or (work.exists() and not work.is_dir()):
        raise ValueError("Use a private native build directory")
    marker = work / MARKER
    if work.exists() and any(work.iterdir()) and (
            marker.is_symlink() or not marker.is_file() or marker.read_text() != "NEXT native candidate build\n"):
        raise ValueError("Refusing a nonempty directory not owned by this recipe")
    work.mkdir(parents=True, exist_ok=True)
    marker.write_text("NEXT native candidate build\n")


def stage(candidate: Path, files: dict[str, Path], kind: str, profile: Path) -> None:
    candidate.mkdir(exist_ok=True)
    expected = set(files) | {"native-inputs.json"}
    if candidate.is_symlink() or any(path.name not in expected or path.is_symlink() for path in candidate.iterdir()):
        raise ValueError("Candidate staging contains an unrelated entry")
    identities = {}
    for name, source in files.items():
        target = candidate / name
        shutil.copyfile(source, target)
        identities[name] = identity(target)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    clean = not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
    receipt = {"schemaVersion": 1, "kind": kind, "candidateOnly": True, "sourceRevision": commit,
               "sourceTreeClean": clean, "profileSha256": identity(profile)["sha256"], "files": identities,
               "runtime": "NOT_RUN", "device": "NOT_RUN", "provider": "NOT_RUN", "publicRelease": "NOT_RUN"}
    (candidate / "native-inputs.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")


def build_android(work: Path, ndk: Path, jobs: int) -> Path:
    own_work(work)
    profile_path = ROOT / "config/android-ffmpeg-audio.json"
    profile = json.loads(profile_path.read_text())
    pins = json.loads((ROOT / "scripts/android_runtime_pins.json").read_text())
    python_pin = next(pin for pin in pins["sources"] if pin["coordinate"].endswith(":library:0.18.1"))
    if profile["pythonLinkage"]["upstream"] != python_pin:
        raise ValueError("Python source pin differs from the admitted runtime")
    ffmpeg = fetch_upstream(profile["upstream"], work / "upstream/ffmpeg-0.18.1.aar")
    python = fetch_upstream(python_pin, work / "upstream/library-0.18.1.aar")
    subprocess.run([sys.executable, str(ROOT / "scripts/build_android_audio_runtime.py"),
                    "--ndk", str(ndk), "--aar", str(ffmpeg), "--python-aar", str(python),
                    "--work-dir", str(work / "ffmpeg"), "--jobs", str(jobs)], check=True)
    subprocess.run([sys.executable, str(ROOT / "scripts/prepare_android_python_runtime.py"),
                    "--ndk", str(ndk), "--ffmpeg-aar", str(ffmpeg), "--python-aar", str(python),
                    "--work-dir", str(work / "python")], check=True)
    ffmpeg_aar = work / "ffmpeg/output/ffmpeg-audio-arm64.aar"
    python_aar = work / "python/library-linkage-arm64.aar"
    require_identity(ffmpeg_aar, profile["derived"]["aar"])
    require_identity(python_aar, profile["pythonLinkage"]["aar"])
    candidate = work / "candidate"
    stage(candidate, {
        "ffmpeg-audio-arm64.aar": ffmpeg_aar,
        "library-linkage-arm64.aar": python_aar,
        "ffmpeg-audio-receipt.json": work / "ffmpeg/output/ffmpeg-audio-receipt.json",
        "android-python-linkage-receipt.json": work / "python/android-python-linkage-receipt.json",
    }, "android-next-native-inputs", profile_path)
    return candidate


def build_windows(work: Path, jobs: int) -> Path:
    # This profile pins a macOS universal compiler archive, not a Linux compiler.
    if platform.system() != "Darwin":
        raise ValueError("The admitted Windows compiler recipe requires macOS")
    profile_path = ROOT / "config/windows-ffmpeg-audio.json"
    profile = json.loads(profile_path.read_text())
    if f"{sys.version_info.major}.{sys.version_info.minor}" != profile["buildTools"]["python"]:
        raise ValueError("Use the reviewed Python minor version for this recipe")
    own_work(work)
    subprocess.run([sys.executable, str(ROOT / "scripts/build_windows_audio_runtime.py"),
                    "--work", str(work / "windows"), "--jobs", str(jobs)], check=True)
    archive = work / "windows/ffmpeg-windows-audio.zip"
    require_identity(archive, profile["derived"]["archive"])
    windows_audio_runtime.validate_directory(work / "windows/output", profile)
    candidate = work / "candidate"
    stage(candidate, {"ffmpeg-windows-audio.zip": archive, "derived.json": work / "windows/derived.json"},
          "windows-next-native-inputs", profile_path)
    return candidate


def unpack_windows(archive: Path, output: Path, profile: dict | None = None) -> None:
    """Authenticate the CI transfer against repository pins before any extraction."""
    profile = profile or windows_audio_runtime.load_profile()
    require_identity(archive, profile["derived"]["archive"])
    files = windows_audio_runtime.pinned_files(profile)
    if output.exists() or output.is_symlink():
        raise ValueError("Use a new Windows candidate output directory")
    with zipfile.ZipFile(archive) as source:
        entries = source.infolist()
        if len(entries) != len(files) or {entry.filename for entry in entries} != set(files):
            raise ValueError("Transferred candidate archive has an unexpected file set")
        if any(entry.is_dir() or stat.S_ISLNK(entry.external_attr >> 16) or
               entry.file_size != files[entry.filename]["bytes"] for entry in entries):
            raise ValueError("Transferred candidate archive metadata differs from pins")
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=output.parent, prefix="native-candidate-") as temporary:
            staged = Path(temporary) / "runtime"
            staged.mkdir()
            # Every path came from pinned_files, which rejects escaping paths.
            source.extractall(staged)
            windows_audio_runtime.validate_directory(staged, profile)
            staged.replace(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("android", "windows"):
        command = commands.add_parser(name)
        command.add_argument("--work", type=Path, required=True)
        command.add_argument("--jobs", type=int, choices=range(1, 9), default=4)
        if name == "android":
            command.add_argument("--ndk", type=Path, required=True)
    unpack = commands.add_parser("unpack-windows")
    unpack.add_argument("--archive", type=Path, required=True)
    unpack.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "unpack-windows":
        unpack_windows(args.archive, args.output)
    elif args.command == "android":
        print(build_android(args.work.absolute(), args.ndk.absolute(), args.jobs))
    else:
        print(build_windows(args.work.absolute(), args.jobs))


if __name__ == "__main__":
    main()
