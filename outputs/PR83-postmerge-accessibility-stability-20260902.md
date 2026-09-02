# PR #83 post-merge accessibility stability receipt — 2026-09-02

## Current object and observed failure

PR #83 was merged by `dj-thank` at `2026-09-02T08:05:39Z` as merge commit `2864117fe3c81b308033155dae337a6030165344`, after the failed Android PR job was rerun successfully. The follow-up starts from the then-current remote `main` `d3291a522699633d5b7426e4fff66ca4f7b55c09`; it does not rewrite PR #83 or its branch.

On PR head `13e41af589ee32018ae6857623b857b9c0356f21`, Android PR run `33583467389`, original job `100102547370`, failed only in `SourceWaveformDeviceTest.frameworkAccessibilityTreeExposesDepthFirstHandlesFocusActionsAndCustomActions`: the first one-shot framework-tree snapshot did not yet contain `選択開始ハンドル`. The parallel Android push run `33583464398` and the later rerun job `100169718860` passed the same API 36 suite, establishing an asynchronous framework-tree readiness race rather than permission to ignore the required check.

## Follow-up repair

Exact follow-up source commit: `8b58e4b294d73e24a75c7a47c06d08502182d69a` / tree `1efc4db4f97588275d2ab532ee19f5a11e720e7f`.

- The test waits for one bounded readiness condition: the current Android framework accessibility tree must expose S, E, and all five numbered chop-marker descriptions.
- Assertions then use a fresh framework snapshot and still require exact depth-first order, accessibility focus actions, and the custom nudge action. Product semantics and acceptance criteria are unchanged.
- The readiness wait is capped at 10 seconds, so a genuinely missing handle continues to fail closed.

## Local verification on current main lineage

- AndroidTest Kotlin compile: `BUILD SUCCESSFUL in 2m 6s`; 42 tasks (40 executed / 2 up-to-date) after the current Compose/Kotlin dependency merge. No device, install, or ADB operation was performed.
- Repository/release policy: 75 tests / 0 failures.
- Public-surface scan: PASS; 478 current-tree candidates, credential/signing/audio candidates 0.
- `git diff --check`: PASS.

## Gate

Ceiling remains `LOCAL_PASS`. A dedicated follow-up PR, all exact-head hosted checks, merge read-back, merged-main checks, and the still-absent `v0.17.1` tag/Release remain mandatory before public release.

## Hosted follow-up and offscreen harness repair

The first follow-up head `b6d88a4` passed its Android push run, but the parallel pull-request run failed before Android build in `DesktopLongPressUiTest.waveformMouseLongPressMovesTheCloserEndAndFocusesOneSecond`. The failure was an `IllegalArgumentException` from Compose UI while the test reused a semantics node obtained immediately after a screen transition; no product assertion failed.

The harness now reacquires each pointer target immediately before input and waits at most one second for exactly one finite, non-empty node fully inside the 1100 × 1000 offscreen viewport. A missing or duplicate node remains a hard failure with the last observed descriptions and bounds. The dedicated Gradle task also emits full exception causes and stack traces on future failures. The exact local 24-test H13 target passes after this repair on JDK 17 / Compose 1.12.0.

A full combined local gate subsequently showed `successfulUserLoadSurvivesAStaleStartupRecoveryFailureDuringClose` finishing in 2.37 seconds while its test joined for exactly 2 seconds. The product correctly waits for the released recovery future; only the outer test observation budget is raised to 5 seconds, with the same assertion that close must terminate and persist the successful user selection.
