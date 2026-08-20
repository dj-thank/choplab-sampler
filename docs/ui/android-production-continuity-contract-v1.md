# Android production continuity reference contract v1

## Authority and sources

1. Primary visual reference: the project's Android CHOP portrait capture `work/choplab-final-portrait.png`, 1080×2424. It proves the source waveform, exact four-stage copy, BANK/page hierarchy, 4×4 PAD topology, production dock, and status strip.
2. Contrast reference: the project's Android BEAT portrait capture `work/choplab-v092-beat.png`, 900×2026. It proves the current discontinuity: a four-lane step board replaces the CHOP performance surface.
3. Authoritative copy and behavior: `shared/src/commonMain/kotlin/com/choplab/sampler/ui/OtohiroiDeck.kt`, `GuidedWorkflow.kt`, `PadGrid.kt`, and the active controller state. Pixels never override truthful runtime state.

Historical captures are appearance evidence only. They do not prove current audio, device, accessibility speech, provider, public, or Human gates.

## Intent

The four numbered tabs are stages of one Production, not separate instruments. Entering `3 ビート / BEAT` keeps the same selected Chop and physical PAD locations visible; sequencing augments that surface instead of replacing it.

## Exact copy additions

- CAPTURE project action: `制作を開く\nOPEN PROJECT`.
- CAPTURE helper: `ファイル、制作、マイク、端末音声から1つ選びます`.
- BEAT default coach: retain the existing `1 音ありPADを選ぶ  →  2 ループ／並べる  →  3 足す／スクラッチ`.
- Detailed sequencer destination: retain `細かく調整\nSTEPS / SOUND` or the current responsive punctuation variant.
- Scratch ready guidance: `選択PADを押さえ、円盤を左右へ擦ります。離すとビートへ戻ります` when a valid return target exists; do not claim return when none exists.

## Geometry and hierarchy

### CHOP baseline

In portrait reading order: machine header → four-stage strip → guidance/source action → source waveform → source transport → BANK A–D → PAD page 01–16/17–32 → exactly 4 columns × 4 rows of near-square PADs → production dock → status.

### BEAT default

In portrait reading order: machine header → four-stage strip → brief Beat coach → selected-Chop waveform/timeline → BANK A–D → PAD page 01–16/17–32 → exactly 4 columns × 4 rows of playable PADs → selected-Chop loop/Beat transport → `音を重ねる` and `細かく調整` actions → status.

In landscape, waveform/controls may occupy the left pane and the same 4×4 PAD grid the right pane. Responsive scaling may change padding, text wrapping, and corner radius, but not stage copy, BANK order, PAD identity, or grid topology.

### Fine BEAT view

The existing responsive sequencer remains available only after the explicit detailed-control action: landscape uses the four-lane `BeatLaneBoard`; portrait uses the selected-PAD 16-step grid with placement presets. Returning from detail restores the default Chop surface with the same selected PAD.

## State and interaction contract

| State | Required destination/surface | Forbidden claim |
|---|---|---|
| Recovery running | CAPTURE shell with `LOADING` and disabled external actions | `NO SOURCE` |
| No recovered project | CAPTURE with OPEN PROJECT and generated starter drums in BANK B | User project restored |
| Recovered Source, no user-authored Beat (untouched starter drums do not count) | CHOP with recovered waveform and PADs | Empty new project |
| Recovered audible pattern or pad-only production | BEAT default Chop surface | Fresh CAPTURE |
| Manual `.choplab` open | Destination recomputed from the loaded bytes | Starter kit silently overwrote BANK B |
| BEAT default | PAD tap plays/selects; long press can reach edit; waveform follows selected/looping Chop | Step board replaces PAD grid |
| Scratch pointer down | Scratch voice owns primary playback immediately | Gesture waits for visible drag before starting |
| Scratch idle | Voice output converges to silence and direction/speed reads neutral | Held DC/sample tone |
| Scratch release with valid prior Beat | Prior loop/transport restarts once | Stale or empty PAD starts |
| Scratch release without prior Beat | Scratch stops cleanly | Automatic playback starts |

## Acceptance

- First-screen project OPEN calls the same platform document contract as FINISH and is disabled during loading/recording.
- New-production starter installation is deterministic and tests show recovery/manual-load preservation.
- Default BEAT composes `PadGrid` with 16 visible PADs and hides detailed sequencing; detail view hides the performance grid and exposes the responsive lane/step sequencer.
- Pointer input starts on down, has a deterministic dead zone and bounded signed speed, publishes playhead progress, and ends on up/cancel.
- Focused tests, Android unit/lint/assemble, shared/desktop regressions, public-surface validation, and screenshot inspection pass before merge.
