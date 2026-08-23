# Global ProductionSession optimization — 2026-08-24

## Purpose and user-visible outcome

ChopLab's simple deck keeps one continuous Source -> Chop -> PAD -> Pattern -> Save/Recover -> Export workflow while Android and Windows stop assigning different meanings to the same edit. The first deliverable moves Source boundaries, slice markers/selection, and normal PAD performance mode behind one shared command/effect contract. This reduces long-term platform drift before Song, MIDI, effects, native DSP, or AI are added.

## Current state

- Exact root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-global-optimization-20260824`.
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`; it serializes this checkout, Pixel/ADB and GitHub writes.
- Branch/base: `codex/choplab-global-optimization` from `origin/main@ab68d2d9eaf2e5b9021a131f9ecc34d5063825bf`, tree `b3bd8be4a7cb96b3b6de54f9ff067d6457810a5f`.
- Core implementation checkpoint: `889a37a`.
- The dirty canonical checkout at `work/codex-workspace/ChopLab-Codex-Workspace` is read-only and excluded.
- v0.17.0 source, annotated tag and Windows daily install already exist. Binary GitHub Release remains fail-closed because stable Android signing secrets are absent.
- Local implementation gate, bounded Pixel slice, PR #46 and merged-main readback are green. Public main is `41be2c2`, with the exact reviewed tree `79a412c`.

## Constraints and invariants

- Source ranges are start-inclusive/end-exclusive and retain the shared minimum Chop length.
- Boundary edits use one bounded zero-crossing policy on every migrated platform.
- Slice selection is SESSION-only and does not create Undo/autosave churn.
- Normal PAD mode toggles ONE_SHOT/GATE; Beat LOOP remains an explicit separate action.
- A loop-owned PAD must stop successfully before ownership and performance mode change.
- Loading/recording block durable PROJECT edits without blocking harmless selection.
- Effect failure is reported as runtime failure; it is not silently promoted to applied success.
- No project schema change, native engine, Song, MIDI, effects, AI, UI redesign, recording, Spotify auth, data deletion, force push, tag rewrite, or partial binary publication is part of this tracer.

## Architecture and interfaces

- `shared/.../model/ProductionCommand.kt` owns the command algebra, PROJECT/SESSION/NONE classification, zero-crossing/minimum-boundary rules and typed effects.
- `SamplerDeckController` retains method compatibility while six migrated methods dispatch shared commands.
- Android `SamplerViewModel` and Windows `DesktopSamplerController` reduce the command, execute blocking `StopPad` effects before state publication, record history only for PROJECT mutation, publish state, refresh runtime adapters and schedule autosave.
- `shared/src/commonTest` runs the same reducer contract on Desktop JVM and Android host targets.
- Horizon 2 will move the remaining repeated reduce/history/publish/persist skeleton into `ProductionSession`; this tracer does not introduce a third copy.
- ADR: `docs/architecture/ADR-0001-production-command-effect-seam.md`.
- System map: `docs/architecture/global-product-optimization-2026-08-24.md`.

## Milestones

### Milestone 1: system direction and decision record

- Audit the full production flow, controller/state boundaries and platform divergences.
- Compare UI-first, native-first, DAW-breadth, release-only, current-state and shared-spine directions.
- Record metrics, staged horizons, rollback, non-goals and ADR.
- Acceptance: current plan registry selects exactly this plan and docs do not claim the target architecture is already complete.

### Milestone 2: shared semantic tracer

- Add command/result/effect types.
- Migrate range START/END, marker add/move, slice selection and normal PAD mode.
- Add minimum length, zero crossing, end-exclusive, loading/recording, loop release and failed-effect tests.
- Acceptance: reducer contracts pass on both configured shared host targets and Desktop controller tests prove history/effect integration.

### Milestone 3: full local gate and review

- Run Android/JVM/Desktop/shared tests, debug/release Lint, APKs and Windows package.
- Run public-surface history scan, policy tests, wrapper/UTF-8 and SBOM checks.
- Review Standards and Spec separately from `origin/main`.
- Acceptance: zero test failures/errors/skips, package outputs exist, reviews have no unresolved hard finding, and SSOT records the exact revision.

### Milestone 4: bounded runtime and device evidence

- Launch the exact packaged Windows candidate in a temporary data root without recording/provider actions.
- Admit one attached Pixel by serial/package/version/signer, install only with `adb install -r`, cold-launch and exercise the non-recording PAD/range slice available through existing instrumentation/UI contracts.
- Acceptance: Windows runtime and project-data negative path remain clean; DEVICE_PASS is claimed only for exact APK bytes and observed scope.

### Milestone 5: GitHub integration

- Push branch, create/read back PR, wait for all required checks, merge without force, and read back merged-main checks/tree.
- Do not create a new tag or binary Release for this architecture-only tracer.
- Acceptance: source is on main with all four workflow families green. Existing v0.17 binary signing block remains separately documented.

## Progress

