# Make playback interruption-safe without adding UI complexity

## Purpose and user-visible outcome

ChopLab must never leave stale sampler audio playing after the app moves to the background, loses Android audio focus, or the active wired/Bluetooth output disappears. A user can return to the app and deliberately restart playback without hearing doubled audio or an unexpected automatic resume. Starting playback while another app owns audio focus must fail with beginner-friendly feedback instead of partially starting.

System-audio capture is a deliberate exception: moving ChopLab to the background is required to capture another app, so that recording continues while ChopLab playback stops. Microphone and vocal-overdub recording stop gracefully on interruption because continuing those sessions without an audible reference is unsafe.

## Starting state

- Authority checkout: `C:\Users\rambo\Documents\ChatGPT\pad\work\codex-workspace\ChopLab-Codex-Workspace`
- Branch and starting commit: `agent/gpt-pro-ui-integration` at `717ba2e193044d9b8b67c2d0421b90b67cba6d18`.
- `app/src/main/java/com/choplab/sampler/SamplerViewModel.kt` starts sampler playback directly at several entry points and has no Android audio-focus or becoming-noisy owner.
- `app/src/main/java/com/choplab/sampler/MainActivity.kt` has no playback lifecycle handoff.
- `stopCompetingPlayback()` already enforces one primary playback mode inside the app, but Android lifecycle/output interruptions are outside that boundary.
- Existing untracked `outputs/` and `work/` contain release/device evidence and are not implementation inputs or commit targets.
- Baseline on 2026-08-14 (Windows, JDK 17, Android SDK 36): `scripts/doctor.sh` passed required Git/Java/SDK/ADB/Gradle checks; optional NDK/CMake and Codex-login checks warned. `scripts/validate_project.sh` passed after supplying `F:\CodexData\ChopLab\tools\kotlin-compiler-2.3.21\kotlinc\bin`.
- Pixel 9a serial `5A121JEBF08094` is not currently enumerated. Physical-device validation remains separate.

## Constraints and invariants

- Keep `minSdk = 29`, `targetSdk = 36`, Java/Kotlin target 17, and the current application ID.
- Do not allocate, block, register receivers, or call Android focus APIs from the real-time `AudioTrack` render loop.
- `SamplerViewModel` remains the application-session owner; the Android adapter owns only `AudioManager` focus and the noisy-output receiver.
- All user playback starts pass through one public coordinator decision before touching `SamplerPlaybackEngine`.
- Audio-focus loss, transient loss, duck requests, app background, and noisy-output changes all stop playback. Audio-focus gain never auto-resumes.
- Repeated interruption signals are idempotent and cannot request duplicate recorder teardown.
- App background preserves `SOURCE_SYSTEM_AUDIO` capture, but requests a graceful stop for `SOURCE_MICROPHONE` and `VOCAL_OVERDUB` unless that session is already stopping.
- Configuration changes do not count as an app-background interruption.
- Existing project bytes, autosaves, loaded pads, edit history, and saved schema are unchanged.
- No copyrighted drum/audio assets or network downloads are introduced.
- Emulator evidence cannot be reported as Pixel evidence, and local/debug evidence cannot be promoted to public-release or human approval evidence.

## Architecture and interfaces

`app/src/main/java/com/choplab/sampler/audio/PlaybackInterruptionCoordinator.kt` is the deep module and policy owner. Its small public surface is:

- `PlaybackFocusAdapter.requestPlaybackFocus(): Boolean`
- `PlaybackFocusAdapter.abandonPlaybackFocus()` and `close()`
- `PlaybackInterruptionCoordinator.beginPlayback(): PlaybackStartDecision`
- `PlaybackInterruptionCoordinator.canRetargetPlayback(): Boolean`
- `PlaybackInterruptionCoordinator.endPlaybackSession()`
- `PlaybackInterruptionCoordinator.interrupt(event, recordingSession): PlaybackInterruptionPlan?`
- `PlaybackInterruptionCoordinator.close()`

The coordinator owns only whether a playback session is active. It does not know the engine, UI state, Android APIs, or recorder implementations. An interruption plan describes observable work: stop all playback, optionally request recording stop, and show a Japanese status message. Returning `null` means the same interruption has already been handled and no duplicate work is needed.

`app/src/main/java/com/choplab/sampler/audio/AndroidPlaybackFocusAdapter.kt` is the Android boundary. It uses media/music `AudioAttributes`, requests `AUDIOFOCUS_GAIN`, maps every non-gain focus change to an interruption callback, registers the protected system broadcast `AudioManager.ACTION_AUDIO_BECOMING_NOISY` with a context receiver visible to system senders, and never automatically resumes.

