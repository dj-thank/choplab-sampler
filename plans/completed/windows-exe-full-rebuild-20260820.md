# Windows EXE full rebuild from the Android product — 2026-08-20

## Purpose and user-visible outcome

Deliver a self-contained Windows ChopLab EXE that uses the same Android-origin おとひろい UI, exact copy, four-stage workflow, sampler state, editing rules, PAD behavior, beat construction, project persistence, recording, and export behavior. Windows-specific code is limited to audio devices, filesystem dialogs, OAuth browser handoff, and packaging.

## Current state

- Target worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-exe-20260819`.
- Branch: `codex/choplab-desktop-exe`.
- Baseline HEAD: `093511ff46463762b1fd3ca0dda056ec3173fc10`; tree `01015d706463eba6d80b4cdc773d352f6c949d0c`.
- The worktree was tracked-clean at the start of this rebuild. The separate canonical checkout is operationally dirty and outside this plan.
- `:shared` already owns the Android-origin Compose UI, four-stage copy, sampler models, workflow policies, pad editing, and sequence UI.
- `:desktop` already opens local WAV, plays source/PAD PCM, records microphone PCM, and renders four bars to WAV.
- Missing or incomplete Windows paths at baseline: `.choplab` save/open, system-audio loopback, real sequencer transport, scratch playback, edit history, complete loop/gate behavior, Spotify OAuth presentation wiring, reproducible final artifact receipt, and packaged-runtime visual/keyboard evidence.
- Existing running EXE is a baseline smoke process, not evidence for the rebuilt bytes.

## Constraints and invariants

- Android `OtohiroiDeck` and its Japanese/English copy remain the presentation source of truth. Do not introduce a separate Windows screen clone.
- Audio frame ranges are start-inclusive/end-exclusive. Project data, ZIP input, PCM allocation, and recording lifecycles remain bounded and fail-closed.
- Audio threads must not perform file I/O, UI work, unbounded allocation, or blocking provider work.
- Spotify integration is OAuth 2 Authorization Code with PKCE plus metadata/playback control only. Never download, capture, record, extract, stream-rip, or convert Spotify Content to MP3.
- Do not reproduce third-party logos, proprietary assets, firmware, project formats, or distinctive trade dress.
- Preserve the dirty canonical checkout and unrelated Android work. All mutations occur in the target worktree and are committed in reversible milestones.
- Target gate for this plan is `LOCAL_PASS`. Device/provider/public/Human gates require separate current receipts.

## Architecture and interfaces

```text
shared/
  OtohiroiDeck + exact copy + adaptive layout
  SamplerUiState + edit/history/routing/domain rules
  SamplerDeckController (platform-neutral UI command port)

desktop/
  DesktopApp (Window, file dialogs, OAuth browser handoff)
  DesktopSamplerController (state integration and lifecycle owner)
  audio/ (source/PAD/transport/scratch/record/render adapters)
  persistence/ (.choplab bounded archive adapter)
  provider/ (Spotify metadata/control session; no audio bytes)

app/
  Android platform adapters consuming the same shared presentation/domain source
