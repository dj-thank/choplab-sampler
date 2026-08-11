# v0.11 Safe project handoff and first-beat coaching

## Purpose and user-visible outcome

Make ChopLab safer and easier in the first ninety seconds without adding scrolling or hiding the performance controls. A user can understand waveform seek, live chop, PAD audition, whole-chop looping, pattern placement, drums, voice, and Scratch from the fixed console. Starting a new source cannot silently replace an existing composition, and stale asynchronous work cannot restore or overwrite an older project after reset or a newer edit.

## Current state

The build target is `app/` at `2c30fc3951f5ea53c52492a100619cd055697ea9`. Version 0.10.0 already provides the four-stage fixed console, A/B/C/D banks, two 16-PAD pages, whole-chop loop, pattern placement, Layer Studio, source/PAD Scratch, autosave, manual archive save, and complete project replacement on successful source import.

The 2026-08-12 baseline audit used a dedicated Pixel 9 / API 36 emulator at 1080 x 2424. Capture, Chop, Beat, Layer Studio, and Scratch fit without scrolling. The accepted pre-change screenshots are under `work/v011-audit/`. One earlier screenshot containing an Android `System UI isn't responding` dialog is rejected and must not be used as product evidence.

Repository baseline checks passed after explicitly selecting the workspace JDK, Kotlin compiler, Android SDK, and Gradle cache:

- `scripts/doctor.sh`: required Java, Android platform/build-tools/platform-tools, adb, wrapper, and repository checks available; optional NDK/CMake absent and not used by this AudioTrack milestone.
- `scripts/validate_project.sh`: PASS.

Two independent read-only Sol audits found the highest-value bounded work:

- warn before a new source discards current PADs and Beat work;
- expose waveform seek, PAD audition/trim, and the Beat order in existing compact guidance;
- rename the whole-chop action so it cannot be confused with a sequencer pattern loop;
- invalidate stale source decode/load completions after reset or a newer selection;
- stop older autosave snapshots from replacing newer revisions;
- prevent an old source voice completion from publishing a stopped state for a newer play generation;
- normalize non-finite Scratch speed at both the control and render boundaries.

## Constraints and invariants

- Keep `minSdk=29` and the current Kotlin/Compose plus AudioTrack architecture.
- Keep the canonical cream/orange/green hardware-deck visual language and the original HTML mental model.
- Add no scroll container and no Figma dependency.
- Keep audio frame ranges start-inclusive and end-exclusive.
- Do not add locks, waits, file I/O, logging, or per-block allocation to the realtime render loop.
- A cancelled picker or failed decode leaves the current project untouched.
- A successful different-source import still creates a clean project with no old PAD, step, loop, scratch, marker, or history state.
- A debug preview is not a production-signed or latency-qualified Pro release.
- Preserve unrelated untracked `outputs/` and `work/` content.

## Architecture and interfaces

`SamplerViewModel` owns a monotonic project-operation epoch. Source decode/load completion may mutate UI state only while it still owns the latest epoch. Reset and newer source selection advance the epoch. Persistence receives the project revision and rejects a stale write relative to the newest committed autosave revision.

`SamplerEngine` separates the newest issued source command from the generation the audio thread actually applied. Play/seek/live-pitch restart commands publish the playing state only when applied; completion of an older applied generation cannot clear a newer applied voice. Scratch speed is converted to a finite bounded value before enqueue and checked again before DSP smoothing.

Compose UI keeps confirmation state locally at the source-import entry point and delegates only a confirmed request to the existing picker. Guidance strings remain pure functions in `GuidedWorkflow`, making the novice flow deterministic and host-testable.

## Milestones

### Milestone 1: Safe new-project intent and coaching

