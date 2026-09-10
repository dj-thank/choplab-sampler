# Shorten autosave recovery without weakening project safety

## Purpose and user-visible outcome

Reduce waiting when reopening an existing ChopLab / おとひろい project on Android
and Windows. Keep recovery, recording and first playback trustworthy.

## Current state

Input: `main@bed7a550a71b1ae91556b2b2af25d7c482083c98`.
The original AtomicProjectStore decodes every available generation and then picks
one. MainActivity eagerly obtains a recording-only system service. AudioTrack is
also started synchronously; its lifecycle is deliberately unchanged in this patch.
An exact-source local checkpoint was established before edits from the source
snapshot inside main workflow 33657961508's artifact 9857641189. The local Git
checkpoint reconstructs the source tree; it is not the original Git history.

## Constraints and invariants

No schema, Undo format, PatternRenderer, PAD/Scratch DSP, LayerStudio, dependency,
release, signing or reference/pro-v0.2 changes. Keep bounded PCM validation,
SHA-256 verification, revision ordering and generation tie priority. Never delete
or rewrite saves during recovery. No user audio enters this repository.

## Architecture and interfaces

The shared JVM store sorts small sidecar hints, then verifies/decode candidates
under the existing store monitor, stopping on first success. A module-internal
reader overload allows tests to count real codec calls; production always uses
the existing bounded ProjectArchiveCodec. Save/read exclusion stays intact.
MainActivity lazily resolves MediaProjectionManager on the main-thread permission
path and reports fully drawn only after the loading state settles.

## Milestones

1. Inspect and checkpoint the exact baseline; run doctor and validator.
2. Add minimal recovery/initial-composition changes and deterministic tests.
3. Compare host recovery against baseline, scan public surface, publish a reviewable
   candidate. Require supported-toolchain CI and physical evidence before promotion.

## Progress

- [x] 2026-09-10 — baseline and open PRs inspected; isolated local checkpoint made.
- [x] 2026-09-10 — ranked recovery, lazy service and fully-drawn signal implemented.
- [x] 2026-09-10 — 25 real-code host test bodies pass (11 existing, 14 added).
- [x] 2026-09-10 — A/B recovery measured; public/policy checks completed.
- [ ] Supported-toolchain Gradle/JUnit, Android lint/build and hosted checks.
- [ ] Physical TTID/TTFD and first-audio/recording validation.
- [ ] Reconcile global PROJECT_STATE, FEATURE_MATRIX and registry at integration;
      the existing release-convergence lane/PR #89 is not superseded by this draft.

## Discoveries

Four decoded PCM projects were kept alive just to return one. At the tested host
fixture, recovery median changed from 148.39 ms to 76.60 ms, with only one codec
call for four healthy generations. This is not physical app startup evidence.
The current runtime has JDK21/Kotlin1.9 and no Android SDK; Gradle distribution DNS
fails. Full validator also fails at unchanged baseline EditHistory.addLast.

## Decision log

- 2026-09-10 — Preserve fallback/digest/budget semantics; do not trust a sidecar
  revision before actual archive validation.
- 2026-09-10 — Leave engine threading unchanged rather than introduce lifecycle
  races. This task-local plan scopes only this candidate; do not resume/alter the
  separate release-convergence plan or bulk-rewrite its large normative history.
- 2026-09-10 — Record candidate feature/evidence delta separately in
  `docs/STARTUP_RECOVERY_20260910.md`; merge-time SSOT reconciliation remains open.

## Validation log

See `docs/STARTUP_RECOVERY_20260910.md` for commands, exact artifact/input, host
adaptation, 18+18 measurements, limitations and reproduction instructions.
Normal regression command: `./gradlew :jvm-core:test :app:testDebugUnitTest
:app:lintDebug :app:assembleDebug`. Policy: `python3 -m unittest discover -s
scripts/tests -p 'test_*.py'`; publication: `python3 scripts/check_public_surface.py`.
No device/provider/public/Human gate is claimed by compilation or host timings.

## Risks and rollback

Malformed/stale metadata must not select a bad project; tests exercise negative
paths and resident limits. CI/device regressions should block merge. Revert this
focused runtime commit to restore the previous selection algorithm; persisted
archives need no migration. Do not clear user data, reset branches or move tags.

## Remaining device validation

Compare equal build types/fixtures; separately measure initial display and usable
project, then first PAD hit, recording permission/cancel paths, rotation/background
and all recovery generations. Retain signer and project data on device updates.
