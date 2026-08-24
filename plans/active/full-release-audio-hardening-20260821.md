# Make ChopLab releases reproducible, bounded, and recoverable

## Purpose and user-visible outcome

Complete the full hardening pass identified by the 2026-08-21 repository review. A completed release must be internally versioned from one source of truth, publish a non-debuggable Android artifact, reject oversized or unsafe audio before exhausting memory or disk, keep iOS source and playback state truthful, make Spotify disconnect authoritative, compile every declared Kotlin target in CI, and ship immutable evidence for every public artifact.

The user-visible result is a preview build that fails closed instead of silently corrupting audio, stops recordings at a documented limit, does not accumulate orphaned iOS source files, never reconnects Spotify after an explicit disconnect, and reports the same version on Android, Windows, iOS, filenames, and release metadata.

## Current state

- Baseline branch: `main`.
- Baseline commit: `9a4e9edc2686914c28c91b2d614dfb95281935c2`.
- Isolated implementation branch: `codex/choplab-full-hardening-20260821`.
- Android currently publishes `app-debug.apk`; debug-only Compose tooling is consequently present in the published merged manifest.
- Android reports `0.16.1` / build `25`, Windows defaults to `0.3.0`, and iOS defaults to `0.15.0` / build `23`.
- Desktop WAV import calls `readBytes()`. Recorders have no shared duration, byte, or free-disk budget. RIFF overflow is silently clamped at close.
- The iOS preview copies every imported source to a new UUID path, does not retire the previous source, can overwrite an import failure with a success status, and leaves playback flags active after natural completion.
- Spotify credentials are memory-only, but asynchronous login/refresh can commit after `disconnect()`, and the loopback receiver lets the first wrong-state callback consume the one-shot result.
- `shared` declares iOS Kotlin/Native targets, but iOS CI currently builds only the Swift preview.
- `main` is currently unprotected. Repository-setting mutations are outside source commits; the final validation record must distinguish source-enforced controls from GitHub administrator controls and verify the latter before claiming completion.

## Constraints and invariants

- Android `minSdk` remains 29 and target/compile SDK remain 36.
- Existing `.choplab` schema compatibility is preserved. Runtime resident-memory limits may be stricter than the archive format hard ceiling, but must be explicit at each platform boundary.
- Audio frame ranges remain start-inclusive/end-exclusive.
- The real-time callback must not perform file I/O, allocation-heavy work, blocking locks, or UI calls.
- Recording limits must stop cleanly and retain a valid file; a limit is not treated as data corruption.
- A disk-write or codec failure deletes the incomplete output and returns a truthful error.
- OAuth authorization codes and tokens remain memory-only and must never be logged.
- Public releases are immutable: an existing tag/release is an error, never a target for `--clobber`.
- Public Android signing uses a stable external key. No key, certificate, password, token, provisioning profile, or generated signing material may be committed.
- Physical-device, provider-account, signed iOS-device, subjective audio, and human-acceptance gates remain separate from source and CI validation.

## Architecture and interfaces

- `gradle.properties` owns `choplabVersion` and `choplabBuildNumber`. Android and Windows read those properties. iOS build scripts receive and verify the same values.
- `shared` owns portable `AudioResourceLimits`, `RecordingBudget`, and PCM identity generation. Platform adapters enforce those limits before and during I/O.
- `jvm-core` owns RIFF boundary enforcement and bounded JVM PCM helpers. `ProjectArchiveCodec.read` accepts a platform resident-memory budget while retaining the archive-format hard ceiling.
- Android microphone and playback-capture adapters, and the Windows TargetDataLine adapter, stop normally at the shared recording budget and fail closed on low disk or write failure.
- iOS source replacement uses a staged candidate: validate, open, commit, then delete the previous source. Playback completion is generation-checked so stale callbacks cannot clear newer playback state.
- Spotify session writes are generation-checked. Disconnect increments the generation and closes the active callback receiver. The callback receiver validates state before completing its future.
- Shared pad DSP primitives are used by real-time and offline paths where the current architecture allows; equivalence tests guard pitch/reverse/tone/gain behavior.
- Large controllers are decomposed at ownership seams rather than reformatted wholesale: resource-limit policy, recording lifecycle, provider session state, and project-loading policy move into focused classes with host tests.

