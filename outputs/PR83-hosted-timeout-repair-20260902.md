# PR #83 hosted H13 timeout repair — 2026-09-02

> Successor note: the next exact-head review found six additional lifecycle/repository/publication findings. Their repair is recorded in [PR83-third-review-repair-20260902.md](PR83-third-review-repair-20260902.md).

## Hosted observation

The third PR head `4d6792ebbcb66de05f60a9432f9dd50af35f5e8d` started eight push/pull-request checks. The two supply-chain checks passed. The Windows push-event run `33579553035` passed the complete workflow, including all 24 H13 tests. The Windows pull-request run `33579555787` failed only in `DesktopLongPressUiTest.beatPadMouseLongPressPreservesItsRangeAndOpensFittedTrim` with `TimeoutCancellationException` from its per-test 15-second harness budget.

On the failed run:

- all 12 H13 controller tests passed;
- package compilation reached the same source and test classes;
- the exact same head's parallel Windows run passed;
- the failing UI test reported no wrong PAD range, loop state, ownership, render, or semantics assertion—only the coroutine timeout.

Merge remained stopped. The failed check was not rerun or relabeled as a product PASS.

## Repair

Exact test-harness repair: `e1669eecbbba1d38ec28826ddda4c908898ddae9` / tree `126bc2e42a243ed3febc3832bc18c9ba5c20cfe5`.

- All 12 offscreen UI tests now use one named `H13_UI_TIMEOUT_MILLIS = 30_000L` budget.
- Input events, 40/700 ms holds, controller ports, PCM fixtures, render dimensions, assertions, output evidence, and product source are unchanged.
- The internal project-load wait remains five seconds; only the whole-test runner allowance changed.

## Exact local verification

Post-commit command:

```text
:desktop:desktopLongPressUiTest
--offline --rerun-tasks --max-workers=1
```

Result: `BUILD SUCCESSFUL in 1m 21s`; 16/16 actionable tasks executed; 2 XML suites / 24 tests / failure, error, skip 0; XML-manifest SHA-256 `622BE90118D53F91DBDC7A8415DD2BE8C475E47F9A861A318636A729C252B70D`.

Python policy 67/67, public/history scan 474 candidates with credential/signing/audio candidates 0, and `git diff --check` also passed before the test commit.

## Gate

This is a test-harness stability successor to product repair `f16218d`; it changes no production bytes. Ceiling remains `LOCAL_PASS`. A new exact-head hosted run must pass both Windows events and every other platform check before merge.
