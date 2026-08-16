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
- `PlaybackInterruptionCoordinator.interrupt(event, recordingSession): PlaybackInterruptionOutcome?`
- `PlaybackInterruptionCoordinator.close()`

The coordinator owns whether a playback session is active and the safety ordering that silences it before focus release. It knows only the small `PlaybackSilencer` and `PlaybackFocusAdapter` interfaces, not the engine, UI state, Android API, or recorder implementations. An interruption outcome reports whether playback was already stopped, whether the caller must request recorder teardown, and the Japanese status message. Returning `null` means the same interruption has already been handled and no duplicate work is needed.

`app/src/main/java/com/choplab/sampler/audio/AndroidPlaybackFocusAdapter.kt` is the Android boundary. It uses media/music `AudioAttributes`, requests `AUDIOFOCUS_GAIN`, maps every non-gain focus change to an interruption callback, registers the protected system broadcast `AudioManager.ACTION_AUDIO_BECOMING_NOISY` with a context receiver visible to system senders, and never automatically resumes.

`SamplerViewModel` constructs and closes both objects, replaces its recording-only playback guard with a single playback-start gate, and projects interruption outcomes through `stopAllPlaybackState` and `stopActiveRecording()`. It does not stop the engine a second time after an interruption. Playback-mode transitions may stop competing engine voices while retaining focus; explicit all-stop, project replacement/reset, and recording start silence the engine before ending their focus session.

`MainActivity` owns no audio policy. It holds its existing `SamplerViewModel` as an activity property and calls `handlePlaybackInterruption(APP_BACKGROUND)` from `onStop()` only when `isChangingConfigurations` is false.

The post-release maintenance seam adds a small `PlaybackSilencer` adapter to the coordinator. Android interruption and active-session `close()` must silence the engine before abandoning playback focus. The returned interruption outcome reports that playback was already stopped, leaving `SamplerViewModel` responsible only for projecting UI state and requesting recorder teardown. Explicit in-app stops retain their existing order: the caller silences the engine, then calls `endPlaybackSession()`.

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

### Milestone 6: Enforce silence before focus release
- Scope: local-only post-release correction to interruption and teardown ordering; no version, UI, schema, device, or provider change.
- Files/interfaces expected to change: `PlaybackInterruptionCoordinator.kt`, `SamplerViewModel.kt`, `PlaybackInterruptionCoordinatorTest.kt`, this plan, and observed-state documentation.
- Implementation steps: add a failing public-interface order test; inject the smallest playback-silencing adapter; return an outcome that says playback is already stopped; remove duplicate caller-side engine stop; cover active-session close ordering.
- Tests/checks: focused coordinator test, full unit suite, Lint, debug/release assembly, repository validation, static call-site scan, and final code review.
- Acceptance evidence: every interruption records `silence -> abandon focus`; repeated interruption performs teardown once; recording-only interruption does not silence; active close records `silence -> abandon focus -> close`; all local gates pass.

### Milestone 7: Restore selectable drums and single-owner interactive playback

- Scope: local-only correction for empty BANK B selection, loading-time stale audio, Beat selection-to-loop overlap, and scratch gesture timeout; no schema, asset, version, device, provider, or public change.
- Files/interfaces expected to change: `SamplerCommands.kt`, `SamplerViewModel.kt`, `ScratchControl.kt`, `BeatLaneBoard.kt`, `OtohiroiDeck.kt`, their focused host tests, this plan, and observed-state documentation.
- Implementation steps: allow empty bank selection with role guidance; silence once at source-replacement start and block starts while loading; make Beat rail selection-only while preserving explicit preview surfaces; define and test a shared scratch idle policy.
- Tests/checks: focused RED/GREEN host tests, full unit suite, Lint, debug/release assembly, repository validation, fixed-point Standards/Spec review, and whitespace/static diff review.
- Acceptance evidence: empty BANK B selects and exposes drum-kit guidance; loading begins silent and rejects playback; selecting a Beat PAD then Loop creates no preview overlap; ordinary 80 ms scratch events remain active while 120 ms is idle; all local gates pass.

## Progress

