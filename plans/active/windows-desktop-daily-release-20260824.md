# Windows desktop daily-use integrated release — 2026-08-24

## Purpose and user-visible outcome

ChopLab becomes a normal daily-use Windows application on this PC: its existing simple shared deck gains real 4×4 computer-keyboard PAD performance, direct project commands and shortcuts, a reproducible self-contained app-image, Start Menu/Desktop launchers, and a public GitHub Release whose downloaded Windows bytes match the installed copy. The current safe dependency-update PR intents are integrated into the same newest source revision rather than merged blindly while red.

## Current state

- Exact implementation root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-daily-20260824`.
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`; it is the only checkout/GitHub/local-install writer for this plan.
- Branch/base: `codex/choplab-desktop-daily-release` from public `origin/main@c4956cfbf0a825dff76ee472b3a8fead9e4814ef`.
- The canonical checkout at `work/codex-workspace/ChopLab-Codex-Workspace` is operationally dirty and excluded from every write.
- Public main already contains the product integration from PR #39. Open Dependabot PRs #40–#44 are current update inputs; #42 fails because its AndroidX versions require compileSdk 37, while #43 fails because the wrapper integrity contract still pins the Gradle 9.5 JAR and its regenerated scripts drop the explicit UTF-8 JVM option. Draft PR #17 is historical 0.9.3 documentation and is not a current product input.
- `MPC Beats 2.12.3.9` was inspected read-only on this PC. Cubase is not installed, so only Steinberg's official Cubase 15 zone documentation is an admissible Cubase reference.
- Baseline Windows app-image launches and already prints `1234 / QWER / ASDF / ZXCV` on ordinary PADs, but those PC keys are not connected to PAD performance and desktop-native production commands are sparse.

## Constraints and invariants

- Preserve the shared Android/Windows deck and mobile behavior; do not add a second desktop product UI.
- Do not copy AKAI/MPC or Steinberg logos, assets, wording, project formats, or distinctive trade dress. Use only functional concepts proven relevant to ChopLab.
- `LOCAL_PASS -> DEVICE_PASS -> PROVIDER_PASS -> PUBLIC_PASS -> HUMAN_GO` remains separated. This plan targets `PUBLIC_PASS`; no fresh physical-device, provider, recording, signed-installer, audio-quality, or Human claim is implied.
- Do not activate recording, Spotify authentication, system-audio capture, or microphone capture during verification.
- Preserve `%LOCALAPPDATA%/ChopLab/projects` and every existing project/autosave. Installation is versioned and data-preserving.
- Keep minSdk 29. Any compileSdk change is a build-compatibility update, not a runtime support-floor change.
- Rollback is branch/worktree removal after preserving evidence; no reset/clean/force-checkout is used.
- Stop if a required step needs credentials outside the configured GitHub CLI, proprietary assets, app-data deletion, or unsigned-binary claims beyond an app-image preview.

## Direction matrix

| Direction | User value | Risk | Decision |
|---|---|---|---|
| Keep the simple shared deck; connect its existing 4×4 key legends and native production commands | High | Low, bounded to the Windows host/input seam | Select |
| Wrap the deck in permanent command/inspector/status rails | Medium | Adds visual and conceptual weight without improving PAD behavior | Reject after deeper MPC review |
| Recreate MPC's timeline/program layout | Medium | High trade-dress and architecture drift | Reject |
| Recreate Cubase arranger/mixer | Low for current sampler scope | Very high scope expansion | Defer |
| Only shrink the empty waveform | Low | Does not solve PAD input, commands, or installation | Reject |

Selected contract: retain ChopLab's cream/green/ink identity, shared responsive deck, four-stage workflow, and existing mobile PAD visuals. The same visible 16-PAD page is played from `1234 / QWER / ASDF / ZXCV` on Windows, where the native menu documents the PC-only map. Native Windows menus provide file/project/save/export, undo/redo, source transport, and ALL STOP without duplicating the production screen. The evidence and deferrals are recorded in `docs/research/mpc-pad-functional-model-2026-08-24.md`.

## Architecture and interfaces

- `desktop/.../DesktopPadKeyboard.kt`: pure key-to-visible-PAD mapping and modifier/repeat admission rules.
- `DesktopApp.kt`: owns native File/Edit/Transport/Integration/Diagnostics menus, shortcuts, window lifecycle, and key-down/key-up ownership for the currently visible PAD page.
- `scripts/install-windows-app.ps1`: verifies source app-image/version, stages and atomically promotes a versioned local installation, and creates user-owned shortcuts. It never deletes project data.
- Gradle/release files: integrate PR #40–#44 intents, compileSdk 37 where required, retain UTF-8, update wrapper hash, and bump product version to `0.17.0 (27)`.

## Test seams

The confirmed public seams for this plan are:

1. `desktopPadOffsetForKey` and `DesktopPadKeyOwner` for the canonical 4×4 mapping, modifiers, key repeat, focus loss, and key-up ownership.
2. Windows native package metadata and installer-script input/output/shortcut contract.
3. existing controller public actions for load/open/save/export/undo/redo/stop.
4. packaged EXE startup and exact installed/reverse-downloaded Release bytes.

## Milestones

