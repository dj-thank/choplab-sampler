#!/usr/bin/env python3
"""Fail-closed check for credentials, signing material, and user audio in public content."""

from __future__ import annotations

import argparse
import bz2
import lzma
import re
import struct
import subprocess
import sys
import zipfile
import zlib
from dataclasses import dataclass
from io import BytesIO
from pathlib import Path, PurePosixPath


SENSITIVE_SUFFIXES = {
    ".cer",
    ".crt",
    ".jks",
    ".keystore",
    ".key",
    ".mobileprovision",
    ".p12",
    ".pfx",
    ".pem",
    ".private",
    ".provisionprofile",
}
AUDIO_SUFFIXES = {
    ".aac",
    ".aiff",
    ".caf",
    ".flac",
    ".m4a",
    ".mp3",
    ".ogg",
    ".opus",
    ".wav",
}
ARCHIVE_BINARY_SUFFIXES = AUDIO_SUFFIXES | SENSITIVE_SUFFIXES | {
    ".a",
    ".bin",
    ".class",
    ".dex",
    ".dll",
    ".dylib",
    ".exe",
    ".gif",
    ".ico",
    ".jpeg",
    ".jpg",
    ".lib",
    ".o",
    ".pdf",
    ".png",
    ".so",
    ".webp",
}
ZIP_NESTED_ARCHIVE_SUFFIXES = {".aab", ".aar", ".apk", ".ipa", ".jar", ".war", ".zip"}
UNSUPPORTED_NESTED_ARCHIVE_SUFFIXES = {".7z", ".bz2", ".gz", ".tar", ".tgz", ".xz"}
ZIP_MEMBER_SCAN_LIMIT = 512 * 1024
ZIP_TOTAL_SCAN_LIMIT = 4 * 1024 * 1024
ZIP_COMPRESSION_RATIO_LIMIT = 100
ZIP_ENTRY_COUNT_LIMIT = 4_096
ZIP_LZMA_DICTIONARY_LIMIT = 16 * 1024 * 1024
HISTORICAL_ZIP_BLOB_COUNT_LIMIT = 128
HISTORICAL_ZIP_CONTAINER_LIMIT = 16 * 1024 * 1024
HISTORICAL_ZIP_TOTAL_LIMIT = 64 * 1024 * 1024
HISTORICAL_NON_COMMIT_TREE_LIMIT = 128
ZIP_NESTED_DEPTH_LIMIT = 3
ZIP_NESTED_ARCHIVE_COUNT_LIMIT = 64
ZIP_NESTED_MEMBER_LIMIT = 16 * 1024 * 1024
ZIP_NESTED_TOTAL_LIMIT = 64 * 1024 * 1024
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"gh" + r"o_[A-Za-z0-9_]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
]
ZIP_LOCAL_FILE_HEADER = struct.Struct("<4s5H3L2H")
ZIP_LOCAL_FILE_SIGNATURE = b"PK\x03\x04"
ZIP_DATA_DESCRIPTOR = struct.Struct("<3L")
ZIP_DATA_DESCRIPTOR_SIGNATURE = b"PK\x07\x08"
ZIP_END_OF_CENTRAL_DIRECTORY = struct.Struct("<4s4H2LH")
ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = b"PK\x05\x06"
ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER = struct.Struct("<4s6H3L5H2L")
ZIP_CENTRAL_DIRECTORY_ENTRY_SIGNATURE = b"PK\x01\x02"
ZIP_MAX_COMMENT_LENGTH = (1 << 16) - 1
ZIP64_UINT16_SENTINEL = (1 << 16) - 1
ZIP64_UINT32_SENTINEL = (1 << 32) - 1
ZIP_MEMBER_INPUT_CHUNK = 64 * 1024
ZIP_BINARY_PROBE_MEMBER_INPUT_LIMIT = 64 * 1024
ZIP_BINARY_PROBE_TOTAL_INPUT_LIMIT = 256 * 1024
ZIP_JIMAGE_HEADER = struct.Struct("<7I")
ZIP_JIMAGE_MAGIC = 0xCAFEDADA


@dataclass
class ZipNestedScanBudget:
    archive_count: int = 0
    compressed_bytes: int = 0
    uncompressed_bytes: int = 0


def run_git(arguments: list[str]) -> bytes:
    return subprocess.run(
        ["git", *arguments],
        check=True,
        capture_output=True,
    ).stdout


def public_candidate_paths() -> list[PurePosixPath]:
    result = run_git(["ls-files", "-co", "--exclude-standard", "-z"])
    return [PurePosixPath(raw.decode("utf-8")) for raw in result.split(b"\0") if raw]


def historical_objects() -> list[tuple[str, PurePosixPath]]:
    objects: list[tuple[str, PurePosixPath]] = []
    for raw_line in run_git(["rev-list", "--objects", "--all"]).splitlines():
        parts = raw_line.decode("utf-8", errors="replace").split(" ", maxsplit=1)
        if len(parts) == 2 and parts[1]:
            objects.append((parts[0], PurePosixPath(parts[1])))
    return objects


def historical_paths() -> list[PurePosixPath]:
    return [path for _, path in historical_objects()]


def historical_zip_objects() -> list[tuple[str, PurePosixPath]]:
    """Return every blob version ever reachable through a path ending in .zip.

    `rev-list --objects` provides only one path hint for a shared object, so a blob
    reachable as both `copy.dat` and `removed.zip` cannot be selected reliably from
    that output. Raw history records retain the path paired with each old/new blob.
    """
    raw = run_git(
        [
            "log",
            "--all",
            "-m",
            "--format=",
            "--raw",
            "--no-abbrev",
            "--no-renames",
            "--root",
            "-z",
            "--",
            ":(icase,glob)**/*.zip",
        ]
    )
    fields = raw.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    if len(fields) % 2:
        raise RuntimeError("unexpected NUL-delimited git raw history output")

    candidates: dict[str, PurePosixPath] = {}
    for index in range(0, len(fields), 2):
        header = fields[index].decode("ascii", errors="replace").strip().split()
        path = PurePosixPath(fields[index + 1].decode("utf-8", errors="replace"))
        if path.suffix.lower() != ".zip":
            continue
        if len(header) != 5 or not header[0].startswith(":"):
            raise RuntimeError("unexpected git raw history record")

        old_mode = header[0][1:]
        new_mode, old_id, new_id = header[1:4]
        for mode, object_id in ((old_mode, old_id), (new_mode, new_id)):
            if mode not in {"100644", "100755"}:
                continue
            if object_id == "0" * 40:
                continue
            candidates.setdefault(object_id, path)
    return list(candidates.items())


