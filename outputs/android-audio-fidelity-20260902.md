# Android audio fidelity repair — 2026-09-02

## User-visible defect

Android playback sounded audibly broken even before intentional polyphonic overload. The shared master applied `x / (1 + abs(x))` to every non-zero sample, so a clean sample at 0.5 became 0.333 and full scale became 0.5. The same curve affected live `AudioTrack` output and offline WAV export.

## Product repair

- Product checkpoint: `b63ed650e47c5555f4a328171c222ca4888a88ae` / tree `7ce7caaea092c9331afe2ea2d51b8efdb19895e1`.
- Decoder/master hardening: `9dc71a4652c943ded89c62bb50d9512270182d20` / tree `4d8eb5f49d7608ee1ad2e1a3315d982065587fb2`.
- Review hardening: `c5c5bcc692b947e684713f7a4e9b8c3762728f34` and final product `7e7c7ae5a5b30f7f3e526ba887825fe60b400fd1` / tree `b45b0d7f8afca04684e2149505cf4b3e869ef2ed`.
- Samples through magnitude 0.9, including a full-scale imported PAD at the product's default 0.9 gain, remain bit-for-bit unchanged by the master.
- Above 0.9, a positive/negative symmetric C1-continuous rational knee approaches but never reaches 0.98. The realtime callback performs only finite arithmetic and adds no allocation, lock, I/O, or logging.
- Android's actual `AudioTrack` call seam and offline `PatternRenderer` both invoke the shared limiter. Tests cover normal source level, 2- and 32-voice-equivalent overload, monotonicity, symmetry, non-finite input, and WAV output amplitude.
- Decoder output format is now stable across sample rate, channel layout, and PCM encoding after the first PCM buffer. Unsupported encoding and NaN/Infinity fail closed. DC removal uses the whole decoded signal and applies only an offset that leaves every PCM16 sample in range.

Android documents PCM float's nominal range as `[-1.0, 1.0]`; explicit finite checking precedes the existing nominal over-range clamp: https://developer.android.com/reference/android/media/AudioFormat

## Revision-bound validation

JDK 17, Windows host, one Gradle worker, 1.5 GiB Gradle heap, in-process Kotlin compiler:

- shared Desktop: 87 tests; shared Android host: 87 tests.
- Android unit: 288 tests; JVM core/offline WAV: 88 tests.
- failures, errors, skips: 0.
- Android lint, debug APK assembly, and AndroidTest Kotlin compilation: PASS.
- `git diff --check`: PASS. The final full command executed 99/99 selected tasks.

Earlier uncommitted predecessor runs did not produce test results: the Gradle JVM/test forks reported insufficient host virtual memory under the default 4 GiB configuration. Final checkpoint `7e7c7ae` passed under the bounded configuration above; crash evidence is kept outside the repository artifacts and is not counted as a product result.

## Evidence ceiling

This receipt establishes `LOCAL_PASS` for PCM math, Android/JVM execution seams, import invariants, APK construction, and static Android checks. Emulator playback can validate lifecycle and absence of fatal logs, but it cannot establish physical speaker/headphone quality, route-specific underruns, DAC inter-sample peaks, or subjective acceptance. Those remain separate device/Human gates.
