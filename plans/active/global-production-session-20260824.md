# Global ProductionSession optimization — 2026-08-24

## Outcome

Make ChopLab's long-term expansion cheaper and safer by moving the first cross-platform editing slice behind one shared command/effect semantic spine. The user-visible deck remains simple; Android and Windows stop disagreeing about Source boundaries, slice selection/history, and PAD performance mode.

## Exact object and ownership

- Root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-global-optimization-20260824`
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`
- Branch: `codex/choplab-global-optimization`
- Base: `origin/main@ab68d2d9eaf2e5b9021a131f9ecc34d5063825bf`
- Dirty canonical checkout: read-only and excluded.
- Pixel/ADB and GitHub writes are serialized to this root task.

## Target gate

The implementation target is `LOCAL_PASS`. A data-preserving Pixel slice may independently establish `DEVICE_PASS` after exact APK/revision admission. GitHub PR/merge readback is source integration evidence; the v0.17 binary Release remains separately blocked by stable Android signing secrets.

## Milestones

1. Record the system map, alternatives, metrics, ADR, rollback and stop conditions.
2. Add shared command/result/effect types and reducer contract tests.
3. Migrate Source range/slice operations and PAD performance-mode toggle from both controllers.
4. Run focused, full Android/JVM/Desktop/common/Lint/package verification and two-axis review.
5. Run bounded Windows runtime and data-preserving Android device checks if the admitted device is available.
6. Push, open PR, wait for checks, merge, and verify merged-main without weakening signing or release policy.

## Invariants

- Source range remains start-inclusive/end-exclusive and preserves minimum Chop length.
- Boundary edits use the same bounded zero-crossing policy on every platform.
- Slice selection alone does not create Undo/autosave churn.
- Normal PAD mode toggles ONE_SHOT/GATE; Beat LOOP remains an explicit separate control.
- A loop-owned PAD is stopped and ownership is cleared before its performance mode changes.
- Recording/loading blocks durable edits without blocking harmless selection.
- Platform effect failure is not silently promoted to project success.

## Verification

- `:shared:desktopTest`
- `:app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease`
- `:jvm-core:test :desktop:test :desktop:packageWindows`
- project policy, public-surface, package/SBOM checks when the focused slice is green.
- exact Git status, commit/tree, artifact hashes, device identity and negative paths in receipts.

## Rollback and stop conditions

- All edits are isolated in this worktree and branch; no schema migration or destructive data operation is part of the tracer.
- Stop if tests expose an unmodeled platform semantic that cannot be represented without widening the slice.
- Stop before credential creation, recording, provider authentication, data deletion, force push, tag rewrite, or partial binary publication.
## Progress

- [x] Baseline and architecture audit.
- [x] Direction matrix and ADR.
- [ ] Shared reducer and contract tests.
- [ ] Android/Windows migration.
- [ ] Full local verification and reviews.
- [ ] Device slice.
- [ ] GitHub integration.
