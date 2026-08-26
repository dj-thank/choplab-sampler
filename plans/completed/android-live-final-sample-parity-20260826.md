# Mix the Android voice's terminal sample before retirement

## Purpose and user-visible outcome

Android live playback and offline WAV must include the same final returned sample from every PAD voice. A naturally completed or CHOKE-released sound must not end one sample earlier only during actual Android mixing. This Wave 7 repair closes the real render-loop seam before any wider multi-PAD, stereo or Song investment.

## Current state

- Root / branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-polyphonic-pattern-parity-20260826` / `codex/choplab-polyphonic-pattern-parity-20260826`; the branch name records the initial selection before evidence recompute.
- Base: Wave 6 closeout `6a0649d80e3e1c62bb10742b0ec01765f0a2c45b`, tree `939922a62b628ab7fa67994545801aa1e4e0b646`.
- Product commit: `2948c6a59ab18ba18a0813e9033098b4e31e41a6`, tree `52059c3dce144ef3ad3a3f81b863709edbe8c9c6`.
- Decision receipt: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE7_20260826.md`, recompute section.
- Baseline: explicit Git Bash `scripts/doctor.sh` found Java 17/Gradle/repository healthy and no persistent Android SDK configuration; process-scoped SDK `scripts/validate_project.sh` passed public-surface 418, executable modes, 18 Gradle tasks, XML and wrapper checks in 1m02s.
- Defect seam before repair: `SamplerEngine.renderLoop` obtained `value = voice.render(outputSampleRate)`. When that call marked `finished`, it deactivated the voice and skipped `monoMix += value`. `PatternRenderer` mixed then retired, and the existing parity test's expected path also mixed the return.

## Constraints and invariants

- Target gate: `LOCAL_PASS`; generated PCM only.
- Preserve dirty canonical checkout, Wave 6 worktree, user audio/projects/autosaves and current schema.
- No allocation, blocking, I/O, logging or Android UI call in the realtime loop.
- Render each active voice at most once per output frame; mix its returned value exactly once; retire immediately after the call when finished; later calls emit zero.
- Natural completion and explicit 48-frame fast release share the same mix-before-retire contract.
- Do not alter pitch, tone, gain, boundary/release envelope, voice stealing, CHOKE selection or event ordering unless the focused RED disproves this diagnosis.
- No ADB/device, physical/captured-output recording, provider/public, signing/secret or Human action.

## Architecture and interfaces

- Extract the current PAD-voice render/retire ordering from `SamplerEngine.renderLoop` into an `internal` allocation-free function in the same file so Android host tests exercise the exact call-site logic without constructing `AudioTrack`.
- Phase-1 extraction deliberately preserves the current skip-on-finish behavior. A regression test compares that seam with a second identical `SamplerEngine.Voice` rendered directly, which is the primitive contract already used by offline parity.
- The repair changes only the seam's return ordering: capture value, retire if finished, return the captured value. Loop playhead publication remains conditional on a still-active LOOP voice.

## Milestones

### Milestone 1: Tight RED loop

- Add the behavior-preserving seam and route the production loop through it.
- Add a minimized generated-PCM test whose terminal returned sample is nonzero.
- Command: focused `SamplerEngineVoiceTest`; assert exact terminal index/value, immediate deactivation and later zero.
- Run twice and record the exact RED.

### Milestone 2: One-variable repair

- Return the captured sample after deactivation instead of zeroing it.
- Run the minimized test, existing `PatternMasterParityTest`, renderer tests and engine tests.
- Add an explicit 48-frame release control if the natural-finish test alone does not cover release retirement.

### Milestone 3: Gate, review and closeout

- Run the established 190-task shared/app/lint/APK/JVM/Desktop/package/CycloneDX gate under process-scoped SDK and single-worker/no-watch limits.
- Run configured validation, Python policy, public-surface and diff checks.
- Read back test XML, Lint, APK/EXE/SBOM bytes and hashes.
- Complete Standards/Spec review, move this plan to `plans/completed/`, update product/PAD SSOT and commit.

## Progress

- [x] `2026-08-26T21:26:21+09:00` — Initial whole-system comparison selected multi-PAD parity.
- [x] `2026-08-26T21:29:00+09:00` — Actual render-loop inspection contradicted the assumed expected path; portfolio recomputed to terminal-sample parity.
- [x] Exact-base doctor/configured validation completed; no source delta beyond active-plan docs.
- [x] `2026-08-26T21:33:00+09:00` — Behavior-preserving seam and deterministic RED reproduced twice.
- [x] `2026-08-26T21:35:00+09:00` — Mix-before-retire GREEN plus natural/release controls and existing parity/renderer tests.
- [x] `2026-08-26T21:47:28+09:00` — Full gate, release-bytecode review, artifact read-back and product SSOT closeout completed.

