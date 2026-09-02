# H13: verify existing Desktop long-press with real offscreen input

## Purpose and user-visible outcome

Verify Windows/JVM mouse long-press on the existing shared PAD deck opens that assigned Chop in TRIM, then waveform long-press focuses and edits the nearest boundary. This fixture adds Desktop component evidence; it does not redesign the UI or prove OS pointer delivery, physical sound, or Human quality. After the actual input RED below, root authorized only the existing Desktop `capturePad` entry to use the shared CAPTURE routing.

## Current state

- Sole writer: existing task `01a03422-fee8-74f3-82be-f50e0ebc7a56`, no descendants.
- Root: `F:/CodexWork/choplab-desktop-longpress-20260901`; branch `codex/desktop-longpress-ui-20260901`.
- Baseline HEAD `0f5b672afb0e6b67e95290c31900ff5c8abc0ef4`, tree `939f954e978b86b509ed162ed68cc4dd5f091372`; clean on creation, RAMBO/rambo owner.
- Current Compose UI 1.11.1 already exposes public `ImageComposeScene.render`, `sendPointerEvent` and `semanticsOwners`; the first approach needs no new dependency.
- `OtohiroiDeck` owns navigation and TRIM viewport; `DesktopSamplerController` owns actual selected PAD and boundary state. `SamplerCommands` supplies the current initial one-second floor / 5:4 context / source clamp and one-second precision contracts.

## Constraints and invariants

- Root accepted run03 and authorized the `DesktopSamplerController.capturePad` correction only. No other production edit is admitted.
- No JavaSound/Clip, recording, Spotify/provider, ADB/device, visible window, OS setting, public/GitHub operation, new task/Goal or fan-out.
- Only this new F lane may receive source/plan/evidence/cache writes. Primary6033, earlier creative4978, Wave20f3051, PR69 and artifacts stay untouched.
- Reuse installed JDK17 and Gradle9.7.1 read-only. Copy only dependency-cache contents into F, excluding lock/gc files; no shared-cache write or cleanup, no version change.
- `doctor.sh` was inspected: it queries ADB/auth. `validate_project.sh` invokes broad suites. `packageWindows` deletes app-image. None is admitted in H13.

## Architecture and interfaces

- Render real `OtohiroiDeck`/`ChopLabTheme` in offscreen `ImageComposeScene`, consuming actual controller StateFlow.
- Seed a small synthetic project only through public `DesktopProjectFiles.save` / `DesktopSamplerController.openProject`; no direct mutation of post-gesture state.
- Audio/recorder ports are silent/forbidden fakes; autosave is explicitly disabled, fixture files live under the F test temp directory. No DesktopApp or OAuth menu is mounted.
- Read production semantics plus actual state; drive real mouse Press/hold/Release, never `OnLongClick` semantic action or production callback directly.
- A controlled short-click failing the long-press expectation is a test-sensitivity negative control, not a product-defect RED.

## Milestones

### Milestone 1: assigned PAD tracer

- Files: `desktop/build.gradle.kts`, `desktop/src/main/kotlin/com/choplab/desktop/DesktopSamplerController.kt`, `desktop/src/test/kotlin/com/choplab/desktop/DesktopSamplerControllerTest.kt`, `desktop/src/test/kotlin/com/choplab/desktop/ui/DesktopLongPressUiTest.kt`.
- Dedicated `:desktop:desktopLongPressUiTest`, one class/fork, headless/software, no default desktop suite.
- Expected range is a worked literal: 8 kHz/10 s Source, selected Chop 2–4 s, initial viewport 1.750–4.250 s.
- Record short-click sensitivity failure separately, then actual hold result with real selection/range/viewport/layout.
- Run03 actual CHOP hold reproduced `16000..32000 -> 0..8000` before TRIM. Root read XML/render/source and authorized existing `resolvePadPressAction(..., CAPTURE, pendingSourceCommand, recordingSession)` delegation plus the existing loading guard. Shared UI/numeric/DSP code stays unchanged.

### Milestone 2: contrasts and source bounds
- Add ordinary click and empty PAD contrasts, nearer START/END and source-edge focus, one vertical case at a time.
- If production behavior fails, keep it unchanged, preserve evidence and return scope to root.

### Milestone 3: fixed candidate
- Re-run only the dedicated fixture, record XML, screenshots, command/environment, hashes and production-source unchanged readback.
- Update this plan/registry plus bounded PROJECT_STATE/FEATURE_MATRIX/evidence. Local commit only; root owns V21 Standards/Spec.

## Progress

