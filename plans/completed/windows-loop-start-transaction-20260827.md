# Start a Windows Beat loop transactionally without destroying the current production on failure

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

## Architecture and interfaces

- `ProductionSession.planEdit` owns a non-consuming, owner/epoch/exact-once project target; only `commit` records history/revision and only `cancel` resolves a failed candidate.
- `SamplerUiState.beatLoopControlEnabled` owns visible start admission while keeping an already-owned loop stoppable.
- `DesktopSamplerAudioEngine.startExclusiveLoopSession` owns the recoverable preparation/start boundary. `JavaSoundWavPlayer` prepares every candidate, starts every candidate, then retires source and prior PAD voices.
- `DesktopSamplerController` serializes the transport step callback with the loop handoff. It requests transport stop and publishes state only after the complete Java Sound candidate session succeeds.
- Existing exact GATE ownership tokens remain on `triggerPad` / `releasePadIfOwned`; this slice does not add a second voice owner.

## Milestones

### Milestone 1: current-main falsifier

- Add shared plan/admission and Windows owner/companion/transport-race tests before production APIs.
- Acceptance: compile RED names the missing seams rather than failing from environment or fixture errors.

### Milestone 2: candidate-first transaction

- Implement shared edit planning, presentation admission, Java Sound candidate-set lifecycle and controller handoff.
- Acceptance: focused shared Android/Desktop and Desktop adapter suites pass; recoverable failures leave runtime/project/history/durable autosave unchanged.

### Milestone 3: closeout

- Run separate Standards and Spec reviews, configured full gate, package/policy/artifact read-back, then move this plan to `plans/completed/` and update current SSOT.
- Acceptance: clean product checkpoint, zero unresolved review findings and explicit remaining physical/device gates.

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

## Discoveries

- The earlier interrupted Wave 17 worktree contained a useful candidate but was based on pre-merge Wave 16 and remained dirty. It is retained read-only; current work was reapplied to exact merged main.
- Kotlin daemon marker creation is denied under the managed sandbox, but Gradle's in-process fallback completes compilation/tests. This is an execution-environment warning, not a product failure.
- Helper-level ordering tests did not prove the actual Java Sound port wiring. Direct proxy-Clip tests now bind source/PAD retirement after complete candidate startup and preservation on companion failure.

## Decision log

- 2026-08-27 — Select Windows synchronous startup because it has a deterministic endpoint admission/failure boundary; defer Android realtime command acknowledgement as a separate design.
- 2026-08-27 — Preserve `triggerPad` ownership tokens and add one batch method rather than weakening the exact GATE release contract.
- 2026-08-27 — Treat only preparation/start `Exception` as recoverable. Failures after candidate startup/retirement begins and fatal `Error` propagate unchanged because rollback can no longer be proven.

## Validation log

- Current-main RED: `:shared:compileTestKotlinDesktop :desktop:compileTestKotlin`; missing `beatLoopControlEnabled` / `planEdit`.
- Focused GREEN: `:shared:testAndroidHostTest :shared:desktopTest :desktop:test`; 333 tests / 58 suites, zero failure/error/skip.
- Adapter wiring follow-up: `:desktop:test`; success after adding direct proxy-Clip success/failure ordering controls.
- Full gate: 184 tasks (150 executed / 34 up-to-date), `BUILD SUCCESSFUL in 2m45s` outside the managed sandbox after the same sandboxed command twice stopped at a readable Gradle-cache JAR with `AccessDeniedException`.
- XML read-back: Android 276 / 47 suites; shared Android 86 / 17; shared Desktop 86 / 17; JVM 88 / 9; Desktop 163 / 24; total 699 / 114, zero failure/error/skip.
- Lint debug/release: fatal/error 0, warning 4 each. Python policy 64/64; public current/history 457 each; configured validator 18 tasks plus XML/mode/wrapper/UTF-8 checks.
- Android unsigned candidate verifier exit 0 (`0.17.0` / code 27 / `manifest_tool=apkanalyzer`); signed-required negative exit 1. CycloneDX 1.6 verifies 650 components / 651 dependencies.
- Artifacts: debug APK `BA96E55410DACE8B753F6C60600375429946069BDD4600404AA7C54399695BC8`; androidTest `F8AC9B2C1FC97672FCFB8565127D6099D80E906F49F623BE334C61AF102FE622`; unsigned release `D8165757CF4393DBCC808118D49585A23D5ACCB3237998BDE74620DF9D686AB5`; Desktop JAR `6659571F558CB832E9D88D21E8E9409A2EDB6370B912ADEA167026004EB25EE7`; Windows image manifest `A98268B6A9F53ACA288D89811DDAEA66170795B113EBA84D06A1EF5160EFADC6`.

## Risks and rollback

- Candidate playback may overlap prior playback for the bounded startup interval; physical click/overlap quality is unclaimed until endpoint testing.
- A non-contract retirement/scratch failure after candidates start cannot be truthfully rolled back and therefore propagates; the state is not misreported as recoverable success.
- Rollback is the single Wave 17 product commit on this isolated branch. The historical dirty worktree and canonical dirty checkout are not changed.

## Remaining device validation

- Physical Windows endpoint open/start failure, click/pop/brief overlap, latency, device removal, Bluetooth and sleep/resume.
- Native keyboard/Narrator interaction with the shared enabled state.
- Android realtime loop-start acknowledgement remains a separately selected slice.

## Progress

- [x] 2026-08-27 — Recomputed Wave 17 against merged main and selected the synchronous Windows initial-loop failure boundary over Android realtime redesign, lower-value filesystem fallback and external gates.
- [x] 2026-08-27 — Preserved the earlier dirty Wave 17 worktree as historical input and created a clean exact-main owner worktree instead of overwriting or treating the old branch as current proof.
- [x] 2026-08-27 — Current-main RED: shared test compilation failed on missing `beatLoopControlEnabled` and `planEdit`; the controller/lifecycle tests also required a complete loop-session audio-port operation.
- [x] 2026-08-27 — Implemented the shared edit-plan/presentation admission and Windows complete candidate-set handoff while retaining exact GATE ownership. Focused shared Android 86 / 17 suites, shared Desktop 86 / 17 and Desktop 161 / 24 pass: 333 tests / 58 suites, zero failure/error/skip.
- [x] 2026-08-27 — Local parent two-axis review found missing direct actual-adapter ordering proof and incomplete ExecPlan structure. Added proxy-Clip source/PAD success/failure controls, exact durable autosave-byte control and the required architecture/milestone/risk logs; final Standards/Spec unresolved findings are `0/0`.
- [x] 2026-08-27 — Full 184-task gate, 699-test XML read-back, lint, Python/public policy, configured validator, APK positive/negative, Windows package and SBOM all pass. Recorded exact artifacts and closed at `LOCAL_PASS`.
