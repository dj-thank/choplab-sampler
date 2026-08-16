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
.\gradlew.bat clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
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

Use the dedicated normal API 36 Google Play AVD for repeatable framework-node regression. Do not reuse another project's AVD. Accessibility Test Devices are intentionally not used for TalkBack because their reduced system image omits or disables Settings/SystemUI components. AVD results remain separate from physical Pixel and HUMAN_GO evidence.

Primary references are recorded in `work/ANDROID_ACCESSIBILITY_AUTOMATION_RESEARCH_2026-08-16.md`.
