# 用語

製品契約は [PRODUCT](PRODUCT.md)、状態と受入は [ROADMAP](ROADMAP.md)。予定の型名は実装済みの主張ではありません。

| 用語 | 意味 |
|---|---|
| Production / 制作 | Source、Chop、PAD、pattern、song、設定を含む再開可能な一つの作品 |
| Source / 素材 | チョップや録音の元になる音声。metadataだけの検索候補とは別 |
| Audio frame | 同じ時刻の全channelの組。stereoなら左右2samples。範囲/再生位置はsample配列indexでなくframeで数える |
| Channel identity | 左右の値と順番を取込・再生・保存・書出しまで保つ契約。解析用mono投影は別 |
| Chop / Slice | Source内の開始を含み終了を含まない `[start, end)` の範囲 |
| PAD | Chopや一音を演奏する場所。現行A–D×32、計画A–H×16で総128 |
| Beat loop | Chopそのものの長さで繰返す音声。stepで再triggerする配置とは別 |
| Pattern / placement | PADをいつ鳴らすかの配置。現行A/B16step、新設計はtick/velocityと可変長 |
| Song arrangement | patternの順と繰返し。現行A/B4小節、新設計は複数pattern/repeat |
| Tick / frame | tickは音楽的位置（計画960/四分音符）、frameは音声時間。tempo変換では端数を保持 |
| Bank / Drum kit | PADのまとまり / 合成drum音色群。新規starterは復元した制作や既存rhythmを上書きしない |
| Production edit | 音や配置を変えるUndo可能な文書変更。選択や進捗表示だけのSESSION変更とは別 |
| Runtime action | 再生、録音、device等の一時操作。成功結果を受け入れた時だけ制作contentになる |
| ProductionSession / EditSession | 計画→必要な副作用→commit/cancel、revision、historyを所有する編集境界。後者は再構築の名称 |
| Studio | 再構築で唯一の振る舞いの持ち主。UIとOS driverはintent/portで接続 |
| Notice | coreが出す種類付き通知。表示文言はUI resourceで決める |
| Voice / fade tail | 発音中の音 / 停止・steal時のクリックを抑える解放音。数と満杯時規則を持つ |
| ScratchSurface | 時刻付きsource位置・CUT・開始終了の共通入力。touch/mouse/jogはadapter |
| Recording session / Vocal take | 同時に一つの録音操作 / 伴奏に対して保存する声のテイク。新設計では非破壊comp対象 |
| Personal audio library | 制作と別のapp-private、content-addressed素材庫。`.choplib`で持ち出す |
| `.choplab` / `.choplib` | version付き制作archive / 素材library archive。app bundleではない |
| Asset / cache | 制作が参照する必要音声 / 再生成可能な派生PCM等。後者もRAM/disk上限を持つ |
| Proposal | 試聴/差分/適用前のAI提案。直接制作を書換えない |
| Audio parity oracle | 固定fixtureを複数経路へ通す数値比較。物理遅延や主観的同等性を証明しない |
| Preview / NEXT | 既存版とdata/signingを分離する配布 / Previewだけで選べる新実装入口の計画 |
| Receipt / readback | revision・bytes・時刻・scope・失敗経路を確認した結果 / 変更後の実物を読み直す操作 |
