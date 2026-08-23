#!/usr/bin/env python3
"""Verify that a CycloneDX SBOM is release-bound and structurally useful."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def verify_sbom(
    path: Path,
    *,
    expected_group: str,
    expected_name: str,
    expected_version: str,
) -> dict[str, int | str]:
    data: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if data.get("bomFormat") != "CycloneDX":
        raise ValueError("SBOM format must be CycloneDX")
    if data.get("specVersion") not in {"1.6", "1.7"}:
        raise ValueError(f"Unsupported CycloneDX specVersion: {data.get('specVersion')!r}")

    component = data.get("metadata", {}).get("component", {})
    expected = {
        "group": expected_group,
        "name": expected_name,
        "version": expected_version,
    }
    for field, value in expected.items():
        if component.get(field) != value:
            raise ValueError(
                f"SBOM metadata.component.{field} mismatch: "
                f"expected {value!r}, found {component.get(field)!r}"
            )

    components = data.get("components")
    dependencies = data.get("dependencies")
    if not isinstance(components, list) or not components:
        raise ValueError("SBOM must contain at least one resolved component")
    if not isinstance(dependencies, list) or not dependencies:
        raise ValueError("SBOM must contain a dependency graph")

    return {
        "spec_version": str(data["specVersion"]),
        "component_count": len(components),
        "dependency_count": len(dependencies),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", type=Path, required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    result = verify_sbom(
        args.sbom,
        expected_group=args.group,
        expected_name=args.name,
        expected_version=args.version,
    )
    print(
        "Verified CycloneDX SBOM: "
        f"{args.group}:{args.name}:{args.version} "
        f"spec={result['spec_version']} "
        f"components={result['component_count']} "
        f"dependencies={result['dependency_count']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
