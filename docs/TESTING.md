# Android testing strategy

## Layers and claims

| Layer | Tooling | What it proves | What it does not prove |
|---|---|---|---|
| LOCAL | JUnit and pure Kotlin policies | state machines, viewport math, boundary contracts | Android framework behavior |
| COMPOSE_INSTRUMENTATION | Compose UI test APIs and Accessibility Test Framework | deterministic gestures, semantics, labels, state, 48 dp targets, common accessibility defects | TalkBack speech or physical touch feel |
| FRAMEWORK_NODE | `UiAutomation` / `AccessibilityNodeInfo` and UI Automator 2.4.0 | nodes and actions exposed to an Android accessibility service, framework depth-first tree order, advertised focus actions, custom-action dispatch | actual accessibility-focus movement, TalkBack's traversal policy, or TTS wording |
| DEVICE | physical Pixel evidence | retained-data install, real lifecycle/audio/microphone/service behavior | subjective quality |
| HUMAN_GO | a person using TalkBack and the phone | spoken clarity, one-hand comfort, audio quality | reproducible automated regression coverage |

Never promote a result across these boundaries. In particular, a Compose semantics callback is not a TalkBack action, and an emulator microphone is not physical microphone evidence.

## Commands

Run the complete local gate from the repository root with JDK 17 and the configured Android SDK:

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 --no-watch-fs
```

Run the deterministic waveform suite on one explicitly selected emulator or device. Do not use Gradle's all-connected-device task when a retained-data Pixel is also attached:

```powershell
adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <serial> install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s <serial> shell am instrument -w -r `
  -e class com.choplab.sampler.ui.SourceWaveformDeviceTest `
  com.choplab.sampler.test/androidx.test.runner.AndroidJUnitRunner
```

`SourceWaveformDeviceTest` renders in-memory PCM and does not read or mutate project autosaves. Physical retained-data deployment must continue to use `scripts/collect-device-evidence.ps1`, which performs signer, package, version, APK readback, autosave, and terminal-state checks fail closed.

## Virtual device policy

Use the ChopLab-only configuration in `config/choplab-review-avd.json` for repeatable framework-node regression. Set `CHOPLAB_AVD_HOME` to an isolated directory, then use the repository scripts:

```powershell
$env:CHOPLAB_AVD_HOME = 'F:\CodexData\ChopLab\avd' # local example; never commit a machine path
.\scripts\check-choplab-review-avd.ps1
.\scripts\provision-choplab-review-avd.ps1 -InstallMissingImage
.\scripts\start-choplab-review-avd.ps1
.\scripts\write-build-provenance.ps1 -OutputPath work\build-provenance.json
.\scripts\run-choplab-review-avd-tests.ps1 -Serial emulator-5592 -BuildProvenancePath work\build-provenance.json
```

Provisioning creates only the pinned AVD and refuses to replace an incomplete or mismatched existing AVD. Starting uses the tracked-process registry, 4096 MiB, no snapshots, and disables emulator Bluetooth emulation because the Google Play API 36 image reproducibly produced Bluetooth/startup crashes in the headless review configuration. The runner rejects every non-emulator serial, so it cannot fall through to a connected Pixel. It also requires a tracked-clean HEAD/tree and a matching build-provenance JSON before installation. It runs the complete deterministic waveform suite at portrait font scales 1.0/1.3/2.0 and landscape 1.0. Each run must produce exactly one positive `OK (N tests)` summary and no JUnit failure, instrumentation failure/abort or process-crash marker; zero tests and ambiguous summaries fail closed. The Android CI additionally reads every generated `app/build/outputs/androidTest-results/**/*.xml` (and connected test XML fallback) with `scripts/instrumentation_summary.py --xml`. The machine-readable XML gate requires tests > 0 and failures=errors=skips=0, checks declared counters against actual `<testcase>` results, and fails closed on malformed or missing files. The observed count is recorded in each receipt run, so adding or removing a test cannot make a successful suite fail only because a script constant drifted. The runner also checks fatal/ANR logs, restores and reads back font/rotation settings, and force-stops ChopLab.

Accessibility Test Devices are intentionally not used for TalkBack because their reduced system image omits or disables Settings/SystemUI components. AVD results remain separate from physical Pixel and HUMAN_GO evidence.

Primary references and fixed reference-repository revisions are recorded in `docs/research/android-audio-accessibility-reference-review-2026-08-17.md`.