- [x] 2026-08-16 — Centralized app-owned capture WAV lifecycle: terminal decode/start/reset/teardown paths delete the exact allowlisted file, and a 24-hour startup sweep removes only stale ChopLab capture names; filesystem ownership tests are green.
- [x] 2026-08-16 — Added generation-owned Playback Capture sessions and a bounded stop→release watchdog; deterministic tests cover startup STOP, old-generation isolation, and a blocking worker released within the 1.5 s + 0.5 s contract.
- [x] 2026-08-16 — Added queue clear generations so an `offerPrepared` reserved before shutdown cannot enqueue afterwards or leak capacity; focused concurrent regression is green.
- [x] 2026-08-16 — Added a fake recorder-input seam and a deterministic initialization-race test so `stop()` cannot be lost while the platform recorder is being created; full local build gate passed.
- [x] 2026-08-16 — Serialized `SamplerEngine` runtime command admission with start/shutdown and added deterministic restart/concurrent-producer regressions so stopped-engine commands cannot leak into the next session.

- [x] 2026-08-14 10:45 +09:00 — Four fresh-context Luna architecture candidates reviewed the current tree; the playback-interruption boundary ranked highest for user impact, cohesion, and offline testability.
- [x] 2026-08-14 10:51 +09:00 — Required repository guidance, TDD test/mocking guidance, and baseline validation were read and verified.
- [x] 2026-08-14 11:02 +09:00 — Milestone 1 completed through repeated RED/GREEN slices: focus denial, focus lifecycle, interruption idempotence, recording policy, and close behavior.
- [x] 2026-08-14 11:06 +09:00 — Android focus/noisy adapter, every playback entry gate, activity `onStop`, recording transitions, and teardown integration compiled and passed focused tests.
- [x] 2026-08-14 11:19 +09:00 — Full unit tests, lint, debug/release assembly, APK identity, and dedicated API 36 emulator lifecycle checks passed. Physical route/focus tests remain separate.
- [x] 2026-08-14 11:35 +09:00 — Independent Standards/Spec reviews and final parent verification completed; unknown focus changes now fail closed, playback retargets prove coordinator ownership, and repository state/evidence docs are updated. Child effective-model metadata was unavailable, so no runtime-verified Luna claim is made.
- [x] 2026-08-14 11:44 +09:00 — Reviewed commits `52a62ed` and `903c698` pushed; branch/PR/tag/release workflows passed; public APK and checksum were reverse-downloaded and verified anonymously.
- [x] 2026-08-14 12:23 +09:00 — Milestone 6 started from clean tracked HEAD `830936774d67f15e44fafc244cfc4fc1548ef3ae`; existing untracked `outputs/` and `work/` remain preserved. Focused baseline test and configured offline validation pass. First RED is observed: the order test fails compilation because the required `playbackSilencer` seam and `PlaybackSilencer` interface do not yet exist.
- [x] 2026-08-14 20:27 +09:00 — Three RED/GREEN slices pass at the coordinator seam. Fixed-point local parent review found two Standards issues (duplicated teardown ordering and stale plan/outcome naming) plus two Spec coverage gaps (repeated silence count and recording-only no-silence); all four were corrected.
- [x] 2026-08-14 20:36 +09:00 — Milestone 6 completed locally. Post-review focused tests, 210-test full suite, Lint, debug/release assembly, repository validation, static seam scan, docs, and final Standards/Spec review pass. No device, provider, or public operation was performed.
- [x] 2026-08-15 12:38 +09:00 — Exact `0bdf31c` local APK installed in place on Pixel 9a with matching-certificate preflight, four verified project backups, exact before/after/after-launch archive equality, installed-byte identity, cold launch, process/crash checks, and fixed no-scroll UI capture. No uninstall, data clear, existing Download overwrite, provider, or public operation was performed.
- [x] 2026-08-15 — Milestone 7 reproduced empty-bank refusal, loading-time stale playback, Beat rail auto-preview, and over-eager scratch idle behavior through five focused RED/GREEN slices. Built-in drum assets and project schema were unchanged.
- [x] 2026-08-15 — Fixed-point local parent Standards/Spec review corrected preview scope and duplicate source-replacement stop ownership. Final code review found no unresolved local blocker; effective child-model metadata was unavailable, so no runtime-verified Luna claim is made.
- [x] 2026-08-15 — Milestone 7 full local gate passed: configured repository validation, 213 tests in 44 suites, Lint 0 errors / 12 warnings, and debug/release assembly. Pixel remained reserved by Sanpo; no device, provider, or public operation was performed.
- [ ] Pixel 9a physical audio interruption, recording, no-double-audio listening, scratch-feel, and human-quality checks.

## Discoveries

