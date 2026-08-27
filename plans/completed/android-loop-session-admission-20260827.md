# Admit one complete Android Beat-loop session before publishing success truth

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
- [x] 2026-08-27 — Removed two obsolete unit-returning `StartPadLoop` helpers/tests whose layer-preserving contract contradicted the new single exclusive command. Release bytecode shows the new command branch has 0 `new`, Function, lock or I/O references.
- [x] 2026-08-27 — Local parent Standards/Spec review found no unresolved issue. Full 184-task gate, 703 tests / 116 suites, lint, policy, package, APK and SBOM read-back pass.
- [x] 2026-08-27 — Recorded exact artifacts, moved the plan to completed and closed repo/PAD SSOT at `LOCAL_PASS`.

## Discoveries

- The old unit-returning command had two separate truths: owner admission was ignored and companion triggers could be partially admitted.
- Former `StartPadLoop` helpers kept unrelated PAD layers only because a separate stop-all command ran first. Once the stop and session start are one command, those helpers/tests became dead and contradictory and were removed.
- The managed sandbox denies Javac/apkanalyzer access to readable workspace artifacts; the same offline full gate and verifier pass outside that sandbox without cache mutation.

## Decision log

- 2026-08-27 — Use one mailbox command rather than returning only the owner admission while companions remain separately fallible.
- 2026-08-27 — Define success as command admission, not synchronous physical output acknowledgement.

## Validation log

- Current RED: `:app:compileDebugUnitTestKotlin`; missing batch port/transaction/snapshot/apply APIs after JUnit4 fixture alignment.
- Focused GREEN: `:app:testDebugUnitTest`; Android 280 tests / 49 suites, zero failure/error/skip.
- Full gate: 184 tasks (145 executed / 39 up-to-date), `BUILD SUCCESSFUL in 3m27s`.
- Final XML: Android 280 / 49 suites; shared Android 86 / 17; shared Desktop 86 / 17; JVM 88 / 9; Desktop 163 / 24; total 703 / 116, zero failure/error/skip.
- Lint debug/release fatal/error 0, warning 4 each. Python policy 64/64; public current/history 461 each; configured validator 18 tasks plus XML/mode/wrapper/UTF-8.
- Android unsigned positive exit 0 (`0.17.0` / code 27 / `manifest_tool=apkanalyzer`); signed-required negative exit 1. CycloneDX 1.6 verifies 650 components / 651 dependencies.
- Release-bytecode loop branch: 85 instructions/labels, `new` 0, Function refs 0, blocking/I/O/lock refs 0.
- Artifacts: debug APK `8EB1CEBB189131E12922B2B88FE3FFF043E3028441C43DD2BE610A63C289667A`; androidTest `F8AC9B2C1FC97672FCFB8565127D6099D80E906F49F623BE334C61AF102FE622`; unsigned release `FD23A077E7499897F8C4D431226E7325741FFF3D1CA858071CE7B2DE6E8F0F8F`; Windows image manifest `A98268B6A9F53ACA288D89811DDAEA66170795B113EBA84D06A1EF5160EFADC6`; SBOM JSON `86B830331BBB3AF1B53301CD365DFD182335BADCE04FB0A5EF82BB9019DB3AA2` / XML `C73D25FB4A8C81B3DCC203488AFD3219128F8A62D04D2EB9E66F013801584AC8`.

## Risks and rollback

- Command batching can change CHOKE/ownership order; explicit owner/companion controls must bind the previous audible policy.
- Rollback is limited to this isolated branch. Wave 17, concurrent worktrees and canonical dirty checkout remain untouched.

## Remaining device validation

- Physical Android output start, route change, click/latency and actual audio-focus interaction.
- API 36 instrumentation and Pixel listening remain separate device gates.
