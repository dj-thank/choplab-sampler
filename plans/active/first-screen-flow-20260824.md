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
- [x] 2026-08-24 16:08 JST — Three GitHub review threads reproduced: normal compact-landscape CAPTURE clipping, collapsed 200% BEAT quick/detail workspaces and autosave-dependent first-screen instrumentation. Reachable source repair `9b4c365` plus direct large-text navigation coverage is complete.
- [x] 2026-08-24 16:08 JST — Review-repair static gates PASS: diff check, 23 Python tests and 389-candidate public-surface scan. Fresh Gradle/AVD proof is delegated to hosted PR CI because this container lacks the Android SDK/cached Gradle and cannot download the distribution.
- [x] 2026-08-24 16:17 JST — PR #52 merged at `495ddc9`; later review repairs are bound to reachable product commit `c650d00`: large-text CHOP uses a touch-safe bounded-scroll stack, compact-landscape status remains visible in the header, and the complete shared source blob is preserved.
- [x] 2026-08-24 16:20 JST — Follow-up PR opened for all five review repairs; review replies are posted and hosted checks are the remaining integration gate.
- [x] 2026-08-24 16:43 JST — Hosted Android compile/unit/lint/APK gates passed; instrumentation exposed the test proxy returning null from `equals`. The proxy now implements identity `equals`/`hashCode`/`toString`; refreshed device execution is pending.
- [x] 2026-08-24 16:45 JST — PR #52 merged to `main@495ddc9`; final PR and merged-main Android/Windows/iOS/Supply checks PASS; provider Windows artifact `9510151389` installed data-preservingly.
- [x] 2026-08-24 16:45 JST — Post-merge review fixes normal compact-landscape CAPTURE, 200% BEAT quick/detail and autosave-independent instrumentation. Three device-test defects were caught RED and repaired; exact final instrumentation is `OK (8 tests)`.
- [x] 2026-08-24 16:45 JST — Closeout source `07f8dcf` / tree `dcd5969`: clean 191-task plus final 184-task gate, policy, exact artifacts, physical-swipe visuals and independent re-review complete.
- [x] 2026-08-24 18:20 JST — PR #62 reachable review repair source `c569604` / tree `4e7250f`: PAD-started vertical swipes cancel before selection/playback, GATE retains a real hold after scroll arbitration, compact LOOP/DRM/VOX remain in full semantics, and focused real-pointer/unit regressions cover each path.
- [x] 2026-08-24 18:39 JST — Follow-up product `4e8b62f` / tree `6ff0b00` integrates `main@a930da4` and gives every triggered deferred GATE exact-one release when TRIM recomposition cancels its pointer-input node; the real-pointer regression also preserves zero actions for an 80 ms parent-cancelled pre-activation swipe.
- [x] 2026-08-24 19:00 JST — CI located the missing Compose Test `longClick` import, and exact-head review located stale pointer routing after an in-place ONE SHOT→GATE update. Product `5b9592f` / tree `97569b6` imports the established API, keys pointer input by `pad.playMode`, makes the existing fixture prove the transition explicitly, and integrates decode-limit `main@ae77cd9` while retaining both evidence snapshots.
- [x] 2026-08-24 — Exact PR #62 head `0e94698` received clean review and four green workflows, then squash-merged as `main@6b645ca`.
- [x] 2026-08-24 — Review thread `3841779683` and all five later PR #58 follow-ups are repaired at reachable product `dfe52d7` / tree `ef1ce32`: large-text CHOP/ONE SHOT commit on completed tap, held empty CHOP captures on pointer-up, GATE activates after a 120 ms scroll window, short taps retain 80 ms, node cancellation releases exactly once, play-mode changes restart arbitration, pre-activation parent swipes dispatch zero controller calls, and normal PADs retain press-down playback.
- [x] 2026-08-24 — Android run `32716871329` compiled prior #58 head `18b815f` and reached instrumentation; its large-text target test failed RED because it measured the inner `B01` glyph. The integrated fixture now measures the PAD button semantics and independently asserts B01 visibility plus ONE SHOT/DRM semantics.
- [x] 2026-08-24 — Exact PR #66 head `7e5130f` received clean review and four green workflows, then squash-merged as `main@3260f5c`. PR #58 product `c21b6be` / tree `55aaa4d` integrates that exact main without changing the five reviewed gesture repairs.
- [x] 2026-08-24 20:21 JST — Product `ff41dcd` / tree `de9cc7c` integrates exact `main@3de1cc5` and retains PR #67's 8–192 kHz Desktop import boundary. GATE now consumes movement after activation through physical up, release generations prevent an older short preview from cutting a retrigger, and one-finger vertical waveform drags remain available to the large-text CHOP scroller. Three real-pointer regressions cover those review findings.
- [x] 2026-08-24 20:35 JST — Product `ba473e0` / tree `814e037` integrates exact `main@2786c372`, retaining PR #68's realtime PAD terminal-sample runtime/test blobs in addition to every prior gesture and Desktop import repair. Documentation was observed and anchored only after that product object existed.
- [x] 2026-08-24 20:51 JST — Product `c2e3aee` / tree `ec8702b` integrates exact `main@33308814`, preserving #68 and #70 runtime/test/docs. It closes the remaining exact-review gaps: activated quick releases retain 80 ms from actual trigger, post-activation movement suppresses TRIM without surrendering GATE, and vertical waveform pass-through is limited to the large-text scroller while non-scroll drags cannot become taps. Three additional real-pointer regressions bind those contracts.
- [x] 2026-08-24 21:13 JST — Product `79c2a8b` / tree `ba39013` integrates exact `main@3072eedd`, retaining #65 Desktop transport readiness/step-zero runtime and tests exactly together with #68 realtime PAD retirement, #70 Android import-name persistence and every reviewed PR #58 gesture repair. Documentation was updated only after this product object and its static read-back existed.
- [ ] Run hosted Android unit/androidTest and exact-head review for the current PR #58 head; local Gradle 9.7.1 acquisition is blocked by the unavailable distribution host.
- [ ] Resolve review threads and close out PR #58 only after exact-head review and required workflows pass.

