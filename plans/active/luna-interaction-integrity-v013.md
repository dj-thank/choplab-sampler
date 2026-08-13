# ChopLab v0.13 interaction integrity and public preview

## Purpose and user-visible outcome

ChopLab の現在の固定コンソール UI を維持したまま、初心者が「音を入れる、波形で切る、PADで鳴らす、RECで並べる」を迷わず実行できるようにする。長押し微調整で PAD が上書きされないこと、読み替えたプロジェクトで古い A01 が鳴らないこと、録音・再生・スクラッチが表示どおりの状態で動くことを、単体テスト、APK、Pixel 9a、公開 GitHub リリースの各証拠で分離して確認する。

## Current state

- Repository: `C:\Users\rambo\Documents\ChatGPT\pad\work\codex-workspace\ChopLab-Codex-Workspace`
- Branch: `agent/gpt-pro-ui-integration`
- Starting commit: `0e50e73783ba176069d1c474b5437e98e5e7f75f`
- Baseline version: `0.12.0` / versionCode 19
- 2026-08-14 baseline reports: 38 unit-test suites, 179 tests, 0 failures/errors; Android lint 0 errors and 10 warnings; debug APK assembled. The invoking shell timed out after 120 seconds, so the final gate must also capture a direct successful Gradle exit.
- Twenty independent `gpt-5.6-luna` medium/default review packets were runtime-verified. Findings were checked against current source before adoption.
- Existing untracked `outputs/` and `work/` are evidence areas and must not be committed. Existing unrelated changes must be preserved.

## Constraints and invariants

- Preserve the no-scroll fixed mobile console, square PADs, Japanese-first copy, four-stage mental model, and project schema compatibility.
- Bank A is melody/chops; bank B is drums. A full A bank must never silently redirect to B or overwrite A01.
- A destructive capture must happen only on an explicit tap, never merely because a long press began.
- Audio callback paths may not allocate or block. Cross-thread PAD updates use fixed-capacity latest-wins state, not an unbounded queue.
- Stop-all must synchronously establish a silent transport boundary even when the bounded command queue is saturated.
- Project reset/import must not revive PADs from a previous project or audio generation.
- Public release claims remain separated: `LOCAL_PASS`, `DEVICE_PASS`, `PROVIDER_PASS`, `PUBLIC_PASS`, `HUMAN_GO`.
- Public debug signing continuity is not claimed as solved without a stable private release key. Built-in sample provenance must remain lawful and documented.

## Architecture and interfaces

- `SamplerViewModel` owns user intent, recording state, project revision, and workflow status.
- `SamplerEngine` owns the audio-thread snapshots. Control-thread PAD state becomes atomic; a fixed indexed mailbox transfers the newest PAD snapshot or clear marker to the audio thread before queued triggers are drained.
- `PadGrid` distinguishes low-latency pad triggering from destructive capture. Assigned PADs in source-playing capture mode defer triggering until a completed tap; long press opens trim only.
- Pure policy helpers cover default chop destination, record-step fallback, recording failure, workflow availability/reconciliation, scratch gesture velocity, and waveform envelopes so behavior is unit-testable without Compose or hardware.
- Persistence format is unchanged. Version bump is an application release change only.

## Milestones

### Milestone 1: Interaction truth
- Scope: destructive long press, A-bank-full behavior, beginner REC flow, stage availability, operation-specific permission copy.
- Files: `ui/PadGrid.kt`, `model/SamplerModels.kt`, `SamplerViewModel.kt`, `ui/GuidedWorkflow.kt`, `ui/OtohiroiDeck.kt`, `MainActivity.kt`, related tests.
- Acceptance: long press does not capture; full A bank does not select A01; REC starts transport and first immediate hit records deterministically; unavailable stages are visibly disabled; reset/source replacement returns to a valid stage.

### Milestone 2: Audio and recording integrity
- Scope: latest-wins PAD mailbox, transport stop boundary, STOPPING-safe recorder failure, vocal-loop cleanup.
- Files: `audio/SamplerEngine.kt`, new fixed-index mailbox/state helpers, `model/RecordingSession.kt`, `SamplerViewModel.kt`, tests.
- Acceptance: rejected/saturated command traffic cannot preserve a stale PAD or running transport; asynchronous recorder errors cannot leave UI idle while stop still owns cleanup; failed vocal recording cannot leave the monitor loop running.

### Milestone 3: Scratch, chop async safety, and waveform clarity
- Scope: event-rate-independent scratch velocity, target-correct dial, project-revision guard for transient detection, cached waveform envelope, bounded readout and accessible actions.
- Files: `ui/OtohiroiDeck.kt`, scratch helpers, `SamplerViewModel.kt`, `ui/WaveformEditor.kt`, tests.
- Acceptance: equivalent physical gestures produce equivalent scratch speed across event rates; PAD scratch uses PAD bounds; stale analysis is discarded; playhead movement does not rescan the full waveform.

### Milestone 4: Open-source and release evidence
- Scope: privacy/provenance documentation, issue templates, version 0.13.0, full local gate, independent Luna verification, Pixel data-preserving install, public GitHub preview release.
- Files: `README.md`, `NOTICE`, `PRIVACY.md`, `.github/ISSUE_TEMPLATE/*`, `app/build.gradle.kts`, evidence under `outputs/`.
- Acceptance: full Gradle gate exits 0; APK identity and SHA-256 recorded; Pixel package data hash is unchanged across `adb install -r`; CI/release assets are publicly readable and checksummed. Device UI/audio quality is claimed only if physically observed.

