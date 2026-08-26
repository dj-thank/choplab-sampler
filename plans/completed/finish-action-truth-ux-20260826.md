# Make Finish actions say exactly what they preserve or remove

## Purpose and user-visible outcome

The SAVE stage distinguishes four intentions without changing audio or persistence behavior: automatic local recovery, portable `.choplab` project save/open, listening confirmation, and four-bar WAV export. The destructive control says `ビート配置を消す / CLEAR STEPS` because it calls `clearAllPattern`; it must never imply that Source, PADs or the whole project will be deleted.

## Current state and evidence

- Baseline: integrated workflow-NEXT closeout `bbd6850d1ed79dffadc402048ac3ae59cefe9f93`, descended from `8b9c00b`.
- Worktree/branch: `work/choplab-goal-ux-20260826`, `codex/choplab-goal-ux-20260826`.
- Fresh screenshots: parent PAD `work/CHOPLAB_GOAL_UX_AUDIT_20260826/{01b-visible-loaded-source,03-quick-sketch-beat,04-save-ready,06-after-save-ready,07-save-before-after}.png`.
- Baseline project validation and Desktop packaging pass. The dedicated runtime used isolated app data and a self-created WAV only.
- The separate locked-stage/next-action owner completed clean commits `a9f2245` / `bbd6850`; this branch fast-forwarded those commits before editing overlapping shared UI files.

## Invariants

- `clearAllPattern` remains the exact callback. Source audio, PAD assignments, project archive, autosave generations and user files are not deleted.
- Keep the existing two-press confirmation and Undo availability.
- WAV readiness and enablement remain based on audible pattern content.
- Project save/open remain available under their current loading/recording boundary.
- No schema, audio engine, callback, persistence, recording, device, provider, public or Human behavior changes.

## TDD seam and acceptance

The public shared presentation policy is the seam. Tests must prove:

1. the ready headline names both project preservation and audio export;
2. guidance distinguishes automatic recovery, portable project save and WAV output;
3. the clear label and confirmation name only beat placements;
4. the shared Finish UI consumes those policy values instead of hard-coded broader copy;
5. focused shared tests, Android host test compilation, full configured local validation and screenshot recapture pass.

## Files and ownership

- `shared/.../ui/GuidedWorkflow.kt`: Finish presentation policy.
- `shared/.../ui/OtohiroiDeck.kt`: render policy values only.
- `shared/src/commonTest/.../FinishWorkspacePresentationTest.kt`: cross-platform contract.
- `docs/PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md`, `docs/VALIDATION.md`, plan registry: revision-bound closeout.

## Rollback and stop conditions

- Rollback is the isolated milestone commit with ordinary `git revert`; no data migration exists.
- Stop if the change needs autosave truth not present in state, alters any destructive callback, overlaps unresolved concurrent ownership, or crosses into device/provider/public scope.

## Progress

- [x] Fresh Windows flow captured and visually inspected at the baseline revision.
- [x] Concurrent NEXT/locked-stage owner completed; clean commits integrated by fast-forward.
- [x] Finish presentation contract observed RED, then passed on shared Desktop and Android host tests.
- [x] Same-state 1200×900 before/after comparison accepted; long labels are uncropped.
- [x] Full 197-task local gate, local-parent Standards/Spec review, revision-bound docs and closeout prepared.