- The render engine is already continuously started in `SamplerViewModel.init`; focus must therefore gate audible commands, not engine construction.
- System-audio capture intentionally needs app background operation. A blanket recorder stop in `onStop()` would break a primary feature.
- Existing `RecordingSession` phases provide the idempotence signal needed to avoid duplicate stop operations; a `STOPPING` session must not be stopped again.
- Playback pitch/seek retargets call `playSource` while playback is already applied. They must preserve the existing focus session rather than request a new one on every slider movement.
- Review exposed that UI playback state alone was not sufficient proof for a retarget. `canRetargetPlayback()` now requires the coordinator's active focus session as a second invariant.
- Current Android broadcast guidance requires `RECEIVER_EXPORTED` for broadcasts from the system or other apps. `ACTION_AUDIO_BECOMING_NOISY` is a platform protected broadcast, so an exported context receiver receives the system signal without allowing ordinary apps to spoof it. Sources: `https://developer.android.com/develop/background-work/background-tasks/broadcasts` and `https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/res/AndroidManifest.xml`.
- `dumpsys audio` includes historical focus request events after the live focus stack. Runtime checks must parse only the current `Audio Focus stack entries` section; a broad package-name search gives false positives.
- `adb shell am start -n ...` created a second `MainActivity` during the first lifecycle probe. Launcher-style resume via `monkey -p ... -c android.intent.category.LAUNCHER 1` reused the existing task and produced valid `onStop` evidence.
- A post-release architecture audit found an ordering defect in `interrupt()`: it abandoned playback focus before `SamplerViewModel` executed `engine.stopAllPlayback()`. Existing explicit stop paths silence first, so the callback path did not share the same safety invariant.
- Calling `bash` from this PowerShell session resolves to an unavailable WSL relay. Repository shell scripts run correctly through `C:\Program Files\Git\bin\bash.exe`; the initial WSL launch failure is environment-only evidence, not a project failure.

## Decision log

- 2026-08-14 10:45 +09:00 — Selected `PlaybackInterruptionCoordinator` over primary-transition, recording-presentation, and revision-store candidates because it centralizes a high-impact safety invariant without adding front-end controls.
- 2026-08-14 10:51 +09:00 — Chose fail-closed handling for every Android focus loss, including duck requests. A sampler with layered playback cannot safely lower only one source without inconsistent mix state.
- 2026-08-14 10:51 +09:00 — Chose no automatic resume after focus gain. A deliberate user action is safer and prevents stale beat/source combinations from returning.
- 2026-08-14 10:51 +09:00 — Chose to retain playback focus during internal primary-mode transitions and release it at explicit session boundaries, avoiding focus churn between source, chop, loop, scratch, and transport.
- 2026-08-14 11:04 +09:00 — Changed the planned noisy receiver from non-exported to exported after checking current Android guidance and the platform protected-broadcast list. The action remains exact-match checked in `onReceive`.
- 2026-08-14 11:35 +09:00 — Reserved versionCode 21 / versionName 0.13.1 and a new preview tag instead of mutating the already-public v0.13.0 artifact.
- 2026-08-14 12:23 +09:00 — Rejected a broad Primary playback coordinator for this maintenance slice because its interface would expose heterogeneous engine commands, UI projection, and recording exceptions. Selected the smaller silence-ordering seam because it fixes observed behavior with high locality and rollback safety.
- 2026-08-14 12:23 +09:00 — Fixed the TDD seam at the public `PlaybackInterruptionCoordinator.interrupt()/close()` interface with recording adapters for the two external resources: playback silence and Android focus. No private ViewModel method is a test seam.
- 2026-08-14 20:27 +09:00 — Renamed the return type to `PlaybackInterruptionOutcome` and concentrated the safety sequence in `silenceAndEndPlaybackSession()`. This keeps the interface truthful and prevents `close()` and `interrupt()` from drifting apart.

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

- Command: push exact `903c698c2fdc443027a8190aa31985253ff3050a`, create annotated `v0.13.1-preview.1`, monitor GitHub Actions, reverse-download Release assets, inspect APK, and repeat an anonymous HTTP download.
- Date/environment: 2026-08-14, public `dj-thank/choplab-sampler` repository.
- Result: PASS. Branch run `31764219592`, PR run `31764223167`, tag run `31764417666`, and Release run `31764417670` succeeded. Tag object `b11eaa13be6c7e4d8bc7cbfcf805dc8ab25dc436` peels to the exact commit. Public APK digest, sidecar, authenticated hash, and anonymous hash all equal `5EE5183C2CA6574E964CC4A6AE44B4BE72813A691843345D9FA78B5ADE6598D6`; repository, Release, and asset returned anonymous HTTP 200.
- Important output or artifact path: `outputs/ChopLab-v0.13.1-preview.1-debug.apk`, `outputs/ChopLab-v0.13.1-preview.1-debug.apk.sha256`, and `work/public-v0131/` readback evidence.

