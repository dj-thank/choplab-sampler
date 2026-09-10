# Reduce persistence work without weakening recovery

## Purpose and user-visible outcome

Reduce repeated disk reads and temporary PCM allocations during recovery/autosave while preserving archive validity, legacy recovery and audio bytes. This is the persistence subtask of the existing startup-improvement effort, not authorization to release or merge.

## Current state

Parent is PR94 `24661363c2e0b75cc1c66dd9908c47c35b5c17b8`. Production owners are `jvm-core/src/main/kotlin/com/choplab/sampler/persistence/AtomicProjectStore.kt` and `MonoPcm16WavCodec.kt`. The older central registry's release-convergence selection is historical for this subtask; it must be reconciled before promoting product-wide gates.

## Constraints and invariants

Keep WAV PCM16 samples/stereo order, ZIP/schema, memory limits, fallback generations, full new-archive validation and durable sync. Do not change UI/AudioTrack, dependencies, release settings or user data. Known stale revisions must not write. Fresh revisions must still inspect disk. This does not add cross-process locking.

## Architecture and interfaces

Store methods retain the existing synchronized monitor. Rank small bounded sidecars, verify the highest valid digest, then return. The implicit save delegates to a monitor-owned write helper without a second scan. An internal hash callback enables deterministic work-count tests. Cancellation and fatal errors escape; ordinary corrupt archives fall back. PCM conversion uses a bounded 8 KiB buffer, not a whole-data intermediary.

## Milestones and progress

- [x] 2026-09-10: verify PR94 identity and read its now-green Android/iOS/policy CI without promoting this candidate.
- [x] 2026-09-10: add ranked revision checks, early stale rejection, bounded metadata, fixed hex encoding and fatal/cancellation propagation.
- [x] 2026-09-10: integrate the earlier local bulk PCM read/write changes and run 90 actual persistence test bodies through the disclosed host adapter.
- [x] 2026-09-10: compare old/new negative paths, 540 storage measurements and 288 compression measurements; retain regressions and reject lower compression.
- [x] 2026-09-10: Python policy 212 pass / 2 skip / 0 fail; attempt supported Gradle and record DNS/toolchain blockers.
- [ ] Fresh exact-head supported-toolchain CI, Android/Windows integration, central registry convergence and independent review.
- [ ] Physical startup, first PAD, long projects, lifecycle and power validation.

## Discoveries and decision log

The old explicit check hashes all generations; implicit save repeats the scan. Reducing these checks cuts work but filesystem sync dominates complete save, whose median does not improve. Lower compression trades tone-file size for time (about 5x larger), so default compression remains. Waveform sampling is already bounded/cached; naive poller suspension has acknowledgement races. See `docs/AUTOSAVE_PERFORMANCE_20260910.md` for exact numbers and exclusions.

## Validation log

Host compiler: Kotlin1.9/JDK21 with real production sources, verified PR92 dependency JARs and temporary JUnit-to-kotlin.test adaptation. This is not Gradle/JUnit or an Android device. Full raw CSV, logs and reproduction scripts are in the conversation evidence archive. `./gradlew :jvm-core:test` fails resolving services.gradle.org before build; validator cannot compile unchanged shared APIs on Kotlin1.9. No generated JAR, user audio, SDK or credential is committed.

## Risks and rollback

Do not weaken hash/codec checks to make a benchmark faster. Sidecars over128 bytes are intentionally rejected as corruption. Revert the two production paths and three test paths to parent PR94 for code rollback; there is no migration or lossy data conversion. Keep source hashes and exact-head checks separate from parent receipts.

## Remaining device validation

Measure release TTID/TTFD, first audible PAD and memory/CPU/power, plus legacy/partial/corrupt recovery, long projects and rapid edits. The separate local startup-preparation patch is not part of this branch. Do not merge or publish automatically.
