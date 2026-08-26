# Admit one complete Android Beat-loop session before publishing success

## Purpose and user-visible outcome

When Android starts or resumes a Beat loop, ChopLab either admits the loop owner and all eligible VOICE companions as one realtime session or leaves the production/history/runtime unchanged with truthful guidance. A stopped engine or saturated mailbox cannot show, save or record against a loop that was never admitted.

## Current state

- Base: `8065c898da4461717b4266c9803b555449caf9d7` / tree `1c794400ac7e9fca391f505da5eb907788d293b0`.
- `SamplerPlaybackEngine.startPadLoop(Int)` returns `Unit`; `SamplerEngine` discards `enqueuePrepared`'s Boolean.
- `SamplerViewModel.toggleBeatLoop` calls the disruptive stop and commits PAD modes/history before loop admission, then issues one owner command plus separate companion triggers.
- Vocal recording restart and scratch return also publish loop success without knowing whether the command entered the mailbox.

## Constraints and invariants

- One command contains precomputed owner/companion snapshots and performs no collection creation, lock, I/O, logging or UI work on the audio thread.
- Initial rejection preserves project, PAD modes, history/revision, source/transport/loop/scratch state and autosave admission; newly acquired focus is released, existing focus is retained.
- Vocal restart rejection stops the new take safely instead of recording against silent/unknown loop truth.
- Scratch return rejection returns false and does not publish loop state; the existing caller releases playback focus.
- Success preserves shared `vocalCompanionPadIndicesForLoopStart`, CHOKE ordering, exact GATE ownership and existing stop/fade policy.
- No synchronous wait for actual AudioTrack rendering; the claim is mailbox admission, not speaker output.

## Architecture and interfaces

- Replace the unit-returning loop port with `startPadLoopSession(loopPad, companions): Boolean`.
- The engine converts models to immutable snapshots on the control thread and enqueues one `StartPadLoopSession` command; the command stops prior realtime ownership and starts owner/companions in array order.
- An app-local transaction combines `ProductionSession.planEdit` with the Boolean port result and returns either an unconsumed rejection or a committed transition plus changed PADs.
- `SamplerViewModel` remains the focus, StateFlow, pad-update, pattern-sync and autosave owner.

## Milestones

### Milestone 1: RED

- Add transaction rejection/success tests and engine command-shape/admission controls before changing production APIs.
- Acceptance: current compilation or focused assertions fail specifically on the unit-returning/multi-command contract.

### Milestone 2: single admitted session

- Implement the batch port/command and initial ViewModel transaction; migrate vocal and scratch restart callers.
- Acceptance: focused app/shared tests pass, queue rejection consumes no history and success creates one edit/session.

### Milestone 3: closeout

- Run separate Standards/Spec reviews, full gate, policy/package/artifact read-back, then move this plan to completed and update repo/PAD SSOT.

## Progress

- [x] 2026-08-27 — Recomputed Wave 18 from Wave 17 and selected Android loop-session admission over external or broad feature lanes.
- [x] 2026-08-27 — Current RED: after aligning new tests with the app module's JUnit4 fixture, compilation failed only on missing `startPadLoopSession`, transaction result, session snapshot and realtime apply seams.
- [x] 2026-08-27 — Implemented one admitted owner/companion command, initial project transaction and shared vocal/scratch runtime starter. Focused `:app:testDebugUnitTest` passes.
- [ ] Review, run full gate and close.

## Discoveries

- The old unit-returning command had two separate truths: owner admission was ignored and companion triggers could be partially admitted.
- Former `StartPadLoop` helpers kept unrelated PAD layers only because a separate stop-all command ran first. Once the stop and session start are one command, those helpers/tests became dead and contradictory and were removed.

## Decision log

- 2026-08-27 — Use one mailbox command rather than returning only the owner admission while companions remain separately fallible.
- 2026-08-27 — Define success as command admission, not synchronous physical output acknowledgement.

## Validation log

- Pending.

## Risks and rollback

- Command batching can change CHOKE/ownership order; explicit owner/companion controls must bind the previous audible policy.
- Rollback is limited to this isolated branch. Wave 17, concurrent worktrees and canonical dirty checkout remain untouched.

## Remaining device validation

- Physical Android output start, route change, click/latency and actual audio-focus interaction.
- API 36 instrumentation and Pixel listening remain separate device gates.
