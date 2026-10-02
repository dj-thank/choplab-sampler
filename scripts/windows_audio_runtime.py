"""Identity checks for the opt-in, source-built Windows FFmpeg payload."""
import hashlib
import json
from pathlib import Path, PurePosixPath

PIN_PATH = Path(__file__).resolve().parents[1] / "config/windows-ffmpeg-audio.json"


def load_profile():
    return json.loads(PIN_PATH.read_text(encoding="utf-8"))


def inputs_hash(profile):
    inputs = {key: value for key, value in profile.items() if key != "derived"}
    return hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def pinned_files(profile=None):
    profile = profile or load_profile()
    derived = profile.get("derived")
    if not isinstance(derived, dict) or derived.get("profileInputsSha256") != inputs_hash(profile):
        raise ValueError("Windows FFmpeg candidate is not bound to its source/build pins")
    files = derived.get("files")
    if not isinstance(files, dict) or not {"ffmpeg.exe", "ffprobe.exe", "FFmpeg-runtime.json", "FFmpeg-SOURCES.json", "FFmpeg-LICENSE.txt"} <= files.keys():
        raise ValueError("Incomplete Windows FFmpeg artifact pins")
    for name, pin in files.items():
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name or path.as_posix() != name:
            raise ValueError("Unsafe Windows FFmpeg artifact path")
        if not isinstance(pin, dict) or set(pin) != {"bytes", "sha256"}:
            raise ValueError("Malformed Windows FFmpeg artifact identity")
    return files


def validate_directory(directory, profile=None):
    profile = profile or load_profile()
    files = pinned_files(profile)
    directory = Path(directory)
    entries = list(directory.rglob("*"))
    if any(path.is_symlink() for path in entries):
        raise ValueError("Windows FFmpeg candidate contains a symlink")
    actual = {path.relative_to(directory).as_posix() for path in entries if path.is_file()}
    if actual != set(files):
        raise ValueError("Windows FFmpeg candidate file set differs from the pins")
    for name, expected in files.items():
        path = directory / name
        with path.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if path.stat().st_size != expected["bytes"] or digest != expected["sha256"]:
            raise ValueError("Windows FFmpeg candidate identity differs: " + name)
    source = json.loads((directory / "FFmpeg-SOURCES.json").read_text())
    if source != {key: value for key, value in profile.items() if key != "derived"}:
        raise ValueError("Windows FFmpeg source receipt differs")
    return files
