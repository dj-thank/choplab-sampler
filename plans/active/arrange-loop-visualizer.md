# Arrange Loop Visualizer

## Purpose and user-visible outcome

「4 並べる」を、音楽制作が初めてでも現在位置と繰り返しを理解できる固定画面にする。選択した PAD の実波形、16 ステップの再生位置、全 BANK の発音位置を同じタイムラインに表示し、4つ打ち・8分・16分の反復をワンタップで作れるようにする。PLAY の 4×4 PAD と、元の HTML 由来の即触れる流れは維持する。

## Current state

- 改善開始点は `main` の `14648c8`、公開版は `v0.5.0-preview.1`。
- 16-step transport と 64 PAD 分の重ね再生は既に engine/ViewModel に接続されている。
- Arrange は選択 PAD の 16 ステップだけを編集できるが、現在位置は細い枠のみで、波形・BANK 横断の重なり・反復作成が見えない。
- 2026-08-10 に API 36 emulator で Capture → Chop → Play → Arrange を同一状態で撮影した。監査画像はタスク workspace の `work/choplab-arrange-audit/` に保存している。

## Constraints and invariants

- スクロール API を導入せず、portrait/landscape とも固定画面内に収める。
- PLAY の 4×4 PAD と既存の音声 import/chop/pad trigger を壊さない。
- 反復操作は選択 PAD のイベントだけを置換し、他 PAD・他 BANK のイベントを保持する。
- 波形は実際の PCM と slice range から描画し、装飾用の偽波形を使わない。
- 色だけに依存せず、BANK 文字・ステップ番号・選択状態・再生位置を併用する。
- 音声 callback に allocation、lock、I/O、ログ、UI 呼び出しを追加しない。
- `reference/pro-v0.2/` と proprietary MPC asset/trade dress は変更・模倣しない。

## Architecture and interfaces

- `model/PatternEditing.kt`: 反復配置、選択 PAD クリア、step ごとの BANK activity を pure function として公開する。
- `SamplerViewModel`: `fillSelectedPadPattern(RepeatGrid)` を edit history と engine sync の既存経路へ接続する。
- `ArrangementWaveformTimeline`: 選択 slice の bounded waveform、16 分割、playhead、BANK markers を Compose Canvas で描画する。
- `PadGrid`: 列数を引数化し、PLAY は既定 4×4、Arrange portrait は 8×2 の compact selector として再利用する。
- `SelectedPadQuickEditor`: KEY の高さ/原音表示と TONE の意味名を追加し、初心者が数値だけを解釈しなくてよい表示にする。

## Milestones

1. RepeatGrid と BANK activity の public behavior を failing unit tests で固定する。
2. Pure domain と ViewModel intent を実装し、選択 PAD だけを安全に書き換える。
3. 実波形タイムライン、明確な playhead、全 BANK marker、反復 row を固定 UI に統合する。
4. KEY/TONE 表示と beginner coach を改善し、portrait/landscape の clipping を修正する。
5. targeted/full unit、lint、assemble、scroll scan、diff check、独立 review を通す。
6. emulator と Pixel 9a で install/launch/主要 Arrange flow を確認し、公開 PR/CI/release APK を作る。
7. 反復の発見性とボタン密度を再監査し、通常の3手順と`細かく調整`へ段階表示する。

## Progress

- [x] 2026-08-10 - v0.6.0 full local validation, dual-axis review, PR/main/tag CI, public prerelease, exact public APK emulator smoke, Pixel 9a install, and verified autosave restoration completed.

