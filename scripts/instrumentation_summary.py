#!/usr/bin/env python3
"""Parse the terminal summary from an Android instrumentation transcript."""

from __future__ import annotations

import argparse
import json
from collections.abc import Iterable, Sequence
from pathlib import Path
import re
import sys
from dataclasses import asdict, dataclass
import xml.etree.ElementTree as ET


_OK_SUMMARY = re.compile(r"(?im)^\s*OK \((\d+) tests?\)\s*$")
_FAILURE_MARKERS = (
    ("JUnit reported failures", re.compile(r"(?im)^\s*FAILURES!!!\s*$")),
    (
        "JUnit reported a non-zero failure count",
        re.compile(r"(?im)^\s*Tests run:\s*\d+\s*,\s*Failures:\s*[1-9]\d*\b"),
    ),
    (
        "JUnit reported a non-zero error count",
        re.compile(r"(?im)^\s*Tests run:\s*\d+.*\bErrors:\s*[1-9]\d*\b"),
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


@dataclass(frozen=True)
class InstrumentationXmlSummary:
    passed: bool
    tests: int
    failures: int
    errors: int
    skips: int
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


def _parse_count(element: ET.Element, attribute: str, *, source: str) -> int | None:
    raw = element.attrib.get(attribute)
    if raw is None:
        return None
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{source} has a non-integer {attribute} count") from error
    if value < 0:
        raise ValueError(f"{source} has a negative {attribute} count")
    return value


def _parse_declared_skips(element: ET.Element, *, source: str) -> int | None:
    skipped = _parse_count(element, "skipped", source=source)
    skips = _parse_count(element, "skips", source=source)
    if skipped is not None and skips is not None and skipped != skips:
        raise ValueError(f"{source} has conflicting skipped/skips counts")
    declared = skipped if skipped is not None else skips
    disabled = _parse_count(element, "disabled", source=source)
    if disabled:
        declared = (declared or 0) + disabled
    return declared


def _local_name(tag: str) -> str:
    return tag.rsplit("}", maxsplit=1)[-1]


def _actual_testcase_counts(testcases: Sequence[ET.Element]) -> tuple[int, int, int, int]:
    actual_tests = len(testcases)
    actual_failures = sum(
        any(_local_name(child.tag) == "failure" for child in list(testcase))
        for testcase in testcases
    )
    actual_errors = sum(
        any(_local_name(child.tag) == "error" for child in list(testcase))
        for testcase in testcases
    )
    actual_skips = sum(
        testcase.attrib.get("status", "").lower()
        in {"ignored", "notrun", "skipped", "disabled"}
        or any(
            _local_name(child.tag) in {"ignored", "skipped"}
            for child in list(testcase)
        )
        for testcase in testcases
    )
    return actual_tests, actual_failures, actual_errors, actual_skips


def _validate_declared_counts(
    element: ET.Element,
    actual: tuple[int, int, int, int],
    *,
    source: str,
) -> None:
    declared = (
        _parse_count(element, "tests", source=source),
        _parse_count(element, "failures", source=source),
        _parse_count(element, "errors", source=source),
        _parse_declared_skips(element, source=source),
    )
    for attribute, expected, observed in zip(
        ("tests", "failures", "errors", "skipped"), declared, actual
    ):
        if expected is not None and expected != observed:
            raise ValueError(
                f"{source} declared {attribute}={expected}, does not match actual {observed}"
            )


def _parse_suite(element: ET.Element, *, source: str) -> tuple[int, int, int, int]:
    child_suites = [
        child for child in list(element) if _local_name(child.tag) == "testsuite"
    ]
    testcases = [
        child for child in list(element) if _local_name(child.tag) == "testcase"
    ]
    direct_counts = _actual_testcase_counts(testcases)
    child_counts = [_parse_suite(child, source=source) for child in child_suites]
    actual = tuple(
        direct_counts[index] + sum(count[index] for count in child_counts)
        for index in range(4)
    )
    if not child_suites and not testcases:
        declared_tests = _parse_count(element, "tests", source=source)
        if (declared_tests or 0) > 0:
            raise ValueError(
                f"{source} declared tests={declared_tests}, but has no testcase results"
            )
    _validate_declared_counts(element, actual, source=source)
    return actual


def _suite_summaries(root: ET.Element, *, source: str) -> Iterable[tuple[int, int, int, int]]:
    root_name = _local_name(root.tag)
    if root_name == "testsuite":
        yield _parse_suite(root, source=source)
        return

    suites = [child for child in list(root) if _local_name(child.tag) == "testsuite"]
    if not suites:
        raise ValueError(f"{source} contains no testsuite elements")
    child_counts = [_parse_suite(suite, source=source) for suite in suites]
    actual = tuple(
        sum(count[index] for count in child_counts) for index in range(4)
    )
    _validate_declared_counts(root, actual, source=source)
    yield actual


def parse_instrumentation_xml(
    xml_text: str,
    *,
    source: str = "instrumentation XML",
) -> InstrumentationXmlSummary:
    """Parse one JUnit XML result and enforce a non-empty, all-green suite."""

    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as error:
        return InstrumentationXmlSummary(False, 0, 0, 0, 0, f"invalid XML: {error}")
    if _local_name(root.tag) not in {"testsuite", "testsuites"}:
        return InstrumentationXmlSummary(
            False, 0, 0, 0, 0, f"unexpected instrumentation XML root: {_local_name(root.tag)}"
        )
    try:
        counts = list(_suite_summaries(root, source=source))
    except ValueError as error:
        return InstrumentationXmlSummary(False, 0, 0, 0, 0, str(error))
    tests = sum(item[0] for item in counts)
    failures = sum(item[1] for item in counts)
    errors = sum(item[2] for item in counts)
    skips = sum(item[3] for item in counts)
    if tests <= 0:
        reason = "instrumentation XML reported zero tests"
    elif failures or errors or skips:
        reason = (
            "instrumentation XML is not all-green: "
            f"failures={failures}, errors={errors}, skips={skips}"
        )
    else:
        reason = None
    return InstrumentationXmlSummary(
        reason is None,
        tests,
        failures,
        errors,
        skips,
        reason,
    )


def summarize_instrumentation_xml_files(paths: Sequence[Path]) -> InstrumentationXmlSummary:
    """Aggregate JUnit XML files without allowing missing or invalid files."""

    if not paths:
        return InstrumentationXmlSummary(False, 0, 0, 0, 0, "no instrumentation XML files found")
    summaries: list[InstrumentationXmlSummary] = []
    for path in paths:
        if not path.is_file():
            return InstrumentationXmlSummary(
                False, 0, 0, 0, 0, f"instrumentation XML file is missing: {path}"
            )
        summaries.append(
            parse_instrumentation_xml(path.read_text(encoding="utf-8"), source=str(path))
        )
    tests = sum(summary.tests for summary in summaries)
    failures = sum(summary.failures for summary in summaries)
    errors = sum(summary.errors for summary in summaries)
    skips = sum(summary.skips for summary in summaries)
    failed_reasons = [summary.reason for summary in summaries if summary.reason]
    reason = "; ".join(reason for reason in failed_reasons if reason) or None
    if tests <= 0 and reason is None:
        reason = "instrumentation XML reported zero tests"
    if tests > 0 and (failures or errors or skips) and reason is None:
        reason = (
            "instrumentation XML is not all-green: "
            f"failures={failures}, errors={errors}, skips={skips}"
        )
    return InstrumentationXmlSummary(
        reason is None,
        tests,
        failures,
        errors,
        skips,
        reason,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--xml",
        dest="xml_paths",
        type=Path,
        action="append",
        help="JUnit instrumentation XML path; repeat for multiple result files.",
    )
    args = parser.parse_args()
    if args.xml_paths:
        summary = summarize_instrumentation_xml_files(args.xml_paths)
        print(json.dumps(asdict(summary), ensure_ascii=False, separators=(",", ":")))
        return 0 if summary.passed else 1
    summary = parse_instrumentation_summary(sys.stdin.read())
    print(json.dumps(asdict(summary), ensure_ascii=False, separators=(",", ":")))
    return 0 if summary.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
