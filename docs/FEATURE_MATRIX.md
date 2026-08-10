# 機能マトリクス

| 要望 | 実装 | 備考 |
|---|---:|---|
| 流れている音楽を録音 | ✅ | Android Playback Capture。録音元が許可した音のみ |
| 録音をそのままビート化 | ✅ | 停止後に波形へ自動読込、PAD割当、16-step制作 |
| PAD付き | ✅ | 4 BANK × 16 PAD |
| 「おとひろい」正式UI | ✅ preview | 固定 `入れる / 切る / 叩く / 並べる / 完成` をPixel 9/API 36 emulatorで表示・操作し、公開APKをPixel 9aへ導入・起動確認 |
| スクロールなし操作 | ✅ preview | portrait/landscapeをconstraint-driven配置。UI sourceにscroll APIなし、411 × 923dp相当portrait emulatorで全固定操作を確認 |
| 曲を流しながらPADで刻む | ✅ preview | CI build、pure tests、Pixel 9aでimport→play→PAD 01割当→stopを観測 |
| 波形タップで頭出し | 🧪 source | source停止中／再生中のseekを実装。実機確認待ち |
| 曲全体のトーン | 🧪 source | ±12 semitone、速度連動。実機確認待ち |
| 音楽からビートを作る | ✅ | Chop + PAD + Sequencer + WAV export |
| 選択音を4つ打ち・8分・16分で反復 | ✅ | 選択PADだけを置換し、他PAD・他BANKの重ね音を保持 |
| 並べる画面の実波形・再生位置 | ✅ preview | 選択sliceのPCM波形、太い16-step playhead、A〜D BANK発音マーカーを固定画面で確認し公開 |
| BANKを替えてドラムを重ねる | ✅ preview | 全64 PADの既存layer再生、BANK別timeline marker、空の次BANK/PADから`叩く`へ直行する音追加導線を公開 |
| 取り込んだ音の場所を選ぶ | ✅ | 波形S/E範囲、slice選択 |
| トーンを変える | ✅ | PAD別one-pole low-pass Tone。「暗い・なじむ・原音」の意味名付きpresetと連続slider |
| 長すぎる音声の箇所選択 | ✅ | 最大10分、zoom/scroll/S/E handles |
| プロ用のようにチョップ | ✅ MVP | 手動、自動、境界drag、zero-crossing snap。高度なspectral editor等は次段階 |
| 選択後に次の対象へ遷移 | ✅ | AUTO NEXTでPAD + active slice前進 |
| ハードウェア系サンプラーの操作感 | ✅ preview | 5工程、固定PAD、KEY/TONE/LEVEL、触覚、二段階clearを独自UIで再構成。公開APKの実機導入・起動まで確認。連打感と触覚の最終評価はHuman判断 |
| Pitch | ✅ | ±24 st、速度も連動 |
| Reverse | ✅ | PAD別 |
| One Shot / Gate | ✅ | PAD別 |
| Choke group | ✅ | 1–4 |
| Swing | ✅ | 50–75% |
| Pattern export | ✅ | 4 bars mono WAV |
| Versioned stereo-capable project domain | 🧪 foundation | Immutable stereo-capable domain is host-tested。MVP archiveは現在のmono engine状態をschema 2/WAVで保存し、schema 1/raw PCMを移行読込 |
| Legacy/native engine coexistence boundary | 🧪 foundation | Playback/render interfaces added; native Oboe engine is not implemented |
| Project save/load | ✅ MVP | `.choplab`手動保存/読込、共有PCM16 WAV、schema migration、path traversal/過大manifest/malformed WAV拒否 |
| Autosave/recovery | ✅ MVP | 900ms debounce、fsync後の二世代置換、最新破損時は前世代、置換中断時はvalid pendingへ復旧 |
| Undo / Redo | ✅ MVP | PAD、slice、sequence、BPM/Swing等を最大40操作。連続slider調整は1操作へcoalesce |
| MIDI | — | 未実装 |
| Independent time-stretch | — | 未実装 |
| Stereo internal engine | — | 未実装 |
| Native Oboe engine | — | 未実装 |
