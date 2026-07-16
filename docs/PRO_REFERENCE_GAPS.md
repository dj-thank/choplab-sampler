# Pro reference gaps

The files in `reference/pro-v0.2/` are partial artifacts. Codex must reconstruct interfaces and verify behavior instead of assuming missing files existed.

## Native audio gaps

Available:

- `SamplerCore.cpp`
- `NativeBridge.cpp`

Missing or not supplied:

- `SamplerCore.h`
- `CMakeLists.txt`
- Gradle `externalNativeBuild` configuration
- Oboe dependency declaration and packaging
- `NativeSamplerEngine.kt`, although JNI symbols target that class
- Kotlin/JNI model mapping and lifecycle owner
- native unit/host test harness
- ABI configuration
- ProGuard/R8 keep rules for JNI if required
- stream restart/disconnect policy tests

JNI methods in the reference expect a `com.choplab.sampler.audio.NativeSamplerEngine` class with create/destroy, sample load, pad configuration, trigger/release, sequence, tempo, transport, master and diagnostics methods.

## Kotlin model gaps

The supplied Pro Kotlin files import models that are absent from the delivered Pro tree, including:

- `AdsrEnvelope`
- `LfoSettings`, `LfoSyncDivision`, `LfoTarget`, `LfoWaveform`
- `MasterEffects`
- `MidiCcMapping`, `MidiCcTarget`, `MidiDeviceModel`
- `PadEffects`, `PadModel`, `PadPlayMode`
- `PatternModel`, `SongSection`
- `PcmAudio`, `PlaybackMode`, `ProjectSnapshot`, `SamplerConfig`

The MVP has models with similar names but a different mono/MVP shape. A deliberate migration is required.

## Persistence/export gaps

`ProjectArchive.kt` depends on a missing `WavCodec` and a missing complete versioned project model.

`OfflineRenderer.kt` depends on missing:

- `SequenceCompiler`
- `SequenceTimeline`
- stereo-capable models
- integration with document creation/share UI
- cancellation/progress and long-render resource policies

Required tests are not supplied for schema migration, ZIP traversal, decompression limits, duplicate asset IDs, truncated files, round trips, deterministic output, or stems.

## Application integration gaps

Not supplied in the Pro tree:

- updated `SamplerViewModel`
- updated Compose screens and controls
- project open/save document contracts
- autosave coordinator
- Undo manager
- MIDI settings UI and permission/device lifecycle integration
- Song mode UI
- effects UI
- stereo waveform rendering
- app startup/load/recovery path
- updated manifest/resources/tests

## Correct integration strategy

1. Keep the MVP compiling.
2. Derive and test the new domain model.
3. Add native build plumbing with the smallest possible engine proof.
4. Add a Kotlin engine abstraction so AudioTrack and Oboe can coexist temporarily.
5. Migrate playback, then stereo import, then offline rendering.
6. Add persistence and Undo around stable snapshots.
7. Add modulation/effects, MIDI, Song and stems as independently testable slices.
8. Remove the legacy engine only after parity and regression tests pass.
