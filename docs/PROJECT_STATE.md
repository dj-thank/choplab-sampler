# Project state

Last prepared: 2026-08-09

## MVP project persistence and edit recovery — 2026-08-10

Version `0.4.0` (`versionCode=5`) adds a bounded persistence slice to the existing mono AudioTrack MVP without changing the original HTML live-chop flow:

- manual `.choplab` save/open from `完成`, with current source, shared PCM16 WAV assets, slice ranges, all 64 PAD assignments and parameters, sequence, BPM/Swing and source KEY;
- schema 2 writes standard WAV entries while the reader migrates schema 1 raw-PCM archives and rejects unknown newer schemas with an update message;
- app-owned autosave after 900 ms of edit inactivity, written through a synced temporary file and two recoverable generations; a valid pending generation can also recover an interrupted replacement;
- at most 40 Undo/Redo entries for slice, PAD, sequence and timing edits, with repeated slider updates coalesced into one operation;
- fail-closed archive checks for schema, normalized entry names, path traversal, duplicate audio IDs/entries, manifest size, total PCM size, unknown entries, malformed/truncated WAV, invalid ranges and invalid references;
- transient playback, recording and loading states are never restored as active.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 37 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,357,226 bytes, SHA-256 `B0E9DF2E9D50E2AD3CBE1DF4C9CF8AA1F6C3296DA181BB57F25535024B205D0A`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- APK installed as `versionCode=5`, `versionName=0.4.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` was top-resumed and no focused fatal exception was found;
- the final APK opened the previous schema 1 autosave at 92 BPM, then imported `choplab-ui-smoke.wav` and wrote a schema 2 autosave whose archive contained `audio/0.wav` with `RIFF/WAVE` headers;
- the expanded `完成` action area showed SAVE PROJECT, OPEN PROJECT, UNDO and REDO without clipping or scrolling;
- after the final APK change, a source-backed 92 BPM schema 2 project was manually saved through DocumentsUI, BPM was changed to 93, and opening the saved project restored 92 BPM with no focused fatal exception;
- saved an empty 92 BPM project through Android DocumentsUI, changed BPM to 93, restored 92 with Undo, restored 93 with Redo, then reopened the saved project and observed 92 BPM;
- changed the reopened project to 94 BPM, waited for autosave, force-stopped/relaunched the app, and observed 94 BPM plus the autosave-restored status;
- intentionally truncated only the emulator app's latest autosave to four bytes, force-stopped/relaunched, and observed recovery from the previous 92 BPM generation with no fatal exception;
- the temporary DocumentsUI `.choplab` and WAV test files were removed from emulator Downloads after verification.

This establishes `LOCAL_PASS` and focused `EMULATOR_PASS` for the MVP persistence slice. It does not establish physical `DEVICE_PASS`, process-death durability under real storage pressure, large-audio performance, a stable production signing/update path, or `HUMAN_GO`. Public CI and Release evidence for version 0.4.0 is pending.

## Guided five-stage sampler workflow — 2026-08-10

The Android application now presents the fixed `入れる / 切る / 叩く / 並べる / 完成` journey while preserving the original HTML workflow inside `叩く`: load or record audio, play the source, and press a PAD at the desired instant to create a live chop. `切る` retains the precision waveform tools, `並べる` retains the 16-step sequencer, and `完成` accurately exposes the implemented four-bar mono WAV export.

Version `0.3.0` (`versionCode=4`) adds:

- a Japanese-first five-stage rail with short English production captions;
- beginner guidance for source sampling and the starter `1・5・9・13` step pattern;
- selected-PAD KEY note names and semitone controls, plus direct TONE/LEVEL sliders on regular portrait layouts;
- the existing reverse, one-shot/gate, choke and PAD clear controls under `叩く` → `詳細`;
- a finish summary with assigned PAD count, audible step count, BPM, beat preview, confirmed pattern clear and four-bar export;
- safe restoration of legacy `CHOP / PAD / SEQ / SOURCE` saved mode names without `valueOf` crashes.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 23 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,616,083 bytes, SHA-256 `718814700DF1929D53CC90B2B0A10A7230E677C598E226080114ACC8D87348D2`.

