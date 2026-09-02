# PR #83 review repair receipt — 2026-09-02

> Successor note: a fresh review of the first repair found three additional ownership/preview issues. Their exact RED/GREEN repair is recorded in [PR83-second-review-repair-20260902.md](PR83-second-review-repair-20260902.md).

## Review input and stop

PR [#83](https://github.com/dj-thank/choplab-sampler/pull/83) first reached head `b1ce88d51cc842e7de3f663276c756feb9b663cd`. All eight push/pull-request hosted checks passed, but the exact-head automated review opened four unresolved P2 threads, so merge was stopped:

1. a persisted LOOP PAD was auditioned as an untracked raw voice;
2. XcodeGen defaults remained `0.17.0 (27)` while Gradle metadata was `0.17.1 (28)`;
3. an output-startup exception could cancel the gesture before long-press opened TRIM;
4. a broad GATE pointer-up could stop a newer keyboard-owned voice.

## RED feedback loops

- `:desktop:desktopLongPressUiTest` on the reviewed head plus test-only repro changes executed 18 tests and failed the four exact playback/gesture assertions: managed LOOP state, GATE stale release, recoverable audition failure, and the real Compose long-press failure path.
- `ReleaseMetadataTest.test_xcodegen_defaults_match_release_metadata` failed because `ios/project.yml` contained `0.17.0` / `27` instead of the release SSOT's `0.17.1` / `28`.

The repros used synthetic PCM and silent/fake audio ports. No device, native audio line, recording, Spotify/OAuth, or user content was used.

## Repair

Exact product repair: `260ad5e82e2bd79dbe3e168d455ef5d4280637eb` / tree `c3d2633f775cf86f14cda44bcf99dfcbc55fef3d`.

- Desktop assigned-PAD playback now applies the existing shared `resolvePerformancePadPressAction`; LOOP requests enter the existing exclusive loop-session transaction and publish `loopingPadIndex` only through that path.
- Capture-mode GATE auditions retain the exact player ownership returned at pointer-down. Pointer-up uses `releasePadIfOwned`; failed startup retains a no-voice sentinel so it cannot become a later broad release.
- Capture audition catches and reports only `Exception`. Fatal `Error` remains observable and is covered by a negative test.
- `ios/project.yml` now embeds `0.17.1 (28)`, with a policy regression that compares XcodeGen defaults to `gradle.properties`.
- The dedicated H13 target expanded from 14 to 20 tests: 10 real Compose mouse-input tests and 10 controller tests.

## Exact post-commit verification

The fixed commit ran the Windows workflow task set with JDK `17.0.20`, Gradle `9.7.1`, `--offline --rerun-tasks`, two workers, and in-process Kotlin compilation:

```text
:shared:desktopTest
:jvm-core:test
:desktop:test
:desktop:desktopLongPressUiTest
:desktop:packageWindows
```

Result: `BUILD SUCCESSFUL in 2m 17s`; 27 actionable tasks / 27 executed.

| Test set | XML suites | Executions | Failure | Error | Skip | XML-manifest SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| shared Desktop | 17 | 86 | 0 | 0 | 0 | `F7D660A14AE37673953331B23EEEB68BD84DE86C27E21C310E35C2A9C74D9BFF` |
| JVM core | 9 | 88 | 0 | 0 | 0 | `82DDEABC76B2630758916EEBD117CD99D789735C3F5AF02056DE8BD7D1B8ECFA` |
| Desktop default | 24 | 175 | 0 | 0 | 0 | `9B26B7DF172466D0B292DB6987EABEDFFB13450233FDD2078B3D80633B15F73F` |
| H13 actual-input target | 2 | 20 | 0 | 0 | 0 | `DFB53835E232D9A9EECEF3F60EFE1EF59D8362B7A8C6AE1E05B77650AEB9DCCF` |

This is 369 executions and 359 unique tests because the 10 H13 controller tests intentionally run in both Desktop default and the dedicated target.

- Python repository/release policy: 67 tests / 0 failures.
- Current plus reachable-history public-surface scan: 472 candidates; credential, signing, and audio candidates 0.
- Windows app-image: 405 files / 79 directories / 176,784,661 bytes.
- `ChopLab.exe`: ProductVersion/FileVersion `0.17.1`; SHA-256 `4E2A8AD0EA2A114309F28BF80B15478B66E5B7ACD0A375FC6319D812D702D7BF`.
- `desktop-0.17.1.jar`: 414,645 bytes; SHA-256 `95B91440B4BB839FC700DDEA394F3E3FE6F0BE99B091071F70A16D835A1C5594`.
- `git diff --check`: PASS.

## Review and gate

Final local Standards review checked controller ownership/lifecycle, workflow reachability, release SSOT, test isolation, public-surface policy, and fatal-vs-recoverable behavior. Final local Spec review checked each of the four review symptoms against its RED/GREEN regression. Unresolved local findings: Standards 0 / Spec 0.

Ceiling: `LOCAL_PASS` for the repaired source and Windows package construction. The PR head must be pushed, all hosted checks rerun on the new exact head, and every review thread resolved before merge. No provider/public/device/audio/accessibility/Human gate is inferred from this receipt.
