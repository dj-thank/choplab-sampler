# Keep Undo and Redo unavailable while production ownership is busy

## User outcome

When ChopLab is opening, importing, saving or exporting a document, or when any recording session owns the production, Undo and Redo are unavailable everywhere. Android/Windows deck buttons and Windows native Ctrl+Z/Ctrl+Y menu items show the same truth. A direct controller request during that interval is rejected before history, project, revision, runtime ownership or autosave admission changes.

## Exact starting point

- Base commit: `1deb8a9ec2198e88fcde07a572bf0a8f9eea333e`
- Base tree: `afcde382a6c0c19dc3d249d96d4dc1d15e4a526f`
- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-history-admission-20260827`
- Branch: `codex/choplab-history-admission-20260827`
- Portfolio receipt: `C:/Users/rambo/Documents/ChatGPT/pad/work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE16_20260827.md`

## Current failure

- Shared deck Undo/Redo uses `canUndo/canRedo && !isLoading && !recordingSession.isActive`.
- Windows native Edit menu uses only `canUndo/canRedo`, so shortcuts remain advertised and dispatchable while loading/recording.
- Android and Windows controller history entry points reject active recording but do not reject `isLoading`.
- The policy is duplicated instead of owned as one domain truth.

## Scope and invariants

- Add one pure shared admission decision for history requests and one display-ready enabled predicate per direction.
- Bind the shared deck, Windows native menu, Android controller and Windows controller to that decision.
- Loading denial must take precedence without consuming Undo/Redo or replacing state.
- Recording denial must preserve the existing Japanese guidance and active recording session.
- Normal Undo/Redo, missing-history messages and Wave 15 same-owner loop transaction stay unchanged.
- Do not alter archives, schemas, audio interfaces/callbacks, recording start/stop, document I/O, autosave scheduling, project data or release identity.

## TDD and controls

1. Add common tests for idle/loading and every active recording phase, including canUndo/canRedo direction controls.
2. Add adapter tests proving denied requests preserve project/history and normal requests still move the frontier.
3. Add a Windows menu policy test so the native surface cannot regress independently.
4. Observe RED against the current duplicated/missing policy, then implement the smallest shared seam.
5. Run focused shared, Android and Desktop tests before the configured offline full gate.

## Acceptance criteria

- One shared policy is the only loading/recording admission source used by all four boundaries.
- Native menu enabled state equals the shared deck for Undo and Redo.
- A denied controller request preserves project content, `canUndo/canRedo`, revision-observable state, recording session and active loop/runtime fields.
- Normal Undo→Redo remains successful, including Wave 15 active-loop controls.
- Review finds no unresolved Standards or Spec issue.
- Configured offline gate, policy scans, package/read-back and `git diff --check` pass from a clean product checkpoint.

## Gate ceiling

`LOCAL_PASS` only. Tests use synthetic state/fakes and do not claim physical keyboard dispatch, audio continuity, device/provider/public/signing or Human acceptance.

## Progress

- [x] 2026-08-27 — Re-read Wave 15 closeout and protected checkout ownership; selected the cross-platform history-admission mismatch over the larger initial loop-start transaction.
- [ ] Observe focused RED.
- [ ] Implement shared admission and all four bindings.
- [ ] Run focused tests and adversarial review.
- [ ] Run full configured gate, record artifacts and close the plan.
