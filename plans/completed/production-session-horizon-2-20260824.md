# Shared ProductionSession transaction owner — 2026-08-24

## Purpose and user-visible outcome

ChopLab keeps the same simple UI and project format, but every migrated edit now gets one cross-platform decision for history, revision, Undo/Redo availability and autosave admission. A required playback stop must succeed before the edit is committed. This removes the application-state duplication that would otherwise multiply when more commands, MIDI and assist proposals are added.

## Current state

- Exact root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-session-20260824`.
- Branch/base: `codex/choplab-production-session` from public `main@41be2c22b29521909ff0a0443e21523eff5e4e8a`, tree `79a412c44dc99d1bdade4f5be470f3b475d3f62d`.
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`; one checkout, Pixel/ADB and GitHub writer.
- Dirty canonical checkout and earlier phase worktrees are preserved and excluded from writes.
- ADR-0001's first semantic tracer is complete on main. Android and Windows still directly own `EditHistory`, revision/flags and repeated command-commit sequencing at this baseline.
- ProductionSession, restart-safe revision, exact Pixel receipt, PR #47 and merged-main readback are complete. Public main is `28bd388`, with the exact reviewed tree `3e3a90d`.

## Constraints and invariants

- Project schema, public controller signatures, UI copy/layout and audio algorithms remain unchanged.
- `ProductionSession` owns history, merge coalescing, monotonic revision, PROJECT/SESSION/NONE classification, canUndo/canRedo and persistence admission.
- `planCommand` is one-owner and one-use. Foreign, stale, cancelled and double-committed plans fail closed.
- Blocking effects execute before commit. Failure cancels the plan and leaves project/history/revision unchanged.
- Restoring a validated project advances the in-memory revision but may explicitly suppress redundant persistence.
- Platform controllers retain StateFlow publication, actual autosave scheduler, audio/document/lifecycle adapters and post-effect failure reporting.
- Calls are serialized by the controller's existing command state owner; this slice does not rewrite audio observation threads.
- JVM-atomic `ProjectOperationEpoch` remains a platform concurrency adapter until a later shared state owner can serialize asynchronous completion and runtime observation without weakening thread safety.
- No schema migration, recording, provider auth, tag/release, data deletion, force push or signing-policy change.

## Architecture and interfaces

- `ProductionSession`: mutable application journal for history/revision/transaction ownership.
- `ProductionCommandPlan`: exposes mutation/effects while hiding the reducer result and owner token.
- `ProductionSessionTransition`: decorated state, mutation, revision, persistence requirement and effects.
- `applyEdit`: migration bridge for legacy pure transforms until they become typed commands.
- `replaceProject`: one reset/revision boundary for source import, manual open, reset and recovery.
- `AtomicProjectStore.loadWithRevision`: returns the verified generation revision so a recovered session advances above disk before its next save.
- Android/Windows controllers: execute blocking effects, commit/cancel, publish returned state, execute non-blocking refresh and schedule only admitted persistence.
- Decision record: `docs/architecture/ADR-0002-production-session-transactions.md`.

## Milestones

### Milestone 1: shared transaction contract

- Add plan/commit/cancel, edit, replace, Undo/Redo and stale-plan tests.
- Run common tests on Desktop JVM and Android host.
- Acceptance: revision/history/persistence invariants and negative paths pass on both targets.

### Milestone 2: controller migration

- Replace direct `EditHistory` and Android `projectRevision` ownership.
- Route commands, legacy edits, recording results, source replacement, reset, manual open and recovery through the session.
- Preserve effect ordering and platform-specific runtime refresh.
- Acceptance: no direct controller `EditHistory`/project revision mutation remains; Android and Desktop regression suites pass.

### Milestone 3: full local gate and review

- Run shared/Android/JVM/Desktop tests, Lint, APKs, Windows package, policies and SBOM.
- Review Standards and Spec independently from `main@41be2c2`.
- Update current snapshot, feature matrix, architecture docs and this plan.
- Acceptance: zero failures/errors/skips and zero unresolved review finding.

### Milestone 4: runtime and scoped device

- Launch exact packaged Windows bytes with isolated data and preserve real project digest.
- Signer-admit and data-preserving install the exact Android candidate on the owned Pixel only after local pass.
- Cold launch/navigation and fatal/ANR negative; no recording or app-data clearing.
- Acceptance: exact bytes and scopes receive separate LOCAL/DEVICE receipts.

### Milestone 5: GitHub integration

- Push, open/read back PR, require all checks, squash merge and verify merged-main tree/checks.
- Do not create a tag or binary release for an internal architecture slice.
- Acceptance: main contains the reviewed tree and all four workflow families pass.

## Progress

