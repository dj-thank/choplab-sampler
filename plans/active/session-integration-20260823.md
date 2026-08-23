# Integrate the completed ChopLab hardening and cross-platform continuity branches

## Purpose and user-visible outcome

一つのローカルcandidateで、Android 10+／Windows EXE／iOS previewのrelease・audio resource hardening、Windows Spotify metadata/control-only UX、Android由来4工程、Windowsの録音後CHOP遷移・Beatに合わせたVOICE・startup autosave・出力device failure cleanupを同時に保持する。利用者がどちらか一方の完了branchを選ばないと機能を失う状態を解消する。

## Current state

- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-session-integration-20260823`.
- Branch: `codex/choplab-session-integration`.
- Base: `codex/choplab-spotify-connect@261d034c52ebf6d767cd9a20f31c866e2fed1100` / tree `c8dd2b5898895333fc456ee0a314e79297e0b095`.
- Merged input: `codex/choplab-cross-platform-polish@df61bb50a8003e2d0417ec47955ea1dc9d52f0e0` / tree `80687542c8840bdc0124dabf9d9cd7d2b1f5d6c8`.
- Merge base: public main `9a4e9edc2686914c28c91b2d614dfb95281935c2`.
- Merge preview and actual merge agree: implementation source auto-merges; only `docs/VALIDATION.md` and `plans/active/README.md` need semantic history/current-selection resolution.
- Both source branches and the dirty canonical checkout remain unchanged.

## Constraints and invariants

- Preserve both source intents. Never resolve by taking one whole branch over the other when the behaviors are compatible.
- Spotify remains metadata/control-only. No Content download, recording, extraction, conversion, or MP3 path.
- No credential, token, signing material, user/third-party audio, or private device identifier may enter committed/public surfaces.
- Do not uninstall, clear app data, record, capture device audio, authenticate Spotify, push, publish, release, or change provider/account settings.
- Physical `DEVICE_PASS` needs exact APK/readback identity and a revision/bytes/scope/negative-path receipt. Historical branch prose alone is not sufficient.
- The canonical dirty checkout and both input worktrees are read-only boundaries for this integration.
- Once the merge starts, resolve and finish it; do not abort or rewrite source histories.

## Architecture and interfaces

The merge retains `shared/` as presentation/domain truth, `jvm-core/` as Android/Windows archive/autosave/WAV/resource-boundary truth, Android `SamplerViewModel` as mobile adapter, and `DesktopSamplerController` as the Windows lifecycle adapter. `DesktopApp` composes the Spotify panel while keeping startup autosave policy. Provider lifecycle, audio recording, playback, persistence, and UI state remain separate seams.

## Milestones

### Milestone 1: Resolve the two documentation conflicts

- Preserve both branch receipts in `docs/VALIDATION.md` as source inputs.
- Make this integration plan the only current selection in `plans/active/README.md`.
- Confirm no conflict marker remains and inspect every auto-merged implementation hunk.

### Milestone 2: Focused merged-interface verification

- Run the merged `DesktopSamplerControllerTest`, Spotify lifecycle/API tests, shared resource/format tests, Android recording/resource tests, and persistence/WAV tests.
- Fix only integration regressions with a new bounded hypothesis and test.

### Milestone 3: Full clean local gate

- Run clean Android unit/lint/debug/release/androidTest assembly, shared/JVM-core/desktop tests, Windows package, configured validation, Python release/security/public-surface tests, SBOM, UI contract validation, and `git diff --check`.
- Count tests and bind every artifact to the exact integrated source commit.

### Milestone 4: Runtime and device evidence reconciliation

- Launch the exact packaged Windows app without credentials, verify its title/responding state, and stop its tracked process tree.
- If Pixel remains unavailable, compare the integrated APK/test APK bytes to the accepted `8306ed2` Pixel receipt. Carry forward only the exact matching byte scope; otherwise leave `DEVICE_PASS` blocked.
- Never perform recording, device-audio capture, Spotify authentication, uninstall, or clear-data.

### Milestone 5: Root review and closeout

- Run separate local-parent Standards and Spec passes because the prior Luna read-only runtime gate failed and was not retried.
- Update `docs/PROJECT_STATE.md`, `docs/FEATURE_MATRIX.md`, `docs/VALIDATION.md`, this plan, PAD SSOT, and machine-readable ledgers.
- Commit locally; do not push or publish.

## Progress

- [x] 2026-08-23 — Both source branches and merge base pinned; new clean integration worktree created.
- [x] 2026-08-23 — Merge preview proved only two documentation conflicts.
- [x] 2026-08-24 — Both conflicts resolved by preserving both receipt histories and selecting this integration plan; no marker remains.
- [x] 2026-08-24 — Merged desktop, JVM-core, and Android unit suites passed before the merge commit.
- [x] 2026-08-24 — Fresh clean 184-task gate, configured validation, Python policy tests, public-surface, UI contract, packaging, SBOM, and source-bound artifacts passed.
- [x] 2026-08-24 — Packaged Windows runtime passed; exact Android source objects and APK/test APK bytes matched the accepted `8306ed2` device receipt, so only that bounded scope was carried.
- [x] 2026-08-24 — Local-parent Standards and Spec reviews completed with zero unresolved findings; repo/PAD closeout remains.

## Discoveries

- Implementation source auto-merges despite both branches editing `DesktopApp.kt` and `DesktopSamplerController.kt`; their changes are structurally compatible.
- The conflicting docs represent two valid, disjoint receipt histories. The correct resolution is a new integration current state plus retained input receipts, not choosing one history.
- Pixel is not currently attached, so fresh device execution is unavailable at integration start.
- Integrated Android/shared/JVM/build inputs are Git-object-identical to accepted source `8306ed2`; debug and test APKs are also byte-identical to its installed read-back artifacts.

## Decision log

- 2026-08-23 — Use the Spotify/full-hardening branch as base because it already contains the larger release/resource/security history; merge the smaller production-continuity branch into it.
- 2026-08-23 — Keep provider/public/Human actions excluded. Local integration does not inherit authority from historical release tasks.
- 2026-08-23 — Do not repeat the Luna probe because no runtime/config change occurred after its read-only sandbox failure; use root-only validation and local-parent two-axis review.

## Validation log

- `git merge-tree --write-tree --name-only --messages 261d034 df61bb5`
  - 2026-08-23.
  - Implementation auto-merge; conflicts only in `docs/VALIDATION.md` and `plans/active/README.md`.
- `adb devices -l`
  - 2026-08-23T23:54:51+09:00.
  - No device attached; no install or mutation attempted.
- `:desktop:test :jvm-core:test :app:testDebugUnitTest --no-daemon --max-workers=1 --no-watch-fs --console=plain`
  - 2026-08-24 after one online CycloneDX plugin dependency resolution; subsequent cache is local.
  - `BUILD SUCCESSFUL` in 2m17s. No merge-source compile or test failure.
- `clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :jvm-core:test :desktop:test :desktop:packageWindows cyclonedxBom --offline --no-daemon --max-workers=1 --no-watch-fs --console=plain`
  - 2026-08-24 from product source `6914e3c`.
  - `BUILD SUCCESSFUL` in 4m05s; 184 tasks. Android 226, JVM-core 49, desktop 66; failures/errors/skips 0; lint errors 0.
- Configured validation / Python policy / public-surface / UI contract / artifact verification
  - 2026-08-24.
  - PASS: Python 19, public candidates 355 current / 360 reachable history, packaged JAR 138 entries, UI regions 9, Android unsigned release `0.16.2 (26)`, Windows ProductVersion `0.16.2`.
- Windows packaged runtime smoke
  - responding title `ChopLab — おとひろい PC`; exact launcher/UI PIDs stopped; no credential/provider/audio operation.
- Device receipt equivalence
  - integrated `app`, `shared`, `jvm-core`, root build, settings, and Gradle properties equal `8306ed2` objects; host APK/test APK hashes equal accepted installed read-back hashes. Scoped `DEVICE_PASS` only for that exact receipt.

## Risks and rollback

- A silent auto-merge can preserve compiling code but break lifecycle ordering. Focused merged tests and actual diff inspection are mandatory.
- Source branch artifacts are not integrated artifacts. Rebuild and hash new bytes after a clean product commit.
- Docs can accidentally promote the old Pixel receipt to new bytes. Require byte equality or a fresh run.
- Rollback is to leave this integration branch unused. Do not alter or delete either input branch or the canonical dirty checkout.

## Remaining device validation

- Exact integrated APK non-recording Pixel UI/instrumentation if a device reconnects and signer/data-preservation preflight passes.
- Physical microphone/playback capture, route loss, Bluetooth, latency, audio quality, TalkBack/VoiceOver speech, Spotify provider/account/device behavior, signed distribution, and Human acceptance remain separately gated.