Emulator evidence on a headless Pixel 9 AVD, Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- final APK installed as `versionCode=4`, `versionName=0.3.0`, `minSdk=29`, `targetSdk=36`;
- `MainActivity` reached `topResumedActivity`; the focused post-launch log query found no fatal exception;
- microphone permission and a three-second emulator recording were used to verify capture → live source playback → PAD 01/02 assignment;
- PAD A-02 KEY changed from C3 to C#3, TONE to 32%, and LEVEL to 75%; steps 1/5/9/13 changed to `オン`;
- `完成` showed two assigned PADs, four audible steps, 92 BPM, enabled beat preview and enabled four-bar WAV export;
- post-review detail screens showed Japanese-first PARAM/PLAY, PITCH/TONE/LEVEL, reverse, one-shot/gate, choke and confirmed PAD-clear labels without clipping; `完成` now includes transport state;
- visual comparison against both selected generated targets completed after adding the direct sliders and arrange TIP; `design-qa.md` records `final result: passed`.

Public evidence for the exact merged UI commit `a882ec633d6b9ad849a8c900171fbbd1006f29d1`:

- public PR `#2`: `https://github.com/dj-thank/choplab-sampler/pull/2`, merged after the PR verification run `31357128321` passed;
- main Android verification run `31357298769`: PASS;
- tag Android verification run `31357435542`: PASS;
- tag: `v0.3.0-preview.1`;
- release workflow run `31357435588`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.3.0-preview.1`;
- public APK: `ChopLab-v0.3.0-preview.1-debug.apk`, 30,116,752 bytes;
- public APK SHA-256: `E5C79BF01F62C5445E23798CF0603B46305E37BC932F3B9AE94C580E3E4E7219`;
- downloaded release APK and published `.sha256` file matched byte-for-byte by digest.

This establishes `PUBLIC_PASS` for the public repository, CI, Release publication, downloadable APK identity, and the focused `EMULATOR_PASS` above. No physical phone is connected, so it does not establish `DEVICE_PASS`, physical touch comfort, haptic quality, microphone fidelity, latency, or `HUMAN_GO`.

## Fixed no-scroll production console — 2026-08-10

The application source now uses a fixed `CHOP / PAD / SEQ / SOURCE` console instead of the former vertically scrolling card stack. The four workspaces preserve the existing sampler engine and expose live chop, 4 BANK × 16 PAD performance, per-PAD editing, 16-step sequencing, capture/import, slicing, assignment, and WAV export without top-level vertical or horizontal scrolling.

Local evidence for version `0.2.0` (`versionCode=3`):

- `DeckLayoutPolicyTest`: four portrait/landscape and compact/regular policy tests pass;
- Gradle `testDebugUnitTest`: 18 tests, zero failures/errors;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: no `verticalScroll`, `horizontalScroll`, or `rememberScrollState` usage;
- local debug APK: `app/build/outputs/apk/debug/app-debug.apk`;
- local debug APK SHA-256 after compact-landscape and accessibility hardening: `CDB02CFFA5F693F2550F41260558D04E259F31AC917E998ED16CDE12D07E8ABD` (30,433,927 bytes).

The current-run Android SDK Platform 36 and Build Tools 36.0.0 were installed locally after accepting their SDK licenses, allowing a real local Android compile and APK build rather than source-only validation.

No phone is connected for this milestone. Local validation establishes `LOCAL_PASS`, but does not claim `DEVICE_PASS`, screenshot parity, touch comfort, clipping-free rendering, audio E2E, or `HUMAN_GO`. Previous Pixel 9a evidence below applies only to the older `v0.1.1-preview.1` artifact.

Public evidence for the exact UI commit `e0896adf8ff96439556d551d1cae4b9d1927f868`:

- main Android verification run `31352372588`: PASS;
- tag Android verification run `31352511018`: PASS;
- tag: `v0.2.0-preview.1`;
- release workflow run `31352511062`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.2.0-preview.1`;
- public APK: `ChopLab-v0.2.0-preview.1-debug.apk`, 30,034,832 bytes;
- public APK SHA-256: `B1CC4F6B014F507F3F928AF10CA0EB25E41EA58E50CCEC771CDE43A9A0F62C26`.

