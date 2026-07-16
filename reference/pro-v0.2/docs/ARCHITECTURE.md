# ChopLab Android Pro 0.2.0 — アーキテクチャ

## 1. 目的と境界

ChopLabは、Android端末だけで次の制作ループを成立させます。

```text
Import / MIC / Playback Capture
        ↓
Stereo PCM + Waveform range
        ↓
Manual / Equal / Transient Chop
        ↓
64 PAD + Sound design
        ↓
Pattern 1–16 / Song arrangement
        ↓
Oboe real-time playback
        ↓
Project archive / Master WAV / Stems ZIP
```

低遅延のリアルタイム処理はC++、状態管理・保存・Android API・UIはKotlinへ分離しています。オフライン書き出しはAndroid audio deviceに依存せず、Kotlinのstreaming rendererで実行します。

## 2. レイヤ構成

```text
Compose UI
  └─ SamplerViewModel
       ├─ ProjectSnapshot / UndoManager
       ├─ ProjectArchive (.choplab)
       ├─ SequenceCompiler
       ├─ MidiController
       ├─ AudioDecoder / Recorders
       ├─ OfflineRenderer
       └─ SamplerEngine
            └─ NativeSamplerEngine (JNI)
                 └─ EngineHost (Oboe)
                      └─ SamplerCore (DSP / Sequencer)
```

### UI／状態層

- `MainActivity`: SAF launcher、runtime permission、MediaProjection許可
- `SamplerScreen`: Wave、PAD、Sound、Pattern、Song、FX、MIDI、Export操作
- `SamplerViewModel`: 単一の`SamplerUiState`を管理し、編集をimmutable `ProjectSnapshot`へ変換
- `UndoManager`: 最大64のsnapshotを保持し、Undo／Redo labelとcoalescingを管理

### Android I/O層

- `AudioDecoder`: `MediaExtractor`／`MediaCodec`で音声をPCM-16へdecode。monoまたはstereoを保持し、2ch超はstereoへdownmix
- `MicrophoneRecorder`: `AudioRecord`から48 kHz PCM-16 WAVを生成
- `PlaybackCaptureService`: Foreground Service、MediaProjection、AudioPlaybackCaptureConfiguration、AudioRecordを組み合わせる
- `MidiController`: Android MIDI serviceからdevice／portを管理し、MIDI byte streamをeventへ変換

### Audio層

- `SamplerEngine`: Kotlin control plane。native sample ID cache、PAD同期、timeline／master更新、stats取得
- `NativeSamplerEngine`: JNI functionを型付きAPIへ集約
- `EngineHost`: Oboe streamのopen／start／stop、data callback、error callback
- `SamplerCore`: sample pool、PAD parameter snapshot、voice、sequencer、DSP、FX bus
- `OfflineRenderer`: real-time設定と同じ意味のparametersでMaster／stemsをoffline生成

## 3. PCMデータモデル

`PcmAudio`は次を保持します。

```kotlin
data class PcmAudio(
    val id: Long,
    val name: String,
    val samples: ShortArray,   // interleaved PCM-16
    val sampleRate: Int,
    val channelCount: Int,     // 1 or 2
)
```

- mono: `M, M, M...`
- stereo: `L, R, L, R...`
- `frameCount = samples.size / channelCount`
- waveform／transient解析ではL/R平均を使用
- native upload時、monoはL/Rへ複製し、内部は常にstereo float
- `id`はProject archive、PAD共有、native sample cacheのidentityに使う

保存／UndoでPCM配列をdeep copyしません。複数PADと複数history snapshotが同じimmutable `PcmAudio`を参照するため、長尺音源を含むUndoでメモリが線形に増えにくい構造です。

## 4. Thread model

### Main thread

Compose eventと`SamplerViewModel`の状態更新を担当します。重いdecode、archive、renderはCoroutine dispatcherへ移動します。

### Engine control thread

`Executors.newSingleThreadExecutor()`由来のdispatcherで、project同期、sample upload、sequence差替え、engine statsを直列化します。`SamplerEngine`とJNI public methodも`synchronized`でclose／restart／updateの競合を抑えます。

