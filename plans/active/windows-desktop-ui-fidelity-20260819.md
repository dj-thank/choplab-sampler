# Windows Desktop original UI fidelity — 2026-08-19

## Purpose and user-visible outcome

Replace the first executable proof's generic Swing layout with a Windows desktop deck that closely follows the existing Android ChopLab UI. A user should recognize the same five-step workflow, source/chop area, 4×4 PAD surface, selected PAD editor, BANK controls, and guided next actions before any Spotify feature is used.

## Current state

- Base implementation branch: `codex/choplab-desktop-exe`.
- Prior implementation commit: `58a464e2cb76d9b489c016564dee8f3975ad155a`.
- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-exe-20260819`.
- Existing `desktop/` is a Kotlin/JVM Swing app with local WAV playback and Spotify PKCE metadata/control seams.
- The Android UI source of truth is `app/src/main/java/com/choplab/sampler/ui/OtohiroiDeck.kt` and its companion UI files. The canonical visual sources are preserved under the canonical checkout's `work/gpt-pro-fullrepo-ui-integration-20260812/packet/visual-references/`.
- The selected quality direction is A+B: original-deck fidelity plus a context-aware guided workflow. The decision matrix is recorded in [`docs/desktop-quality-direction-matrix.md`](../../docs/desktop-quality-direction-matrix.md).

## Constraints and invariants

- Keep the existing Android `:app` behavior and source untouched.
- Keep Spotify metadata/playback-control-only boundaries; no Spotify audio bytes, capture, stream ripping, or MP3 conversion.
- Preserve local WAV support and avoid claiming MIC/DEVICE capture, low-latency WASAPI, or advanced DSP until implemented and tested.
- Do not copy canonical large screenshots, user audio, APKs, tokens, or SDK paths into the product branch.
- Use the visual contract in `docs/ui/windows-desktop-original-ui-contract.json`; every declared region needs implementation evidence.
- Target gate remains `LOCAL_PASS`.

## Architecture and interfaces

The UI is a desktop-owned presentation layer over small public seams:

```text
DesktopApp
  DesktopDeckPanel          original-style Swing deck and event routing
  DesktopDeckModel          4 banks × 32 pads, 16-pad page, workflow/stage state
  JavaSoundWavPlayer         local WAV playback
  WavWaveform                local WAV envelope for the original waveform region
  SpotifyApi / PKCE          metadata and playback control only
```

The original Android UI remains the behavior vocabulary, not a dependency. The desktop implementation reuses visual tokens and copy manually so the Android build remains isolated from JDK/Swing code.

## Milestones

### Milestone 1: contract and state seam

- Add the machine-readable UI contract and human-readable fidelity brief.
- Add tests for bank/page/selection/assignment state and stage transitions.

### Milestone 2: original-style chop deck

- Replace the generic header/grid with the original dark/cream deck, workflow strip, source controls, waveform, coach, pad grid, editor, bank strip, and production dock.
- Preserve local WAV open/play/stop and Spotify actions in an adapted header/status area.

### Milestone 3: original-style arrange deck

- Add the 4×4 pad selector and 16-step sequencer state/layout modeled after the Android arrange screen.
- Keep unsupported recording/export features visibly bounded.

### Milestone 4: visual and product validation

- Capture chop and arrange states at the desktop target viewport.
- Run the UI contract validator, desktop tests, Android regression checks, Windows app-image packaging, exact-PID EXE smoke, and `git diff --check`.
- Complete separate standards/spec review and push a GitHub branch/PR without publishing a release.

## Progress

- [x] 2026-08-19 — Inventoried Android UI source and inspected canonical chop/arrange captures at 853×1844.
- [x] 2026-08-19 — Wrote the visual contract and fidelity brief.
- [x] Implement the expanded desktop state seam, including four banks, two pages, stage gates, source/PAD/step state, and local WAV waveform data.
- [x] Replace the generic UI with the original-style chop deck and add keyboard/accessibility/High-DPI interaction seams.
- [x] Add the original-style arrange state with a 16-step sequencer and truthful unsupported-action messages.
- [x] 2026-08-19 — Captured chop and arrange states, passed the UI contract validator, and corrected narrow-pane text collision and source/PAD playback-state transitions.
- [ ] Run the final full validation, complete standards/spec review, commit, and manage the GitHub PR.

## Validation and evidence

- Visual references: canonical worktree paths recorded in the UI contract.
- Implementation captures: ignored `desktop/build/ui-captures/windows-desktop-chop.png` and `windows-desktop-arrange.png`.
- Product checks: `:desktop:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:desktop:packageWindows`.
- Delivery checks: versioned `jpackage --type app-image`, EXE SHA-256 receipt, and Windows PR workflow at `.github/workflows/desktop.yml`.
- Declared follow-up: KEY/TONE/LEVEL currently preserve PAD state and UI vocabulary; audio DSP, project save/open, native low-latency routes, signing, installer publication, and Spotify account/device validation are not part of this slice.
- Evidence ceiling: `LOCAL_PASS`; provider/public/Human claims remain separate.

### Local receipt — 2026-08-19 13:18 JST

- `:desktop:test --rerun-tasks --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL`, 12 tests.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL`.
- `validate_ui_contract.py docs/ui/windows-desktop-original-ui-contract.json --root .`: `PASS`, 8 regions, exact=5, semantic=3, states=4.
- `:desktop:packageWindows --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL`; app-image version `0.2.0`, EXE 449,024 bytes, SHA-256 `F1FB7267AF73028AD61DFF30158CCE17D856988E46332A5D8359A8142A7781F3`, Authenticode `NotSigned`.
- Exact-PID GUI smoke: packaged EXE launch, empty state, local WAV load, PAD 01 assignment/playback, arrange transition, source playback re-load after PAD audition, playback-end stop state, and final arrange capture passed. Capture bounds were `1440×1179` including the Windows frame for a `1440×1180` target size.
- `git diff --check`: `PASS`.

## Rollback

The prior working implementation is commit `58a464e2cb76d9b489c016564dee8f3975ad155a`. Revert only this bounded UI commit if the direction is rejected; never reset/clean the canonical dirty checkout.
