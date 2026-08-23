# Target architecture

This document is a starting constraint, not proof that the current code implements it.

## Layers

### UI and navigation

Compose screens render immutable UI state and send intents. They do not own audio buffers, native handles, file streams or MIDI ports.

### Application/state

A `ProductionSession` application module owns editing history, revision and persistence admission. UI/MIDI/capture inputs become shared `ProductionCommand` values; a pure reducer returns classified PROJECT/SESSION mutations and typed `ProductionEffect` values. Platform coordinators expose explicit loading, saving, recording, rendering, runtime-application and error states while executing only the effects supported by their adapters.

### Domain

Versioned project snapshots and pure operations define sources, slices, pads, patterns, Song sections, effects, MIDI mappings and master settings. Audio buffers are shared through stable IDs/references rather than copied into every history state.

### Engine abstraction

A Kotlin interface separates application logic from playback implementation. During migration, legacy AudioTrack and native Oboe implementations may coexist behind this interface. Native ownership and teardown are explicit.

### Native DSP

C++ owns the low-latency output stream, bounded commands, immutable pad/sequence snapshots, voices and real-time DSP. Control-thread updates never invalidate data referenced by the callback.

### Offline rendering

Offline rendering consumes the same project/sequence model and shared DSP primitives where feasible. It runs outside the UI/audio callback, supports cancellation/progress and writes incrementally.

### Persistence

A versioned archive layer converts project metadata to/from a bounded schema and uses a WAV/PCM codec for portable audio assets. Save is atomic/recoverable and independent from Compose.

### MIDI and capture adapters

Android-specific adapters own `MidiDevice`, `AudioRecord`, MediaProjection and foreground-service resources. They expose events to the application layer and close idempotently.

## Thread ownership

- Main thread: Compose, Activity results, UI state publication.
- App/background dispatcher: decoding, project I/O, waveform summary, long pure transforms.
- MIDI handler thread: device callbacks and parsing; forward bounded events.
- Audio control thread: native engine configuration and retired-buffer cleanup.
- Oboe callback: fixed bounded DSP only.
- Offline render worker: deterministic render and file output.

## Key interfaces to establish

- `SamplerPlaybackEngine`
- `ProductionCommand`, `ProductionCommandResult` and `ProductionEffect`
- `ProductionSession`
- `ProjectRepository`
- `ProjectSnapshot` and schema version
- `AudioAssetStore`
- `SequenceCompiler`
- `OfflineRenderService`
- `MidiInputService`
- `UndoManager<T>`

Exact names may change, but responsibilities must remain separate and testable.
