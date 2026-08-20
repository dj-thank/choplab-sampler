# Android production continuity — 2026-08-20

## Purpose and user-visible outcome

ChopLabを「画面ごとの機能集」ではなく、一つの制作機としてつなぐ。起動時は前回制作または手動で選んだ`.choplab`を最初に読める。CHOPで作った波形／チョップ／4×4 PADをBEATでも同じ位置関係のまま演奏でき、新規制作はBANK Bの内蔵ドラムですぐ鳴る。スクラッチは接触から反応し、停止時は無音、解除後は直前の有効なビート再生へ戻る。

## Current state

- Target worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-app-product-intent-20260820`.
- Branch: `codex/choplab-app-product-intent`; baseline `8c12f71f7c5a699669fdf0d5392a599fb50759c3`, tree `f59dde21a9846a6f4ccb7eddb26ba6a1770332fa`.
- Dirty canonical checkout is read-only evidence and is not reset, cleaned, staged, or edited.
- `SamplerViewModel` already performs three-generation autosave recovery, but CAPTURE has no manual project-open action and workflow launch routing only distinguishes whether Source exists.
- CHOP uses a source waveform and full 4×4 `PadGrid`; default BEAT replaces that surface with `BeatLaneBoard` and a compact `BeatSoundRail`.
- Five deterministic built-in kits exist, but none is installed for a new Production.
- Scratch has bounded signed speed and an engine playhead, but drag begins after gesture slop, lacks an input dead zone/explicit return policy, and defaults to Source even when opened from a selected Beat PAD.
- Baseline `:app:testDebugUnitTest --no-daemon --max-workers=1 --no-watch-fs`: `BUILD SUCCESSFUL` on JDK 17 / Android SDK 36, 2026-08-20.

## Constraints and invariants

- Android API 29+; no storage-wide permission, DRM bypass, provider audio extraction, or capture-policy bypass.
- Restored/manual projects are exact user data. Starter drums are installed only after a confirmed empty recovery, explicit reset, or successful new-source replacement.
- Existing BANK B audio is never overwritten without the current confirmation path.
- Audio callback remains allocation-, file-I/O-, UI-, and lock-free.
- CHOP copy, four-stage copy, BANK order, PAD labels, and 4×4 topology remain authoritative from shared Android-origin source.
- Android, Windows, iOS, device, provider, public, and Human gates remain separate. This plan targets local source/tests/build and GitHub CI; Pixel 9a is not leased here.

## Architecture and interfaces

- `shared/.../model/ProductionBootstrap.kt` owns pure decisions for launch routing, starter-kit eligibility, and scratch return target.
- `shared/.../audio/BuiltInDrumKits.kt` constructs the deterministic starter BANK B state without persistence or platform effects.
- Android `SamplerViewModel` owns when a new-production bootstrap is applied, engine synchronization, autosave, and runtime scratch return execution.
- Shared `OtohiroiDeck` owns first-screen OPEN copy and BEAT's default Chop surface. Fine step controls remain an explicit secondary panel.
- Persistence schema does not change; runtime return intent is never archived.

## Milestones

### Milestone 1: Domain and pure policy

- Add red tests for launch routing, starter-kit eligibility/installation, project preservation, dead-zone speed mapping, and scratch return selection.
- Record the product/domain and reference UI contract.

### Milestone 2: Recovery and first sound

- Add first-screen `制作を開く / OPEN PROJECT` using the existing document callback and operation lock.
- Route recovered active patterns to BEAT, recovered Source work to CHOP, and a truly new Production to CAPTURE.
- Install DUSTY JAZZ and its starter pattern only for new/reset/new-source state; synchronize engine and autosave.

### Milestone 3: One Chop surface and scratch performance

- Make default BEAT retain selected-Chop waveform, BANK/page controls, and full 4×4 playable `PadGrid`.
- Keep transport/loop actions visible and move the 16-step lane/grid behind `細かく調整 / STEPS · SOUND`.
- Begin scratch on pointer-down, apply a tested dead zone/curve, default to the selected PAD in BEAT, expose direction/speed/playhead truth, and resume prior valid loop/transport on release.

### Milestone 4: Validation and delivery

- Run focused tests, full Android unit/lint/assemble, shared/desktop regression tests, project/public-surface validation, and screenshot/UI-contract review.
- Update SSOT, feature matrix, project state, plan evidence, and PAD receipt.
- Commit, push, open a GitHub PR, wait for Android/Windows/iOS checks, review the exact diff, then merge only if checks and review pass.

## Progress

- [x] 2026-08-20 — Created isolated clean worktree at exact baseline and preserved the dirty canonical boundary.
- [x] 2026-08-20 — Read current SSOT, compared CHOP/BEAT/SCRATCH reference captures, and passed baseline Android unit tests.
- [x] 2026-08-20 — Recorded the root product job and domain terms.
- [x] 2026-08-20 — Milestone 1: added red→green launch, starter, scratch-return, dead-zone, UI-surface, and archive runtime-field tests.
- [x] 2026-08-20 — Milestone 2: wired CAPTURE project OPEN, content-derived launch routing, and new-production-only DUSTY JAZZ bootstrap on Android and shared Windows UI.
- [x] 2026-08-20 — Milestone 3: made default BEAT a waveform/BANK/page/4×4 PAD Chop surface; retained responsive detailed sequencing; added pointer-down scratch, live direction/speed/playhead, idle silence, and valid loop/transport return.
- [x] 2026-08-20 — Milestone 4: local/source-bound validation, API 36 interaction evidence, two-axis review, PR #35, all PR and merged-main checks, annotated tag, Release workflow, asset/digest/anonymous HTTP read-back, and local public EXE launch completed.

## Discoveries

- The first screen can import Source audio but cannot manually open a project; project OPEN exists only in FINISH.
- Startup recovery is real, but the saved workflow stage is not part of the project. Routing must be derived from recovered audible content without a schema migration.
- BEAT currently contains a waveform and selected sounds, yet its primary surface is a four-lane step board rather than CHOP's fixed performance grid; this explains the visible discontinuity.
- `selectedDrumKitId` defaults to `dusty-jazz` even when BANK B is empty, so the field alone cannot prove installation.
- GitHub Android runs `32372699096` and `32372741818` exposed that `scripts/run_pure_logic_smoke.sh` manually listed four shared model files. `SamplerModels.kt` referenced the new `ProjectLaunchTarget`, while `ProductionBootstrap.kt` and its `PatternEditing.kt` dependency were omitted. Windows/iOS and full Gradle builds were unaffected; the smoke harness now compiles the complete shared model directory glob.

## Decision log

- 2026-08-20 — Interpret the user's “3つ目のタブ” as BEAT. Preserve the same selected-Chop waveform/BANK/page/4×4 PAD hierarchy; keep detailed steps as a secondary view.
- 2026-08-20 — Infer launch destination from project content instead of changing archive schema: user-edited audible pattern or pad-only work → BEAT, Source plus untouched starter drums → CHOP, starter-only/new → CAPTURE.
- 2026-08-20 — Use generated DUSTY JAZZ as the starter kit because it is already the default selected kit and contains no third-party recording.

## Validation log

- `scripts/doctor.ps1`: JDK 17 and Android SDK 36 present; ADB not on PATH; NDK/CMake absent and out of this Kotlin/AudioTrack slice.
- `./gradlew.bat :app:testDebugUnitTest --no-daemon --max-workers=1 --no-watch-fs`: baseline PASS, 41 tasks.
- Focused Android/shared/archive/desktop policy suites after implementation: PASS.
- `:desktop:test :jvm-core:test`: PASS after shared controller integration.
- `:app:assembleDebug`: PASS; 30,888,469-byte pre-version-bump APK installed only to `emulator-5580`.
- API 36 emulator manual evidence at parent PAD `work/CHOPLAB_APP_CONTINUITY_EVIDENCE_20260820/`: CAPTURE OPEN invokes DocumentsUI; default BEAT shows 16-pad grid; scratch motion shows `FORWARD ×0.58 / 080%`; release resumes B-01 loop; autosave relaunch routes to BEAT. This is emulator evidence, not physical-device/Human evidence.
- Full gate at candidate `1e15fe3`: Android 217 tests, JVM-core 44, desktop 35, all zero failures/errors/skips; lint 0 errors/8 warnings; APK and Windows package PASS.
- Explicit Git Bash `scripts/validate_project.sh`: PASS. API 36 `connectedDebugAndroidTest`: 4/4 PASS.
- Local parent two-axis review: Standards unresolved 0; Spec unresolved 0. Review found and fixed the Windows replacement-confirmation regression in `1e15fe3`.
- GitHub first Android attempt: deterministic RED at standalone `kotlinc` (`ProjectLaunchTarget` unresolved). Fast static repro failed with both required files absent from the smoke list, then passed after replacing the brittle list with the complete nine-file model glob; `:shared:compileKotlinDesktop` PASS.
- PR #35 final head `8d1f79c`: Android 2/2, Windows 2/2, iOS 2/2 PASS. Squash merge `64e84b82888598bf7282a92fd277b54c027c1979`; merged-main runs `32374131628`, `32374131637`, `32374131624` all PASS.
- Annotated tag object `4d881d5998381682e3739f8f0e0343d77d114f77` peels to merged commit `64e84b8`. Release workflow `32374833191` and all four jobs PASS; public prerelease `v0.16.0-preview.1` is non-draft.
- Public read-back: Android APK SHA-256 `2F04339524022F25B4D1ABB513152195C331A3C955168C4E84140D523F01E437`; Windows ZIP `60C78C1D23BB2FE959C325C3AD42995EFF2758D242ED21E90602E92CA145C27A`; Windows EXE `A69373FE39324619903D7B575509AF976CF8ED8D2A5C6921C2E18F3B40F790CF`; iOS Simulator ZIP `B704C1861477F7D5C2CD6297CAFAA5C95984B67778ED63FF4B7A8697A5008267`. GitHub digests, sidecars, authenticated download, and anonymous HTTP 200 agree.

## Risks and rollback

- Starter drums could make an empty project look user-authored or trigger source-replacement confirmation. Cover “starter-only is still new” and source replacement with pure tests before wiring.
- A tall full PAD grid can crowd BEAT controls. Preserve 4×4 topology and move detail controls behind the existing secondary action instead of shrinking interaction targets below policy.
- Automatic scratch return can restart stale content. Store only a runtime target and validate the PAD/transport immediately before restarting.
- Rollback is a normal revert of this branch's milestone commits. No persistence schema migration or destructive workspace operation is introduced.

## Remaining device validation

- Physical touch-down latency, multi-density gesture feel, audible scratch clicks, Bluetooth/wired route changes, interruption during scratch, TalkBack speech, and subjective drum/scratch quality on a leased device.
