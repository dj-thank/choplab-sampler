---
name: choplab-android
description: Use for ChopLab Android sampler development, especially Oboe/JNI integration, stereo audio, chopping, project persistence, Undo/Redo, time stretch, ADSR/LFO/FX, MIDI, Song mode, stems, build repair, and release validation. Do not use for unrelated Android apps.
---

# ChopLab Android development workflow

## Establish the truth

1. Read the nearest `AGENTS.md`.
2. Read `docs/PROJECT_STATE.md`, `docs/PRO_REFERENCE_GAPS.md`, and the active ExecPlan.
3. Treat `app/` as the current implementation and `reference/pro-v0.2/` as unverified reference material.
4. Run `./scripts/validate_project.sh` before invasive changes. Run the relevant Gradle task when the Android SDK is available.

## Plan the change

For work spanning more than one layer or more than a small patch, create or update an ExecPlan under `plans/active/` using `.agent/PLANS.md`.

Describe:

- user-visible behavior;
- state/data-model changes;
- Kotlin/Compose changes;
- native/JNI/DSP changes;
- persistence/export compatibility;
- tests and device-only checks;
- rollback or migration strategy.

## Implement in compiling slices

Do not bulk-copy the Pro reference tree. Integrate one compiling vertical slice at a time:

1. models and pure logic;
2. tests;
3. engine or persistence boundary;
4. ViewModel/state wiring;
5. UI;
6. lifecycle/error handling;
7. documentation and feature matrix.

Keep `app/` buildable after each milestone.

## Audio-specific rules

- Use start-inclusive/end-exclusive frames.
- Preserve explicit sample rate, channel count, and channel order.
- Do not allocate, block, perform file I/O, or invoke expensive JNI work in the Oboe callback.
- Use bounded queues or immutable snapshots for control-to-audio communication.
- Cap voice count, queue size, source size, render duration, and project package size.
- Handle stream disconnect/restart and lifecycle teardown.
- Add host-testable DSP units whenever possible.
- Compare real-time and offline output for equivalent settings.

## Persistence-specific rules

- Use a versioned project schema.
- Validate ZIP entry paths, duplicate IDs, entry counts, uncompressed sizes, frame counts, and total PCM memory.
- Write atomically through a temporary file where possible.
- Preserve unknown/newer fields safely or fail with an actionable error.
- Test save-load round trips and corrupt/truncated inputs.

## Validation gate

Run, as applicable:

```bash
./scripts/validate_project.sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Then review the diff with `qa_reviewer` or `/review`. Record real command results in the ExecPlan and `docs/PROJECT_STATE.md`. Clearly label any device-only item as unverified.

## Completion output

Summarize:

- what changed;
- files and architecture affected;
- checks run and their results;
- remaining risks or device tests;
- generated APK/output paths when they actually exist.
