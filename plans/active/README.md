# ExecPlan registry

更新: 2026-08-23

このディレクトリには ChopLab の過去の ExecPlan と、将来選択できる計画が保存されています。ファイルが `plans/active/` に存在すること自体は、現在その計画を実行中であることを意味しません。

## Current selection

**LOCAL完了 / scoped Android DEVICE完了 / 外部gate分離:** `session-integration-20260823.md`。product source `6914e3c`でfull release/audio hardening、Spotify metadata/control-only UX、Windows production continuityを一つのclean local candidateへ統合した。clean 184-task gate、226 Android / 49 JVM / 66 desktop、package／SBOM／public-surface／UI contract／runtime smokeがPASS。Android bytesとsource inputsはaccepted `8306ed2` Pixel receiptに完全一致するため、その非録音scopeだけ`DEVICE_PASS`。provider/public/Humanは未到達。

**統合入力・LOCAL/DEVICE receipt保全:** `spotify-connect-ux-lifecycle-20260823.md`。source/device receipt `8306ed2`のOAuth lifecycle、provider-state UI、release/audio hardening、Pixel 9a data-preserving instrumentationを保全する。実Spotify account/provider、公開、Human評価は別gate。

**統合入力・LOCAL完了:** `cross-platform-production-continuity-20260823.md`。reviewed source `31061be`のsource録音後CHOP遷移、VOICE Beat-loop restart、startup project autosave、出力device失敗時safe stop/temp cleanupを保全する。

**統合入力・source hardening:** `full-release-audio-hardening-20260821.md`。再現可能な非debug release、音声I/O資源境界、iOS/Kotlin Native lifecycle、供給網・復旧sourceを保全する。GitHub administrator ruleset、実device audio、provider、Humanはsourceだけでは完了扱いにしない。

**完了済み:** `precision-trim-long-press-number-wheel-20260820.md`。PAD／波形長押しから最大1秒の精密窓を開き、START/END数値ホイールとframe/1 ms/10 ms精度をAndroid/Windowsへ実装。PR #37としてmainへマージ済み。物理device/Human境界は未昇格。

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
- `full-release-audio-hardening-20260821.md`（統合入力）
- `spotify-connect-ux-lifecycle-20260823.md`（統合入力）
- `cross-platform-production-continuity-20260823.md`（統合入力）
- `session-integration-20260823.md`（現在の選択）

## Selection protocol

計画を選ぶときは、計画本文を現在の source に対して再検証し、対象ファイル、owner、rollback、停止条件、target gate、検証コマンドを追記したうえで、この README の `Current selection` を更新する。device / provider / public / human の作業を含む計画は、local contract の完了後に別 task として分離する。
