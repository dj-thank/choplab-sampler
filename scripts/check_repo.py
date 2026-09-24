#!/usr/bin/env python3
"""Small repository entry point; archive/history policy remains in its tested scanner."""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

WRAPPER_SHA256 = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"
PERSONAL_HOME = re.compile(r"(?i)(?:[A-Z]:[\\/]+Users[\\/]+|/home/)([A-Za-z0-9_.-]+)")
EXAMPLE_USERS = {"user", "username", "your-user", "example", "runner", "public", "default", "test", "fixture"}


def contains_personal_path(text: str) -> bool:
    for match in PERSONAL_HOME.finditer(text):
        if match[1].lower() in EXAMPLE_USERS:
            continue
        # Standard devcontainer Gradle volume only; other folders still fail.
        container_home = "/home/" + "vscode"
        if match[0] == container_home and re.match(r"/\.gradle(?:[/,\s\"\']|$)", text[match.end():]):
            continue
        return True
    return False


def structural_findings(root: Path) -> list[str]:
    findings = []
    wrapper = root / "gradle/wrapper/gradle-wrapper.jar"
    if not wrapper.is_file() or hashlib.sha256(wrapper.read_bytes()).hexdigest() != WRAPPER_SHA256:
        findings.append("Gradle wrapper JAR checksum mismatch or missing")
    for name in ("gradlew", "gradlew.bat"):
        path = root / name
        if not path.is_file() or "-Dfile.encoding=UTF-8" not in path.read_text(encoding="utf-8"):
            findings.append(f"Wrapper must force UTF-8: {name}")
    for path in sorted((root / "app/src").rglob("*.xml")):
        try:
            ET.parse(path)
        except (ET.ParseError, OSError) as error:
            findings.append(f"Invalid Android XML: {path.relative_to(root)} ({type(error).__name__})")
    for path in sorted((root / ".github/workflows").glob("*.yml")):
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.search(r"\buses:\s+([^\s#]+)", line)
            if match and not re.fullmatch(r"[^@\s]+@[0-9a-f]{40}", match[1]):
                findings.append(f"Action must be pinned to a full commit: {path.name}")
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--history", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    findings = structural_findings(root)
    staged = subprocess.run(["git", "ls-files", "--stage", "--", "gradlew", "scripts/*.sh"],
                            cwd=root, check=True, capture_output=True, text=True, encoding="utf-8")
    for line in staged.stdout.splitlines():
        metadata, name = line.split("\t", 1)
        if (root / name).exists() and metadata.split()[0] != "100755":
            findings.append(f"Tracked executable must use Git mode 100755: {name}")
    candidates = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                                cwd=root, check=True, capture_output=True).stdout.split(b"\0")
    for raw_name in candidates:
        if not raw_name:
            continue
        path = root / raw_name.decode("utf-8")
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
            continue
        if path.suffix.lower() not in {".md", ".py", ".kt", ".kts", ".sh", ".ps1", ".yml", ".yaml", ".json", ".toml", ".properties", ".txt"}:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if contains_personal_path(text):
            findings.append(f"Personal host path in public source: {path.relative_to(root).as_posix()}")
    for finding in findings:
        print(f"ERROR: {finding}", file=sys.stderr)
    if findings:
        return 1
    command = [sys.executable, str(root / "scripts/check_public_surface.py")]
    if args.history:
        command.append("--history")
    return subprocess.run(command, cwd=root, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
