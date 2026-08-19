# Restart local contract: playback interruption safety — 2026-08-19

## Status and scope

- Status: completed local revalidation PASS; no implementation diff was made. The next implementation seam remains unselected.
- Owner: root integrator in the canonical ChopLab checkout.
- Target gate: `LOCAL_PASS` only.
- Source snapshot at selection: branch `agent/gpt-pro-ui-integration`, HEAD `6033d85b68c9b67f767a31b8878dbe4f4be3392c`, tree `39f8aa19e77b56acbd21c5bdde0f2aa911e6366f`.
- The checkout is operationally dirty: preserve the existing tracked documentation edits and untracked evidence. Do not reset, clean, delete, stage, force-checkout, or commit as part of this revalidation.
- Device, ADB, Pixel 9a, provider, public, browser, VM, and Human checks are explicitly out of scope.

## Why this seam

The local safety contract already has a small policy owner and focused tests, while the historical plan at `plans/active/playback-interruption-safety.md` is bound to older commits and must not be resumed as current without revalidation. This seam can falsify the highest-risk local behavior without claiming physical audio quality: silence before focus release, idempotent interruption handling, no automatic resume on focus gain, and recording-kind-specific background behavior.

## Owned files and interfaces

Inspect only these files first:

- `app/src/main/java/com/choplab/sampler/audio/PlaybackInterruptionCoordinator.kt`
- `app/src/main/java/com/choplab/sampler/audio/AndroidPlaybackFocusAdapter.kt`
- `app/src/main/java/com/choplab/sampler/SamplerViewModel.kt`
- `app/src/main/java/com/choplab/sampler/MainActivity.kt`
- `app/src/main/java/com/choplab/sampler/model/RecordingSession.kt`
- `app/src/test/java/com/choplab/sampler/audio/PlaybackInterruptionCoordinatorTest.kt`
- `app/src/test/java/com/choplab/sampler/audio/AndroidPlaybackFocusPolicyTest.kt`
- `app/src/test/java/com/choplab/sampler/model/RecordingSessionPolicyTest.kt`

The revalidation does not change the public interface or implementation. If a failure requires a code change, stop, record the failing assertion and proposed diff scope, and wait for a separate implementation step.

## Falsifiable check

Run from the canonical root, with the existing workspace state preserved. The first attempt without process-local toolchain variables stopped at the environment precondition `SDK location not found`; it did not run tests or change source. The accepted rerun used the existing portable tools without writing `local.properties`:

```powershell
$env:ANDROID_HOME='F:\\CodexData\\ChopLab\\tools\\android-sdk'; $env:ANDROID_SDK_ROOT='F:\\CodexData\\ChopLab\\tools\\android-sdk'; $env:JAVA_HOME='F:\\CodexData\\ChopLab\\tools\\jdk17\\jdk-17.0.20+8'; .\gradlew.bat :app:testDebugUnitTest --tests 'com.choplab.sampler.audio.PlaybackInterruptionCoordinatorTest' --tests 'com.choplab.sampler.audio.AndroidPlaybackFocusPolicyTest' --tests 'com.choplab.sampler.model.RecordingSessionPolicyTest' --no-daemon --max-workers=1 --no-watch-fs
```

Pass requires `BUILD SUCCESSFUL`, zero failures/errors in all three selected classes, and source inspection confirming:

1. every non-gain focus loss and noisy-output event reaches one interruption path;
2. playback is silenced before focus is abandoned, and repeated interruption/close is idempotent;
3. focus gain never automatically resumes playback;
4. system-audio capture may continue in the background, while microphone/vocal sessions request at most one safe stop and a `STOPPING` session is not stopped again.

## Observed result

- Observed at: `2026-08-19T02:13:31.3948208+09:00`.
- The portable-tool rerun returned `BUILD SUCCESSFUL` in 16 seconds: 3 selected suites, 23 tests total (`14 + 1 + 8`), failures 0, errors 0, skipped 0.
- The source inspection confirmed the coordinator's `stopAllPlayback -> abandonPlaybackFocus` order, non-gain focus/noisy-output routing, no resume on `AUDIOFOCUS_GAIN`, activity-stop delegation, and `STOPPING`/system-audio recording exceptions in the current files listed above.
- This proves the local policy seam only. No physical route, audio quality, long-run, microphone-content, TalkBack speech, provider, public, or Human gate is promoted.

## Negative path, rollback, and handoff

- Stop immediately if the focused tests fail, the source is not the stated HEAD-bound implementation, the work requires a physical route/audio observation, or any external authority is needed.
- Rollback is documentation-only for this selection: leave source code untouched and retain the existing dirty boundary. If a later implementation is authorized, revert only that later bounded diff; never use reset/clean to recover.
- Record the command output, exact observation time, source revision, and any remaining device/Human gap in `docs/PROJECT_STATE.md` and the PAD ledger. Do not promote beyond `LOCAL_PASS`.
