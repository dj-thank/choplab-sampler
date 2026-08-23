# Keep one ChopLab production continuous on Android and Windows

## Purpose and user-visible outcome

Androidスマホ版とWindows EXE版で、同じ「入れる → チョップ → ビート → 保存」の制作が途切れないようにする。Windowsでsource録音を止めた後は自動でCHOPへ進み、VOICE録音は選択Beat loopを先頭から再始動して合わせ、`.choplab` をEXE引数で開いたsessionでもその後の編集をautosaveする。

## Current state

- Implementation worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-cross-platform-polish-20260823`.
- Branch/base: `codex/choplab-cross-platform-polish`, `9a4e9edc2686914c28c91b2d614dfb95281935c2`, tree `4e79be4cbb8923b67076da146c420322c0dd943a`.
- Canonical dirty checkout remains untouched.
- Fresh local baseline: `:app:testDebugUnitTest :jvm-core:test :desktop:test --offline --no-daemon --max-workers=1 --no-watch-fs --console=plain` passed on 2026-08-23 using JDK 17, portable Android SDK, and `work/gradle-home`.
- `DesktopSamplerController.toggleRecording` currently stops all playback for every recording kind. It does not restart the selected Beat loop for `VOCAL_OVERDUB`.
- Source-recording decode updates `currentAudio` but does not publish `projectLaunchTarget=CHOP` or a new launch revision.
- `DesktopApp` passes `autosaveStore=null` whenever a startup `.choplab` or WAV is supplied, disabling autosave for the whole session.
- Pixel was not connected at the fresh 20:03 JST preflight. Another branch's device receipt is not evidence for this branch.

## Constraints and invariants

- Preserve the canonical dirty checkout and the separate Spotify branch. No reset, clean, broad deletion, or force checkout.
- `DesktopAudioRecorder` is the testable capture seam; source and vocal recordings never overlap.
- A vocal take restarts the selected loop from its beginning after recorder start succeeds. Failure must not claim recording or loop success.
- Startup project import may suppress stale autosave recovery, but it must not disable future autosave.
- Project formats and persisted schema do not change.
- No Pixel uninstall/data clear, recording, device-audio capture, Spotify authentication, provider/public operation, push, release, or Human claim.
- Evidence remains `LOCAL_PASS` until this exact branch/bytes receive a separate device receipt.

## Architecture and interfaces

`DesktopSamplerController` remains the single Windows lifecycle integrator. Its recorder constructor dependencies use the existing `DesktopAudioRecorder` interface so host tests can observe the public controller state and audio-port commands without opening hardware. `recoverAutosaveOnStart` separates initial recovery policy from the presence of an `AtomicProjectStore`; the store remains available to `scheduleAutosave`. Shared `SamplerUiState.projectLaunchTarget` and `projectLaunchRevision` remain the UI navigation contract used by Android and Windows.

## Milestones

### Milestone 1: RED tests for the three continuity seams

- Files: `desktop/src/test/kotlin/com/choplab/desktop/DesktopSamplerControllerTest.kt`.
- Add a fake `DesktopAudioRecorder` that produces a bounded valid WAV without opening an input device.
- Prove current failures for source-capture CHOP routing, vocal loop restart, and recovery-disabled-but-autosave-enabled startup policy.
- Acceptance: focused desktop tests fail for the expected missing behavior, not setup errors.

### Milestone 2: Minimal controller and startup wiring

- Files: `desktop/src/main/kotlin/com/choplab/desktop/DesktopSamplerController.kt`, `desktop/src/main/kotlin/com/choplab/desktop/DesktopApp.kt`.
- Change recorder dependency types to `DesktopAudioRecorder` while preserving concrete defaults.
- Restart the selected Beat loop after vocal recorder start succeeds; keep source recording exclusive.
- Publish CHOP launch target/revision after decoded source recording succeeds.
- Separate `recoverAutosaveOnStart` from `autosaveStore`, and keep the default store for startup-file sessions.
- Acceptance: focused tests turn GREEN; no provider or hardware call is required.

### Milestone 3: Cross-platform local gate and UI contract

- Run focused desktop tests, all desktop/JVM/Android unit tests, Android lint/assemble, Windows packaging, configured validation, public-surface scan, and `git diff --check`.
- Confirm the existing `docs/ui/android-parity-contract-v2.json` remains valid with nine mapped regions.
- Update `docs/PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md`, and this plan with exact results.
- Acceptance: source-bound local commands pass or each failure is classified with a concrete blocker.

### Milestone 4: Scoped device journey when Pixel is present

- Recheck one time after the local gate. If unavailable, record a device blocker and stop repeated polling.
- If present, verify serial/package/version/signer/hash, use only `adb install -r`, retain data, and run non-recording UI/state smoke. Restore any changed rotation or volume setting.
- Acceptance: exact APK/revision receipt plus negative-path results. Do not claim audio quality, recording, TalkBack speech, or Human approval.

## Progress

- [x] 2026-08-23 — Rebuilt current state and isolated a clean worktree from `origin/main`.
- [x] 2026-08-23 — Fresh Android/JVM/desktop unit baseline passed; visual references and nine-region UI contract inspected.
- [x] 2026-08-23 — Direction A selected over broad reskin, native-audio work, and feature expansion.
- [x] 2026-08-23 — Milestone 1: each of the three public seams observed RED for the intended missing behavior/API.
- [x] 2026-08-23 — Milestone 2: minimal controller/startup implementation turned all focused tests GREEN.
- [x] 2026-08-23 — Milestone 3: 91-task cross-platform gate, configured validation, public-surface scan, UI contract, package smoke, and `git diff --check` passed.
- [ ] Milestone 4 device slice or explicit unavailable blocker; Pixel was absent at the first bounded preflight.

## Discoveries

- The Windows and Android surfaces already compile the same responsive deck. Current visual evidence supports structural parity, but not fresh landscape/resize or physical usability.
- The highest-impact current gaps are lifecycle gaps in Windows controller wiring rather than missing shared UI regions.
- A prior Luna read-only probe resolved to a writable effective sandbox and was rejected; no child packet is acceptance evidence.

## Decision log

- 2026-08-23 — Select production continuity parity as the primary direction because it fixes user-visible breaks with deterministic local checks and no external gate.
- 2026-08-23 — Keep responsive retuning as fallback until fresh same-state landscape and 200% DPI captures exist.
- 2026-08-23 — Keep the separate Spotify/full-hardening branch unmerged in this task; its 74-file history and device receipt have different scope and revision.

## Validation log

- `:app:testDebugUnitTest :jvm-core:test :desktop:test --offline --no-daemon --max-workers=1 --no-watch-fs --console=plain`
  - 2026-08-23, Windows / JDK 17 / portable Android SDK / `work/gradle-home`.
  - `BUILD SUCCESSFUL` in 1m39s.
- `adb devices -l`
  - 2026-08-23T20:03:47+09:00.
  - No devices attached; no install or mutation attempted.
- `:desktop:test :jvm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktop:packageWindows --offline --no-daemon --max-workers=1 --no-watch-fs --console=plain`
  - 2026-08-23, Windows / JDK 17 / portable Android SDK / `work/gradle-home`.
  - `BUILD SUCCESSFUL` in 1m27s; 91 tasks. Android 225, JVM-core 44, desktop 38 tests; failures/errors/skips 0; lint errors 0.
- `scripts/validate_project.sh`, `scripts/check_public_surface.py`, UI contract validator, and `git diff --check`
  - 2026-08-23.
  - PASS; public candidates 322; UI regions 9 (`4 exact / 4 semantic / 1 adapted`).

## Risks and rollback

- Recorder timing can claim alignment without actually restarting the Beat. Protect with fake audio-port assertions and state assertions.
- Startup recovery and explicit project open can race if one store flag controls both. Separate the policy and keep persistence single-owner.
- The finished Spotify branch also edits `DesktopApp.kt` and `DesktopSamplerController.kt`. Keep this branch isolated; later integration must reconcile both diffs explicitly.
- Rollback is path-scoped reversal of this branch's changes. The canonical checkout and device data remain untouched.

## Remaining device validation

- Pixel retained-data install and non-recording UI journey for the exact final APK, only after reconnect.
- Physical touch comfort, route loss, latency, microphone/system capture, audio quality, TalkBack speech, Spotify account behavior, signed distribution, and `HUMAN_GO` remain separate tasks.
