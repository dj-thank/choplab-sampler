# ExecPlan registry

更新: 2026-08-26

このディレクトリには ChopLab の過去の ExecPlan と、将来選択できる計画が保存されています。ファイルが `plans/active/` に存在すること自体は、現在その計画を実行中であることを意味しません。

## Current selection

**wave 10 in progress:** `stereo-channel-identity-tracer-20260826.md`。exact Wave 9 closeout `d6c2243` / tree `85ffa6d`から、左右非対称の1/2ch PCMをimport、Android/Windows playback、schema 7 save/reopen、Pattern/Song WAVまでframe単位で保持する一つの縦断tracerを実装する。3–8chは既存互換mono、schema 1–6とmono-only bytesを保護し、device/provider/public/signing/Humanはscope外。

**wave 9 completed local; goal remains active:** `../completed/android-aapt2-release-verifier-fallback-20260826.md`。exact Wave 8 closeout `a484a96` / tree `f325d6e`から、`apkanalyzer`を持たないbuild-tools-only SDKでもfinal APKの同一manifest/security/alignment/signature policyをfail closedで検査できるようにした。product checkpoint `e522907` / tree `514e516`、Python policy 59、configured validation、exact Wave 8 APK/SBOM read-backまで`LOCAL_PASS`。署名、secret、workflow、GitHub/Release、device/provider/Humanは変更していない。

**wave 8 completed local; goal remains active:** `../completed/ab-song-variation-tracer-20260826.md`。exact Wave 7 closeout `41d715c` / tree `091f6a30`から、A/B二つの16-step variationを4小節へ並べ、shared history、schema 6 save/reopen、Android/Windows playback、offline WAVを一つの順序truthへ接続した。odd BPM/swingのSong full-PCM REDでcontinuous timingを修復し、511 tests / 190-task gate、APK/EXE/SBOM read-backまで`LOCAL_PASS`。arbitrary Song editor、stereo、stems、48dp responsive redesign、device/provider/public/Humanは別gate。

**wave 7 completed local:** `../completed/android-live-final-sample-parity-20260826.md`。actual Android render loopが`Voice.render()`のterminal returnを破棄していた不一致を、natural finish／48-frame releaseのactual mix seamでRED再現し、mix-before-retireへ修復。471 tests / 190-task gate、release bytecode、APK/EXE/SBOM read-backまで`LOCAL_PASS`。multi-PAD/stereo/Songとphysical/device/provider/public/Humanは未選択の別wave。

**wave 6 completed local:** `../completed/choke-export-parity-20260826.md`。offline WAVのsame-group VOICEがloop ownerをsilenceするlive/export不一致をshared companion policyで修復。owner-only／other-group layerのfull-bar最大PCM差≤1、no-loop／multiple-loop controls、469 tests / 190-task gateで`LOCAL_PASS`。

**wave 5 completed local:** `../completed/choke-loop-session-ownership-20260826.md`。同じnonzero CHOKE groupのPAD triggerがloop owner voiceだけを止めてcontroller state／VOICE companionを残す不具合と、同group companionがloop開始直後にownerをsilenceする不具合をshared ownership planで修復。通常polyphonyを保持し、466 tests / 190-task gateで`LOCAL_PASS`。

**wave 4 integrated current local line:** `b6eed97`。regular landscapeのfirst entryを2×2 own-audio＋独立demo panelへし、stacked/wideのaction copyを一つのshared contractへ集約した。compact/portrait/large-textは保持し、456 tests / 190-task gate / same-state Windows visualで`LOCAL_PASS`。次はcompact/device/Human evidenceなしに追加copyやdesktop-only visualを開始しない。

**統合済みcurrent local line:** `c660ce9`。workflow NEXT、document outcome truth、Finish action truthの三つを一つのclean branchへ統合し、453 tests / 190-task gateで`LOCAL_PASS`を再確認した。copy-only UXの4回目は開始せず、次はportfolioを再計算して非copy laneを一つだけ選ぶ。

**完了:** `../completed/finish-action-truth-ux-20260826.md`。SAVE画面の見出しを制作保存＋WAV書出しの両目的へ揃え、実際にはpatternだけを消す操作を`ビート配置を消す / CLEAR STEPS`へ限定した。callback、schema、audio、project/autosave bytesは変えず、TDD、全197-task gate、同一Windows状態のbefore/afterで`LOCAL_PASS`を確認した。

**完了:** `../completed/wide-first-entry-ux-20260826.md`。current-run Windows auditで確認した最大化first-entryの巨大なdead canvasを、regular landscape限定の2-column own-audio/demo layoutへ修復した。compact/portrait/large-textは既存contractを保持し、before/after visualと全local/package gateで`LOCAL_PASS`。次はcompact/device/speech/Human evidenceなしに別のdesktop-only visualを増やさない。

