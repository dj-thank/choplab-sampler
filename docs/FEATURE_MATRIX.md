# 機能マトリクス

| 要望 | 実装 | 備考 |
|---|---:|---|
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ | 停止後に波形へ自動読込、PAD割当、16-step制作 |
| PAD付き | ✅ emulator | 4 BANK × 32 PAD（合計128）。各BANKを固定01–16 / 17–32ページで表示 |
| 正方形PAD | ✅ device/emulator | 4×4 / 8×2とも親領域へ収まる最大正方形を中央配置。Pixel 9a portraitとv0.11 portrait/landscape emulatorで確認 |
| 内蔵ドラムキット | ✅ device | オリジナル合成5キット×16音。常にBANK B（ドラム）へ適用し、既存音がある場合は二度押し確認。starter beat付き |
| ビートへ声を重ねる | 🧪 local | loop再始動に合わせて録音し、BANK Dへ最大32テイク。project schema 5とWAV exportに含む。実環境の声は未録音 |
| DJスクラッチ | 🧪 local/emulator UI | 元曲の波形で選んだsliceまたはS/E範囲を左右dragで正逆再生し、離すと停止。range/DSPはhost test、固定UIはemulator確認 |
| レイヤー制作UI | ✅ emulator | `音を重ねる` 1入口に SOUNDS / DRUMS / VOICE / SCRATCH を集約。SOUNDSは全BANKの音を4つ打ち・8分・16分で配置可能 |
| 「おとひろい」正式UI | ✅ device/emulator | `入れる / チョップ / ビート / 保存` の4工程。Pixel 9a v0.10とv0.11 emulatorで固定表示を確認 |
| スクロールなし操作 | ✅ preview | portraitは上下固定、landscapeのChopは波形＋右4×4 PADの左右分割、Beatはcompact操作列。UI sourceにscroll APIなし |
| 曲を流しながらPADで刻む | ✅ preview | CI build、pure tests、Pixel 9aでimport→play→PAD 01割当→stopを観測 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| チョップ済みビート音声全体を連続ループ | 🧪 local | PAD範囲の末尾から先頭へ前向き・逆向きに折り返し、同時に使うループPADは1つ。実機音質確認待ち |
| 選択音を4つ打ち・8分・16分へ配置 | ✅ | `配置プリセット`として選択PADだけを置換し、他PAD・他BANKの重ね音を保持 |
| PAD→ループ／並べる→足す／擦る導線 | ✅ device/emulator | 4レーン×16step、BANK別音色レール、選択音ループ、Add、Scratchを同一固定画面へ整理 |
| 上級操作の段階表示 | 🧪 emulator | KEY/TONE/LEVELは通常BEAT画面から直操作。16手動step、BPM/Swing等は`細かく調整`へ整理 |
| ビート画面の実波形・再生位置 | ✅ device | 選択sliceのPCM波形、ビートループ位置、16-step playhead、A〜Dの4レーン発音マーカーを固定表示 |
| BANKを替えて音を重ねる | ✅ emulator | A=メロディー、B=ドラム、C=ワンショット、D=ボイスを常時表示し、全128 PADを演奏・配置 |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ | PAD別one-pole low-pass Tone。「暗い・なじむ・原音」の意味名付きpresetと連続slider |
| 長すぎる音声の箇所選択 | ✅ | 最大10分、zoom/scroll/S/E handles |
| プロ用のようにチョップ | ✅ MVP | 手動、自動、境界drag、zero-crossing snap。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | ✅ device | 4工程、正方形PAD、役割色、波形、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。連打感と触覚の最終評価はHuman判断 |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate / Beat Loop | 🧪 local | PAD別。Beat Loopはチョップ範囲全体を連続再生し、直前の同一PAD試聴voiceを先に除去 |
| Choke group | ✅ | 1–4 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable stereo-capable domain is host-tested。MVP archiveは32-PAD page対応schema 5/WAVで保存し、schema 1–4（旧4×16配置を含む）を移行読込 |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | ✅ MVP | `.choplab`手動保存/読込、共有PCM16 WAV、schema migration、path traversal/過大manifest/malformed WAV拒否 |
| 新しい音源への安全な切替 | 🧪 local/emulator | 既存制作がある場合は二度押し確認。成功時だけ別プロジェクトとしてPAD・step・loop・scratch・履歴を消去し、失敗／キャンセル／古い非同期完了は現制作へ触れない |
| 再生表示と実音声の同期 | 🧪 local | 命令発行世代と音声スレッド適用世代を分離。キュー投入だけでは再生中を表示せず、古い完了も新しい再生を停止表示へ戻さない |
| Autosave/recovery | ✅ device/local | 900ms debounce、検証済みpending、同期排他、revision逆転拒否、三世代保持。手動上書き前にもアプリ内安全コピーを作成 |
| Undo / Redo | ✅ MVP | PAD、slice、sequence、BPM/Swing等を最大40操作。連続slider調整は1操作へcoalesce |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
| LLM / 音声AIアシスト | 📝 vision | 非破壊proposal、試聴比較、Undo、local-first、cloud明示同意の境界を`docs/AI_ASSIST_VISION.md`へ定義。実装は未着手 |
