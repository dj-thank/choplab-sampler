# Runtime quality integration — 2026-09-10

## Identity and scope

Baseline: PR95 `d1626def5c8f7f117e954d71034b0e32d048e99a`, tree `9b03323a2cb72435f3fa7ce2e6c61bbd765e249d`. Related input: PR93 `a932918e25399a863ac79da1b74df6e14bfb3f97`. Main observed at `bed7a550a71b1ae91556b2b2af25d7c482083c98`. This receipt describes an unmerged candidate, not a Release or device update. Parent-head hosted results never substitute for fresh candidate CI.

Integrates the prior local startup preparation with PR93 output-open/recovery overlap and display-only gain. Adds bounded recording writes, pre-open validation, per-channel transient energy, stereo-aware zero crossing and shared primitive waveform envelopes for the editor/PAD/timeline. Restores H13 range-only viewport state semantics while retaining the display-only gain explanation as a separate accessible text node. Schema, audio gain, PCM, realtime DSP, dependencies, workflows/permissions and signing are not changed.

## Reproduced failures and correctness

The actual baseline focused runner produced 15 passes and 7 failures: three invalid WAV-constructor/file-preservation cases, one cancelled-stereo-attack case and three editor channel/range cases. The actual old zero-crossing function produced three additional failing regressions (10 other shared tests passed). All ten baseline-negative cases pass after correction.

Current-turn distinct Kotlin test bodies: persistence90, startup/drum/cleanup30, actual engine voice/idle19, writer/transient/editor22, shared waveform/zero crossing13, PR93 lifecycle/gain16: **190 pass**. These are actual production Kotlin compiled against PR95 artifact dependencies with Kotlin1.9/JDK21 and a metadata compatibility flag. The external host runner temporarily adapts JUnit annotations/imports to kotlin.test; this is NOT normal JUnit discovery, complete supported-toolchain builds, Compose UI execution, Android Binder/AudioTrack or physical testing. Only the engine harness uses Android signature/output stubs. Synthetic fixtures only.

The PR93 Windows job `102711379420` (run `34426043553`) passed ordinary compilation/test phases but failed six of24 H13 UI tests because a gain suffix changed exact viewport stateDescription. No H13 assertion is weakened or removed here. A fresh real H13 run is required. The related Android run `34426043556` stopped in offline validation; its downstream unit/lint/APK/instrumentation were skipped.

## Performance method

Four independent JVMs in A/B/B/A order: control=PR95 exact writer and verbatim old visual helpers; candidate=modified production implementations. Same JDK21, dependency JARs, -Xmx384m and fixed synthetic inputs. Each case has24 measured samples per version (12 per JVM), 240 measured rows total. Recording has4 warmups per JVM/case; visual helpers8. No warmups are included in the table. Full CSV and harness are supplied in conversation evidence.

Recording writes1,048,576 PCM16 samples (2,097,152 bytes) in each round to a temporary file. Creation and the candidate's per-writer8KiB buffer are BEFORE the timed/allocation interval. The table counts only writePcm16 calls; close/fsync timings are separate in CSV and remain enabled. Zero thread-allocation inside this measured loop does not mean zero writer setup or native/kernel memory. Every output length is2,097,196 bytes; independent writer tests cover payload/channel equality and boundaries.

Visual results are100 envelope/cache rebuilds, not100 frames. Actual UI uses remember and ordinarily reuses cached arrays. Channel-extrema/last-bucket behavior is intentionally more correct than the old downmix sampler, not bit-identical drawing. Both are bounded sampled displays, not exhaustive peak meters. The timeline replaces per-bucket Pair/boxed values with primitive arrays; the tiny PAD helper adds a small fixed wrapper cost.

| Measured host interval | Control median / p90 ms | Candidate median / p90 ms | Median thread-allocated bytes, control → candidate |
|---|---:|---:|---:|
| Recording,1024 samples/call |2.982358 /4.173660 |1.455745 /1.916731 |2,170,880 →0 |
| Recording,4096 samples/call |1.887188 /2.171699 |0.648921 /0.893221 |2,115,584 →0 |
| Recording,16384 samples/call |1.438300 /2.177399 |0.631128 /0.682558 |2,101,760 →0 |
| Timeline,100 rebuilds |9.772882 /11.010652 |9.112464 /10.152332 |6,152,000 →415,200 |
| PAD mini waveform,100 rebuilds |1.528070 /1.958415 |0.351502 /0.458259 |12,800 →13,600 |

The 4096-sample recording median falls65.6% in this fixture; timeline allocation falls93.3%, while its elapsed-time change is only6.8%. The PAD helper allocates8 additional bytes per rebuild in this host measurement despite faster bounded sampling. Do not translate these values into app launch, FPS, battery, total CPU or resident-memory improvements. Mono and duplicated stereo fixtures, adverse phase, swapped channels, endpoints and immutable input are covered separately.

## Build/policy and remaining gates

Current local Python policy:214 tests,212 pass/2skip, no failures. Current public-surface and diff checks pass. doctor/validator were invoked; executable modes were restored from the source archive and baseline Git tree verified exactly. The full validator cannot compile unchanged EditHistory.addLast under local Kotlin1.9. A regular Gradle attempt fails before compilation downloading the distribution (UnknownHostException); Android SDK/ADB is absent. None is a passed app build.

Required fresh candidate checks: supported shared/JVM/Android unit suites, Android lint/debug and release inspection, H13 UI, Android API36 instrumentation/accessibility, Windows packaging and iOS preview. Required physical checks: first PAD after cold/warm start, cancellation/close during audio open/recovery, microphone/system capture, long-project recovery/save, opposite-phase stereo import/edit, zoom/scrub, TalkBack/Narrator speech, routes/focus/underruns and power. No main merge, tag, Release or device install is performed.

## References

- https://developer.android.com/develop/ui/compose/performance/bestpractices
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- https://github.com/Kotlin/kotlinx.coroutines/blob/master/docs/topics/coroutines-cancellation.md

Exa and Context7 were used to inspect primary documentation. No dependency upgrade is included.
