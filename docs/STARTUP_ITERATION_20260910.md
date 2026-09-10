# Startup iteration 2: bounded PCM restore and responsive first-run preparation

## Scope and exact baseline

This is a follow-up to PR #92, not a replacement for its backup selection fix.
Input is `5fb534c08559c98ba50439a42055bd44e6e28a5d`, tree
`f06aea953c7247d9e81f56374adc73747c3bf095`. Its Android, Windows, iOS and supply-chain
workflows completed successfully (runs 34425126531, 34425126488, 34425126518,
34425126497 respectively). Main remained `bed7a550a71b1ae91556b2b2af25d7c482083c98`
at the follow-up readback. No merge, tag, Release, signing, dependency upgrade,
installation, data wipe or user audio is part of this work.

The exact source snapshot was downloaded from PR #92's Windows workflow artifact
10132345885. A local Git checkpoint reconstructs the identical source tree; it is
not the original Git history. Runtime jars from the same workflow's app-image
artifact 10132448733 provide the unchanged model/dependency code for host tests.

## Implemented changes

### 1. Eliminate the second recording-sized PCM allocation

`jvm-core/.../persistence/MonoPcm16WavCodec.kt` previously allocated the complete
WAV payload as a byte array, then allocated and populated the final short array.
The reader now fills an at-most-8-KiB heap buffer and bulk-converts little-endian
PCM into the final short array. The scratch bound is independent of recording
length. Mono legacy schemas and current mono/stereo WAV paths use the same reader.

Header validation, sample counts, channel order, entry/digest validation, archive
residency limits and backup fallback are unchanged. The reader fills each block
across partial or odd-sized stream reads, rejects zero progress/EOF, does not read
past the declared payload, and does not share mutable scratch among readers.
No direct/off-heap buffers, sample downsampling, lossy compression, audio DSP,
archive migration or weakened safety checks are introduced.

The NIO position reset deliberately calls the `java.nio.Buffer.position(int)`
signature rather than a newer-JDK covariant `ShortBuffer` method. `javap` of the
candidate confirms the Android-compatible Buffer-returning descriptor.

### 2. Move first-run drum synthesis off the Android UI thread

`StartupProjectPreparation.kt` prepares data only: archive I/O uses `Dispatchers.IO`,
then, **only when no autosave exists**, original drum synthesis uses
`Dispatchers.Default`. Only a per-preparation drum-kit object is produced; the latest UI state is read at commit time.
The ViewModel retains both revision and operation-epoch admission checks **after**
all suspending preparation. Engine updates, project publication and saving remain
on their previous control path.

An intermediate prototype prepared a full state snapshot. A blocked-worker test
reproduced a lost selection (expected bank 2, observed bank 0). The final design
prepares only a typed drum kit, then installs it into the **latest** state after
revision/epoch admission. The falsifier now passes. Each preparation owns its own
PCM; there is no global cache or UI snapshot retained by the worker.

A corrupt archive does not authorize replacement with a blank project. Cancellation
is rethrown instead of becoming an error or a late successful startup. Existing
PCM synthesis, IDs, patterns, default bank and selected pad are unchanged.
This removes CPU work from the UI thread; it does not prove a shorter device TTFD.

### 3. Add trace points and a repeatable host comparison

Android trace sections identify `ChopLab.audioEngine.start`, `ChopLab.autosave.read`
and `ChopLab.starter.synthesize`. These are outside real-time audio rendering.
The existing fully-drawn signal still waits until project preparation completes.
`benchmarkPcmRestore` is an opt-in host benchmark, not a timing-sensitive CI test.

## Iteration record: five approaches, then a separate confirmation

All variants used the same Kotlin 1.9 compiler, JDK 21, unchanged dependency jars,
synthetic PCM and host heap settings. Recompiling both control and candidate avoids
comparing different compiler output as though it were only an algorithm change.
Two reversed rounds used separate JVMs for each variant/workload; every process
performed eight warmups and twelve measurements. Each cell has 24 observations.
Filesystems were warm. These are exploratory host measurements, not Android ART,
physical cold startup or universal performance predictions.