- [x] 2026-08-24 04:16 JST — Fixed exact main, owner, rollback, gate and phase-1 receipt inputs.
- [x] 2026-08-24 04:20 JST — Selected two-phase plan/effect/commit over an EditHistory-only wrapper; recorded ADR-0002.
- [x] 2026-08-24 04:25 JST — Shared ProductionSession contracts pass on Desktop JVM and Android host.
- [x] 2026-08-24 04:30 JST — Android/Windows compile and focused regression suites pass after initial migration.
- [x] 2026-08-24 04:38 JST — Bound verified autosave generation revision into ProductionSession and proved the next edit saves above disk.
- [x] 2026-08-24 04:44 JST — Added the missing foreign-plan negative and clarified the operation-epoch concurrency boundary from two-axis review.
- [x] 2026-08-24 04:37 JST — Final 152-task local gate passed; shared hosts 19 each, JVM-core 50, Android 226 and Desktop 76 with zero failures/errors/skips.
- [x] 2026-08-24 04:37 JST — Final two-axis re-review unresolved Standards 0 / Spec 0; committed current local SSOT.
- [x] 2026-08-24 04:40 JST — Exact Windows isolated runtime and Pixel `9E5C…` retained install/readback/cold launch passed.
- [x] 2026-08-24 04:57 JST — PR #47 merged as `main@28bd388`; PR/push 8/8 and merged-main Android/Windows/iOS/Supply-chain 4/4 PASS.

## Discoveries

- Direct `EditHistory` ownership appeared in source load, recording completion, reset, command dispatch, legacy commit, manual open, recovery and Undo/Redo on both platforms.
- Android separately owned a monotonic `projectRevision` used for stale async-result and autosave checks; Desktop had no equivalent application revision.
- A pure reducer alone cannot safely commit a mode change that requires a fallible runtime stop; the application transaction must remain two-phase.
- Recovery is a project replacement and should invalidate stale work, but it need not immediately rewrite the same validated autosave bytes.
- The previous Android application revision restarted at zero even when disk held a higher verified revision, so post-recovery saves could be rejected until the counter caught up.

## Decision log

- 2026-08-24 — Use one mutable ProductionSession journal rather than a thin EditHistory wrapper.
- 2026-08-24 — Keep platform effect execution outside common code and make plan commit/cancel explicit.
- 2026-08-24 — Advance revision on all project replacements, including recovery; allow recovery to suppress redundant persistence separately.
- 2026-08-24 — Seed recovery from `max(session revision, verified disk revision) + 1`; the next durable edit is therefore always newer than the recovered generation.
- 2026-08-24 — Keep legacy `applyEdit` as a bounded migration bridge and avoid expanding the typed command set in the same PR.

## Validation log

- `:shared:desktopTest :shared:testAndroidHostTest` — PASS after initial contract.
- `:shared:desktopTest :shared:testAndroidHostTest :app:compileDebugKotlin :desktop:test` — PASS after initial controller integration.
- `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :desktop:test` — PASS after project-replacement integration.
- `:shared:desktopTest :shared:testAndroidHostTest :jvm-core:test :app:testDebugUnitTest :desktop:test` — PASS after recovered-revision integration; JVM-core adds the disk-to-session save regression.
- Full `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — PASS, 152 tasks; shared 19/19, Android 226, JVM-core 50, Desktop 76; zero failures/errors/skips.
- Windows packaged runtime — responding launcher/UI and exact title in isolated data; exact process stop; real project digest unchanged.
- Pixel — exact candidate SHA `9e5c5767…`, signer match, data-preserving install/readback, projects 7 / 62,592 KiB preserved, cold launch/navigation/fatal negative PASS. Receipt: parent PAD `work/PAD_CHOPLAB_PRODUCTION_SESSION_69EFBED_DEVICE_RECEIPT_20260824.json`.
- GitHub — parent PAD `work/PAD_CHOPLAB_PRODUCTION_SESSION_GITHUB_RECEIPT_20260824.md`; PR #47, tree equality and merged-main runs `32662509006`, `32662509027`, `32662509010`, `32662509005` PASS.

## Risks and rollback

- Risk: mutable session calls from unrelated threads. Mitigation: no new caller threads; controller command/state owners serialize the migrated paths, while runtime observation stays outside the session.
- Risk: recovery revision changes stale-result timing. Mitigation: explicit contract tests and existing operation-epoch/recovery suites.
- Risk: persistence suppression could hide a real edit. Mitigation: only validated recovery calls pass `persistenceRequired=false`; normal edits and replacements default true.
- Rollback: revert the phase commits. No schema or user-data migration is involved.
- Stop before credentials, recording, user-audio extraction, app-data deletion, force push, tag rewrite or binary publication.

## Remaining device validation

- Exact new APK signer/hash admission and retained-data install.
- Cold launch/top-resumed/fatal/ANR and project-shape preservation.
- Physical audio, latency, route loss, recording, TalkBack speech and HUMAN_GO remain separate regardless of scoped device success.