- Scope: common import guard, state-based Chop guidance, Beat Quick guidance, unambiguous whole-chop label.
- Files/interfaces expected to change: `OtohiroiDeck.kt`, `GuidedWorkflow.kt`, focused UI/model tests.
- Implementation: vertical Red/Green slices against pure decision functions and rendered labels; use the existing confirmation/dialog visual system.
- Tests/checks: focused unit tests, fixed-layout policy tests, pre/post 1080 x 2424 screenshots.
- Acceptance: destructive replacement requires confirmation only when current work exists; no confirmation for a blank project; cancellation does not invoke the picker; beginner guidance names waveform tap, empty PAD, assigned PAD audition/trim, whole-chop loop, pattern placement, Add, and Scratch without adding screen height.

### Milestone 2: Project epoch and revision-safe autosave

- Scope: stale decode/load completion invalidation and monotonic autosave freshness.
- Files/interfaces expected to change: `SamplerViewModel.kt`, `AtomicProjectStore.kt`, `ProjectStore` seam if required, persistence and operation tests.
- Implementation: one operation epoch at ViewModel boundaries and one monotonic saved revision at the store boundary; retain atomic generation recovery.
- Tests/checks: deterministic out-of-order completion and revision-arrival tests.
- Acceptance: reset/newest source wins; stale success/failure cannot change the newest loading state; an older revision cannot replace a newer autosave.

### Milestone 3: Source state and Scratch safety

- Scope: source playback generation ownership and finite Scratch controls.
- Files/interfaces expected to change: `SamplerEngine.kt` and focused pure/audio tests.
- Implementation: bounded primitive generation values in existing commands; no realtime locks or allocation; finite normalization on both sides of the queue.
- Tests/checks: old completion versus queued new play, stop ordering, live-pitch restart, NaN/Inf recovery.
- Acceptance: published source-playing state reflects the newest applied command, and invalid Scratch input cannot poison later finite output.

### Milestone 4: Release verification and delivery

- Scope: regression gate, same-state visual audit, APK, data-preserving device install, public GitHub prerelease, evidence docs.
- Files/interfaces expected to change: version metadata, project state/feature evidence, this plan, release notes.
- Tests/checks: `scripts/validate_project.sh`, focused tests during iteration, full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `git diff --check`, no-scroll scan, emulator UI flow, physical Pixel smoke when connected, GitHub CI/tag/release checks, reverse-download hash.
- Acceptance: all local gates pass; exact local APK identity is recorded; physical-device claims are limited to observed actions; public release asset and checksum match; Human GO and subjective audio/latency remain separate.

### Milestone 3b: Live controls and realtime reliability follow-up

- Scope: large-text fixed-console labels, KEY/TONE/LEVEL changes on an already-playing voice, bounded command ingress, guaranteed Stop All boundary, allocation-free PAD voice reuse, AudioTrack teardown ownership, and microphone worker completion.
- Files/interfaces expected to change: `GuidedWorkflow.kt`, `OtohiroiDeck.kt`, `SamplerEngine.kt`, `VoicePlaybackCursor.kt`, `MicrophoneRecorder.kt`, bounded mailbox classes, and focused host tests.
- Implementation: keep command and voice allocation on producer/initialization paths; inspect at most 64 queued commands per render block; reserve Stop All outside queue capacity; preallocate 32 PAD voices plus one source voice; reject decode after a microphone worker timeout.
- Tests/checks: Red/Green host tests, bytecode inspection of normal render/drain/start-voice paths, full unit/Lint/assemble gate, 130% font-scale screenshots, and data-preserving emulator install.
- Acceptance: live sound edits do not restart the loop cursor; normal audio-thread paths create no voice objects; command growth is capped; Stop All cannot be dropped by queue pressure; incomplete recording output is not decoded.

## Progress

