# Open a long-pressed chop at a screen-fitting precision view

## Purpose and user-visible outcome

Long-pressing an assigned PAD opens TRIM with that entire chop already visible at a comfortable scale. The chop occupies about 80% of the editable waveform when source context permits, with a one-second minimum context for very short chops. A compact full-source strip keeps orientation. Long-pressing inside the waveform still moves the nearer boundary safely and focuses a maximum one-second window.

## Current state

- Base: `25792b94190ff4558e29d0d2543d9a5fd08ca10e`, tree `4a72d7d89cb6b53c7b613242072b56420f931487`.
- User reference: 570 × 1280 current Android screenshot.
- ImageGen target: parent PAD `outputs/ChopLab-precision-trim-imagegen-target-20260825.png`; hierarchy reference only.
- Machine-readable UI contract: `docs/ui/precision-trim-reference-contract-20260825.json`.
- Before this change, `precisionFocusFrame` opened at the PAD midpoint but `WaveformEditor` initialized at zoom 1, so the whole source appeared until another waveform long-press or zoom action.

## Constraints and invariants

- Preserve start-inclusive/end-exclusive ranges and minimum two-frame PAD length.
- Preserve existing tap, waveform long-press, pinch/pan, viewport buttons, START/END wheels, precision choices, Preview, Revert, Source and Stop All.
- A generated image is visual guidance only; runtime frame/time truth comes from the current `PcmAudio` and PAD.
- Overview is orientation-only and disappears below 500dp height to protect compact landscape editing space.
- No schema, audio engine, persistence, recording, provider, public or physical-device changes.

## Architecture and interfaces

- `padTrimInitialWindow` owns the pure source/PAD viewport policy.
- `WaveformEditor` accepts optional initial focus/visible frames and reports the current resolved viewport.
- `PrecisionTrimOverview` renders the full-source envelope, selected PAD range, current viewport and focus line.
- `PadTrimEditor` freezes the initial window at entry, resets back to it on Revert, and keeps the later one-second focus behavior.

## Milestones

### Milestone 1: Reference contract and RED tests

- Extract seven regions from the supplied screenshot and generated hierarchy candidate.
- RED tests cover two-second, short and source-edge PADs plus an initially focused Compose waveform.

### Milestone 2: Shared implementation and runtime comparison

- Add pure initial-window policy and shared UI implementation.
- Capture the exact initial PAD-fit state on the dedicated API 36 AVD using synthetic audio.
- Normalize current/reference/generated/implementation views into a three-way comparison.

### Milestone 3: Verification and closeout

- Run focused tests, two exact instrumentation tests, full clean cross-platform gate, UI contract validator, public-surface scan and diff checks.
- Update revision-bound SSOT and parent PAD receipt, then move this plan to completed.

## Progress

- [x] 2026-08-25 — User screenshot inventoried at 570 × 1280; 7-region contract PASS.
- [x] 2026-08-25 — ImageGen target created with full-source context plus focused waveform hierarchy.
- [x] 2026-08-25 — RED unit/instrumentation compile failures observed.
- [x] 2026-08-25 — PAD-fit, one-second floor, edge clamp, overview and accessibility policies implemented.
- [x] 2026-08-25 — API 36 AVD initial state captured; two focused instrumentation tests PASS.
- [ ] Final revision-bound docs, review and closeout.

## Discoveries

- The prior domain function already handled the second-stage one-second focus correctly; the missing behavior was the initial `WaveformEditor` viewport.
- The ImageGen candidate's strongest contribution was hierarchy, not decoration: a non-interactive full-source context strip above the editable waveform.
- A fixed overview is unsuitable below 500dp height; compact landscape retains the main PAD-fit waveform without the overview.

## Decision log

- 2026-08-25 — Initial visible frames are `max(1 second, ceil(PAD length × 1.25))`, capped to source length. This makes ordinary chops occupy about 80% while avoiding disorienting over-zoom on tiny chops.
- 2026-08-25 — Keep the existing one-second waveform-long-press focus and boundary movement unchanged.
- 2026-08-25 — Do not embed the generated raster in the app; implement the hierarchy with existing Compose tokens and live data.

## Validation log

- `:app:testDebugUnitTest --tests '*PadTrimTest' ... :shared:desktopTest` — RED then PASS.
- API 36 AVD manual state: A01 `0:00.000–0:00.500`, initial editable viewport `0:00.000–0:01.000`.
- AVD instrumentation: initial focused context + waveform-long-press one-second focus, 2/2 PASS.
- Clean full gate: 191 tasks PASS before compact-height adaptation; final incremental focused gate PASS after adaptation.

## Risks and rollback

- Extra vertical context could compress landscape UI; hide it below 500dp.
- Viewport callback could create a recomposition loop; only update when the resolved immutable viewport changes.
- Rollback is the isolated branch; no canonical checkout, real user project or physical device was modified.

## Remaining device validation

- Physical touch feel, TalkBack speech order, device-specific font scale screenshots and human preference remain outside the local/emulator gate.