### Milestone 1: simple functional PAD desktop input
- Add RED mapping/admission tests, then connect the smallest key-down/key-up owner to the shared controller.
- Add File/Edit/Transport menus and documented keyboard shortcuts.
- Keep Ctrl/Alt/Meta combinations out of PAD routing; suppress OS key-repeat retriggers; release the exact key-owned PAD.
- Acceptance: focused tests pass, the packaged EXE visibly preserves the shared deck, and synthetic input proves the intended PAD callbacks without capturing or recording audio.

### Milestone 2: dependency and version integration
- Integrate the exact changes from PR #40–#44.
- Raise compileSdk only where required while retaining minSdk 29.
- Restore `-Dfile.encoding=UTF-8`, update the wrapper integrity hash, and validate combined compatibility.
- Bump Android/iOS/Windows metadata to `0.17.0 (27)`.

### Milestone 3: daily local installation
- Add and test the data-preserving PowerShell installer.
- Package the Windows app-image and install it below `%LOCALAPPDATA%/Programs/ChopLab/0.17.0`.
- Create/update Start Menu and Desktop shortcuts to the exact versioned EXE.
- Launch the installed EXE through the tracked-process wrapper and verify title/path/responding state without using audio or provider actions.

### Milestone 4: full validation and review
- Run focused tests regularly, then the full Android/JVM/Desktop/package/policy/SBOM/public-surface gates once.
- Capture exact hashes and screenshots, and perform separate local Standards and Spec review passes against `c4956cf` because the Luna read-only runtime gate remains failed.
- Commit a clean, exact candidate and record receipts.

### Milestone 5: GitHub integration and Release
- Push the branch, open/read back the PR, wait for all required checks, and merge without force.
- Close PR #17 and bot PRs #40–#44 only after their exact intents are present in merged main, with supersession comments.
- Recheck merged-main CI, tag `v0.17.0`, create/read back the Release, reverse-download assets, verify sidecars and hashes, and reinstall from those downloaded bytes.

## Progress

- [x] 2026-08-24 01:07 JST — Fixed exact root, owner, base, dirty exclusion, rollback, target gate, stop boundaries, and root-only dispatch policy.
- [x] 2026-08-24 01:07 JST — Initial broad shell direction selected from a shallow Main-screen comparison.
- [x] 2026-08-24 01:35 JST — User correction stopped that direction. Read-only live `Program Edit`, `Sample Edit`, `Step Sequencer`, `Pad Mixer`, and `Pad Mute` plus Akai primary manuals established the contextual PAD-role model. The uncommitted shell implementation was removed; functional keyboard PAD input is the replacement seam.
- [ ] Implement and verify milestone 1.
- [ ] Integrate and verify milestone 2.
- [ ] Complete the local installation and runtime milestone.
- [ ] Complete full validation and two-axis review.
- [ ] Merge and verify the GitHub source and binary Release.

## Discoveries

- MPC Beats uses one 4×4 PAD surface for different explicit roles: assigned sound/clip performance, sample-boundary audition, slice selection, step entry, 16 parameter levels, note/chord mapping, per-PAD mixing, and mute targets. Context compatibility is explicit.
- ChopLab already implements the most relevant sample/PAD/step/edit roles. Its visible PC key legends are currently display-only, so connecting them has more daily-use value than adding DAW rails.
- Cubase is absent from this PC. Steinberg's current official documentation confirms a permanently shown project zone plus independently toggled left/right/lower/channel/transport zones; screenshots or installed-binary assumptions are not used.
- The present host has active Windows audio endpoints, but this plan deliberately avoids recording and physical-audio validation.

## Decision log

- 2026-08-24 — Use version `0.17.0 (27)` because this is a user-visible desktop PAD-input and distribution change, not only a dependency patch.
- 2026-08-24 — Reject the uncommitted permanent desktop shell after deeper functional inspection. Keep the shared simple deck and add real keyboard PAD performance plus native commands.
- 2026-08-24 — Consolidate bot PR intents in one tested product PR, then close the originals as superseded; do not merge red branches or historical PR #17.
- 2026-08-24 — Keep the app-image preview truthful and add a data-preserving user-local installer script instead of claiming a signed MSI/MSIX.

## Validation log

- Pending.

## Risks and rollback

- Combined Kotlin/Compose/AGP/Gradle updates may expose compatibility failures absent from isolated bot PRs. Keep dependency work in its own commit and revert that commit if primary-source-supported repair cannot make the full gate pass.
- Global key handling can conflict with native shortcuts or repeat into duplicate voices. Admission excludes Ctrl/Alt/Meta, tracks key ownership, ignores repeated key-down, and releases the exact owned PAD on key-up.
- Shortcut creation can point at stale bytes. The installer binds the complete source app-image tree to a full SHA-256 receipt and derived path, rejects a mismatched existing tree, and reads back each shortcut target.
- Public Release can succeed while assets differ locally. Promotion requires reverse download and sidecar/hash comparison before the installed copy is considered public-byte equivalent.

## Remaining device validation

- Physical Android phone layout, TalkBack speech, microphone/system-audio recording, device route loss, latency/xRuns, subjective audio quality, Spotify account behavior, code signing/reputation, and `HUMAN_GO` remain separate.
