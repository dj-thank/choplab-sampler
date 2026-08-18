# One-action Chop start and consolidated performance controls

## Purpose and user-visible outcome

The Chop screen starts sampling with one obvious `チョップ開始 / START CHOP` action. While the source is playing, an empty PAD captures the current position and an assigned PAD auditions its existing Chop. The separate CHOP/PLAY mode switch is removed. Portrait and landscape keep their fixed no-scroll layouts but share one implementation for repeated coach, source-control, and next-action rows. Unreachable source-edit frontend is deleted without removing active waveform, trim, drum, vocal, beat, or scratch features.

## Current state

The current branch is `agent/precision-trim-exclusive-playback` at baseline commit `ad4b8b9`. `app/src/main/java/com/choplab/sampler/ui/OtohiroiDeck.kt` holds a local `liveChopArmed` state and repeats nearly identical source and action controls in `PerformanceWorkspace` and `LandscapePerformanceWorkspace`. Capture mode currently requires both that local mode and `SamplerUiState.sourcePlaying`. `SamplerViewModel.capturePad` already protects assigned PADs from overwrite through `resolvePadPressAction`; empty PADs capture only while the source is audio-thread-confirmed playing. `SourceWorkspace`, `SourceControlDeck`, `SourceEditRows`, and `SourceToolRow` have no call site from the active stage tree.

Configured baseline validation passed on 2026-08-12 with Git Bash, JDK 17.0.20, the portable Android SDK, and Kotlin 2.3.21. The generic doctor passed required Git/JDK/SDK/ADB checks and warned only about unused NDK/CMake, Codex sign-in, and the intentionally dirty untracked `outputs/` and `work/` directories.

## Constraints and invariants

- Keep the fixed no-scroll console in portrait and landscape.
- Preserve square PAD geometry and all BANK/page switching.
- An assigned PAD must audition and must never be overwritten by capture mode.
- An empty PAD captures only after source playback is applied by the audio thread.
- Starting or stopping Chop playback must respect `sourceStartPending` and existing source generation handling.
- Do not change project persistence, existing user projects, audio-thread queue semantics, drum/vocal layering, beat loop behavior, or scratch routing.
- Preserve untracked `outputs/` and `work/` bytes.

## Architecture and interfaces

`GuidedWorkflow.kt` owns a small pure `ChopSessionPresentation` interface derived from source playback truth and assigned-PAD count. It provides the single primary label, coach text, and PAD capture-mode flag. `SamplerViewModel.toggleChopPlayback()` hides pending-start handling and delegates to the existing proven restart/stop operations. `OtohiroiDeck.kt` uses shared private modules for the coach/project row, source-control row, and next-action row; portrait and landscape remain layout adapters only.

The active `SourceEditorWaveform` module remains. Only unreachable source frontend definitions are deleted after call-site and compile verification.

## Milestones

### Milestone 1: Lock the one-action contract with tests

- Scope: pure Chop-session presentation and pending-safe start/stop action.
- Files/interfaces expected to change: `GuidedWorkflow.kt`, `GuidedWorkflowTest.kt`, `SamplerViewModel.kt`, and focused model tests if needed.
- Implementation steps: add failing presentation tests; add the pure interface; add a ViewModel method that uses existing `sourcePlaybackToggleAction`.
- Tests/checks: focused `GuidedWorkflowTest` and `PadPressRoutingTest`.
- Acceptance evidence: RED before implementation, GREEN after implementation.

### Milestone 2: Consolidate the active frontend

- Scope: remove `liveChopArmed`, route capture directly from source playback, share repeated rows, reduce main Chop rows, and keep four secondary destinations in one compact row.
- Files/interfaces expected to change: `OtohiroiDeck.kt` and UI contract tests.
- Implementation steps: replace the mode button with the primary session action; call `capturePad` whenever the presentation says capture mode; extract repeated rows; standardize portrait/landscape labels and enabled rules.
- Tests/checks: UI pure tests, Kotlin compile, layout/no-scroll scan.
- Acceptance evidence: one primary start control, no CHOP/PLAY mode switch, no duplicated control implementation.

### Milestone 3: Remove dead frontend and verify the candidate

- Scope: delete unreachable source frontend definitions, run full local gates, review, build the APK, and update evidence.
- Files/interfaces expected to change: `OtohiroiDeck.kt`, `docs/PROJECT_STATE.md`, this plan, and a local artifact under `outputs/`.
- Tests/checks: `scripts/validate_project.sh`; `testDebugUnitTest lintDebug assembleDebug`; `git diff --check`; no-scroll scan; fixed-point two-axis review.
- Acceptance evidence: all local gates pass, APK hash recorded, no P0/P1 review issue remains.

