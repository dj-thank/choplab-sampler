# Keep the chosen Chop through Beat handoff and compact layouts

## Purpose
The sound edited in TRIM must become the loop when explicitly continuing to Beat. Failure must keep the editor. Compact Beat must keep a visible waveform and reachable controls without adding zoom/mode controls.

## Current state
Base 8e2765805ea059a113eee2f59ab5afa1e61af64a, root owns the clean refinement worktree. Existing handoff only navigates; an old loop wins selection. QUICK portrait fixed children consume waveform height on short screens.

## Invariants
Preserve full-source map, permanent S/E dials, existing audio transaction and start guards, stored audio/history. No device/provider/public work. Revert only this milestone's diff if local tests fail. Target LOCAL_PASS via actual shared scene input plus controller/audio tests and required build gate.

## Plan
- [x] Reproduce old-loop handoff and zero-height waveform in scene tests.
- [x] Return loop admission from platform controllers; navigate only after accepted chosen loop + pattern start. Label the action explicitly as starting playback.
- [x] Allow compact Beat to scroll while reserving useful waveform height.
- [ ] Validate both changes; review Standards/Spec against this plan and base; update receipts and commit.

## Evidence and limits
Pending. Android device touch/audio and iOS are not verified by shared scene or builds.

RED: old loop remained 0 instead of1; compact waveform Rect(0,0,0,0). GREEN: chosen-loop handoff, rejected loop admission staying in trim, compact visible waveform and scrolling to >=48dp controls all passed.