This establishes `PUBLIC_PASS` for repository visibility, CI build, and downloadable artifact identity. It still does not establish `DEVICE_PASS` for the new fixed console because no phone was available.

## Canonical 「おとひろい」 UI — 2026-08-10

The user-supplied 505-line HTML prototype is now treated as the canonical top-screen specification. Source changes add:

- the cream hardware-deck visual system, Japanese `おとひろい` identity, orange sampling lamp, green waveform, 4 × 4 PAD layout, and one-row 16-step sequencer in Compose;
- full-source playback, waveform seek, atomic source playhead reporting, ±12-semitone source pitch-by-rate, and source/beat transport exclusion;
- live chop capture: while the source is playing, pressing a visible PAD assigns the latency-compensated playhead and reflows same-audio PAD end frames within the current bank;
- the existing 4 BANK, advanced waveform selection, microphone/system capture, per-PAD editing, quantized record, Swing, and WAV export under an expandable details section.

Observed locally before Android publication:

- pure Kotlin JUnit: `OK (14 tests)` including two new live-chop boundary tests;
- `scripts/validate_project.sh`: PASS using the preserved portable JDK 17 / Kotlin 2.3.21 environment;
- `git diff --check`: PASS.

Public GitHub Actions run `31321170535` passed offline validation, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and artifact upload for commit `1fc8fc8`. The CI APK SHA-256 is `BDAE4725031940B8452331D61BA689905AE1C947E77C615BAF0586EB7BAD32F5` (29,920,144 bytes).

The exact CI APK was installed on the connected Pixel 9a after a content-free `run-as` inspection found only cache and `files/profileInstalled`; Android rejected in-place update because GitHub runner debug signatures differ, so the old preview was uninstalled first. `MainActivity` launched with no immediate fatal exception. Visual inspection confirmed the canonical deck, header, source controls, waveform, banks, and PAD proportions without visible clipping. A generated 30-second mono 48 kHz sine test then verified `曲を読込` → `曲を再生` → PAD 01 during sampling → stop, with `PAD 01 割り当て済み` and marker `01` at approximately `0:00.8`. The temporary test WAV was removed from the device afterward.

This is a focused preview smoke, not latency measurement or complete microphone/system-capture/export validation. The source pitch slider, waveform seek, lower-page sequencer controls, advanced editor, and lifecycle stress remain unverified on this APK.

Final packaging for this UI milestone:

- version commit: `d273fe9997afa34c23868be0477b57fddcd198ae` (`versionCode=2`, `versionName=0.1.1`);
- final main CI: `31321683089`, all Android verification steps PASS;
- tag: `v0.1.1-preview.1`;
- release workflow: `31321828427`, build/package and publish jobs PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.1-preview.1`;
- public APK: `ChopLab-v0.1.1-preview.1-debug.apk`, 29,920,144 bytes;
- public APK SHA-256: `F4C1C47066771ABF4FD47AB1F72C06A442A30FA7B6EE13B1ADC6777C416EFB6A`.

The exact public Release APK was installed on the Pixel 9a after a second content-free data check again found only cache/profile files. Package inspection reports `versionCode=2`, `versionName=0.1.1`, `minSdk=29`, and `targetSdk=36`; `MainActivity` is top-resumed and the immediate logcat query found no fatal exception.

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
- stereo-aware project migration beyond the current mono MVP archive and real-device lifecycle durability;
- deeper history policies beyond the current bounded MVP Undo/Redo;
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