- [x] 2026-08-10 — 現行 Arrange を emulator で撮影し、波形・playhead・BANK layering・tone clarity の不足を監査。
- [x] 2026-08-10 — RepeatGrid/BANK activity、audible layer filtering、repeat recognition、tone preset の Red/Green。44 unit tests PASS。
- [x] 2026-08-10 — ViewModel と portrait/landscape Compose UI を統合。
- [x] 2026-08-10 — local validation と Standards/Spec 二軸review。修正後のmaterial finding 0。
- [x] 2026-08-10 — PR #4、main/tag CI、公開Release、公開APKのemulator/Pixel 9a install-launch-state migration evidence。
- [x] 2026-08-10 — v0.5.0実機相当画面を再監査。反復見出し欠如、無効時の低発見性、主要/上級操作の同時露出を確認。
- [x] 2026-08-10 — `1 PADを選ぶ → 2 反復を選ぶ → 3 ビートを聴く`と`細かく調整`の段階表示を実装し、portrait/landscapeで固定画面確認。
- [ ] v0.6.0 full validation、review、public/device evidence。

## Decision log

- 2026-08-10 — Arrange だけを 8×2 PAD selector に圧縮し、PLAY はライブ演奏用 4×4 のまま維持する。
- 2026-08-10 — 反復プリセットは選択 PAD の現行ステップを置換する。他 PAD/BANK は重ね音として保持する。
- 2026-08-10 — tone は連続値を残しつつ、compact UI では「暗い・なじむ・原音」の意味名付き preset を巡回する。
- 2026-08-10 — 通常Arrangeは初心者の3手順だけを主役にし、16-step/BPM/Swing/REC/CLEAR/KEY/TONE/LEVELは同一画面の`細かく調整`へ切り替える。削除・スクロール追加はしない。
- 2026-08-10 — AIは未動作ボタンを増やさず、将来1つの`AIで整える`入口から非破壊proposalを出す。試聴、差分、Undo、明示同意を実装前提とする。

## Validation log

- Baseline: `scripts/validate_project.sh` と `:app:testDebugUnitTest` PASS、37 tests。
- Visual baseline: API 36 emulator、1080×2424、Capture/Arrange stopped/Arrange playing を保存。
- 2026-08-10: `scripts/validate_project.sh`、44 unit tests、Lint zero issues、assemble、scroll scan 0、`git diff --check` PASS。
- 2026-08-10: API 36 emulator で A-01 4つ打ち + B-01 8分を同時再生。portrait/landscape とも固定画面内で waveform/playhead/BANK markers/repeat/KEY/TONE を確認。
- 2026-08-10: review後、空PADをBANK markerから除外し、全16-stepのTalkBack説明、columns range guard、truthful semitone表示、空BANKから`叩く`への追加導線を再検証。
- 2026-08-10: PR #4をmerge commit `48c645e`として公開。main/tag Android verificationとrelease workflow PASS。公開APK 30,297,035 bytes、SHA-256 `DB3EC8CC7B23C7DFB82547FBFC10DFEC59A11BFE11AF707AD24DC2CEBF16C4F1`。
- 2026-08-10: 公開APKをPixel 9 AVDとPixel 9aへ導入・起動。Pixel 9aでは旧autosaveを内容非表示でbackupし、同一SHA-256のまま公開版へ復元。APKは端末Downloadへ保存。
- 2026-08-10: v0.5.0同条件baselineとv0.6.0 quick/fineをPixel 9 AVDで比較。音入りA-01選択→4つ打ち→再生でstep 1/5/9/13、active preset、playheadを確認。landscapeもvisible clippingなし。
- 2026-08-10: reviewで反復presetのtouch targetが約34.7dpと判明。panel高を調整し、最終UI hierarchyで127px / 420dpi（約48.4dp）を確認。

## Risks and rollback

- 固定画面の密度が高い。touch target を保ち、portrait screenshot と accessibility text で clipping を検査する。
- Canvas の PCM 走査が再描画コストになる。表示幅に bounded down-sampling し、audio engine には触れない。
- 反復置換は undoable edit として既存 history 経路を通す。問題時は domain/ViewModel/UI の各 commit を `git revert` できる粒度に保つ。
- 物理端末はユーザー利用中の可能性があるため、開発中は emulator を使い、最終 install/smoke のみ短時間で行う。
