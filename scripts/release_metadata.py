#!/usr/bin/env python3
"""Read and validate ChopLab's single-source release metadata."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections.abc import Iterable
from dataclasses import asdict, dataclass
from pathlib import Path

VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
RELEASE_TAG_PATTERN = re.compile(
    r"^v(?P<version>(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"
    r"(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?$"
)
FULL_SHA_PATTERN = re.compile(r"^[0-9a-fA-F]{40}$")
LEGACY_VERSION_CODE_PATTERN = re.compile(r"(?m)^\s*versionCode\s*=\s*([0-9]+)\s*$")
LEGACY_VERSION_NAME_PATTERN = re.compile(r'(?m)^\s*versionName\s*=\s*"([^"]+)"\s*$')


@dataclass(frozen=True)
class ReleaseMetadata:
    version: str
    build_number: int

    @property
    def tag(self) -> str:
        return f"v{self.version}"


def _parse_properties_text(content: str, *, path_label: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(content.splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"Invalid property at {path_label}:{line_number}")
        key, value = line.split("=", maxsplit=1)
        key = key.strip()
        value = value.strip()
        if not key:
            raise ValueError(f"Empty property name at {path_label}:{line_number}")
        values[key] = value
    return values


def read_gradle_properties(path: Path) -> dict[str, str]:
    return _parse_properties_text(path.read_text(encoding="utf-8"), path_label=str(path))


def load_release_metadata(path: Path) -> ReleaseMetadata:
    values = read_gradle_properties(path)
    version = values.get("choplabVersion", "")
    build_text = values.get("choplabBuildNumber", "")
    if VERSION_PATTERN.fullmatch(version) is None:
        raise ValueError(
            "choplabVersion must be numeric SemVer without a prerelease suffix "
            f"(for example 0.16.2), found: {version!r}"
        )
    try:
        build_number = int(build_text)
    except ValueError as error:
        raise ValueError(f"choplabBuildNumber must be an integer, found: {build_text!r}") from error
    if build_number <= 0:
        raise ValueError(f"choplabBuildNumber must be positive, found: {build_number}")
    return ReleaseMetadata(version=version, build_number=build_number)


def validate_tag(metadata: ReleaseMetadata, tag: str | None) -> None:
    if tag is None:
        return
    if tag != metadata.tag:
        raise ValueError(
            f"Release tag mismatch: expected {metadata.tag!r} from gradle.properties, found {tag!r}"
        )


def parse_release_tag(tag: str) -> tuple[int, int, int]:
    """Return the numeric version carried by a v* release tag.

    Historical preview tags carry a suffix, while the current source metadata
    deliberately remains numeric SemVer.  The suffix is ignored only for
    historical ordering; it is never accepted as the current source version.
    """

    match = RELEASE_TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError(f"Release tag must be v<SemVer> (optional preview suffix), found {tag!r}")
    version = tuple(int(part) for part in match.group("version").split("."))
    return version  # type: ignore[return-value]


def _version_tuple(version: str) -> tuple[int, int, int]:
    if VERSION_PATTERN.fullmatch(version) is None:
        raise ValueError(f"Release version must be numeric SemVer, found {version!r}")
    return tuple(int(part) for part in version.split("."))  # type: ignore[return-value]


def _normalize_commit(commit: str) -> str:
    if FULL_SHA_PATTERN.fullmatch(commit) is None:
        raise ValueError(f"Expected a full 40-character Git commit SHA, found {commit!r}")
    return commit.lower()


def validate_annotated_tag(
    *,
    tag: str,
    expected_commit: str,
    tag_object_type: str,
    peeled_commit: str,
) -> None:
    """Require an annotated tag whose peeled target is exactly expected_commit."""

    parse_release_tag(tag)
    normalized_expected = _normalize_commit(expected_commit)
    normalized_peeled = _normalize_commit(peeled_commit)
    if tag_object_type != "tag":
        raise ValueError(
            f"Release tag {tag!r} must be an annotated tag object, found {tag_object_type!r}"
        )
    if normalized_peeled != normalized_expected:
        raise ValueError(
            f"Release tag {tag!r} peeled commit {normalized_peeled} does not match "
            f"expected commit {normalized_expected}"
        )


def _run_git(repository: Path, arguments: list[str]) -> str:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise ValueError(f"Git command failed ({' '.join(arguments)}): {detail}")
    return result.stdout.strip()


def _run_git_optional(repository: Path, arguments: list[str]) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        check=False,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if result.returncode != 0:
        return None
    return result.stdout


def validate_annotated_tag_in_repository(
    repository: Path,
    *,
    tag: str,
    expected_commit: str,
) -> None:
    """Resolve and validate a tag from a checked-out repository."""

    parse_release_tag(tag)
    tag_ref = f"refs/tags/{tag}"
    _run_git(repository, ["rev-parse", "--verify", tag_ref])
    tag_object_type = _run_git(repository, ["cat-file", "-t", tag_ref])
    peeled_commit = _run_git(repository, ["rev-parse", "--verify", f"{tag_ref}^{{}}"])
    peeled_object_type = _run_git(repository, ["cat-file", "-t", peeled_commit])
    if peeled_object_type != "commit":
        raise ValueError(
            f"Release tag {tag!r} must peel to a commit object, found {peeled_object_type!r}"
        )
    validate_annotated_tag(
        tag=tag,
        expected_commit=expected_commit,
        tag_object_type=tag_object_type,
        peeled_commit=peeled_commit,
    )


def validate_monotonic_release_metadata(
    metadata: ReleaseMetadata,
    previous: Iterable[ReleaseMetadata],
) -> None:
    """Require release version/build coordinates to move forward.

    Version may stay the same for a rebuild, but then its build number must
    increase.  Android's versionCode is global, so the build number must also
    be greater than every reachable historical release regardless of version.
    """

    current_version = _version_tuple(metadata.version)
    for prior in previous:
        prior_version = _version_tuple(prior.version)
        if current_version < prior_version or (
            current_version == prior_version and metadata.build_number <= prior.build_number
        ):
            raise ValueError(
                "Release metadata is not monotonic: "
                f"{metadata.version} ({metadata.build_number}) is not newer than "
                f"{prior.version} ({prior.build_number})"
            )
        if metadata.build_number <= prior.build_number:
            raise ValueError(
                "Release metadata build number is not monotonic: "
                f"{metadata.build_number} must exceed {prior.build_number}"
            )


def _historical_release_metadata(repository: Path, tag: str) -> ReleaseMetadata:
    version_tuple = parse_release_tag(tag)
    version = ".".join(str(part) for part in version_tuple)

    properties = _run_git_optional(repository, ["show", f"{tag}:gradle.properties"])
    if properties is not None:
        values = _parse_properties_text(properties, path_label=f"{tag}:gradle.properties")
        source_version = values.get("choplabVersion")
        build_text = values.get("choplabBuildNumber")
        if source_version is not None or build_text is not None:
            if source_version != version:
                raise ValueError(
                    f"Historical tag {tag!r} version metadata {source_version!r} does not match tag"
                )
            try:
                build_number = int(build_text or "")
            except ValueError as error:
                raise ValueError(f"Historical tag {tag!r} has invalid build number") from error
            if build_number <= 0:
                raise ValueError(f"Historical tag {tag!r} has non-positive build number")
            return ReleaseMetadata(version=version, build_number=build_number)

    # Tags before the single-source properties file used app/build.gradle.kts.
    # Keep that compatibility strictly read-only and fail closed if neither
    # source carries a build identity.
    legacy_sources = (
        "app/build.gradle.kts",
        "app/build.gradle",
    )
    for source in legacy_sources:
        content = _run_git_optional(repository, ["show", f"{tag}:{source}"])
        if content is None:
            continue
        version_match = LEGACY_VERSION_NAME_PATTERN.search(content)
        if version_match and version_match.group(1) != version:
            raise ValueError(
                f"Historical tag {tag!r} versionName {version_match.group(1)!r} does not match tag"
            )
        build_match = LEGACY_VERSION_CODE_PATTERN.search(content)
        if build_match is None:
            continue
        build_number = int(build_match.group(1))
        if build_number <= 0:
            raise ValueError(f"Historical tag {tag!r} has non-positive build number")
        return ReleaseMetadata(version=version, build_number=build_number)

    raise ValueError(f"Historical tag {tag!r} has no readable build number")


def _reachable_release_tags(repository: Path, reachable_ref: str) -> tuple[str, ...]:
    resolved = _run_git(repository, ["rev-parse", "--verify", reachable_ref])
    raw = _run_git(
        repository,
        [
            "for-each-ref",
            "--merged",
            resolved,
            "--format=%(refname:strip=2)",
            "refs/tags/v*",
        ],
    )
    return tuple(tag for tag in raw.splitlines() if tag)


def validate_reachable_tag_monotonicity(
    repository: Path,
    metadata: ReleaseMetadata,
    *,
    reachable_ref: str = "HEAD",
    current_tag: str | None = None,
) -> None:
    """Compare metadata against every reachable v* tag, fail closed on gaps."""

    previous = [
        _historical_release_metadata(repository, tag)
        for tag in _reachable_release_tags(repository, reachable_ref)
        if tag != current_tag
    ]
    validate_monotonic_release_metadata(metadata, previous)


def append_github_output(path: Path, metadata: ReleaseMetadata) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"version={metadata.version}\n")
        output.write(f"build_number={metadata.build_number}\n")
        output.write(f"tag={metadata.tag}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--tag", help="Optional pushed tag; must exactly equal v<choplabVersion>.")
    parser.add_argument("--commit", help="Expected full commit SHA for tag identity checks.")
    parser.add_argument(
        "--verify-annotated-tag",
        action="store_true",
        help="Require --tag to be an annotated tag peeling to --commit.",
    )
    parser.add_argument(
        "--check-monotonic",
        action="store_true",
        help="Compare metadata against every reachable v* tag.",
    )
    parser.add_argument(
        "--reachable-ref",
        help=(
            "Git ref/commit whose reachable tags should be compared "
            "(defaults to --commit or HEAD)."
        ),
    )
    parser.add_argument("--github-output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    metadata = load_release_metadata(args.properties)
    validate_tag(metadata, args.tag)
    repository = args.properties.resolve().parent
    if args.verify_annotated_tag:
        if args.tag is None or args.commit is None:
            raise ValueError("--verify-annotated-tag requires both --tag and --commit")
        validate_annotated_tag_in_repository(
            repository,
            tag=args.tag,
            expected_commit=args.commit,
        )
    if args.check_monotonic:
        validate_reachable_tag_monotonicity(
            repository,
            metadata,
            reachable_ref=args.reachable_ref or args.commit or "HEAD",
            current_tag=args.tag,
        )
    if args.github_output is not None:
        append_github_output(args.github_output, metadata)
    payload = asdict(metadata) | {"tag": metadata.tag}
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
