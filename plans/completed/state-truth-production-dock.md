# State-truth playback and persistent Production Dock

## Purpose and user-visible outcome

ChopLab keeps the fixed four-stage `入れる / チョップ / ビート / 保存` console, square PADs, 4 BANK x 32 PADs, and current Layer Studio, but makes the working controls consistent. Source playback visibly distinguishes stopped, starting, playing, and stopping states. Stage tabs navigate without unexpectedly restarting audio or changing BANK. Beat Quick and detailed step editing both keep `ADD` and `SCRATCH` in the same Production Dock. `ALL STOP` reliably stops transport as well as source, PAD voices, loop, and scratch so a later sequencer step cannot sound again.

## Current state

The implementation baseline is branch `agent/gpt-pro-ui-integration` at `0eb15b23fe8cc8bd67e3e194f019a4533b637e25`. Before this plan, `scripts/validate_project.sh` and `gradlew.bat testDebugUnitTest --offline --no-daemon --max-workers=1` passed with 153 tests in 34 suites. Untracked `outputs/` and `work/` contain user and task artifacts and must remain uncommitted.

The user requested a full-file GPT Pro review. A privacy-sanitized packet included all 188 Git-tracked files plus the canonical HTML and two approved visual references. The nested source ZIP is 830,153 bytes with SHA-256 `BD3657AC89142E2736371F8C50DEEADF842AB993CE6EA7C47FEF12D9DACD80A3`; the one-attachment outer review bundle is 812,286 bytes with SHA-256 `4B33ED6939D4B6505A2BF5CD5BB8F67FA0928DAFCE52D074D9AB343998F7E989`. The completed exclusive browser conversation is `6a7c716e-0b80-83ee-aa23-a89a0cbffbae`; its transcript is 47,282 bytes with SHA-256 `D27FFE3765E23A3CE7E05A138F387BD8874B5AD260C9441462EBFBB78CE7C52E`. The response confirmed all 193 packet entries and the 188 tracked sources before recommending this slice.

The current UI derives `ChopSessionPresentation` from only `sourcePlaying`, while pending start is private `SamplerViewModel.sourceStartPending`. Therefore a queued start still looks like another available `START CHOP`. `WorkflowStrip` also restarts source playback and selects BANK A whenever CHOP is selected. Beat Quick exposes Add and Scratch, but both disappear in portrait and landscape detailed modes. `SamplerViewModel.stopAllSounds()` calls only `engine.stopAllVoices()` and leaves transport state active. In replacement/reset/load paths, `StopTransport` is queued before the newer out-of-band Stop All boundary, so the mailbox can discard that transport stop.

## Constraints and invariants

- Keep Android minSdk 29, current AudioTrack engine, project archive schema, and autosave compatibility.
- Preserve audio-thread-applied `sourcePlaying` truth; a queued play request must never enable live Chop.
- An empty PAD captures only in audio-thread-confirmed PLAYING state; an assigned PAD always auditions and is never overwritten.
- Preserve square 4 x 4 PAD geometry, two PAD pages per BANK, no top-level scroll APIs, and the canonical cream/charcoal/orange/green visual language.
- Keep Layer Studio internals, precision trim and revert, Beat Loop, 16-step placement, drum, vocal, scratch, and live Key/Tone/Level behavior.
- Global playback stop must not discard microphone, system-audio, or vocal recording data. Recording remains controlled by its explicit recording action.
- Do not copy the partial `reference/pro-v0.2/` tree, add unverified Pro claims, imitate AKAI/MPC trade dress, or convert the workflow to five stages in this slice.
- Preserve unrelated dirty bytes and do not mutate the shared emulator or claim physical-device evidence without exclusive ownership.

## Architecture and interfaces

`SamplerUiState` gains a runtime-only `PendingSourceCommand`. `PadPressRouting.kt` owns the pure `SourceUiPhase` reducer so Compose can render `STOPPED / STARTING / PLAYING / STOPPING` without guessing. Project codecs do not persist this runtime field, and restored/new state defaults to `NONE`.

`SamplerPlaybackEngine.stopAllPlayback()` establishes the required producer ordering: publish the out-of-band Stop All boundary first, then enqueue `StopTransport` after that boundary. `SamplerViewModel` routes ALL STOP, source replacement, reset, and project application through this method. A pure state reducer clears transport, recording arm, step, loop, and scratch UI while preserving audio-thread-applied source truth until polling confirms stop.

`GuidedWorkflow.kt` owns source-phase presentation, stage navigation effects, and Production Dock action policy. `OtohiroiDeck.kt` keeps orientation-specific layouts but renders Capture, Chop, and Beat actions through one Production Dock primitive. Beat Quick and Steps use the same four actions in the same order: Quick, Steps, Add, Scratch. Detailed content changes behind that dock rather than replacing its destinations.

## Milestones

### Milestone 1: Lock state truth and stop ordering with RED tests