- Command: `C:\Program Files\Git\bin\bash.exe scripts/validate_project.sh` with JDK 17, SDK, ADB, and Kotlin compiler paths supplied to the process
- Date/environment: 2026-08-14 12:20 +09:00, Windows PowerShell, maintenance starting HEAD `8309367`.
- Result: PASS; pure Kotlin smoke, four Android XML parses, and Gradle Wrapper SHA-256 all passed.
- Important output or artifact path: terminal evidence for this run; no tracked artifact.

- Command: `.\gradlew.bat :app:testDebugUnitTest --tests 'com.choplab.sampler.audio.PlaybackInterruptionCoordinatorTest' --no-daemon`
- Date/environment: 2026-08-14 12:21 +09:00, Windows/JDK 17/Android SDK 36.
- Result: PASS baseline before Milestone 6 changes.
- Important output or artifact path: `app/build/reports/tests/testDebugUnitTest/index.html`.

- Command: same focused Gradle test after adding `interruptionSilencesPlaybackBeforeReleasingFocus`
- Date/environment: 2026-08-14 12:27 +09:00, Windows/JDK 17/Android SDK 36.
- Result: expected RED; unit-test Kotlin compilation reports `No parameter with name 'playbackSilencer' found` and `Unresolved reference 'PlaybackSilencer'`.
- Important output or artifact path: terminal evidence for the RED cycle.

- Command: three focused RED/GREEN cycles through `PlaybackInterruptionCoordinatorTest`
- Date/environment: 2026-08-14 12:27–12:35 +09:00, Windows/JDK 17/Android SDK 36.
- Result: RED 1 missing silencer seam; GREEN 1 interruption order; RED 2 close-order assertion; GREEN 2 active close order; RED 3 unresolved `playbackStopped`; GREEN 3 truthful stopped outcome. Post-review focused rerun also passed with `BUILD SUCCESSFUL`.
- Important output or artifact path: `app/build/reports/tests/testDebugUnitTest/index.html` and terminal evidence.

- Command: fixed-point review of `git diff 830936774d67f15e44fafc244cfc4fc1548ef3ae` against `AGENTS.md`, `docs/DEFINITION_OF_DONE.md`, and Milestone 6
- Date/environment: 2026-08-14 20:20–20:36 +09:00, local parent two-pass; no substitute child model used because child effective-model metadata was unavailable.
- Result: initial Standards 2 / Spec 2 findings corrected. Final Standards 0 / Spec 0 unresolved findings. The final pass verified one teardown implementation, truthful outcome naming/docs, one repeated silence, zero recording-only silence, and no caller-side duplicate engine stop.
- Important output or artifact path: tracked code/tests/docs and terminal diff/static-scan evidence.

- Command: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon --console=plain --max-workers=1 --no-watch-fs`
- Date/environment: 2026-08-14 20:28 +09:00, Windows/JDK 17/Android SDK 36.
- Result: BUILD SUCCESSFUL in 4m25s; 210 tests in 44 suites, zero failures/errors/skips; Android Lint 11 warnings and no errors; debug and unsigned release APKs assembled.
- Important output or artifact path: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.html`, `app/build/outputs/apk/debug/app-debug.apk` (31,046,270 bytes, SHA-256 `507181B5AA3ED958EAF45004189964723DCBE58D27823B4E1456EC6156426172`), and `app/build/outputs/apk/release/app-release-unsigned.apk` (23,603,385 bytes, SHA-256 `B0DD9596A33876AD851998D3B7ED2F78EFC9A3A6A5671C0448B7C0551E9F4F21`).

- Command: configured `C:\Program Files\Git\bin\bash.exe scripts/validate_project.sh`, `git diff --check 8309367`, and focused static seam scans
- Date/environment: 2026-08-14 20:34 +09:00, Windows local checkout.
- Result: PASS; pure Kotlin smoke, four Android XML parses, wrapper SHA-256, whitespace check, constructor wiring, outcome naming, teardown locality, and required regression-test presence all verified.
- Important output or artifact path: terminal evidence; no new copied artifact.

