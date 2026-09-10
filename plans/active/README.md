# ExecPlan registry

Updated: 2026-09-10

## Current selection

Exactly one plan is selected: [Runtime quality: recording safety, stereo analysis and bounded visual work](runtime-quality-20260910.md).

The base is PR95 `d1626def5c8f7f117e954d71034b0e32d048e99a`. Reconcile PR93's startup/display work rather than replacing it, integrate prior local startup preparation, and validate the combined candidate. No main merge, release, dependency update or physical-device claim is authorized by a checkbox here.

## Evidence and outstanding gates

190 selected actual Kotlin test bodies pass under the temporary host runner; the current receipt records ten baseline-negative regressions and 240 performance measurements. Complete supported-toolchain CI, real UI/Android instrumentation and physical latency/routes/power remain separate gates.

See [the current state](../../docs/PROJECT_STATE.md), [feature matrix](../../docs/FEATURE_MATRIX.md) and [verification receipt](../../docs/verification/runtime-quality-20260910.md). Do not treat PR95's successful CI as proof of the integration.

## Retained prior plans

The full prior registry is preserved byte-for-byte in [README_20260903.md](README_20260903.md); all its relative plan links remain valid. Its prior selections and release intentions are historical, not simultaneous current instructions. PR89 release hardening and PR90/91 dependency proposals remain separate open work, not silently accepted by this runtime plan.

Historical constraint: the old registry's "existing PR #69" checkpoint is retained in the archive. It is not a new merge instruction or a second selected plan.
