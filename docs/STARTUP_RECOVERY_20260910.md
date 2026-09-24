# Startup recovery candidate — 2026-09-10

## Scope and exact input

User request: shorten ChopLab / おとひろい startup. Base is GitHub main
`bed7a550a71b1ae91556b2b2af25d7c482083c98`, tree
`c68ae49f314d87902d4bfa171cf78aa0831e5633`.

This is a proposed runtime delta, not a merged-main or physical-device receipt.
PR #89 release-policy work and dependency PRs #90/#91 remain separate.
The existing PROJECT_STATE and FEATURE_MATRIX retain their baseline evidence;
this document records the candidate's feature/evidence delta without promoting
those historical gates. Reconcile the main snapshot and registry when integrating.

## Implementation and feature delta

- `jvm-core/.../AtomicProjectStore.kt`: rank revision sidecars first, then validate
  archive SHA-256 and decode in descending revision / existing generation priority.
  Stop at the first valid project instead of holding up to four decoded projects.
  Both Android and Windows use this implementation. Save rotation, schema, PCM
  budget and fallback recovery remain unchanged. Unverified revisions are hints,
  never permission to skip digest or codec validation.
- `app/.../MainActivity.kt`: obtain MediaProjectionManager only when system-audio
  capture is requested, not during initial composition. Permission launchers and
  capture policy are unchanged. ReportDrawnWhen waits for `!state.isLoading`, so
  fully-drawn timing includes autosave recovery or its handled failure state.
- New deterministic tests count actual bounded codec calls. Healthy four-generation
  recovery now decodes one archive; corruption, interrupted pending saves, legacy
  files, revision ties/extremes, budget fallback and unchanged disk bytes are covered.

No AudioTrack startup threading, real-time DSP, PatternRenderer, archive schema,
Undo/Redo format, shared deck, LayerStudio, dependency versions or release changes.
No user or third-party audio is included.

## Observed host evidence

| Check | Observed result |
| --- | --- |
| Focused production Kotlin compilation | PASS against exact-base JVM codec/model bytecode |
| Existing 11 + new 14 autosave test bodies | 25 PASS, no production stubs |
| Python policy suite | 214 run, 2 skipped, no failures/errors |
| Public surface and whitespace | PASS before publication |
| Full project validator | BLOCKED: installed Kotlin 1.9 cannot resolve baseline EditHistory.addLast |
| Gradle JVM tests / Android tests / lint / APK | BLOCKED before build: UnknownHostException services.gradle.org; SDK also absent |
| Device launch, audio, Android Compose runtime | NOT RUN |

Host environment: Linux, OpenJDK 21.0.11, standalone Kotlin 1.9.0, Java target 17.
The repository uses Kotlin 2.4.10 and recommends JDK 17. To test the changed store
without misrepresenting a full source build, it was compiled using
`-Xskip-metadata-version-check` against `jvm-core-0.17.2.jar`,
`shared-desktop-0.17.2.jar` and `kotlin-stdlib-2.4.10.jar` from exact-base Windows
workflow `33657961508`, artifact `9857641189`.

JUnit was not installed locally. The host runner only changed JUnit imports and
annotations into kotlin.test assertions plus an equivalent assertThrows bridge;
it executed the original 11 and added 14 test bodies against the real changed
AtomicProjectStore and the real existing archive codec/model. This is **not** a
claim that Gradle/JUnit/Android tests ran. The committed tests remain normal JUnit4.

## Host A/B measurement, not app startup

Synthetic fixture: four archives, each with 30 seconds of 48 kHz stereo PCM-16
(5,760,000 PCM bytes per generation), deterministic pseudo-random samples.
Baseline and candidate stores were compiled with the same compiler against the
same exact-base dependencies. Separate JVMs ran in baseline/candidate/candidate/
baseline order, each with three warmups and nine measured reads, Xms256m/Xmx512m.
Every read checked recovered revision, BPM and sample count. Filesystem was warm.

| Recovery only | Baseline | Candidate |
| --- | ---: | ---: |
| Measurements | 18 | 18 |
| Median | 148.386564 ms | 76.6049565 ms |
| Minimum | 127.906048 ms | 37.709514 ms |
| Maximum | 178.831709 ms | 91.710923 ms |

Observed median reduction: **48.37%** in this fixture and host, not a guaranteed
speedup. The deterministic reduction is **four codec decodes to one** when all
four generations exist and the highest-ranked archive is valid. Fewer generations,
corruption and real projects have different costs. Empty first-install startup
gets no autosave-decode saving. Peak process memory and physical startup were not
measured; do not convert the avoided PCM projects into a measured RSS claim.

## Reproduce with a supported checkout

```sh
./gradlew :jvm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :jvm-core:benchmarkAutosaveRecovery --args="seed /tmp/choplab-startup-fixture"
./gradlew :jvm-core:benchmarkAutosaveRecovery --args="measure /tmp/choplab-startup-fixture candidate"
```

Use a new directory outside the checkout for the synthetic fixture. Do not commit
its audio/project files. For an A/B run, apply only the benchmark file/task to a
separate baseline worktree, keep the production store at the base revision, and
measure the same fixture with identical JVM settings and alternating run order.
No timing threshold is added to ordinary CI.

## Required device follow-up

Use the same device and build type for at least ten force-stopped launches per
revision, testing empty project and representative restored projects separately.
Do not clear app data or replace signing identities. `adb shell am start -W -S
-n com.choplab.sampler/.MainActivity` measures launch; collect ActivityTaskManager
Displayed and Fully drawn messages separately to distinguish TTID from TTFD.
Verify all four autosave recovery paths, first PAD hit, microphone/system recording,
notification permission denial, background/rotation and missing audio output.
This patch does not move engine initialization to a worker: doing so without
lifecycle ownership and first-command tests risks dropped hits or late restarts.

Primary API references checked 2026-09-10:
- https://developer.android.com/topic/performance/vitals/launch-time
- https://developer.android.com/reference/kotlin/androidx/activity/compose/package-summary
