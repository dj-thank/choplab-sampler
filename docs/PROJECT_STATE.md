# Project state

Last prepared: 2026-08-09

## Public preview packaging — 2026-08-09

The repository is now prepared for public preview publication at:

- `https://github.com/dj-thank/choplab-sampler`
- public-release branch: `agent/public-choplab-release`
- release workflow: `.github/workflows/release.yml`

The successful GitHub Actions run `31319111062` verified the public branch with:

- `scripts/validate_project.sh`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `:app:lintDebug`: PASS with warnings but no errors.
- `:app:assembleDebug`: PASS.
- debug APK artifact: `choplab-debug-and-reports`.
- downloaded APK SHA-256: `07A53C695D7A229816E0FC0F53C4B5C9F270C705228DE7320008B4074785FE67`.
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.0-preview.1`.
- release workflow run `31319529630`: `build-and-package` and `publish-release` PASS.
- public Release APK: `ChopLab-v0.1.0-preview.1-debug.apk`.
- public Release APK SHA-256: `4E6220484F5991B34792CBCFCC5B251460893D9433DD2BE06A6B4635BCBEA513`.

The CI artifact was first installed onto the connected physical Pixel 9a. The public Release APK uses a different debug signing key, so Android correctly rejected an in-place update; a read-only app-data check found only cache/compiled-profile files and no project files. The old preview was then uninstalled and the public Release APK was installed successfully. Package `com.choplab.sampler` reports version `0.1.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` launched and the immediate logcat check found no fatal exception. This is launch/install evidence only, not a complete audio workflow or latency result.

## Target-machine verification — 2026-08-09

Observed on Windows 11 with workspace-local Temurin JDK 17, Kotlin 2.3.21, Gradle 9.5.0, Android command-line tools 22.0, and adb 37.0.1:

- `scripts/validate_project.sh` passed after the domain changes.
- Six pure Kotlin JUnit test classes passed: `OK (12 tests)`.
- The Gradle wrapper starts successfully on JDK 17.
- The local Windows shell does not have `local.properties` or Platform 36 / Build Tools 36.0.0 installed, so the Android Gradle tasks were not run locally. The public GitHub Actions run listed above supplied the Android SDK and passed the Android tests, Lint, and debug APK build.
- adb sees a physical Pixel 9a as `device` on Android 16 / API 36 / `arm64-v8a`.
- `com.choplab.sampler` is installed from the successful CI debug artifact and launched once without an immediate fatal exception.
- Portable JDK/SDK/Kotlin/Gradle storage and Gradle/build/test output paths now resolve through verified NTFS junctions to `F:`. The offline validation and adb device check passed again after relocation.

Source-only foundations added in this checkpoint:

- Immutable, stereo-capable `PcmBuffer` validation and bounded versioned project models, including metadata and pattern-event limits.
- Legacy MVP state-to-project adapter.
- Pure pad-range assignment command shared by the ViewModel and host tests.
- Playback and pattern-render service interfaces for incremental legacy/native coexistence.

These foundations are host-tested but are not yet a user-visible Pro implementation. The public CI debug build and initial device launch are verified; complete device audio workflow, permissions, lifecycle, and latency tests remain open.

## Buildable baseline

The active Gradle project is the MVP under the repository root.

Implemented in the baseline:

- Android 10 / API 29 minimum.
- Audio import through SAF and MediaCodec.
- Microphone recording.
- Android Playback Capture for sources that allow capture.
- Mono PCM internal representation.
- Waveform range selection, zoom, scroll, manual/equal/transient chopping, zero-crossing snap.
- 4 banks × 16 pads.
- Auto-next pad/slice assignment.
- AudioTrack-based low-latency playback.
- Per-pad pitch-by-rate, tone, gain, reverse, one-shot/gate, choke.
- 16-step sequencing, BPM, swing, quantized recording.
- Four-bar mono WAV export.

The offline project validation script passed when this workspace was prepared:

```text
PASS: project-level offline validation completed
```

A complete Android SDK/NDK build was not run locally. The public Android debug build is verified in GitHub Actions; native NDK/Oboe targets remain unimplemented and therefore are not claimed.

## Intended Pro target

The requested target adds:

- Oboe/AAudio native audio engine.
- Project save/load and autosave.
- Undo/Redo.
- Stereo import, playback, processing, and export.
- Pitch-independent time stretch.
- ADSR.
- LFO.
- Pad and master effects.
- USB/Bluetooth/virtual MIDI, velocity, CC learn, clock and transport.
- Multiple patterns and Song mode.
- Master and stem export.

## Reference material

`reference/pro-v0.2/` contains partial source and design documents for the Pro target. It includes:

- `SamplerCore.cpp`
- `NativeBridge.cpp`
- `OfflineRenderer.kt`
- `ProjectArchive.kt`
- `MidiController.kt`
- architecture and feature documents

These artifacts are not wired into the root Gradle project and have missing dependencies. They are design input, not a completed implementation.

## Immediate next milestone

1. Keep the public preview CI and tag-release path green.
2. Continue `plans/active/choplab-pro-integration.md` as the native/pro migration plan.
3. Define a versioned stereo-capable domain model and pure tests.
4. Add NDK/CMake/Oboe infrastructure and a minimal native tone/single-sample proof before replacing the existing engine.
5. Migrate one vertical feature at a time while keeping the application buildable.

## Evidence policy

Update this file only with observed facts. Record exact commands, dates, device/API levels, ABI, and output paths. Separate:

- source implemented;
- host/unit tested;
- Gradle built;
- emulator tested;
- physical-device tested;
- latency measured.

## Workspace preparation checks

See `docs/PREPARATION_VALIDATION.md`. Offline validation and configuration syntax checks passed locally; the public GitHub Actions run established the current Android debug build status.
