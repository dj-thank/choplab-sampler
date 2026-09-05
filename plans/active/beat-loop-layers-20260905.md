# Select a sound, loop it, then add layers

## Authoritative user correction
BEAT had become too sparse to understand/select sounds. User clarified that the basic workflow is selecting a chopped range, pressing Loop to repeat it as the core, then adding favorite sounds/loops one by one (sampling-based music). A step-order-first redesign is withdrawn. Preserve automatic selected waveform and S/E controls; preserve full-source overview for rechopping.

## Scope and architecture
Base c83cad8 with compatible drum-kit pattern-preservation fix. Root owns UI/model/adapters and tests in this isolated worktree. Restore an explicit sound selector and Loop action, show core and added loops. Add/remove non-core loops without restarting the core or drums. Existing PadPlayMode.LOOP persists the layer set; runtime loopingPadIndex monitors the core, so no new archive schema is needed. STOP and replay restore the configured loop set; export already understands LOOP voices but must be verified. Resuming an existing loop preserves its configured companions; TRIM Loop and Continue-to-Beat also use additive layering, while older explicit core-control APIs retain their replacement semantics.

## Invariants
Single root writer. Preserve source/audio, A/B/Song, Undo/Redo and persistence. Audio callback has no allocation/locks/I/O. Layer start admission must precede project mutation; rejection preserves core/history. Independent sample loops retain their native durations; no automatic time stretch or beat snapping claim. No device/provider/public operations; target LOCAL_PASS.

## Plan
- [x] Drum kit changes preserve A/B; targeted model/controller/save-reopen and UI tests pass.
- [x] Restore selection + explicit loop UI and visible stacked sound context.
- [x] Implement incremental layer start/remove and restart/trim/export contracts.
- [x] Test admission failures, unchanged core ownership, saved layers and actual PCM loops.
- [ ] Full local gate, review, screenshots, docs, receipt and commit.

## Stop and rollback
Revert only this milestone's owned diffs if tests fail; do not modify the canonical dirty checkout. Device sound/touch, live audio quality, iOS and Human acceptance remain unverified.

## Validation checkpoint
Shared/model, Android unit, JVM renderer and Desktop controller checks exercised. Add/remove ClipProbe test confirms no core stop/restart; two-loop stereo export has energy in both channels late in the render. UI selection/add/remove and compact controls passed. Test input now waits for actual scroll positions to stabilize; a click during animated semantics scrolling only canceled the scroll. Full gate and reviews pending.

## Playback limits
Layers repeat at their own native selected lengths; no automatic tempo match/time stretch. Restart begins the configured loop set at each slice start; live entry timing is not captured as a performance. Core monitor is runtime state; persisted content is the LOOP pad set.

## Review resolutions
- Standards: replacing a kit must retire every LOOP in the affected 16 slots, not only the monitored core. Both adapters now stop target loop layers before replacing pads, and abort the sound edit if stop admission/failure occurs. An outside core is untouched. Android stop-admission and Desktop rejection/UI continuity cases added.
- Spec: the initial release intentionally permits 8 configured sample loops (including core), reserving headroom in the Android 32-voice pool for drums and recorded vocals. The ninth additional loop is disabled with the explicit 8-sound explanation; existing loops remain editable/removable. This is a product limit, not a claim that hardware supports only 8 voices. No schema migration or automatic time stretching is added.

If the core itself is in the replaced kit slots, its whole playback session stops (matching the core Stop action), so no outside layer is left playing without an owner. Configuration of outside loops remains saved for replay.
