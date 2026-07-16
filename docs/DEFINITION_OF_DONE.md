# Definition of done

## Repository and build

- Clean checkout can be configured using documented prerequisites.
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes.
- Native targets build for every configured ABI.
- No secrets, `local.properties`, signing keys, generated APKs, or machine-specific SDK paths are committed.

## Core workflow

A user can, on a supported physical device:

1. import or legally capture audio;
2. choose a long source range;
3. create and adjust chops;
4. assign slices with auto-next;
5. play/edit pads;
6. create patterns and a Song arrangement;
7. save, close, reopen and recover the project;
8. Undo/Redo expected editing operations;
9. use supported MIDI input and transport;
10. export a stereo master and selected stems.

## Audio correctness

- Stereo channel identity is preserved.
- Pitch and time-stretch controls are functionally independent within documented quality limits.
- ADSR, LFO, inserts, sends, choke and transport are deterministic.
- Real-time callback passes the project real-time safety checklist.
- Real-time and offline rendering are compared with tolerances documented in tests.
- Voice stealing, queue overflow, stream restart, silence, clipping and NaN/Inf behavior are tested.

## Persistence and safety

- Project schema is versioned.
- Save/load round trips preserve project state.
- Atomic save or recoverable temporary-save behavior is implemented.
- Corrupt, oversized and malicious ZIP/project inputs fail safely.
- Autosave failure does not destroy the last valid project.

## Android lifecycle

- Runtime permissions and denied states are handled.
- MediaProjection/foreground-service behavior is correct for supported Android versions.
- Recording and audio resources stop on lifecycle teardown and explicit user action.
- MIDI devices can disconnect/reconnect without leaking resources or crashing.

## Verification evidence

The final report lists:

- exact commands run;
- tests and results;
- APK path and checksum when produced;
- physical device model, Android version and ABI for device tests;
- measured latency/xRun method and results if claimed;
- remaining limitations and unverified items.

A feature matrix must mark only observed implementation and verification status, not design intent.
