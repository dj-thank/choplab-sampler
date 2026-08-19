# Windows Desktop original UI fidelity brief

The Windows EXE must read as the same ChopLab deck as the Android app, not as a generic desktop dashboard. The Android Compose implementation in `app/src/main/java/com/choplab/sampler/ui/OtohiroiDeck.kt`, `PadGrid.kt`, `BeatLaneBoard.kt`, `GuidedWorkflow.kt`, and `ProductionDockPolicy.kt` is the authoritative copy and behavior vocabulary.

The primary visual reference is the canonical chop capture at 853×1844. The arrange capture is the comparison state. The desktop implementation is allowed to reflow the portrait composition into a wider workspace, but it must retain the same five-stage workflow, cream console panel, black inset controls, orange active lamp, green audio/status accents, 4×4 pad surface, selected-PAD editor, BANK A–D strip, and guided production dock.

## Fidelity policy

| Region | Fidelity | Desktop treatment |
|---|---|---|
| Workflow, copy, palette, control rhythm | exact | Same labels, order, active/disabled meaning, and deck tokens |
| Source waveform and pad performance | semantic | Wider desktop panels; local WAV is the supported source |
| Arrange sequencer | semantic | Wider split layout with the same 4×4 selector and 16-step lane concept |
| Android capture, haptics, and advanced DSP | adapted | Visible capability boundary; no false desktop support |

## Reference evidence boundary

The source PNGs are preserved in the canonical dirty checkout and are not copied into the product branch. The JSON contract records their canonical relative paths plus ignored local desktop captures. Visual comparison proves appearance only; it does not promote the project beyond `LOCAL_PASS` or prove Windows audio latency, Spotify account behavior, signing, or public distribution.

Validate locally with:

```powershell
python C:\Users\rambo\.codex\skills\reconstruct-ui-from-reference\scripts\validate_ui_contract.py `
  docs/ui/windows-desktop-original-ui-contract.json `
  --root .
```
