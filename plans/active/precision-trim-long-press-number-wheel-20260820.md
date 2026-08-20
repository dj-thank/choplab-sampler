# Precision trim long-press and numeric boundary wheels — 2026-08-20

## Purpose and user-visible outcome

音の入ったPADを長押ししてTRIMを開いた後、波形上の目的位置を長押しすると近いCut境界がその位置へ移り、周囲が最大1秒幅へ拡大される。STARTとENDは、ダイヤル位置と前／現在／次の時刻を示す独立した数値ホイールを縦ドラッグまたはマウスホイールで操作でき、frame・1 ms・10 msの精度を切り替えられる。

## Current state

- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-precision-trim-20260820`.
- Branch: `codex/choplab-precision-trim`; baseline main `923d7bb711d399efdf7ea8726e9a72769f1d97a5`, tree `7c05e6192736f6b16cba8d520bea1bef810eaa67`.
- Dirty canonical checkout remains a separate protected boundary.
- `PadGrid` long press already selects an assigned PAD and opens `PadTrimEditor` without triggering destructive capture.
- `PadTrimEditor` already supports range handles, nearest-boundary tap, preview, entry Revert, and controller updates. Android uses shared `trimPadBoundary`; Windows still has simpler direct clamps.
- `WaveformEditor` supports tap, double-tap zoom, two-pointer zoom/pan, handles, and viewport accessibility actions, but no long-press callback or one-second focus operation.
- `PadTrimPrecision` and `padTrimNudgeFrames` already define frame / 1 ms / 10 ms steps but are not exposed in the current trim UI.

## Constraints and invariants

- Audio frame ranges remain start-inclusive/end-exclusive and at least two frames long.
- Long press and numeric wheels edit only PAD boundary metadata; they never copy/mutate PCM or change the Source asset.
- Continuous wheel changes coalesce through the existing trim merge keys and remain Undo/Redo/Revert compatible.
- The precision viewport is at most one source second and always stays inside the source, including edge presses and sources shorter than one second.
- Touch, mouse drag/wheel, and accessibility increment/decrement share one bounded frame policy.
- No audio callback, archive schema, provider, recording, or physical-device scope change.

## Architecture and interfaces

- `shared/.../model/SamplerCommands.kt`: pure nearest-boundary selection, precision-window bounds, and existing safe boundary mutation.
- `shared/.../ui/WaveformViewportPolicy.kt`: pure focus viewport from frame + target visible frames.
- `shared/.../ui/WaveformEditor.kt`: optional long-press focus/callback, preserving all existing callers through defaults.
- New shared trim-control file: dial-backed START/END numeric wheels and precision selector.
- `OtohiroiDeck.PadTrimEditor`: owns local active boundary/precision display state and routes intents through `SamplerDeckController`.
- Android and Windows controllers keep platform side effects but share the same trim clamping functions.

## Milestones

### Milestone 1: pure trim and viewport policy

- Add RED tests for nearest-boundary ties, source-edge precision windows, one-second maximum, short sources, and focused viewport geometry.
- Implement the smallest shared pure functions.

### Milestone 2: long press and wheels

- Add WaveformEditor long-press callback/focus with truthful semantics copy.
- Add two numeric boundary wheels with dial progress, previous/current/next values, drag/wheel input, accessibility actions, and precision selection.
- Preserve existing tap/handle/Preview/Revert behavior.

### Milestone 3: controller parity and validation

- Align Windows START/END/Revert clamping with shared Android policy.
- Run focused/full tests, lint, APK, JVM/Windows package, public-surface and project validation.
- Verify PAD long press→TRIM, waveform long press→≤1 s focus, and both numeric wheels on API 36 emulator.

### Milestone 4: review and GitHub delivery

- Run local parent Standards/Spec review if Luna runtime remains unverifiable.
- Update SSOT/feature matrix/evidence, commit, push one PR, wait for Android/Windows/iOS checks, merge only when green, and record PAD receipt.

## Progress

- [x] 2026-08-20 — Created isolated clean worktree at exact main baseline and preserved dirty canonical checkout.
- [x] 2026-08-20 — Mapped existing PAD long-press, trim editor, waveform viewport, controller, and test seams.
- [x] Milestone 1 — nearest-boundary、1秒window、focused viewport、overflow-safe boundary stepをRED→GREENで実装。
- [x] Milestone 2 — 波形長押し、START/END数値ホイール、ダイヤル、frame/1 ms/10 ms、accessibility action、portrait full TRIMを実装。
- [x] Milestone 3 — Android/JVM/desktop full gate、Lint、Windows package、project/public-surface validation、API 36 instrumentation 6/6と手動操作を完了。
- [ ] Milestone 4.

## Discoveries

- Existing imports already anticipated `PadTrimPrecision`/`padTrimNudgeFrames`, but the visible trim UI never used them.
- A simple long-press callback is insufficient for ten-minute Sources because the current `maximumZoom = 256` cannot always reach a one-second viewport; focus policy must derive zoom from target visible frames.

## Decision log

- 2026-08-20 — Long press moves the nearer boundary and focuses without replacing audio. Ordinary tap keeps its existing nearest-boundary move without forced focus.
- 2026-08-20 — Combine dial feedback and numeric scrolling in each START/END wheel; avoid a separate modal keypad and keep both boundaries visible together.

## Validation log

- 2026-08-20 — Focused Android unit、Android compile/assemble、desktop test PASS。最新APKをAPI 36 `medium_phone(AVD) - 16`へdata-preserving installし、PAD長押し→TRIM、波形長押し→3:38.374–3:39.374の1秒窓、ZOOM+→同位置3:38.624–3:39.124の0.5秒窓、10 ms wheel→END +10 ms、Revert→元境界＋1.0xを画面で確認。
- 2026-08-20 — `:app:connectedDebugAndroidTest` 6/6 PASS。long pressが通常tapを発火しないこと、100-frame focus、数値wheel swipeをinstrumentationで確認。
- 2026-08-21 — Local parent Standards/Spec二軸review。Standardsでabsolute frame差分のoverflow余地と38 dp precision targetを検出し、shared setter＋extreme regression、48 dp化で解消。frame modeは6桁sub-ms表示へ改善。focused post-review build/testとAPI 36 visual recheck PASS。最終未解決はStandards 0 / Spec 0。

## Risks and rollback

- Gesture recognizer conflict: long press must not also emit a tap or interfere with pinch/pan. Verify gesture ordering and emulator behavior.
- Small screens: controls must remain readable without removing Preview/Revert. Use one compact shared row and existing responsive workspace.
- Rapid wheel updates could flood history. Reuse named trim merge keys and test resulting bounds; no new persistence state.
- Rollback is a normal revert of this branch; no schema migration or destructive asset change exists.

## Remaining device validation

- Physical long-press timing/haptics, high-refresh drag feel, TalkBack rotor/custom-action speech, audible click quality at newly selected boundaries, and Human acceptance.