## Progress

- [x] 2026-08-12 - Confirmed exact branch/worktree and preserved untracked outputs/work.
- [x] 2026-08-12 - Read project state, product gaps, definition of done, domain context, Android skill, and prior active plan.
- [x] 2026-08-12 - Mapped active Chop flow, pad routing, portrait/landscape duplication, and unreachable source frontend.
- [x] 2026-08-12 - Ran configured baseline offline validation successfully.
- [x] 2026-08-12 - Added the RED/GREEN one-action Chop presentation contract and pending-safe ViewModel action.
- [x] 2026-08-12 - Consolidated portrait/landscape modules, removed the mode switch, and deleted dead frontend.
- [x] 2026-08-12 - Completed full local gates, artifact build, documentation, and fixed-point Standards/Spec review.

## Discoveries

- `capturePad` already routes assigned PADs to playback and refuses overwrite; the extra local PLAY mode is not required for safety.
- `sourcePlaying` is deliberately audio-thread-applied truth, so capture mode must remain false during a pending start.
- `SourceWorkspace`, `SourceControlDeck`, `SourceEditRows`, and `SourceToolRow` are unreachable from `OtohiroiDeck`'s stage tree.
- The shell's default `bash.exe` is WSL without `/bin/bash`; validation must use `C:\Program Files\Git\bin\bash.exe` and the portable Kotlin compiler on PATH.

## Decision log

- 2026-08-12 - Remove the separate live Chop mode instead of renaming it. The same intent is already represented safely by source playback plus assigned/empty PAD state.
- 2026-08-12 - Keep PAD edit reachable as one compact secondary action and through assigned-PAD long press; do not remove precision trim, PARAM, or PLAY pages.
- 2026-08-12 - Share behavior modules but retain separate portrait and landscape layout adapters to protect fixed geometry.
- 2026-08-12 - Do not refactor source playback generations in this change; use the existing tested ViewModel operations.

## Validation log

- `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\doctor.ps1` with configured toolchain - 2026-08-12 - required Git, Java, SDK, platform/build-tools, ADB, and repository checks passed; non-blocking NDK/CMake/sign-in/dirty-worktree warnings remain.
- `C:\Program Files\Git\bin\bash.exe ./scripts/validate_project.sh` with configured JDK/SDK/Kotlin PATH - 2026-08-12 - PASS.
- Focused `GuidedWorkflowTest`, `PadPressRoutingTest`, `PadGridAccessibilityTest`, and `DeckLayoutPolicyTest` - 2026-08-12 - PASS after an intentional unresolved-reference RED for `chopSessionPresentation`.
- `gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1` - 2026-08-12 - PASS; 153 tests, zero failures/errors/skips; lint zero errors and 7 advisories; APK assembled.
- `git diff --check` and UI scroll API scan - 2026-08-12 - PASS; zero scroll API matches.
- `outputs/ChopLab-v0.11.3-simple-chop-local-debug.apk` - 2026-08-12 - 31,580,082 bytes; SHA-256 `95C9EB3E3F2171E9DC0DA66D5D9C78CC3BF039A2D53C6E864232E8247C84DAC2`.
- Two-axis review of `git diff ad4b8b9...5f41fc9` - 2026-08-12 - local parent route because returned child runtime metadata was unavailable; Standards: no finding; Spec: no implementation finding; no substitute child model used.

## Outcomes

Chop now begins through one large start/stop action, and PAD behavior follows audio-thread-applied source playback instead of a second mode switch. Portrait and landscape use the same behavior modules, the redundant full-width edit row is gone, and 226 net lines of active-file frontend complexity were removed while retaining the working production features. Local evidence reaches `LOCAL_PASS`; device and human evidence remain explicitly pending below.

## Risks and rollback

The main risk is fixed-height clipping after row consolidation. Keep each orientation adapter's existing weights and run compact/regular layout tests plus screenshots when an exclusively owned device is available. A routing regression could overwrite a PAD; preserve and rerun assigned-versus-empty pad tests. Rollback is the single implementation commit; project archive bytes are not migrated or changed.

## Remaining device validation

- Exact-final APK on an exclusively owned Android target.
- Physical Pixel touch/audio confirmation of one-tap start, empty-PAD capture, and assigned-PAD audition.
- Portrait and landscape clipping at normal and large font scale.
- TalkBack traversal and long-press trim discovery.
- Subjective confirmation that no duplicate source/PAD audio is heard.