## Milestones

### Milestone 1: Reproducible, non-debug public artifacts

- Scope: one version source, Android release variant, signing boundary, artifact inspection, immutable release publication.
- Files/interfaces expected to change: `gradle.properties`, `app/build.gradle.kts`, `desktop/build.gradle.kts`, `ios/project.yml`, `scripts/build-ios-simulator.sh`, `.github/workflows/release.yml`, release verification scripts/tests.
- Implementation steps:
  1. Read version/build properties from all platform builds.
  2. Build and publish a non-debuggable Android release APK.
  3. Require a stable signing secret for tag releases and verify the certificate fingerprint.
  4. Extract and compare Android, Windows, and iOS embedded versions with the tag.
  5. Reject existing releases/assets instead of overwriting them.
  6. Generate checksums, SBOM/provenance metadata, and commit identity metadata.
- Tests/checks: release-script unit tests; merged-manifest permission/export allowlist; APK debuggable/version/certificate checks; iOS Info.plist check; Windows jpackage version check.
- Acceptance evidence: CI artifacts whose embedded versions match the release tag and whose Android merged manifest contains no debug/test tooling surface.

### Milestone 2: Bounded audio import, recording, WAV, and project residency

- Scope: desktop streaming decode, shared limits, clean recording cutoff, RIFF overflow, low-disk handling, platform project memory budgets.
- Files/interfaces expected to change: shared audio policy files, `AudioDecoder.kt`, `DesktopWavDecoder.kt`, `WavFileWriter.kt`, Android/Windows recorder adapters, `ProjectArchiveCodec.kt`, Android/desktop project-open seams.
- Implementation steps:
  1. Introduce portable duration/frame/byte/free-disk limits.
  2. Replace desktop whole-stream allocation with chunked downmix into a bounded builder.
  3. Reject RIFF overflow before writing bytes; sync completed files.
  4. Stop recorders at a valid aligned boundary and retain a valid WAV.
  5. Reserve disk headroom and delete partial output on actual failures.
  6. Apply a mobile resident-memory budget when opening projects.
- Tests/checks: boundary arithmetic, oversized declared/unknown streams, partial frames, limit cutoff, low-disk policy, RIFF maximum, project memory budget.
- Acceptance evidence: JVM/Android tests demonstrate rejection before unbounded allocation and valid headers at the largest accepted boundary.

### Milestone 3: iOS/Kotlin Native and OAuth lifecycle truth

- Scope: declared iOS KMP targets, iOS file lifecycle/playback truth, Spotify generation/callback validation.
- Files/interfaces expected to change: `SamplerModels.kt`, iOS CI, `SamplerStore.swift`, iOS policy/tests, Spotify session/callback/tests.
- Implementation steps:
  1. Replace JVM-only common identity generation.
  2. Compile/link `ChopLabShared` for iOS Simulator in CI.
  3. Stage and atomically replace iOS sources; remove stale/failed candidates.
  4. Use bounded iOS recording and generation-checked playback completion.
  5. Make Spotify disconnect and close invalidate all pending writes.
  6. Ignore wrong-state loopback callbacks without consuming the valid callback.
- Tests/checks: Kotlin/Native framework link, Swift policy tests, callback wrong-state-then-valid integration test, session-generation unit tests.
- Acceptance evidence: iOS and desktop CI pass with the new lifecycle tests.

### Milestone 4: Shared DSP seams and controller decomposition

