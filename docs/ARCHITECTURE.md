# ChopLab アーキテクチャ

## 1. 設計目標

ChopLabのMVPは、Android端末だけで次のループを成立させることを目的にしています。

1. 音を取り込む
2. 長尺音声から範囲を選ぶ
3. チョップする
4. PADへ順番に割り当てる
5. PADを演奏／編集する
6. 16ステップでビートを組む
7. WAVへ書き出す

UI層、制作状態、リアルタイム音声処理、オフライン書き出しを分離し、リアルタイム音声スレッド上でメモリアロケーションやAndroid UI処理を行わない構成です。

## 2. データモデル

`model/SamplerModels.kt`

- `PcmAudio`
  - 16-bit signed PCMの1ch/2ch interleaved `ShortArray`
  - `frameCount`は全channelを一緒に進める時間座標。`sampleAt`はchannel identity、`monoSampleAt`は表示／解析用の明示投影
  - 元サンプルレート
  - 表示名と一意ID
- `SliceRange`
  - start inclusive / end exclusiveのフレーム範囲
- `PadModel`
  - 参照する `PcmAudio`
  - 開始／終了フレーム
  - Pitch、Tone、Gain、Reverse、Play mode、Choke group
- `SamplerUiState`
  - 現在音源、選択範囲、境界、PAD、シーケンス、録音／Transport状態

同じ音源から複数スライスをPADへ割り当てた場合、PCM本体はコピーせず共有します。これにより典型的な1音源→16スライスではメモリ重複を避けます。

## 3. 入力パイプライン

### 3.1 ファイル

`AudioDecoder`

1. `MediaExtractor`で最初のaudio trackを選択
2. MIMEに対応する`MediaCodec` decoderを作成
3. decoder outputをPCMとして取得
4. 2chは左右を保持し、1chはmonoのまま、3–8chはspeaker layoutを推測せず従来どおり平均monoへ変換
5. PCM float/8/16/24/32-bitを内部PCM-16へ正規化
6. 微小なDC offsetをchannel別に除去

import sample rateはproject/archive契約と同じ8–192 kHzです。AndroidとWindowsはこの範囲外をproject stateへ公開する前に拒否します。展開可能なaudio frame数は `min(30,000,000, sampleRate × 600秒)` です。duration metadataが欠落・不正確でも両platformのstreaming builderが同じ上限で停止し、2chではinterleaved sample数を2倍として容量へ反映します。最初のPCM出力後にsample rateまたは保存channel shapeが変わった場合は、後続PCMの有無にかかわらずfail closedにします。

### 3.2 マイク

`MicrophoneRecorder`

- `AudioRecord`
- 48 kHz / mono / PCM-16
- `UNPROCESSED` audio sourceを優先し、使用できない端末では`MIC`へフォールバック
- 専用audio-priority threadでストリーミングWAV書き込み

### 3.3 端末再生音

`PlaybackCaptureService`

- Foreground Service type: `mediaProjection`
- `MediaProjectionManager`のユーザー許可結果を受け取る
- `AudioPlaybackCaptureConfiguration`でMEDIA/GAME/UNKNOWN usageを対象化
- stereoを優先し、初期化できなければmonoへフォールバック
- PCM-16 WAVへストリーミング保存
- 完了ファイルを`CaptureEventBus`経由でViewModelへ通知

録音元アプリがPlayback Captureを許可していない場合、Androidの仕様により音声は取得できません。

## 4. 波形とチョップ

`WaveformEditor`

- 表示区間だけをCanvasへ描画
- ピクセル幅ごとにmin/max peakを抽出して長尺波形の描画量を制限
- S/E handleと各slice markerを`draggable`で操作
- zoom倍率からvisible frame rangeを算出
- タップ位置をframeへ逆変換

`TransientDetector`

- 約5 ms windowのRMS energy
- 指数平滑化後の正方向energy差分をnoveltyとして計算
- mean + standard deviation × sensitivityを閾値に使用
- local peakをscore順に選択
- 65 msのminimum distanceで密集した候補を排除

これはドラム／フレーズ用の軽量ヒューリスティックです。商用品質へ進める場合はspectral flux、multi-band onset、zero-crossing snap、ML onset detectorなどを追加できます。

## 5. PAD割り当て

`SamplerViewModel.assignRanges`

- 現在BANK内で選択PADから順に割り当て
- 16を超える場合はBANK内で循環
- PAD parameterは再割り当て前の値を保持
- AUTO NEXTがONの場合、最後に割り当てた次のPADへ移動
- 単一slice割り当て時はactive sliceも次へ移動
- 変更されたPADだけをリアルタイムengineへcommand queueで同期

## 6. リアルタイム音声エンジン

`SamplerEngine`

### Thread model

- UI/ViewModel threadは`ConcurrentLinkedQueue`へcommandを投入
- audio threadがblock境界でcommandをdrain
- `THREAD_PRIORITY_AUDIO`
- Android UI、Coroutine、ファイルI/Oをaudio threadから呼ばない

