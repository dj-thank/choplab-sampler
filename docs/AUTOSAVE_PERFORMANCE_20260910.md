# Autosave revision checks and bounded PCM I/O — 2026-09-10

## Current implementation and exact scope

This persistence-only follow-up is based on PR #94 at `24661363c2e0b75cc1c66dd9908c47c35b5c17b8`, tree `638843a910d802d876adcb7ac3cfaadfa53124a9`. It includes the previously local 8 KiB bulk PCM read/write changes and their tests, plus a new autosave revision optimization and error-handling regressions. Android and Windows both consume `jvm-core`; this does not change the separate iOS store.

Only `AtomicProjectStore.kt` and `MonoPcm16WavCodec.kt` change production behavior. No dependency, schema, compression level, audio gain, DSP, AudioTrack lifecycle, UI polling, release, or signing change is included. The separate local asynchronous starter-kit preparation and startup-maintenance scheduling patch remains outside this branch.

## Changes and invariants

1. Read bounded revision hints, rank them newest-first, and hash archives only until the highest digest-verified revision is found. With four valid generations, save's existing-generation check hashes one archive instead of four. Corrupt, missing or mismatched candidates fall back; metadata alone is never trusted.
2. Reject a revision at/below this store instance's known high-water mark before touching disk. Fresh revisions still validate the disk, including another instance's newer commit. The implicit-revision save uses its completed check instead of repeating the same scan. This is not a cross-process locking guarantee.
3. Retain archive write, full bounded codec reread, checksum generation, file/directory sync, generation rotation, replacement and rollback. The new archive's checksum remains necessary and is not included in the one-versus-four existing-generation comparison.
4. Bound sidecar reads to 128 bytes, above the writer's maximum 86-byte ASCII record. Uppercase SHA-256 and modest surrounding whitespace remain accepted; oversized/non-hex sidecars are corruption, so recovery tries another generation. Historically accepted sidecars padded beyond this limit are intentionally rejected, not rewritten.
5. Preserve `CancellationException` and fatal `Error` identity rather than retrying every backup. Recoverable exceptions still permit fallback. No real memory exhaustion is induced in tests.
6. Encode SHA-256 using one fixed 64-character buffer rather than 32 Formatter calls. Read/write PCM with one 8 KiB byte buffer and bulk ShortBuffer operations. PCM values, sample order, channel order, WAV headers, chunk limits and stream ownership are tested; there is no lossy downsampling or quantization change.

## Verification performed in this round

- **90 persistence test bodies pass**: 19 new revision/error/hex cases plus the retained archive, recovery, streaming-read and streaming-write cases. The real production Kotlin is compiled. Temporary JUnit import/annotation adaptation and a direct host runner are used, not the supported Gradle/JUnit test engine.
- Old-versus-new negative probes use the real store. Old code invokes the failing decoder four times and wraps cancellation/LinkageError; new code invokes it once and propagates the same exception. A 1 MB padded sidecar selects the old primary in the control and the next valid backup in the candidate. A known-stale save recreates a deleted directory in the control but does not in the candidate.
- Python policy: 214 cases, 212 pass, 2 explicit skips, no failures.
- `./gradlew :jvm-core:test` stops before compilation because `services.gradle.org` cannot be resolved. The project validator stops in unchanged shared source because the available Kotlin 1.9 toolchain lacks APIs used by the supported compiler. JDK 21 is present; Android SDK/ADB are absent.
- PR #94's earlier Android/iOS/policy workflows have succeeded, but they do **not** verify this follow-up. Exact-head hosted CI is a separate gate.

## Host measurements, not device startup or power

Three variants: control = exact PR94; ranked = new store with old WAV codec; integrated = new store with bulk PCM codec. Independent JVMs run A/B/C/C/B/A, with 2 warmups and 6 measured rounds per case per JVM: 12 measurements per variant/case. A separate compression experiment uses default/level1/level3/level3/level1/default. JVM heap is 256–512 MiB; compiler is local Kotlin 1.9 with a metadata compatibility flag and JDK 21. Dependency JARs are from the verified PR92 Windows artifact. Fixtures are empty, built-in generated starter drums, seeded noise, and a synthetic tone; no user audio is used.

Storage includes actual temporary-directory file operations and synchronous writes. The 5-second noise is 48 kHz stereo PCM16 and its archive is 961,156 bytes. Warm-filesystem, small-sample and JVM/JIT effects apply. Total completed measurements are 540 storage + 288 compression; warmups are excluded. An earlier 30-second filesystem trial timed out; its partial CSV is preserved separately and excluded. A batch compilation also timed out and the remaining variants were recompiled before use.

| 5-second synthetic stereo / control → integrated | Median ms | p90 ms | Thread-allocated bytes, median |
|---|---:|---:|---:|
| Fresh instance, existing-revision check | 2.841 → 0.803 | 3.485 → 0.870 | 215,616 → 16,392 |
| Valid project recovery | 2.509 → 1.963 | 3.139 → 2.455 | 2,272,200 → 1,202,576 |
| Explicit-revision complete save | 363.467 → 364.274 | 377.973 → 368.351 | 2,563,944 → 1,395,192 |
| Implicit-revision complete save | 367.370 → 379.272 | 393.862 → 390.830 | 2,776,312 → 1,395,192 |

The revision check median falls about 72% and recovery allocation about 47% in this fixture. The complete save is not demonstrably faster; its implicit-save median is about 3.2% worse. Do not claim a full-save, app-startup, resident-RAM or battery improvement from the reduced checks. The known-stale fast path allocates zero bytes in the host probe, but its sub-microsecond timings are too small for a portable latency claim.

## Alternatives evaluated and not adopted

Lower ZIP compression helps the synthetic tone but makes its archive roughly five times larger: default 44,932 bytes / 17.317 ms median, level1 226,884 / 10.559 ms, level3 230,446 / 10.047 ms. The seeded-noise size and write time barely change (default 5,762,622 bytes / 147.164 ms; level1 5,762,620 / 146.614 ms). Starter drums improve somewhat with level1 but p90 worsens. Compression defaults are therefore unchanged.

Waveform envelopes already have bounded sampling and remember keys, so a second cache was not added. State-only suspension of the 24 ms poller remains excluded because optimistic UI state can race queued engine acknowledgement. AudioTrack lifecycle is unchanged to avoid trading startup time for the first audible PAD.

Official sources checked: Android Compose performance best practices and remember/drawWithCache invalidation documentation; Android startup analysis guidance; Java Deflater compression-level API. No library or toolchain version is changed as a result.

## Required next gates and integration boundary

Run supported-toolchain `:jvm-core:test`, Android unit/lint/assemble, and Windows tests/package, then measure actual release-build TTID, TTFD, first PAD latency, memory and power on a device. Exercise existing legacy/corrupt/pending projects, first save after recovery, long files and rapid edits. Preserve prior PRs and never substitute their green CI for this head.

The repo's large central historical state/feature registries retain their older snapshots; this report and `plans/active/persistence-memory-20260910.md` identify the bounded follow-up and its unpromoted gates. Reconcile the central registry and the separate local startup-preparation patch before promoting overall product convergence. No merge/tag/Release or device installation is performed by this change.
