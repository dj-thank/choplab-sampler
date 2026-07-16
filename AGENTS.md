# ChopLab repository guidance

## Mission

Build a reliable Android 10+ mobile sampler that supports capture/import, waveform range selection, professional chopping, pad assignment, beat sequencing, native low-latency playback, project persistence, stereo processing, independent pitch/time stretch, modulation, effects, MIDI, Song mode, and master/stem export.

## Repository truth

- `app/` is the only current build target. It is the AudioTrack-based MVP baseline.
- `reference/pro-v0.2/` contains incomplete design artifacts and partial source. It is not a verified or buildable implementation.
- Never claim a Pro feature exists merely because it appears in a reference README or partial source file.
- Preserve `reference/pro-v0.2/` as historical input. Implement production code under `app/`.

## First actions

1. Read `docs/PROJECT_STATE.md`, `docs/PRO_REFERENCE_GAPS.md`, and `docs/DEFINITION_OF_DONE.md`.
2. Run `./scripts/doctor.sh` and `./scripts/validate_project.sh`.
3. For a complex feature or significant refactor, create and maintain an ExecPlan under `plans/active/` according to `.agent/PLANS.md`.
4. Establish a clean Git checkpoint before invasive work.

## Build and validation

Use the smallest relevant checks during iteration, then run the full gate before declaring completion.

```bash
./scripts/validate_project.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

When the NDK path is introduced, also build all configured ABIs and run native host tests where practical. Never report success without command output or equivalent evidence.

## Engineering constraints

- minSdk remains 29 unless an explicit product decision changes it.
- Keep Kotlin/Compose UI, project state, real-time DSP, offline rendering, and persistence separated.
- Real-time audio callback: no blocking locks, file I/O, logging loops, heap allocation, Android UI calls, or heavy JNI work.
- Cross-thread control data must use bounded queues, immutable snapshots, atomics, or another demonstrably real-time-safe mechanism.
- Reuse one DSP core for real-time and offline rendering where feasible so exported audio matches playback.
- Audio frame ranges use start-inclusive/end-exclusive semantics.
- Preserve stereo channel order and document every mono/stereo conversion.
- Bound all imported/project data to prevent memory exhaustion and ZIP traversal/decompression abuse.
- Destructive sample edits require Undo/Redo coverage or an explicit irreversible confirmation path.
- Do not introduce DRM circumvention, capture-policy bypasses, or misleading compatibility claims.
- Do not reproduce AKAI/MPC logos, proprietary assets, firmware, project formats, or distinctive trade dress. Recreate workflow concepts through an original interface.

## Change discipline

- Integrate the Pro design in small compiling milestones; do not paste all reference files into `app/` at once.
- Prefer tests before or alongside behavior changes.
- Keep unrelated formatting and dependency changes out of focused commits.
- Update `docs/PROJECT_STATE.md`, feature matrix, and active ExecPlan after every milestone.
- Commit checkpoints by milestone when Git is available. Do not rewrite user history or force-push.

## Subagents

Use subagents for independent read-heavy work such as code mapping, API verification, DSP review, and test-gap analysis. Serialize write-heavy work that touches shared Gradle files, shared models, JNI boundaries, or the same Kotlin/C++ modules. The main agent owns integration and final validation.

Suggested roles are defined in `.codex/agents/`:

- `android_architect`
- `audio_dsp_engineer`
- `build_engineer`
- `qa_reviewer`

## Documentation sources

For version-sensitive Android, Kotlin, Gradle, NDK, Oboe, and MIDI behavior, verify against primary sources before changing architecture or dependency versions. Record the source and the exact version assumption in the ExecPlan or relevant docs.

## Definition of done

A feature is done only when:

- the user-visible workflow is wired through UI, state, engine, persistence/export as applicable;
- unit or host tests cover the core logic;
- relevant Gradle tasks pass;
- errors and lifecycle transitions are handled;
- docs and feature matrix reflect actual—not intended—behavior;
- remaining device-only verification is explicitly listed.