`SamplerViewModel` constructs and closes both objects, replaces its recording-only playback guard with a single playback-start gate, and translates interruption plans into existing `engine.stopAllPlayback()`, `stopAllPlaybackState`, and `stopActiveRecording()` operations. Playback-mode transitions may stop competing engine voices while retaining focus; explicit all-stop, project replacement/reset, recording start, interruption, and teardown release focus.

`MainActivity` owns no audio policy. It holds its existing `SamplerViewModel` as an activity property and calls `handlePlaybackInterruption(APP_BACKGROUND)` from `onStop()` only when `isChangingConfigurations` is false.

## Milestones

### Milestone 1: Lock the pure interruption contract with TDD

- Scope: stateful playback focus acquisition/release, interruption idempotence, and recording policy.
- Files/interfaces expected to change: new coordinator production and unit-test files under `app/src/main/java/.../audio` and `app/src/test/java/.../audio`.
- Implementation steps: write one failing public-behavior test; run it and record RED; add the smallest implementation; add one behavior slice at a time.
- Tests/checks: focused JUnit task for `PlaybackInterruptionCoordinatorTest`.
- Acceptance evidence: focus denial blocks playback; interruption abandons focus once; repeated events are inert; system capture remains active in background; microphone/vocal stop requests are emitted once; focus gain is not a resume event.

### Milestone 2: Add the Android audio boundary

- Scope: audio focus, noisy-output receiver, lifecycle teardown.
- Files/interfaces expected to change: `AndroidPlaybackFocusAdapter.kt`, Android manifest only if required (expected not required), focused adapter-policy tests where Android-free seams exist.
- Implementation steps: create application-context adapter, map focus events, register/unregister receiver, compile against API 36 while retaining API 29 compatibility.
- Tests/checks: Kotlin/JVM tests for pure mapping plus Android compilation/lint.
- Acceptance evidence: the protected system broadcast receiver is visible to system senders, unregister is idempotent, all non-gain focus callbacks route to one interruption, and gain routes nowhere.

### Milestone 3: Integrate every playback and recording transition

- Scope: ViewModel start gates, interruption execution, lifecycle callback, and focus release paths.
- Files/interfaces expected to change: `SamplerViewModel.kt`, `MainActivity.kt`, existing ViewModel/model tests where needed.
- Implementation steps: route scratch/source/pad/loop/transport/preview starts through the gate; preserve intentional vocal-loop playback; release focus before recording and all-stop; route activity background safely.
- Tests/checks: focused unit tests, compile, static search confirming no unowned playback-start sites.
- Acceptance evidence: every engine start/trigger path either enters through the gate or documents an already-focused retarget; no configuration-change stop; system capture background policy is preserved.

### Milestone 4: Full local and emulator validation

- Scope: regressions, lifecycle/runtime smoke tests, APK identity.
- Files/interfaces expected to change: tests, evidence under ignored `work/` or `outputs/`, and plan validation log.
- Implementation steps: run repository validation, unit tests, lint, debug/release assemble, and connected tests if an owned emulator is safely available; install the exact debug APK on that emulator and exercise play/background/return and noisy/focus behavior where automation permits.
- Tests/checks: `scripts/validate_project.sh`, Gradle unit/lint/assemble/connected checks, SHA-256 and package/version inspection.
- Acceptance evidence: all local gates pass; artifact path/hash are recorded; any emulator-only limitation is named.

### Milestone 5: Review, document, checkpoint, and publish candidate evidence

- Scope: independent code review, repository state docs, commit/push/CI, and release decision.
- Files/interfaces expected to change: `PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md`, this plan, and version/release files only if a new public preview is cut.
- Implementation steps: run correctness and architecture reviews; fix findings; update evidence; commit only tracked implementation/docs; push exact commit; inspect CI; publish only artifacts built from the reviewed commit.
- Tests/checks: clean tracked diff, exact commit/tree, CI checks, GitHub asset hash if released.
- Acceptance evidence: review findings resolved or explicitly accepted; commit and artifact provenance are exact; public state is not claimed before provider readback.

## Progress