def historical_non_commit_tree_zip_objects() -> tuple[
    list[tuple[str, PurePosixPath]],
    list[str],
]:
    raw_refs = run_git(
        [
            "for-each-ref",
            "--format=%(objectname)%00%(objecttype)%00%(*objectname)%00%(*objecttype)%00",
        ]
    ).replace(b"\0\n", b"\0")
    fields = raw_refs.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    if len(fields) % 4:
        return [], ["git history ZIP content: invalid non-commit ref metadata"]

    tree_ids: set[str] = set()
    for index in range(0, len(fields), 4):
        object_id, object_type, peeled_id, peeled_type = (
            field.decode("ascii", errors="replace").strip()
            for field in fields[index : index + 4]
        )
        if object_type == "tree":
            tree_ids.add(object_id)
        if peeled_type == "tree":
            tree_ids.add(peeled_id)
    tree_ids.discard("")
    if len(tree_ids) > HISTORICAL_NON_COMMIT_TREE_LIMIT:
        return [], [
            "git history ZIP content: "
            f"{len(tree_ids)} non-commit trees exceed the "
            f"{HISTORICAL_NON_COMMIT_TREE_LIMIT}-tree scan limit"
        ]

    candidates: dict[str, PurePosixPath] = {}
    findings: list[str] = []
    for tree_id in sorted(tree_ids):
        raw_tree = run_git(["ls-tree", "-rz", "-r", "--full-tree", tree_id])
        for record in raw_tree.split(b"\0"):
            if not record:
                continue
            try:
                metadata, raw_path = record.split(b"\t", maxsplit=1)
                mode, object_type, object_id = metadata.decode("ascii").split()
            except (UnicodeDecodeError, ValueError):
                findings.append(
                    "git history ZIP content: invalid non-commit tree record"
                )
                continue
            path = PurePosixPath(raw_path.decode("utf-8", errors="replace"))
            if (
                mode in {"100644", "100755"}
                and object_type == "blob"
                and path.suffix.lower() == ".zip"
            ):
                candidates.setdefault(object_id, path)
    return list(candidates.items()), findings


def suspicious_path(path: PurePosixPath) -> str | None:
    lower = path.as_posix().lower()
    name = path.name.lower()
    if name in {".env", ".env.local", ".env.production"}:
        return "environment file"
    if path.suffix.lower() in SENSITIVE_SUFFIXES:
        return "signing or private-key material"
    if path.suffix.lower() in AUDIO_SUFFIXES:
        return "audio asset"
    if lower.endswith("/google-services.json") or lower.endswith("/googleservice-info.plist"):
        return "provider credential file"
    return None


def is_known_archive_binary_path(path: PurePosixPath) -> bool:
    parts = tuple(part.lower() for part in path.parts)
    return parts[-3:] == ("runtime", "lib", "ct.sym")


def is_app_main_executable_path(path: PurePosixPath) -> bool:
    app_directory = path.parent.name
    return (
        app_directory.lower().endswith(".app")
        and path.name == app_directory[:-4]
    )


def is_jdk_modules_path(path: PurePosixPath) -> bool:
    parts = tuple(part.lower() for part in path.parts)
    return parts[-3:] == ("runtime", "lib", "modules")


def is_icu_data_path(path: PurePosixPath) -> bool:
    return path.name.lower() == "icudtl.dat"


def decode_text_for_secret_scan(content: bytes) -> str:
    # Credential patterns are ASCII. Replacement decoding preserves those bytes
    # even when unrelated malformed bytes appear elsewhere in safe-named text.
    if content.startswith((b"\xff\xfe\x00\x00", b"\x00\x00\xfe\xff")):
        return content.decode("utf-32", errors="replace")
    if content.startswith((b"\xff\xfe", b"\xfe\xff")):
        return content.decode("utf-16", errors="replace")
    return content.decode("utf-8-sig", errors="replace")


def audio_payload_signature(content: bytes) -> str | None:
    if len(content) >= 12 and content[:4] == b"RIFF" and content[8:12] == b"WAVE":
        return "RIFF/WAVE"
    if len(content) >= 12 and content[:4] == b"FORM" and content[8:12] in {
        b"AIFF",
        b"AIFC",
    }:
        return "AIFF"
    if content.startswith(b"fLaC"):
        return "FLAC"
    if content.startswith(b"OggS"):
        return "Ogg"
    if content.startswith(b"caff"):
        return "CAF"
    if content.startswith(b"ID3"):
        return "ID3/MP3"
    if len(content) >= 12 and content[4:8] == b"ftyp" and content[8:12] in {
        b"M4A ",
        b"M4B ",
    }:
        return "M4A"
    return None


