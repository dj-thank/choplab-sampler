# Runtime quality: recording safety, stereo analysis and bounded visual work

## Purpose and user-visible outcome

Reduce avoidable work beyond startup while keeping recordings safe and stereo material visible and detectable. Consolidate the previously unpromoted startup-preparation candidate only when compatible with current open work. This selected plan is not a declaration that all product or physical-device work is complete.

## Current state

Base: PR95 head `d1626def5c8f7f117e954d71034b0e32d048e99a`, tree `9b03323a2cb72435f3fa7ce2e6c61bbd765e249d`. All four exact-head hosted workflows were successful when read on 2026-09-10. Main remains `bed7a550a71b1ae91556b2b2af25d7c482083c98`. The source snapshot was restored with its Git executable modes and its tree matched the base exactly. The local compiler is Kotlin 1.9/JDK21, not the supported complete toolchain; no Android SDK/ADB is present.

PR93 overlaps the editor/startup paths and was inspected and reconciled locally. Its last Android and Windows workflows failed; do not assume its host tests establish merge readiness. PR89 and dependency PR90/91 remain separate.

## Constraints and invariants

Preserve PCM bytes, stereo frame order, recording limits, stream ownership, autosave recovery, history and schema. No AudioTrack callback I/O/allocation or speculative idle suspension. No dependency, workflow permission, signing, release/tag or user-file mutation. Small-volume display changes must not amplify saved audio. Synthetic fixtures only.

## Architecture and interfaces

`jvm-core/.../audio/WavFileWriter.kt` owns synchronous recording-file writes and validates before opening a file. Its bounded 8KiB scratch storage lives per writer, not per batch.

`app/.../audio/TransientDetector.kt` computes channel power independently rather than cancelling signed channels before squaring.

`shared/.../ui/WaveformEnvelope.kt` provides bounded, sampled visual bounds for editor/PAD/timeline. Primitive arrays avoid per-bucket object lists. UI cache identity includes the PCM array reference; no mutable global cache is introduced. Visual extrema are not an exhaustive peak-meter guarantee.

Startup preparation preserves the current UI selection at commit time and defers maintenance until loading completes. Overlap with PR93's output lifecycle work requires an explicit reconciliation decision.

## Milestones

1. Reproduce recording/stereo regressions before changing the implementation. Accept only tests with actual baseline failures and matching green tests after fixes.
2. Bound recording/visual work and cover PCM identity, odd limits, edge buckets, invalid viewports, stereo phase and input immutability.
3. Run broader persistence/startup regressions and supported build/policy gates where available. Record host and real-toolchain results separately.
4. Publish only the validated exact tree in a draft review branch, plus reproducible evidence; no automatic merge/release.

## Progress

- [x] 2026-09-10 — verified PR95 source identity and hosted CI; inspected source and open PRs.
- [x] 2026-09-10 — focused baseline: 15 pass, 7 fail; modified production: 22 pass, 0 fail (host test bodies, not JUnit).
- [x] 2026-09-10 — bounded recording writer, per-channel transient energy, shared primitive visual helpers and three call sites implemented locally.
- [x] Expanded tests: 190 distinct test bodies pass; ten failures reproduced on actual prior implementations. A/B/B/A: 240 measured rows, no warmups included.
- [x] Reconciled PR93 gain/output startup and the previous local preparation. Restored exact H13 range semantics; retained historical status/registry bytes separately while preserving the required first release snapshot.
- [ ] Complete policy/build checks and exact-tree GitHub publication/read-back.
- [ ] Device audio/UI/startup/power validation.

## Discoveries

Invalid WAV constructor settings could create/truncate a destination before validation. Signed stereo averaging could erase opposite-polarity attacks and visual bounds. Out-of-range editor viewports repeated an edge sample. Existing 24ms polling already avoids unchanged-state publication and must not be blindly suspended. The first central release snapshot heading has a validation contract; it and its section remain verbatim. The full historical ledger and registry remain byte-for-byte as same-directory history files.

## Decision log

2026-09-10: prefer bounded primitive storage and immutable input identity over additional global caches. Retain realtime render/DSP/level policy. Output initialization uses PR93's cancellation-safe overlap; this is distinct from moving the render loop or sleeping the AudioTrack stream. Broad user intent does not remove validation gates.

## Validation log

Current-turn conversation evidence retains `focused-red.log` and `focused-green.log`, compiler arguments and adapted test runner. Production code is real; JUnit imports/annotations were temporarily adapted to kotlin.test for the host runner. Compile/discovery/Compose/device validation is not implied. Results: storage90, startup/drum/cleanup30, engine19, focused22, shared13, PR93 lifecycle/gain16 =190. Python policy214 with2skip and no failures; current public-surface and diff logs are retained. Regular Gradle stopped before compilation on distribution DNS resolution; doctor reports no SDK/ADB. See the verification receipt for performance medians, allocations, tradeoffs and required fresh CI.

## Risks and rollback

Stereo-aware visuals and detection intentionally differ from a signed mono downmix, but must not change audio samples. Sampled displays can still miss unsampled narrow peaks. Revert this follow-up relative to PR95 to restore prior behavior; no data/schema migration is needed. Preserve other branches and pending work.

## Remaining device validation

Physical cold/warm startup (first frame, readiness and first audible PAD separately), microphone and system capture, low-level/anti-phase imported stereo, cancel/close during load, large projects, zoom/scrub/cache invalidation, TalkBack/Narrator, audio routes/focus, underruns, memory and power.
