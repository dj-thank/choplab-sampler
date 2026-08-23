from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.verify_sbom import verify_sbom


class VerifySbomTest(unittest.TestCase):
    def write_sbom(self, payload: dict[str, object]) -> Path:
        handle = tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", delete=False)
        with handle:
            json.dump(payload, handle)
        path = Path(handle.name)
        self.addCleanup(path.unlink)
        return path

    def valid_payload(self) -> dict[str, object]:
        return {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "metadata": {
                "component": {
                    "group": "com.choplab",
                    "name": "ChopLab",
                    "version": "0.17.0",
                }
            },
            "components": [{"name": "kotlin-stdlib", "version": "2.4.10"}],
            "dependencies": [{"ref": "pkg:maven/com.choplab/ChopLab@0.17.0"}],
        }

    def test_accepts_release_bound_dependency_graph(self) -> None:
        result = verify_sbom(
            self.write_sbom(self.valid_payload()),
            expected_group="com.choplab",
            expected_name="ChopLab",
            expected_version="0.17.0",
        )

        self.assertEqual(1, result["component_count"])
        self.assertEqual(1, result["dependency_count"])

    def test_rejects_unspecified_release_identity(self) -> None:
        payload = self.valid_payload()
        payload["metadata"] = {
            "component": {"group": "", "name": "ChopLab", "version": "unspecified"}
        }

        with self.assertRaisesRegex(ValueError, "component.group mismatch"):
            verify_sbom(
                self.write_sbom(payload),
                expected_group="com.choplab",
                expected_name="ChopLab",
                expected_version="0.17.0",
            )

    def test_rejects_empty_component_or_dependency_sets(self) -> None:
        payload = self.valid_payload()
        payload["components"] = []
        with self.assertRaisesRegex(ValueError, "resolved component"):
            verify_sbom(
                self.write_sbom(payload),
                expected_group="com.choplab",
                expected_name="ChopLab",
                expected_version="0.17.0",
            )

        payload = self.valid_payload()
        payload["dependencies"] = []
        with self.assertRaisesRegex(ValueError, "dependency graph"):
            verify_sbom(
                self.write_sbom(payload),
                expected_group="com.choplab",
                expected_name="ChopLab",
                expected_version="0.17.0",
            )
