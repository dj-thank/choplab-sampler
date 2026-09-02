# Windows UI / startup brush-up receipt — 2026-09-02

## Fixed source

- Current base: PR #83 head `13e41af589ee32018ae6857623b857b9c0356f21` (branch `codex/choplab-h13-v0171-20260902`).
- Current product: `822305c` on lane `work/choplab-ui-brushup-20260902`, branch `codex/choplab-ui-brushup-pr83-latest-20260902`.
- Current integration checkpoint: `68296f2079349ac0c61df2fb4e34e03fce250e16`, tree `2e6dea9f2da62d072407edf35419660cf3553bfb`, incorporating `main@b737b17` and PR #87 head `07f3ad9`.
- Final main-ancestry integration: `ee1ea293af37037846c0bd16a9b0087001b5c170`, tree `b28c2fc333223b43d3537979fb117d94f11fd3da`, incorporating merged `main@61147bf`; product/build objects remain byte-identical to `68296f2`.
- Preserved predecessor: `a5f5a17` on `codex/choplab-ui-brushup-20260902`, based on PR #83's earlier `f16218d` head. Its local startup timings remain historical evidence for those bytes.
- Plan: [windows-ui-brushup-20260902](../plans/active/windows-ui-brushup-20260902.md).

This section records local evidence. Tag, Release, device, provider-account and Human gates remain separate.

## Product changes

| Area | Change |
| --- | --- |
| `DesktopSamplerController.setMasterPitch` | Whole-source re-render moved off the UI thread to the project I/O worker. Old-key playback stops at its exact frame, the new key resumes from that frame only if the request is still current (`sourceLoadOperations` epoch), and failures still publish the existing source-playback failure message. |
| `DesktopWavDecoder` | Mono/stereo-preserving imports copy each 64 KiB chunk through a little-endian `ShortBuffer` into `BoundedPcmBuilder.appendAll`; the decoded-frame bound is unchanged. |
| `DesktopApp` | One process-wide `JFileChooser` (still WAV-only, All Files disabled); open/save/export dialogs share the last folder; window minimum 760×600 px. |
| `OtohiroiDeck` | `ConfirmActionButton` disarms after 4 s or when disabled; hover colour/elevation on `MachineButton` and workflow tabs; console width capped at 1600 dp and actually enforced (`fillMaxHeight().widthIn(max)`); landscape CHOP grid shares width (`weight`) instead of claiming width == height; compact BANK labels in landscape rows; status strip 9 sp; mouse-wheel zoom/pan on the CAPTURE source waveform. |
| `WaveformEditor` | Mouse-wheel zoom at cursor / horizontal-wheel pan via `resolveWaveformWheelGesture`. |
| `PadGrid` | Hover ring/tint on pads. |
| `desktop/build.gradle.kts` | jpackage `--java-options -XX:-UsePerfData -Xms160m -Dfile.encoding=UTF-8` (valid on the JDK 17 CI runtime). |

## Parent review repair

- RED: two KEY changes issued while the first asynchronous render was blocked left source playback stopped after the latest render. The focused test failed before the repair and passes at `822305c`.
- Repair: pending playback resume frame is carried across superseding KEY requests; only the latest request may resume it. ALL STOP, competing playback, project replacement, failure, and close clear the intent, and a late stale resume is stopped.
- RED: ALL STOP during the blocked render previously allowed the worker to restart the source afterward. The focused test failed before the repair and passes at `822305c`.
- Destructive confirmation state is keyed to its PAD, pattern slot, or drum-kit target so a first click cannot arm a different target after selection changes.

## Verification (JDK 17.0.20, Gradle 9.7.1, Windows 11)

- `:shared:desktopTest` 90 / `:jvm-core:test` 88 / `:desktop:test` 185 / `:desktop:desktopLongPressUiTest` 24 / `:app:testDebugUnitTest` 284 — failures/errors/skips 0; `:app:compileDebugAndroidTestKotlin` PASS.
- `python -m unittest discover -s scripts/tests` 75 OK; `scripts/check_public_surface.py` PASS (482 candidates); `scripts/validate_project.sh` PASS through Git Bash.
- New tests: `WaveformWheelGestureTest` (4), `BoundedPcmBuilderTest` (3), rapid superseding KEY resume and ALL STOP late-resume denial; the pitch-failure case awaits the asynchronous failure report.
- `:desktop:packageWindows` PASS: 405 files, 176,816,561 bytes. `ChopLab.exe` SHA-256 `4e2a8ad0ea2a114309f28bf80b15478b66e5b7acd0a375fc6319d812d702d7bf`; `desktop-0.17.1.jar` `cf08eda79b68e21b7c528c7499d7aca0bf17bfab9ba2738331a850843d840909`; `shared-desktop-0.17.1.jar` `6d5570f0e720dc82a4dd6f266f94caecb5532826bf9def281de0919f6f93eb6e`. `ChopLab.cfg` carries the three new java-options.
- Exact integration checkpoint `68296f2`: shared Desktop 91, shared Android host 91, Android unit 289, JVM core 88, Desktop 185, long-press UI 24; failures/errors/skips 0. Android lint, debug APK, AndroidTest compilation and Windows package all pass. Full Python policy 207 passes with one local Windows symlink-privilege skip; current/history public scans pass across 488 candidates.
- Final main-ancestry integration `ee1ea29`: full Python policy 213 passes with the same one local Windows symlink-privilege skip; current/history public scans pass across 488 candidates.

## Predecessor startup smoke (`a5f5a17`, packaged app image, 3840×2160 @150 %)

| Launch | Time to titled window |
| --- | --- |
| Previous `v0.17.0` local-run image, warm | 2,074 ms |
| New image, first launch after packaging | 2,609 ms |
| New image, warm launch 2 | 1,598 ms |
| New image, warm launch 3 | 1,590 ms |

Window title `ChopLab — おとひろい PC`, responding, graceful `CloseMainWindow` each time. Maximized (3862×2110 px) shows the console centered at 1600 dp with black margins; restored 1200×900 px shows CHOP with compact BANK labels and no wrapped rows. Captures are kept in the PAD workspace (`work/CHOPLAB_UI_BRUSHUP_EVIDENCE_20260902/`), not in Git.

The startup timing and pointer captures were not repeated after product `822305c`; the review repair changes controller request ownership and confirmation state, not packaging flags or layout dimensions.

## Not verified

Physical audio, actual WAV import timing on a long file, hover/wheel under a real pointer (the fixture covers press/long-press only), Android rendering of the shared changes, accessibility speech, provider/public gates, Human acceptance.