```

Shared model/UI files are single-writer truth. Platform capabilities report unavailable/error states through the same visible status contract rather than silently succeeding.

## Milestones

### Milestone 1: contract and baseline

- Fix exact target revision, dirty boundary, UI contract, and current feature gap list.
- Validate the Luna project ledger and one fresh runtime probe before parallel work.
- Checks: `git status -sb`, `git rev-parse HEAD`, UI-contract validation, focused shared/desktop tests.

### Milestone 2: durable projects

- Add a bounded Windows `.choplab` archive adapter compatible with current sampler state and shared PCM.
- Wire native Windows open/save dialogs and state replacement rules.
- Test round-trip, malformed input, traversal, duplicate/missing entries, data limits, and cancellation/failure preservation.

### Milestone 3: Windows audio parity

- Add bounded system-audio loopback capture where the active Windows mixer exposes a legal loopback line.
- Replace status-only transport/loop/scratch paths with real playback state and lifecycle control.
- Reuse shared PAD pitch/tone/gain/reverse/choke rules for live and offline behavior where practical.
- Keep unavailable hardware as an explicit error; do not claim WASAPI/driver behavior without a current runtime receipt.

### Milestone 4: edit/history and provider seam

- Connect Undo/Redo and destructive edits to shared history rules.
- Wire Spotify PKCE login, current playback metadata, and playback control through a provider-neutral desktop seam.
- Keep credentials/tokens out of source, logs, project archives, and release artifacts.

### Milestone 5: product and package validation

- Run desktop/shared/Android regression tests, lint, assemble, and `jpackage` app-image.
- Stop only the exact prior ChopLab app-image process when packaging requires it, then reopen the rebuilt EXE on this terminal.
- Capture the packaged runtime at supported DPI/viewports, validate the UI contract, inspect focus/keyboard behavior, and record SHA-256 plus exact revision.
- Run independent Standards and Spec reviews, fix P0/P1 findings, commit, push, and read back the GitHub PR head.

## Progress

- [x] 2026-08-20 14:39 JST — Fixed clean target worktree, baseline revision/tree, dirty canonical exclusion, rollback, stop condition, and `LOCAL_PASS` ceiling.
- [x] 2026-08-20 14:42 JST — Created and validated the bounded project ledger (`N=8`, `C=8`, `W=4`, verifier reserve `V=2`).
- [x] 2026-08-20 — Luna reviewer returned a useful read-only gap map, but runtime-provenance verification timed out twice; the packet was rejected and all later child dispatch was stopped. Root continued sequentially.
- [x] 2026-08-20 — Moved archive/autosave/export/WAV/cursor logic into `:jvm-core`; connected persistence, driver-loopback, provider, transport/scratch, live PAD controls and edit history.
- [x] 2026-08-20 — Produced the 0.3.0 app-image, reopened it on this terminal and captured the complete maximized 200% DPI empty state.
- [x] 2026-08-20 — Opened the same retained `.choplab` project through the packaged EXE and captured the Android-matching loaded CHOP state at 200% DPI.
- [x] 2026-08-20 — Completed root Standards/Spec review, source-bound local verification, GitHub PR synchronization, all PR checks, squash merge, and all merged-main checks.

## Discoveries

- The prior visual migration succeeded because Windows now renders the same Compose source, but several controller commands still only update status text. UI source equality alone is not backend parity.
- Windows `jpackage` app-image replacement fails while the exact prior launcher/child process holds the destination; process ownership must be resolved by executable path and PID before repackaging.
- GitHub Android run `32342306585` never reached instrumentation: the API 36 emulator booted with `-accel off` and timed out after 600 seconds because KVM permissions were not enabled. The workflow now applies the official `reactivecircus/android-emulator-runner` KVM group-permission preflight in Android and release jobs.

## Decision log

- 2026-08-20 — Keep one shared UI/domain source and deepen platform adapter seams; reject a second Windows-only UI implementation because it would immediately drift from Android copy and behavior.
- 2026-08-20 — Treat Spotify as provider metadata/control only; user-selected/local/recorded audio is the only sampler source.
- 2026-08-20 — Keep iPhone framework consumption as a separate platform deliverable. This plan rebuilds the Windows EXE while preserving the shared source needed by iOS.

## Validation log

- Planning checker: `python C:/Users/rambo/.agents/skills/run-diverse-luna-project/scripts/check_setup.py --agent-role luna_builder --ledger-json C:/Users/rambo/Documents/ChatGPT/pad/work/PAD_CHOPLAB_EXE_REBUILD_LEDGER.json` — PASS on 2026-08-20.
- UI contract validator: PASS, 9 regions mapped; exact=4, semantic=4, adapted=1; 3 states.
- `:jvm-core:test :desktop:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: BUILD SUCCESSFUL on 2026-08-20.
- `:desktop:packageWindows --rerun-tasks`: BUILD SUCCESSFUL; tracked launcher PID `13920`, responding child PID `28300`.
- Packaged empty-state capture: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-maximized-20260820.png`, 1106×2202 at 200% DPI.
- Packaged loaded-state capture: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-loaded-project-20260820.png`, 1106×2202 at 200% DPI.
- `scripts/validate_project.sh`: PASS after adding a Gradle JVM-core/Desktop fallback for Windows hosts without standalone `kotlinc`; XML and wrapper digest checks PASS.
- `scripts/verify.ps1`: PASS at feature revision `6f8044b`; 116 clean-build tasks, Android app/test APK signer match, and revision-bound provenance receipt PASS.
- PR #32 head `d3e52e2`: Android/Windows/iOS checks SUCCESS ×2 each. Squash-merged main `d88f022` uses the identical tree `05e255ccb7eeba9c0cc9b939b68684229581c322`; merged-main Android run `32344903904`, Windows run `32344903955`, and iOS run `32344903922` all SUCCESS.
- Final main-tree Windows artifact: `ChopLab-Windows-0.3.0-main-d88f022.zip`, SHA-256 `9929CF01E75556735410AB2D50BD703BFCC6D66C83FC47622E3DB08E4A23CBA5`.

## Risks and rollback

- Audio-device APIs vary by driver. Fail with a clear status and preserve the project if no supported capture line exists.
- Live/offline DSP divergence can make exports sound different. Prefer one tested PCM voice implementation or explicit golden comparisons.
- OAuth browser callback and account state are provider boundaries. Unit tests can prove PKCE/request formation but not an actual account session.
- Roll back by reverting this plan's milestone commits. Never reset/clean the dirty canonical checkout.

## Remaining device validation

- Physical microphone/system-loopback audio quality, latency, route change, sleep/resume, Bluetooth, and driver loss.
- Windows 100%/150%/200% DPI and keyboard-only Human acceptance.
- Spotify account authorization/current-device behavior with the user's explicit provider session.
- Signed installer reputation and public distribution checks.