- Scope: remove avoidable real-time/offline DSP drift and shrink ownership concentration without changing product workflow.
- Files/interfaces expected to change: shared/JVM DSP primitives, `PatternRenderer.kt`, Android/desktop playback adapters, focused coordinator/policy classes and controller call sites.
- Implementation steps:
  1. Identify sample-rate/pitch/reverse/tone/gain primitives duplicated between playback and rendering.
  2. Extract deterministic allocation-free per-voice primitives.
  3. Use the same primitives in offline rendering and platform playback where thread constraints permit.
  4. Extract recording/project/provider ownership from large controllers into testable coordinators.
- Tests/checks: golden/equivalence PCM tests, thread-safe command/state tests, existing UI and controller suites.
- Acceptance evidence: real-time and offline paths produce equivalent samples for the covered controls, and controllers delegate lifecycle ownership to focused classes.

### Milestone 5: Supply-chain, repository, and recovery controls

- Scope: CI coverage, secret scanning, dependency evidence, CODEOWNERS, SECURITY policy, autosave durability, GitHub administrator controls.
- Files/interfaces expected to change: workflows, scanner scripts, `CODEOWNERS`, `SECURITY.md`, dependency/SBOM configuration, `AtomicProjectStore.kt`, documentation.
- Implementation steps:
  1. Include `shared/**` and `jvm-core/**` in Windows CI triggers.
  2. Add source-controlled secret scanning and dependency/SBOM evidence.
  3. Add ownership and vulnerability-reporting policy.
  4. Sync the parent directory after atomic store replacement where the platform permits.
  5. Configure and verify main/tag protection, required checks, and review requirements through repository administration; do not claim this from source changes alone.
- Tests/checks: scanner fixtures, dependency report generation, autosave fault/boundary tests, actual branch/ruleset read-back.
- Acceptance evidence: protected main/tag refs with required Android/Windows/iOS checks and review policy, plus passing source-controlled checks.

### Milestone 6: Integrated validation and pull request

- Scope: full regression gate, documentation, PR review, CI failure correction.
- Files/interfaces expected to change: project state, feature/test docs, this plan and registry.
- Implementation steps:
  1. Run the smallest focused suites after each milestone.
  2. Run project validation, Android unit/lint/build/instrumentation, JVM core, desktop test/package, Kotlin/Native link, and iOS tests in GitHub Actions.
  3. Inspect built artifacts rather than inferring properties from source.
  4. Open a non-draft PR with exact limitations and evidence.
  5. Correct all deterministic failures before merge. Device/provider/human work remains explicitly unpromoted.
- Acceptance evidence: all PR checks green, artifact inspection receipts attached, no unresolved review finding.

## Progress

- [x] 2026-08-21 — Confirmed repository, permissions, baseline commit, unprotected main state, and existing open PR boundary.
- [x] 2026-08-21 — Created isolated branch `codex/choplab-full-hardening-20260821` from `9a4e9edc`.
- [x] 2026-08-21 — Mapped release, audio import/recording, project archive, iOS, OAuth, KMP, CI, and governance seams.
- [x] Milestone 1 source implementation and focused tests; final-APK inspection now permits the AndroidX profile installer receiver only when it retains the platform `android.permission.DUMP` guard.
- [x] Milestone 2 implementation and focused tests.
- [x] 2026-08-24 follow-up — corrected the low-sample-rate duration bypass with a shared `min(global frames, sampleRate × 600)` policy and applied it to Android/Desktop known, unknown and post-decode boundaries. Local Gradle execution is explicitly deferred to hosted CI because the sandbox has no usable wrapper distribution.
- [x] Milestone 3 implementation and focused tests.
- [ ] Milestone 4 shared-DSP extraction/equivalence work; resource and lifecycle seams are complete, but this larger refactor remains deferred.
- [ ] Milestone 5 administrative read-back; source-controlled scanning, SBOM, CODEOWNERS, security policy, and autosave durability are implemented.
- [x] Full source-controlled CI rerun and final artifact inspection at `0dc4a215`; documentation closeout is recorded here.
- [ ] Human PR review and repository-administrator ruleset read-back remain external gates.

