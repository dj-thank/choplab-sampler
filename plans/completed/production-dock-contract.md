# Production Dock の操作契約を統一する

## Purpose and user-visible outcome

Capture、Chop、Beat の下端操作を、同じ表示・有効状態・選択状態・確認・操作意図モデルから描画する。画面密度や Quick/Fine の分岐が増えても、ADD/SCRATCH などの主要操作が片方だけ消えたり、停止中に実行可能と表示されたりしない状態を保つ。実機検証で見つかった起動時 autosave 復元待ちも `NO SOURCE` ではなく `LOADING / 音声を読込中` と表示し、データ消失に見える空白時間をなくす。新しい画面や音声機能は追加しない。

## Constraints

- 既存の4工程、4 BANK x 32 PAD、非スクロール構成を維持する。
- 再生、保存、PAD、DSP、音声エンジンの契約は変更しない。
- `ProductionDock` のラベルと状態判断を Composable 内へ重複させない。
- 既存の確認操作（RESET ALL）を維持する。
- C: の空き容量が少ないため、生成物と一時領域は既存の F: 側 ChopLab 領域を使う。

## Implementation

1. Capture、Chop、Beat の期待する Dock item を pure Kotlin test で先に固定する。
2. `ProductionDockIntent` と immutable な `ProductionDockItem`、工程別 policy を追加する。
3. 共通 renderer は item list と handler map だけを受け取り、工程別 Composable からラベル・enabled・active と重複 `when` を除く。
4. 起動時 autosave の開始・空結果・失敗・成功で `isLoading` が実際の復元状態と一致する pure reducer を追加する。
5. 復元中は新規取込を無効化し、既に録音中ならSTOPだけを維持する。status、波形、status strip は `LOADING / 音声を読込中 / PLEASE WAIT` に統一する。
6. focused test、full unit、Lint、assemble、offline validation、no-scroll scan、diff review、接続済み実機確認を行う。

## Acceptance

- Capture は素材なしで START CHOP が無効、素材ありで有効、STOPPING 中は無効。
- Capture の RESET ALL は素材がある場合だけ表示され、二段階確認を維持する。
- Chop は BEAT、PAD EDIT、ADD、SCRATCH の順を維持し、各 enabled 状態が pure test と一致する。
- Beat は Quick/Fine の両方で QUICK、STEPS、ADD、SCRATCH を同じ順で表示する。
- autosave 復元中は `NO SOURCE` と誤表示せず、復元完了後に従来の制作状態へ戻る。
- UI に scroll API を追加しない。
- full Gradle gate と APK生成が成功する。

## Progress

- [x] 2026-08-13 — GPT Pro 提案、現行コード、既存テスト、Pro移行計画を照合。
- [x] 2026-08-13 — `scripts/validate_project.sh` と既存 `:app:testDebugUnitTest` baseline PASS。
- [x] 2026-08-13 — Dock契約と復元状態／表示の focused tests を未実装参照で RED、実装後 GREEN。
- [x] 2026-08-13 — `ProductionDockPolicy`、共通 handler renderer、autosave recovery reducer と truthful loading UI を実装。
- [x] 2026-08-13 — final `scripts/validate_project.sh` PASS。Gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1` BUILD SUCCESSFUL。169 tests / 37 suites、failures/errors/skips 0、Lint errors 0 / warnings 7。
- [x] 2026-08-13 — `git diff --check` PASS、UI scroll API scan 0 matches。
- [x] 2026-08-13 — APK `outputs/ChopLab-v0.12.0-production-dock-contract-local-debug.apk`、31,615,690 bytes、SHA-256 `B0CF6B6DFE21FF24B5AC5BD457E6EEE637B75BFDB4EA438044CB84A5A07B1C29`。package `com.choplab.sampler`、versionCode 19 / versionName 0.12.0、minSdk 29 / targetSdk 36、APK Signature Scheme v2。
- [x] 2026-08-13 — Pixel 9a Android 17/API 37へ exact APKを `adb install -r`。端末Download／installed base／hostのサイズ・SHA-256一致。起動途中の `LOADING / 音声を読込中 / PLEASE WAIT`、FILE/MIC REC/DEVICE REC親ボタンの`enabled=false`、復元後のCapture/Chop/Beat、各0 scrollable node、Chop/Beat Dock全項目、focused fatal/ANR 0を観測。
- [x] 2026-08-13 — 実機の4 project archiveはinstall前、install直後、復元・工程移動後でサイズとSHA-256不変。
- [x] 2026-08-13 — local parent Standards／Spec二段階レビュー。Dock固有の解消範囲と録音STOP例外を文書へ限定し、残るmaterial finding 0。

## Discoveries

- 約32 MBのautosave復元はPixel 9aで十数秒かかり、旧UIはその間だけ `NO SOURCE` を表示していた。データ自体は正常で4 archiveのhashも不変だったため、復元状態を公開して競合操作と誤解を防ぐ修正へ広げた。
- 最初のreview routeはchild runtime metadataを返さなかったため、Luna証拠として採用しない。最終差分は親でStandards／Specを分離して再レビューする。
- C: 容量不足を避けるため、再生成可能な `app/build` と既存 `outputs` を `F:\CodexData\ChopLab` 配下へ移し、元のworkspace pathにはjunctionを保持した。

## Evidence

- 実機: `work/pixel9a-v0120-dock-contract/final4-loading.{png,xml}`、`final4-capture.png`、`final4-restored.xml`、`final4-chop.{png,xml}`、`final4-beat.{png,xml}`、`installed-base-final4.apk`。
- 保存hash: autosave `f9f96c49…8b43`、pending `45b17581…334`、previous `ed01bba6…ed71`、previous2 `0946a0d7…c45`。

## Rollback

このスライスは UI policy、renderer接続、起動時復元表示だけに限定する。問題があれば checkpoint `98e5c34` と後続のintegration commitを通常の `git revert` で戻し、`553f203` 時点の v0.12.0 Dockへ復帰できる。project schema、PCM、保存byteは変更していない。
