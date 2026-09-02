# Windows UI / startup brush-up receipt — 2026-09-02

## Fixed source

- Base: PR #83 head `f16218ddc0eb1e7b8dbdcafbb01ad8b69f6fe6bc` (branch `codex/choplab-h13-v0171-20260902`).
- Lane: `work/choplab-ui-brushup-20260902`, branch `codex/choplab-ui-brushup-20260902`.
- Plan: [windows-ui-brushup-20260902](../plans/active/windows-ui-brushup-20260902.md).

This is local evidence only. No push, PR, tag, Release, device, provider, or Human action was performed.

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

## Verification (JDK 17.0.20, Gradle 9.7.1, Windows 11)

- `:shared:desktopTest` 90 / `:jvm-core:test` 88 / `:desktop:test` 182 / `:desktop:desktopLongPressUiTest` 24 — failures/errors/skips 0.
- `python -m unittest discover -s scripts/tests` 67 OK; `scripts/check_public_surface.py` PASS (476 candidates).
- New tests: `WaveformWheelGestureTest` (4), `BoundedPcmBuilderTest` (3); `DesktopSamplerControllerTest` pitch-failure case now awaits the asynchronous failure report.
- `:desktop:packageWindows` PASS: 405 files, 176,814,833 bytes. `ChopLab.exe` SHA-256 `4e2a8ad0ea2a114309f28bf80b15478b66e5b7acd0a375fc6319d812d702d7bf`; `desktop-0.17.1.jar` `b69275eb51738e5d0efc50f86c270a5bda13f0e3d833ba48be75f869d5af607d`; `shared-desktop-0.17.1.jar` `8646fa61cc417dd464847baf41a6894f8f1c45bea0894f441f75964723c4fdaa`. `ChopLab.cfg` carries the three new java-options.

## Startup smoke (packaged app image, 3840×2160 @150 %)

| Launch | Time to titled window |
| --- | --- |
| Previous `v0.17.0` local-run image, warm | 2,074 ms |
| New image, first launch after packaging | 2,609 ms |
| New image, warm launch 2 | 1,598 ms |
| New image, warm launch 3 | 1,590 ms |

Window title `ChopLab — おとひろい PC`, responding, graceful `CloseMainWindow` each time. Maximized (3862×2110 px) shows the console centered at 1600 dp with black margins; restored 1200×900 px shows CHOP with compact BANK labels and no wrapped rows. Captures are kept in the PAD workspace (`work/CHOPLAB_UI_BRUSHUP_EVIDENCE_20260902/`), not in Git.

## Not verified

Physical audio, actual WAV import timing on a long file, hover/wheel under a real pointer (the fixture covers press/long-press only), Android rendering of the shared changes, accessibility speech, provider/public gates, Human acceptance.
