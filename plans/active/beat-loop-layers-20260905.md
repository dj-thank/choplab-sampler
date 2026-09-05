# Select a sound, loop it, then add layers

## Authoritative user correction
BEAT had become too sparse to understand/select sounds. User clarified that the basic workflow is selecting a chopped range, pressing Loop to repeat it as the core, then adding favorite sounds/loops one by one (sampling-based music). A step-order-first redesign is withdrawn. Preserve automatic selected waveform and S/E controls; preserve full-source overview for rechopping.

## Scope and architecture
Base c83cad8 with compatible drum-kit pattern-preservation fix. Root owns UI/model/adapters and tests in this isolated worktree. Restore an explicit sound selector and Loop action, show core and added loops. Add/remove non-core loops without restarting the core or drums. Existing PadPlayMode.LOOP persists the layer set; runtime loopingPadIndex monitors the core, so no new archive schema is needed. STOP and replay restore the configured loop set; export already understands LOOP voices but must be verified. Resuming an existing loop preserves its configured companions; explicitly replacing the core through existing TRIM handoff may retain its historical replacement behavior.

## Invariants
Single root writer. Preserve source/audio, A/B/Song, Undo/Redo and persistence. Audio callback has no allocation/locks/I/O. Layer start admission must precede project mutation; rejection preserves core/history. Independent sample loops retain their native durations; no automatic time stretch or beat snapping claim. No device/provider/public operations; target LOCAL_PASS.

## Plan
- [x] Drum kit changes preserve A/B; targeted model/controller/save-reopen and UI tests pass.
- [ ] Restore selection + explicit loop UI and visible stacked sound context.
- [ ] Implement incremental layer start/remove and restart/trim/export contracts.
- [ ] Test admission failures, unchanged core ownership, saved layers and actual PCM loops.
- [ ] Full local gate, review, screenshots, docs, receipt and commit.

## Stop and rollback
Revert only this milestone's owned diffs if tests fail; do not modify the canonical dirty checkout. Device sound/touch, live audio quality, iOS and Human acceptance remain unverified.
