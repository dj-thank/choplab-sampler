# Start a Windows Beat loop without destroying the current production on failure

## User outcome

On Windows, pressing Beat Loop while another primary playback mode is active either starts the complete loop owner/eligible-vocal session and then switches the production once, or leaves the current sound, project, Undo/Redo frontier and recording ownership intact with an actionable message. Loading or recording rejection performs no audio work. A partial loop session is never published as success.

## Exact starting point

- Base commit: `9441b32da468393f79e10e65b50cd596ee19742a`
- Base tree: `08849f6b5f4568745523454e5b8854ceac89a995`
- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-wave17-main-20260827`
- Branch: `codex/choplab-wave17-loop-start-main-20260827`
- Portfolio receipt: `C:/Users/rambo/Documents/ChatGPT/pad/work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE17_20260827.md`
- Historical input kept read-only: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-loop-start-transaction-20260827` at committed `d2a5e6c` plus uncommitted candidate bytes.

## Current failure

- Windows `toggleBeatLoopControl` calls `stopCompetingPlayback` before `commitEdit` performs loading/recording admission.
- It commits LOOP mode/history before `player.triggerPad` can open and start the Java Sound candidate.
- The owner and vocal companions start as separate calls; a later companion failure can leave physical audio active while loop runtime truth is unpublished.
- The existing Java Sound replacement helper protects old same-PAD/choke voices, but the controller has already stopped the broader source/transport/scratch/PAD ownership.

## Scope and invariants

- Add an owner/epoch/exact-once non-consuming edit plan to `ProductionSession`; `applyEdit` keeps its existing observable behavior through that lifecycle.
- Expose one shared Beat-loop control enabled predicate: an active loop remains stoppable, while a new start requires an assigned selected PAD and no loading/recording owner.
- Add a Windows audio-port operation that prepares and starts the loop PAD and eligible vocal companions as one candidate set before retiring prior Java Sound playback, while retaining current exact GATE ownership APIs.
- Controller order is admission → pure target → plan → complete candidate start → retire non-player schedulers → commit/publish/autosave.
- Recoverable preparation/start failure is typed by the audio port, cancels the plan and changes only status. Any non-contract exception or fatal `Error` cancels then propagates without ordinary failure copy.
- Success records one project edit when PAD modes change, publishes one loop owner/playhead, starts each eligible companion once and preserves the existing exclusive primary-mode policy.
- Do not change Android engine/callbacks, source PCM, pad DSP, pattern/song render, project archives/schema, recording lifecycle, provider behavior or release identity.

## TDD and controls

1. Add shared tests for edit-plan preview/cancel/commit, exact-once/stale/cross-session rejection and shared loop-control enablement.
2. Add Desktop controller tests proving loading/recording denial and recoverable/fatal startup failure preserve transport/project/history/runtime and do no disruptive stop.
3. Add a success/Undo control proving one candidate session, one committed edit and the existing exclusive switch.
4. Add Java Sound lifecycle tests proving all candidates start before any prior owner retires and any candidate failure abandons the complete candidate set while retiring none.
5. Add a transport race control so a late step cannot publish a voice during the handoff.
6. Observe RED against exact current main, implement the smallest lifecycle seams, run focused shared/Desktop tests, then the configured offline full gate.

## Acceptance criteria

- A denied new-loop request executes no stop/start and preserves recording/loading ownership.
- Any recoverable owner or companion candidate failure preserves prior runtime, PAD modes, history frontier and revision; only status changes.
- Any non-contract exception or fatal startup failure propagates and preserves the same production state.
- Successful start retires prior playback only after the complete candidate set starts, publishes one owner and adds exactly one Undo step when needed.
- Active-loop stop and exact GATE release ownership remain available and unchanged.
- Final Standards/Spec review has no unresolved finding.
- Configured offline gate, policy scans, package/read-back and `git diff --check` pass from a clean product checkpoint.

## Gate ceiling

`LOCAL_PASS` only. Host fakes and lifecycle ordering do not claim physical Java Sound output, click-free transition, endpoint removal, Bluetooth/sleep/resume, device/provider/public/signing or Human acceptance.

## Progress

- [x] 2026-08-27 — Recomputed Wave 17 against merged main and selected the synchronous Windows initial-loop failure boundary over Android realtime redesign, lower-value filesystem fallback and external gates.
- [x] 2026-08-27 — Preserved the earlier dirty Wave 17 worktree as historical input and created a clean exact-main owner worktree instead of overwriting or treating the old branch as current proof.
- [x] 2026-08-27 — Current-main RED: shared test compilation failed on missing `beatLoopControlEnabled` and `planEdit`; the controller/lifecycle tests also required a complete loop-session audio-port operation.
- [x] 2026-08-27 — Implemented the shared edit-plan/presentation admission and Windows complete candidate-set handoff while retaining exact GATE ownership. Focused shared Android 86 / 17 suites, shared Desktop 86 / 17 and Desktop 161 / 24 pass: 333 tests / 58 suites, zero failure/error/skip.
- [ ] Run two-axis review, full configured gate, record artifacts and close the plan.