## Discoveries

- The Android `release` build type already exists; the public workflow explicitly chooses `assembleDebug`, so the primary public-APK defect is a release-pipeline selection error rather than a missing build type.
- The archive format hard-bounds total PCM at 512 MiB, but decoding materializes `ShortArray` assets. A lower platform resident-memory budget is needed without changing archive compatibility.
- Source code can add CODEOWNERS and required checks, but branch/tag protection is a GitHub repository setting. Completion requires actual API/UI read-back of that setting.

## Decision log

- 2026-08-21 — Use `0.16.2` / build `26` as the next unified baseline rather than rewriting already-published `0.16.1` artifacts.
- 2026-08-21 — Preserve debug builds for developer instrumentation, but never publish them as release assets.
- 2026-08-21 — Treat recording-budget completion as a valid successful file, while disk/codec/read failures remain destructive failures.
- 2026-08-21 — Keep the 512 MiB archive compatibility ceiling and add stricter platform runtime budgets at load boundaries.
- 2026-08-21 — Prefer focused seam extraction over a high-risk wholesale rewrite of the 100k-line Android ViewModel and 150k-line shared UI file.

## Validation log

- Follow-up implementation `9f01f42` / tree `1071acb` — allocation-free shared arithmetic tests cover exact 8 kHz and 48 kHz boundaries plus next-frame rejection; Android and Desktop focused tests cover effective streaming-limit changes and unknown-duration streams. Public-surface 389 candidates and `git diff --check` PASS. Gradle wrapper provisioning was unavailable locally, so no test-pass promotion is claimed before hosted CI.

- Baseline source mapping — 2026-08-21 — GitHub read-back at commit `9a4e9edc`; no fresh build claim.
- PR head `d871baf` — Supply-chain policy run 32468989684 PASS; Windows run 32468989763 PASS; iOS run 32468989677 PASS. Android compilation, tests, lint, and release assembly passed, then final-APK inspection exposed the missing guarded-receiver policy case.
- PR head `0dc4a215` — [Android run 32487125343](https://github.com/dj-thank/choplab-sampler/actions/runs/32487125343) PASS, including release-policy tests, unit tests, lint, unsigned release assembly, final release-APK inspection, and API 36 instrumentation/accessibility tests.
- PR head `0dc4a215` — [Windows run 32487125323](https://github.com/dj-thank/choplab-sampler/actions/runs/32487125323) PASS; [iOS run 32487125298](https://github.com/dj-thank/choplab-sampler/actions/runs/32487125298) PASS, including the Kotlin/Native framework and Simulator tests/build; [supply-chain run 32487125397](https://github.com/dj-thank/choplab-sampler/actions/runs/32487125397) PASS, including complete-history scanning and a non-empty CycloneDX SBOM.

## Risks and rollback

- Release signing can fail when secrets are absent. Workflow dispatch may build an explicitly unsigned local/CI candidate, but tag publication must fail closed without the configured stable key.
- Kotlin/Native compilation may expose additional JVM-only common code. Fix portable code; do not remove the target to make CI green.
- Audio boundary changes can alter tests that assume unlimited recording. Preserve valid existing files and expose deterministic policy helpers for testing.
- iOS playback completion callbacks can arrive after a newer playback command. Every callback must compare a captured generation before mutating state.
- Rollback is branch deletion or reverting milestone commits. Never force-update `main`, rewrite published tags, or delete historical evidence.

## Remaining device validation

- Physical Pixel microphone, playback-capture, route-loss, Bluetooth, thermal, long-duration, and low-storage behavior.
- Physical Windows render/capture endpoint behavior, audible quality, device removal, and actual code-signing UX.
- Physical iPhone/iPad microphone, interruption, background/foreground, and signed IPA distribution.
- Spotify account/device authorization, cancellation, expiry, pause/resume, and disconnect behavior with a real provider session.
- Subjective real-time/offline audio equivalence and human acceptance.
