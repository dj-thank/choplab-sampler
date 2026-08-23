#!/usr/bin/env python3
"""Fail-closed check for credentials, signing material, and user audio in public content."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import zipfile
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


def historical_paths() -> list[PurePosixPath]:
    paths: list[PurePosixPath] = []
    for raw_line in run_git(["rev-list", "--objects", "--all"]).splitlines():
        parts = raw_line.decode("utf-8", errors="replace").split(" ", maxsplit=1)
        if len(parts) == 2 and parts[1]:
            paths.append(PurePosixPath(parts[1]))
    return paths


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


def scan_zip(path: PurePosixPath) -> list[str]:
    findings: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                entry = PurePosixPath(name)
                reason = suspicious_path(entry)
                if reason:
                    findings.append(f"{path}: archive entry {name!r}: {reason}")
    except (OSError, zipfile.BadZipFile):
        return findings
    return findings


def scan_text(label: str, content: str) -> list[str]:
    return [
        f"{label}: secret-shaped content matched {pattern.pattern!r}"
        for pattern in SECRET_PATTERNS
        if pattern.search(content)
    ]


def scan_history() -> list[str]:
    findings: list[str] = []
    for path in historical_paths():
        reason = suspicious_path(path)
        if reason:
            findings.append(f"git history path {path}: {reason}")

    # A textual patch includes every textual addition/deletion reachable from all
    # refs without checking out or materializing historical files. Binary payloads
    # are intentionally not printed; their suspicious names are covered above.
    history_patch = run_git(
        ["log", "--all", "--format=", "--no-ext-diff", "--no-textconv", "-p"],
    ).decode("utf-8", errors="replace")
    findings.extend(scan_text("git history patch", history_patch))
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
        reason = suspicious_path(path)
        if reason:
            findings.append(f"{path}: {reason}")
        if path.suffix.lower() == ".zip":
            findings.extend(scan_zip(path))

        try:
            content = Path(path).read_bytes().decode("utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        findings.extend(scan_text(str(path), content))

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
