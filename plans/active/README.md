# ExecPlan registry

更新: 2026-08-24

このディレクトリには ChopLab の過去の ExecPlan と、将来選択できる計画が保存されています。ファイルが `plans/active/` に存在すること自体は、現在その計画を実行中であることを意味しません。

## Current selection

**現在の実装plan:** `first-screen-flow-20260824.md`。PR #52/#62のscreen-flowを基礎に、exact `main@029500a`を第二親へ統合したproduct `7c57fb4` / tree `085c809`がcurrent executable anchor。従来のpointer/rotation修復に加え、normal BEAT touch、stopped-source CHOP touchとDesktop keyboardもpaired ownershipへ統一する。CHOP assigned GATEはcapture seamを迂回してownership callbackへ進む。Androidはmailbox受理後だけtokenを発行し、sequencer/touch voiceを同じlock-free sequenceで識別してexact voiceだけreleaseする。Desktopはaudio start成功後にtokenをactive voiceへ保持し、stale physical releaseも新しいvoiceを残して自voiceだけcloseする。#65 transport、#67 import rate、#68 terminal sample、#71 import名、#73 recorder取消と#74 reverse non-loop rendererを維持する。prior head `b79f49d`は全4 workflow PASS、current productはPython 39/39・public 398・XML/diff PASSでhosted CI・exact-head review待ち。threadは未解決のまま保持する。

**直近の統合保守:** iOS import/recording exclusionは録音中のSource importをstore/UIで拒否し、picker取消/失敗を非破壊にする限定follow-upとしてPR #60 / `main@5430d0d`へ統合済み。macOS CIと物理録音は別gateのまま。

**直近の統合保守:** Windows recorder startup cleanupは、`TargetDataLine.open`後の`start`失敗をexact-once closeと一時WAV／状態破棄でfail-closedにし、PR #59 / `main@364ccde`へ統合済み。

**直近の統合保守:** Desktop recorder開始取消修正はPR #73 / `main@a0b356c`へ統合済み。blocking `TargetDataLine.start()`中のSTOPがpending lineをclaim/closeし、late worker公開・readを禁止する。exact headはclean review・threads 0・4 workflows PASS。PR #58 product `7c57fb4`もruntime/test blobsをexact保持し、物理microphoneは別gate。

**独立したreverse PAD末尾修正:** PR #74 / `main@029500a`は逆one-shot/gateのcount/renderをrealtime cursor advanceへ統一し、48→60 kHzの旧末尾無音（80→79）と8→48 kHzの1/12-step丸め（12）を固定。2-frame / pitch −5でLOOP/Windows forceLoopは従来のdirect-position 3-element有限境界を保持する。PR #58 product `7c57fb4`はrenderer/test/adapter-test blobsをexact保持し、adapter runtimeはrender seamを保ったままper-voice ownershipを追加する。fresh JVM/Windows CIとreview待ち。

**直近の統合保守:** Desktop transport step-zero orderingはPR #65 / `main@3072eed`へ統合済み。worker開始前readiness barrier、controller readiness公開、scratch restart失敗時record-arm復元のruntime/test/docsを後続deltaもexact保持する。

**独立audio保守delta:** Android realtime PAD terminal-sample修正はproduct `5dd3d66` / tree `c6a7e9b`からPR #68でmainへ統合済み。PR #58 product `7c57fb4`はその処理とexact parity testを保持し、runtimeへallocation-free per-voice ownership filterだけを追加する。Python 39/39・public 398・diffはPASS、current PR hosted Android gate待ち。

**独立したAndroid import名境界修正:** product `b7364ee` / tree `dae6252`はPR #70でmainへ統合され、その契約はPR #71のshared seamへ移行済み。PR #58 product `7c57fb4`はexact `main@029500a`を統合し、#71 exact source/test/docsとmerged #65/#68/#73/#74を保持する。current PR hosted Android CIが残る。