### Oboe callback thread

`onAudioReady`はstereo float bufferを受け取り、`SamplerCore::process()`だけを実行します。callback内では次を行いません。

- mutex lock
- file／network I/O
- JNI callback
- heap allocation／free
- sleep／blocking wait

Trigger／Release／Transport commandはfixed-size lock-free ring bufferへ投入します。PAD parametersはatomic fieldからsnapshotを作り、sample IDを最後にrelease-publishすることで半更新状態を避けます。

### Sequence lifetime

Pattern／Song timelineはimmutable `SequenceData`へ変換し、atomic pointerで差し替えます。旧sequenceはcallback epochを3回経過してからcontrol threadが回収します。audio threadで`shared_ptr` refcountやdeallocationを発生させません。

## 5. Oboe stream

`EngineHost`は次の条件でoutput streamを開きます。

- Direction: Output
- Performance: Low Latency
- Format: Float
- Channels: Stereo
- Usage: Game
- Content: Music
- Sharing: Exclusiveを先に試し、失敗時Shared
- Device native sample rate／frames-per-burstをKotlin `AudioManager`からhint
- Format conversion許可
- bufferは概ね2 burstを目標にcapacity内へclamp

OboeがAAudioを選択できる端末ではAAudio、それ以外ではOpenSL ESとなります。backend、sample rate、burst、xRun count、active voice、dropped command、native errorをUIへpollします。

## 6. Voice／Pitch／Time Stretch

### Voice allocation

- 最大96 voice
- PAD 0–63が演奏用、64番は波形audition専用
- free voiceを優先し、上限時は最も古いvoiceをsteal
- Choke group trigger時は同group voiceをrelease
- Gate release／MIDI Note OffでADSR Releaseへ移行

### Direct playback

Pitch 0、Stretch 1、Pitch LFOなしの場合はlinear interpolationによるdirect readerを使います。source sample rateとoutput rateの比を含むread incrementでmono／stereoを処理します。

### Independent Pitch／Stretch

独立処理が必要な場合はgranular overlap-addへ切り替えます。

- Hann-windowed grain
- 基本grain length最大1024 frame
- 1/4 grain hop
- source hopを`stretchRatio`で調整
- grain内read incrementをpitch ratioで調整
- reverse時はsource方向を反転
- Pitch LFOを使うvoiceもgranular path

概念式:

```text
pitchRatio = 2^(semitones / 12)
readIncrement = sourceRate / outputRate × pitchRatio × direction
grainSourceHop = grainHop × sourceRate / outputRate ÷ stretchRatio × direction
```

これによりPitchと出力durationを別々に制御します。リアルタイム性を優先した方式であり、phase vocoder等のoffline高品質処理とはartifact特性が異なります。

## 7. PAD DSP order

概念上の処理順は次のとおりです。

```text
Sample reader / Granular reader
  → edge fade
  → LFO modulation
  → ADSR × Velocity
  → TPT Low-pass + Resonance
  → Drive
  → Bit-depth reduction
  → Sample-rate reduction
  → Gain / equal-power Pan
  → Dry master bus
  ├→ Delay send
  └→ Reverb send
```

### ADSR

- Attack 0–5000 ms
- Decay 0–5000 ms
- Sustain 0–1
- Release 1–10000 ms
- source終端後もRelease処理を継続可能

### LFO

Waveform:

- Sine
- Triangle
- Square
- Saw
- Sample & Hold

Target:

- Amp
- Pan
- Pitch
- Filter

Free modeは0.05–30 Hz、Sync modeはBPMと1 bar～1/32 divisionからfrequencyを算出します。

### Master FX

```text
Dry bus + Delay return + Reverb return
  → Compressor
  → Master Drive
  → Master Gain
  → Soft clip
```

Delayはbeat同期とping-pong、Reverbは複数comb／all-pass系、Compressorはattack／release envelope followerです。

## 8. Sequencer／Song

Pattern eventは`stepKey = padIndex × 64 + stepIndex`で保持します。compile時に各stepを64-bit PAD maskへ変換します。

```text
step 0: 0b...0101  // PAD 0 and PAD 2
step 1: 0b...0000
...
```

