# Startup responsiveness and microphone waveform visibility

## Purpose and user-visible outcome

Respond to the 2026-09-10 request to shorten startup and make quiet microphone waveforms readable without changing the recorded sound.

## Current state

Baseline: main at bed7a550a71b1ae91556b2b2af25d7c482083c98. SamplerViewModel.init calls engine.start() synchronously; AudioTrack creation/play occurs in that call. Autosave recovery already reads disk on Dispatchers.IO. WaveformEditor renders PCM amplitude at unity display gain, averages stereo channels before taking extrema, and uses bounded decimation for drawing.

## Constraints and invariants

- Keep original PCM, playback gain, project schema, exports, autosave recovery and bank contents unchanged.
- Keep audio callbacks allocation-free and free of file/UI work.
- Preserve startup loading admission: controls must not become ready before audio initialization and recovery complete.
- Cancelled startup must not leave an audio output alive after ViewModel teardown.
- Waveform display gain is runtime UI state only, bounded, labelled, stable while panning and distinct from audio normalization.
- Work only on this branch; no automatic merge, release, signing or device-install claim.

## Architecture and interfaces

Move Android audio warmup off the UI thread without replacing the existing engine admission contract. Keep recovery completion on the existing owner thread. Extract host-testable waveform amplitude policy from Compose drawing; analyze immutable PCM on a worker and apply gain only when drawing. Preserve channel extrema rather than averaging opposing channels.

## Milestones

1. Implement and test quiet/silent/loud/stereo waveform policy; retain the bounded viewport envelope contract.
2. Integrate display-only gain and an explicit UI label into the waveform editor.
3. Move startup AudioTrack preparation off the UI thread; cover cancellation and ordering.
4. Review exact diff and run available tests; report full-build and physical-device gates separately.

## Progress

- [x] 2026-09-10 — inspected repository guidance, startup path and waveform extraction/drawing.
- [ ] Pure logic implementation and tests.
- [ ] UI and startup integration.
- [ ] Validation and PR read-back.

## Discoveries

The execution container has Java/Kotlin but no Android SDK or Gradle checkout/cache. Direct GitHub clone/download failed because network name resolution is unavailable; connected GitHub reads/writes work. No existing CI result is treated as validation of this patch.

## Decision log

- 2026-09-10 — do not enable recorder AGC or bake normalization into saved audio merely to enlarge the waveform.
- 2026-09-10 — preserve the existing recovery gate rather than displaying an editable blank project during recovery.

## Validation log

No build, app test or physical-device timing run yet. Results will be recorded after implementation.

## Risks and rollback

Review cancellation during AudioTrack opening and source replacement during async waveform analysis. Revert this branch's commits to restore baseline; no project migration is required.

## Remaining device validation

Measure cold-start TTID and time-to-ready before/after on the same device/build configuration. Test new project, autosave recovery, immediate close/reopen and first PAD playback. Record quiet and loud speech and verify that only the displayed amplitude changes, not saved/replayed PCM.