- [x] 2026-08-12 - Confirmed `main` and `origin/main` at `2c30fc3951f5ea53c52492a100619cd055697ea9`; tracked tree clean.
- [x] 2026-08-12 - Captured and inspected current Capture, Chop, Beat, Layer Studio, and Scratch states on dedicated emulator `emulator-5590`.
- [x] 2026-08-12 - Completed independent Sol UX and audio/persistence audits.
- [x] 2026-08-12 - Passed configured doctor and offline validation baseline.
- [x] 2026-08-12 - Implemented Milestone 1 with Red/Green guidance, destructive-intent, compact Beat, and layout-policy evidence.
- [x] 2026-08-12 - Implemented Milestone 2 with Red/Green project-epoch, capture-operation, and revision-arrival evidence.
- [x] 2026-08-12 - Implemented Milestone 3 with Red/Green source-generation and finite-Scratch evidence; final review found and closed the applied-vs-issued playback gap.
- [x] 2026-08-12 - Merged PR #20, passed branch/PR/main/tag/release CI, published `v0.11.0-preview.1`, and reverse-verified the public APK plus checksum.
- [x] 2026-08-12 - A Sol-specified read-only audit identified unbounded command drain, audio-thread voice allocation, live-control, AudioTrack teardown, microphone-stop, and multi-pattern gaps; runtime metadata did not expose the effective model, so this is not claimed as runtime-verified Sol evidence.
- [x] 2026-08-12 - Implemented live KEY/TONE/LEVEL updates without cursor restart plus 130% font-scale labels; dedicated emulator screenshots show the full `選択音ループ` label.
- [x] 2026-08-12 - Implemented bounded command/stop ordering, reusable PAD/source voices, owner-safe AudioTrack cleanup, and fail-closed microphone worker completion with Red/Green tests.
- [x] 2026-08-12 - Built versionCode 16 / versionName 0.11.1, installed it in place on dedicated emulator `emulator-5590`, preserved the exact pre-interaction autosave, and observed source, Chop, Beat loop/live KEY, and Scratch runtime states without a focused fatal/ANR match.
- [ ] Complete Milestone 4 and move this plan to `plans/completed/`.

## Discoveries

- The current Chop surface already exposes source pitch, A/B/C/D, 32 PADs, Beat, Add, and Scratch without scrolling; the remaining first-use problem is guidance and terminology, not another navigation layer.
- `loadAudio` currently has no operation token, so reset cannot invalidate an in-flight decode.
- Atomic file replacement prevents corruption but does not itself prevent a logically older snapshot from winning by arrival order.
- `Float.coerceIn` does not make NaN finite.
- A system-level emulator ANR dialog appeared once at startup; selecting Wait restored a stable app. It is environment evidence, not an app defect claim.
- A project epoch captured only at decode time is insufficient for microphone, Playback Capture, and vocal flows: the same capture operation must own start, stop, delayed service completion, decode, and assignment.
- Reusing the portrait stack in landscape clipped high-value controls. Chop requires a waveform/PAD split workspace; Beat requires compact BANK/page and action rows while keeping Details reachable.
- Source command issuance and source playback application are different facts. The public `playing` state now follows the audio-thread application rather than queue insertion.
- The old `ConcurrentLinkedQueue` could grow without limit and drained the entire backlog before every render block; bounded capacity alone was insufficient because Stop All also needed an out-of-band sequence boundary.
- The old PAD and source paths constructed `Voice` and `VoicePlaybackCursor` on the realtime thread. A fixed 32-voice pool plus one reusable source voice removes those normal-path allocations while preserving deterministic oldest-voice stealing.
- `Thread.join` timing out is not a successful microphone stop. The WAV stays ineligible for decode until the worker has closed the writer.

## Decision log

- 2026-08-12 - Preserve the existing visual system and fixed layout. Use existing TIP and confirmation components instead of adding controls or a tutorial page.
- 2026-08-12 - Treat a Beat loop as repetition of one selected Chop, matching `CONTEXT.md`; label pattern placement separately.
- 2026-08-12 - Keep the source replacement model, but require explicit intent when there is material work and reject stale asynchronous completions.
- 2026-08-12 - Do not claim Luna fan-out: the Luna setup check found `max_concurrent_threads_per_session=10` instead of the required 40. Sol routing was explicitly requested, but returned runtime metadata did not expose the effective model name; therefore no runtime-verified Sol claim is made.
- 2026-08-12 - Keep producer-side mailbox ordering synchronized while the audio-thread consumer remains lock-free. A 512-entry queue and 64-command per-block inspection cap bound both memory and render delay; Stop All uses an atomic latest-request boundary.
- 2026-08-12 - Retain the current one-pattern product scope for this patch. Multi-pattern/Song mode remains a named Pro gap rather than being rushed into the reliability release.

