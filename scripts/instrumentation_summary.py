#!/usr/bin/env python3
"""Parse the terminal summary from an Android instrumentation transcript."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import asdict, dataclass


_OK_SUMMARY = re.compile(r"(?im)^\s*OK \((\d+) tests?\)\s*$")
_FAILURE_MARKERS = (
    ("JUnit reported failures", re.compile(r"(?im)^\s*FAILURES!!!\s*$")),
    (
        "JUnit reported a non-zero failure count",
        re.compile(r"(?im)^\s*Tests run:\s*\d+\s*,\s*Failures:\s*[1-9]\d*\b"),
    ),
    ("instrumentation failed", re.compile(r"(?i)\bINSTRUMENTATION_FAILED\b")),
    ("instrumentation aborted", re.compile(r"(?i)\bINSTRUMENTATION_ABORTED\b")),
    ("instrumentation process crashed", re.compile(r"(?i)\bProcess crashed\b")),
)


@dataclass(frozen=True)
class InstrumentationSummary:
    passed: bool
    testCount: int | None
    reason: str | None


def parse_instrumentation_summary(transcript: str) -> InstrumentationSummary:
    """Accept exactly one positive OK summary and no failure/crash marker."""

    failure_reasons = [label for label, pattern in _FAILURE_MARKERS if pattern.search(transcript)]
    matches = _OK_SUMMARY.findall(transcript)

    if failure_reasons:
        return InstrumentationSummary(False, None, "; ".join(failure_reasons))
    if not matches:
        return InstrumentationSummary(False, None, "missing terminal OK summary")
    if len(matches) != 1:
        return InstrumentationSummary(False, None, "multiple terminal OK summaries")

    test_count = int(matches[0])
    if test_count <= 0:
        return InstrumentationSummary(False, test_count, "instrumentation ran zero tests")
    return InstrumentationSummary(True, test_count, None)


def main() -> int:
    summary = parse_instrumentation_summary(sys.stdin.read())
    print(json.dumps(asdict(summary), ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