- Scope: source phase, Chop labels, PAD accessibility during pending start, stage-tab effects, persistent Beat dock, global stop state, and engine call ordering.
- Files/interfaces expected to change: `SamplerModels.kt`, `PadPressRouting.kt`, `GuidedWorkflow.kt`, `DeckLayoutPolicy.kt`, their focused tests, and a new audio stop-order test.
- Implementation steps: add tests that reference the intended APIs before production definitions; run focused Gradle tests and retain the compilation/test failure as RED; implement the smallest pure contracts; rerun GREEN.
- Tests/checks: `GuidedWorkflowTest`, `PadPressRoutingTest`, `PadGridAccessibilityTest`, `DeckLayoutPolicyTest`, `ProductionDockPolicyTest`, and `StopAllPlaybackTest`.
- Acceptance evidence: STARTING cannot capture; STOPPING disables the primary action; navigation has no restart/BANK effect; Quick and Steps both contain Add/Scratch; Stop All invokes boundary before transport stop and clears sequencer UI without falsifying source truth.

### Milestone 2: Wire ViewModel and UI integration

- Scope: replace private pending-start drift with runtime state, use the safe global stop path, remove stage-tab playback side effects, and consolidate Production Dock rendering.
- Files/interfaces expected to change: `SamplerPlaybackEngine.kt`, `SamplerViewModel.kt`, `OtohiroiDeck.kt`, `PadGrid.kt`, and focused tests.
- Implementation steps: route all pending source transitions through `PendingSourceCommand`; update poll reconciliation; add explicit default Melody Chop preparation only to Capture's Start Chop; rename `NEW PROJECT` to `REPLACE SOURCE`; show phase labels in source controls/readout; share dock button rendering; replace Fine-only back header with the persistent Quick/Steps/Add/Scratch dock.
- Tests/checks: focused tests plus `:app:compileDebugKotlin` or `:app:testDebugUnitTest` after each coherent edit.
- Acceptance evidence: no CHOP-tab call to `restartSourcePlayback()` or `selectBank(0)`; Add/Scratch present in all Beat branches; no source capture during STARTING; ALL STOP updates UI and engine consistently.

### Milestone 3: Full local candidate, review, and APK

- Scope: full offline validation, Lint, APK, source review, documentation, and exact artifact receipt.
- Files/interfaces expected to change: `docs/PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md` if feature truth changes, `design-qa.md`, and this plan. Final APK is placed under untracked `outputs/`.
- Tests/checks: configured `scripts/validate_project.sh`; `gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1`; `git diff --check`; no-scroll API scan; fixed-point code review against `0eb15b2`.
- Acceptance evidence: zero test/lint/build failures, exact suite/test count, APK size/hash/package/version/signature metadata, no unresolved P0/P1 review finding, and device-only gaps listed separately.

## Progress

- [x] 2026-08-12 - Confirmed branch, exact baseline commit, clean tracked tree, and preserved untracked outputs/work.
- [x] 2026-08-12 - Built and privacy-scanned the complete one-ZIP review packet containing all 188 tracked files.
- [x] 2026-08-12 - Recovered a grounded GPT Pro response from the exclusive ChopLab conversation after the initial capture timeout.
- [x] 2026-08-12 - Reconciled the response with current code and selected one bounded state-truth/Production Dock slice.
- [x] 2026-08-12 - Observed focused RED failures and implemented state/stop contracts.
- [x] 2026-08-12 - Wired ViewModel, accessibility, and the fixed no-scroll Production Dock UI.
- [x] 2026-08-12 - Fixed review findings for replacement/reset truth and runtime-only Undo state.
- [x] 2026-08-12 - Completed local gates, two-pass parent review, exact APK receipt, and scoped Pixel 9a validation.

## Discoveries

- The first shared-profile harvest attached to an unrelated concurrent conversation. It was rejected and quarantined; no recommendation from it is used. The accepted response came from exclusive conversation `6a7c716e-0b80-83ee-aa23-a89a0cbffbae` and explicitly confirmed the ChopLab archive and exact filenames.
- `RealtimeCommandMailbox` intentionally drops queued commands older than the newest Stop All boundary. The existing `stopTransport(); stopAllVoices()` order can therefore discard the transport stop. Reversing the producer order is required, not merely changing UI state.
- The existing audio-thread confirmation already prevents pending-start live Chop. The missing part is visible state and accessible copy, not weakening that gate.
- A five-stage rail would visually resemble the historical HTML, but current code, tests, and product docs consistently define four working stages. This slice keeps four stages and reuses only the visual language.
- The first Spec review found that replacement, reset, and project application still assigned `sourcePlaying = false` before the audio thread confirmed stop. One pure replacement-state helper now preserves applied truth in all three paths.
- The final parent pass found that the new runtime-only pending command could enter Undo snapshots. The focused test failed before `historySnapshot()` explicitly reset it to `NONE`, then passed.
- A final message-truth pass found that ALL STOP could say “stopped” while the source phase was still STOPPING and that an unrelated replacement/reset message could be overwritten when the old source retired. Focused assertions failed first; the reducer now reports stopping until the applied transition and preserves unrelated project messages.
- The configured smoke script initially failed because the Kotlin/JVM process could not allocate about 1 MB while another scoped compiler daemon remained resident. After confirming its exact PID, parent, creation time, executable, and ChopLab Kotlin 2.3.21 purpose, only that stale daemon was stopped; the crash files were moved under untracked `work/validation-jvm-crash-20260812/`, and validation passed with a 512 MB smoke JVM. No unrelated Java process was stopped.