| Approach | 30s WAV median | 300s WAV median | 30s recovery median | Decision |
|---|---:|---:|---:|---|
| PR #92 reader, full payload copy | 2.531 ms | 24.086 ms | 12.393 ms | Control |
| Scalar conversion, 8 KiB | 3.772 ms | 29.540 ms | 11.411 ms | Reject: raw decode slower |
| Scalar conversion, 32 KiB | 4.046 ms | 32.934 ms | 11.278 ms | Reject: raw decode slower |
| Bulk short conversion, 8 KiB | 1.633 ms | 6.830 ms | 9.151 ms | Select: small bound, good long-file result |
| Bulk short conversion, 32 KiB | 1.668 ms | 10.820 ms | 8.760 ms | Not selected; recovery difference needs device evidence |

The selection does not assert that 8 KiB is universally faster than 32 KiB.
It retains a smaller allocation/read bound and performed well across these cases.

The final Android-compatible Buffer-signature candidate was then rebuilt and
compared in control/candidate/candidate/control order, again 24 samples per case:

| Operation (48 kHz stereo PCM-16) | Control median | Candidate median | Median reduction | Control p90 | Candidate p90 |
|---|---:|---:|---:|---:|---:|
| Decode 1s canonical WAV | 0.333 ms | 0.093 ms | 72.0% | 0.659 ms | 0.123 ms |
| Decode 30s canonical WAV | 2.254 ms | 1.499 ms | 33.5% | 2.636 ms | 2.970 ms |
| Decode 300s canonical WAV | 24.451 ms | 7.533 ms | 69.2% | 25.772 ms | 8.044 ms |
| Restore 30s project, four available generations | 11.499 ms | 8.713 ms | 24.2% | 13.730 ms | 11.473 ms |

**The 30s raw-decode p90 did not improve in this confirmation.** Do not claim every
latency percentile or workload improved. The end-to-end recovery p90 did improve
in this host sample. Device p50/p90 and first-audio latency remain required.

ThreadMXBean measured allocations made by the executing thread **during each call**:

| Operation | Control allocated bytes | Candidate allocated bytes | Reduction |
|---|---:|---:|---:|
| Decode 30s WAV | 11,520,416 | 5,768,720 | 49.9% |
| Decode 300s WAV | 115,200,416 | 57,608,720 | 50.0% |
| Restore 30s project | 11,878,000 | 6,126,304 | 48.4% |

These are allocation-volume measurements, **not peak RSS, retained heap or total
app memory**. The final PCM still occupies its original amount of memory. The
large temporary PCM-byte-array allocation is gone; other archive allocations remain.
Do not combine these timings with PR #92's earlier 148.39/76.60 ms measurements:
the host/session conditions and warmup differ. The control here already contains
PR #92's one-valid-generation recovery algorithm.

## Validation and evidence boundaries

- RED: the original reader passes 12 new test bodies and fails the two scratch-bound
  checks. This reproduces the recording-sized intermediate allocation contract.
- GREEN: 66 persistence test bodies pass: 27 existing archive tests, 25 existing
  store/startup tests and 14 new streaming tests.
- Startup preparation: ten new tests plus six existing drum-kit tests pass. They
  verify worker/caller ownership, latest PAD selection, exact drum PCM, no synthesis for restored data,
  failures, cancellation and superseded operation admission while CPU work is held.
- Policy: 214 tests run, 2 explicit skips and no failures; current public-surface scan and `git diff --check` passed.
- Total: **82 host test bodies**, using actual production Kotlin and unchanged
  baseline runtime jars plus exact unchanged SamplerModels source where cross-module smart casts require it. JUnit imports/annotations were adapted to equivalent
  kotlin.test assertions in temporary runner sources. No Android/audio engine stubs
  were used. This is **not a normal Gradle/JUnit or real-ViewModel device run**.
- `doctor.sh` and the full baseline validator were attempted. This host has no
  Android SDK; its Kotlin 1.9 cannot resolve unchanged `EditHistory.addLast` in the
  repository's Kotlin 2.4 source. Do not promote the partial validator to success.
- Supported-toolchain CI of the new exact head, physical Android/Windows startup,
  real recording/playback, audio route changes, accessibility speech, provider,
  public distribution and human acceptance are independent gates.

