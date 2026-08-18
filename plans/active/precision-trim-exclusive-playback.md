# Precision trim, exclusive loop playback, and clearer Chop switching

This ExecPlan is a living document. Keep `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` current while the work proceeds.

## Purpose

Make the beginner Chop path dependable: an assigned PAD can be long-pressed and trimmed at frame-level precision, the trim can be restored to the value captured when the editor opened, starting a beat loop cannot stack an earlier source or preview voice, and BANK/page/PAD switching remains immediately legible without adding scrolling.

## Progress

- [x] (2026-08-12) Confirmed branch baseline `main` at `b0fd2b9`, preserved existing untracked `outputs/` and `work/`, and created `agent/precision-trim-exclusive-playback`.
- [x] (2026-08-12) Mapped source playback, preview voice, PAD loop, trim history, long-press navigation, and BANK/page controls.
- [x] (2026-08-12) Added focused RED regressions for preview/loop and source/loop exclusivity, then made them GREEN.
- [x] (2026-08-12) Implemented one audio-thread loop-start boundary for source, preview, and same-PAD audition voices while preserving other layers.
- [x] (2026-08-12) Added model tests for frame/ms nudge precision, source-safe entry snapshots, restore behavior, and viewport centering.
- [x] (2026-08-12) Implemented the compact precision trim UI and clearer Chop mode/BANK/page/PAD labels.
- [x] (2026-08-12) Ran 151 host tests, validation, lint, APK assembly, diff/no-scroll checks, two Luna reviews, and focused emulator display/launch checks.
- [x] (2026-08-12) Updated project evidence and prepared the reviewed change for commit.

## Surprises & Discoveries

- The engine uses a dedicated `sourceVoice` for the imported song and pooled `voices` for PAD/preview playback. `StartPadLoop` currently deactivates only a pooled voice with the target PAD index, so preview index `-1` and `sourceVoice` survive and can audibly stack.
- `WaveformEditor` already has zoom, viewport navigation, and time readouts, but `PadTrimEditor` explicitly disables them.
- Global Undo/Redo history already coalesces continuous start/end edits, but the long-press editor has no local baseline or explicit restore action.
- A final realtime review found that a full command mailbox could advance source generation without admitting the loop command. A first rollback approach could reuse a stale generation, so it was replaced: the mailbox now reserves capacity before running the producer-side source-stop preparation. Focused tests prove rejected preparation has no side effect and admitted preparation runs once.
- The only enumerated Android target was `emulator-5588`. Another task repeatedly moved Neefo to the foreground during later interaction, so emulator observations are focused screenshots/launch checks rather than an exclusive end-to-end audio DEVICE_PASS.

## Decision Log

- Decision: loop start is an exclusive handoff only for imported-source playback, anonymous preview, and the target PAD audition. Pattern/drum/vocal layering remains intact.
  Rationale: this removes accidental duplicates without breaking the product's intentional layering model.
- Decision: capture a trim-session baseline in the ViewModel and expose an explicit restore action that itself remains Undo-compatible.
  Rationale: closing the editor continues to keep edits, while the user always has a clear way back to the entry state.
- Decision: reuse the existing fixed-console waveform controls, raise useful zoom precision, and add exact nudge steps rather than introducing a scrollable detailed screen.
  Rationale: this honors the no-scroll requirement and minimizes new concepts.
- Decision: improve BANK/page/PAD labels and active-state wording in place rather than add another navigation layer.
  Rationale: the user asked for simpler Chop operation and fewer competing controls.

## Context and Orientation

The Android app lives under `app/`. Audio commands and realtime voice ownership are in `app/src/main/java/com/choplab/sampler/audio/SamplerEngine.kt`. Pure trim operations are in `app/src/main/java/com/choplab/sampler/model/SamplerCommands.kt`; orchestration and edit history are in `app/src/main/java/com/choplab/sampler/SamplerViewModel.kt`. The fixed Compose console is primarily `app/src/main/java/com/choplab/sampler/ui/OtohiroiDeck.kt`, with waveform interaction in `WaveformEditor.kt` and long-press PAD gestures in `PadGrid.kt`.

