# Android idle mixer and capture cleanup: 2026-09-10

## Scope

This independent follow-up is based on PR #92 at `5fb534c08559c98ba50439a42055bd44e6e28a5d` (tree `f06aea953c7247d9e81f56374adc73747c3bf095`). It does not include the separate local iteration2 WAV/startup-preparation patch. No ViewModel, schema, DSP formula, AudioTrack lifecycle, dependency, workflow, signing, release, or user-data policy change is included.

## Implemented

- After draining the existing command queues, inspect transport/source/scratch and the fixed 32-voice pool once. A completely idle block skips only the per-sample mixer. The existing zero-filled stereo output is still written using the unchanged AudioTrack loop.
- Running transport (including silent steps), scratch, source, active PADs and release tails retain the complete render block. Stop priorities, terminal samples and command admission remain unchanged.
- Capture cleanup filters owned names before allocating File wrappers and resolves canonical ownership only for old candidate files. The final canonical-parent check is preserved; outside symlinks, unrelated files, fresh captures and directories are not deleted.

## Host evidence, not device performance

The real modified SamplerEngine Kotlin was compiled with host Android signature stubs. The real private renderLoop ran against a nonblocking test AudioTrack sink. This exercises the production mixer and command code, but NOT Android Binder, native audio, hardware latency, focus/routes or actual device CPU use.

A/B/B/A used four independent JVMs, 8 warmups and 16 measured rounds per condition in each JVM (32 measurements per version). Every round renders 4,000 blocks of 192 stereo frames. Both variants use identical stubs, dependencies and synthetic fixtures.

| Host process only | Control median | Candidate median | Control p90 | Candidate p90 |
|---|---:|---:|---:|---:|
| Idle mixer, 4,000 blocks | 8.363 ms | 0.416 ms | 9.414 ms | 0.586 ms |
| One active looping voice | 44.060 ms | 37.128 ms | 46.275 ms | 37.858 ms |
| Running silent transport | 34.368 ms | 27.766 ms | 37.295 ms | 28.054 ms |

For 192-frame fully idle blocks, the fixed-pool checks fall from 6,144 to at most 32 per block. The host idle kernel median falls about 95.0%; this is NOT a 95% app CPU, startup, battery, or power reduction. Per-round thread allocations remain 1,664 bytes in both idle variants (initial output/frame objects and reflection); no per-block allocation is introduced by the gate. Active-path timing is host/JIT-sensitive and is not a portable improvement guarantee.

A separate A/B/B/A cleanup stress fixture contains 5,000 fresh app-owned names and 5,000 unrelated names; all files contain zero bytes. With 8 warmups and 12 measurements per JVM (24/version), the median decreases from 45.390 to 15.852 ms, p90 from 48.171 to 19.269 ms, and thread-allocated bytes from 8,449,472 to 3,825,584. These are warm-filesystem synthetic stress measurements, not typical-device cache or app startup results.

## Correctness

- 7 new idle-gate tests plus 12 existing real voice/master/terminal-sample tests: 19 test bodies pass.
- 2 existing capture tests plus 4 regressions: 6 test bodies pass. The outside-symlink regression runs successfully on this host; JUnit uses an explicit assumption on platforms without symlink permissions.
- 12 actual renderLoop scenarios compare 294,912 float samples in total and the published playback-state sequence, hashing raw float bits and state fields. Every digest and first-nonzero position matches the control. Cases cover idle, mono/stereo source, one-shot, reverse, GATE release, loop session, 32 voices, PAD/source scratch, silent transport and an initially silent transport with a later note.
- Tests used temporary JUnit import/annotation adaptation to kotlin.test, not a JUnit engine. Production code was not replaced with a reimplementation. Android signatures and the output sink were stubbed only in the external host harness.
- Compiler: local Kotlin 1.9 with metadata compatibility flag and JDK 21; dependencies from the exact successful PR92 Windows artifact. This is not the repository-supported full-toolchain gate.

## Required merge gates

Run with the supported JDK/SDK/toolchain:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/check_public_surface.py
git diff --check
```

Fresh exact-head CI and review are required. Physical tests must cover first PAD after idle, release tails, first audible frame, background/foreground, route/focus changes, and power/CPU profiling. The earlier PR92 green workflows do not validate this follow-up. No merge, tag, release or device installation is performed here.

## Evidence availability and excluded experiments

Raw CSV, host stubs, probes, exact patch/tree verification and the broader local integration are supplied separately in the conversation evidence archive. The larger local work also evaluates WAV bulk writing and startup-maintenance deferral; neither is included in this independent PR. PROJECT_STATE/FEATURE_MATRIX convergence with the other open work remains a separate, unpromoted gate.

A naive suspension of the 24 ms UI poller was not adopted: state can still be optimistic before an engine command takes effect, so a state-only idle predicate can lose later start acknowledgement. AudioTrack start/stop was not moved across threads without lifecycle tests.
