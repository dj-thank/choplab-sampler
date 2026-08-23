# MPC PAD functional model and ChopLab fit — 2026-08-24

## Purpose and evidence boundary

This note answers one bounded product question: what the PAD surface in MPC Beats actually *does* across its different modes, and which concepts belong in a simpler ChopLab Android/Windows workflow. It is not a visual reconstruction brief and does not authorize copying AKAI assets, wording, project formats, proprietary resources, or trade dress.

Primary evidence:

- Akai Professional, *MPC Software User Guide v2.8*, especially pp. 89, 116, 125–129, 140–151, 165, 175–176, and 190: <https://cdn.inmusicbrands.com/akai/MM28M20/MPCSoftware-UserGuide-v2.8.pdf>
- Akai Professional, *MPC Software User Guide v2.14*, current 2.x PAD-panel reference: <https://cdn.inmusicbrands.com/akai/214SMPCSTEMS/MPC%20Software%20-%20User%20Guide%20-%20v2.14.pdf>
- Akai Professional support, *MPC Beats | How To Set Up 16 Levels With Your MIDI Controller*: <https://support.akaipro.com/en/support/solutions/articles/69000858129-mpc-beats-how-to-set-up-16-levels-with-your-midi-controller>
- Akai Professional support, *MPC Beats | Why Are The Pad Perform Dropdown Menus Grayed Out?*: <https://support.akaipro.com/en/support/solutions/articles/69000864632-mpc-beats-why-are-the-pad-perform-dropdown-menus-grayed-out->
- Read-only local inspection of the installed `MPC Beats.exe`, product/file version `2.12.3.9`, plus its installed mode resources. No audio was recorded or extracted, no project was saved, and no MPC installation files were modified.

Local visual receipts are retained outside the repository under the PAD workspace's `work/CHOPLAB_DESKTOP_REFERENCE_20260824/` directory. Confirmed live titles include `MPC Beats - Program Edit (untitled)`, `MPC Beats - Sample Edit (untitled)`, `MPC Beats - Step Sequencer (untitled)`, `MPC Beats - Keygroup mixer (untitled)`, and `MPC Beats - Pad Mute (untitled)`. These images are observation evidence only and are not product references to reproduce.

## The core model: one physical surface, contextual roles

MPC's durable idea is not a particular screen layout. It is a stable 4×4 input surface whose meaning changes with an explicit working context. The selected program/track and mode determine whether the same 16 pads represent sounds, slices, steps, parameter levels, notes/chords, or mute targets. Akai's guide states this directly: each PAD displays different content depending on the current mode (v2.8 p. 89).

The context is also allowed to reject an operation. In the local 2.12.3 build, Pad Mixer and Pad Mute show an explicit unavailable reason while an Audio track is selected. Akai similarly documents Step Sequencer as MIDI-track-only (p. 175) and Pad Perform as requiring Plugin, Keygroup, MIDI, or CV rather than Drum/Clip. This is a functional safety and comprehension pattern: preserve the user's current work, disable the incompatible operation, and explain why.

## Mode-by-mode PAD roles