## Discoveries

- `Voice.render` returns a value from the same call that can set `finished`. Consumers must mix that value before retirement; post-call `finished` is a lifecycle signal, not a statement that the returned value is invalid.
- The earlier full-bar repair changed offline `PatternRenderer` to mix-before-retire, but actual Android `renderLoop` still performed retire-before-mix. The existing oracle therefore proved primitive/offline parity, not the exact AudioTrack mix call site.
- Natural completion RED: frame `402`, direct terminal `-0.0012556206`, actual mix seam `0.0`.
- Fast-release RED: release frame `47`, direct terminal `0.0040690107`, actual mix seam `0.0`.
- Both failures reproduced identically in a second 13-second focused run. This falsifies boundary-envelope-always-zero and offline-extra-frame hypotheses; the render-loop branch is the cause.

## Decision log

- `2026-08-26T21:29:00+09:00` — Pivoted before coding multi-PAD tests because a more fundamental current-source contradiction invalidated their expected-path claim.
- `2026-08-26T21:29:00+09:00` — Chose a host-testable same-file seam rather than AudioTrack instrumentation; it preserves realtime constraints and directly covers the ordering defect.
- Multi-PAD, stereo, Song, accessibility and release remain separate portfolio lanes.

## Validation log

- `C:\Program Files\Git\bin\bash.exe scripts/doctor.sh` — Java 17, Gradle wrapper and Git PASS; expected warnings for process-scoped SDK, absent ADB and active plan docs.
- Process-scoped Android SDK + `C:\Program Files\Git\bin\bash.exe scripts/validate_project.sh` — exit 0; public-surface 418; 18 Gradle tasks; XML/wrapper UTF-8 checks PASS.
- Focused RED command: `.\gradlew.bat :app:testDebugUnitTest --tests com.choplab.sampler.audio.SamplerEngineVoiceTest --no-daemon --max-workers=1 --no-watch-fs --console=plain`.
- RED run 1: 11 tests, 2 exact terminal-sample failures; exit 1 in 51s. RED run 2: same two failures; exit 1 in 13s.
- GREEN: `SamplerEngineVoiceTest` 11/11 in 22s. Combined `SamplerEngineVoiceTest` + `PatternMasterParityTest` and `PatternRendererTest` passed in 13s + 11s.
- Full Android app host-unit suite: 45 suites / 252 tests, failures 0, errors 0, skipped 0; Gradle exit 0 in 23s.
- Full Gradle gate: 190 tasks, exit 0 in 2m33s. Android 252, shared Android/Desktop 40/40, JVM-core 55, Desktop 84; 471 tests / 87 suites, failures/errors/skips 0. Lint debug/release each report errors 0 / warnings 7.
- Configured validation 18 tasks, Python policy 40, public-surface 418 and `git diff --check` PASS.
- Release bytecode: actual `renderLoop` has helper invocation 0; the inlined PAD block performs render, finished/deactivate, then mixes the captured value. No new realtime allocation, blocking, I/O, logging or UI call.
- Artifacts: debug APK 31,590,514 / `5B2D88932F3F8AA1B79E4123585464EB35221AB99C130B2AB122D6565F4C978C`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,126,580 / `0AFAEEE1887DE5AD872D1F190D328E6710807A0B1F78E01F3B5097C1105D86DC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- CycloneDX 1.6 / product `0.17.0`: 650 components / 651 dependencies, 1,581,101 bytes / `50B03C65FEC1B35E159FB539A446ED9957813D745F5A3E240E658823F59B35F2`. Every listed artifact is newer than the product commit.
- Parent review: `work/PAD_CHOPLAB_GOAL_WAVE7_REVIEW_20260826.md`; Standards/Spec unresolved 0/0.

## Risks and rollback

- A shallow `Voice` test alone would miss the caller ordering; the extracted production seam is required.
- Returning the terminal sample must not retain finished voices or update loop playhead after retirement. Tests assert inactive state immediately and zero thereafter.
- The helper must inline cleanly in the realtime loop and allocate nothing.
- Rollback is the isolated Android engine/test commit; no migration or external state exists.

## Remaining device validation

- Physical click/pop, exact DAC/output capture, latency, sustained polyphony and route loss.
- Human listening to natural tails and CHOKE transitions.
- Provider/public/release/signing, TalkBack/Narrator speech and `HUMAN_GO` remain separate.
