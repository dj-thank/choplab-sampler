# ChopLab Android Pro 0.2.0 — 機能マトリクス

凡例: **実装済み**はUIから操作可能、**基盤実装**はエンジン／データ形式まで実装済みだが商用製品水準には追加調整が必要、**対象外**は本版に含まれない機能です。

## 要望への対応

| 要望 | 状態 | 実装内容／境界 |
|---|---:|---|
| 流れている音楽を録音 | 実装済み | Android Playback Capture。録音元アプリが許可した音のみ。MediaProjection許可と`RECORD_AUDIO`が必要 |
| 録音をそのままビート化 | 実装済み | 録音停止後に波形へ自動読込し、Chop→PAD→Pattern／Songへ接続 |
| PAD付き | 実装済み | 4 Bank × 16 PAD、合計64 PAD |
| 取り込んだ音の場所を選ぶ | 実装済み | S/E範囲、Zoom／Scroll、slice選択、境界drag |
| 長い音声の一部を選択 | 実装済み | 長尺波形の範囲編集。1音源30,000,000 frameの安全上限 |
| プロ用のようにChop | 実装済み | 手動、4/8/16等分、Transient、zero-crossing snap、境界削除／全消去、audition |
| 選択後に次へ遷移 | 実装済み | AUTO NEXTで割当先PADと対象sliceを同時に前進 |
| AKAI系の制作フロー | 基盤実装 | Chop、Bank、PAD、Pattern、Song、FX、MIDIを独自UIで構成。固有UI／商標／firmwareは非複製 |

## Proアップグレード

| 機能 | 状態 | 実装内容 |
|---|---:|---|
| Oboe音声エンジン | 実装済み | Oboe 1.10.0、C++20、stereo float callback、AAudio／OpenSL ES、Exclusive→Shared fallback、native rate／burst、96 voice |
| プロジェクト保存 | 実装済み | `.choplab` ZIP。JSON schema 2＋重複排除PCM WAV。SAF保存／読込、内部autosave |
| Undo／Redo | 実装済み | 最大64 snapshot。slider変更coalescing。PCM配列はcopyせず参照共有 |
| ステレオ処理 | 実装済み | Decoder、PCM model、native mixer、Pan、Delay／Reverb、offline renderer、WAV exportをstereo化 |
| 独立Time Stretch | 基盤実装 | Granular overlap-add。Pitch ±24 stとduration 0.25–4.0倍を独立制御。極端な設定ではartifactあり |
| ADSR | 実装済み | PAD別Attack 0–5000 ms、Decay 0–5000 ms、Sustain 0–1、Release 1–10000 ms |
| LFO | 実装済み | 5 waveform、Amp／Pan／Pitch／Filter、0.05–30 Hz、tempo sync 1 bar～1/32 |
| エフェクト | 実装済み | PAD Drive／Bit Crush／Sample-rate Reduction／Send、Master Ping-pong Delay／Reverb／Compressor／Drive／Gain |
| MIDI | 実装済み | Android MIDI、USB／Bluetooth／virtual、Note On/Off、Velocity、CC Learn、running status、24 PPQN Clock、transport |
| Songモード | 実装済み | 16 Patternを最大128 sectionへ配置、1–64 repeat、連続再生／render |
| ステム書き出し | 実装済み | ZIP内にMaster、使用Bank、使用PAD、manifest。48 kHz／16-bit／stereo |

## Sampler／Sequencer詳細

| 領域 | 状態 | 仕様 |
|---|---:|---|
| Pitch | 実装済み | PAD別 ±24 semitone |
| Stretch | 実装済み | PAD別 0.25–4.0倍 |
| Tone／Resonance | 実装済み | PAD別TPT low-pass／resonance |
| Gain／Pan | 実装済み | PAD別Gain 0–150%、Pan L–R |
| Reverse | 実装済み | PAD別。direct／granular双方 |
| One Shot／Gate | 実装済み | Note Off／PAD releaseでADSR Releaseへ遷移 |
| Choke | 実装済み | Group 1–8。新規trigger時に同groupをrelease |
| Voice handling | 実装済み | 最大96 voice、古いvoiceのsteal、command drop counter |
| Pattern | 実装済み | 16個、16／32／64 step、64 PAD独立step |
| Swing | 実装済み | 50–75% |
| Live Record | 実装済み | PAD入力を再生中の現在stepへquantize |
| Song | 実装済み | Pattern section／repeat、timeline flattening |
| Master WAV | 実装済み | 48 kHz／PCM16／stereo、FX tail付き |
| Bank stem | 実装済み | 使用Bank単位。PAD insert FX込み、Master FXなし |
| PAD stem | 実装済み | 使用PAD単位。PAD insert FX込み、Master FXなし |

## 保存／安全性

| 項目 | 状態 | 仕様 |
|---|---:|---|
| Deterministic archive | 実装済み | ZIP entry timestamp固定、ID順、同一状態から再現可能なarchive |
| Audio deduplication | 実装済み | 同じ`PcmAudio.id`は一つの`audio/<id>.wav`へ保存 |
| Schema version | 実装済み | `SCHEMA_VERSION = 2`、未知versionは拒否 |
| Manifest上限 | 実装済み | `project.json`最大4 MiB |
| Asset上限 | 実装済み | 最大65音源、各30,000,000 frame、総80,000,000 interleaved sample |
| Corruption checks | 実装済み | 負ID、重複ID、未参照audio、WAV format／size、rangeを検証／sanitize |
| Autosave | 実装済み | 編集から1.2秒debounce後に内部fileへ保存。破損autosaveは削除してclean start |

## 本版の対象外

| 機能 | 状態 | 備考 |
|---|---:|---|
| Disk streaming | 対象外 | PCMはメモリ常駐。大量・長尺素材には別cache層が必要 |
| Phase-vocoder品質のStretch | 対象外 | 本版はlow-latency granular方式 |
| Per-step velocity／probability／micro-timing | 対象外 | 現在のstep eventはPAD bitmask |
| Automation lane | 対象外 | MIDI CCは現在値を直接編集する方式 |
| MIDI output／MPE | 対象外 | MIDI inputのみ |
| Ableton Link | 対象外 | MIDI Clock Syncのみ |
| Audio track／multitrack recorder | 対象外 | Sample／Pattern／Song中心 |
| Plugin host | 対象外 | VST／AU／CLAP等はAndroid版に含まない |
| AKAI project互換 | 対象外 | 独自`.choplab`形式 |
