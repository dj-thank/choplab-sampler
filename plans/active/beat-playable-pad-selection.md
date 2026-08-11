# Beat playable-PAD selection

## Purpose and user-visible outcome

ビート／重ねる画面で空PAD・空ページ・空BANKを触っても、現在の再生可能な音を見失わないようにする。空の対象は編集対象へ切り替えず、日本語の案内を表示する。ビートへ入った時点で選択中PADが空なら、既存音を優先して自動選択する。

## Current state

- Baseline: `1c88a17402c9c18e3f9590d3940d1552196f36e6` on `main`; work branch `agent/beat-playable-pad-selection`.
- `selectPad` / `selectPadPage` / `selectBank` are assignment and performance surfacesで共用され、空PADも通常選択になる。
- 空PADを選ぶと波形、KEY/TONE/LEVEL、ループ操作がまとめて無効になり、初心者が現在の音へ戻りにくい。
- `outputs/` と `work/` は既存の未追跡成果物として保持する。

## Constraints and invariants

- Figma、スクロール、追加モーダル、追加ボタンは使わない。
- チョップ／PAD割り当て画面では空PAD選択が必要なため、既存の汎用選択を維持する。
- PAD音声、パターン、Undo/Redo、保存形式、自動保存を変更しない。
- ビート／重ねる画面だけが再生可能PAD選択を使う。

## Architecture and agreed test seams

1. `selectPlayablePad`: 空PADでは選択を保持し、案内を返す。
2. `selectPlayablePadPage`: 指定ページの同じ位置、なければ最初の再生可能PADへ移動し、空ページなら保持する。
3. `selectPlayableBank`: 指定BANKの同じ位置、なければ最初の再生可能PADへ移動し、空BANKなら保持する。
4. `ensurePlayablePadSelected`: ビートへ入る際、現在PAD、現在BANK、全BANKの順で再生可能PADを選ぶ。

これらをホスト単体テスト可能な純粋な状態遷移として実装し、`SamplerViewModel` は委譲する。

## Milestones

- [x] RED: 空PAD選択を拒否する公開状態遷移テスト。
- [x] GREEN: PAD、ページ、BANK、ビート入場の再生可能選択ロジック。
- [x] Compose: BeatとSample Layerの選択ハンドラーを専用操作へ接続。
- [x] Validation: host tests、lint、assemble、offline validation、no-scroll、emulator smoke。
- [ ] Review and delivery: Standards/Spec二軸レビュー、コミット、PR、CI、preview release。

## Validation log

- 2026-08-11 — baseline `scripts/doctor.ps1` completed with non-blocking NDK/CMake/ADB PATH/sign-in warnings; `scripts/validate_project.sh` PASS.
- 2026-08-11 — targeted playable-PAD and Beat-lane host tests PASS after observed RED failures for each new seam.
- 2026-08-11 — final reviewed gate PASS: 98 tests, Lint, assemble, offline validation, diff check, and zero scroll APIs. APK SHA-256 `3587D5CCC3BCB216D9E8FA231267420F785206388E4396F8389E023E13C34C20`.
- 2026-08-11 — Pixel 9/API 36 emulator PASS after data-preserving cold boot: v0.9.3 restored the project, Beat selected `A-04`, and empty PAD/page taps preserved that playable selection with guidance.
- 2026-08-11 — local parent two-pass review found no Standards violation and two Spec clarity issues; empty lanes now say `空 / EMPTY`, accessibility announces `空`, and valid selections clear stale empty-PAD guidance.

## Risks and rollback

- 空BANKを選べなくなる範囲をBeat/Layerに限定し、Chopの割り当て導線を壊さない。
- 自動選択は音声やパターンを変更せず、`selectedBank` / `selectedPad` / `statusMessage`だけを更新する。
- 変更は単独コミットでrevert可能にする。