| MPC context | What the 16 PADs mean | Important behavior | ChopLab fit |
|---|---|---|---|
| Main / normal program performance | Assigned samples, notes, or clips | PAD banks extend the surface; a press triggers the assigned object. The object type comes from the current Program (v2.8 pp. 125–126). | Already present as four role banks × two 16-PAD pages. Preserve one performance surface. |
| Sample Edit — Trim | Audition commands around Start, End, and Loop markers | Different PADs audition before/after boundaries, one-shot vs hold-to-play, entire sample, and loop ranges (p. 143). | ChopLab already has selected-PAD preview, START/END precision controls, one-shot/gate/loop, and waveform seek. Do not add 16 opaque audition commands; keep named Preview and boundary controls. |
| Sample Edit — Chop | Slices of the current sample | Pressing a PAD both selects and auditions the corresponding slice; One Shot and Note On auditions differ (p. 151). | Already the heart of CHOP. Keep PAD selection, source position, and assigned slice visually linked. |
| Program Edit | The sound/note/clip being edited | PAD remains playable while per-PAD layers and parameters are edited (pp. 128–129). | Already present as PAD EDIT with trim, pitch, tone, gain, reverse, play mode, and choke. Add missing parameters only when the audio domain supports them. |
| Step Sequencer | Sixteen time steps for one selected PAD | Unlit/lit PAD deletes/adds an event; color communicates velocity and another PAD indicates playhead. Bar and target PAD are explicit (pp. 175–176). | Already present as the secondary 16-step surface. Keep it secondary so it does not replace live PAD performance. |
| 16 Level | Sixteen fixed values for one selected PAD | The selected PAD is temporarily copied across the surface; Velocity, Tune, Filter, Layer, Attack, or Decay increases across PAD numbers (p. 116). | Understand, but defer. ChopLab has per-PAD pitch/tone/gain and preserves 16 distinct fragments. A temporary remap needs a clear exit state, real velocity semantics, and tests before it is worth the added mode. |
| Full/Half Level | One fixed velocity response for all PADs | Full Level forces velocity 127; Half Level forces 63. | Defer until velocity-sensitive UI/MIDI input exists. A fixed mouse/keyboard gain shortcut would otherwise misrepresent velocity. |
| Note Repeat | Repeated triggers while a PAD is held | Repeat rate follows tempo and Time Correct; latch is a separate state. | Defer until the realtime trigger scheduler has a bounded hold/latch contract and Android/Windows parity tests. Existing placement presets cover offline four/eighth/sixteenth placement without pretending to be live repeat. |
| Pad Perform | Notes, scales, chords, or progressions | Valid for Plugin/Keygroup/MIDI/CV, not Drum/Clip; the mapping is musical-note performance rather than sample chopping. | Out of the current sample-only MVP. Reconsider with MIDI/keygroup work, not as a cosmetic PAD screen. |
| Pad Mixer | One channel strip per PAD | Level, pan, routing, and effects per PAD (p. 165). | Partially present: gain/tone/pitch/choke/edit per PAD. Pan/routing/effects belong to a later stereo/audio-engine seam. |
| Pad Mute | Individual PAD mute or a group trigger | PADs mute individual sounds or up to 16 groups in real time (p. 190). | Defer while ChopLab has a small single-pattern model. Current choke, exclusive loop, clear, and ALL STOP solve different jobs and must not be relabeled as mute. |

## Immediate ChopLab decisions

1. Keep `入れる → チョップ → ビート → 保存` and the shared 4×4 PAD surface. Do not add a Cubase/MPC-style permanent inspector, mixer, timeline, or collection of mode windows just to look more professional.
2. Make the existing Windows PAD key legends real. The visible `1234 / QWER / ASDF / ZXCV` layout becomes a tested desktop input contract for the currently visible 16-PAD page. Key-down selects/triggers once; key-up releases Gate playback. Ctrl/Alt/Meta combinations remain available to native commands.
3. Always keep the keyboard legend visible. PAD content badges such as DRUM, VOICE, or LOOP must not replace the input key label.
4. Add native Windows File/Edit/Transport shortcuts because they shorten real production tasks without changing the shared mobile workflow.
5. Preserve explicit disabled reasons and truthful scope. A future 16 Level, live Note Repeat, Pad Perform, Pad Mixer, or Pad Mute slice needs its own domain state and audio tests; it is not added as a decorative toggle.

## Acceptance implications

- Android retains the same touch PAD behavior and visuals apart from a non-destructive key/badge labeling clarification.
- Windows key mapping follows the exact currently visible page and selected BANK; it never captures/replaces audio and never triggers from Ctrl/Alt/Meta shortcuts.
- Key repeat cannot create duplicate voices merely because Windows repeats a key-down event.
- Key-up releases the same global PAD index that key-down owned, even if selection changes meanwhile.
- Native file/project/save/export/undo/redo/transport commands obey the same recording and document-action admission rules as the visible UI.