## Decision log

- 2026-08-12 - Adopt GPT Pro's P0 stop ordering and P1 state-truth recommendations because exact engine/mailbox paths confirm them locally.
- 2026-08-12 - Adopt one shared Production Dock primitive and a persistent Beat dock; keep orientation adapters to protect no-scroll geometry.
- 2026-08-12 - Keep Layer Studio as the existing modal instead of promoting a fifth top-level stage. This preserves the tested workflow and avoids a broad navigation rewrite.
- 2026-08-12 - Keep all 128 PADs and four BANK roles; the 16-pad visuals describe one visible page, not the whole project capacity.
- 2026-08-12 - Keep recording independent from ALL STOP to avoid data loss; the UI message will explicitly say recording continues.
- 2026-08-12 - Keep audio-thread-applied source truth across source replacement/reset/load until polling observes stop; archive and Undo snapshots intentionally omit pending runtime commands.

## Validation log

- `scripts/validate_project.sh` with configured Git Bash/JDK/SDK/Kotlin - 2026-08-12 baseline - PASS.
- `gradlew.bat testDebugUnitTest --offline --no-daemon --max-workers=1` - 2026-08-12 baseline - PASS; 153 tests in 34 suites, zero failures/errors/skips.
- GPT Pro full-file consultation - 2026-08-12 - completed; 188 tracked files and 193 packet entries confirmed; transcript SHA-256 `D27FFE3765E23A3CE7E05A138F387BD8874B5AD260C9441462EBFBB78CE7C52E`.
- Focused review regressions - 2026-08-12 - intentional RED observed for missing replacement STOPPING helper and Undo restoring `PendingSourceCommand.START`; both GREEN after implementation.
- `scripts/validate_project.sh` with configured Git Bash/JDK/SDK/Kotlin and constrained smoke JVM - 2026-08-12 final - PASS; wrapper SHA-256 matched.
- `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1536m -Pkotlin.compiler.execution.strategy=in-process` - 2026-08-12 final - PASS; 163 tests in 36 suites, zero failures/errors/skips; Lint zero errors and 10 advisories; APK assembled.
- `git diff --check 0eb15b2` and UI scroll API scan - 2026-08-12 final - PASS; zero whitespace errors and zero scroll API matches.
- Pixel 9a `5A121JEBF08094` - 2026-08-12 - exact APK `adb install -r` PASS; 0.10.0 to 0.12.0; cold launch/process/focused fatal-ANR check PASS; Capture/Chop/Beat dumps zero scrollable nodes; four retained project hashes unchanged through install and launch.
- Final two-pass review - 2026-08-12 - local parent Standards and Spec notes under `work/state-truth-production-dock-{standards,spec}-review.md`; zero unresolved hard/P0/P1 findings. Returned child completion metadata omitted the effective model, so no runtime-verified Luna claim is made.

## Risks and rollback

The main functional risk is a pending-source command that never clears under queue rejection. Existing engine overflow status remains fail-visible; focused reducer tests and polling checks must prevent UI from enabling capture prematurely. The main layout risk is the four-action dock at 360 x 640 and 800 x 320. Reuse the existing control-row height, remove the Fine header it replaces, and run fixed-height policy tests plus no-scroll scan. The main audio risk is stop ordering; preserve the mailbox boundary design and test that the post-boundary transport command remains processable. Rollback is the final focused implementation commit; no project archive migration is introduced.

## Remaining device validation

- Physical Pixel touch/audio confirmation for STARTING cancellation, first Chop, assigned-PAD audition, and no duplicate audio.
- ALL STOP while a 16-step pattern is active, followed by at least two bars without re-trigger.
- Source replacement/reset/project open while transport is active, followed by no sound until explicit Play.
- Portrait 360 x 640 and 412 x 820, landscape 800 x 320, font scale 1.0/1.3, and TalkBack traversal.
- Subjective scratch, loop, drum, vocal, and live Key/Tone/Level behavior after dock integration.

## Outcome and retrospective

The bounded slice is complete. Source state now has one visible truth model, destructive project transitions do not pretend an applied voice is already silent, stage navigation is side-effect-limited, every Beat mode retains Add and Scratch, and ALL STOP includes the transport boundary. The exact local APK was installed on the physical Pixel without changing any retained project archive. The remaining work is experiential audio/accessibility coverage and public release governance, not an unfinished code path in this plan.
