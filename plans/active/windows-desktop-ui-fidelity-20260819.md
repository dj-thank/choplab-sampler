# Windows Desktop original UI fidelity — 2026-08-19

## Purpose and user-visible outcome

Replace the first executable proof's generic Swing layout with the exact Android-origin ChopLab deck on Windows. A user must see the same four-step workflow, same copy, same pad/source/beat/save workspaces, and the same state vocabulary; window size may only change the shared adaptive layout.

## Current state

- Base implementation branch: `codex/choplab-desktop-exe`.
- Prior implementation commit: `58a464e2cb76d9b489c016564dee8f3975ad155a`.
- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-exe-20260819`.
- Existing `desktop/` is now a Compose Multiplatform/JVM shell with the shared Android-origin UI and a Windows audio/file adapter.
- The authoritative UI is `shared/src/commonMain/kotlin/com/choplab/sampler/ui/OtohiroiDeck.kt`; its copy and state policy are shared with Android.
- The selected quality direction is exact shared UI + shared sampler model. The architecture decision is recorded in [`docs/architecture/multiplatform-parity.md`](../../docs/architecture/multiplatform-parity.md).

## Constraints and invariants

- Keep Android behavior while moving pure model/UI source into `:shared` so both platforms compile from the same files.
- Keep Spotify metadata/playback-control-only boundaries; no Spotify audio bytes, capture, stream ripping, or MP3 conversion.
- Preserve local WAV support and avoid claiming MIC/DEVICE capture, low-latency WASAPI, or advanced DSP until implemented and tested.
- Do not copy canonical large screenshots, user audio, APKs, tokens, or SDK paths into the product branch.
- Use `docs/ui/android-parity-contract-v2.json`; it supersedes the historical five-step screenshot contract.
- Target gate remains `LOCAL_PASS`.

## Architecture and interfaces

The UI is a desktop-owned presentation layer over small public seams:

```text
  DesktopApp
    shared OtohiroiDeck       exact Android-origin Compose deck
    DesktopSamplerController  shared state/controller seam
    JavaSoundWavPlayer        JVM audio adapter
    DesktopWavDecoder         JVM WAV -> shared mono PCM adapter
    SpotifyApi / PKCE         provider metadata/control seam
```

The original Android UI is now a dependency through `:shared`, not a manually
recreated approximation.

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
- [x] Replace the Swing deck with the shared Android-origin Compose deck.
- [x] Move pure sampler model, workflow policy, waveform/pad/sequence UI, and shared colors/copy into `:shared`.
- [x] 2026-08-19 — Android and desktop shared UI compilation, desktop tests, and Windows app-image packaging passed.
- [ ] Run the final full validation, complete standards/spec review, commit, and manage the GitHub PR.

## Validation and evidence

- Visual source: current Android shared source and `docs/ui/android-parity-contract-v2.json`.
- Product checks: `:desktop:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:desktop:packageWindows`.
- Delivery checks: versioned `jpackage --type app-image`, EXE SHA-256 receipt, and Windows PR workflow at `.github/workflows/desktop.yml`.
- Declared follow-up: KEY/TONE/LEVEL currently preserve PAD state and UI vocabulary; audio DSP, project save/open, native low-latency routes, signing, installer publication, and Spotify account/device validation are not part of this slice.
- Evidence ceiling: `LOCAL_PASS`; provider/public/Human claims remain separate.

### Local receipt — 2026-08-19 13:18 JST

- `:desktop:test --rerun-tasks --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL`, 12 tests.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL`.
- `:shared:compileKotlinDesktop :shared:compileAndroidMain`: `BUILD SUCCESSFUL`.
- `:app:compileDebugKotlin`: `BUILD SUCCESSFUL`.
- `:desktop:test :desktop:packageWindows`: `BUILD SUCCESSFUL`; shared Compose app-image generated.
- Exact packaged child process smoke: new EXE launched and is responding with title `ChopLab — おとひろい PC`.
- `git diff --check`: `PASS`.

## Rollback

Rollback is the last committed branch revision before the shared migration. Do not reset/clean the canonical dirty checkout.
