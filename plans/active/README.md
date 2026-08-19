# ExecPlan registry

更新: 2026-08-19

このディレクトリには ChopLab の過去の ExecPlan と、将来選択できる計画が保存されています。ファイルが `plans/active/` に存在すること自体は、現在その計画を実行中であることを意味しません。

## Current selection

**進行中:** `windows-desktop-ui-fidelity-20260819.md`。既存 Android ChopLab UIを正本として、Windows Desktop EXEへ同じ Compose UI、4工程、文言、source/chop、4×4 PAD、PAD editor、BANK、production dock、beat step sequencerを共有移植する。Spotify Content の取得・録音・stream ripping・MP3化は対象外。target gate は `LOCAL_PASS` のみ。

**完了済み:** `restart-playback-interruption-local-20260819.md`。`app/` の playback interruption local contract を focused local test と source inspection で再確認した。実装差分はなく、Pro 統合、Pixel 9a、device/provider/public 検証は開始していない。

## Retained plans

次のファイルは、過去の実装・設計・検証の文脈として保全している未選択計画です。再利用する場合は、現在の `HEAD`、`docs/PROJECT_STATE.md`、`docs/FEATURE_MATRIX.md`、対象コードを再確認し、current selection に一つだけ登録します。

- `arrange-loop-visualizer.md`
- `bank-role-safe-playback-ui.md`
- `beat-playable-pad-selection.md`
- `choplab-pro-integration.md`
- `choplab-public-preview-release.md`
- `drum-vocal-scratch-workstation.md`
- `luna-interaction-integrity-v013.md`
- `mpc-frontend-reorganization.md`
- `otohiroi-aaa-fixed-console.md`
- `otohiroi-canonical-ui.md`
- `otohiroi-guided-mpc-workflow.md`
- `playback-interruption-safety.md`
- `precision-trim-exclusive-playback.md`
- `simple-chop-project-isolation.md`
- `v011-safety-coaching.md`
- `waveform-device-evidence-hardening.md`
- `restart-playback-interruption-local-20260819.md`（完了済み本文を保全）
- `windows-desktop-mvp-20260819.md`（初期プロトタイプの本文を保全）
- `windows-desktop-ui-fidelity-20260819.md`（現在の選択）

## Selection protocol

計画を選ぶときは、計画本文を現在の source に対して再検証し、対象ファイル、owner、rollback、停止条件、target gate、検証コマンドを追記したうえで、この README の `Current selection` を更新する。device / provider / public / human の作業を含む計画は、local contract の完了後に別 task として分離する。
