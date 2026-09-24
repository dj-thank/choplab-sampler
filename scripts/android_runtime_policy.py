"""Repository-owned APK runtime pins, derived from verified upstream Maven AARs.

Admission proves byte identity, not license completeness or runtime behavior.
No manifest inside an APK is read as a trust source. Updating these pins requires
reviewing the upstream artifact and its members again; this helper only verifies.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from functools import lru_cache
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import urllib.request
import zipfile

PIN_FILE = Path(__file__).with_name("android_runtime_pins.json")
COORDINATES = frozenset({
    "io.github.junkfood02.youtubedl-android:library:0.18.1",
    "io.github.junkfood02.youtubedl-android:common:0.18.1",
    "io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1",
    "com.microsoft.onnxruntime:onnxruntime-android:1.29.0",
})
NATIVE_PATH = re.compile(r"lib/(arm64-v8a|armeabi-v7a|x86|x86_64)/lib[A-Za-z0-9_.-]+\.so")
RAW_PATH = re.compile(r"res/(?:raw/)?[A-Za-z0-9_][A-Za-z0-9_.-]{0,79}")


@dataclass(frozen=True)
class RuntimePin:
    coordinate: str
    aar_member: str
    apk_path: str | None
    kind: str
    size: int
    sha256: str

    @property
    def identity(self) -> str:
        return self.coordinate + ":" + self.aar_member


@lru_cache(maxsize=1)
def load_pins() -> tuple[RuntimePin, ...]:
    if PIN_FILE.stat().st_size > 64 * 1024:
        raise ValueError("Android runtime pin file exceeds its bound")
    raw = json.loads(PIN_FILE.read_text(encoding="utf-8"))
    if raw.get("schema") != 1 or {s["coordinate"] for s in raw["sources"]} != COORDINATES:
        raise ValueError("Unknown Android runtime source set")
    for source in raw["sources"]:
        group, artifact, version = source["coordinate"].split(":")
        expected = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.aar"
        if source["url"] != expected or not re.fullmatch(r"[0-9a-f]{64}", source["sha256"]):
            raise ValueError("Invalid Android runtime source identity")
    pins = tuple(RuntimePin(**member) for member in raw["members"])
    if not pins or len(pins) > 64 or len({p.identity for p in pins}) != len(pins):
        raise ValueError("Invalid or duplicate Android runtime pin set")
    paths = set()
    for pin in pins:
        if pin.coordinate not in COORDINATES or not 0 < pin.size <= 64 * 1024 * 1024 or not re.fullmatch(r"[0-9a-f]{64}", pin.sha256):
            raise ValueError("Invalid Android runtime member identity")
        if pin.kind == "raw_ytdlp":
            if pin.coordinate != "io.github.junkfood02.youtubedl-android:library:0.18.1" or pin.aar_member != "res/raw/ytdlp" or pin.apk_path is not None:
                raise ValueError("Unknown raw runtime resource")
        elif pin.kind in {"native_elf", "native_zip"}:
            if pin.apk_path is None or NATIVE_PATH.fullmatch(pin.apk_path) is None or pin.aar_member != "jni/" + pin.apk_path[4:]:
                raise ValueError("Invalid Android runtime ABI/path")
            if pin.apk_path in paths:
                raise ValueError("Duplicate Android runtime native path")
            paths.add(pin.apk_path)
        else:
            raise ValueError("Unknown Android runtime kind")
    return pins


def runtime_candidate(name: str, size: int, pins: tuple[RuntimePin, ...]) -> RuntimePin | None:
    """Pick a bounded candidate; admission still requires its full exact digest."""
    path = PurePosixPath(name)
    known_names = {PurePosixPath(p.apk_path).name for p in pins if p.apk_path}
    for pin in pins:
        if pin.apk_path == name:
            if size != pin.size:
                raise ValueError("Pinned Android runtime size mismatch")
            return pin
    if path.name in known_names:
        raise ValueError("Pinned Android runtime occurs outside its exact ABI/path")
    for pin in pins:
        if pin.kind == "raw_ytdlp" and RAW_PATH.fullmatch(name) and size == pin.size:
            return pin
    return None


def verify_runtime_content(pin: RuntimePin, content: bytes) -> bool:
    if len(content) != pin.size or hashlib.sha256(content).hexdigest() != pin.sha256:
        return False
    if pin.kind == "native_elf":
        return content.startswith(b"\x7fELF")
    if pin.kind == "native_zip":
        return content.startswith(b"PK\x03\x04")
    return pin.kind == "raw_ytdlp" and content.startswith(b"#!/usr/bin/env python")


def valid_resource_table(content: bytes) -> bool:
    """Bounded Android ResTable header/chunk check; callers still scan all bytes."""
    if len(content) < 12:
        return False
    kind, header_size, size, package_count = struct.unpack_from("<HHII", content)
    if kind != 2 or header_size != 12 or size != len(content) or not 1 <= package_count <= 256:
        return False
    offset, packages, chunks = header_size, 0, 0
    while offset < size:
        if offset + 8 > size or chunks >= 4096:
            return False
        kind, child_header, child_size = struct.unpack_from("<HHI", content, offset)
        if kind not in {1, 0x200} or child_header < 8 or child_size < child_header or offset + child_size > size:
            return False
        if kind == 0x200:
            packages += 1
        offset += child_size
        chunks += 1
    return offset == size and packages == package_count


def verify_cache(cache_root: Path, *, verify_origin: bool = False) -> None:
    pins = load_pins()
    raw = json.loads(PIN_FILE.read_text(encoding="utf-8"))
    for source in raw["sources"]:
        group, artifact, version = source["coordinate"].split(":")
        candidates = list((cache_root / group / artifact / version).glob("*/*.aar"))
        if len(candidates) != 1:
            raise ValueError("Expected one cached AAR for " + source["coordinate"])
        aar = candidates[0]
        with aar.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != source["sha256"]:
            raise ValueError("Upstream AAR SHA-256 mismatch: " + source["coordinate"])
        if verify_origin:
            algorithm = source["checksum_algorithm"]
            expected_url = source["url"] + "." + algorithm
            if algorithm not in {"sha1", "sha256"} or source["checksum_url"] != expected_url:
                raise ValueError("Invalid pinned upstream checksum URL")
            with urllib.request.urlopen(expected_url, timeout=20) as response:
                published = response.read(513)
            if len(published) > 512 or published.decode("ascii").strip().split()[0] != source["checksum"]:
                raise ValueError("Published upstream checksum changed")
            with aar.open("rb") as stream:
                if hashlib.file_digest(stream, algorithm).hexdigest() != source["checksum"]:
                    raise ValueError("Cached AAR differs from official checksum")
        with zipfile.ZipFile(aar) as archive:
            for pin in (p for p in pins if p.coordinate == source["coordinate"]):
                matches = [entry for entry in archive.infolist() if entry.filename == pin.aar_member]
                if len(matches) != 1 or matches[0].file_size != pin.size:
                    raise ValueError("AAR member size/count mismatch")
                with archive.open(matches[0]) as stream:
                    content = stream.read(pin.size + 1)
                if not verify_runtime_content(pin, content):
                    raise ValueError("AAR member SHA-256/type mismatch")
        print("Verified pinned AAR and admitted member bytes:", source["coordinate"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-cache", type=Path, required=True)
    parser.add_argument("--verify-origin", action="store_true")
    arguments = parser.parse_args()
    verify_cache(arguments.verify_cache, verify_origin=arguments.verify_origin)
