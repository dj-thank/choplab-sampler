# ChopLab Pro complete integration task

You are the lead engineer responsible for turning this repository into a genuinely buildable, testable Android sampler. Work autonomously and continue through the next feasible milestone instead of stopping after analysis. Do not ask for routine confirmations. Make conservative, documented decisions when requirements are underspecified.

## Start here

1. Read `AGENTS.md`, `.agent/PLANS.md`, `docs/PROJECT_STATE.md`, `docs/PRO_REFERENCE_GAPS.md`, and `docs/DEFINITION_OF_DONE.md` in full.
2. Inspect the root MVP and `reference/pro-v0.2/` source. Treat the Pro reference as incomplete and unverified.
3. Run `./scripts/doctor.sh`, `./scripts/validate_project.sh`, and the smallest available Gradle build/test command.
4. Create `plans/active/choplab-pro-integration.md` as a living ExecPlan. Record the true baseline, exact errors, decisions, progress and validation evidence.
5. Establish a Git checkpoint before invasive changes.

## Delegation

Use independent subagents where useful:

- `android_architect` to map current state, lifecycle, UI/state and migration boundaries.
- `audio_dsp_engineer` to review the partial C++/JNI design and real-time hazards.
- `build_engineer` to verify AGP/Kotlin/Gradle/NDK/CMake/Oboe compatibility and CI.
- `qa_reviewer` after each major milestone and for final review.

Run read-heavy exploration/review in parallel. Serialize edits to shared Gradle, model, JNI, ViewModel and UI files. The main agent owns integration and final validation.

## Product outcome

Deliver one original Android application, not a branded clone, with this end-to-end workflow:

1. Import an audio file, record a microphone, or capture only Android playback audio that the source permits.
2. Display a stereo-aware waveform and let the user select a long source range.
3. Manually/equally/transient chop, drag boundaries, snap safely, audition, normalize/trim where implemented, and assign slices with auto-next.
4. Play 4 banks × 16 pads with low latency.
5. Edit per-pad pitch, independent time stretch, gain, pan, tone/filter, resonance, reverse, one-shot/gate, choke, ADSR, LFO and effects/send levels.
6. Create multiple 16/32/64-step patterns, record pad hits, set BPM/swing, and arrange patterns in Song mode.
7. Use Android MIDI input for notes/velocity, CC learn, clock and start/continue/stop. Handle disconnects safely.
8. Save/load a versioned `.choplab` project, autosave without destroying the last good state, and Undo/Redo supported edits.
9. Export a stereo master and useful bank/pad stems with progress/cancellation and bounded resource use.

## Required implementation strategy

Do not copy all reference files into `app/` at once. Maintain a compiling baseline and integrate in vertical slices.

### Phase A — baseline truth and domain model

- Make the root MVP build with the documented toolchain or document a minimal, justified version correction using primary sources.
- Add tests that characterize existing slicing, pad assignment, sequencing and export behavior before replacing internals.
- Design a versioned stereo-capable project/domain model with immutable or copy-safe snapshots.
- Define engine interfaces so the legacy AudioTrack engine can coexist temporarily with the native engine.

### Phase B — native Oboe foundation

- Add complete NDK/CMake/Oboe build configuration.
- Reconstruct `SamplerCore.h` and a Kotlin `NativeSamplerEngine` from actual JNI requirements, not guesswork.
- First prove stream creation, teardown, diagnostics and a minimal known sample/tone path.
- Then implement bounded command transport, sample lifetime ownership, pad configuration, voice handling, sequence transport and stream restart.
- Enforce the real-time rules in `AGENTS.md` and add native/host tests.
- Keep an explicit fallback or migration path until parity is proven.

### Phase C — stereo, persistence and history

- Upgrade decoder, source model, waveform summaries, engine and renderer to preserve stereo.
- Implement a versioned project package with safe ZIP paths, counts, sizes, frame limits, duplicate-ID checks, schema migration and atomic saves.
- Implement autosave/recovery and bounded Undo/Redo with coalescing for continuous controls.
- Add round-trip, corruption, oversized input, rollback and history tests.

### Phase D — DSP and production features

- Implement pitch-independent time stretch with documented quality/latency tradeoffs. The supplied granular code may be reused only after review and tests.
- Implement ADSR, LFO targets/waveforms/sync, filter/resonance, inserts, sends and master processing.
- Prefer shared DSP primitives or equivalence tests between real-time and offline paths.
- Prevent NaN/Inf, unstable feedback, uncontrolled gain, denormal and unbounded work.

### Phase E — MIDI, patterns, Song and stems

- Implement multiple patterns, variable lengths and a deterministic Song timeline.
- Implement Android MIDI device discovery/open/close, running status, velocity, CC learn, clock and transport.
- Implement stereo master and bank/pad stem export with clear wet/dry/master-FX policy documented and tested.

### Phase F — UI, lifecycle and release

- Integrate all controls into a coherent original Compose UI suitable for a phone.
- Preserve the fast Chop → Assign → Auto Next → Pad → Pattern workflow.
- Handle permissions, denied states, MediaProjection foreground service requirements, rotation/process recreation where practical, MIDI disconnects, audio focus and app teardown.
- Add accessibility labels, sensible touch targets and clear error/progress states.
- Update README, architecture, feature matrix and project-state docs to match actual behavior.

## Validation requirements

At each milestone:

1. Run the narrow relevant tests.
2. Run `./scripts/validate_project.sh`.
3. Run relevant Gradle unit/native tasks.
4. Run `:app:lintDebug` and `:app:assembleDebug` before marking the milestone complete.
5. Review the diff for regressions, real-time hazards, data loss and misleading claims.
6. Commit a checkpoint with a descriptive message when the milestone is stable.

Before final completion, run:

```bash
./scripts/verify.sh
```

If a physical Android device is available, install and smoke-test the APK. Record device model, Android version, ABI, audio route, capture source and MIDI device. If no device is available, explicitly list physical-device tests as unverified—never simulate evidence.

## Deliverables

- Buildable source under the root Gradle project.
- Updated active/completed ExecPlan with evidence.
- Tests for pure logic, persistence, rendering, MIDI parsing, native DSP and key ViewModel behavior.
- Updated architecture and truthful feature matrix.
- Debug APK only if actually built, with output path and SHA-256.
- Final report with completed features, commands/results, remaining limitations and device-only validation.

Continue working until the repository reaches the strongest verified state possible in this environment. If a dependency, SDK or device blocks a step, solve all unblocked work, preserve exact failure evidence, and leave the repository in a clean resumable state.
