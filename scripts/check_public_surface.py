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
UNSUPPORTED_NESTED_ARCHIVE_SUFFIXES = {
    ".7z",
    ".bz2",
    ".gz",
    ".tar",
    ".tgz",
    ".xz",
    ".zst",
}
ZIP_MEMBER_SCAN_LIMIT = 512 * 1024
ZIP_TOTAL_SCAN_LIMIT = 4 * 1024 * 1024
ZIP_COMPRESSION_RATIO_LIMIT = 100
ZIP_ENTRY_COUNT_LIMIT = 4_096
ZIP_CT_SYM_ENTRY_COUNT_LIMIT = 20_000
ZIP_CT_SYM_TOTAL_SCAN_LIMIT = 128 * 1024 * 1024
ZIP_LZMA_DICTIONARY_LIMIT = 16 * 1024 * 1024
HISTORICAL_ZIP_BLOB_COUNT_LIMIT = 128
HISTORICAL_ZIP_CONTAINER_LIMIT = 16 * 1024 * 1024
HISTORICAL_ZIP_TOTAL_LIMIT = 64 * 1024 * 1024
HISTORICAL_NON_COMMIT_TREE_LIMIT = 128
HISTORICAL_NON_COMMIT_REF_LIMIT = 4_096
ZIP_NESTED_DEPTH_LIMIT = 3
ZIP_NESTED_ARCHIVE_COUNT_LIMIT = 64
ZIP_NESTED_MEMBER_LIMIT = 16 * 1024 * 1024
ZIP_NESTED_TOTAL_LIMIT = 256 * 1024 * 1024
CURRENT_ZIP_ARCHIVE_COUNT_LIMIT = 128
CURRENT_ZIP_COMPRESSED_INPUT_LIMIT = 512 * 1024 * 1024
CURRENT_ZIP_EXPANDED_OUTPUT_LIMIT = 512 * 1024 * 1024
HISTORICAL_NON_COMMIT_BLOB_LIMIT = 128
HISTORICAL_ZIP_CHANGE_RECORD_LIMIT = 4_096
HISTORICAL_ZIP_RAW_OUTPUT_LIMIT = 4 * 1024 * 1024
HISTORICAL_TAG_PEEL_LIMIT = 8
HISTORICAL_TAG_PEEL_OPERATION_LIMIT = 512
HISTORICAL_STRUCTURAL_BLOB_PROBE_LIMIT = 16_384
HISTORICAL_STRUCTURAL_BLOB_CONTENT_LIMIT = 256 * 1024 * 1024
GIT_OUTPUT_LIMIT = 64 * 1024 * 1024
SECRET_PATTERNS = [
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    re.compile(r"gh" + r"o_[A-Za-z0-9_]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"AIza[0-9A-Za-z_-]{20,}"),
]
SECRET_BYTE_PATTERNS = [
    re.compile(pattern.pattern.encode("ascii")) for pattern in SECRET_PATTERNS
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
ZIP_JIMAGE_HEADER = struct.Struct("<7I")
ZIP_JIMAGE_MAGIC = 0xCAFEDADA
ZIP_SKIKO_ICU_SCAN_LIMIT = 16 * 1024 * 1024
ZIP_BINARY_SECRET_MEMBER_LIMIT = 32 * 1024 * 1024
ZIP_JIMAGE_FULL_SCAN_LIMIT = 128 * 1024 * 1024
ZIP_BINARY_SECRET_TOTAL_LIMIT = 384 * 1024 * 1024
APK_SIGNING_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIGNING_BLOCK_MAX_SIZE = 16 * 1024 * 1024
APK_SIGNING_BLOCK_PAIR_LIMIT = 128
APK_BINARY_MEMBER_SCAN_LIMIT = 32 * 1024 * 1024
APK_BINARY_TOTAL_SCAN_LIMIT = 64 * 1024 * 1024
EXPLICIT_TEXT_FILE_LIMIT = 16 * 1024 * 1024
TEXT_BOM_PREFIXES = (
    b"\xff\xfe\x00\x00",
    b"\x00\x00\xfe\xff",
    b"\xff\xfe",
    b"\xfe\xff",
)
ZSTANDARD_MAGIC = b"\x28\xb5\x2f\xfd"
SECRET_TEXT_PREFIXES = (
    "-----BEGIN ",
    "gho_",
    "github_pat_",
    "AKIA",
    "AIza",
)


@dataclass
class ZipNestedScanBudget:
    archive_count: int = 0
    compressed_bytes: int = 0
    expanded_bytes: int = 0


@dataclass
class ZipCandidateScanBudget:
    archive_limit: int = CURRENT_ZIP_ARCHIVE_COUNT_LIMIT
    compressed_input_limit: int = CURRENT_ZIP_COMPRESSED_INPUT_LIMIT
    expanded_output_limit: int = CURRENT_ZIP_EXPANDED_OUTPUT_LIMIT
    archive_count: int = 0
    compressed_bytes: int = 0
    expanded_bytes: int = 0
    scope: str = "current/explicit"


@dataclass
class ApkContentScanBudget:
    compressed_bytes: int = 0
    expanded_bytes: int = 0


@dataclass
class ZipBinaryScanBudget:
    compressed_bytes: int = 0
    expanded_bytes: int = 0


@dataclass
class GitTagPeelBudget:
    operations: int = 0


class GitScanLimitError(RuntimeError):
    """A Git evidence stream exceeded its explicit in-memory/work bound."""


def run_git(arguments: list[str]) -> bytes:
    command = ["git", *arguments]
    bounded_zip_history = (
        "--raw" in arguments and ":(icase,glob)**/*.zip" in arguments
    )
    output_limit = (
        HISTORICAL_ZIP_RAW_OUTPUT_LIMIT
        if bounded_zip_history
        else GIT_OUTPUT_LIMIT
    )
    nul_field_limit = (
        HISTORICAL_ZIP_CHANGE_RECORD_LIMIT * 2
        if bounded_zip_history
        else None
    )
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if process.stdout is None:
        process.kill()
        process.wait()
        raise RuntimeError("git stdout pipe is unavailable")

    output = bytearray()
    nul_fields = 0
    try:
        while True:
            chunk = process.stdout.read(64 * 1024)
            if not chunk:
                break
            if len(output) + len(chunk) > output_limit:
                raise GitScanLimitError(
                    f"git output exceeds the {output_limit}-byte scan limit"
                )
            if nul_field_limit is not None:
                nul_fields += chunk.count(b"\0")
                if nul_fields > nul_field_limit:
                    raise GitScanLimitError(
                        "git ZIP history exceeds the "
                        f"{HISTORICAL_ZIP_CHANGE_RECORD_LIMIT}-record scan limit"
                    )
            output.extend(chunk)
    except BaseException:
        process.kill()
        process.wait()
        raise
    finally:
        process.stdout.close()

    return_code = process.wait()
    if return_code:
        raise subprocess.CalledProcessError(return_code, command)
    return bytes(output)


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


def git_object_metadata(object_ids: list[str]) -> dict[str, tuple[str, int]]:
    payload = b"".join(object_id.encode("ascii") + b"\n" for object_id in object_ids)
    completed = subprocess.run(
        ["git", "cat-file", "--batch-check=%(objectname) %(objecttype) %(objectsize)"],
        input=payload,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        check=True,
    )
    if len(completed.stdout) > GIT_OUTPUT_LIMIT:
        raise GitScanLimitError(
            f"git object metadata exceeds the {GIT_OUTPUT_LIMIT}-byte scan limit"
        )
    metadata: dict[str, tuple[str, int]] = {}
    for line in completed.stdout.splitlines():
        object_id, object_type, raw_size = line.decode("ascii").split()
        metadata[object_id] = (object_type, int(raw_size))
    return metadata


def read_git_blob_prefixes(object_ids: list[str], byte_limit: int = 4) -> dict[str, bytes]:
    """Stream bounded blob bodies through one Git process while retaining only prefixes."""
    process = subprocess.Popen(
        ["git", "cat-file", "--batch"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    if process.stdin is None or process.stdout is None:
        process.kill()
        process.wait()
        raise RuntimeError("git blob batch pipes are unavailable")
    prefixes: dict[str, bytes] = {}
    try:
        for object_id in object_ids:
            process.stdin.write(object_id.encode("ascii") + b"\n")
            process.stdin.flush()
            header = process.stdout.readline().decode("ascii").split()
            if len(header) != 3 or header[0] != object_id or header[1] != "blob":
                raise RuntimeError("unexpected git blob batch header")
            size = int(header[2])
            prefix = process.stdout.read(min(byte_limit, size))
            remaining = size - len(prefix)
            while remaining:
                chunk = process.stdout.read(min(64 * 1024, remaining))
                if not chunk:
                    raise RuntimeError("truncated git blob batch body")
                remaining -= len(chunk)
            if process.stdout.read(1) != b"\n":
                raise RuntimeError("missing git blob batch delimiter")
            prefixes[object_id] = prefix
        process.stdin.close()
        return_code = process.wait()
        if return_code:
            raise subprocess.CalledProcessError(return_code, process.args)
    except BaseException:
        process.kill()
        process.wait()
        raise
    finally:
        if not process.stdin.closed:
            process.stdin.close()
        process.stdout.close()
    return prefixes


def historical_structural_zip_objects(
    objects: list[tuple[str, PurePosixPath]],
    excluded_object_ids: set[str],
) -> tuple[list[tuple[str, PurePosixPath]], list[str]]:
    candidates = {
        object_id: path
        for object_id, path in objects
        if object_id not in excluded_object_ids and path.suffix.lower() != ".zip"
    }
    if len(candidates) > HISTORICAL_STRUCTURAL_BLOB_PROBE_LIMIT:
        return [], [
            "git history ZIP structure probe: "
            f"{len(candidates)} objects exceed the "
            f"{HISTORICAL_STRUCTURAL_BLOB_PROBE_LIMIT}-object scan limit"
        ]

    try:
        metadata = git_object_metadata(list(candidates))
    except (GitScanLimitError, OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        return [], [
            "git history ZIP structure probe metadata failed "
            f"({type(error).__name__})"
        ]
    blob_ids = [
        object_id
        for object_id in candidates
        if metadata.get(object_id, (None, 0))[0] == "blob"
    ]
    total_blob_bytes = sum(metadata[object_id][1] for object_id in blob_ids)
    if total_blob_bytes > HISTORICAL_STRUCTURAL_BLOB_CONTENT_LIMIT:
        return [], [
            "git history ZIP structure probe: blob contents total "
            f"{total_blob_bytes} bytes exceeds the "
            f"{HISTORICAL_STRUCTURAL_BLOB_CONTENT_LIMIT}-byte scan limit"
        ]
    try:
        prefixes = read_git_blob_prefixes(blob_ids)
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        return [], [
            "git history ZIP structure probe content failed "
            f"({type(error).__name__})"
        ]

    structural: list[tuple[str, PurePosixPath]] = []
    for object_id in blob_ids:
        path = candidates[object_id]
        prefix = prefixes[object_id]
        if prefix.startswith((ZIP_LOCAL_FILE_SIGNATURE, ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE)):
            structural.append((object_id, path))
    return structural, []


def historical_zip_objects() -> tuple[list[tuple[str, PurePosixPath]], list[str]]:
    """Return every blob version ever reachable through a path ending in .zip.

    `rev-list --objects` provides only one path hint for a shared object, so a blob
    reachable as both `copy.dat` and `removed.zip` cannot be selected reliably from
    that output. Raw history records retain the path paired with each old/new blob.
    """
    try:
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
    except GitScanLimitError as error:
        return [], [f"git history ZIP changes: {error}"]
    fields = raw.split(b"\0")
    if fields and not fields[-1]:
        fields.pop()
    if len(fields) % 2:
        return [], ["git history ZIP changes: invalid NUL-delimited raw output"]
    record_count = len(fields) // 2
    if record_count > HISTORICAL_ZIP_CHANGE_RECORD_LIMIT:
        return [], [
            "git history ZIP changes: "
            f"{record_count} records exceed the "
            f"{HISTORICAL_ZIP_CHANGE_RECORD_LIMIT}-record scan limit"
        ]

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
    return list(candidates.items()), []


def peel_annotated_tag(
    object_id: str,
    object_type: str,
    peeled_id: str,
    peeled_type: str,
    *,
    budget: GitTagPeelBudget | None = None,
) -> tuple[str, str]:
    """Peel tag objects to their final object with an explicit depth bound."""
    current_id = peeled_id or object_id
    current_type = peeled_type or object_type
    if current_type != "tag":
        return current_id, current_type

    seen_ids: set[str] = set()
    for _ in range(HISTORICAL_TAG_PEEL_LIMIT):
        if current_id in seen_ids:
            raise ValueError("annotated tag peel cycle detected")
        seen_ids.add(current_id)
        if budget is not None:
            if budget.operations + 1 > HISTORICAL_TAG_PEEL_OPERATION_LIMIT:
                raise GitScanLimitError(
                    "annotated tag peel operations exceed the "
                    f"{HISTORICAL_TAG_PEEL_OPERATION_LIMIT}-operation aggregate limit"
                )
            budget.operations += 1
        raw_tag = run_git(["cat-file", "-p", current_id])
        header = raw_tag.splitlines()
        if len(header) < 2:
            raise ValueError("annotated tag object header is truncated")
        object_line = header[0].decode("ascii", errors="replace")
        type_line = header[1].decode("ascii", errors="replace")
        if not object_line.startswith("object ") or not type_line.startswith("type "):
            raise ValueError("annotated tag object header is invalid")
        current_id = object_line[7:].strip()
        current_type = type_line[5:].strip()
        if not current_id or not current_type:
            raise ValueError("annotated tag object target is missing")
        if current_type != "tag":
            return current_id, current_type
    raise GitScanLimitError(
        "annotated tag peel exceeds the "
        f"{HISTORICAL_TAG_PEEL_LIMIT}-tag scan limit"
    )


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
    ref_count = len(fields) // 4
    if ref_count > HISTORICAL_NON_COMMIT_REF_LIMIT:
        return [], [
            "git history ZIP content: "
            f"{ref_count} non-commit refs exceed the "
            f"{HISTORICAL_NON_COMMIT_REF_LIMIT}-ref scan limit"
        ]

    tree_ids: set[str] = set()
    direct_blob_ids: set[str] = set()
    findings: list[str] = []
    peel_budget = GitTagPeelBudget()
    for index in range(0, len(fields), 4):
        object_id, object_type, peeled_id, peeled_type = (
            field.decode("ascii", errors="replace").strip()
            for field in fields[index : index + 4]
        )
        if object_type == "tag" or peeled_type == "tag":
            try:
                final_id, final_type = peel_annotated_tag(
                    object_id,
                    object_type,
                    peeled_id,
                    peeled_type,
                    budget=peel_budget,
                )
            except (
                GitScanLimitError,
                OSError,
                RuntimeError,
                ValueError,
                subprocess.CalledProcessError,
            ) as error:
                findings.append(
                    "git history ZIP content: annotated tag peel failed "
                    f"({type(error).__name__})"
                )
                continue
            if final_type == "tree":
                tree_ids.add(final_id)
            elif final_type == "blob":
                direct_blob_ids.add(final_id)
            continue
        if object_type == "tree":
            tree_ids.add(object_id)
        if peeled_type == "tree":
            tree_ids.add(peeled_id)
        if object_type == "blob":
            direct_blob_ids.add(object_id)
        if peeled_type == "blob":
            direct_blob_ids.add(peeled_id)
    tree_ids.discard("")
    direct_blob_ids.discard("")
    if len(tree_ids) > HISTORICAL_NON_COMMIT_TREE_LIMIT:
        return [], [
            "git history ZIP content: "
            f"{len(tree_ids)} non-commit trees exceed the "
            f"{HISTORICAL_NON_COMMIT_TREE_LIMIT}-tree scan limit"
        ]

    if len(direct_blob_ids) > HISTORICAL_NON_COMMIT_BLOB_LIMIT:
        findings.append(
            "git history ZIP content: "
            f"{len(direct_blob_ids)} direct blob refs exceed the "
            f"{HISTORICAL_NON_COMMIT_BLOB_LIMIT}-blob scan limit"
        )
        direct_blob_ids = set(
            sorted(direct_blob_ids)[:HISTORICAL_NON_COMMIT_BLOB_LIMIT]
        )

    candidates: dict[str, PurePosixPath] = {}
    direct_blob_identification_bytes = 0
    for object_id in sorted(direct_blob_ids):
        try:
            size = int(run_git(["cat-file", "-s", object_id]).strip())
        except (OSError, ValueError, subprocess.CalledProcessError) as error:
            findings.append(
                "git history ZIP content: direct blob ref size check failed "
                f"({type(error).__name__})"
            )
            continue
        if size > HISTORICAL_ZIP_CONTAINER_LIMIT:
            findings.append(
                "git history ZIP content: direct blob ref exceeds the "
                f"{HISTORICAL_ZIP_CONTAINER_LIMIT}-byte identification limit"
            )
            continue
        if (
            direct_blob_identification_bytes + size
            > HISTORICAL_ZIP_TOTAL_LIMIT
        ):
            findings.append(
                "git history ZIP content: direct blob ref identification exceeds "
                f"the {HISTORICAL_ZIP_TOTAL_LIMIT}-byte aggregate limit"
            )
            break
        try:
            content = run_git(["cat-file", "blob", object_id])
        except (OSError, GitScanLimitError, subprocess.CalledProcessError) as error:
            findings.append(
                "git history ZIP content: direct blob ref read failed "
                f"({type(error).__name__})"
            )
            continue
        if len(content) != size:
            findings.append("git history ZIP content: direct blob ref size changed")
            continue
        direct_blob_identification_bytes += len(content)
        if is_zip_compatible_payload(content):
            candidates.setdefault(
                object_id,
                PurePosixPath(f"direct-ref-{object_id[:12]}.zip"),
            )

    tree_listing_bytes = 0
    for tree_id in sorted(tree_ids):
        raw_tree = run_git(["ls-tree", "-rz", "-r", "--full-tree", tree_id])
        if tree_listing_bytes + len(raw_tree) > HISTORICAL_ZIP_TOTAL_LIMIT:
            findings.append(
                "git history ZIP content: non-commit tree listings exceed the "
                f"{HISTORICAL_ZIP_TOTAL_LIMIT}-byte aggregate limit"
            )
            break
        tree_listing_bytes += len(raw_tree)
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
                if (
                    object_id not in candidates
                    and len(candidates) >= HISTORICAL_ZIP_BLOB_COUNT_LIMIT
                ):
                    findings.append(
                        "git history ZIP content: non-commit refs exceed the "
                        f"{HISTORICAL_ZIP_BLOB_COUNT_LIMIT}-archive candidate limit"
                    )
                    return list(candidates.items()), findings
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


def is_skiko_icu_data_path(
    path: PurePosixPath,
    container_path: PurePosixPath | None,
) -> bool:
    return (
        path.as_posix().lower() == "icudtl.dat"
        and container_path is not None
        and container_path.suffix.lower() == ".jar"
        and container_path.name.lower().startswith("skiko-awt-runtime-windows-")
    )


def decode_text_for_secret_scan(content: bytes) -> str:
    # Credential patterns are ASCII. Replacement decoding preserves those bytes
    # even when unrelated malformed bytes appear elsewhere in safe-named text.
    if content.startswith((b"\xff\xfe\x00\x00", b"\x00\x00\xfe\xff")):
        return content.decode("utf-32", errors="replace")
    if content.startswith((b"\xff\xfe", b"\xfe\xff")):
        return content.decode("utf-16", errors="replace")
    return content.decode("utf-8-sig", errors="replace")


def has_valid_adts_frame(content: bytes) -> bool:
    """Return whether content starts with one complete, structurally valid ADTS frame."""
    if len(content) < 7 or content[0] != 0xFF or content[1] & 0xF0 != 0xF0:
        return False
    if (content[1] >> 1) & 0x03:
        return False
    sampling_frequency_index = (content[2] >> 2) & 0x0F
    if sampling_frequency_index >= 13:
        return False
    frame_length = (
        ((content[3] & 0x03) << 11)
        | (content[4] << 3)
        | ((content[5] >> 5) & 0x07)
    )
    header_length = 7 if content[1] & 0x01 else 9
    return frame_length >= header_length and frame_length <= len(content)


def audio_payload_signature(content: bytes) -> str | None:
    # UTF-16/32 text commonly starts with FF FE (or FE FF). Those bytes can
    # satisfy the loose MPEG frame prefix check below, but are not audio.
    if content.startswith(TEXT_BOM_PREFIXES):
        return None
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
    if has_valid_adts_frame(content):
        return "ADTS/AAC"
    if len(content) >= 4:
        version_bits = (content[1] >> 3) & 0x03
        layer_bits = (content[1] >> 1) & 0x03
        bitrate_index = (content[2] >> 4) & 0x0F
        sample_rate_index = (content[2] >> 2) & 0x03
        if (
            content[0] == 0xFF
            and content[1] & 0xE0 == 0xE0
            and version_bits != 0x01
            and layer_bits != 0x00
            and bitrate_index not in {0x00, 0x0F}
            and sample_rate_index != 0x03
        ):
            return "MPEG audio/MP3"
    if has_m4a_compatible_brand(content):
        return "M4A"
    return None


def has_m4a_compatible_brand(content: bytes) -> bool:
    """Recognize one bounded ISO-BMFF ftyp box carrying an M4A/M4B brand."""
    if len(content) < 16 or content[4:8] != b"ftyp":
        return False
    declared_size = int.from_bytes(content[:4], "big")
    if declared_size == 0:
        box_size = len(content)
    elif declared_size == 1:
        return False
    else:
        box_size = declared_size
    if box_size < 16 or box_size > len(content) or (box_size - 16) % 4:
        return False
    brands = [content[8:12]] + [
        content[offset : offset + 4]
        for offset in range(16, box_size, 4)
    ]
    return any(brand in {b"M4A ", b"M4B "} for brand in brands)


def is_zip_compatible_payload(content: bytes) -> bool:
    if content.startswith(
        (
            ZIP_LOCAL_FILE_SIGNATURE,
            ZIP_END_OF_CENTRAL_DIRECTORY_SIGNATURE,
        )
    ):
        return True
    try:
        return zipfile.is_zipfile(BytesIO(content))
    except (OSError, ValueError):
        return False


def unsupported_nested_archive_signature(content: bytes) -> str | None:
    if content.startswith(b"\x1f\x8b"):
        return ".gz"
    if len(content) >= 4 and content[:3] == b"BZh" and content[3:4] in b"123456789":
        return ".bz2"
    if content.startswith(b"\xfd7zXZ\x00"):
        return ".xz"
    if content.startswith(b"7z\xbc\xaf'\x1c"):
        return ".7z"
    if content.startswith(ZSTANDARD_MAGIC):
        return ".zst"
    if (
        len(content) >= 4
        and content[:3] == b"\x50\x2a\x4d"
        and content[3] == 0x18
    ):
        return ".zst"
    if len(content) >= 262 and content[257:262] == b"ustar":
        return ".tar"
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


def read_valid_apk_signing_block_values(
    stream: object,
    *,
    block_start: int,
    central_directory_start: int,
) -> list[bytes] | None:
    block_size = central_directory_start - block_start
    if block_size < 32 or block_size > APK_SIGNING_BLOCK_MAX_SIZE:
        return None
    original_position: int | None = None
    try:
        original_position = stream.tell()
        stream.seek(block_start)
        leading_size_bytes = stream.read(8)
        stream.seek(central_directory_start - 24)
        trailing = stream.read(24)
        if len(leading_size_bytes) != 8 or len(trailing) != 24:
            return None
        leading_size = int.from_bytes(leading_size_bytes, "little")
        trailing_size = int.from_bytes(trailing[:8], "little")
        if (
            trailing[8:] != APK_SIGNING_BLOCK_MAGIC
            or leading_size != trailing_size
            or leading_size + 8 != block_size
        ):
            return None

        pair_offset = block_start + 8
        pair_end = central_directory_start - 24
        pair_values: list[bytes] = []
        while pair_offset < pair_end:
            if len(pair_values) >= APK_SIGNING_BLOCK_PAIR_LIMIT:
                return None
            stream.seek(pair_offset)
            pair_size_bytes = stream.read(8)
            if len(pair_size_bytes) != 8:
                return None
            pair_size = int.from_bytes(pair_size_bytes, "little")
            if pair_size < 4 or pair_offset + 8 + pair_size > pair_end:
                return None
            pair_content = stream.read(pair_size)
            if len(pair_content) != pair_size:
                return None
            pair_values.append(pair_content[4:])
            pair_offset += 8 + pair_size
        if not pair_values or pair_offset != pair_end:
            return None
        return pair_values
    except (OSError, ValueError):
        return None
    finally:
        if original_position is not None:
            try:
                stream.seek(original_position)
            except (OSError, ValueError):
                pass


def is_apk_archive(
    source: Path | PurePosixPath | BytesIO,
    container_path: PurePosixPath | None,
) -> bool:
    if container_path is not None:
        return container_path.suffix.lower() == ".apk"
    return not isinstance(source, BytesIO) and Path(source).suffix.lower() == ".apk"


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
        filename_start = record_offset + ZIP_CENTRAL_DIRECTORY_ENTRY_HEADER.size
        filename_end = filename_start + filename_length
        if filename_end > len(central_directory):
            return [f"{archive_label}: central directory filename is truncated"]
        if b"\\" in central_directory[filename_start:filename_end]:
            return [
                f"{archive_label}: central directory entry contains a Windows "
                "path separator"
            ]
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
    nested_total_limit: int = ZIP_NESTED_TOTAL_LIMIT,
    _nested_depth: int = 0,
    _nested_budget: ZipNestedScanBudget | None = None,
    _candidate_budget: ZipCandidateScanBudget | None = None,
    _container_path: PurePosixPath | None = None,
    _apk_content_context: bool = False,
    _apk_binary_budget: ApkContentScanBudget | None = None,
    _binary_secret_budget: ZipBinaryScanBudget | None = None,
) -> list[str]:
    findings: list[str] = []
    scanned_bytes = 0
    scanned_compressed_bytes = 0
    scanned_large_binary_bytes = 0
    scanned_large_binary_compressed_bytes = 0
    archive_label = label or str(source)
    nested_budget = _nested_budget or ZipNestedScanBudget()
    candidate_budget = _candidate_budget
    apk_archive = is_apk_archive(source, _container_path)
    apk_content_context = apk_archive or _apk_content_context
    apk_binary_budget = _apk_binary_budget
    if apk_content_context and apk_binary_budget is None:
        apk_binary_budget = ApkContentScanBudget()
    binary_secret_budget = _binary_secret_budget or ZipBinaryScanBudget()

    if candidate_budget is not None and _nested_depth == 0:
        if candidate_budget.archive_count + 1 > candidate_budget.archive_limit:
            return [
                f"{archive_label}: {candidate_budget.scope} ZIP candidate count exceeds "
                f"the {candidate_budget.archive_limit}-archive scan limit"
            ]
        candidate_budget.archive_count += 1

    def reserve_candidate_work(compressed_bytes: int, expanded_bytes: int) -> bool:
        if candidate_budget is None:
            return True
        if (
            candidate_budget.compressed_bytes + compressed_bytes
            > candidate_budget.compressed_input_limit
        ):
            findings.append(
                f"{archive_label}: {candidate_budget.scope} ZIP compressed input exceeds "
                f"the {candidate_budget.compressed_input_limit}-byte aggregate "
                "scan limit"
            )
            return False
        if (
            candidate_budget.expanded_bytes + expanded_bytes
            > candidate_budget.expanded_output_limit
        ):
            findings.append(
                f"{archive_label}: {candidate_budget.scope} ZIP decoded output exceeds "
                f"the {candidate_budget.expanded_output_limit}-byte aggregate "
                "scan limit"
            )
            return False
        candidate_budget.compressed_bytes += compressed_bytes
        candidate_budget.expanded_bytes += expanded_bytes
        return True

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
        audio_signature = audio_payload_signature(content)
        if audio_signature:
            findings.append(
                f"{metadata_label}: audio signature {audio_signature!r} detected"
            )
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
                if "\\" in name:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: Windows path "
                        "separators are not allowed"
                    )
                entry = PurePosixPath(name.replace("\\", "/"))
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
                        if not reserve_candidate_work(info.compress_size, 0):
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
                bounded_apk_binary = False
                bounded_binary_secret = False
                if (
                    entry_suffix in ARCHIVE_BINARY_SUFFIXES
                    or is_known_archive_binary_path(entry)
                ):
                    if reason is not None:
                        continue
                    bounded_apk_binary = apk_content_context
                    if bounded_apk_binary:
                        if (
                            info.file_size > APK_BINARY_MEMBER_SCAN_LIMIT
                            or info.compress_size > APK_BINARY_MEMBER_SCAN_LIMIT
                        ):
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: APK binary "
                                f"exceeds the {APK_BINARY_MEMBER_SCAN_LIMIT}-byte "
                                "member scan limit"
                            )
                            continue
                    else:
                        if (
                            info.file_size > ZIP_BINARY_SECRET_MEMBER_LIMIT
                            or info.compress_size > ZIP_BINARY_SECRET_MEMBER_LIMIT
                        ):
                            findings.append(
                                f"{archive_label}: archive entry {name!r}: binary body "
                                f"exceeds the {ZIP_BINARY_SECRET_MEMBER_LIMIT}-byte "
                                "full secret-scan limit"
                            )
                            continue
                        bounded_binary_secret = True
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
                        > nested_total_limit
                        or nested_budget.expanded_bytes + info.file_size
                        > nested_total_limit
                    ):
                        findings.append(
                            f"{archive_label}: nested archives exceed the "
                            f"{nested_total_limit}-byte aggregate limit"
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
                    nested_budget.expanded_bytes += info.file_size
                    if not reserve_candidate_work(
                        info.compress_size,
                        info.file_size,
                    ):
                        continue
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
                            nested_total_limit=nested_total_limit,
                            _nested_depth=_nested_depth + 1,
                            _nested_budget=nested_budget,
                            _candidate_budget=candidate_budget,
                            _container_path=entry,
                            _apk_content_context=apk_content_context,
                            _apk_binary_budget=apk_binary_budget,
                            _binary_secret_budget=binary_secret_budget,
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
                bounded_skiko_icu = (
                    is_skiko_icu_data_path(entry, _container_path)
                    and info.file_size <= ZIP_SKIKO_ICU_SCAN_LIMIT
                )
                bounded_jimage = is_jdk_modules_path(entry)
                if bounded_jimage and (
                    info.file_size > ZIP_JIMAGE_FULL_SCAN_LIMIT
                    or info.compress_size > ZIP_JIMAGE_FULL_SCAN_LIMIT
                ):
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: JIMAGE body exceeds "
                        f"the {ZIP_JIMAGE_FULL_SCAN_LIMIT}-byte full scan limit"
                    )
                    continue
                if (
                    info.file_size > member_scan_limit
                    and not bounded_app_executable
                    and not bounded_skiko_icu
                    and not bounded_apk_binary
                    and not bounded_binary_secret
                    and not bounded_jimage
                ):
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{member_scan_limit}-byte member content scan limit"
                    )
                    continue
                entry_compressed_input_limit = (
                    APK_BINARY_MEMBER_SCAN_LIMIT
                    if bounded_apk_binary
                    else (
                        ZIP_JIMAGE_FULL_SCAN_LIMIT
                        if bounded_jimage
                        else (
                            ZIP_BINARY_SECRET_MEMBER_LIMIT
                            if bounded_binary_secret
                            else (
                                ZIP_SKIKO_ICU_SCAN_LIMIT
                                if bounded_skiko_icu
                                else compressed_input_limit
                            )
                        )
                    )
                )
                if info.compress_size > entry_compressed_input_limit:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: exceeds "
                        f"{entry_compressed_input_limit}-byte compressed payload scan limit"
                    )
                    continue
                if bounded_apk_binary:
                    assert apk_binary_budget is not None
                    if (
                        apk_binary_budget.expanded_bytes + info.file_size
                        > APK_BINARY_TOTAL_SCAN_LIMIT
                        or apk_binary_budget.compressed_bytes + info.compress_size
                        > APK_BINARY_TOTAL_SCAN_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: APK binary content exceeds the "
                            f"{APK_BINARY_TOTAL_SCAN_LIMIT}-byte aggregate scan limit"
                        )
                        continue
                elif bounded_binary_secret or bounded_jimage:
                    if (
                        binary_secret_budget.expanded_bytes + info.file_size
                        > ZIP_BINARY_SECRET_TOTAL_LIMIT
                        or binary_secret_budget.compressed_bytes + info.compress_size
                        > ZIP_BINARY_SECRET_TOTAL_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: binary/JIMAGE content exceeds the "
                            f"{ZIP_BINARY_SECRET_TOTAL_LIMIT}-byte aggregate scan limit"
                        )
                        continue
                elif bounded_skiko_icu:
                    if (
                        scanned_large_binary_bytes + info.file_size
                        > ZIP_SKIKO_ICU_SCAN_LIMIT
                        or scanned_large_binary_compressed_bytes + info.compress_size
                        > ZIP_SKIKO_ICU_SCAN_LIMIT
                    ):
                        findings.append(
                            f"{archive_label}: bounded Skiko ICU content exceeds the "
                            f"{ZIP_SKIKO_ICU_SCAN_LIMIT}-byte aggregate scan limit"
                        )
                        continue
                else:
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
                if not reserve_candidate_work(info.compress_size, info.file_size):
                    continue
                if bounded_apk_binary:
                    assert apk_binary_budget is not None
                    apk_binary_budget.expanded_bytes += info.file_size
                    apk_binary_budget.compressed_bytes += info.compress_size
                elif bounded_binary_secret or bounded_jimage:
                    binary_secret_budget.expanded_bytes += info.file_size
                    binary_secret_budget.compressed_bytes += info.compress_size
                elif bounded_skiko_icu:
                    scanned_large_binary_bytes += info.file_size
                    scanned_large_binary_compressed_bytes += info.compress_size
                else:
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
                            APK_BINARY_MEMBER_SCAN_LIMIT
                            if bounded_apk_binary
                            else (
                                ZIP_JIMAGE_FULL_SCAN_LIMIT
                                if bounded_jimage
                                else (
                                    ZIP_BINARY_SECRET_MEMBER_LIMIT
                                    if bounded_binary_secret
                                    else (
                                        ZIP_SKIKO_ICU_SCAN_LIMIT
                                        if bounded_skiko_icu
                                        else (
                                            total_scan_limit
                                            if bounded_app_executable
                                            else member_scan_limit
                                        )
                                    )
                                )
                            )
                        ),
                        compressed_input_limit=entry_compressed_input_limit,
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
                if bounded_jimage:
                    if not has_valid_jimage_header(content, len(content)):
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: JIMAGE body "
                            "has an invalid structural header"
                        )
                        continue
                elif bounded_skiko_icu:
                    if not has_valid_icu_data_header(content, len(content)):
                        findings.append(
                            f"{archive_label}: archive entry {name!r}: bounded Skiko "
                            "ICU data has an invalid structural header"
                        )
                        continue
                elif (
                    not bounded_apk_binary
                    and not bounded_binary_secret
                    and not bounded_jimage
                ):
                    if scanned_bytes + len(content) > total_scan_limit:
                        findings.append(
                            f"{archive_label}: archive content exceeded "
                            f"{total_scan_limit}-byte total content scan limit while reading"
                        )
                        break
                    scanned_bytes += len(content)
                nested_output_already_charged = False
                if _nested_depth > 0:
                    if (
                        nested_budget.expanded_bytes + len(content)
                        > nested_total_limit
                    ):
                        findings.append(
                            f"{archive_label}: recursively decoded content exceeds "
                            f"the {nested_total_limit}-byte nested aggregate limit"
                        )
                        continue
                    nested_budget.expanded_bytes += len(content)
                    nested_output_already_charged = True
                unsupported_format = unsupported_nested_archive_signature(content)
                if unsupported_format is not None:
                    findings.append(
                        f"{archive_label}: archive entry {name!r}: nested archive "
                        f"format {unsupported_format!r} is not supported"
                    )
                    continue
                if is_zip_compatible_payload(content):
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
                        nested_budget.compressed_bytes + info.compress_size
                        > nested_total_limit
                        or (
                            not nested_output_already_charged
                            and nested_budget.expanded_bytes + info.file_size
                            > nested_total_limit
                        )
                    ):
                        findings.append(
                            f"{archive_label}: nested archives exceed the "
                            f"{nested_total_limit}-byte aggregate limit"
                        )
                        continue
                    nested_budget.archive_count += 1
                    nested_budget.compressed_bytes += info.compress_size
                    if not nested_output_already_charged:
                        nested_budget.expanded_bytes += info.file_size
                    findings.extend(
                        scan_zip(
                            BytesIO(content),
                            label=f"{archive_label}: nested archive {name!r}",
                            member_scan_limit=member_scan_limit,
                            total_scan_limit=(
                                ZIP_CT_SYM_TOTAL_SCAN_LIMIT
                                if is_known_archive_binary_path(entry)
                                else total_scan_limit
                            ),
                            compressed_input_limit=(
                                ZIP_CT_SYM_TOTAL_SCAN_LIMIT
                                if is_known_archive_binary_path(entry)
                                else compressed_input_limit
                            ),
                            compression_ratio_limit=compression_ratio_limit,
                            entry_count_limit=(
                                ZIP_CT_SYM_ENTRY_COUNT_LIMIT
                                if is_known_archive_binary_path(entry)
                                else entry_count_limit
                            ),
                            lzma_dictionary_limit=lzma_dictionary_limit,
                            nested_total_limit=nested_total_limit,
                            _nested_depth=_nested_depth + 1,
                            _nested_budget=nested_budget,
                            _candidate_budget=candidate_budget,
                            _container_path=entry,
                            _apk_content_context=apk_content_context,
                            _apk_binary_budget=apk_binary_budget,
                            _binary_secret_budget=binary_secret_budget,
                        )
                    )
                    continue
                member_label = f"{archive_label}: archive entry {name!r}"
                audio_signature = audio_payload_signature(content)
                if audio_signature:
                    findings.append(
                        f"{member_label}: audio signature {audio_signature!r} detected"
                    )
                if (
                    bounded_apk_binary
                    or bounded_binary_secret
                    or bounded_jimage
                    or bounded_skiko_icu
                    or bounded_app_executable
                ):
                    findings.extend(scan_secret_bytes(member_label, content))
                else:
                    findings.extend(
                        scan_text(member_label, decode_text_for_secret_scan(content))
                    )

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
                        stream = archive.fp
                        pair_values = (
                            read_valid_apk_signing_block_values(
                                stream,
                                block_start=expected_offset,
                                central_directory_start=archive.start_dir,
                            )
                            if apk_archive and stream is not None
                            else None
                        )
                        if pair_values is None:
                            findings.append(
                                f"{archive_label}: ZIP local records leave unclaimed "
                                "bytes before the central directory"
                            )
                        else:
                            block_size = archive.start_dir - expected_offset
                            if reserve_candidate_work(block_size, block_size):
                                for index, value in enumerate(pair_values):
                                    pair_label = (
                                        f"{archive_label}: APK signing-block pair "
                                        f"{index + 1} value"
                                    )
                                    unsupported_format = (
                                        unsupported_nested_archive_signature(value)
                                    )
                                    if unsupported_format is not None:
                                        findings.append(
                                            f"{pair_label}: nested archive format "
                                            f"{unsupported_format!r} is not supported"
                                        )
                                        continue
                                    if is_zip_compatible_payload(value):
                                        if _nested_depth >= ZIP_NESTED_DEPTH_LIMIT:
                                            findings.append(
                                                f"{pair_label}: nested archive depth "
                                                f"exceeds {ZIP_NESTED_DEPTH_LIMIT}"
                                            )
                                            continue
                                        if (
                                            nested_budget.archive_count + 1
                                            > ZIP_NESTED_ARCHIVE_COUNT_LIMIT
                                        ):
                                            findings.append(
                                                f"{archive_label}: nested archive count "
                                                f"exceeds {ZIP_NESTED_ARCHIVE_COUNT_LIMIT}"
                                            )
                                            continue
                                        if (
                                            nested_budget.compressed_bytes + len(value)
                                            > nested_total_limit
                                            or nested_budget.expanded_bytes + len(value)
                                            > nested_total_limit
                                        ):
                                            findings.append(
                                                f"{archive_label}: nested archives exceed "
                                                f"the {nested_total_limit}-byte aggregate limit"
                                            )
                                            continue
                                        if not reserve_candidate_work(
                                            len(value),
                                            len(value),
                                        ):
                                            continue
                                        nested_budget.archive_count += 1
                                        nested_budget.compressed_bytes += len(value)
                                        nested_budget.expanded_bytes += len(value)
                                        findings.extend(
                                            scan_zip(
                                                BytesIO(value),
                                                label=f"{pair_label}: nested archive",
                                                member_scan_limit=member_scan_limit,
                                                total_scan_limit=total_scan_limit,
                                                compressed_input_limit=compressed_input_limit,
                                                compression_ratio_limit=compression_ratio_limit,
                                                entry_count_limit=entry_count_limit,
                                                lzma_dictionary_limit=lzma_dictionary_limit,
                                                nested_total_limit=nested_total_limit,
                                                _nested_depth=_nested_depth + 1,
                                                _nested_budget=nested_budget,
                                                _candidate_budget=candidate_budget,
                                                _container_path=PurePosixPath("pair.zip"),
                                                _apk_content_context=apk_content_context,
                                                _apk_binary_budget=apk_binary_budget,
                                                _binary_secret_budget=binary_secret_budget,
                                            )
                                        )
                                        continue
                                    audio_signature = audio_payload_signature(value)
                                    if audio_signature:
                                        findings.append(
                                            f"{pair_label}: audio signature "
                                            f"{audio_signature!r} detected"
                                        )
                                    findings.extend(
                                        scan_secret_bytes(pair_label, value)
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


def scan_secret_bytes(label: str, content: bytes) -> list[str]:
    safe_label = redact_secret_text(label)
    matched_patterns = {
        text_pattern.pattern
        for text_pattern, byte_pattern in zip(SECRET_PATTERNS, SECRET_BYTE_PATTERNS)
        if byte_pattern.search(content)
    }
    if content.startswith(TEXT_BOM_PREFIXES):
        decoded = decode_text_for_secret_scan(content)
        matched_patterns.update(
            pattern.pattern for pattern in SECRET_PATTERNS if pattern.search(decoded)
        )
    else:
        for encoding in ("utf-16-le", "utf-16-be"):
            encoded_prefixes = [prefix.encode(encoding) for prefix in SECRET_TEXT_PREFIXES]
            offsets = {
                index % 2
                for prefix in encoded_prefixes
                if (index := content.find(prefix)) >= 0
            }
            for offset in offsets:
                decoded = content[offset:].decode(encoding, errors="replace")
                matched_patterns.update(
                    pattern.pattern for pattern in SECRET_PATTERNS if pattern.search(decoded)
                )
    return [
        f"{safe_label}: secret-shaped content matched {pattern.pattern!r}"
        for pattern in SECRET_PATTERNS
        if pattern.pattern in matched_patterns
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
    candidate_budget = ZipCandidateScanBudget(
        archive_limit=HISTORICAL_ZIP_BLOB_COUNT_LIMIT,
        compressed_input_limit=HISTORICAL_ZIP_TOTAL_LIMIT,
        expanded_output_limit=HISTORICAL_ZIP_TOTAL_LIMIT,
        scope="historical",
    )
    nested_budget = ZipNestedScanBudget()
    apk_binary_budget = ApkContentScanBudget()
    binary_secret_budget = ZipBinaryScanBudget()
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
        findings.extend(
            scan_zip(
                BytesIO(content),
                label=archive_label,
                _nested_budget=nested_budget,
                _candidate_budget=candidate_budget,
                _apk_binary_budget=apk_binary_budget,
                _binary_secret_budget=binary_secret_budget,
            )
        )
    return findings


def scan_history() -> list[str]:
    findings: list[str] = []
    try:
        objects = historical_objects()
    except GitScanLimitError as error:
        findings.append(f"git history object inventory: {error}")
        objects = []
    for _, path in objects:
        reason = suspicious_path(path)
        if reason:
            findings.append(f"git history path {path}: {reason}")
    non_commit_objects, non_commit_findings = (
        historical_non_commit_tree_zip_objects()
    )
    findings.extend(non_commit_findings)
    historical_zip_candidates, historical_zip_findings = historical_zip_objects()
    findings.extend(historical_zip_findings)
    zip_objects = dict(historical_zip_candidates)
    for object_id, path in non_commit_objects:
        zip_objects.setdefault(object_id, path)
    structural_zip_candidates, structural_zip_findings = historical_structural_zip_objects(
        objects,
        set(zip_objects),
    )
    findings.extend(structural_zip_findings)
    for object_id, path in structural_zip_candidates:
        zip_objects.setdefault(object_id, path)
    findings.extend(scan_historical_zip_blobs(list(zip_objects.items())))

    # A textual patch includes every textual addition/deletion reachable from all
    # refs without checking out or materializing historical files. Binary payloads
    # are intentionally not printed; their suspicious names are covered above.
    try:
        history_patch = run_git(
            ["log", "--all", "--format=", "--no-ext-diff", "--no-textconv", "-p"],
        ).decode("utf-8", errors="replace")
    except GitScanLimitError as error:
        findings.append(f"git history patch: {error}")
    else:
        findings.extend(scan_text("git history patch", history_patch))
    return findings


def scan_public_path(
    path: Path | PurePosixPath,
    *,
    zip_budget: ZipCandidateScanBudget | None = None,
) -> list[str]:
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
        findings.extend(
            scan_zip(
                resolved_path,
                label=str(path),
                _candidate_budget=zip_budget,
            )
        )
        return findings

    try:
        raw_content = resolved_path.read_bytes()
    except OSError:
        return findings
    if is_zip_compatible_payload(raw_content):
        findings.extend(
            scan_zip(
                BytesIO(raw_content),
                label=str(path),
                _candidate_budget=zip_budget,
            )
        )
        return findings
    try:
        content = raw_content.decode("utf-8")
    except UnicodeDecodeError:
        return findings
    findings.extend(scan_text(str(path), content))
    return findings


def scan_explicit_text_file(
    path: Path,
    *,
    size_limit: int = EXPLICIT_TEXT_FILE_LIMIT,
) -> list[str]:
    if path.is_symlink():
        return [f"{path}: explicit text file must be a regular file, not a symlink"]
    try:
        size = path.stat().st_size
    except OSError as error:
        return [
            f"{path}: explicit text file preflight failed ({type(error).__name__})"
        ]
    if size > size_limit:
        return [
            f"{path}: explicit text file exceeds the {size_limit}-byte scan limit"
        ]
    try:
        content = path.read_bytes()
    except OSError as error:
        return [f"{path}: explicit text file read failed ({type(error).__name__})"]
    if len(content) != size:
        return [f"{path}: explicit text file size changed while reading"]
    findings: list[str] = []
    audio_signature = audio_payload_signature(content)
    if audio_signature:
        findings.append(f"{path}: audio signature {audio_signature!r} detected")
    findings.extend(scan_text(str(path), decode_text_for_secret_scan(content)))
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
        help="also scan this exact ZIP-compatible archive after it has been created",
    )
    parser.add_argument(
        "--text-file",
        action="append",
        default=[],
        type=Path,
        help="also scan this exact bounded text artifact after it has been created",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    paths = public_candidate_paths()
    findings: list[str] = []
    zip_budget = ZipCandidateScanBudget()
    for path in paths:
        findings.extend(scan_public_path(path, zip_budget=zip_budget))
    for archive_path in args.archive:
        if archive_path.is_symlink():
            findings.append(
                f"{archive_path}: explicit archive must be a regular file, not a symlink"
            )
            continue
        findings.extend(
            scan_zip(
                archive_path,
                label=str(archive_path),
                _candidate_budget=zip_budget,
            )
        )
    for text_path in args.text_file:
        findings.extend(scan_explicit_text_file(text_path))

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
    if args.text_file:
        scope += f" plus {len(args.text_file)} explicit text file(s)"
    print(
        f"PUBLIC SURFACE CHECK: PASS ({len(paths)} public candidates; {scope}; "
        "no credential, signing, or audio candidates)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
