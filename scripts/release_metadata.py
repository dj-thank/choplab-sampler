#!/usr/bin/env python3
"""Read and validate ChopLab's single-source release metadata."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

VERSION_PATTERN = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


@dataclass(frozen=True)
class ReleaseMetadata:
    version: str
    build_number: int

    @property
    def tag(self) -> str:
        return f"v{self.version}"


def read_gradle_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"Invalid property at {path}:{line_number}")
        key, value = line.split("=", maxsplit=1)
        key = key.strip()
        value = value.strip()
        if not key:
            raise ValueError(f"Empty property name at {path}:{line_number}")
        values[key] = value
    return values


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


def append_github_output(path: Path, metadata: ReleaseMetadata) -> None:
    with path.open("a", encoding="utf-8", newline="\n") as output:
        output.write(f"version={metadata.version}\n")
        output.write(f"build_number={metadata.build_number}\n")
        output.write(f"tag={metadata.tag}\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--properties", type=Path, default=Path("gradle.properties"))
    parser.add_argument("--tag", help="Optional pushed tag; must exactly equal v<choplabVersion>.")
    parser.add_argument("--github-output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    metadata = load_release_metadata(args.properties)
    validate_tag(metadata, args.tag)
    if args.github_output is not None:
        append_github_output(args.github_output, metadata)
    payload = asdict(metadata) | {"tag": metadata.tag}
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