### Output

- `AudioTrack.MODE_STREAM`
- `ENCODING_PCM_FLOAT`
- stereo output（mono sourceはL/Rへ複製し、stereo source/PAD/scratchは左右を別々にmix・limit）
- `PERFORMANCE_MODE_LOW_LATENCY`
- 端末が申告するnative sample rateを優先
- partial writeを考慮し、block全体を書き切るまでループ

### Voice DSP

- 最大32 voices
- Pitch: semitoneからratioを求めるvariable-rate resampling
- Interpolation: linear
- Tone: one-pole low-pass、最大値付近ではbypass
- Gain: per voice
- Reverse
- One Shot / Gate
- Choke group: 同groupを48 framesでfast release
- 境界クリック抑制: source boundaryの短いfade-in/fade-out
- Mix protection: `abs(x) <= 0.9` は bit-for-bit linear、超過時だけ C1 連続の rational knee で `0.98` 未満へ漸近する shared master limiter。Android AudioTrack と offline WAV が同じ関数を使用

### Sequencer

- audio frame単位で次stepまでの残りframeをカウント
- BPMから16分音符長を算出
- Swing値により偶数stepを長く、奇数stepを短くするが、2 step合計長は一定
- fractionalなstep deadlineは、その時刻より前ではない最初の整数frame（ceiling）で発火する
- step onsetで該当PAD voiceを開始

## 7. オフライン書き出し

`PatternRenderer`

- リアルタイムengineと同じPitch/Tone/Gain/Reverse/Choke/limiterロジック
- Pattern modeの選択A/B × 4 bars、またはSong modeのA/B 4 sectionsについて、Android countdownと同じ連続fractional timingでevent frameを先に計算
- realtimeと同じfractional countdown残差をbar間でも持ち越し、step長を加算→ceilingで整数frameを進行→量子化残差を次stepへ渡す
- 実際に発音する素材がmonoだけなら48 kHz mono、stereoを含むなら48 kHz interleaved stereo PCM-16をchunk単位で`WavFileWriter`へ送る
- UIのCreate Documentで選ばれたURIへ一時ファイルをコピー

リアルタイムengineとoffline rendererのVoice lifecycleは現状別実装です。一方、pitch step、tone coefficient、gain sanitation、forward/reverse boundary fade、master limiter、swing step durationはallocation-free shared primitivesへ移し、Android realtime Voiceとhost PAD rendererのPCM oracleを持ちます。default gain 0.9のfull-scale source/PADはmasterで非線形変形せず、2〜32 voice相当の過負荷だけを有限・正負対称・単調な0.98未満へ制限します。single PAD / single event / full barではmonoと左右非対称stereoの両方をrealtime Voice + shared limiterとoffline WAVで全frame比較し、channelごと最大1 PCM unitをgateとします。polyphony/choke/loop/vocalをさらに拡張してから共通Voice kernelまたはnative moduleへ進みます。

### 7.1 制作archive

`ProjectArchiveCodec`

- current schema 7はaudio manifestへ`channelCount`を記録し、1ch/2ch PCM-16 WAVのchannel、byte rate、block align、frame count、data lengthを相互検証
- schema 1–6はchannel identityを持たないため、既存contractどおりmonoとして移行
- manifest宣言時点と各entry読込時の両方で、frame × channel × 2 bytesをarchive／resident PCM budgetへ算入
- current sourceと複数PADが同じaudio IDを共有する場合、name/rate/channel shape/sample bytesが一致しなければ拒否

## 8. メモリ戦略

- PCMはFloatArrayではなくShortArrayで保持
- PADはPCM本体をコピーせずrange参照
- waveformは全frameをPath化せず、表示ピクセル単位でpeak抽出
- decoderにduration/frame上限

次段階では、memory-mapped PCM cache、LRU source cache、background waveform pyramid、streaming decoderを導入する余地があります。

## 9. 製品化ロードマップ

優先順位の高い順:

1. shared `ProductionCommand` / effect spineと、編集履歴・revision・autosaveを持つ`ProductionSession`
2. persistent Production、session selection、実適用済みruntime stateの分離
3. realtime/offline共通event compiler・DSP parity harnessと、その下でのOboe/AAudio段階移行
4. Stereo sample path、multi-output renderer、実機latency/xRun matrix
5. Zero-crossing snap、fade editor、normalize、trim、time-stretch independent from pitch
6. ADSR/filter envelope/LFO/insert and send FX
7. bounded A/B Songを越える任意数pattern、可変section repeat、per-track length/polymeter
8. MIDI/USB MIDI、velocity、aftertouch
9. Stem export、share、portable project package
10. Instrumented tests、Macrobenchmark、battery/thermal profilingとgate別release automation

全体方針と評価軸は`docs/architecture/global-product-optimization-2026-08-24.md`を正本とする。native engineだけを先行させても、platformごとの編集規則、履歴、保存、runtime truthの分岐は解消しないため、semantic spineとparity harnessを先に成立させる。