- Command: `work/install-0bdf31c-pixel9a.ps1` preflight followed by its data-preserving `adb install -r` path; launch wrapper quoting was corrected after the install had completed, and launch/final checks were then completed directly without reinstalling.
- Date/environment: 2026-08-15 12:34-12:38 +09:00, Pixel 9a `5A121JEBF08094`, Android 17/API 37, exact repository HEAD `0bdf31c7701612c6c147b6ab9c19b00144bbf714`.
- Result: scoped DEVICE PASS. Package advanced from `0.12.0`/19 to `0.13.1`/21; pulled installed APK equals the 31,046,270-byte target at SHA-256 `507181B5AA3ED958EAF45004189964723DCBE58D27823B4E1456EC6156426172`. Four project archives retained exact path/bytes/SHA-256 before install, after install, and after cold launch. MainActivity became top-resumed, recent and historical crash/ANR counts were zero, and the 176-node captured UI had zero scrollable nodes. Physical audio behavior was not exercised.
- Important output or artifact path: `work/pixel9a-0bdf31c-install-20260815-123455/receipt.json`, binary project backups in the same evidence directory, and `outputs/ChopLab-v0.13.1-0bdf31c-Pixel9a-install-receipt.md`.

- Command: five focused RED/GREEN host-test slices for playable-bank selection, source replacement, Beat rail accessibility/preview policy, and scratch idle timing.
- Date/environment: 2026-08-15, Windows/JDK 17/Android SDK 36, starting HEAD `d1afa480f90c30460347b19888fc5a1cd4335302`.
- Result: expected RED observed before each production seam; all focused tests GREEN after implementation and review corrections.
- Important output or artifact path: `PlayablePadSelectionTest`, `SourceReplacementTest`, `BeatLaneAccessibilityTest`, and `ScratchControlTest` in the tracked host-test suite.

- Command: `C:\Program Files\Git\bin\bash.exe scripts/validate_project.sh` and `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain --max-workers=1 --no-watch-fs`.
- Date/environment: 2026-08-15, Windows/JDK 17/Android SDK 36, Milestone 7 reviewed working tree.
- Result: PASS / BUILD SUCCESSFUL in 1m46s; 213 tests in 44 suites with zero failures/errors/skips; Android Lint 12 warnings and no errors; both APK variants assembled.
- Important output or artifact path: `app/build/outputs/apk/debug/app-debug.apk` (31,707,538 bytes, SHA-256 `5D9EC3A4F86CD0EE36B636E8E9A7C182AE7B64A18A98E6B82F86E02A647C8AAB`) and `app/build/outputs/apk/release/app-release-unsigned.apk` (23,603,385 bytes, SHA-256 `E862905F40A5D934FDB8A0E76302347C488EAB4B6334056E3D223C184F3E70F8`).

## Risks and rollback

- Risk: missing a direct engine start path leaves an ungated playback command. Mitigation: static search all `SamplerPlaybackEngine` audible methods after integration and classify each site.
- Risk: lifecycle handling stops system capture when the user switches apps. Mitigation: explicit policy tests and `SOURCE_SYSTEM_AUDIO` exemption.
- Risk: activity recreation accidentally creates two ViewModels/adapters. Mitigation: activity-scoped `viewModels()` owner and idempotent adapter close.
- Risk: focus denial leaves UI in a playing state. Mitigation: gate before engine/UI mutation and test denied-start behavior.
- Risk: interruption races recorder completion. Mitigation: reuse `stopActiveRecording()` and existing `STARTING/RECORDING/STOPPING` ownership instead of directly clearing recording state.
- Rollback: revert this feature commit; no persistence schema, project archive, audio asset, or user data migration is involved.

## Remaining device validation

- [x] Pixel 9a serial `5A121JEBF08094`: package/version/certificate and artifact SHA preflight, followed by `adb install -r` without uninstall or data clear.
- [x] Existing project archives retained exact path, bytes, and SHA-256 before install, after install, and after launch; four verified binary backups were also exported.
- [ ] Confirm source, pad, loop, scratch, and transport stop on Home/background and do not auto-resume.
- [ ] Confirm rotating the device does not stop playback solely because of configuration change.
- [ ] Confirm wired/Bluetooth route loss stops playback without speaker blast.
- [ ] Confirm transient/permanent audio focus loss from another media app or call stops playback once.
- [ ] Confirm system-audio recording continues while switching to the source app, while mic and vocal recording stop safely on interruption.
- [ ] Confirm no doubled playback after returning to ChopLab and deliberately restarting.
- [ ] Confirm empty BANK B selects immediately and all built-in drum-kit choices remain reachable.
- [ ] Confirm replacing a source silences old source/PAD audio throughout loading and does not revive old A01 assignments after success.
- [ ] Confirm selecting a Beat PAD then pressing Loop produces one audible loop with no preview overlap.
- [ ] Confirm continuous source/PAD scratch remains active through normal finger event spacing and stops promptly on release.