- [x] 2026-09-01 — scope, current AGENTS/.agent/PLANS/glossary, public scene APIs and effects preflight read.
- [x] 2026-09-01 — fresh F worktree created from exact main; old primary status 8 tracked /165 untracked, status SHA256 `CAB9B1DAB6DFD83C16189DC2FC08D5583731268FF597BC295A86F6E79EA6437F`.
- [x] Run02 short-click sensitivity negative (TRIM absent); not a product defect.
- [x] Run03 actual CHOP mouse-hold product RED; before-state correct, after-state overwritten.
- [x] Run04 BEAT hold and run05 waveform END-focus controls PASS without a production change.
- [x] Run07 public controller stopped-assigned regression RED; run08 same entry plus original UI scenarios GREEN after the root-approved change.
- [x] Run09 assigned ordinary click and run10 empty click/hold controls PASS.
- [x] Run12 actual controller live-capture control PASS with a silent source port.
- [x] Runs13/14 recording and pending-import guards PASS at public controller entry points using fake I/O.
- [x] Runs15/16 Source-start/Source-end one-second floor and focus clamps PASS, including the existing START tie rule.
- [x] Run17 waveform ordinary click edits without precision zoom; run18 real mouse live-capture and run19 direct empty-PAD selection PASS.
- [x] Run20 repeats the short-click sensitivity negative on final fixture code; expected missing-TRIM failure, not a product defect.
- [x] Run21 full H13 target: 9 actual UI input tests + 5 existing-controller regressions, failure/error/skip 0, 16 tasks in 26 seconds. No other suite/package task ran.
- [x] Implementation checkpoint `1f96ef8db2e6f55efb7b8764900293338e70fd2d` / tree `738c27018ad635631847f36b5382802e2d80f1aa`; run22 exact-commit readback executes 14/14 with failure/error/skip 0 in 27 seconds.
- [x] Source/evidence hashes, rendered output, process exit and old-source readback recorded in `outputs/H13-desktop-longpress-20260901.md`; bounded SSOT/feature matrix updated.
- [ ] Independent V21 Standards/Spec: owned by root after fixed candidate; not an owner self-review promotion.

## Discoveries

- Existing C carrier required an explicit root-granted, per-command read-only safe.directory exception. Primary could create the F worktree without an exception. No config or ACL changed.
- Dedicated prior module cache contains 3,493 files /1,253,016,498 bytes; local copy only, no network acquisition planned.
- The CHOP grid invokes its capture entry on pointer-down while stopped. Unlike Android, Desktop unconditionally assigned the observed Source position there, corrupting the Chop before long-press navigation. The shared fit code was not at fault: BEAT used the same TRIM and preserved the original range.
- Run06 failed on a fixture constructor argument error, not a product bug; corrected to named PcmAudio arguments. Run11 used an incorrect assumed end for live capture; current SamplerCommands extends the last live Chop to the Source selection end, so the expected literal was corrected to 80000 without changing production.

## Decision log

- 2026-09-01 — Choose public ImageComposeScene from existing runtime before adding ui-test dependencies. Keep actual deck/controller rather than reproducing a parallel state/UI model.
- 2026-09-01 — No old Android/full-product gate. Scope-specific command and root's independent review remain separate.
- 2026-09-01 — Rank causes: (1) unconditional capture pointer-down, (2) fit mutates state, (3) bad import fixture. Correct before-state and passing BEAT/trim control exclude 2/3; run07 minimises 1 at the public controller boundary.
- 2026-09-01 — Root scope amendment explicitly permits capturePad-only shared routing; no new routing abstraction or shared UI/trim changes.

## Validation log

- Planned runner: `pwsh -NoProfile -File work/h13-local/run-gradle.ps1 -RunLabel <unique-label> [-ShortPressNegative]`.
- It invokes cached Gradle9.7.1 `:desktop:desktopLongPressUiTest --offline --no-daemon --max-workers=2 --no-watch-fs --console=plain`, JDK17, in-process Kotlin compiler, toolchain download disabled, all writable cache/temp/log in F.
- Each iteration runs only the new UI class or `DesktopSamplerControllerTest.h13CapturePad*` selection (16 Gradle tasks). This does not establish a full-product or OS/physical UI PASS.
- Run21 complete H13 target is PASS; remaining fixed-commit readback is a separate receipt. `java.awt.headless=true`, software ImageComposeScene at 1100x1000, Windows 11 10.0, Java17.0.20, actual ui-desktop1.11.1 from the F cache. UI tests took 14.343 s; controller tests 0.501 s.
- Preservation readback: primary HEAD6033/status digest still `CAB9B1DA...`; creative branch4978 and carrier f3051/treeea6645 unchanged. There is no working-tree diff in app/shared-main/jvm-core-main/ios or Desktop audio/provider/persistence. No Windows package or APK was produced.

## Risks and rollback

- Platform capability or coroutine/frame scheduling may prevent safe headless input; report the exact gap rather than fake success or launch a visible/real-audio app.
- Fixture edits are isolated and revertible by one local commit; preserve worktree/evidence, no reset/clean/broad deletion.

## Remaining review and device validation

Independent root V21 remains pending. Windows OS pointer delivery, real window/DPI/insets, physical audio, accessibility speech and Human visual/gesture acceptance are outside H13.