def read_verified_zip_member(
    stream: object,
    info: zipfile.ZipInfo,
    *,
    payload_offset: int,
    output_limit: int,
    compressed_input_limit: int,
    lzma_dictionary_limit: int,
) -> bytes:
    """Decode exactly one declared payload without trusting ZipExtFile's size cap."""
    if info.file_size > output_limit:
        raise ValueError("declared uncompressed size exceeds the member scan limit")
    if info.compress_size > compressed_input_limit:
        raise ValueError("declared compressed payload exceeds the input scan limit")

    output = bytearray()

    def remaining_output_with_probe() -> int:
        return info.file_size - len(output) + 1

    def append_output(content: bytes) -> None:
        if len(output) + len(content) > info.file_size:
            raise ValueError("decompressed output exceeds declared uncompressed size")
        output.extend(content)

    decompressor: object | None
    lzma_header = bytearray()
    if info.compress_type == zipfile.ZIP_STORED:
        decompressor = None
    elif info.compress_type == zipfile.ZIP_DEFLATED:
        decompressor = zlib.decompressobj(-15)
    elif info.compress_type == zipfile.ZIP_BZIP2:
        decompressor = bz2.BZ2Decompressor()
    elif info.compress_type == zipfile.ZIP_LZMA:
        decompressor = None
    else:
        raise NotImplementedError(
            f"compression method {info.compress_type} is not supported"
        )

    def feed_compressed(content: bytes) -> None:
        nonlocal decompressor
        if info.compress_type == zipfile.ZIP_DEFLATED:
            pending = content
            while pending:
                assert decompressor is not None
                before = len(pending)
                append_output(
                    decompressor.decompress(
                        pending,
                        remaining_output_with_probe(),
                    )
                )
                pending = decompressor.unconsumed_tail
                if pending and len(pending) >= before:
                    raise ValueError("deflate decompressor made no input progress")
            return

        if info.compress_type == zipfile.ZIP_LZMA and decompressor is None:
            lzma_header.extend(content)
            if len(lzma_header) < 4:
                return
            properties_size = struct.unpack_from("<H", lzma_header, 2)[0]
            if properties_size != 5:
                raise ValueError("invalid ZIP LZMA property length")
            header_size = 4 + properties_size
            if len(lzma_header) < header_size:
                return
            properties = bytes(lzma_header[4:header_size])
            property_code = properties[0]
            if property_code >= 9 * 5 * 5:
                raise ValueError("invalid ZIP LZMA filter properties")
            dictionary_size = int.from_bytes(properties[1:], "little")
            if dictionary_size > lzma_dictionary_limit:
                raise ValueError(
                    f"ZIP LZMA dictionary exceeds the "
                    f"{lzma_dictionary_limit}-byte memory limit"
                )
            literal_context_bits = property_code % 9
            remainder = property_code // 9
            literal_position_bits = remainder % 5
            position_bits = remainder // 5
            decompressor = lzma.LZMADecompressor(
                format=lzma.FORMAT_RAW,
                filters=[
                    {
                        "id": lzma.FILTER_LZMA1,
                        "dict_size": dictionary_size,
                        "lc": literal_context_bits,
                        "lp": literal_position_bits,
                        "pb": position_bits,
                    }
                ],
            )
            content = bytes(lzma_header[header_size:])
            lzma_header.clear()

        if decompressor is None:
            return
        append_output(
            decompressor.decompress(
                content,
                remaining_output_with_probe(),
            )
        )
        while not decompressor.needs_input and not decompressor.eof:
            before = len(output)
            append_output(
                decompressor.decompress(
                    b"",
                    remaining_output_with_probe(),
                )
            )
            if len(output) == before and not decompressor.needs_input:
                raise ValueError("decompressor made no output progress")

    stream.seek(payload_offset)
    compressed_remaining = info.compress_size
    while compressed_remaining:
        chunk = stream.read(min(compressed_remaining, ZIP_MEMBER_INPUT_CHUNK))
        if not chunk:
            raise ValueError("declared compressed payload is truncated")
        compressed_remaining -= len(chunk)
        if info.compress_type == zipfile.ZIP_STORED:
            append_output(chunk)
            continue
        if decompressor is not None and getattr(decompressor, "eof", False):
            raise ValueError(
                "declared compressed payload contains trailing or unconsumed data"
            )
        feed_compressed(chunk)
        if decompressor is not None and (
            getattr(decompressor, "unused_data", b"") or
            (getattr(decompressor, "eof", False) and compressed_remaining)
        ):
            raise ValueError(
                "declared compressed payload contains trailing or unconsumed data"
            )

    if info.compress_type != zipfile.ZIP_STORED:
        if decompressor is None:
            raise ValueError("compressed payload is missing its decoder metadata")
        if info.compress_type == zipfile.ZIP_DEFLATED:
            append_output(decompressor.flush(remaining_output_with_probe()))
        if not getattr(decompressor, "eof", False):
            raise ValueError("declared compressed payload ended before stream EOF")
        if getattr(decompressor, "unused_data", b""):
            raise ValueError(
                "declared compressed payload contains trailing or unconsumed data"
            )
    if len(output) != info.file_size:
        raise ValueError("decompressed output does not match declared uncompressed size")
    if (zlib.crc32(output) & ZIP64_UINT32_SENTINEL) != info.CRC:
        raise ValueError("decompressed output CRC does not match central directory")
    return bytes(output)


def read_zip_binary_prefix(
    stream: object,
    info: zipfile.ZipInfo,
    *,
    payload_offset: int,
    compressed_input_limit: int,
) -> tuple[bytes, int]:
    """Read only enough bounded stored/deflate input to classify known binaries."""
    probe_size = ZIP_JIMAGE_HEADER.size
    if compressed_input_limit <= 0:
        raise ValueError("archive binary probe input budget is exhausted")
    stream.seek(payload_offset)

    if info.compress_type == zipfile.ZIP_STORED:
        read_size = min(info.compress_size, probe_size, compressed_input_limit)
        prefix = stream.read(read_size)
        if len(prefix) != read_size:
            raise ValueError("stored binary probe payload is truncated")
        return prefix, read_size

    if info.compress_type != zipfile.ZIP_DEFLATED:
        return b"", 0

    decompressor = zlib.decompressobj(-15)
    output = bytearray()
    compressed_remaining = info.compress_size
    consumed = 0
    while (
        len(output) < probe_size
        and compressed_remaining
        and consumed < compressed_input_limit
    ):
        read_size = min(
            compressed_remaining,
            ZIP_MEMBER_INPUT_CHUNK,
            compressed_input_limit - consumed,
        )
        chunk = stream.read(read_size)
        if not chunk:
            raise ValueError("deflate binary probe payload is truncated")
        compressed_remaining -= len(chunk)
        consumed += len(chunk)
        output.extend(decompressor.decompress(chunk, probe_size - len(output)))
        if decompressor.eof:
            break
    return bytes(output), consumed


def has_valid_jimage_header(content: bytes, file_size: int) -> bool:
    if len(content) < ZIP_JIMAGE_HEADER.size:
        return False
    (
        magic,
        version,
        flags,
        resource_count,
        table_length,
        locations_size,
        strings_size,
    ) = ZIP_JIMAGE_HEADER.unpack_from(content)
    index_size = (
        ZIP_JIMAGE_HEADER.size
        + table_length * 2 * struct.calcsize("<I")
        + locations_size
        + strings_size
    )
    return (
        magic == ZIP_JIMAGE_MAGIC
        and version >> 16 == 1
        and flags == 0
        and 0 < resource_count <= table_length
        and index_size <= file_size
    )


