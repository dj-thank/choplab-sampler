# ChopLab MPC-style frontend reorganization

## Destination

A coherent MPC-style mobile sampler workflow where source/chop/pad/beat/layer
surfaces share one navigation model, one undo/redo model, and one playback
ownership model. Every destructive or mode-changing action has a visible
return, undo, or stop path.

Phone-first constraints: portrait layouts, reachable one-hand controls,
touch-sized hit targets, pinch waveform navigation, and no desktop-only
precision controls.

## Current evidence

- Pad long-press to trim is wired and covered by contract tests.
- Waveform handles, tap seek, pinch/double-tap zoom, and stale-waveform hiding
  are implemented locally; Compose gesture behavior is not yet device-verified.
- Beat loop and arrangement models exist; playback exclusivity and preview
  teardown are covered by unit tests.
- Full local unit gate is 213 tests with zero failures; this is LOCAL_PASS only.

## Milestones

1. Navigation contract: make return/back, stop-all, undo, and redo visible and
   consistent across Capture, Chop 2, Pad edit, Beat, Scratch, and Layer.
2. Source/chop surface: one waveform component with pinch zoom, tap seek,
   visible viewport controls, direct S/E handles, and loading placeholder.
3. Pad edit surface: long-press is the single entry to trim; preview is a
   single voice; parameter/play/trim pages share the same back affordance.
4. Beat surface: simplify loop selection and placement so selection never
   silently auditions or creates overlapping playback.
5. Verification: add pure viewport/gesture policy tests, run full Gradle gate,
   then perform Pixel touch/audio verification before any release claim.

## Guardrails

- Preserve archive schema and data unless an explicit migration is designed.
- Keep destructive edits behind undo/redo or confirmation.
- Keep playback ownership centralized; no UI starts a second voice directly.
- Do not call local tests DEVICE/PUBLIC/HUMAN evidence.
- Keep destructive actions recoverable on-device with an obvious undo path or
  confirmation.
