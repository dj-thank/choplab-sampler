# Release hardening receipt — 2026-09-03

## Scope

This candidate is based on `main@bed7a550a71b1ae91556b2b2af25d7c482083c98` and changes CI/release policy, verification scripts/tests and product-scope documentation. Android, shared DSP/UI, JVM core, Desktop runtime and iOS product sources are inherited from the already hosted-verified main line.

## Enforced controls

- Pull requests always create uniquely named `Android verification`, `Windows desktop verification`, `iOS verification` and `Supply-chain policy` jobs; branch pushes run them only on `main`.
- Android instrumentation XML must contain at least one test and zero failures, errors or skips. Debug APK signing identity is read before the build and compared with the final APK certificate without exporting private-key material.
- Android and iOS read version `0.17.2 (29)` from `gradle.properties`; release metadata rejects mismatched tags, lightweight tags, non-monotonic version/build history and a tag target not reachable from `main`.
- The immutable release manifest permits exactly the Android debug APK, unsigned iOS Simulator archive and CycloneDX SBOM plus declared checksum records. Windows app-image remains a verification artifact and never enters the public release job.
- Current/history source, committed-source ZIP and final artifact scans remain fail-closed under separate resource limits.
- [`DEFINITION_OF_DONE.md`](../docs/DEFINITION_OF_DONE.md) defines the single current product line. Historical Pro/MIDI/stems/Oboe/effects material is future non-scope, not a second unfinished product.

## Local verification

- `python -m unittest discover -s scripts/tests -p 'test_*.py'`: 242 tests pass; one Windows filesystem-symlink privilege skip.
- `python scripts/check_public_surface.py`: 493 candidates, PASS.
- `python scripts/check_public_surface.py --history`: 493 candidates, PASS.
- `python scripts/release_metadata.py`: version `0.17.2`, build `29`, tag `v0.17.2`.
- `git diff --check`: PASS.

Hosted exact-head checks, normal merge, repository ruleset read-back, annotated tag creation, immutable publication, reverse download and anonymous read-back remain subsequent gates and are not claimed by this local receipt.