def has_valid_icu_data_header(content: bytes, file_size: int) -> bool:
    if len(content) < 24:
        return False
    header_size = int.from_bytes(content[0:2], "little")
    info_size = int.from_bytes(content[4:6], "little")
    return (
        content[2:4] == b"\xda\x27"
        and 24 <= header_size <= file_size
        and 20 <= info_size <= header_size
        and content[12:16] == b"CmnD"
    )


def has_valid_large_binary_header(
    path: PurePosixPath,
    content: bytes,
    file_size: int,
) -> bool:
    if is_jdk_modules_path(path):
        return has_valid_jimage_header(content, file_size)
    if is_icu_data_path(path):
        return has_valid_icu_data_header(content, file_size)
    return False


def preflight_zip_directory(
    source: Path | PurePosixPath | BytesIO,
    *,
    archive_label: str,
    entry_count_limit: int,
    total_scan_limit: int,
) -> list[str]:
    """Bound central-directory allocation before ZipFile constructs ZipInfo objects."""
    tail_limit = ZIP_END_OF_CENTRAL_DIRECTORY.size + ZIP_MAX_COMMENT_LENGTH
    try:
        if isinstance(source, BytesIO):
            original_position = source.tell()
            try:
                source.seek(0, 2)
                archive_size = source.tell()
                tail_offset = max(0, archive_size - tail_limit)
                source.seek(tail_offset)
                tail = source.read(tail_limit)
            finally:
                source.seek(original_position)
        else:
            archive_path = Path(source)
            archive_size = archive_path.stat().st_size
            tail_offset = max(0, archive_size - tail_limit)
            with archive_path.open("rb") as stream:
                stream.seek(tail_offset)
                tail = stream.read(tail_limit)
    except (OSError, ValueError) as error:
        return [
            f"{archive_label}: archive directory preflight failed "
            f"({type(error).__name__})"
        ]

    search_end = len(tail)
    directory_fields: tuple[bytes, int, int, int, int, int, int, int] | None = None
    directory_offset = 0
    while search_end:
        candidate = tail.rfind(
            ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE,
            0,
            search_end,
        )
        if candidate < 0:
            break
        if candidate + ZIP_END_OF_CENTRAL_DIRECTORY.size <= len(tail):
            try:
                fields = ZIP_END_OF_CENTRAL_DIRECTORY.unpack_from(tail, candidate)
            except struct.error:
                fields = None
            if fields is not None:
                comment_length = fields[-1]
                absolute_offset = tail_offset + candidate
                if (
                    absolute_offset
                    + ZIP_END_OF_CENTRAL_DIRECTORY.size
                    + comment_length
                    == archive_size
                ):
                    directory_fields = fields
                    directory_offset = absolute_offset
                    break
        search_end = candidate

    if directory_fields is None:
        return [f"{archive_label}: bounded end-of-central-directory record not found"]

    (
        _,
        disk_number,
        central_directory_disk,
        entries_on_disk,
        entry_count,
        central_directory_size,
        central_directory_offset,
        _,
    ) = directory_fields
    if (
        disk_number != 0
        or central_directory_disk != 0
        or entries_on_disk != entry_count
    ):
        return [f"{archive_label}: multi-disk ZIP archives are not supported"]
    if (
        entry_count == ZIP64_UINT16_SENTINEL
        or central_directory_size == ZIP64_UINT32_SENTINEL
        or central_directory_offset == ZIP64_UINT32_SENTINEL
    ):
        return [f"{archive_label}: ZIP64 directory metadata exceeds bounded scan policy"]
    if entry_count > entry_count_limit:
        return [
            f"{archive_label}: archive contains {entry_count} entries, exceeding "
            f"the {entry_count_limit}-entry content scan limit"
        ]
    maximum_directory_size = (
        ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER.size * entry_count + total_scan_limit
    )
    if central_directory_size > maximum_directory_size:
        return [
            f"{archive_label}: central directory metadata exceeds the "
            f"{total_scan_limit}-byte total content scan limit before parsing"
        ]
    claimed_directory_end = central_directory_offset + central_directory_size
    if claimed_directory_end > directory_offset:
        return [f"{archive_label}: central directory bounds are invalid"]
    if claimed_directory_end < directory_offset:
        return [f"{archive_label}: archive contains unclaimed prefix bytes"]

    central_directory_start = directory_offset - central_directory_size
    try:
        if isinstance(source, BytesIO):
            original_position = source.tell()
            try:
                source.seek(central_directory_start)
                central_directory = source.read(central_directory_size)
            finally:
                source.seek(original_position)
        else:
            with Path(source).open("rb") as stream:
                stream.seek(central_directory_start)
                central_directory = stream.read(central_directory_size)
    except (OSError, ValueError) as error:
        return [
            f"{archive_label}: central directory preflight failed "
            f"({type(error).__name__})"
        ]
    if len(central_directory) != central_directory_size:
        return [f"{archive_label}: central directory is truncated"]

    record_offset = 0
    parsed_entry_count = 0
    minimum_local_header_offset: int | None = None
    while record_offset < len(central_directory):
        if (
            record_offset + ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER.size
            > len(central_directory)
        ):
            return [f"{archive_label}: central directory entry header is truncated"]
        try:
            record = ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER.unpack_from(
                central_directory,
                record_offset,
            )
        except struct.error:
            return [f"{archive_label}: central directory entry header is invalid"]
        if record[0] != ZIP_CENTRAL_DIRECTORY_ENTRY_SIGNATURE:
            return [f"{archive_label}: central directory entry signature is invalid"]
        filename_length, extra_length, comment_length = record[10:13]
        local_header_offset = record[16]
        if (
            record[8] == ZIP64_UINT32_SENTINEL
            or record[9] == ZIP64_UINT32_SENTINEL
            or local_header_offset == ZIP64_UINT32_SENTINEL
        ):
            return [
                f"{archive_label}: ZIP64 entry metadata exceeds bounded scan policy"
            ]
        minimum_local_header_offset = (
            local_header_offset
            if minimum_local_header_offset is None
            else min(minimum_local_header_offset, local_header_offset)
        )
        record_offset += (
            ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER.size
            + filename_length
            + extra_length
            + comment_length
        )
        if record_offset > len(central_directory):
            return [f"{archive_label}: central directory entry metadata is truncated"]
        parsed_entry_count += 1
        if parsed_entry_count > entry_count_limit:
            return [
                f"{archive_label}: archive contains at least {parsed_entry_count} "
                f"entries, exceeding the {entry_count_limit}-entry content scan limit"
            ]
    if parsed_entry_count != entry_count:
        return [
            f"{archive_label}: central directory contains {parsed_entry_count} entries "
            f"but its end record declares {entry_count}"
        ]
    if minimum_local_header_offset not in {None, 0}:
        return [f"{archive_label}: archive contains unclaimed prefix bytes"]
    if parsed_entry_count == 0 and central_directory_offset != 0:
        return [f"{archive_label}: empty archive contains unclaimed prefix bytes"]
    return []