## Discoveries

- Pristine starter is already export-ready with 16 assigned pads and 14 audible steps. The issue is silent context switching, not missing demo content.
- At font scale 2.0, the current one-row strip and fixed header fail visibly despite pure tests asserting fixed compact 8/9 sp values.
- Desktop `ensurePlayablePadSelected()` copies only `selectedPad`; Android calls the shared state helper that also synchronizes bank and page.
- A 360 × 640 dp viewport at font scale 2.0 cannot show all first-entry choices simultaneously without shrinking text or targets. The selected bounded-scroll exception exposes the demo on one intentional swipe while header, workflow and status remain fixed.
- The BEAT quick surface has more fixed controls than the post-chrome 360 × 640 / 200% workspace can hold; leaving timeline and PAD grid weighted collapses both. Quick and detailed bodies therefore need their own bounded-scroll policy and explicit content heights.
- A real-activity first-screen test is nondeterministic after data-preserving installs because autosave may correctly restore CHOP or BEAT. The new in-memory shared-deck fixture is deterministic and never touches retained user projects.
- A 640 × 360 dp / large-text CHOP split cannot fit its fixed left-side rows even before allocating waveform space. The large-text policy therefore switches CHOP to the same explicit-height stacked scroll boundary while normal landscape remains split.
- Compact landscape intentionally omits the separate status strip; large text also hides the original bank/BPM header label. A dedicated inline-header status policy is required so recording and transient feedback never disappear.
- A PAD `onPress` runs before its parent resolves scroll touch slop. In the two bounded large-text PAD bodies, CHOP/ONE SHOT model/audio actions therefore belong to completed `onTap`; empty CHOP pads disable the inapplicable long-press trim recognizer so a stationary hold is not dropped. Performance GATE waits 120 ms for parent cancellation, then begins while the pointer is still held and releases only at the actual pointer-up; a shorter completed tap retains an 80 ms preview. Visual pressed state may begin sooner, but a drag consumed before activation dispatches no controller action, and cancellation after activation must release ownership exactly once.
- `tryAwaitRelease()` cannot claim movement after delayed activation. The GATE detector must observe Main/Final separately before activation, then consume every Main-pass change after activation. A monotonically increasing release generation lets the newest short preview own the pad-wide release API without an older timer stopping it.
- Generic transform detection consumes a one-finger vertical pan even when only `pan.x` is applied. The waveform detector therefore resolves intent at touch slop: vertical single-pointer intent exits unconsumed, while horizontal single-pointer or multi-touch intent consumes and transforms.
- The 80 ms GATE contract is measured from the actual trigger, not from pointer-down or pointer-up. A child preview clock can outlive physical up while the generation token prevents a stale waiter from releasing a retrigger; cancellation/recomposition still releases exact once.
- Consuming post-activation movement establishes GATE ownership but must not preserve stationary long-press eligibility. Movement beyond touch slop suppresses TRIM while continuing to consume through physical up.
- Vertical waveform pass-through is safe only with the large-text CHOP scroll ancestor. Normal CHOP and PAD trim consume that drag as a no-op transform so the independent tap recognizer observes consumption and cancels.

