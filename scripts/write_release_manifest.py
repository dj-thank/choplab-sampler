#!/usr/bin/env python3
"""Create a deterministic manifest that binds public files to one source commit."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

SHA256_SUFFIX = ".sha256"
EXPECTED_BINARY_PATTERNS = {
    "android": re.compile(r"^ChopLab-v[^/]+-android\.apk$"),
    "ios_simulator": re.compile(r"^ChopLab-v[^/]+-ios-simulator\.app\.zip$"),
    "windows": re.compile(r"^ChopLab-v[^/]+-windows-app-image\.zip$"),
}
EXPECTED_SBOM_PATTERN = re.compile(r"^ChopLab-v[^/]+-sbom\.cdx\.json$")


@dataclass(frozen=True)
class ReleaseAsset:
    name: str
    size_bytes: int
    sha256: str


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_assets(directory: Path, output: Path) -> list[ReleaseAsset]:
    files = sorted(
        path
        for path in directory.iterdir()
        if path.is_file()
        and path.resolve() != output.resolve()
        and not path.name.endswith(SHA256_SUFFIX)
        and path.name != "SHA256SUMS"
    )
    return [
        ReleaseAsset(name=path.name, size_bytes=path.stat().st_size, sha256=sha256(path))
        for path in files
    ]


def validate_expected_binaries(assets: list[ReleaseAsset], version: str) -> None:
    expected_prefix = f"ChopLab-v{version}-"
    binary_names = {asset.name for asset in assets}
    for platform, pattern in EXPECTED_BINARY_PATTERNS.items():
        matches = sorted(name for name in binary_names if pattern.fullmatch(name))
        if len(matches) != 1:
            raise ValueError(
                f"Expected exactly one {platform} binary, found {len(matches)}: {matches}"
            )
        if not matches[0].startswith(expected_prefix):
            raise ValueError(
                f"{platform} binary version mismatch: expected prefix {expected_prefix!r}, "
                f"found {matches[0]!r}"
            )


def validate_checksum_sidecars(
    directory: Path,
    assets: list[ReleaseAsset],
    version: str,
) -> None:
    assets_by_name = {asset.name: asset for asset in assets}
    expected_prefix = f"ChopLab-v{version}-"
    required_names = {
        asset.name
        for asset in assets
        if any(pattern.fullmatch(asset.name) for pattern in EXPECTED_BINARY_PATTERNS.values())
    }
    sbom_names = sorted(
        asset.name for asset in assets if EXPECTED_SBOM_PATTERN.fullmatch(asset.name)
    )
    if len(sbom_names) != 1:
        raise ValueError(f"Expected exactly one release SBOM, found {len(sbom_names)}: {sbom_names}")
    if not sbom_names[0].startswith(expected_prefix):
        raise ValueError(
            f"SBOM version mismatch: expected prefix {expected_prefix!r}, found {sbom_names[0]!r}"
        )
    required_names.add(sbom_names[0])

    sidecars = sorted(path for path in directory.iterdir() if path.is_file() and path.name.endswith(SHA256_SUFFIX))
    sidecars_by_target = {path.name[: -len(SHA256_SUFFIX)]: path for path in sidecars}
    missing = sorted(name for name in required_names if name not in sidecars_by_target)
    if missing:
        raise ValueError(f"Missing checksum sidecar(s): {missing}")

    for target_name, sidecar in sidecars_by_target.items():
        asset = assets_by_name.get(target_name)
        if asset is None:
            raise ValueError(f"Checksum sidecar has no published target: {sidecar.name}")
        text = sidecar.read_text(encoding="utf-8-sig").strip()
        match = re.fullmatch(r"([0-9a-fA-F]{64})[ \t]+(?:\*)?([^\r\n]+)", text)
        if match is None:
            raise ValueError(f"Malformed checksum sidecar: {sidecar.name}")
        declared_digest, declared_name = match.groups()
        if declared_name != target_name:
            raise ValueError(
                f"Checksum sidecar filename mismatch: {sidecar.name} declares {declared_name!r}"
            )
        if declared_digest.lower() != asset.sha256:
            raise ValueError(f"Checksum mismatch for {target_name}")


def write_manifest(
    *,
    directory: Path,
    output: Path,
    version: str,
    build_number: int,
    tag: str,
    commit: str,
    repository: str,
    workflow_run_id: str,
) -> dict[str, object]:
    if tag != f"v{version}":
        raise ValueError(f"Tag/version mismatch: expected v{version!s}, found {tag!r}")
    if build_number <= 0:
        raise ValueError("build_number must be positive")
    if re.fullmatch(r"[0-9a-f]{40}", commit) is None:
        raise ValueError("commit must be a full lowercase 40-character Git SHA")
    if not repository or "/" not in repository:
        raise ValueError("repository must be owner/name")

    output.parent.mkdir(parents=True, exist_ok=True)
    assets = collect_assets(directory, output)
    validate_expected_binaries(assets, version)
    validate_checksum_sidecars(directory, assets, version)
    payload: dict[str, object] = {
        "schema_version": 1,
        "product": "ChopLab",
        "version": version,
        "build_number": build_number,
        "tag": tag,
        "source": {
            "repository": repository,
            "commit": commit,
            "workflow_run_id": workflow_run_id,
        },
        "assets": [asdict(asset) for asset in assets],
    }
    output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return payload


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build-number", type=int, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--workflow-run-id", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    payload = write_manifest(
        directory=args.directory,
        output=args.output,
        version=args.version,
        build_number=args.build_number,
        tag=args.tag,
        commit=args.commit,
        repository=args.repository,
        workflow_run_id=args.workflow_run_id,
    )
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