def scan_zip(
    source: Path | PurePosixPath | BytesIO,
    *,
    label: str | None = None,
    member_scan_limit: int = ZIP_MEMBER_SCAN_LIMIT,
    total_scan_limit: int = ZIP_TOTAL_SCAN_LIMIT,
    compressed_input_limit: int = ZIP_TOTAL_SCAN_LIMIT,
    compression_ratio_limit: int = ZIP_COMPRESSION_RATIO_LIMIT,
    entry_count_limit: int = ZIP_ENTRY_COUNT_LIMIT,
    lzma_dictionary_limit: int = ZIP_LZMA_DICTIONARY_LIMIT,
    _nested_depth: int = 0,
    _nested_budget: ZipNestedScanBudget | None = None,
) -> list[str]:
    findings: list[str] = []
    scanned_bytes = 0
    scanned_compressed_bytes = 0
    scanned_binary_probe_bytes = 0
    archive_label = label or str(source)
    nested_budget = _nested_budget or ZipNestedScanBudget()

    preflight_findings = preflight_zip_directory(
        source,
        archive_label=archive_label,
        entry_count_limit=entry_count_limit,
        total_scan_limit=total_scan_limit,
    )
    if preflight_findings:
        return preflight_findings

    def scan_metadata(metadata_label: str, content: bytes) -> bool:
        nonlocal scanned_bytes
        if scanned_bytes + len(content) > total_scan_limit:
            findings.append(
                f"{archive_label}: archive metadata exceeds "
                f"{total_scan_limit}-byte total content scan limit"
            )
            return False
        scanned_bytes += len(content)
        findings.extend(scan_text(metadata_label, decode_text_for_secret_scan(content)))
        return True

    try:
        with zipfile.ZipFile(source) as archive:
            entries = archive.infolist()
            claimed_ranges: list[tuple[int, int]] = []
            ordered_local_offsets = sorted({info.header_offset for info in entries})
            next_record_offsets = {
                offset: (
                    ordered_local_offsets[index + 1]
                    if index + 1 < len(ordered_local_offsets)
                    else archive.start_dir
                )
                for index, offset in enumerate(ordered_local_offsets)
            }
            if len(entries) > entry_count_limit:
                findings.append(
                    f"{archive_label}: archive contains {len(entries)} entries, exceeding "
                    f"the {entry_count_limit}-entry content scan limit"
                )
                return findings

            if not scan_metadata(
                f"{archive_label}: archive comment",
                archive.comment,
            ):
                return findings

            for info in entries:
                name = info.filename
                entry = PurePosixPath(name)
                reason = suspicious_path(entry)
                if reason:
                    findings.append(f"{archive_label}: archive entry {name!r}: {reason}")
                if not scan_metadata(
                    f"{archive_label}: archive entry {name!r} filename",
                    name.encode("utf-8", errors="replace"),
                ):
                    break
                if not scan_metadata(
                    f"{archive_label}: archive entry {name!r} comment",
                    info.comment,
                ):
                    break
                if not scan_metadata(
                    f"{archive_label}: archive entry {name!r} extra field",
                    info.extra,
                ):
                    break

                stream = archive.fp
                original_position: int | None = None
                local_name = b""
                local_extra = b""
                local_header_fields: tuple[
                    bytes,
                    int,
                    int,
                    int,
                    int,
                    int,
                    int,
                    int,
                    int,
                    int,
                    int,
                ] | None = None
                local_header_valid = False
                local_payload_offset: int | None = None
                local_record_end: int | None = None
                try:
                    if stream is None:
                        raise ValueError("archive stream is unavailable")
                    original_position = stream.tell()
                    stream.seek(info.header_offset)
                    header = stream.read(ZIP_LOCAL_FILE_HEADER.size)
                    if len(header) != ZIP_LOCAL_FILE_HEADER.size:
                        raise ValueError("truncated local file header")
                    fields = ZIP_LOCAL_FILE_HEADER.unpack(header)
                    if fields[0] != ZIP_LOCAL_FILE_SIGNATURE:
                        raise ValueError("invalid local file header signature")
                    local_header_fields = fields
                    name_length, extra_length = fields[-2:]
                    local_name = stream.read(name_length)
                    local_extra = stream.read(extra_length)
                    if len(local_name) != name_length or len(local_extra) != extra_length:
                        raise ValueError("truncated local file header metadata")
                    local_payload_offset = stream.tell()
                    payload_end = local_payload_offset + info.compress_size
                    local_record_end = payload_end
                    if fields[2] & 0x08:
                        stream.seek(payload_end)
                        descriptor = stream.read(
                            len(ZIP_DATA_DESCRIPTOR_SIGNATURE)
                            + ZIP_DATA_DESCRIPTOR.size
                        )
                        expected_descriptor = (
                            info.CRC,
                            info.compress_size,
                            info.file_size,
                        )
                        descriptor_sizes: list[int] = []
                        if (
                            len(descriptor) >= ZIP_DATA_DESCRIPTOR.size
                            and ZIP_DATA_DESCRIPTOR.unpack_from(descriptor, 0)
                            == expected_descriptor
                        ):
                            descriptor_sizes.append(ZIP_DATA_DESCRIPTOR.size)
                        signed_descriptor_size = (
                            len(ZIP_DATA_DESCRIPTOR_SIGNATURE)
                            + ZIP_DATA_DESCRIPTOR.size
                        )
                        if (
                            len(descriptor) >= signed_descriptor_size
                            and descriptor.startswith(ZIP_DATA_DESCRIPTOR_SIGNATURE)
                            and ZIP_DATA_DESCRIPTOR.unpack_from(
                                descriptor,
                                len(ZIP_DATA_DESCRIPTOR_SIGNATURE),
                            )
                            == expected_descriptor
                        ):
                            descriptor_sizes.append(signed_descriptor_size)
                        if not descriptor_sizes:
                            raise ValueError(
                                "ZIP data descriptor does not match central directory"
                            )
                        if len(descriptor_sizes) > 1:
                            next_record_offset = next_record_offsets.get(
                                info.header_offset,
                                archive.start_dir,
                            )
                            boundary_matches = [
                                size
                                for size in descriptor_sizes
                                if payload_end + size == next_record_offset
                            ]
                            if len(boundary_matches) != 1:
                                raise ValueError("ZIP data descriptor layout is ambiguous")
                            required_size = boundary_matches[0]
                        else:
                            required_size = descriptor_sizes[0]
                        local_record_end += required_size
                    local_header_valid = True
                except (OSError, ValueError, struct.error) as error:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: local header metadata "
                        f"scan failed ({type(error).__name__})"
                    )
                finally:
                    if stream is not None and original_position is not None:
                        try:
                            stream.seek(original_position)
                        except (OSError, ValueError):
                            local_header_valid = False
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: local header "
                                "stream position restore failed"
                            )

                if not scan_metadata(
                    f"{archive_label}: archive entry {name!r} local header filename",
                    local_name,
                ):
                    break
                if not scan_metadata(
                    f"{archive_label}: archive entry {name!r} local header extra field",
                    local_extra,
                ):
                    break
                if local_header_valid and local_header_fields is not None:
                    filename_encoding = (
                        "utf-8" if info.flag_bits & 0x800 else "cp437"
                    )
                    try:
                        central_name = info.orig_filename.encode(filename_encoding)
                    except UnicodeEncodeError:
                        local_header_valid = False
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: central filename "
                            "cannot be reconstructed for local-header validation"
                        )
                    if local_header_valid and local_name != central_name:
                        local_header_valid = False
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: local header "
                            "filename does not match central directory"
                        )

                    local_flags = local_header_fields[2]
                    local_compression = local_header_fields[3]
                    if local_flags != info.flag_bits:
                        local_header_valid = False
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: local header "
                            "flags do not match central directory"
                        )
                    if local_compression != info.compress_type:
                        local_header_valid = False
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: local header "
                            "compression does not match central directory"
                        )
                    if not local_flags & 0x08:
                        local_crc = local_header_fields[6]
                        local_compressed_size = local_header_fields[7]
                        local_file_size = local_header_fields[8]
                        if (
                            local_crc != info.CRC
                            or local_compressed_size != info.compress_size
                            or local_file_size != info.file_size
                        ):
                            local_header_valid = False
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: local header "
                                "CRC or sizes do not match central directory"
                            )
                if not local_header_valid:
                    continue
                if local_record_end is not None:
                    claimed_ranges.append((info.header_offset, local_record_end))
                if info.is_dir():
                    if info.file_size:
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: "
                            "directory-marked entry contains data"
                        )
                        continue
                    if info.compress_size:
                        if local_payload_offset is None:
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: directory "
                                "payload offset is unavailable"
                            )
                            continue
                        if (
                            scanned_compressed_bytes + info.compress_size
                            > compressed_input_limit
                        ):
                            findings.append(
                                f"{archive_label}: archive directory compressed input "
                                f"exceeds the {compressed_input_limit}-byte aggregate "
                                "scan limit"
                            )
                            continue
                        scanned_compressed_bytes += info.compress_size
                        directory_stream = archive.fp
                        directory_stream_position: int | None = None
                        try:
                            if directory_stream is None:
                                raise ValueError("archive stream is unavailable")
                            directory_stream_position = directory_stream.tell()
                            directory_content = read_verified_zip_member(
                                directory_stream,
                                info,
                                payload_offset=local_payload_offset,
                                output_limit=0,
                                compressed_input_limit=compressed_input_limit,
                                lzma_dictionary_limit=lzma_dictionary_limit,
                            )
                            if directory_content:
                                raise ValueError("directory output is not empty")
                        except (
                            EOFError,
                            MemoryError,
                            NotImplementedError,
                            OSError,
                            RuntimeError,
                            ValueError,
                            lzma.LZMAError,
                            struct.error,
                            zipfile.BadZipFile,
                            zlib.error,
                        ) as error:
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: "
                                "directory-marked entry contains invalid compressed "
                                f"payload ({type(error).__name__})"
                            )
                        finally:
                            if (
                                directory_stream is not None
                                and directory_stream_position is not None
                            ):
                                try:
                                    directory_stream.seek(directory_stream_position)
                                except (OSError, ValueError):
                                    findings.append(
                                        f"{archive_label}: archive entry {name!r}: "
                                        "directory stream position restore failed"
                                    )
                    continue
                entry_suffix = entry.suffix.lower()
                if entry_suffix in UNSUPPORTED_NESTED_ARCHIVE_SUFFIXES:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: nested archive "
                        f"format {entry_suffix!r} is not supported"
                    )
                    continue
                if (
                    entry_suffix in ARCHIVE_BINARY_SUFFIXES
                    or is_known_archive_binary_path(entry)
                ):
                    continue
                if local_payload_offset is None:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: local payload "
                        "offset is unavailable"
                    )
                    continue
                if entry_suffix in ZIP_NESTED_ARCHIVE_SUFFIXES:
                    if _nested_depth >= ZIP_NESTED_DEPTH_LIMIT:
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: nested archive "
                            f"depth exceeds {ZIP_NESTED_DEPTH_LIMIT}"
                        )
                        continue
                    if (
                        nested_budget.archive_count + 1
                        > ZIP_NESTED_ARCHIVE_COUNT_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: nested archive count exceeds "
                            f"{ZIP_NESTED_ARCHIVE_COUNT_LIMIT}"
                        )
                        continue
                    if (
                        info.compress_size > ZIP_NESTED_MEMBER_LIMIT
                        or info.file_size > ZIP_NESTED_MEMBER_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: nested archive "
                            f"exceeds the {ZIP_NESTED_MEMBER_LIMIT}-byte member limit"
                        )
                        continue
                    if (
                        nested_budget.compressed_bytes + info.compress_size
                        > ZIP_NESTED_TOTAL_LIMIT
                        or nested_budget.uncompressed_bytes + info.file_size
                        > ZIP_NESTED_TOTAL_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: nested archives exceed the "
                            f"{ZIP_NESTED_TOTAL_LIMIT}-byte aggregate limit"
                        )
                        continue
                    nested_compressed_size = max(info.compress_size, 1)
                    if (
                        info.file_size
                        > nested_compressed_size * compression_ratio_limit
                    ):
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: nested archive "
                            f"exceeds {compression_ratio_limit}:1 compression limit"
                        )
                        continue
                    nested_budget.archive_count += 1
                    nested_budget.compressed_bytes += info.compress_size
                    nested_budget.uncompressed_bytes += info.file_size
                    nested_stream = archive.fp
                    nested_stream_position: int | None = None
                    try:
                        if nested_stream is None:
                            raise ValueError("archive stream is unavailable")
                        nested_stream_position = nested_stream.tell()
                        nested_content = read_verified_zip_member(
                            nested_stream,
                            info,
                            payload_offset=local_payload_offset,
                            output_limit=ZIP_NESTED_MEMBER_LIMIT,
                            compressed_input_limit=ZIP_NESTED_MEMBER_LIMIT,
                            lzma_dictionary_limit=lzma_dictionary_limit,
                        )
                    except (
                        EOFError,
                        MemoryError,
                        NotImplementedError,
                        OSError,
                        RuntimeError,
                        ValueError,
                        lzma.LZMAError,
                        struct.error,
                        zipfile.BadZipFile,
                        zlib.error,
                    ) as error:
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: nested archive "
                            f"read failed ({type(error).__name__})"
                        )
                        continue
                    finally:
                        if (
                            nested_stream is not None
                            and nested_stream_position is not None
                        ):
                            try:
                                nested_stream.seek(nested_stream_position)
                            except (OSError, ValueError):
                                findings.append(
                                    f"{archive_label}: archive entry {name!r}: nested "
                                    "archive stream position restore failed"
                                )
                    findings.extend(
                        scan_zip(
                            BytesIO(nested_content),
                            label=f"{archive_label}: nested archive {name!r}",
                            member_scan_limit=member_scan_limit,
                            total_scan_limit=total_scan_limit,
                            compressed_input_limit=compressed_input_limit,
                            compression_ratio_limit=compression_ratio_limit,
                            entry_count_limit=entry_count_limit,
                            lzma_dictionary_limit=lzma_dictionary_limit,
                            _nested_depth=_nested_depth + 1,
                            _nested_budget=nested_budget,
                        )
                    )
                    continue
                compressed_size = max(info.compress_size, 1)
                if info.file_size > compressed_size * compression_ratio_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{compression_ratio_limit}:1 compression scan limit"
                    )
                    continue
                bounded_app_executable = (
                    is_app_main_executable_path(entry)
                    and info.file_size <= total_scan_limit
                )
                if info.file_size > member_scan_limit and not bounded_app_executable:
                    if not (is_jdk_modules_path(entry) or is_icu_data_path(entry)):
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: exceeds "
                            f"{member_scan_limit}-byte member content scan limit"
                        )
                        continue
                    binary_probe_stream = archive.fp
                    binary_probe_position: int | None = None
                    try:
                        if binary_probe_stream is None:
                            raise ValueError("archive stream is unavailable")
                        remaining_probe_budget = (
                            ZIP_BINARY_PROBE_TOTAL_INPUT_LIMIT
                            - scanned_binary_probe_bytes
                        )
                        binary_probe_position = binary_probe_stream.tell()
                        prefix, consumed = read_zip_binary_prefix(
                            binary_probe_stream,
                            info,
                            payload_offset=local_payload_offset,
                            compressed_input_limit=min(
                                ZIP_BINARY_PROBE_MEMBER_INPUT_LIMIT,
                                remaining_probe_budget,
                            ),
                        )
                        scanned_binary_probe_bytes += consumed
                    except (OSError, RuntimeError, ValueError, zlib.error) as error:
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: binary probe "
                            f"failed ({type(error).__name__})"
                        )
                        continue
                    finally:
                        if (
                            binary_probe_stream is not None
                            and binary_probe_position is not None
                        ):
                            try:
                                binary_probe_stream.seek(binary_probe_position)
                            except (OSError, ValueError):
                                findings.append(
                                    f"{archive_label}: archive entry {name!r}: binary "
                                    "probe stream position restore failed"
                                )
                    if has_valid_large_binary_header(entry, prefix, info.file_size):
                        continue
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: large platform "
                        "binary has an invalid structural header"
                    )
                    continue
                if info.compress_size > compressed_input_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{compressed_input_limit}-byte compressed payload scan limit"
                    )
                    continue
                if scanned_bytes + info.file_size > total_scan_limit:
                    findings.append(
                        f"{archive_label}: archive content exceeds "
                        f"{total_scan_limit}-byte total content scan limit"
                    )
                    break
                if (
                    scanned_compressed_bytes + info.compress_size
                    > compressed_input_limit
                ):
                    findings.append(
                        f"{archive_label}: archive compressed input exceeds "
                        f"the {compressed_input_limit}-byte aggregate scan limit"
                    )
                    break
                scanned_compressed_bytes += info.compress_size

                member_stream = archive.fp
                member_stream_position: int | None = None
                try:
                    if member_stream is None:
                        raise ValueError("archive stream is unavailable")
                    member_stream_position = member_stream.tell()
                    content = read_verified_zip_member(
                        member_stream,
                        info,
                        payload_offset=local_payload_offset,
                        output_limit=(
                            total_scan_limit
                            if bounded_app_executable
                            else member_scan_limit
                        ),
                        compressed_input_limit=compressed_input_limit,
                        lzma_dictionary_limit=lzma_dictionary_limit,
                    )
                except (
                    EOFError,
                    MemoryError,
                    NotImplementedError,
                    OSError,
                    RuntimeError,
                    ValueError,
                    lzma.LZMAError,
                    struct.error,
                    zipfile.BadZipFile,
                    zlib.error,
                ) as error:
                    detail = f": {error}" if str(error) else ""
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: content scan failed "
                        f"({type(error).__name__}{detail})"
                    )
                    continue
                finally:
                    if member_stream is not None and member_stream_position is not None:
                        try:
                            member_stream.seek(member_stream_position)
                        except (OSError, ValueError):
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: member stream "
                                "position restore failed"
                            )
                if scanned_bytes + len(content) > total_scan_limit:
                    findings.append(
                        f"{archive_label}: archive content exceeded "
                        f"{total_scan_limit}-byte total content scan limit while reading"
                    )
                    break
                scanned_bytes += len(content)
                text = decode_text_for_secret_scan(content)
                member_label = f"{archive_label}: archive entry {name!r}"
                audio_signature = audio_payload_signature(content)
                if audio_signature:
                    findings.append(
                        f"{member_label}: audio signature {audio_signature!r} detected"
                    )
                findings.extend(scan_text(member_label, text))

            if len(claimed_ranges) == len(entries):
                expected_offset = 0
                for range_start, range_end in sorted(claimed_ranges):
                    if range_start != expected_offset or range_end < range_start:
                        findings.append(
                            f"{archive_label}: ZIP local records do not form one "
                            "contiguous claimed range"
                        )
                        break
                    expected_offset = range_end
                else:
                    if expected_offset != archive.start_dir:
                        findings.append(
                            f"{archive_label}: ZIP local records leave unclaimed bytes "
                            "before the central directory"
                        )
    except (OSError, zipfile.BadZipFile) as error:
        findings.append(
            f"{archive_label}: archive content scan failed ({type(error).__name__})"
        )
    return findings


