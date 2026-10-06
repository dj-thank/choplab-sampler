"""Exact, reviewed Mac native dependency sets; no filename patterns or partial matches."""
from pathlib import Path

MEDIA_POLICY_FILES = (
    "config/mac-media-tool-files.txt",
    "config/mac-media-tool-files-20261006.txt",
)


def media_file_sets(root: Path) -> dict[str, frozenset[str]]:
    policies = {}
    for name in MEDIA_POLICY_FILES:
        lines = (root / name).read_text(encoding="utf-8").splitlines()
        if not lines or len(set(lines)) != len(lines) or any(
                not value or Path(value).name != value or "\\" in value for value in lines):
            raise ValueError(f"Invalid Mac tool policy: {name}")
        policies[name] = frozenset(lines)
    return policies


def matching_policy(files, policies: dict[str, frozenset[str]]) -> str | None:
    actual = frozenset(files)
    return next((name for name, expected in policies.items() if actual == expected), None)
