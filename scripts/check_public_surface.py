#!/usr/bin/env python3
"""Fail-closed check for credentials, signing material, and user audio in public content."""

from __future__ import annotations

import argparse
import re
import struct
import subprocess
import sys
import zipfile
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
    ".7z",
    ".aab",
    ".apk",
    ".bin",
    ".class",
    ".dex",
    ".dll",
    ".dylib",
    ".exe",
    ".gif",
    ".gz",
    ".ico",
    ".jar",
    ".jpeg",
    ".jpg",
    ".o",
    ".pdf",
    ".png",
    ".so",
    ".tar",
    ".webp",
    ".xz",
    ".zip",
}
ZIP_MEMBER_SCAN_LIMIT = 512 * 1024
ZIP_TOTAL_SCAN_LIMIT = 4 * 1024 * 1024
ZIP_COMPRESSION_RATIO_LIMIT = 100
ZIP_ENTRY_COUNT_LIMIT = 4_096
HISTORICAL_ZIP_BLOB_COUNT_LIMIT = 128
HISTORICAL_ZIP_CONTAINER_LIMIT = 16 * 1024 * 1024
HISTORICAL_ZIP_TOTAL_LIMIT = 64 * 1024 * 1024
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"gh" + r"o_[A-Za-z0-9_]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
]
ZIP_LOCAL_FILE_HEADER = struct.Struct("<4s5H3L2H")
ZIP_LOCAL_FILE_SIGNATURE = b"PK\x03\x04"
ZIP_END_OF_CENTRAL_DIRECTORY = struct.Struct("<4s4H2LH")
ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE = b"PK\x05\x06"
ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER_SIZE = 46
ZIP_MAX_COMMENT_LENGTH = (1 << 16) - 1
ZIP64_UINT16_SENTINEL = (1 << 16) - 1
ZIP64_UINT32_SENTINEL = (1 << 32) - 1


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


def decode_text_for_secret_scan(content: bytes) -> str:
    # Credential patterns are ASCII. Replacement decoding preserves those bytes
    # even when unrelated malformed bytes appear elsewhere in safe-named text.
    return content.decode("utf-8-sig", errors="replace")


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
        ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER_SIZE * entry_count + total_scan_limit
    )
    if central_directory_size > maximum_directory_size:
        return [
            f"{archive_label}: central directory metadata exceeds the "
            f"{total_scan_limit}-byte total content scan limit before parsing"
        ]
    if central_directory_offset + central_directory_size > directory_offset:
        return [f"{archive_label}: central directory bounds are invalid"]
    return []


def scan_zip(
    source: Path | PurePosixPath | BytesIO,
    *,
    label: str | None = None,
    member_scan_limit: int = ZIP_MEMBER_SCAN_LIMIT,
    total_scan_limit: int = ZIP_TOTAL_SCAN_LIMIT,
    compression_ratio_limit: int = ZIP_COMPRESSION_RATIO_LIMIT,
    entry_count_limit: int = ZIP_ENTRY_COUNT_LIMIT,
) -> list[str]:
    findings: list[str] = []
    scanned_bytes = 0
    archive_label = label or str(source)

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
                    name_length, extra_length = fields[-2:]
                    local_name = stream.read(name_length)
                    local_extra = stream.read(extra_length)
                    if len(local_name) != name_length or len(local_extra) != extra_length:
                        raise ValueError("truncated local file header metadata")
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
                if info.is_dir():
                    if info.file_size:
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: "
                            "directory-marked entry contains data"
                        )
                    continue
                if entry.suffix.lower() in ARCHIVE_BINARY_SUFFIXES:
                    continue
                if info.file_size > member_scan_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{member_scan_limit}-byte member content scan limit"
                    )
                    continue
                compressed_size = max(info.compress_size, 1)
                if info.file_size > compressed_size * compression_ratio_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{compression_ratio_limit}:1 compression scan limit"
                    )
                    continue
                if scanned_bytes + info.file_size > total_scan_limit:
                    findings.append(
                        f"{archive_label}: archive content exceeds "
                        f"{total_scan_limit}-byte total content scan limit"
                    )
                    break

                try:
                    with archive.open(info) as member:
                        content = member.read(member_scan_limit + 1)
                except (NotImplementedError, OSError, RuntimeError, zipfile.BadZipFile) as error:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: content scan failed "
                        f"({type(error).__name__})"
                    )
                    continue
                if len(content) > member_scan_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeded "
                        f"{member_scan_limit}-byte member content scan limit while reading"
                    )
                    continue
                if scanned_bytes + len(content) > total_scan_limit:
                    findings.append(
                        f"{archive_label}: archive content exceeded "
                        f"{total_scan_limit}-byte total content scan limit while reading"
                    )
                    break
                scanned_bytes += len(content)
                text = decode_text_for_secret_scan(content)
                member_label = f"{archive_label}: archive entry {name!r}"
                findings.extend(scan_text(member_label, text))
    except (OSError, zipfile.BadZipFile) as error:
        findings.append(
            f"{archive_label}: archive content scan failed ({type(error).__name__})"
        )
    return findings


def scan_text(label: str, content: str) -> list[str]:
    return [
        f"{label}: secret-shaped content matched {pattern.pattern!r}"
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
    findings.extend(scan_historical_zip_blobs(historical_zip_objects()))

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
    if path.suffix.lower() == ".zip":
        findings.extend(scan_zip(Path(path), label=str(path)))
        return findings

    try:
        content = Path(path).read_bytes().decode("utf-8")
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
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = public_candidate_paths()
    findings: list[str] = []
    for path in paths:
        findings.extend(scan_public_path(path))

    if args.history:
        findings.extend(scan_history())

    if findings:
        print("PUBLIC SURFACE CHECK: FAIL", file=sys.stderr)
        for finding in sorted(set(findings)):
            print(f"- {finding}", file=sys.stderr)
        return 1

    scope = "current tree and reachable history" if args.history else "current tree"
    print(
        f"PUBLIC SURFACE CHECK: PASS ({len(paths)} public candidates; {scope}; "
        "no credential, signing, or audio candidates)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