The bounded stream test covers every signed 16-bit sample value, mono/stereo,
1- and 3-byte partial reads, block edges, every truncated-header position, malformed
canonical fields, truncated data, zero-progress streams, I/O failure, concurrent
readers and preservation of trailing bytes. Existing archive/store tests retain
schema compatibility, corruption, memory caps, stale metadata, fallback and
no-rewrite guarantees.

## Reproduction on a supported JDK 17 / repository toolchain

```bash
./gradlew :jvm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :jvm-core:benchmarkAutosaveRecovery --args='seed /tmp/choplab-synthetic-startup'
./gradlew :jvm-core:benchmarkPcmRestore --args='candidate raw'
./gradlew :jvm-core:benchmarkPcmRestore --args='candidate /tmp/choplab-synthetic-startup'
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
python3 scripts/check_public_surface.py
git diff --check
```

Use a **new, synthetic** fixture directory; never point the seeder at user projects.
Compare the two revisions in separate clean worktrees with identical compiler,
heap, hardware, fixtures, warmup and run order. Do not enforce absolute host timing
thresholds in CI. The working evidence bundle contains raw exploration and
confirmation CSVs, logs, the host runners and experimental variant sources.

## Other audited approaches: priority and reasons not to apply blindly

| Area | Finding | Next acceptance condition |
|---|---|---|
| AudioTrack startup | `engine.start()` performs synchronous device setup before UI initialization finishes | Trace new `ChopLab.audioEngine.start`; a future async owner must handle close/background/failure and preserve first-PAD latency, not shift waiting to first tap |
| Capture cleanup | Stale-file cleanup competes with recovery on the I/O pool at startup | Compare startup traces before/after scheduling cleanup after the critical path; preserve ownership/retention rules |
| Waveforms | Visible envelopes/thumbnails already bound their sampled work per bucket | Measure compose/draw traces first; avoid an unbounded cache or broad invalidation rewrite without evidence |
| First-run drum synthesis | Pure original synthesis had run on Main after archive lookup | Implemented worker preparation; device TTFD and first beat remain unmeasured |
| Transport polling | A 24 ms loop checks transport state even while idle | Evaluate event-driven wakeups separately, retaining scratch/repeat/recording responsiveness |
| R8 / shrinking | Release minification is disabled; public distribution is still a debug-preview contract | Benchmark an optimized non-debug variant with compatible signing and full regression; simply enabling release R8 does not change distributed debug APKs |
| Baseline / Startup Profiles | No application-specific generation/measurement module is present | Generate actual critical journeys; compare None vs Partial/Require with identical fixtures and physical device; no guessed profile or promised percentage |
| PCM memoization / direct buffers | Can retain recordings, share mutable samples, or hide native allocations | Not used; fixed owned heap scratch removes an allocation without new cache lifecycle |

Next device matrix: fresh installation state without deleting personal data,
existing small/large projects, healthy backup generations, corrupt-primary recovery;
measure TTID, TTFD, first PAD response, allocated/retained memory and p50/p90 separately.
Use the same build type and signer across comparisons. An empty frame is not ready.

## Primary references consulted through Exa and Context7

- Android startup analysis/optimization and TTID/TTFD:
  https://developer.android.com/topic/performance/appstartup/analysis-optimization
- Macrobenchmark and release-like benchmark variants:
  https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- Actual Baseline Profile comparison on physical hardware:
  https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- Enable R8 and resource shrinking:
  https://developer.android.com/topic/performance/app-optimization/enable-app-optimization
- Heap byte buffers, fixed view byte order and bulk reads:
  https://developer.android.com/reference/java/nio/ByteBuffer
  https://developer.android.com/reference/java/nio/ShortBuffer

Task-local plan: `plans/active/startup-memory-20260910.md`. This follow-up does not
supersede the separate release-convergence lane/PR #89. Global PROJECT_STATE,
FEATURE_MATRIX and plan-registry promotion must reconcile with that work at
integration; no broad rewrite of their historical evidence is included.

## Publication status

The GitHub connector blocked the tree-write request during its safety check.
No follow-up branch or PR was created, no repository ref moved, and the new
product changes have not run in hosted CI. The existing PR #92 remains separate.
The local patch and evidence bundle are the deliverables for this iteration;
no alternate write endpoint, force push, or safety-check bypass was attempted.