- [x] 2026-08-24 02:50 JST — Replaced the local UI-improvement plan with an end-to-end production system map and direction matrix.
- [x] 2026-08-24 03:05 JST — Selected shared command/effect spine and recorded ADR-0001.
- [x] 2026-08-24 03:24 JST — Implemented checkpoint `889a37a`: six commands, two platform adapters, common tests and Desktop integration negatives.
- [x] 2026-08-24 03:35 JST — Full 152-task local Gradle gate passed.
- [x] 2026-08-24 03:42 JST — Policy, public history surface and SBOM checks passed.
- [x] 2026-08-24 03:44 JST — Resolved code/test/workflow findings in `fcbed5b`; rebuilt the ExecPlan and bound current SSOT.
- [x] 2026-08-24 03:48 JST — Exact packaged Windows launcher/UI responded in isolated data; exact processes stopped and real project digest remained unchanged.
- [x] 2026-08-24 03:52 JST — Pixel signer/version admission, `install -r --no-streaming`, byte readback, project preservation and cold launch passed.
- [x] 2026-08-24 04:15 JST — PR #46 merged as `main@41be2c2`; PR/push 8/8 and merged-main Android/Windows/iOS/Supply-chain 4/4 PASS.

## Discoveries

- Shared presentation did not imply shared behavior: range safety, play-mode transitions and selection history already differed by platform.
- `SamplerDeckController` has about 50 actions, with 54 overrides in each platform controller and 53 names in common before this migration.
- New clean worktrees do not inherit Android SDK locator files; command-scoped `ANDROID_HOME=C:/Users/rambo/AppData/Local/Android/Sdk` is required without committing `local.properties`.
- Enabling `withHostTest {}` makes common tests executable against Android main on Windows; the hosted workflows must name that task explicitly.
- Stable Android signing remains an independent release prerequisite, not a reason to weaken product or CI policy.

## Decision log

- 2026-08-24 — Choose shared command/effect spine plus capability ladder; defer UI breadth and native-engine replacement until semantic and audio parity harnesses exist.
- 2026-08-24 — Classify mutations as PROJECT, SESSION or NONE so selection/guidance cannot accidentally create history and autosave churn.
- 2026-08-24 — Keep public controller method compatibility for incremental rollback; do not rewrite both lifecycle controllers at once.
- 2026-08-24 — Execute loop-stop effects before state publication. If stop fails, keep the old project/runtime ownership and do not record history.
- 2026-08-24 — Run common contracts on both Desktop JVM and Android host, and wire both tasks into GitHub Actions.

## Validation log

- `:shared:desktopTest :shared:testAndroidHostTest :desktop:test :app:compileDebugKotlin` — PASS after failed-effect hardening.
- `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — PASS, 152 tasks; Android 226, JVM-core 49, Desktop 76, shared Desktop 10 and shared Android host 10; failures/errors/skips 0.
- `python -m unittest discover -s scripts/tests -p 'test_*.py'` — PASS, 22 tests.
- `python scripts/check_public_surface.py --history` — PASS, 374 current/reachable-history candidates; no credential, signing or audio candidate.
- wrapper SHA-256 — PASS `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`; both wrappers retain explicit UTF-8.
- `cyclonedxBom` plus `verify_sbom.py` — PASS, CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies.
- Local two-axis review checkpoint `889a37a` — four Standards and four Spec findings recorded in parent PAD `work/`; all hard/partial findings were repaired before SSOT closure.
- Review-fix focused gate — shared Desktop 12, shared Android host 12 and Desktop 76 PASS; both hosted workflows parse and invoke the relevant shared task.
- Windows packaged runtime — responding launcher/UI pair and exact title; real projects 2 files / 365,609 bytes / unchanged digest; exact tracked process tree stopped.
- Pixel receipt — `work/PAD_CHOPLAB_GLOBAL_OPT_FCBED5B_DEVICE_RECEIPT_20260824.json`; exact host/installed SHA `eada7421…`, signer match, projects 7 files / 62,592 KiB preserved, cold launch/navigation/fatal negative PASS.
- GitHub receipt — parent PAD `work/PAD_CHOPLAB_GLOBAL_OPT_GITHUB_RECEIPT_20260824.md`; PR #46, tree equality and exact merged-main runs `32660087814`, `32660087821`, `32660087857`, `32660087856` PASS.

## Risks and rollback

- Risk: controller integration logic is temporarily duplicated. Mitigation: ADR and Horizon 2 prohibit a third copy and assign consolidation to `ProductionSession`.
- Risk: a post-publication engine refresh can fail. Mitigation: preserve the project edit, publish explicit runtime failure status, and never claim applied audio success.
- Risk: zero-crossing behavior can change a Windows boundary by nearby frames. Mitigation: shared contract tests, end-exclusive bounds and Undo protect the edit.
- Rollback: revert the tracer commits. Public controller signatures and project schema are unchanged, so no user data migration or cleanup is required.
- Stop if a fix needs credentials, recording, user audio, project-data deletion, force push, tag rewrite, or signing-policy weakening.

## Remaining device validation

- Physical range/marker gesture precision and long-press ergonomics remain unobserved in this tracer.
- PAD audio output, latency/xRuns, subjective audio quality, route loss, microphone/system capture and recording are not inferred from the cold-launch receipt.
- TalkBack speech/order and HUMAN_GO remain separate human boundaries.
- Provider authentication and binary Release are not device outcomes.
