# ChopLab product requirements

## Product principle

ChopLab is an original mobile sampler optimized for a rapid source → chop → pad → pattern → song → export loop. It may learn from hardware-sampler workflows, but it must not copy proprietary visual identity, firmware behavior, assets or project formats.

The core job is not to visit four disconnected feature screens. A person brings in a song or voice, finds memorable fragments, plays them immediately, and turns them into a Beat while the same waveform, Chops, and PAD locations remain understandable. Reopening the app should continue that Production before presenting an empty start.

## Production continuity and first sound

- **FLOW-001** Launch visibly checks the latest valid autosave, restores it atomically, and routes to CHOP or BEAT from recovered content; CAPTURE is shown only for a truly new Production.
- **FLOW-002** CAPTURE exposes both source import and `制作を開く / OPEN PROJECT`, so an existing `.choplab` Production can be loaded before entering the save screen.
- **FLOW-003** CHOP and BEAT keep one Chop surface: selected-Chop waveform, BANK/page selectors, and a fixed four-by-four PAD grid. Detailed step editing may be a secondary view but must not replace that performance surface by default.
- **FLOW-004** A truly new Production contains ChopLab's original starter Drum kit in BANK B. Autosave recovery and manual project opening preserve their exact BANK B content and never receive an implicit replacement.
- **FLOW-005** Scratch starts on direct contact, maps left/right movement to bounded signed speed with a noise dead zone, visibly follows the playhead, becomes silent when motion is idle, and safely resumes the valid Beat loop or transport that owned playback before the gesture.

## Capture and source handling

- **CAP-001** Import audio through Android's document picker without requiring broad storage permission.
- **CAP-002** Record microphone audio with explicit permission and visible state.
- **CAP-003** Capture Android playback only through official Playback Capture and only when the source allows it.
- **CAP-004** Preserve source sample rate/channel metadata and stereo identity.
- **CAP-005** Reject or bound unsupported, malformed and excessive inputs with actionable errors.
- **CAP-006** Never implement DRM/capture-policy bypasses.

## Waveform and chopping

- **CHOP-001** Select a start-inclusive/end-exclusive source range.
- **CHOP-002** Zoom and scroll without rendering every source frame.
- **CHOP-003** Add manual, equal and transient-derived boundaries.
- **CHOP-004** Drag/delete boundaries and snap to a safe nearby zero crossing when enabled.
- **CHOP-005** Audition the active slice and full selected range.
- **CHOP-006** Assign the active slice to a pad and advance pad/slice with Auto Next.
- **CHOP-007** Long sources must use bounded memory or a documented source/cache limit.
- **CHOP-008** In PAD trim, a waveform long press moves the nearer START/END boundary to the pressed frame and opens a source-clamped precision viewport no wider than one second.
- **CHOP-009** START and END expose independent numeric scroll wheels with previous/current/next values, dial position, and selectable frame/1 ms/10 ms steps; every update preserves start-inclusive/end-exclusive bounds, minimum Chop length, Preview, Revert, and Undo/Redo.

## Pads and voice engine

- **PAD-001** Four banks × thirty-two pads, exposed as fixed sixteen-pad pages.
- **PAD-002** Velocity-sensitive trigger through UI/MIDI where input supports it.
- **PAD-003** One-shot and gate modes.
- **PAD-004** Reverse, choke, gain, pan, pitch, time stretch, tone/filter and resonance.
- **PAD-005** ADSR and LFO per pad.
- **PAD-006** Deterministic voice stealing, stop-all and stream restart.
- **PAD-007** Pad reassignment retains or resets parameters according to an explicit, tested policy.

## DSP and effects

- **DSP-001** Pitch and duration controls are independent within documented algorithm limits.
- **DSP-002** LFO supports defined waveforms, target, depth and free/BPM-synced rates.
- **DSP-003** Inserts and sends use stable parameter ranges and prevent feedback blow-up.
- **DSP-004** Master processing prevents uncontrolled clipping while preserving export consistency.
- **DSP-005** Real-time and offline paths share primitives or have equivalence tests.

## Pattern and Song

- **SEQ-001** Multiple named patterns.
- **SEQ-002** Pattern length supports at least 16/32/64 sixteenth-note steps.
- **SEQ-003** BPM and swing have explicit ranges and deterministic timing.
- **SEQ-004** Live pad recording quantizes according to a visible policy.
- **SEQ-005** Song mode orders pattern sections and repeat counts with bounded total duration.
- **SEQ-006** Playback position, stop, restart and loop behavior are consistent across UI, MIDI and export.

## MIDI

- **MIDI-001** Discover and connect Android MIDI input devices.
- **MIDI-002** Parse note on/off, velocity and running status correctly.
- **MIDI-003** Provide channel/base-note mapping for 128 pads.
- **MIDI-004** Support bounded CC learn for selected pad/master parameters.
- **MIDI-005** Follow 24 PPQN clock and start/continue/stop when clock sync is enabled.
- **MIDI-006** Disconnect/reconnect without crash or resource leak.

## Project and history

- **PROJ-001** Save/load a versioned `.choplab` package using an original schema.
- **PROJ-002** Embed/deduplicate referenced PCM assets or document an alternative portable policy.
- **PROJ-003** Validate entry paths, counts, compressed/uncompressed sizes, frame totals and duplicate IDs.
- **PROJ-004** Use atomic or recoverable saves and preserve the last valid autosave.
- **PROJ-005** Undo/Redo has a bounded history and coalesces continuous control edits.
- **PROJ-006** Save/load round trips preserve audible and structural project state.

## Export

- **EXP-001** Export stereo PCM WAV master at a documented sample rate/bit depth.
- **EXP-002** Export selected bank/pad stems in a package with deterministic naming and manifest.
- **EXP-003** Document whether stems include insert, send and master effects.
- **EXP-004** Long exports have progress, cancellation and bounded resource use.
- **EXP-005** Export must not silently truncate or claim success after failure.

## Non-functional requirements

- **NFR-001** Android API 29+.
- **NFR-002** Audio callback follows the repository real-time safety rules.
- **NFR-003** No secrets or machine paths in source control.
- **NFR-004** Critical pure logic and persistence have deterministic tests.
- **NFR-005** Build, lint and tests are reproducible in CI.
- **NFR-006** Accessibility labels and appropriate touch targets for primary controls.
- **NFR-007** Documentation distinguishes source presence, build verification and physical-device verification.