Frames are start-inclusive and end-exclusive. Realtime command application must not add locks, blocking I/O, or avoidable allocations. Existing project data and unrelated untracked files must remain untouched.

## Plan of Work

First, add a focused test that represents the currently failing transition from preview/source playback into one PAD loop. Make it fail on the current implementation, then make loop start carry a source stop boundary and retire conflicting pooled voices before creating the loop voice. Keep other BANK voices and sequencer transport alive.

Second, add pure trim range/baseline behavior and ViewModel session methods under tests. The editor should support exact one-frame movement as well as practical millisecond steps, clamp through the existing trim invariant, show zoom/time controls, preview the current PAD, and restore the captured entry range in one Undo-compatible edit.

Third, make the Chop switching hierarchy self-describing. BANK buttons must retain the A Melody / B Drums / C One Shots / D Voice roles, page buttons must clearly identify 01-16 versus 17-32 and which page is active, and the selected PAD should be visible in the switch labels. Do not introduce scrolling or hide Scratch/Drums behind new menus.

Finally, run focused and full verification, inspect the diff for realtime/audio/UI regressions, build a debug APK, and install/smoke it only on the currently enumerated owned Android target. Record exact evidence and remaining subjective-device gaps in `docs/PROJECT_STATE.md`.

## Concrete Steps

From the repository root, use the portable toolchain under `F:\CodexData\ChopLab\tools`, then run focused tests with Gradle's `--tests` filter. After implementation run:

    scripts/validate_project.sh
    ./gradlew testDebugUnitTest lintDebug assembleDebug --offline --no-daemon --max-workers=1
    git diff --check

Also scan the UI sources for `verticalScroll`, `horizontalScroll`, and `rememberScrollState`. If an Android device is connected, enumerate it before installation, use `adb install -r` only for `com.choplab.sampler`, cold-launch `MainActivity`, and inspect a focused fatal log query. Preserve app data.

## Validation and Acceptance

Acceptance requires all of the following:

- Starting a PAD loop after waveform preview leaves only one copy of that PAD audio.
- Starting a PAD loop while the imported source plays stops the source at the loop-start boundary.
- Intentional pattern/drum/vocal layering is not globally silenced.
- Long-press trim exposes visible waveform zoom/time navigation and exact one-frame nudging.
- `Restore entry trim` returns both boundaries to the values captured when the trim editor opened, and Undo can reverse that restore.
- BANK, PAD page, and selected PAD are visually unambiguous in Chop portrait and landscape layouts.
- No scrolling API is introduced; validation, host tests, lint, and APK assembly pass.

## Outcomes & Retrospective

The implementation now gives long-press trim a visible 1x-256x waveform viewport, time readout, one-frame/1 ms/10 ms boundary nudges, preview, and an explicit entry-range restore. Chop mode and BANK/page/PAD selection are readable without relying on color alone. Loop start atomically retires imported-source, preview, and same-PAD audition playback without silencing intentional other-PAD layers.

Local validation passed with 153 tests, zero failures/errors/skips; Android Lint had zero errors and seven platform/toolchain advisories; offline project validation and the no-scroll scan passed. The final local debug APK is `outputs/ChopLab-v0.11.3-precision-trim-local-debug.apk`, 31,627,306 bytes, SHA-256 `4E255B329D4A8A85194F79B1E106B91D215C3CBFF4FFEB92DEDF1624970CE1A9`.

Focused pre-final-candidate emulator observation confirmed install/cold launch, the clarified Chop switching surface, assigned-PAD long press, the precision trim controls, and no AndroidRuntime fatal signal. The final readout-color and queue-generation corrections were rebuilt but not reinstalled after another task took foreground ownership of the shared emulator. Exact-final-artifact interaction, subjective duplicate-audio listening, physical Pixel behavior, TalkBack, landscape interaction, and sustained realtime stress remain device-only gaps.
