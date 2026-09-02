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

The first follow-up head `b6d88a4` passed its Android push run, but the parallel pull-request run failed before Android build in `DesktopLongPressUiTest.waveformMouseLongPressMovesTheCloserEndAndFocusesOneSecond`. Head `f34065e` added input-target reacquisition and full test stacks. Its Android push run `33616031946`, job `100201945805`, then made the remaining cause exact: all product assertions completed, and `ImageComposeScene.close()` failed during fixture teardown with `LayoutNode 1239 not found in RectList` from `RectManager.remove`. The parallel PR run remained in progress; merge stayed stopped.

The harness reacquires each pointer target immediately before input and waits at most one second for exactly one finite, non-empty node fully inside the 1100 × 1000 offscreen viewport. A missing or duplicate node remains a hard failure with the last observed descriptions and bounds. The dedicated Gradle task also emits full exception causes and stack traces on future failures.

Exact teardown repair: `df61ac4406481b0837d9ac4582eaee906f4a658c` / tree `aaa17d7f4893315dcc4c5ca60f18b77064a2310d`.

- Only the exact Compose 1.12 offscreen close signature is classified: `IllegalArgumentException`, message `LayoutNode <digits> not found in RectList`, a `RectManager.remove` frame, and an `ImageComposeScene.close` frame must all be present.
- Unexpected close failures still escape. A deterministic classifier test covers the known signature plus missing-stack and wrong-message negative cases.
- JDK 17 local H13 target: 25 tests / 0 failures; `BUILD SUCCESSFUL in 2m 36s`. No product code, device, install, ADB, audio, or credential operation was involved.

A full combined local gate subsequently showed `successfulUserLoadSurvivesAStaleStartupRecoveryFailureDuringClose` finishing in 2.37 seconds while its test joined for exactly 2 seconds. The product correctly waits for the released recovery future; only the outer test observation budget is raised to 5 seconds, with the same assertion that close must terminate and persist the successful user selection.

The same combined suite also observed a successful zero-delay autosave after the former shared two-second polling deadline. All controller tests now use one five-second asynchronous observation budget; production debounce, recorder, playback, and close deadlines are unchanged.
