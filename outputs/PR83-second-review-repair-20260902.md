# PR #83 second review repair receipt — 2026-09-02

> Successor note: the first hosted run of this repair exposed one 15-second offscreen-test timeout while the parallel Windows run passed. The bounded harness-only correction is recorded in [PR83-hosted-timeout-repair-20260902.md](PR83-hosted-timeout-repair-20260902.md).

## Review input and stop

PR [#83](https://github.com/dj-thank/choplab-sampler/pull/83) reached head `f438a512035bb1791e3d466d7bbe6a467f0c4b6b`. All eight push/pull-request hosted checks passed and the first four review threads were resolved with exact RED/GREEN replies. The fresh exact-head review then opened three additional P2 threads, so merge was stopped again:

1. TRIM PREVIEW of a saved LOOP PAD entered the performance loop transaction instead of playing one bounded preview voice;
2. Narrator/Compose semantics activation of a stopped CHOP GATE PAD had no paired release;
3. ordinary Java Sound replacement could start a new GATE candidate and then lose its ownership if retirement of the prior voice failed.

## RED feedback loops

- The dedicated H13 task first executed 23 tests and failed exactly three new assertions: bounded LOOP preview, semantics GATE release, and post-start candidate cleanup.
- `JavaSoundWavPlayerTest.retirementFailureAfterOrdinaryCandidateStartClosesTheCandidateBeforeThrowing` separately failed against the actual replacement helper because the started candidate remained open after prior-voice retirement failed.
- A rapid two-click semantics control was added so an earlier delayed release must target ownership 1 while the later voice retains ownership 2.
- A candidate-cleanup-failure control requires both prior and candidate voices to remain owned when their first close attempts fail, allowing `stopAll()` to retry both exact resources.

The feedback loops use synthetic PCM, fake controller ports, and proxy `Clip` objects. They do not open an OS audio device, record, import user content, or contact Spotify/OAuth.

## Repair

Exact product repair: `f16218ddc0eb1e7b8dbdcafbb01ad8b69f6fe6bc` / tree `1ac8db94bcfdaf46adf3cbc8156afec316e13e10`.

- `previewPad` bypasses performance routing and starts a copied `ONE_SHOT` PAD directly, preserving the saved LOOP/GATE mode while keeping PREVIEW bounded.
- `capturePadWithOwnership` is now a shared controller API implemented by both Android and Desktop controllers. Physical capture presses and semantics actions retain their own returned token instead of sharing a controller-global slot.
- GATE semantics activation uses the same owned capture/performance route as pointer input, then issues an exact release after the existing 80 ms minimum preview. Coroutine cancellation still executes the paired release.
- `PadTriggerOwnership.NONE` is the uniform no-voice token. Desktop ignores both that sentinel and legacy zero at the release boundary.
- If conflict retirement fails after candidate startup, `startReplacementBeforeRetiringConflicts` abandons the new candidate before rethrowing. A candidate whose cleanup also fails remains in engine ownership and is retried by `stopAll()`.
- Android `SamplerViewModel` returns the admitted realtime ownership from the same existing capture routing; no Android audio engine algorithm or project schema changed.

## Exact post-commit verification

### Windows / Desktop

The fixed commit ran the complete Windows workflow set with JDK `17.0.20`, Gradle `9.7.1`, `--offline --rerun-tasks`, one worker, and in-process Kotlin compilation:

```text
:shared:desktopTest
:jvm-core:test
:desktop:test
:desktop:desktopLongPressUiTest
:desktop:packageWindows
```

Result: `BUILD SUCCESSFUL in 3m 24s`; 27 actionable tasks / 27 executed.

| Test set | XML suites | Executions | Failure | Error | Skip | XML-manifest SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| shared Desktop | 17 | 86 | 0 | 0 | 0 | `0B34151397218A3CE8CAFDC8C828117EAA22118F0BE1F5859C9AA32AFFE0A813` |
| JVM core | 9 | 88 | 0 | 0 | 0 | `272290D1219A5D174DFF1E93BF84271174B084F65E94045765E28712E8B32376` |
| Desktop default | 24 | 179 | 0 | 0 | 0 | `F65365068E57889F3F608412BF145FD27DADEF38A6A110E5176A3777CC778103` |
| H13 actual-input target | 2 | 24 | 0 | 0 | 0 | `75FB3C13A3CA68F7EC0FAF93D2C40AD9B5AC36E2BADA3AB1BF806FBE4B9F623C` |

This is 377 executions and 365 unique tests because the 12 H13 controller tests intentionally run in both Desktop default and the dedicated target.

One earlier two-worker `--rerun-tasks` run failed two unchanged autosave timing tests at their two-second wait boundary. Both exact tests immediately passed alone with one worker, and the complete one-worker rerun above passed without changing those tests or production autosave code. This transient harness-load result is retained here rather than counted as a product PASS.

- Windows app-image: 405 files / 79 directories / 176,794,876 bytes.
- `ChopLab.exe`: ProductVersion/FileVersion `0.17.1`; SHA-256 `4E2A8AD0EA2A114309F28BF80B15478B66E5B7ACD0A375FC6319D812D702D7BF`.
- `desktop-0.17.1.jar`: 414,839 bytes; SHA-256 `A16A863A7E477163000DFF6304C1FB14B808A39F3AE30EF68AF07AE9B4E46CF0`.

### Android / shared host

- `:app:testDebugUnitTest --offline --rerun-tasks --max-workers=1`: `BUILD SUCCESSFUL in 2m 17s`; 41/41 tasks executed; 50 XML suites / 284 tests / failure, error, skip 0; XML-manifest SHA-256 `9F7766972EEBCA1A7D88C61479FEC6D27D0A80E5B1E3F79E02FED351162DB08B`.
- `:shared:testAndroidHostTest --offline --rerun-tasks --max-workers=1`: `BUILD SUCCESSFUL in 1m 1s`; 8/8 tasks executed; 17 XML suites / 86 tests / failure, error, skip 0; XML-manifest SHA-256 `6B6E9443D9071E00784D2357348195D355BF6BAB48D782CC085AA1BFC839CD88`.

The first offline Android/host preflights stopped before tests because current public Compose/AndroidX artifacts were not cached. Those public build dependencies were fetched once; the exact recorded runs above then completed offline.

### Policy and review

- Python repository/release policy: 67 tests / 0 failures.
- Current plus reachable-history public-surface scan: 473 candidates; credential, signing, and audio candidates 0.
- Release metadata: `0.17.1 (28)` / tag `v0.17.1` PASS.
- `git diff --check`: PASS.
- Final parent Standards pass checked cross-platform interface compatibility, per-gesture ownership, coroutine cancellation, sentinel handling, Java Sound cleanup ownership, release identity, and artifact policy. Final parent Spec pass replayed all three review symptoms plus rapid-semantics and cleanup-retry controls. Unresolved local findings: Standards 0 / Spec 0.

Across the exact recorded Windows, Android app, and shared Android host sets there are 747 executions and 649 unique tests after removing the H13 controller and cross-host shared duplicates.

## Gate

Ceiling: `LOCAL_PASS` for repaired source, Android/shared host contracts, real offscreen Desktop input, Java Sound ownership helpers, and Windows package construction. The branch must be pushed, all hosted checks rerun on the new exact head, all three new threads answered/resolved, and a fresh exact-head review must report no further actionable findings before merge. No provider/public/device/physical-audio/accessibility-speech/Human gate is inferred from this receipt.
