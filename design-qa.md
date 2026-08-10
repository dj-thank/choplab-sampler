# Guided MPC workflow design QA

## Evidence

- Source visual truth, `叩く`: `C:\Users\rambo\.codex\generated_images\019fe6e3-807a-7d31-a87b-7fbe7fc4c388\exec-b0a3fa89-1939-4ee8-aaf6-029b9b065980.png`
- Source visual truth, `並べる`: `C:\Users\rambo\.codex\generated_images\019fe6e3-807a-7d31-a87b-7fbe7fc4c388\exec-079585ce-60c8-49c4-b72e-aedbd8ffe4d2.png`
- Canonical product workflow and visual language: `C:\Users\rambo\.codex\attachments\3f2fc606-7aeb-4fda-8333-83b63b96c961\pasted-text.txt`
- Android implementation, `叩く`: `C:\Users\rambo\Documents\Codex\2026-08-09\new-chat-2\work\choplab-visual-qa\play-postfix.png`
- Android implementation, `並べる`: `C:\Users\rambo\Documents\Codex\2026-08-09\new-chat-2\work\choplab-visual-qa\arrange-postfix2.png`
- Android implementation, detailed PAD PARAM: `C:\Users\rambo\Documents\Codex\2026-08-09\new-chat-2\work\choplab-visual-qa\pad-details-final.png`
- Android implementation, detailed PAD PLAY: `C:\Users\rambo\Documents\Codex\2026-08-09\new-chat-2\work\choplab-visual-qa\play-details-final.png`
- Android implementation, `完成`: `C:\Users\rambo\Documents\Codex\2026-08-09\new-chat-2\work\choplab-visual-qa\finish-final.png`

Source images are 853 × 1844 px at 96 dpi. Android screenshots are 1080 × 2424 px at emulator density 420, approximately 411 × 923dp including system bars. The Android viewport is a headless Pixel 9 AVD on Android 16/API 36. Full-view comparisons were made in one combined visual input per screen, scaling the long edges for composition rather than claiming pixel parity between different aspect ratios and device chrome.

State used for the final comparison:

- a 3-second emulator microphone recording loaded as the current source;
- PAD 01 and PAD 02 assigned through live chop while source playback was running;
- PAD A-02 selected;
- PAD key changed from C3 to C#3;
- tone set to 32% and level to 75% through the visible sliders;
- steps 1, 5, 9, and 13 enabled for PAD A-02.

Focused-region comparison was not separated into additional crops because the combined high-detail source/implementation views kept the selected-PAD controls, 16-step grid, labels, and touch controls legible. UIAutomator bounds were also checked for PAD 13/PAD 16, KEY minus/plus, the selected-PAD editor, and all 16 steps.

## Findings

No actionable P0, P1, or P2 findings remain.

- Fonts and typography: the implementation uses the existing Otohiroi monospace hierarchy with Japanese fallback, bold Japanese primary labels, and small English technical captions. The generated target uses a more editorial proportional Japanese face, but preserving the original HTML type language is intentional. No core label wraps beyond two lines or becomes unreachable at the tested viewport.
- Spacing and layout rhythm: all five stages, waveform, 4 × 4 PAD grid, 2 × 8 steps, selected-PAD controls, and status remain in one fixed viewport. The post-fix PAD row is at least about 51dp high on the tested regular portrait layout. The generated target groups some controls into larger hardware panels; the implementation uses the existing cream deck and tighter mobile spacing to preserve the original HTML and real four-bank workflow.
- Colors and visual tokens: cream, charcoal, orange, and green consistently match the canonical original HTML. The darker generated shell is treated as workflow/quality direction, not the final palette. Orange remains the active/sampling/selected color and green remains waveform/value/guidance color.
- Image quality and asset fidelity: the implementation has no stretched or low-resolution visible assets; controls and waveform are native Compose rendering. Generated icons and decorative hardware textures were not copied because the original HTML visual language and repository rule against distinctive MPC trade dress are canonical.
- Copy and content: Japanese-first labels explain the task while small English captions preserve professional vocabulary. The fixed coach says `光るマスで音が鳴ります。まず 1・5・9・13 を押そう`; source playback changes its readout to `ここだと思ったらPADを押すと、その瞬間が入ります`. Status details intentionally ellipsize rather than expand or scroll.
- Detailed editing: the review follow-up shows Japanese-first labels for sound shaping, play behavior, reverse, one-shot/gate, choke and PAD clear. The finish summary includes an explicit playback-state tile.
- Affordances and state: active stage, bank, selected PAD, active steps, sampling state, recording state, and export readiness use both color and text. UIAutomator confirmed live-chop assignment for PAD 01/02, KEY + changing C3 to C#3, and steps 1/5/9/13 changing to `オン`.

## Intentional differences

- The cream Otohiroi shell from the original HTML is final; the dark generated shell is not copied.
- The running MVP has four banks × 16 PADs and uses timestamps instead of invented instrument names.
- Song, stems, stereo, and arbitrary-length export are not implied. `完成` accurately offers the implemented four-bar WAV export.
- System status/navigation bars are present in Android evidence but absent from the generated mock.

## Comparison history

### Iteration 1 — blocked

- P2: selected-PAD tone and level were visible as tap-to-cycle buttons, but the selected mock and professional workflow called for obvious direct adjustment.
  - Fix: regular portrait now shows PAD identity, KEY minus/value/plus, and direct TONE/LEVEL sliders. Compact and landscape layouts retain the bounded one-row controls plus the detailed editor.
  - Post-fix evidence: `play-postfix.png`; UIAutomator read back `KEY (+1)` and `C#3`, with both sliders enabled for assigned PAD A-02.
- P2: the `並べる` beginner instruction existed only in the bottom status line and was less prominent than the selected visual.
  - Fix: added a fixed dark/green TIP bar above the arrange controls without introducing scrolling.
  - Post-fix evidence: `arrange-postfix2.png`; PADs, all 16 steps, both parameter sliders, and the status strip remain visible.

### Iteration 2 — passed

- The earlier P2 findings are fixed in the same Pixel 9/API 36 viewport and state.
- No clipping, hidden persistent control, or actionable P0/P1/P2 difference remains.

## Follow-up polish

- P3: a physical phone pass can tune haptic strength, slider comfort, font scale, and sunlight contrast.
- P3: a non-silent source should be used on a physical device to judge waveform amplitude rendering and marker legibility over dense audio.
- P3: landscape should receive a separate same-state screenshot pass even though the fixed layout policy and compile checks cover it structurally.

final result: passed
