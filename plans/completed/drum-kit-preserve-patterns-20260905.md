# Change drum sounds without losing the beat

## Purpose and user-visible outcome
Changing the built-in drum kit keeps the user's A/B rhythms and Song order. Initial addition to empty kit slots may supply a starter groove, but never clears the other variation or overwrites an existing saved groove. UI clearly distinguishes adding a kit from changing sounds.

## Current state
Base c83cad8db221e80f8857dfc8d7afc542cc3a7bf4 in the sole-writer refinement worktree. Both adapters call replaceBankStepsAcrossPatterns during kit application, resetting selected drum rhythm and clearing inactive variation. Existing empty/new-kit behavior and replacement confirmation remain.

## Invariants
Preserve existing audio outside the 16 kit slots, loop owner outside those slots, tempo, A/B and Song, recording/loading admission, Undo/Redo and archive schema. Root owns changes in shared model/UI, Android/Desktop adapters and tests. No device/provider/public operations. Rollback only this milestone's diff if verification fails. LOCAL_PASS target.

## Plan
- [x] Reproduce replacing custom A/B kit rhythm via actual desktop controller.
- [x] Share a pure initial-pattern policy, preserve replacement rhythms, update UI copy.
- [x] Verify initial blank/stored groove cases, live loop, Undo/Redo and save/open round trip.
- [x] Run required full local gate, review fixed-base diff, record artifacts and commit.

## Evidence and remaining limits
Pending local verification. Physical audio/touch, Android runtime and iOS are separate.

RED: kit replacement wiped A and reset B before save. GREEN: shared first-install/stored-groove/intentional-empty guards and controller Undo/Redo/save/reopen test passed. User then corrected BEAT over-simplification; this data-preservation fix remains compatible and continues as a subtask of the next Beat composition plan.

## Local closeout
Product360ccb0, full gate and final UI/policy verification PASS (812 standard+33 UI/controller checks, 12 overlaps). Standards mandatory cleanup and Spec limit-documentation findings closed; optional UI action extraction deferred. See outputs/beat-loop-layers-20260905.json for byte hashes and limits. No device or public action.