- 16 Pattern
- Pattern length 16／32／64
- 最大64 PADを一つの`Long`で表現
- SongはPattern／repeatをflattenし、最大16,384 step
- `patternAtStep`、`localStepAtStep`、`sectionAtStep`を同時保持
- Native sequencerは16分音符単位、奇数stepへSwing delayを適用

このbitmask形式は軽量ですが、per-step velocity、probability、micro-timing等を将来追加する際はevent structureへの拡張が必要です。

## 9. MIDI

`MidiController`はAndroid MIDI deviceのoutput port（アプリから見たinput source）を開きます。parserはrunning statusを保持し、次を処理します。

- Note On／Off
- CC
- MIDI Clock (`0xF8`)
- Start (`0xFA`)
- Continue (`0xFB`)
- Stop (`0xFC`)

PAD mappingは`midiBaseNote + globalPadIndex`です。channelはOmniまたは0–15に絞れます。Clockは直近pulse intervalから24 PPQN BPMを算出し、異常値を除外して追従します。CC Learnはtargetごとに最後のmappingを使用します。

## 10. Project archive

`.choplab`はZIPコンテナです。

```text
project.json
 audio/<audio-id>.wav
 audio/<audio-id>.wav
 ...
```

`project.json` schema 2に含むもの:

- project name
- current source、S/E、slice markers
- PAD割当と全sound parameters
- 16 Pattern
- Song sections
- BPM／Swing／playback mode
- Master FX
- MIDI channel／base note／clock／CC mappings

同じ`PcmAudio.id`を参照するPADはWAVを一度だけ保存します。ZIP entry timeを0へ固定し、audio IDとcollectionを安定順で出力して、同じ状態から決定的archiveを作ります。

読込防御:

- schema version検査
- `project.json` 4 MiB上限
- audio asset最大65
- 1 asset最大30,000,000 frame
- 総80,000,000 interleaved sample
- negative／duplicate audio ID拒否
- WAV channel／sample rate／data size検査
- range、enum、parameterのsanitize

## 11. Undo／Redo

履歴entryは「変更前snapshot」とlabelです。新しい編集時にredo stackを破棄し、最大64件へtrimします。連続slider変更は同じlabelでcoalesceし、dragごとに大量の履歴が増えるのを防ぎます。

音声recording／file importのような大きい状態もsnapshotへ含みますが、`ShortArray`をcopyせずimmutable参照として共有します。

## 12. Offline render／Stems

`OfflineRenderer`は48 kHz固定でchunked PCM-16 stereo WAVを書きます。Sequence timelineからvoiceを起動し、Pitch／Stretch、ADSR、LFO、PAD FX、Send、Master FXを処理します。最大96 voiceで、WAV RIFF 4 GiB上限を事前検査します。

Stems ZIP:

```text
Master.wav
Banks/Bank_A.wav
Banks/Bank_B.wav
Pads/Pad_A_01.wav
Pads/Pad_A_02.wav
manifest.txt
```

- Master: 全PAD、Send、Master FX込み
- Bank／PAD: PAD insert FX込み、Master Delay／Reverb／Compressor／Limiterなし
- 使用されていないBank／PADは出力しない
- working fileはcache directoryへ一時生成し、成功／失敗にかかわらず削除

## 13. Failure handling

- Oboe open／start failureはKotlinへexceptionとして返す
- Exclusive失敗時はSharedへfallback
- error callbackでnative error codeを保持し、UIへ通知
- command ring overflowはdrop countへ記録
- corrupt autosaveは削除し、clean projectで開始
- project export／stem exportの一時fileは`finally`で削除
- Playback Capture終了時はAudioRecord、MediaProjection、Foreground Serviceを解放

## 14. 今後の拡張ポイント

- disk-backed sample streaming／LRU cache
- higher-quality offline stretch／pitch engine
- per-step velocity、probability、ratchet、micro-timing
- automation lanesとparameter lock
- MIDI output／MPE
- Ableton Link
- multitrack audio recorder／mixer
- effect preset／routing graph
- project migration framework for schema 3+