## Validation log

- `scripts/doctor.sh` - 2026-08-12, configured Git Bash/JDK 17/Android SDK - required MVP dependencies available; optional future NDK/CMake missing.
- `scripts/validate_project.sh` - 2026-08-12, configured Git Bash/Kotlin 2.3.21 - PASS.
- focused playback-state TDD - 2026-08-12 - RED on missing `applyPlay` / `applyStop`; GREEN after separating issued and applied generations.
- Gradle `testDebugUnitTest lintDebug assembleDebug` - 2026-08-12 - PASS; 125 tests, zero failures/errors/skips; Lint zero errors and 11 advisories.
- local APK - 2026-08-12 - versionCode 15 / versionName 0.11.0; 31,516,578 bytes; SHA-256 `37D60CB25D7FC996B68BC83F7FDDCAFA3DE770117ABC1A072A53A8C256B7CC85`; v2 signature verified.
- dedicated `emulator-5590` - 2026-08-12 - in-place install PASS; autosave remained 5,316,915 bytes and SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`; cold launch alive with no focused fatal/ANR match.
- fixed-layout audit - 2026-08-12 - accepted portrait Chop plus landscape Chop, Beat Quick, and Beat Details captures under `work/v011-audit/`; scroll API scan zero matches.
- GitHub publication - 2026-08-12 - PR #20 merge `1e0446a29ba245383149de9bfab7863bd69b87e8`; branch/PR/main/tag/release runs `31522964955` / `31522968714` / `31523293224` / `31523626784` / `31523626790` PASS.
- public APK - 2026-08-12 - 30,723,019 bytes; SHA-256 `04F7284DB3EF90F37561259BF1E0DBCDE59D4AD6A06A448B8729A942AC902B39`; GitHub digest, checksum sidecar, v2 signature, package, and version metadata match.
- focused realtime/recorder TDD - 2026-08-12 - RED on missing bounded mailbox, cursor reset, reusable voice, and worker completion seam; GREEN after implementation.
- Gradle `testDebugUnitTest lintDebug assembleDebug` follow-up - 2026-08-12 - PASS; 137 tests, zero failures/errors/skips; Lint zero errors and 11 advisories.
- `scripts/validate_project.sh` follow-up - 2026-08-12, configured Git Bash/Kotlin 2.3.21 - PASS; `git diff --check` PASS; UI scroll API scan zero matches.
- local v0.11.1 APK - 2026-08-12 - 30,739,399 bytes; SHA-256 `354571D8390BA8F86B20DBEA53E3954912A8FECA47D9171253E38B864FAB4059`; package/version/minSdk/targetSdk and v2 signature verified; local certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.
- dedicated `emulator-5590` v0.11.1 - 2026-08-12 - in-place install and cold launch PASS; autosave stayed 5,316,915 bytes / `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513` through the install checkpoint; source/Chop/Beat live loop+KEY/Scratch smoke stayed alive with zero scoped fatal/ANR matches.

## Risks and rollback

- Revision gating can reject a legitimate save if revision ownership is inconsistent. Keep one monotonic source and test arrival order directly.
- Source-generation changes can create a UI/audio mismatch if state is published before command application. Publish from the same ownership boundary tested by the render state machine.
- Confirmation copy can overflow compact layouts. Reuse the existing modal dimensions and verify the 1080 x 2424 device plus existing compact policy tests.
- If a milestone regresses the full gate, revert only that focused milestone commit; do not reset unrelated user files.

## Remaining device validation

- Physical Pixel 9a data-preserving install and launch of the final local APK.
- Non-destructive navigation, source play, live key change, Beat, Layer Studio, and Scratch smoke on the physical device.
- Subjective audio quality, Scratch latency, microphone ambience, sustained thermal behavior, and Human GO remain user validations.
