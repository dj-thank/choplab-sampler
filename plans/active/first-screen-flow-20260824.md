# Make the first screen and production path immediately understandable

## Purpose and user-visible outcome

A first-time user sees four distinct entry choices—own audio, an existing project, recording, or the included DUSTY JAZZ demo—without an empty waveform dominating the screen. The shared `入れる → チョップ → ビート → 保存` path remains visible on Android and Windows, survives large text, and keeps the visible bank/page aligned with the selected playable PAD.

## Current state

- Clean worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-screen-flow-20260824`.
- Baseline source: `3cc4cd5c22afca08074f405b8a61658652b2aec1`, tree `e973f0f9bd939dc92f8a658afa0feedd6954ad2f`.
- Runtime audit: parent PAD `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/BASELINE_AUDIT.md`.
- Android normal text is readable, but the empty first screen is waveform-heavy and silently exposes starter BEAT/SAVE.
- Android font scale 1.3 ellipsizes first actions; font scale 2.0 clips core chrome and actions.
- Windows runtime selected `B-01` on BEAT entry without synchronizing the visible bank/page because the desktop controller bypasses the shared selection transition.

## Constraints and invariants

- Preserve the original console visual language, four stages, Japanese-first copy and 48 dp minimum actions.
- MPC/Cubase are functional references only. Do not copy assets, wording, project formats or trade dress.
- Keep the DUSTY JAZZ starter and default pattern unchanged.
- Do not change audio rendering, project schema, persistence semantics, recording safety or provider behavior.
- Loading and active-recording screens must retain their current truthful STOP/WAIT controls.
- The dirty canonical checkout remains untouched. All changes live in this clean worktree.
- Rollback is deletion of this isolated branch/worktree or reversion of its focused commits; never reset/clean the canonical checkout.
- Stop if a safe large-text layout requires weakening touch targets, hiding stop controls, or changing audio/project truth.

## Architecture and interfaces

- `GuidedWorkflow.kt` owns pure entry/large-text presentation policy.
- `OtohiroiDeck.kt` owns the responsive first-entry composition, large-text header and stage-strip rendering.
- `SamplerCommands.ensurePlayablePadSelected` remains the single model transition for selected PAD/bank/page coherence.
- Android and Desktop controllers adapt I/O only; both call the shared model transition.
- No persistence migration is required. Entry-screen/demo choice is presentation-only.

## Milestones

### Milestone 1: Lock the behavior contract with RED tests

- Add pure tests for pristine entry presentation, loaded/recording fallbacks, large-text workflow rows and header content.
- Add a Desktop regression proving BEAT-entry playable selection updates selected PAD, bank and page together.
- Acceptance: focused tests fail against baseline for the newly required behavior.

### Milestone 2: Implement focused entry and adaptive chrome

- Replace the pristine empty waveform with a focused, responsive entry surface.
- Add explicit starter-demo CTA and copy.
- Render workflow stages in two rows and simplify the machine header only at large text.
- Route Desktop playable selection through the shared model function.
- Acceptance: focused tests pass; normal 1.0 layout remains unchanged outside pristine first entry.

### Milestone 3: Verify product behavior and fresh visuals

- Run shared Android-host/Desktop, Android unit, JVM-core, Desktop and full packaging gates as appropriate.
- Install the exact debug APK data-preservingly on the dedicated emulator; do not uninstall or clear.
- Recapture Android 1.0/1.3/2.0 and Windows first/BEAT states; inspect every accepted image.
- Verify visible labels, selected states, no clipping and the desktop bank/page correction.
- If Pixel reconnects, use only signer-admitted `adb install -r --no-streaming` and non-recording navigation; otherwise report emulator-only.

### Milestone 4: Review and integrate

- Run Standards and Spec reviews against this plan and fresh evidence.
- Update PROJECT_STATE, FEATURE_MATRIX, validation docs and this plan.
- Commit, push, PR, wait for applicable checks, merge only after clean read-back, then verify exact main workflows.
- No tag or binary Release in this plan.

## Progress

- [x] 2026-08-24 14:15 JST — Current source/dirty boundary fixed; Product Design combined audit captured Android and Windows baseline states.
- [x] 2026-08-24 14:15 JST — Selected explicit-entry + adaptive-large-text direction; strict lock and DAW dashboard rejected.
- [x] 2026-08-24 15:10 JST — Focused RED tests reproduced missing entry policy, large-text rows and Desktop bank/page coherence.
- [x] 2026-08-24 15:52 JST — Shared entry/chrome policy, Desktop shared selection and focused GREEN complete at reviewed product commit `43d8ace`.
- [x] 2026-08-24 15:52 JST — Clean 191-task gate plus final 184-task incremental cross-platform gate, policy gates, exact hashes and final Android/Windows visual regression PASS.
- [x] 2026-08-24 15:52 JST — Exact final API 36 debug/test APK data-preserving install and full seven-test instrumentation PASS; portrait and 640 × 360 dp landscape large-text scroll verified and emulator settings restored.
- [x] 2026-08-24 15:52 JST — Independent verifier's 40 dp compact-landscape finding reproduced RED, repaired to stage 49 dp / demo 59 dp, and re-observed on exact final APK.
- [x] 2026-08-24 16:45 JST — PR #52 merged to `main@495ddc9`; final PR and merged-main Android/Windows/iOS/Supply checks PASS; provider Windows artifact `9510151389` installed data-preservingly.
- [x] 2026-08-24 16:45 JST — Post-merge review fixes normal compact-landscape CAPTURE, 200% BEAT quick/detail and autosave-independent instrumentation. Three device-test defects were caught RED and repaired; exact final instrumentation is `OK (8 tests)`.
- [x] 2026-08-24 16:45 JST — Closeout source `07f8dcf` / tree `dcd5969`: clean 191-task plus final 184-task gate, policy, exact artifacts, physical-swipe visuals and independent re-review complete.
- [x] 2026-08-24 17:40 JST — PR #62 reachable review repair source `0a7d340` / tree `5d7fb0e`: PAD-started vertical swipes now cancel before selection/playback, compact LOOP/DRM/VOX remain in full semantics, and focused real-pointer/unit regressions were added while integrating `main@8fa1dac`.
- [ ] Run hosted Gradle plus the focused API 36 gesture test for the review-repair head; local wrapper acquisition is blocked by the unavailable Gradle distribution.
- [ ] Closeout PR and merged-main read-back.

## Discoveries

- Pristine starter is already export-ready with 16 assigned pads and 14 audible steps. The issue is silent context switching, not missing demo content.
- At font scale 2.0, the current one-row strip and fixed header fail visibly despite pure tests asserting fixed compact 8/9 sp values.
- Desktop `ensurePlayablePadSelected()` copies only `selectedPad`; Android calls the shared state helper that also synchronizes bank and page.
- A 360 × 640 dp viewport at font scale 2.0 cannot show all first-entry choices simultaneously without shrinking text or targets. The selected bounded-scroll exception exposes the demo on one intentional swipe while header, workflow and status remain fixed.
- A large-text BEAT body has more fixed controls than the remaining workspace. Weighted timeline/PAD children collapse; explicit heights plus bounded body scrolling preserve 48 dp PADs and all lower controls.
- A retained-data Activity test may correctly restore CHOP/BEAT. The deterministic first-screen test must render a starter-only CAPTURE state in memory and give its dynamic controller proxy stable `equals/hashCode/toString` behavior.
- A PAD `onPress` callback runs before a parent scroll recognizes touch slop. In a bounded scroll body, model/audio side effects therefore belong to the completed `onTap` boundary; visual pressed state may begin earlier, but a consumed drag must dispatch no controller action.
- Compact large-text cells can intentionally omit secondary captions only when semantics independently enumerate both play mode and content kind; PAD assignment alone is not a complete accessible role description.

## Validation evidence

- Product anchor: `07f8dcf3c2b0fe17c1e1d8ed3d135728c18f0c96`, tree `dcd5969bf72ceab1facbceb43c3fe63a9df99b4d`.
- Review-repair source: `0a7d340958fffa733f7e40deff98eff4e261e99a`, tree `5d7fb0e99131b50f5154ee71b9ae6e3eb788aaac`, integrating original PR #62 head with `main@8fa1dac79b76f851e035cd8abaa5db8f9b1f5532`; static policy 34/34, public surface 394 and diff check PASS. Gradle/device execution remains pending.
- Gradle: clean 191 tasks plus final incremental 184 tasks PASS; shared 25/25, Android 234, JVM-core 52, Desktop 77; failures/errors/skips 0.
- Instrumentation: API 36 `OK (8 tests)` after exact data-preserving APK installs。first-screen 2本はin-memory shared-deck fixtureであり、production MainActivity/controller wiringは別のmanual cold-launch/navigation captureに限定する。
- Visuals: parent PAD `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/accepted/` and `closeout/`.
- Pixel 9a: disconnected; no physical-device gate promotion.
- A Windows CopyFromScreen capture was invalid at 200% DPI/off-screen placement; PrintWindow produced the accepted full-window evidence.

## Decision log

- 2026-08-24 — Keep the starter, but expose it as an explicit demo entry instead of treating enabled BEAT/SAVE tabs as self-explanatory.
- 2026-08-24 — Permit a large-text-only two-row stage strip and scrollable first-entry body. Fixed-console identity does not justify clipped accessibility content.
- 2026-08-24 — Extend bounded body scrolling to compact-landscape CAPTURE and large-text BEAT quick/detail; keep normal-text responsive composition and global chrome fixed.
- 2026-08-24 — In a scrollable BEAT body, defer PAD model/audio actions to completed tap; preserve direct press performance everywhere else.
- 2026-08-24 — Treat play mode and content kind as semantic PAD identity even when compact layout hides their secondary visual caption.
- 2026-08-24 — Keep existing loaded-source workspaces unchanged unless fresh post-implementation screenshots show a regression.

## Validation log

- Baseline API 36 screenshots: `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/baseline/android/`.
- Baseline Windows screenshots: `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/baseline/windows/`.
- Luna runtime probe P-01: verified `gpt-5.6-luna` / medium; effective sandbox writable, behavioral read-only respected; packet used as source mapping only.

## Risks and rollback

- Large-text chrome may consume too much workspace height. Keep the two-row rule limited to font scale >= 1.2 and verify 1.0 screenshots unchanged.
- First-entry simplification must not hide recording STOP/WAIT states; use it only for idle, no-source, non-loading state.
- Demo CTA must not mutate starter audio/pattern. It only selects the shared playable PAD and changes the local workspace stage.
- Desktop selection fix could affect saved selection expectations; cover existing manual bank/page tests plus the new playable-selection regression.

## Remaining device validation

- Pixel 9a is disconnected at baseline. Physical touch, TalkBack speech, audio quality, latency, recording and route-loss remain separate.
- iOS native UI parity is outside this Android/Windows flow plan.
- Spotify provider behavior, binary Release and HUMAN_GO remain outside scope.