def redact_secret_text(content: str) -> str:
    redacted = content
    for pattern in SECRET_PATTERNS:
        redacted = pattern.sub("<redacted-secret>", redacted)
    return redacted


def scan_text(label: str, content: str) -> list[str]:
    safe_label = redact_secret_text(label)
    return [
        f"{safe_label}: secret-shaped content matched {pattern.pattern!r}"
        for pattern in SECRET_PATTERNS
        if pattern.search(content)
    ]


def scan_historical_zip_blobs(
    candidates_list: list[tuple[str, PurePosixPath]],
) -> list[str]:
    findings: list[str] = []
    candidates = dict(candidates_list)

    if len(candidates) > HISTORICAL_ZIP_BLOB_COUNT_LIMIT:
        findings.append(
            "git history ZIP content: "
            f"{len(candidates)} unique blobs exceed the "
            f"{HISTORICAL_ZIP_BLOB_COUNT_LIMIT}-archive scan limit"
        )

    scanned_container_bytes = 0
    for object_id, path in list(candidates.items())[:HISTORICAL_ZIP_BLOB_COUNT_LIMIT]:
        size = int(run_git(["cat-file", "-s", object_id]).strip())
        archive_label = f"git history ZIP {path} ({object_id[:12]})"
        if size > HISTORICAL_ZIP_CONTAINER_LIMIT:
            findings.append(
                f"{archive_label}: exceeds the "
                f"{HISTORICAL_ZIP_CONTAINER_LIMIT}-byte container scan limit"
            )
            continue
        if scanned_container_bytes + size > HISTORICAL_ZIP_TOTAL_LIMIT:
            findings.append(
                "git history ZIP content exceeds the "
                f"{HISTORICAL_ZIP_TOTAL_LIMIT}-byte aggregate container scan limit"
            )
            break

        content = run_git(["cat-file", "blob", object_id])
        if len(content) != size:
            findings.append(f"{archive_label}: blob size changed while reading")
            continue
        scanned_container_bytes += len(content)
        findings.extend(scan_zip(BytesIO(content), label=archive_label))
    return findings


