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

from instrumentation_summary import failing_instrumentation_cases, summarize_instrumentation_xml_files


def instrumentation_xml_paths(build_root: Path) -> list[Path]:
    """Select only regular instrumentation XML files, not host-unit reports."""
    results: list[Path] = []
    if build_root.is_symlink():
        return results
    for directory in (build_root / "outputs/androidTest-results", build_root / "test-results/connected"):
        if directory.is_symlink() or directory.parent.is_symlink():
            continue
        for path in directory.rglob("*.xml"):
            if not path.is_symlink() and path.is_file():
                results.append(path)
    return sorted(results)


def run_gate(repo_root: Path) -> int:
    """An all-green XML result can never hide a failed Gradle invocation."""
    build_root = repo_root / "app" / "build"
    if any(path.is_symlink() for path in (repo_root, build_root.parent, build_root)):
        print("Android instrumentation requires regular repository, app and build directories.", file=sys.stderr)
        return 1
    try:
        # Only reports from this invocation can attest to its instrumentation result.
        # Keep host-unit reports, diagnostic logs and other build outputs intact.
        for path in instrumentation_xml_paths(build_root):
            path.unlink()
    except OSError as error:
        print(f"Could not remove previous Android instrumentation XML: {error}", file=sys.stderr)
        return 1
    try:
        completed = subprocess.run(
            ["./gradlew", "--stacktrace", ":app:connectedDebugAndroidTest",
             # The native candidate gate requires an owned arm64 target, explicit opt-in and
             # reviewed runtime hashes. Its separate class/argument invocation remains mandatory.
             "-Pandroid.testInstrumentationRunnerArguments.notClass=com.choplab.sampler.audio.AndroidNativeCodecTest"],
            cwd=repo_root,
            check=False,
        )
    except OSError as error:
        print(f"Could not start Android instrumentation: {error}", file=sys.stderr)
        return 1

    summary_status = 1
    try:
        paths = instrumentation_xml_paths(build_root)
        summary = summarize_instrumentation_xml_files(paths)
        print(json.dumps(asdict(summary), ensure_ascii=False, separators=(",", ":")))
        summary_status = 0 if summary.passed else 1
        for case in failing_instrumentation_cases(paths):
            print(case, file=sys.stderr)
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
