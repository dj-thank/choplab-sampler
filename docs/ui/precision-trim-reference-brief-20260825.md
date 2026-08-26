# Precision trim image-reference brief — 2026-08-25

## Evidence roles

- Primary reference: user-supplied 570 × 1280 current Android screenshot. It proves visible layout only.
- Generated comparison: 839 × 1875 ImageGen UI candidate. It contributes hierarchy—the full-source context strip above a larger focus waveform—but proves no behavior or backend state.
- Product authority: current shared Compose/domain source and tests.
- Implementation capture: fresh 1080 × 2400 API 36 AVD screenshot with a generated four-second WAV and A01 selected. It proves this emulator viewport/state only.

## Selected direction

When a user long-presses an assigned PAD, the first precision-trim viewport should fit that entire chop with comfortable context. The selected chop occupies about 80% of the waveform width: visible frames are the larger of one second or `pad length × 1.25`, bounded by the source. A later long-press inside the waveform keeps the existing behavior of moving the nearer boundary and changing to a maximum one-second focus centered on the pressed frame.

A compact full-source overview is added above the editing waveform. It shows the whole source, the selected PAD range, and the current editing viewport. It is an orientation aid, not a second editor.

## Intentional deviations from the generated image

- Generated times and waveform data are illustrative; runtime frames remain authoritative.
- No magnifying-glass icon is added. The existing original visual system has no icon vocabulary for it.
- Existing tested START/END wheels, precision options, Preview, Revert, Source and Stop All remain unchanged.
- The generated mockup's typography and border simplification are interpreted through current Compose tokens, rather than copied as raster styling.
- At heights below 500dp, the overview strip is omitted so the editable waveform and existing controls remain usable; the initial PAD-fit viewport still applies.

## Acceptance

- Initial long-pressed PAD viewport is screen-fitting and deterministic at center and source edges.
- Waveform long-press still produces a one-second precision viewport and safe boundary move.
- Overview and focus readout share exact frame truth.
- Minimum 48dp interaction targets and accessibility actions remain.
- Final implementation is captured in the same trim state and compared with both references.

## Current visual mapping

- Regions: 7 total; exact 4, semantic 1, adapted 2.
- The generated full-source context strip was implemented without its decorative search icon.
- The initial A01 range is 0.5 seconds; the main viewport opens at exactly 1.0 second, so the selected chop occupies half the width rather than being excessively enlarged.
- Side-by-side comparison: parent PAD `outputs/ChopLab-precision-trim-comparison-20260825.png`.