def scan_history() -> list[str]:
    findings: list[str] = []
    objects = historical_objects()
    for _, path in objects:
        reason = suspicious_path(path)
        if reason:
            findings.append(f"git history path {path}: {reason}")
    non_commit_objects, non_commit_findings = (
        historical_non_commit_tree_zip_objects()
    )
    findings.extend(non_commit_findings)
    zip_objects = dict(historical_zip_objects())
    for object_id, path in non_commit_objects:
        zip_objects.setdefault(object_id, path)
    findings.extend(scan_historical_zip_blobs(list(zip_objects.items())))

    # A textual patch includes every textual addition/deletion reachable from all
    # refs without checking out or materializing historical files. Binary payloads
    # are intentionally not printed; their suspicious names are covered above.
    history_patch = run_git(
        ["log", "--all", "--format=", "--no-ext-diff", "--no-textconv", "-p"],
    ).decode("utf-8", errors="replace")
    findings.extend(scan_text("git history patch", history_patch))
    return findings


def scan_public_path(path: Path | PurePosixPath) -> list[str]:
    findings: list[str] = []
    reason = suspicious_path(path)
    if reason:
        findings.append(f"{path}: {reason}")
    resolved_path = Path(path)
    if resolved_path.is_symlink():
        try:
            link_target = resolved_path.readlink()
        except OSError as error:
            findings.append(
                f"{path}: symbolic-link target scan failed ({type(error).__name__})"
            )
            return findings
        findings.extend(scan_text(f"{path}: symbolic-link target", str(link_target)))
        return findings
    if path.suffix.lower() == ".zip":
        findings.extend(scan_zip(resolved_path, label=str(path)))
        return findings

    try:
        content = resolved_path.read_bytes().decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return findings
    findings.extend(scan_text(str(path), content))
    return findings


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--history",
        action="store_true",
        help="also scan reachable historical paths and text patches",
    )
    parser.add_argument(
        "--archive",
        action="append",
        default=[],
        type=Path,
        help="also scan this exact ZIP archive after it has been created",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = public_candidate_paths()
    findings: list[str] = []
    for path in paths:
        findings.extend(scan_public_path(path))
    for archive_path in args.archive:
        if archive_path.is_symlink():
            findings.append(
                f"{archive_path}: explicit archive must be a regular file, not a symlink"
            )
            continue
        findings.extend(scan_zip(archive_path, label=str(archive_path)))

    if args.history:
        findings.extend(scan_history())

    if findings:
        print("PUBLIC SURFACE CHECK: FAIL", file=sys.stderr)
        for finding in sorted(set(findings)):
            print(f"- {redact_secret_text(finding)}", file=sys.stderr)
        return 1

    scope = "current tree and reachable history" if args.history else "current tree"
    if args.archive:
        scope += f" plus {len(args.archive)} explicit archive(s)"
    print(
        f"PUBLIC SURFACE CHECK: PASS ({len(paths)} public candidates; {scope}; "
        "no credential, signing, or audio candidates)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
