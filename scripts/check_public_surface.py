#!/usr/bin/env python3
"""Fail-closed check for credentials, signing material, and user audio in public content."""

from __future__ import annotations

import argparse
import re
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
            "--format=",
            "--raw",
            "--no-abbrev",
            "--no-renames",
            "--root",
            "-z",
            "--",
            "*.zip",
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
            if mode in {"000000", "040000", "160000"}:
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


def decode_text_for_secret_scan(content: bytes) -> str | None:
    try:
        return content.decode("utf-8-sig")
    except UnicodeDecodeError:
        return None


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
    try:
        with zipfile.ZipFile(source) as archive:
            entries = archive.infolist()
            if len(entries) > entry_count_limit:
                findings.append(
                    f"{archive_label}: archive contains {len(entries)} entries, exceeding "
                    f"the {entry_count_limit}-entry content scan limit"
                )
                return findings

            archive_comment = archive.comment
            if scanned_bytes + len(archive_comment) > total_scan_limit:
                findings.append(
                    f"{archive_label}: archive comments exceed "
                    f"{total_scan_limit}-byte total content scan limit"
                )
                return findings
            scanned_bytes += len(archive_comment)
            archive_comment_text = decode_text_for_secret_scan(archive_comment)
            if archive_comment_text is not None:
                findings.extend(
                    scan_text(f"{archive_label}: archive comment", archive_comment_text)
                )

            for info in entries:
                name = info.filename
                entry = PurePosixPath(name)
                reason = suspicious_path(entry)
                if reason:
                    findings.append(f"{archive_label}: archive entry {name!r}: {reason}")
                if scanned_bytes + len(info.comment) > total_scan_limit:
                    findings.append(
                        f"{archive_label}: archive comments exceed "
                        f"{total_scan_limit}-byte total content scan limit"
                    )
                    break
                scanned_bytes += len(info.comment)
                entry_comment_text = decode_text_for_secret_scan(info.comment)
                if entry_comment_text is not None:
                    findings.extend(
                        scan_text(
                            f"{archive_label}: archive entry {name!r} comment",
                            entry_comment_text,
                        )
                    )
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
                if text is not None:
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
