# Startup responsiveness and microphone waveform visibility

## Purpose and user-visible outcome

Respond to the 2026-09-10 request to shorten startup and make quiet microphone waveforms readable without changing the recorded sound. The startup change is Android-specific; the waveform editor is shared UI used by Android and Desktop.

## Current state

Baseline: main at bed7a550a71b1ae91556b2b2af25d7c482083c98. Before this patch SamplerViewModel.init synchronously opened/played AudioTrack. Autosave disk reads were already on IO. Waveforms used unity display gain and averaged stereo channels before extrema extraction.

Implementation is on fix/startup-waveform-20260910. Helpers/tests were committed at ebb0c71f1a0591bc6f0c3b9cfbc9678e0c1896a5; integration was applied at 4e4dd5e4e0e1c8f72ed2e1d7b380eed7e889bb62 and its exact four-file diff was read back. GitHub Actions run 34425650967 successfully applied the hash-guarded patch and ran git diff --check. The temporary application script and workflow are removed after use; no persistent branch-writing automation is part of the final diff.

## Constraints and invariants

- Original PCM, playback gain, project schema, exports, autosave recovery and bank contents are unchanged.
- Audio callbacks are unchanged: no added allocation, file or UI work.
- Startup loading admission remains closed until audio initialization and recovery loading complete.
- Cancelled startup joins an in-flight output open before cleanup; successful output ownership stays with the ViewModel.
- Waveform gain is display-only, bounded to 1–16x, labelled, and stable while panning; no recorder AGC is enabled.
- Work is isolated to this branch. No automatic merge, release, signing, device installation or latency claim.

## Architecture and interfaces

app/src/main/java/com/choplab/sampler/audio/SamplerStartup.kt runs output preparation and project loading concurrently on IO. SamplerViewModel.recoverAutosave invokes it before the existing revision/operation/recovery handling. Cancellation cleanup uses NonCancellable IO and waits for the open worker before shutdown.

shared/src/commonMain/kotlin/com/choplab/sampler/ui/WaveformDisplayPolicy.kt scans immutable PCM without modifying it. The absolute peak uses Int before abs, includes both channels, targets 0.8 display amplitude and caps gain at 16. Peak <=32 PCM units remains unity rather than inflating digital silence/near-silence. WaveformDisplayGain.kt runs this once per source with Dispatchers.Default and cancellation checks. WaveformEditor applies gain only to draw coordinates and labels the display multiplier. Channel extrema replace average-mono extraction; bounded viewport decimation otherwise remains unchanged.

No persistence migration, new dependency, audio effect, or provider behavior is introduced.

## Milestones

1. Pure waveform policy and cancellation-safe startup helper, with host-testable seams.
2. Wire Android startup and shared waveform UI without altering audio/persistence semantics.
3. Read back integration diff, remove temporary application tooling and open a reviewable PR.
4. Run complete repository CI and measure the physical-device result separately.

## Progress

- [x] 2026-09-10 — inspected repository guidance, startup path, recorder and waveform code.
- [x] 2026-09-10 — implemented pure helpers and 16 test bodies.
- [x] 2026-09-10 — compiled standalone host seams and passed all 16 test bodies.
- [x] 2026-09-10 — applied exact-baseline integration; read back the resulting diff.
- [x] 2026-09-10 — removed the one-shot application workflow and script from the final tree.
- [ ] Full repository Gradle/CI result on the final PR revision.
- [ ] Physical-device cold-start, first-play, recording and waveform checks.

## Discoveries

The editing container has Java/Kotlin but no Android SDK, Gradle checkout or cache. Direct GitHub clone/download failed at DNS resolution; connected GitHub reads/writes work. A temporary branch-scoped workflow therefore applied only reviewed replacements after validating all four original blob hashes and every unique edit anchor. It wrote no file until every check passed. This workflow succeeded and is removed from the final tree.

## Decision log

- 2026-09-10 — preserve PCM and disable neither raw capture nor existing processing merely to enlarge a waveform.
- 2026-09-10 — retain the recovery gate rather than briefly presenting an editable blank project.
- 2026-09-10 — analyze source peaks off the UI thread and not on every viewport update. Exact peak analysis does not make the existing decimated envelope full-resolution.

## Validation log

Environment: OpenJDK 21, installed Kotlin compiler and its coroutines/kotlin-test jars. Commands:

```sh
kotlinc new/WaveformDisplayPolicy.kt new/SamplerStartup.kt host/*.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar:$KOTLIN_LIB/kotlin-test.jar" \
  -include-runtime -d host-tests.jar
java -cp "host-tests.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar:$KOTLIN_LIB/kotlin-test.jar" HostMainKt
```

Result: 16/16 PASS (11 waveform and 5 startup). The standalone runner uses annotation-free test bodies and Kotlin assertions; the committed Android test uses equivalent JUnit Assert imports to match the existing app test dependency. The two production helpers and the exact extracted envelope seam were compiled. JUnit discovery, Compose integration and Android framework behavior were NOT exercised by this host runner.

Covered: quiet/silent/loud inputs, Short.MIN_VALUE, off-grid whole-source peak, cancellation, unchanged PCM, opposing and one-sided stereo, mono/raw envelope, invalid input; concurrent off-caller-thread initialization and loading, readiness admission, cancel during open/load, late-open cleanup and unchanged recovery-failure Result handling.

GitHub Actions run 34425650967: exact-hash guarded source application and git diff --check PASS. This is an application/diff check, NOT a Gradle build result.

Required full checks:

```sh
./scripts/validate_project.sh
./gradlew :shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Follow the existing Android/Windows/iOS/Supply workflows for complete CI; retain their exact head and result separately. No APK or physical timing evidence is produced by the host checks above.

## Risks and rollback

Review cancellation during output opening, prompt source replacement during waveform analysis and very compact waveform layouts. Existing approximate viewport decimation can still omit a transient between sampled frames; a waveform pyramid is outside this repair. Reverting this branch's product diff restores the baseline without migrating projects.

## Remaining device validation

On the same physical device/build configuration compare cold-start TTID and time-to-ready before/after. Test blank/new project, valid autosave, corrupt autosave, immediate close/reopen, first PAD/source playback and interruption during startup. Record quiet/loud speech and confirm readable waveforms with unchanged replay/export PCM. Check the display-only label and accessibility description across Android/desktop window sizes. Do not infer a millisecond or percentage improvement from removal of synchronous work alone.
