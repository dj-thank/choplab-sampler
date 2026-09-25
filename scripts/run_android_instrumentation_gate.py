#!/usr/bin/env python3
"""Run the Linux emulator gate in one process, preserving Gradle and XML failures.

android-emulator-runner executes every input line in a separate ``sh -c``.
Keep its workflow input to one invocation of this file, not inline shell state.
"""

from __future__ import annotations

from dataclasses import asdict
import json
from pathlib import Path
import subprocess
import sys

from instrumentation_summary import summarize_instrumentation_xml_files


def instrumentation_xml_paths(build_root: Path) -> list[Path]:
    """Select only regular instrumentation XML files, not host-unit reports."""
    results: list[Path] = []
    for path in build_root.rglob("*.xml"):
        relative = "/" + path.relative_to(build_root).as_posix()
        if (
            not path.is_symlink()
            and path.is_file()
            and (
                "/outputs/androidTest-results/" in relative
                or "/test-results/connected/" in relative
            )
        ):
            results.append(path)
    return sorted(results)


def run_gate(repo_root: Path) -> int:
    """An all-green XML result can never hide a failed Gradle invocation."""
    try:
        completed = subprocess.run(
            ["./gradlew", "--stacktrace", ":app:connectedDebugAndroidTest"],
            cwd=repo_root,
            check=False,
        )
    except OSError as error:
        print(f"Could not start Android instrumentation: {error}", file=sys.stderr)
        return 1

    summary_status = 1
    try:
        paths = instrumentation_xml_paths(repo_root / "app" / "build")
        summary = summarize_instrumentation_xml_files(paths)
        print(json.dumps(asdict(summary), ensure_ascii=False, separators=(",", ":")))
        summary_status = 0 if summary.passed else 1
    except (OSError, UnicodeError) as error:
        print(f"Could not read Android instrumentation XML: {error}", file=sys.stderr)

    if completed.returncode:
        print(
            f"Gradle instrumentation exited with status {completed.returncode}; "
            "XML results do not override it.",
            file=sys.stderr,
        )
        return completed.returncode if completed.returncode > 0 else 128 - completed.returncode
    return summary_status


if __name__ == "__main__":
    raise SystemExit(run_gate(Path(__file__).resolve().parents[1]))
