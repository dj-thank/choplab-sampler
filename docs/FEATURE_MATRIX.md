# 機能マトリクス

| 要望 | 実装 | 備考 |
|---|---:|---|
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ | 停止後に波形へ自動読込、PAD割当、16-step制作 |
| PAD付き | ✅ | 4 BANK × 16 PAD |
| 「おとひろい」正式UI | 🧪 emulator | 固定 `入れる / 切る / 叩く / 並べる / 完成` をPixel 9/API 36 emulatorで表示・操作確認。物理端末確認待ち |
| スクロールなし操作 | 🧪 emulator | portrait/landscapeをconstraint-driven配置。UI sourceにscroll APIなし、411 × 923dp相当portrait emulatorで全固定操作を確認 |
| 曲を流しながらPADで刻む | ✅ preview | CI build、pure tests、Pixel 9aでimport→play→PAD 01割当→stopを観測 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ | PAD別one-pole low-pass Tone |
| 長すぎる音声の箇所選択 | ✅ | 最大10分、zoom/scroll/S/E handles |
| プロ用のようにチョップ | ✅ MVP | 手動、自動、境界drag、zero-crossing snap。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | 🧪 emulator | 5工程、固定PAD、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。物理端末の連打・触覚確認待ち |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate | ✅ | PAD別 |
| Choke group | ✅ | 1–4 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable PCM/project validation and legacy adapter are host-tested; save/load UI is not implemented |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | — | 未実装 |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
