# Production Dock の操作契約を統一する

## Purpose and user-visible outcome

Capture、Chop、Beat の下端操作を、同じ表示・有効状態・選択状態・確認・操作意図モデルから描画する。画面密度や Quick/Fine の分岐が増えても、ADD/SCRATCH などの主要操作が片方だけ消えたり、停止中に実行可能と表示されたりしない状態を保つ。今回、新しい画面や音声機能は追加せず、現在の見た目と操作結果を維持する。

## Constraints

- 既存の4工程、4 BANK x 32 PAD、非スクロール構成を維持する。
- 再生、保存、PAD、DSP、音声エンジンの契約は変更しない。
- `ProductionDock` のラベルと状態判断を Composable 内へ重複させない。
- 既存の確認操作（RESET ALL）を維持する。
- C: の空き容量が少ないため、生成物と一時領域は既存の F: 側 ChopLab 領域を使う。

## Implementation

1. Capture、Chop、Beat の期待する Dock item を pure Kotlin test で先に固定する。
2. `ProductionDockIntent` と immutable な `ProductionDockItem`、工程別 policy を追加する。
3. 共通 renderer は item list と intent dispatcher だけを受け取り、工程別 Composable からラベル・enabled・active の重複を除く。
4. 既存の操作 callback へ intent を exhaustively 接続する。
5. focused test、full unit、Lint、assemble、offline validation、no-scroll scan、diff review を行う。

## Acceptance

- Capture は素材なしで START CHOP が無効、素材ありで有効、STOPPING 中は無効。
- Capture の RESET ALL は素材がある場合だけ表示され、二段階確認を維持する。
- Chop は BEAT、PAD EDIT、ADD、SCRATCH の順を維持し、各 enabled 状態が pure test と一致する。
- Beat は Quick/Fine の両方で QUICK、STEPS、ADD、SCRATCH を同じ順で表示する。
- UI に scroll API を追加しない。
- full Gradle gate と APK生成が成功する。

## Progress

- [x] 2026-08-13 — GPT Pro 提案、現行コード、既存テスト、Pro移行計画を照合。
- [x] 2026-08-13 — offline validation と既存 unit suite の baseline PASS。
- [ ] RED test。
- [ ] implementation。
- [ ] full validation、review、artifact、commit。

## Rollback

このスライスは UI policy と renderer 接続だけに限定する。問題があれば本スライスの単一コミットを `git revert` し、v0.12.0 の Dock 実装へ戻す。
