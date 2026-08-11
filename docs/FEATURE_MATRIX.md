# 機能マトリクス

| 要望 | 実装 | 備考 |
|---|---:|---|
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ | 停止後に波形へ自動読込、PAD割当、16-step制作 |
| PAD付き | ✅ | 4 BANK × 16 PAD |
| 正方形PAD | ✅ device | 4×4 / 8×2とも親領域へ収まる最大正方形を中央配置。Pixel 9a portraitで確認 |
| 内蔵ドラムキット | ✅ device | オリジナル合成5キット×16音。常にBANK B（ドラム）へ適用し、既存音がある場合は二度押し確認。starter beat付き |
| ビートへ声を重ねる | 🧪 local | loop再始動に合わせて録音し、BANK Dへ最大16テイク。project schema 4とWAV exportに含む。実環境の声は未録音 |
| DJスクラッチ | 🧪 local/device UI | 選択音を左右dragで正逆再生、離すと停止。signed cursor/DSPはhost test、Pixel 9aで固定UIを確認 |
| レイヤー制作UI | ✅ device | `音を重ねる` 1入口に DRUMS / VOICE / SCRATCH を集約。固定画面・スクロールなし |
| 「おとひろい」正式UI | ✅ device | `入れる / チョップ（切る・鳴らす）/ ビート / 完成` の4工程。Pixel 9aで固定表示・操作確認 |
| スクロールなし操作 | ✅ preview | portrait/landscapeをconstraint-driven配置。UI sourceにscroll APIなし、411 × 923dp相当portrait emulatorで全固定操作を確認 |
| 曲を流しながらPADで刻む | ✅ preview | CI build、pure tests、Pixel 9aでimport→play→PAD 01割当→stopを観測 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| チョップ済みビート音声全体を連続ループ | 🧪 local | PAD範囲の末尾から先頭へ前向き・逆向きに折り返し、同時に使うループPADは1つ。実機音質確認待ち |
| 選択音を4つ打ち・8分・16分へ配置 | ✅ | `配置プリセット`として選択PADだけを置換し、他PAD・他BANKの重ね音を保持 |
| PAD→ビートをループ→音を重ねる導線 | ✅ device | 4レーン×16step、BANK別音色レール、ループ開始・停止を同一固定画面へ整理 |
| 上級操作の段階表示 | 🧪 emulator | 16手動step、BPM/Swing、録音/削除、KEY/TONE/LEVELを`細かく調整`へ移し、機能を削らず通常画面を簡素化 |
| ビート画面の実波形・再生位置 | ✅ device | 選択sliceのPCM波形、ビートループ位置、16-step playhead、A〜Dの4レーン発音マーカーを固定表示 |
| BANKを替えてドラムを重ねる | ✅ device | A=メロディー、B=ドラム、C=ワンショット、D=ボイスを常時表示し、全64 PADを演奏・配置 |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ | PAD別one-pole low-pass Tone。「暗い・なじむ・原音」の意味名付きpresetと連続slider |
| 長すぎる音声の箇所選択 | ✅ | 最大10分、zoom/scroll/S/E handles |
| プロ用のようにチョップ | ✅ MVP | 手動、自動、境界drag、zero-crossing snap。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | ✅ device | 4工程、正方形PAD、役割色、波形、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。連打感と触覚の最終評価はHuman判断 |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate / Beat Loop | 🧪 local | PAD別。Beat Loopはチョップ範囲全体を連続再生 |
| Choke group | ✅ | 1–4 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable stereo-capable domain is host-tested。MVP archiveはPAD role対応schema 4/WAVで保存し、schema 1/raw PCM、schema 2/WAV、schema 3/LOOPを移行読込 |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | ✅ MVP | `.choplab`手動保存/読込、共有PCM16 WAV、schema migration、path traversal/過大manifest/malformed WAV拒否 |
| Autosave/recovery | ✅ device | 900ms debounce、検証済みpending、同期排他、三世代保持。手動上書き前にもアプリ内安全コピーを作成 |
| Undo / Redo | ✅ MVP | PAD、slice、sequence、BPM/Swing等を最大40操作。連続slider調整は1操作へcoalesce |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
| LLM / 音声AIアシスト | 📝 vision | 非破壊proposal、試聴比較、Undo、local-first、cloud明示同意の境界を`docs/AI_ASSIST_VISION.md`へ定義。実装は未着手 |
