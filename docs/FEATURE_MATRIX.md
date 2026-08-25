# 機能マトリクス

この表は実装状態と確認層の要約であり、ゲートの昇格表ではありません。`device`、`emulator`、`historical` の記載は各行の備考にある範囲・revisionへ束縛されます。現在の checkout と gate は [`docs/PROJECT_STATE.md`](PROJECT_STATE.md) の先頭 `Current snapshot` と [`docs/VALIDATION.md`](VALIDATION.md) を正本とし、過去の Pixel / provider / public receiptだけでは現在の `DEVICE_PASS` 以上を宣言しません。

| 要望 | 実装 / 確認層 | 備考 |
|---|---:|---|
| 次にやること／押せない工程の理由 | ✅ current local | empty/demo/source/chop/PAD-only/loop/pattern/loading/recordingをpure policyで一つの`NEXT`へ分類。固定status stripが`NEXT 1 入れる → 2 チョップ → 3 ビート → 4 保存`または待機/録音停止を表示。disabled工程は具体的な日本語prerequisiteをscreen-reader stateへ公開し、enabled/navigation truthは不変。Android 244、shared 34/34、Windows packageをLOCAL検証。物理touch/TalkBack/Humanは未確認 |
| PAD長押し後の見やすい切り位置表示 | ✅ current local / scoped AVD | `6befe11`で長押しPAD全体を最初からscreen-fitting表示。`max(1秒, PAD長×1.25)`で普通のchopは約80%、短いchopは周辺contextを保持。全体overviewにPAD/viewport/focusを表示し、500dp未満では省略。波形長押しの近い境界移動＋1秒focus、全既存controls、Revertを保持。reference contract 7 regions PASS、API36 initial `0–0.5秒`→表示`0–1.0秒`、instrumentation 2/2。物理touch/TalkBack/Human未確認 |
| 音声ファイルだけを表示する取込picker | ✅ current local | Androidは専用`OPEN_DOCUMENT + OPENABLE + audio/*` contractでAll Files base typeを除去し、選択後もMediaExtractorでaudio trackとresource上限を検証。MP3/AAC・M4A/FLAC/Ogg・Opus/WAV等はAndroid platform/container対応範囲。WindowsはAll Filesを無効化したWAV-only chooserと二重検証で、未実装MP3を偽表示しない。clean 191 tasks、compiled bytecode read-back、Windows実dialog確認。Pixel/provider固有表示は未確認 |
| 素材から8つの下書きを作る | ✅ current local | `143a969`のQUICK SKETCHは、Bank Aが完全に空でmarker/A-stepもない時だけ、選択範囲をzero-crossing-safeな8連続sliceへ分けてA01–A08とsteps 1/3/5/7/9/11/13/15へ一括配置。starter drumsとB/C/Dを保持し、一つのUndo/Redo/autosave単位。短いrange・既存A作業・録音/loadingは完全no-op。shared両host32、isolated Windows autosaveで7 markers/8 pads/8 melody stepsを確認。物理touch/音質/Humanは未確認 |
| Pattern/master full-bar parity | ✅ product anchor / scoped device | product bytes `ecc6c54`。single PAD / step 0 / one barのrealtime Voice+master expectedとoffline WAVを全frame比較。REDはframe 402でoffline末尾sample欠落61、mix-before-retire修正後max delta≤1。Android 229、exact `DB08…` Pixel、全PR/main CI PASS。docs-only source main、polyphony/stereo/listeningは別 |
| Realtime / offline / Windows audio parity | ✅ main / scoped device | shared pitch/tone/gain/fade/limiter/swing/NaN policy。Android Voiceとhost PAD PCM最大差1、shared両host25、merged-main Windows daily install `0.17.0-802a667d39cb`。polyphony/stereo/listeningは次段階 |
| ProductionSession / 履歴・復旧の全体整合 | ✅ main / scoped device | shared history/revision/plan transactionとverified disk revision引継ぎ。Android/Windows source/recording/reset/open/recovery/editを一本化し、PR #47/main/Pixel PASS。物理recovery操作は未確認 |
| 全体最適化 / Production semantics | ✅ main / scoped device | shared ProductionCommandのrange/marker/slice/PAD mode semantics、PROJECT/SESSION/NONE、effect failure truthをPR #46からmainへ統合。残りcontroller actionのtyped-command化は次期候補 |
| Windows EXE版のPAD操作 | ✅ current local / merged source | `main@ab68d2d`の`0.17.0`は共有Android-origin deckを保ち、現在表示中の割当済み16 PADを `1234 / QWER / ASDF / ZXCV` で演奏。repeat/modifier/source/recording/loadingをfail-closedにし、key-up・focus loss・終了でexact global PADを解放。File/Edit/Transport menu、現候補76 desktop tests、installer/tamper/shortcut E2E、実user-local起動がPASS。実ドライバー音質・レイテンシは未確認 |
| Windows WASAPI endpoint | 🧪 local / current-device unavailable | JNA 5.19.1でMMDevice default/active/all-state endpointとmix formatをSTAからprobeし、native診断menuへ接続。現端末はJava Sound mixer 0、PnP AudioEndpoint present 0、MMDevice render/capture count 0、default `0x80070490`。adapter hardware presenceだけから音声利用可能とは主張しない |
| Spotify OAuth / Connect UX | ✅ integrated local / exact-byte Android device receipt | Native `連携` menuのSpotify Connect panelからAuthorization Code with PKCE、dynamic-port `127.0.0.1` callback、memory-only token、明示的状態、cancel・late callback無効化、現在再生／保存済みライブラリ、pause/resume、境界別復帰案内を接続。Spotify Contentはメタデータ／再生制御のみ。Android device receiptはapp全体のexact bytes/instrumentationであり、実Spotify account/provider、物理音声品質、screen-reader speechを証明しない |
| Spotify楽曲のMP3化 | — | Spotify Contentのdownload、stream ripping、録音、音声抽出、変換は設計上対象外。ChopLabへ渡せるのはユーザーが選んだローカル音源 |
| iOS 16 preview | 🧪 local policy / GitHub macOS | SwiftUI + AVFoundation。ユーザー音源のローカル取込、16 PAD、PAD別範囲、録音、`ALL STOP`。録音中の新規importはUI/store双方で拒否し、picker取消/失敗は録音・既存Sourceを停止/置換しないMainActor回帰を追加。Swift/Simulator実行と署名なし`.app.zip`はmacOS CI境界。 |
| Windows / Android / iOS 公開preview | ✅ historical `v0.17.0` artifact-integrity PUBLIC_PASS / current verifier LOCAL | `v0.17.0@ab68d2d`のsigned Android、unsigned Windows app-image、unsigned iOS Simulator archive、SBOM/manifest/sidecarsは既存release receiptとlocal recovery bytesで整合。hosted attemptのsigner digest解析失敗を、SDK-owned tool優先＋stdout/stderr一意digestのfail-closed verifierへ修復しexact local APKで再検査。より新しいproduct bytesのversion/tag/provider/public実行と署名済みiOS IPAは別gate。 |
| 公開面の資格情報・音源境界 | ✅ local / CI | `scripts/check_public_surface.py`が認証情報、署名素材、音源候補をfail-closedで検査。Windows CIはcheckout直後の未変更treeをscanし、policy testsの後にsource snapshotを作成・uploadする。ユーザー音源はReleaseへ同梱しない。 |
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ current local | AndroidとWindowsの停止後decodeが波形を読込み、fresh launch revisionでCHOPへ遷移してPAD割当／16-step制作へ続く。実録音内容は今回取得していない |
| PAD付き | ✅ emulator | 4 BANK × 32 PAD（合計128）。各BANKを固定01–16 / 17–32ページで表示。CHOPと通常BEATは同じ4×4演奏面を共有し、詳細sequencerだけを二次面へ分離 |
| 正方形PAD | ✅ device/emulator | 4×4 / 8×2とも親領域へ収まる最大正方形を中央配置。Pixel 9a portraitとv0.11 portrait/landscape emulatorで確認 |
| 内蔵ドラムキット | ✅ emulator/local | オリジナル合成5キット×16音。新規／reset／新規Source制作だけはDUSTY JAZZ＋starter beatをBANK Bへ自動配置し、復元／手動OPEN済みBANK Bは暗黙変更しない。別kit適用で既存音がある場合は二度押し確認 |
| ビートへ声を重ねる | 🧪 current local | AndroidとWindowsで選択Beat loopを先頭から再始動して録音状態とloop表示を一致させ、BANK Dへ最大32テイク。project schema 5とWAV exportに含む。fake recorder/audio-port host testのみで、実環境の声は未録音 |
| DJスクラッチ | 🧪 local/emulator | 元曲slice/S-Eまたは選択PADをpointer-downから直接所有し、左右を正逆速度へ変換。微小ノイズdead zone、px/s正規化、120ms idle無音、方向／倍率／playhead表示をhost test＋API 36 emulatorで確認。解除時は直前の有効なBeat loop/transportへ一度だけ復帰し、再開対象なしでは停止。TalkBack操作を公開。物理実機の連続操作感・クリック音・読み上げは未確認 |
| レイヤー制作UI | ✅ emulator | `音を重ねる` 1入口に SOUNDS / DRUMS / VOICE / SCRATCH を集約。SOUNDSは全BANKの音を4つ打ち・8分・16分で配置可能 |
| 「おとひろい」正式UI | ✅ device/emulator | `入れる / チョップ / ビート / 保存` の4工程。CAPTUREに`制作を開く / OPEN PROJECT`を追加し、通常BEATをCHOP由来の波形＋BANK/page＋4×4 PADへ統一。旧Pixel receiptとcurrent API 36 emulator表示を分離して保持 |
| 初期画面とデモ導線 | ✅ current local / API 36 emulator | pristine CAPTUREは空波形より先に`曲を読み込む / 制作を開く / MIC / DEVICE`を提示し、starterを`DUSTY JAZZデモ`として明示。Android/Windowsのデモ遷移はB DRUMS・B-01・01–16で一致。final exact APK 7 instrumentation testsと両platformのfresh captureを確認。物理touch/TalkBack/Humanは未確認 |
| 固定操作面とlarge-text例外 | ✅ current local / emulator | 通常portraitとperformance画面は固定、landscapeのCHOP/通常BEATはresponsive配置。font scale 1.2+はheaderを簡素化し4工程を2×2、statusを複数行化。初期入力面だけはconstrained portrait/landscapeで全CTAへ到達するbounded vertical scrollを許可。640 × 360 dp / 200%でstage 49dp、demo 59dpを実測。16-step全セルの48dp化はresponsive再設計待ち |
| 曲を流しながらPADで刻む | 🧪 local/emulator | source再生中のCapture PADは空／割当済みにかかわらず現在位置を刻む。割当済みA01は旧音を鳴らさず現在素材へ上書きし、旧loop/scratch実行参照も解除 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| チョップ後のPAD操作案内 | 🧪 local/emulator | 元曲再生中は空PAD＝追加、音ありPAD＝タップ上書き／長押し微調整。長押し開始時にはcaptureせず、通常タップ完了時だけ上書きする契約をhost test。通常／文字130%で旧版固定表示を確認 |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| チョップ済みビート音声全体を連続ループ | 🧪 local | PAD範囲の末尾から先頭へ前向き・逆向きに折り返し、同時に使うループPADは1つ。実機音質確認待ち |
| 選択音を4つ打ち・8分・16分へ配置 | ✅ | `配置プリセット`として選択PADだけを置換し、他PAD・他BANKの重ね音を保持。LOOP/VOCALは専用再生のため配置対象外 |
| LOOP / VOICEとステップ表示の整合 | 🧪 local/emulator | LOOPは音声全体の反復、VOICEは開始時の一度再生。16-stepセルと演奏録音を無効化し、旧保存keyも再生・書出し・Finish表示から除外 |
| PAD→ループ／並べる→足す／擦る導線 | ✅ device/emulator | Capture/Chop/BeatのDockをpure item policy＋共通handler rendererへ統一。通常BEATはCHOPと同じ4×4 PADで演奏し、`QUICK / STEPS / ADD / SCRATCH`から4レーン／16-step詳細へ明示的に切替。選択PADとBANK/pageは往復で保持 |
| 上級操作の段階表示 | 🧪 emulator/local | KEY/TONE/LEVELは通常BEAT画面から直操作し、再生中の選択音へカーソルを戻さず即時反映。16手動step、BPM/Swing等は`細かく調整`へ整理 |
| ビート画面の実波形・再生位置 | ✅ device | 選択sliceのPCM波形、ビートループ位置、16-step playhead、A〜Dの4レーン発音マーカーを固定表示 |
| BANKを替えて音を重ねる | ✅ emulator/local | A=メロディー、B=ドラム、C=ワンショット、D=ボイスを常時表示し、空BANKも選択可能。全128 PADを演奏・配置 |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ local | PAD別one-pole low-pass Tone。「暗い・なじむ・原音」の意味名付きpresetと連続slider。再生中のloopへ即時反映するhost regressionあり |
| 長すぎる音声の箇所選択 | ✅ local / API 36 emulator | 最大10分、zoom/pan/S/E handles。PAD長押しでTRIMへ入り、波形長押しで近いSTART/ENDを移動して押下位置の最大1秒窓へfocus。START/ENDはダイヤル付き前／現在／次の数値ホイールをframe・1 ms・10 ms単位で調整でき、Preview/Revert/Undo/Redoを保持 |
| 波形viewportのアクセシビリティ | ✅ local / AVD framework-node / historical device focus-path | autosave非依存の固定PCM fixtureで2本指pinch/pan、前/次/reset、overview、S/E/chopの48dp・端点・可逆nudgeを検証。source-bound `9177229` は専用Google Play API 36 AVDのportrait font 1.0/1.3/2.0・landscapeで各4 tests PASS、fatal/ANR 0、設定復元PASS。物理Pixelの実TalkBack focus ringは既存receiptあり。TTS内容・完全な読み上げ順・service custom-action menuはHUMAN_GOへ分離。 |
| プロ用のようにチョップ | ✅ MVP/local/device launch | 手動、自動、境界drag、zero-crossing snap。range/marker境界policyはAndroid/Windows shared commandへ一本化。Pixelではexact APKの起動/navigationまでで、今回の物理gesture精度は未確認。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | ✅ device | 4工程、正方形PAD、役割色、波形、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。連打感と触覚の最終評価はHuman判断 |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate / Beat Loop | 🧪 local | PAD別。通常modeは全platformでONE_SHOT/GATEだけを切替え、Beat Loopは明示controlでチョップ範囲全体を連続再生。loop停止effect失敗時はmode/owner/historyを変更しないnegative testあり |
| Choke group | ✅ | 1–4 |
| リアルタイム音声安全性 | 🧪 local | 操作queueを512件へ制限し1 block最大64件、Stop Allを容量外で優先しtransportも同じ境界で停止。clear世代境界と128固定latest-wins PAD mailbox、事前確保Voiceを維持。新shared DSPはcallback call-siteでallocation/lock/I/Oなし、tone expはcontrol boundaryだけ、soft limiterは非有限値を0へ安全化 |
| マイク停止の完了確認 | 🧪 local | workerとWAV writerの終了を最大2秒確認し、timeout時は未完成WAVを成功扱い・decodeしない。Windowsはlineの`open`成功後に`start`が失敗してもexact-once closeし、一時WAVと内部状態を破棄するhost regressionを含む |
| 録音セッションと誤再生防止 | 🧪 historical emulator / current local | MIC / DEVICE / VOICEを単一の`STARTING → RECORDING → STOPPING`状態で排他管理。端末音声captureは世代付きsessionと2秒のstop/release境界を持ち、開始途中STOPと旧workerの新session破壊をLOCALで防止。開始時に既存再生を停止し、Vocalだけ選択Beat loopを再始動。録音中の競合操作を遮断。現候補の実MediaProjection/AudioRecord再検証は未実施 |
| 一時録音のprivacy cleanup | 🧪 local | app cacheのChopLab命名mic/system/vocal WAVだけを所有対象とし、decode成功・失敗・取消・無効化後に削除。異常終了残留は24時間後の起動時に限定清掃。SAF import、export、project、無関係cacheは削除対象外 |
| 主再生モード／同一PADの二重音防止 | ✅ current local | 元曲、preview、Beat loop、transport、scratchは既存の共通停止境界を維持。さらにAndroid／Windows／offline WAVで同一PAD retriggerを単声restartへ統一し、VOCAL loop ownerを開始時companionから除外。loop停止は同じcompanion setも止める。異なるPADの意図的な重ね演奏はnegative controlで保持。物理聴取とclick/pop品質は未確認 |
| Android再生割り込み安全性 | ✅ emulator/local | 全audible startをmedia/music audio focusで統一。Home、focus loss/transient/duck、出力切替ではengine silenceをfocus解放より先に一箇所で強制し、反復割り込みは一度だけ停止、録音のみの割り込みは再生へ触れない。gainでは自動再開しない。回転は再生継続、source seek/KEY retargetはfocus所有中のみ。端末音声録音はbackgroundで継続し、MIC/VOICEは安全停止。実機route-loss／通話競合は未確認 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable stereo-capable domain is host-tested。MVP archiveは32-PAD page対応schema 5/WAVで保存し、schema 1–4（旧4×16配置を含む）を移行読込 |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | ✅ MVP/local/emulator UI | `.choplab`手動保存/読込、共有PCM16 WAV、schema 1–5 migration、path traversal/過大manifest/malformed WAV/進捗0 InputStreamをfail-closedで拒否。CAPTURE先頭から同じdocument contractを開けることをAPI 36 DocumentsUI起動で確認。独立schema-1 fixture、固定seed malformed corpus、1000小entry、PCM総量超過をLOCAL検証 |
| 新しい音源への安全な切替 | 🧪 local/emulator | 既存制作がある場合は二度押し確認。ただし未編集starter drumsだけは制作破棄扱いにしない。読込開始時点で現在再生を停止し、decode中の新規再生を遮断。成功時だけ旧Source／A・C・D／user B／旧step／loop／scratch／履歴を除去し、新しいSource＋生成starter BANK Bとして開始。失敗／キャンセル／古い非同期完了は現制作へ触れない |
| 再生表示と実音声の同期 | 🧪 local/device UI | `STOPPED / STARTING / PLAYING / STOPPING`を音声スレッド適用値と保留命令から導出。差替え・リセット・読込も停止確認前にSTOPPEDを表示せず、Undoは保留命令を復元しない |
| 全再生停止 | 🧪 local/device UI | `ALL STOP`がsource、PAD voice、loop、scratchの境界を先に発行してからtransportを停止。UIのstep/loop/scratchも同時に解除し、録音中データは明示的に継続 |
| 録音中の編集所有権 | 🧪 local | MIC / DEVICE / VOICEのSTARTING・RECORDING・STOPPING中はproject edit、UNDO、REDOを拒否し、ボタンも無効化。PAD preview開始はengine停止と同時にsource/transport/loop/scratch UI truthを更新 |
| Autosave/recovery | ✅ historical device / current local | 900ms debounce、SHA-256に結合した世代revision、store再生成後も古い/equal revisionを拒否、最新の検証済み世代を選ぶpending復旧、三世代保持。verified disk revisionをProductionSessionへ引継ぎ、process restart後の次editが古い番号で拒否されないJVM regressionあり。現候補のDEVICE再実行は未実施。手動上書き前にもアプリ内安全コピーを作成 |
| 起動時autosave復元と制作routing | ✅ emulator/local | 復元開始を`isLoading`として公開し、空結果・失敗・成功で必ず解除。復元中は新規取込を無効化し、`LOADING / 音声を読込中 / PLEASE WAIT`でfalseな`NO SOURCE`を防止。復元後は未編集starterだけ→CAPTURE、Source＋starter→CHOP、user Beat／PAD-only→BEATへroute。API 36でBeat編集→autosave→強制終了→BEAT復帰を確認 |
| Undo / Redo | ✅ MVP/local | PAD、range/marker、sequence、BPM/Swing等を最大40操作。連続slider調整は1操作へcoalesceし、slice選択だけのSESSION changeはUndo/autosaveを増やさない |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
| LLM / 音声AIアシスト | 📝 vision | 非破壊proposal、試聴比較、Undo、local-first、cloud明示同意の境界を`docs/AI_ASSIST_VISION.md`へ定義。実装は未着手 |
