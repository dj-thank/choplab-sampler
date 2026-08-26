# Keep Undo and Redo unavailable while production ownership is busy

## User outcome

When ChopLab is opening, importing, saving or exporting a document, or when any recording session owns the production, Undo and Redo are unavailable everywhere. Android/Windows deck buttons and Windows native Ctrl+Z/Ctrl+Y menu items show the same truth. A direct controller request during that interval is rejected before history, project, revision, runtime ownership or autosave admission changes.

## Exact starting point

- Base commit: `1deb8a9ec2198e88fcde07a572bf0a8f9eea333e`
- Base tree: `afcde382a6c0c19dc3d249d96d4dc1d15e4a526f`
- Source product: `63092410f6f249d7b89c49393efe6cfbed349827`
- Source tree: `263c1958ccf0e52fa417fa6254a43bffde392cf0`
- Source worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-history-admission-20260827`
- Integration worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-github-integration-20260827`
- Portfolio receipt: `C:/Users/rambo/Documents/ChatGPT/pad/work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE16_20260827.md`

## Current failure

- Shared deck Undo/Redo used `canUndo/canRedo && !isLoading && !recordingSession.isActive`.
- Windows native Edit menu used only `canUndo/canRedo`, so shortcuts remained advertised and dispatchable while loading/recording.
- Android and Windows controller history entry points rejected active recording but did not reject `isLoading`.
- The policy was duplicated instead of owned as one domain truth.

## Scope and invariants

- Add one pure shared admission decision for history requests and one display-ready enabled predicate per direction.
- Bind the shared deck, Windows native menu, Android controller and Windows controller to that decision.
- Loading denial takes precedence without consuming Undo/Redo or replacing state.
- Recording denial preserves the existing Japanese guidance and active recording session.
- Normal Undo/Redo, missing-history messages and Wave 15 same-owner loop transaction stay unchanged.
- Do not alter archives, schemas, audio interfaces/callbacks, recording start/stop, document I/O, autosave scheduling, project data or release identity.

## TDD and controls

1. Common tests cover idle/loading and every active recording phase, including canUndo/canRedo direction controls.
2. Adapter tests prove denied requests preserve project/history and normal requests move the frontier.
3. Windows menu policy test prevents the native surface from regressing independently.
4. RED against the duplicated/missing policy preceded the smallest shared implementation.
5. A separate controller loading-time test proves project and frontier preservation at the integrated boundary.

## Acceptance evidence

- One shared policy is the loading/recording admission source used by all four boundaries.
- Native menu enabled state equals the shared deck for Undo and Redo.
- Denied controller requests preserve project content, `canUndo/canRedo`, revision-observable state, recording session and active loop/runtime fields; stale unresolved history plans are invalidated without consuming history.
- Normal Undo to Redo remains successful, including Wave 15 active-loop controls.
- Independent Standards/Spec review unresolved findings: `0/0`.
- Focused merged gate: 59 tasks, `BUILD SUCCESSFUL`.
- Full configured single-worker gate: 184 tasks, 39 executed / 145 up-to-date, `BUILD SUCCESSFUL`.
- XML read-back: Android 276 / 47 suites; shared Android 82 / 16; shared Desktop 82 / 16; JVM 88 / 9; Desktop 148 / 24; total 676 / 112 with zero failures, errors or skips.
- Android lint read-back: debug and release each 0 errors / 7 warnings.
- Policy/read-back: Python 64 pass; public current/history each 454 candidates with no credential, signing material or user-audio exposure; configured validation 18 tasks; CycloneDX 650/651 components; unsigned APK positive and signed-required negative controls pass.
- Artifact read-back:
  - `app-debug.apk`: 32,901,372 bytes; SHA-256 `42E0987046F9790EB7A26DEFE9D1544B153892F07B09A1BA9A6D4861A28A86D1`
  - `app-debug-androidTest.apk`: 11,173,354 bytes; SHA-256 `147D47F27C8DF3CD488E29C1EA682B5F85E73837DB0FB084375314C968B4D358`
  - unsigned `app-release-unsigned.apk`: 24,274,036 bytes; SHA-256 `2A25D94847AC0A1F6D0842B9F9E893EDF1DCF53FAA213B08E22A23174E7976C7`
  - `desktop-0.17.0.jar`: 393,533 bytes; SHA-256 `61C619DDBD791A774C4B9F2CCA3051025D7A2917C6AD00FABEED2C211B8508F0`
  - Windows image: 405 files / 176,760,428 bytes; deterministic manifest SHA-256 `8B161FD1604F7ABC26D059292E772EDEA068C9FEFCAFE5B959697345812600E2`
  - aggregate SBOM JSON: 1,581,101 bytes / SHA-256 `9FB3E143B2BF5A38C3BA0B16586AF308024B4ED9B5BCD238CA52179BFAA93D59`; XML: 1,431,320 bytes / SHA-256 `8E6311F678C261C7CF31E2331012CDA5B733165FF23BEEF6B222A1A0B9FB9A2B`
- `git diff --cached --check` passes on the integrated product bytes.

## Gate ceiling

`LOCAL_PASS` only. Tests use synthetic state/fakes and do not claim physical keyboard dispatch, audio continuity, device/provider/public/signing or Human acceptance.

## Progress

- [x] 2026-08-27 — Re-read Wave 15 closeout and protected checkout ownership; selected the cross-platform history-admission mismatch over the larger initial loop-start transaction.
- [x] 2026-08-27 — RED: `:shared:compileTestKotlinDesktop` failed on missing `HistoryRequestDenial`, `historyRequestDenial`, `undoRequestEnabled` and `redoRequestEnabled` (`BUILD FAILED in 39s`).
- [x] 2026-08-27 — Implemented one shared loading/recording admission, deep `ProductionSession` refusal, shared deck + Windows native menu enablement and Android/Windows controller preflight.
- [x] 2026-08-27 — Focused GREEN: shared Android 74 / 14 suites, shared Desktop 74 / 14, Android app 265 / 46 and Desktop 111 / 23; total 524 / 97 with zero failures/errors/skips.
- [x] 2026-08-27 — Adversarial review found that a busy denial initially left an older unresolved history plan committable. Busy planning now invalidates that stale plan without consuming history or advancing revision; the regression control passes. Final focused Standards/Spec unresolved findings: `0/0`.
- [x] 2026-08-27 — Integrated onto Waves 12–15, added a direct loading-time controller preservation test, passed the focused and full configured gates, recorded artifacts and closed the plan.