**独立したshared／Desktop import名境界修正:** integration product `e0d3fa1` / tree `1558c4a`の修正はPR #71 / `main@dfcd9d8`へ統合済み。PR #58 product `7c57fb4`は六つのproduct/test blob、#65 transport/test、#68 exact test、#73 recorder runtime/test、#74 renderer/testを保持し、current exact-head hosted gate待ち。

**独立したDesktop import境界修正:** reachable product `3ad2bd9`（tree `8272a51`、`main@3260f5c`）で、Windows decoderはproject/archiveと同じ8–192 kHzだけを受理し、192,001 Hz以上をPCM payload読込・state公開・autosaveより前に拒否する。exact 192 kHz受理とfail-on-read 192,001 Hz拒否をfocused testに固定し、上記画面planやarchive schemaは変更しない。hosted `:desktop:test`がmerge gate。

**次に選ぶ一つ:** (1) polyphony/choke/repeated-event oracle、(2) loop/vocal oracle、(3) stereo internal/export path、(4) audio parityを一旦止めてmulti-pattern/Song arrangement。native engine/Voice kernelは選択dimensionのoracle前に開始しない。

**直前のpattern/master完了:** `../completed/pattern-master-parity-oracle-20260824.md`。REDでoffline final-sample欠落を検出・修復し、exact Pixel、PR #49、`main@ecc6c54`、全PR/main CI、merged-main provider Windows daily installまで完了。

**直前のaudio primitives完了:** `../completed/audio-parity-primitives-20260824.md`。shared DSP/non-finite policy、PAD PCM oracle、exact Pixel、PR #48、`main@5c56d84`、修正headと全main CIまで完了。

**直前のProductionSession完了:** `../completed/production-session-horizon-2-20260824.md`。shared transaction/history/revision/recovery、exact Pixel、PR #47、`main@28bd388`、全PR/main CIまで完了。physical recovery/audio/provider/Humanは別gate。

**直前の全体最適化tracer完了:** `../completed/global-production-session-20260824.md`。shared `ProductionCommand`の最初の6操作、Desktop/Android host parity tests、Windows/Pixel、PR #46、`main@41be2c2`、全PR/main CIまで完了。物理gesture/audio/recording/TalkBack/provider/Humanは別gate。

**直前のsource統合・binary配布待ち:** `windows-desktop-daily-release-20260824.md`。Windows PAD keyboard、native commands、data-preserving install、依存更新をPR #45 / `main@ab68d2d` / annotated `v0.17.0`へ統合済み。Windows/iOS tag artifactsは検証済みだが、stable Android signing secrets不在のためbinary Releaseはfail-closedで未公開。

**直前の完了入力:** `../completed/session-integration-20260823.md`。product source `6914e3c`でfull release/audio hardening、Spotify metadata/control-only UX、Windows production continuityを一つのclean local candidateへ統合した。clean 184-task gate、226 Android / 49 JVM / 66 desktop、package／SBOM／public-surface／UI contract／runtime smokeがPASS。Android bytesとsource inputsはaccepted `8306ed2` Pixel receiptに完全一致するため、その非録音scopeだけ`DEVICE_PASS`。provider/public/Humanは別の明示task。

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
- `windows-desktop-daily-release-20260824.md`（source統合完了、binary Releaseはstable Android signing待ち）
- `../completed/android-production-continuity-20260820.md`（完了済み）
- `precision-trim-long-press-number-wheel-20260820.md`（完了済み本文を保全）
- `full-release-audio-hardening-20260821.md`（統合入力）
- `spotify-connect-ux-lifecycle-20260823.md`（統合入力）
- `cross-platform-production-continuity-20260823.md`（統合入力）
- `../completed/session-integration-20260823.md`（完了済み統合plan）

## Selection protocol

計画を選ぶときは、計画本文を現在の source に対して再検証し、対象ファイル、owner、rollback、停止条件、target gate、検証コマンドを追記したうえで、この README の `Current selection` を更新する。device / provider / public / human の作業を含む計画は、local contract の完了後に別 task として分離する。
