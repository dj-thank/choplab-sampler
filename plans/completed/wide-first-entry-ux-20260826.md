# Wide first-entry UX

## Outcome

最大化Windowsのpristine first entryで、画面下半分以上が説明のない空白になる問題を修正する。regular non-large-text landscapeだけ、own-audio actionsとoptional demoを2カラムで画面全体へ展開する。compact landscape、portrait、large-textは既存stacked contractを保持する。

## Boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-wide-capture-ux-20260826` / current root task
- Baseline: `2bdf60d21252d490c4d50576375528e395b8f426`
- Product checkpoint: `8b3751e5f56b9b2dd0b0c74f1003283064e45e5b`, tree `2dcbb42bc31c98778d1705182ecec5afbae01e90`
- Audit: parent PAD `work/CHOPLAB_GOAL_WAVE3_AUDIT/AUDIT.md`
- Target gate: `LOCAL_PASS` + scoped current-run Windows screenshot evidence
- No project/audio/schema/I/O behavior change; no ADB/device, OAuth/provider, GitHub/publication, secret or Human action

## Evidence-driven change

- Current-run baseline screenshot showed a maximized 3862×2110 window with every action compressed into the top and an unbounded black lower canvas.
- `FocusedCaptureEntryLayout` admits `WIDE_SPLIT` only for regular landscape without large text. 640×360 compact landscape, large-text landscape and portrait remain `STACKED` by exact policy tests.
- Wide layout places LOAD/OPEN and MIC/DEVICE in a 2×2 left column; DUSTY JAZZ demo is a separate right panel with its own action. Wide action labels use normal 10sp typography instead of compact 8sp.
- No new screen, modal, scroll, action, state mutation or navigation behavior.
- Exact before/after capture uses the same package state, maximized viewport, isolated app data and WAV-cancel flow. The after screenshot removes the dead lower region while preserving stages, NEXT and cancel reassurance.

## Validation

- RED: focused layout test failed compilation before the new policy existed.
- Focused layout and shared Desktop tests PASS.
- `scripts/validate_project.sh`: public-surface baseline 411, executable modes, JVM-core/Desktop 18 tasks, XML/wrapper checks PASS. Documentation-inclusive final public-surface 412 PASS.
- Full gate: 190 tasks PASS. Android 248, shared Android/Desktop 34/34, JVM-core 54, Desktop 80; failures/errors/skips 0.
- Lint errors 0/warnings 7. Debug APK 31,574,130 / `3BB275F43AADF26C34004170F56794CF9D51E97481799FE05DE01D60BE9CD369`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,110,196 / `D8FB290F6F5858D7322B559E42BCAE10C539836482CDC42F9E0BA961DB114F4A`.
- Windows ProductVersion `0.17.0` / EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630` verifier PASS. CycloneDX, Python 40, final public-surface 412 and `git diff --check` PASS.
- Accepted baseline/after screenshots: parent audit `r3/01-start.png` and `after2/01-start.png`; exact runtime processes closed gracefully under isolated app data.

## Gate / next action

This is `LOCAL_PASS` plus scoped Windows visual evidence for the exact package. It does not prove compact screenshot parity, screen-reader speech, physical-device behavior, audio or Human preference. Further visual growth should compare an authorized compact/large-text/device state rather than invent another desktop-only layout.

## Rollback

Product commit `8b3751e` is isolated and migration-free. Revert or decline it to restore the prior stacked first entry.