- [x] 2026-08-14 10:45 +09:00 — Four fresh-context Luna architecture candidates reviewed the current tree; the playback-interruption boundary ranked highest for user impact, cohesion, and offline testability.
- [x] 2026-08-14 10:51 +09:00 — Required repository guidance, TDD test/mocking guidance, and baseline validation were read and verified.
- [x] 2026-08-14 11:02 +09:00 — Milestone 1 completed through repeated RED/GREEN slices: focus denial, focus lifecycle, interruption idempotence, recording policy, and close behavior.
- [x] 2026-08-14 11:06 +09:00 — Android focus/noisy adapter, every playback entry gate, activity `onStop`, recording transitions, and teardown integration compiled and passed focused tests.
- [x] 2026-08-14 11:19 +09:00 — Full unit tests, lint, debug/release assembly, APK identity, and dedicated API 36 emulator lifecycle checks passed. Physical route/focus tests remain separate.
- [x] 2026-08-14 11:51 +09:00 — Independent Standards/Spec reviews and final parent verification completed; unknown focus changes now fail closed, playback retargets prove coordinator ownership, and repository state/evidence docs are updated. Child effective-model metadata was unavailable, so no runtime-verified Luna claim is made.
- [ ] Commit/push/CI/release evidence.
- [ ] Pixel 9a data-preserving install and physical interaction checks.

## Discoveries

- The render engine is already continuously started in `SamplerViewModel.init`; focus must therefore gate audible commands, not engine construction.
- System-audio capture intentionally needs app background operation. A blanket recorder stop in `onStop()` would break a primary feature.
- Existing `RecordingSession` phases provide the idempotence signal needed to avoid duplicate stop operations; a `STOPPING` session must not be stopped again.
- Playback pitch/seek retargets call `playSource` while playback is already applied. They must preserve the existing focus session rather than request a new one on every slider movement.
- Review exposed that UI playback state alone was not sufficient proof for a retarget. `canRetargetPlayback()` now requires the coordinator's active focus session as a second invariant.
- Current Android broadcast guidance requires `RECEIVER_EXPORTED` for broadcasts from the system or other apps. `ACTION_AUDIO_BECOMING_NOISY` is a platform protected broadcast, so an exported context receiver receives the system signal without allowing ordinary apps to spoof it. Sources: `https://developer.android.com/develop/background-work/background-tasks/broadcasts` and `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/res/AndroidManifest.xml`.
- `dumpsys audio` includes historical focus request events after the live focus stack. Runtime checks must parse only the current `Audio Focus stack entries` section; a broad package-name search gives false positives.
- `adb shell am start -n ...` created a second `MainActivity` during the first lifecycle probe. Launcher-style resume via `monkey -p ... -c android.intent.category.LAUNCHER 1` reused the existing task and produced valid `onStop` evidence.

## Decision log

- 2026-08-14 10:45 +09:00 — Selected `PlaybackInterruptionCoordinator` over primary-transition, recording-presentation, and revision-store candidates because it centralizes a high-impact safety invariant without adding front-end controls.
- 2026-08-14 10:51 +09:00 — Chose fail-closed handling for every Android focus loss, including duck requests. A sampler with layered playback cannot safely lower only one source without inconsistent mix state.
- 2026-08-14 10:51 +09:00 — Chose no automatic resume after focus gain. A deliberate user action is safer and prevents stale beat/source combinations from returning.
- 2026-08-14 10:51 +09:00 — Chose to retain playback focus during internal primary-mode transitions and release it at explicit session boundaries, avoiding focus churn between source, chop, loop, scratch, and transport.
- 2026-08-14 11:04 +09:00 — Changed the planned noisy receiver from non-exported to exported after checking current Android guidance and the platform protected-broadcast list. The action remains exact-match checked in `onReceive`.
- 2026-08-14 11:51 +09:00 — Reserved versionCode 21 / versionName 0.13.1 and a new preview tag instead of mutating the already-public v0.13.0 artifact.

## Validation log

- Command: `scripts/doctor.sh`
- Date/environment: 2026-08-14, Windows PowerShell/Git Bash dependencies, repository starting commit `717ba2e`.
- Result: PASS for required Git, Java 17, Android SDK/ADB and Gradle checks; optional NDK/CMake and Codex-login warnings only.
- Important output or artifact path: terminal evidence for this run; no tracked artifact.

- Command: `scripts/validate_project.sh` with `PATH` including `F:\CodexData\ChopLab\tools\kotlin-compiler-2.3.21\kotlinc\bin`
- Date/environment: 2026-08-14, JDK `F:\CodexData\ChopLab\tools\jdk17\jdk-17.0.20+8`, SDK `F:\CodexData\ChopLab\tools\android-sdk`.
- Result: PASS.
- Important output or artifact path: terminal evidence for this run; no tracked artifact.