## Validation evidence

- Historical product label: `43d8ace6aa43f3eb6e3b9dc01ea74604ee600705`, tree `798212c33d1dcc3eb52ea79fb20e13b87a9b2d9a`. It is outside the current PR/main ancestry and is retained only as externally unverifiable historical context, not current gate proof.
- Review-repair source: reachable two-parent commit `79c2a8bee5858641fcba14ea746e979c11c4b6c5`, tree `ba39013b5589bfe29eab2f77903641962df96acc`, integrating exact `main@3072eedd84b357f4ccd22c611dcc7b7f22f92874`; the final follow-up commit only binds documentation to this source. Static policy 39/39, public-surface 394, six XML parses, exact #65/#68/#70 blob preservation and diff checks pass. Hosted compile/runtime checks are pending; the out-of-ancestry 43d8ace report is externally unverifiable historical context and supplies no current proof.
- A `pointerInput` block retains the values captured when its keys were last changed. `pad.playMode` must therefore be a key so Undo/Redo or another in-place ONE SHOT/GATE update cannot keep stale routing.
- Compact large-text cells can intentionally omit secondary captions only when semantics independently enumerate both play mode and content kind; PAD assignment alone is not a complete accessible role description.
- Merged PR #62 source: reachable commit `5b9592ff27608166a99fe77af0876ad1d6b917f5`, tree `97569b6a07fb74f9ee5b59101c0ea27059259a1b`; exact head `0e94698625f676573d42e74c52bf2394e1f24fd3` passed review and all four workflows before merge as `main@6b645ca5005f905e93c572edfc1d375d4a6eeeb5`.
- Historical Gradle report: clean 191 tasks plus final incremental 184 tasks PASS; shared 25/25, Android 234, JVM-core 52, Desktop 77; failures/errors/skips 0. Its out-of-ancestry source makes it externally unverifiable here and it supplies no current gate.
- Historical instrumentation report: API 36 `OK (8 tests)` after exact data-preserving APK installs。first-screen 2本はin-memory shared-deck fixtureであり、production MainActivity/controller wiringは別のmanual cold-launch/navigation captureに限定する。このreportもcurrent source proofには再利用しない。
- Historical visual labels: parent PAD `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/accepted/` and `closeout/`; neither is current revision proof.
- Pixel 9a: disconnected; no physical-device gate promotion.
- A Windows CopyFromScreen capture was invalid at 200% DPI/off-screen placement; PrintWindow produced the accepted full-window evidence.

## Decision log

- 2026-08-24 — Keep the starter, but expose it as an explicit demo entry instead of treating enabled BEAT/SAVE tabs as self-explanatory.
- 2026-08-24 — Permit a large-text-only two-row stage strip and scrollable first-entry body. Fixed-console identity does not justify clipped accessibility content.
- 2026-08-24 — Extend bounded body scrolling to compact-landscape CAPTURE and large-text BEAT quick/detail while retaining normal-text fixed/responsive layouts and fixed global chrome.
- 2026-08-24 — Use the same bounded large-text body policy for CHOP and preserve compact-landscape status in the fixed header instead of restoring a space-consuming status strip.
- 2026-08-24 — Commit CHOP/ONE SHOT on completed tap inside large-text scroll bodies; let held empty CHOP pads complete at pointer-up; give GATE a bounded 120 ms parent-cancel window, an 80 ms short preview and cancellation-safe ownership through release; preserve press-down performance everywhere else.
- 2026-08-24 — Restart a PAD's pointer-input coroutine when `playMode` changes; displayed mode and captured gesture routing must advance together.
- 2026-08-24 — Replace deferred GATE `tryAwaitRelease()` with explicit pass-aware arbitration: yield to parent before 120 ms, consume at Main after activation, and release only on physical up or node cancellation. Serialize delayed pad-wide releases by newest trigger generation.
- 2026-08-24 — Reserve one-finger vertical waveform motion for enclosing scroll and claim transforms only for horizontal or multi-touch intent.
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
