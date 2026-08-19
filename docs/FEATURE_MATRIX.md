# 機能マトリクス

この表は実装状態と確認層の要約であり、ゲートの昇格表ではありません。`device`、`emulator`、`historical` の記載は各行の備考にある範囲・revisionへ束縛されます。現在の checkout と gate は [`docs/PROJECT_STATE.md`](PROJECT_STATE.md) の先頭 `Current snapshot` と [`docs/VALIDATION.md`](VALIDATION.md) を正本とし、過去の Pixel / provider / public receiptだけでは現在の `DEVICE_PASS` 以上を宣言しません。

| 要望 | 実装 / 確認層 | 備考 |
|---|---:|---|
| Windows EXE版のPAD操作 | 🧪 local | `:desktop` Kotlin/JVM の元 Android deck 踏襲 UI。5工程、波形、4×4 PAD、PAD editor、BANK A–D、arrange 16-step、ローカルWAVのOPEN/PLAY/STOP、PAD割り当て。`desktop/build/windows-app-image/ChopLab/ChopLab.exe` を生成。Windows音声レイテンシ・操作感は未確認 |
| Spotify OAuth / 現在再生情報 | 🧪 local | Authorization Code with PKCE、`127.0.0.1` loopback callback、token交換、現在再生API request builder。実アカウント接続は未実施。tokenは試作中メモリ内のみ |
| Spotify楽曲のMP3化 | — | Spotify Contentのdownload、stream ripping、録音、音声抽出、変換は設計上対象外。ChopLabへ渡せるのはユーザーが選んだローカル音源 |
| iOS 16 preview | 🧪 GitHub macOS | SwiftUI + AVFoundation。ユーザー音源のローカル取込、16 PAD、PAD別範囲、録音、`ALL STOP`。署名なしSimulator `.app.zip`まで。 |
| Android / iOS 公開配布 | 🧪 CI | `v*`タグでAndroid debug APKとiOS Simulator app zipを同一Releaseへ添付し、各SHA-256を生成。署名済みiOS実機IPAは未提供。 |
| 公開面の資格情報・音源境界 | ✅ local / CI | `scripts/check_public_surface.py`が認証情報、署名素材、音源候補をfail-closedで検査。ユーザー音源はReleaseへ同梱しない。 |
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ | 停止後に波形へ自動読込、PAD割当、16-step制作 |
| PAD付き | ✅ emulator | 4 BANK × 32 PAD（合計128）。各BANKを固定01–16 / 17–32ページで表示 |
| 正方形PAD | ✅ device/emulator | 4×4 / 8×2とも親領域へ収まる最大正方形を中央配置。Pixel 9a portraitとv0.11 portrait/landscape emulatorで確認 |
| 内蔵ドラムキット | ✅ device/local | オリジナル合成5キット×16音。空のBANK Bも直接選択でき、キット選択へ案内。常にBANK B（ドラム）へ適用し、既存音がある場合は二度押し確認。starter beat付き |
| ビートへ声を重ねる | 🧪 local | loop再始動に合わせて録音し、BANK Dへ最大32テイク。project schema 5とWAV exportに含む。実環境の声は未録音 |
| DJスクラッチ | 🧪 local/emulator UI | 元曲の波形で選んだsliceまたはS/E範囲、または選択PAD自身の範囲を左右dragで正逆再生し、離すと停止。drag速度はイベント頻度に依存しないpx/sへ正規化し、通常のgesture event間隔を42msで誤終了しない120ms idle policyをhost test。円盤はTalkBack向け開始・停止・左右操作と状態説明を公開。固定UIはemulator確認、実機の連続操作感と読み上げは未確認 |
| レイヤー制作UI | ✅ emulator | `音を重ねる` 1入口に SOUNDS / DRUMS / VOICE / SCRATCH を集約。SOUNDSは全BANKの音を4つ打ち・8分・16分で配置可能 |
| 「おとひろい」正式UI | ✅ device/emulator | `入れる / チョップ / ビート / 保存` の4工程。Pixel 9a v0.10とv0.11 emulatorで固定表示を確認 |
| スクロールなし操作 | ✅ preview | portraitは上下固定、landscapeのChopは波形＋右4×4 PADの左右分割、Beatはcompact操作列。360dp級portraitのheader/mode/control/page/PAD編集/stepper/Layer/Scratch/波形compact操作は48dp以上で、文字拡大時もcompact文字のsp値を縮めない。16-step全セルの48dp化はresponsive再設計待ち。UI sourceにscroll APIなし |
| 曲を流しながらPADで刻む | 🧪 local/emulator | source再生中のCapture PADは空／割当済みにかかわらず現在位置を刻む。割当済みA01は旧音を鳴らさず現在素材へ上書きし、旧loop/scratch実行参照も解除 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| チョップ後のPAD操作案内 | 🧪 local/emulator | 元曲再生中は空PAD＝追加、音ありPAD＝タップ上書き／長押し微調整。長押し開始時にはcaptureせず、通常タップ完了時だけ上書きする契約をhost test。通常／文字130%で旧版固定表示を確認 |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| チョップ済みビート音声全体を連続ループ | 🧪 local | PAD範囲の末尾から先頭へ前向き・逆向きに折り返し、同時に使うループPADは1つ。実機音質確認待ち |
| 選択音を4つ打ち・8分・16分へ配置 | ✅ | `配置プリセット`として選択PADだけを置換し、他PAD・他BANKの重ね音を保持。LOOP/VOCALは専用再生のため配置対象外 |
| LOOP / VOICEとステップ表示の整合 | 🧪 local/emulator | LOOPは音声全体の反復、VOICEは開始時の一度再生。16-stepセルと演奏録音を無効化し、旧保存keyも再生・書出し・Finish表示から除外 |
| PAD→ループ／並べる→足す／擦る導線 | ✅ device/emulator | Capture/Chop/BeatのDockをpure item policy＋共通handler rendererへ統一。BeatのQuick／Steps双方に固定`QUICK / STEPS / ADD / SCRATCH`。4レーン×16step、BANK別音色レール、選択音ループも同じ固定画面へ整理 |
| 上級操作の段階表示 | 🧪 emulator/local | KEY/TONE/LEVELは通常BEAT画面から直操作し、再生中の選択音へカーソルを戻さず即時反映。16手動step、BPM/Swing等は`細かく調整`へ整理 |
| ビート画面の実波形・再生位置 | ✅ device | 選択sliceのPCM波形、ビートループ位置、16-step playhead、A〜Dの4レーン発音マーカーを固定表示 |
| BANKを替えて音を重ねる | ✅ emulator/local | A=メロディー、B=ドラム、C=ワンショット、D=ボイスを常時表示し、空BANKも選択可能。全128 PADを演奏・配置 |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ local | PAD別one-pole low-pass Tone。「暗い・なじむ・原音」の意味名付きpresetと連続slider。再生中のloopへ即時反映するhost regressionあり |
| 長すぎる音声の箇所選択 | ✅ | 最大10分、zoom/scroll/S/E handles |
| 波形viewportのアクセシビリティ | ✅ local / AVD framework-node / historical device focus-path | autosave非依存の固定PCM fixtureで2本指pinch/pan、前/次/reset、overview、S/E/chopの48dp・端点・可逆nudgeを検証。source-bound `9177229` は専用Google Play API 36 AVDのportrait font 1.0/1.3/2.0・landscapeで各4 tests PASS、fatal/ANR 0、設定復元PASS。物理Pixelの実TalkBack focus ringは既存receiptあり。TTS内容・完全な読み上げ順・service custom-action menuはHUMAN_GOへ分離。 |
| プロ用のようにチョップ | ✅ MVP | 手動、自動、境界drag、zero-crossing snap。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | ✅ device | 4工程、正方形PAD、役割色、波形、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。連打感と触覚の最終評価はHuman判断 |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate / Beat Loop | 🧪 local | PAD別。Beat Loopはチョップ範囲全体を連続再生し、直前の同一PAD試聴voiceを先に除去 |
| Choke group | ✅ | 1–4 |
| リアルタイム音声安全性 | 🧪 local | 操作queueを512件へ制限し1 block最大64件、Stop Allを容量外で優先しtransportも同じ境界で停止。clear世代境界により予約途中の古いcommandも次回起動へ残さない。PAD差替え/clearは128固定slotのlatest-wins mailboxでqueue飽和時の旧A01残留を防止。32 PAD voice＋source voiceを事前確保し通常render pathのVoice生成を除去 |
| マイク停止の完了確認 | 🧪 local | workerとWAV writerの終了を最大2秒確認し、timeout時は未完成WAVを成功扱い・decodeしない |
| 録音セッションと誤再生防止 | 🧪 historical emulator / current local | MIC / DEVICE / VOICEを単一の`STARTING → RECORDING → STOPPING`状態で排他管理。端末音声captureは世代付きsessionと2秒のstop/release境界を持ち、開始途中STOPと旧workerの新session破壊をLOCALで防止。開始時に既存再生を停止し、Vocalだけ選択Beat loopを再始動。録音中の競合操作を遮断。現候補の実MediaProjection/AudioRecord再検証は未実施 |
| 一時録音のprivacy cleanup | 🧪 local | app cacheのChopLab命名mic/system/vocal WAVだけを所有対象とし、decode成功・失敗・取消・無効化後に削除。異常終了残留は24時間後の起動時に限定清掃。SAF import、export、project、無関係cacheは削除対象外 |
| 主再生モードの二重音防止 | ✅ local | 元曲、範囲preview、Beat loop、transport、PAD/source scratchの開始前に既存voiceを共通境界で停止。Beat音色レールはPAD選択だけを行い自動試聴せず、その後のLoopを一本で開始。Sample Layer/Scratchの明示的試聴と、通常PADによる意図的なドラム等の重ね演奏は維持 |
| Android再生割り込み安全性 | ✅ emulator/local | 全audible startをmedia/music audio focusで統一。Home、focus loss/transient/duck、出力切替ではengine silenceをfocus解放より先に一箇所で強制し、反復割り込みは一度だけ停止、録音のみの割り込みは再生へ触れない。gainでは自動再開しない。回転は再生継続、source seek/KEY retargetはfocus所有中のみ。端末音声録音はbackgroundで継続し、MIC/VOICEは安全停止。実機route-loss／通話競合は未確認 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable stereo-capable domain is host-tested。MVP archiveは32-PAD page対応schema 5/WAVで保存し、schema 1–4（旧4×16配置を含む）を移行読込 |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | ✅ MVP/local | `.choplab`手動保存/読込、共有PCM16 WAV、schema 1–5 migration、path traversal/過大manifest/malformed WAV/進捗0 InputStreamをfail-closedで拒否。独立schema-1 fixture、固定seed malformed corpus、1000小entry、PCM総量超過をLOCAL検証 |
| 新しい音源への安全な切替 | 🧪 local/emulator | 既存制作がある場合は二度押し確認。読込開始時点で現在の再生を停止し、decode中の新規再生を遮断。成功時だけ別プロジェクトとしてA01を含む全PAD・step・loop・scratch・履歴を消去し、失敗／キャンセル／古い非同期完了は現制作へ触れない。録音停止後のdecode完了までは録音STOPPINGとして表示 |
| 再生表示と実音声の同期 | 🧪 local/device UI | `STOPPED / STARTING / PLAYING / STOPPING`を音声スレッド適用値と保留命令から導出。差替え・リセット・読込も停止確認前にSTOPPEDを表示せず、Undoは保留命令を復元しない |
| 全再生停止 | 🧪 local/device UI | `ALL STOP`がsource、PAD voice、loop、scratchの境界を先に発行してからtransportを停止。UIのstep/loop/scratchも同時に解除し、録音中データは明示的に継続 |
| 録音中の編集所有権 | 🧪 local | MIC / DEVICE / VOICEのSTARTING・RECORDING・STOPPING中はproject edit、UNDO、REDOを拒否し、ボタンも無効化。PAD preview開始はengine停止と同時にsource/transport/loop/scratch UI truthを更新 |
| Autosave/recovery | ✅ historical device / current local | 900ms debounce、SHA-256に結合した世代revision、store再生成後も古い/equal revisionを拒否、最新の検証済み世代を選ぶpending復旧、三世代保持。新しいrevisionメタデータ候補はLOCAL検証済みで、現候補のDEVICE再実行は未実施。手動上書き前にもアプリ内安全コピーを作成 |
| 起動時autosave復元の状態表示 | ✅ device/local | 復元開始を`isLoading`として公開し、空結果・失敗・成功で必ず解除。復元中は新規取込を無効化し、既に録音中ならSTOPだけを維持。`LOADING / 音声を読込中 / PLEASE WAIT`を表示してfalseな`NO SOURCE`を出さない |
| Undo / Redo | ✅ MVP | PAD、slice、sequence、BPM/Swing等を最大40操作。連続slider調整は1操作へcoalesce |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
| LLM / 音声AIアシスト | 📝 vision | 非破壊proposal、試聴比較、Undo、local-first、cloud明示同意の境界を`docs/AI_ASSIST_VISION.md`へ定義。実装は未着手 |