## Progress

- [x] 2026-08-14 — Repository, branch, dirty state, Luna runtime, and baseline reports verified.
- [x] 2026-08-14 — Twenty diverse Luna review packets completed and checked against source.
- [x] 2026-08-14 — Adopt/reject synthesis completed; false findings about REC-arm loss, pending autosave priority, and scratch smoother duration rejected.
- [x] 2026-08-14 — Milestones 1-3 implementation and targeted tests.
- [x] 2026-08-14 — Full local gate and independent Luna/code review.
- [ ] 2026-08-14 — Pixel data-preserving install and evidence.
- [x] 2026-08-14 — Commit, push, CI, public v0.13.0 preview release, and public readback.

## Discoveries

- `PadGrid` invokes `onTrigger()` from `onPress` before `onLongPress`; in source-playing capture mode this overwrites an assigned PAD before trim opens.
- `defaultMelodyChopPad()` returns index 0 when A is full, giving an unsafe default even though actual capture follows the tapped global index.
- `SamplerEngine.applyStopAllVoices()` silences voices but does not stop transport; the queued transport-stop command may be rejected under saturation.
- `controlPadKit` is changed before `SetPad`/`ClearPad` enqueue. Queue rejection can split UI/control state from audio-thread `padKit`, explaining stale sample playback.
- `toggleTransport()` already preserves `recordArmed`; the review claim that it drops arming is false. The real beginner-flow gap is that arming REC while stopped does not start transport.
- `AtomicProjectStore.load()` prefers a valid primary over pending data; the review claim that pending always revives stale state is false.
- The scratch smoother coefficient is sub-millisecond at 48 kHz, not approximately 0.8 seconds. Event-rate-dependent gesture velocity is the actionable issue.

## Decision log

- 2026-08-14 — Keep the established UI and repair behavioral truth before adding new surfaces.
- 2026-08-14 — Do not spill melody chops into drum bank B when A is full. Require the user to select an overwrite target or clear a PAD.
- 2026-08-14 — Use a fixed indexed latest-wins mailbox for PAD snapshots so stale audio cannot survive bounded queue rejection.
- 2026-08-14 — Preserve empty-sequence PLAY as valid professional behavior, but make REC auto-start and explain an empty pattern instead of blocking playback.
- 2026-08-14 — Defer audio-focus/background-mic policy and stable public signing to explicit future work; neither can be truthfully completed by UI-only changes.
- 2026-08-14 — Adopt the Spec review correction for a full Bank A: destination preparation is a pure no-op on bank and PAD selection until the user explicitly chooses an overwrite or clears a PAD.
- 2026-08-14 — Keep repository-local Luna concurrency at 20 because the user explicitly requested an approximately 20-way review fan-out; do not treat it as a product runtime setting.

## Validation log

- Command: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs`
- Date/environment: 2026-08-14, Windows, repository-bundled Android SDK/JDK configuration.
- Baseline result: report-backed 179/179 tests, lint 0 errors/10 warnings, APK generated; direct process result not retained due 120-second caller timeout.
- Final result: BUILD SUCCESSFUL, direct exit 0; 194 tests in 42 suites, failures/errors/skips 0; Lint errors 0/advisories 10; assemble PASS.
- Additional local result: configured `scripts/validate_project.sh` PASS; `git diff --check` PASS; UI scroll API scan 0.
- Exact local APK: 30,804,939 bytes; SHA-256 `3438CCD65D3C84BAEA47B9385B1EF465ED9A2E517C155D7A7E0C93E4D6FFB56B`; versionCode 20 / versionName 0.13.0.
- Emulator result: exact APK installed in place on `emulator-5588`; installed-base hash matched; three retained archives remained byte-identical; cold launch PASS; 0 scrollable nodes; 0 package exit-info crash/ANR reasons.
- Physical Pixel result: pending because serial `5A121JEBF08094` is not currently enumerated.
- Provider/public result: branch, PR, tag, and release runs `31724970140`, `31724972880`, `31725302532`, and `31725302549` PASS; annotated tag peels to `61f1044610ee172785d87478659862fb4f342be3`.
- Public artifact: 30,804,939 bytes; SHA-256 `B25E018C8743D9EC7459FDDF5698F008E41D34D7FB34336961865B34F867C86A`; GitHub digest, sidecar, authenticated and anonymous downloads match; repository/Release/APK return HTTP 200.

## Risks and rollback

- Audio-thread mailbox ordering can alter which sample a simultaneous update/trigger hears. Apply pending PAD updates before trigger commands and cover this ordering with tests.
- Deferring destructive capture from press to tap can add release latency only in capture mode; normal performance triggering remains on press.
- REC auto-start changes an established control. Disarming remains non-destructive and tests define first-step behavior.
- If a milestone fails, revert only this plan's commit(s); never reset the user's unrelated working tree or delete evidence directories.

## Remaining device validation

- Confirm Pixel identifier and installed package before mutation.
- Hash the app's accessible autosave bytes before and after `adb install -r`.
- Launch and verify no startup crash; inspect logcat for package-scoped fatal errors.
- Human checks still required when possible: long-press does not recapture, immediate REC hit lands at step 1, source replacement removes old A01, recorder stop stays coherent, scratch feels consistent, and audio is not doubled.
