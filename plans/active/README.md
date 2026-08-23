# ExecPlan registry

更新: 2026-08-20

このディレクトリには ChopLab の過去の ExecPlan と、将来選択できる計画が保存されています。ファイルが `plans/active/` に存在すること自体は、現在その計画を実行中であることを意味しません。

## Current selection

**LOCAL完了 / DEVICE blocked:** `cross-platform-production-continuity-20260823.md`。AndroidとWindows EXEの共通4工程を基準に、Windowsのsource録音後CHOP遷移、Beatに合わせたVOICE録音、startup project sessionのautosave継続、出力device失敗時の安全停止をpublic controller seamでRED→GREENした。reviewed sourceは`31061be`。`LOCAL_PASS`は閉じ、Pixelは二回のbounded checkで未接続だったため、データ保持型の非録音device sliceは別の再接続taskへ残す。

**完了済み:** `precision-trim-long-press-number-wheel-20260820.md`。PAD長押し後のTRIMで、波形長押し位置へ近い境界を移して最大1秒の精密窓を開き、START/ENDをダイヤル付き数値ホイールとframe/1 ms/10 ms精度で選べるようにした。`v0.16.1-preview.1`へ束縛されたlocal/API 36/public evidenceは保全するが、今回のbranchのdevice evidenceには流用しない。

**完了済み:** `../completed/android-production-continuity-20260820.md`。起動時の制作復元／手動OPEN、CHOPと同じ4×4 PAD素材面を保つBEAT、新規制作だけの初期ドラム、演奏として成立するスクラッチを接続。PR #35、全PR/merged-main CI、`v0.16.0-preview.1` public Releaseとasset read-backまで完了。物理device/Human境界は未昇格。

**診断完了・streaming待ち:** `windows-wasapi-endpoint-probe-20260820.md`。JNA/MMDevice診断とGitHub PR #34は完了。現hostにpresent AudioEndpointがないため、render/loopback streamingは外部状態が変わるまで開始しない。

**完了済み:** `../completed/windows-exe-full-rebuild-20260820.md`。Android-origin shared UI/domainとJVM coreを使うWindows EXEを実装し、local verification、UI A/B、GitHub PR #32、全PR CI、squash merge、全merged-main CIまで完了。device/provider/signing/Human境界は未昇格。

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
- `windows-desktop-ui-fidelity-20260819.md`（shared UI移植の完了済み履歴）
- `windows-wasapi-endpoint-probe-20260820.md`（診断完了、streamingは外部待ち）
- `../completed/android-production-continuity-20260820.md`（完了済み）
- `precision-trim-long-press-number-wheel-20260820.md`（完了済み本文を保全）
- `cross-platform-production-continuity-20260823.md`（現在の選択）

## Selection protocol

計画を選ぶときは、計画本文を現在の source に対して再検証し、対象ファイル、owner、rollback、停止条件、target gate、検証コマンドを追記したうえで、この README の `Current selection` を更新する。device / provider / public / human の作業を含む計画は、local contract の完了後に別 task として分離する。