- Command: `./gradlew.bat :app:testDebugUnitTest --no-daemon`
- Date/environment: 2026-08-14, Windows/JDK 17/Android SDK 36.
- Result: PASS, including the new coordinator, Android focus policy, and activity lifecycle tests.
- Important output or artifact path: `app/build/reports/tests/testDebugUnitTest/index.html`.

- Command: `./gradlew.bat :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon`
- Date/environment: 2026-08-14, Windows/JDK 17/Android SDK 36.
- Result: PASS; lint reported 0 errors and 11 pre-existing dependency/Compose/manifest warnings, with no new warning in the interruption module.
- Important output or artifact path: `app/build/reports/lint-results-debug.html`, `app/build/outputs/apk/debug/app-debug.apk`, and `app/build/outputs/apk/release/app-release-unsigned.apk`.

- Command: `adb -s emulator-5590 install -r app/build/outputs/apk/debug/app-debug.apk` plus launcher/UI/audio-focus probes.
- Date/environment: 2026-08-14, dedicated tracked API 36 `sdk_gphone64_x86_64` emulator on port 5590. Shared Neefo emulator 5588 was not touched.
- Result: PASS. Debug APK SHA-256 `2FAE75700B31D17D07A3969379A22F52B986E5ED8BE3075FE39627704B8A3317`, package `com.choplab.sampler`, version `0.13.0`/20, debug signer SHA-256 `c0be467a0f8010bed6f2687d1fdd138498e99b0401722c487459aeedc453d587`. Playback acquired `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC` focus; Home changed UI status to `バックグラウンド移行のため再生を停止しました` and left the live focus stack empty; rotation retained focus before, during, and after configuration recreation; ALL STOP emptied the stack.
- Important output or artifact path: ignored runtime captures under `work/playback-interruption-*.xml`, `work/background-result.xml`, and `work/rotation-result.xml`.

- Command: post-review `scripts/validate_project.sh` and `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon`
- Date/environment: 2026-08-14, Windows/JDK 17/Android SDK 36, versionCode 21 / versionName 0.13.1.
- Result: PASS; 207 tests in 44 suites, zero failures/errors/skips; Lint 0 errors / 11 advisories; both APK variants assembled.
- Important output or artifact path: `outputs/ChopLab-v0.13.1-playback-interruption-safety-local-debug.apk`, 30,821,319 bytes, SHA-256 `9A11118395AEC68AF6A739416514135FAEFF562302EB541573A49CF48A038668`.

- Command: exact v0.13.1 APK install plus Home/return, rotation, and ALL STOP focus probes on `emulator-5590`.
- Date/environment: 2026-08-14, dedicated tracked Android 16/API 36 emulator; shared `emulator-5588` remained untouched.
- Result: PASS. Home emptied the live focus stack and surfaced the background-stop message; configuration recreation retained focus; ALL STOP released it. Protected noisy-route broadcast injection was not treated as physical route evidence.
- Important output or artifact path: ignored `work/playback-interruption-final-*.xml` captures and terminal focus-stack evidence.

## Risks and rollback

- Risk: missing a direct engine start path leaves an ungated playback command. Mitigation: static search all `SamplerPlaybackEngine` audible methods after integration and classify each site.
- Risk: lifecycle handling stops system capture when the user switches apps. Mitigation: explicit policy tests and `SOURCE_SYSTEM_AUDIO` exemption.
- Risk: activity recreation accidentally creates two ViewModels/adapters. Mitigation: activity-scoped `viewModels()` owner and idempotent adapter close.
- Risk: focus denial leaves UI in a playing state. Mitigation: gate before engine/UI mutation and test denied-start behavior.
- Risk: interruption races recorder completion. Mitigation: reuse `stopActiveRecording()` and existing `STARTING/RECORDING/STOPPING` ownership instead of directly clearing recording state.
- Rollback: revert this feature commit; no persistence schema, project archive, audio asset, or user data migration is involved.

## Remaining device validation

- Pixel 9a serial `5A121JEBF08094`: install with `adb install -r` only after package/version/certificate and artifact SHA checks.
- Confirm existing app data/projects remain after install.
- Confirm source, pad, loop, scratch, and transport stop on Home/background and do not auto-resume.
- Confirm rotating the device does not stop playback solely because of configuration change.
- Confirm wired/Bluetooth route loss stops playback without speaker blast.
- Confirm transient/permanent audio focus loss from another media app or call stops playback once.
- Confirm system-audio recording continues while switching to the source app, while mic and vocal recording stop safely on interruption.
- Confirm no doubled playback after returning to ChopLab and deliberately restarting.