**直前の完了:** `../completed/document-outcome-confidence-20260826.md`。音声取込、制作open/save、WAV exportのcancel/successをAndroid/Windowsで一つのtruthful contractへ揃え、外部fileとアプリ内制作／autosaveを区別した。path/bytes/schemaは不変、全local/package gateで`LOCAL_PASS`。Finish truthとwide entryを最後にcopy/desktop-only proxyを増やさず、次はHuman/device evidenceまたは別laneを選ぶ。

**直前の完了:** `../completed/workflow-next-action-ux-20260826.md`。既存4工程にNEXT 1–4／待機／録音停止の一意な次操作と、disabled工程の具体的prerequisiteを追加した。新screen/modal/scrollやstate mutationを増やさず、全local/package gateで`LOCAL_PASS`。

**直前の完了:** `../completed/android-signer-verifier-recovery-20260826.md`。既存`v0.17.0`で手動recoveryを必要にしたsigner digest解析境界を、SDK-owned tool優先、stdout/stderr双方の一意digest受理、競合fail-closedへ修復した。exact local signed APKと全release policyで`LOCAL_PASS`を確認した。外部rerun・新version公開は行っていない。

**直前の完了:** `../completed/monophonic-pad-retrigger-20260825.md`。同じ物理PADを単声retriggerとし、VOICE PADがloop ownerとcompanion layerへ二重投入される経路、Windowsでloop停止後にVOICE companionが残る経路、offline WAVのrepeated-event倍化を統一して除いた。異なるPADの意図的な重ね演奏は保持し、`LOCAL_PASS`を確認した。

**直前の完了:** `../completed/precision-trim-imagegen-fit-20260825.md`。ユーザー提供のTRIM画面とImageGen候補から、PAD長押し直後に選択chop全体をscreen-fitting表示し、その後の波形長押しで1秒精密focusへ移る二段階を実装した。`LOCAL_PASS` + scoped API 36 AVD evidenceを確認し、physical device/provider/public/Humanは別gateとして保持した。

**直前の完了plan:** `../completed/quick-sketch-20260825.md`。素材を安全な8チョップとA01–A08の交互step下書きへ変えるcross-platform QUICK SKETCHは`LOCAL_PASS`で完了済み。

**直前の画面・導線plan:** `first-screen-flow-20260824.md`。pristine first entry、large-text workflow chrome、Android/Windowsのselected PAD/bank/page coherenceはPR #52としてmainへ統合済み。現在のQUICK SKETCHのbaseline入力として保全する。

**独立したiOS安全修正:** `6ceb4d2`は録音中のSource importをstore/UIで拒否し、picker取消/失敗を非破壊にする限定follow-up。この修正は上記Android/Windows planの選択を変更せず、macOS CIと物理録音は別gateのまま。

**独立保守delta:** `main@495ddc9`からのWindows recorder startup cleanupは、`TargetDataLine.open`後の`start`失敗をexact-once closeと一時WAV／状態破棄でfail-closedにする小規模修正。新しいExecPlanは選択ず、上記のproduct decisionは変更しない。hosted `:desktop:test`が実行済みになるまでsource/static candidate扱いとする。

**次に選ぶ一つ:** 検査基盤だけを連続投資せず、次は製品体験へ戻る。local authorityの範囲ではmono→stereoのimport/live/save/export tracerを第一候補とし、bounded physical/compact accessibilityまたはA/B Song device audio/routeは権限取得時だけ比較する。arbitrary Song breadth、native engine/Voice kernel、MIDI/AIは自動選択しない。

**直前のpattern/master完了:** `../completed/pattern-master-parity-oracle-20260824.md`。REDでoffline final-sample欠落を検出・修復し、exact Pixel、PR #49、`main@ecc6c54`、全PR/main CI、merged-main provider Windows daily installまで完了。

**直前のaudio primitives完了:** `../completed/audio-parity-primitives-20260824.md`。shared DSP/non-finite policy、PAD PCM oracle、exact Pixel、PR #48、`main@5c56d84`、修正headと全main CIまで完了。

**直前のProductionSession完了:** `../completed/production-session-horizon-2-20260824.md`。shared transaction/history/revision/recovery、exact Pixel、PR #47、`main@28bd388`、全PR/main CIまで完了。physical recovery/audio/provider/Humanは別gate。

**直前の全体最適化tracer完了:** `../completed/global-production-session-20260824.md`。shared `ProductionCommand`の最初の6操作、Desktop/Android host parity tests、Windows/Pixel、PR #46、`main@41be2c2`、全PR/main CIまで完了。物理gesture/audio/recording/TalkBack/provider/Humanは別gate。

**historical v0.17 source/release:** `windows-desktop-daily-release-20260824.md`。Windows PAD keyboard、native commands、data-preserving install、依存更新をPR #45 / `main@ab68d2d` / annotated `v0.17.0`へ統合し、そのexact tag bytesの三平台prereleaseは後続の既存署名recovery taskで公開・read-back済み。これは以後のproduct commitsを含まないため、次のbinary releaseは新versionを使い、旧tagを書き換えない。

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
