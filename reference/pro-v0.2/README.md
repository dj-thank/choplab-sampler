# ChopLab Android Pro 0.2.0

Android 10（API 29）以降向けの、サンプリング／チョップ／PAD演奏／ビート制作を一つにまとめたAndroidアプリです。音声ファイル、マイク、またはAndroid Playback Captureで録音可能な端末再生音を取り込み、波形範囲を選び、スライスをPADへ割り当て、PatternとSongを制作して、ステレオMasterまたはステムへ書き出せます。

この版では、初期MVPの`AudioTrack`再生をC++／Oboeエンジンへ置き換え、プロジェクト保存、Undo／Redo、ステレオ処理、独立Pitch／Time Stretch、ADSR、LFO、PAD／Master FX、MIDI、Songモード、ステム書き出しを追加しました。

> **製品上の位置づけ**  
> MPC系ハードウェア・サンプラーの制作フローを参考にした独自実装です。AKAI Professionalのロゴ、画面、ファームウェア、音源、プロジェクト形式、固有のトレードドレスは複製していません。

## 主な仕様

| 領域 | 実装内容 |
|---|---|
| 音源取り込み | SAFによる音声ファイル読込、48 kHzマイク録音、Playback Captureによる録音可能な端末再生音の取り込み |
| 波形／チョップ | ステレオ対応波形、S/E範囲、Zoom／Scroll、手動・4/8/16等分・Transient Chop、境界drag、zero-crossing snap |
| PAD | 4 BANK × 16 PAD、AUTO NEXT、One Shot／Gate、Reverse、Choke 1–8、Gain、Pan、Tone、Resonance |
| ネイティブ音声 | Oboe 1.10.0、AAudio／OpenSL ES自動選択、Exclusive優先＋Shared fallback、native sample rate、96 voice上限 |
| Pitch／Stretch | Pitch ±24 semitone、Time Stretch 0.25–4.0倍を独立制御する粒状Overlap-Add再生 |
| ADSR | PAD別Attack、Decay、Sustain、Release |
| LFO | Sine／Triangle／Square／Saw／Sample & Hold、Amp／Pan／Pitch／Filter、free-runまたはtempo sync |
| PAD FX | Drive、Bit Depth 4–16 bit、Sample-rate reduction 1–32、Delay／Reverb send |
| Master FX | Ping-pong Delay、Reverb、Compressor、Drive、Master Gain、soft clipping |
| Pattern | 16 Pattern、各16／32／64 step、BPM 40–240、Swing 50–75%、step入力、quantized live record |
| Song | Patternを最大128 sectionまで並べ、各sectionを1–64回repeatして連続再生／書き出し |
| MIDI | USB／Bluetooth／仮想MIDI、Note On/Off、Velocity、CC Learn、24 PPQN Clock、Start／Continue／Stop |
| Project | `.choplab`保存／復元、PCM WAVを内包、同一音源を重複保存しない、1.2秒debounceのautosave |
| Undo／Redo | 最大64状態、連続スライダー編集のcoalescing、音声配列を共有して履歴メモリを抑制 |
| Export | 48 kHz／16-bit／stereo Master WAV、Master・使用Bank・使用PADを含むステムZIP |

## 必要環境

- Android Studioの現行安定版
- JDK 17以上
- Android SDK Platform 36
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Android 10／API 29以上の実機またはエミュレーター

プロジェクトで固定している主なツールチェーンは次のとおりです。

- Android Gradle Plugin `9.3.0`
- Gradle `9.5.0`
- Kotlin／Compose Compiler plugin `2.3.21`
- Compose BOM `2026.06.00`
- Activity Compose `1.13.0`
- Lifecycle `2.10.0`
- Core KTX `1.17.0`
- Oboe `1.10.0`
- Java source/target `17`
- C++ `20`

## Android Studioで開く

1. 配布ZIPを展開します。
2. Android Studioで`ChopLabAndroid`フォルダーを開きます。
3. SDK ManagerからPlatform 36、NDK 29.0.14206865、CMake 3.22.1を導入します。
4. Gradle JDKを17以上へ設定し、Gradle Syncを実行します。
5. API 29以上の端末を接続して`app`を実行します。

コマンドライン検証:

```bash
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APKの標準出力先:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 基本ワークフロー

### 1. 音を取り込む

- **音声読込**: AndroidのMediaExtractor／MediaCodecで扱えるMP3、AAC、WAV等を選択します。
- **MIC**: マイク録音を開始し、停止するとステレオ対応波形画面へ読み込みます。
- **端末音**: 画面共有許可後、録音元アプリがPlayback Captureを許可した再生音を取り込みます。

端末音録音は、録音元アプリの設定、DRM、ユーザープロファイル等により無音または利用不可になる場合があります。録音・サンプリングする素材の権利と利用条件は利用者が確認してください。

### 2. 使用範囲とChopを決める

波形上のS/Eハンドルで開始・終了範囲を設定します。Zoomと表示位置を動かし、手動タップ、等分、Transient検出で境界を追加できます。境界drag時は近傍のzero crossingへsnapし、クリックを抑えます。

### 3. PADへ割り当てる

- **選択 → PAD**: 現在のsliceを選択PADへ割り当てます。
- **全SLICE → PAD**: 現在Bankへ順番に割り当てます。
- **AUTO NEXT**: 割り当て後、PADとsliceの両方を次へ進めます。
- PAD tapで発音、long pressで編集対象を選択します。

### 4. PAD音色を作る

PADごとにPitch、Stretch、Tone、Resonance、Gain、Pan、Reverse、Gate、Chokeを設定します。さらにADSR、LFO、Drive／Bit Crush／Sample-rate Reduction、Delay／Reverb Sendを設定できます。

**独立Time Stretch**は粒状Overlap-Add方式です。Pitchを変えても基本の出力長を維持し、Stretchを変えても基本の音程を維持します。極端な設定や打楽器以外ではgranular artifactが生じるため、原音に合わせて調整してください。

### 5. PatternとSongを作る

Patternは16／32／64 stepを選び、PADごとにstepを配置します。REC ARM中にPADを叩くと現在stepへquantizeされます。SongモードではPatternとrepeat回数を順に並べ、全体を連続再生または書き出します。

### 6. MIDIを使う

MIDI画面で入力デバイスを接続します。基準Noteから64 PADへchromaticに割り当て、Velocityを発音強度へ反映します。MIDI Learnでは選択PADのGain／Pan／Tone／Pitch／Stretch／Send、およびMasterのDelay／Reverb／GainをCCへ割り当てられます。Clock Syncを有効にすると24 PPQNクロックからBPMを追従し、Start／Continue／Stopへ応答します。

### 7. 保存・復元・Undo

- **SAVE PROJECT**で`.choplab`を作成します。
- **OPEN PROJECT**で音源、Chop、PAD、Pattern、Song、FX、MIDI設定を復元します。
- 編集後はアプリ内部へautosaveされます。
- Undo／Redoは最大64状態です。スライダー操作は近接した変更をまとめ、履歴の過剰増加を防ぎます。

`.choplab`はZIPコンテナで、`project.json`と`audio/<id>.wav`を格納します。同一`PcmAudio.id`を参照する複数PADは一つのWAVを共有します。読込時にはmanifest容量、音源数、1音源のframe数、総PCM量、重複IDを検査します。

### 8. Master／Stemsを書き出す

- **MASTER WAV**: 48 kHz、PCM 16-bit、stereo。PAD FX、Send、Master FXを含みます。
- **STEMS ZIP**: `Master.wav`、使用Bank、使用PAD、`manifest.txt`を含みます。

Bank／PAD stemは、合計時の二重処理を避けるためPAD insert FXを反映し、Master Delay／Reverb／Compressor／Limiterをbypassします。Master stemには全処理を反映します。

## 音声アーキテクチャ

リアルタイム処理は`SamplerCore.cpp`で行います。Kotlin UIからJNIへ送るsample／PAD／sequence設定はcontrol threadで処理し、Oboe data callbackではファイルI/O、lock、heap allocationを行いません。発音commandはlock-free ring bufferへ積み、sequenceはimmutable snapshotとして差し替え、callback epoch経過後にcontrol側で回収します。

主要コンポーネント:

- `AudioDecoder`: MediaExtractor／MediaCodecからmonoまたはstereo PCM-16へ変換
- `PlaybackCaptureService`: MediaProjection＋AudioPlaybackCapture＋Foreground Service
- `MicrophoneRecorder`: PCM-16 WAV録音
- `SamplerCore`: Oboe callback、voice、Pitch／Stretch、ADSR、LFO、filter、FX、sequence
- `SamplerEngine`: Kotlin／JNI境界、sample cache、project同期
- `ProjectArchive`: `.choplab`の決定的ZIP保存／安全な復元
- `UndoManager`: bounded snapshot history
- `SequenceCompiler`: Pattern／Songをstep timelineへcompile
- `OfflineRenderer`: stereo Master／Bank／PAD stemのchunked render
- `MidiController`: Android MIDI device接続、running status、Clock／CC／Note解析

詳細は[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、機能対応は[`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)、検証結果は[`docs/VALIDATION.md`](docs/VALIDATION.md)を参照してください。

## 現在の制約

- 音源はPCMへ展開してメモリへ保持します。1音源30,000,000 frame、最大65音源、総80,000,000 interleaved sampleの防御上限があります。大量の長尺音源にはdisk streaming／sample cache層が必要です。
- Time Stretchは高品質offline phase vocoderではなく、リアルタイム向けgranular overlap-addです。
- MIDI出力、MPE、Ableton Link、automation lane、per-step velocity／micro-timing、audio track録音、VST/AU plugin hostは含みません。
- Output latencyはAndroid端末、Audio HAL、Bluetooth経路、thermal状態に依存します。Bluetoothは有線／本体speakerより遅延が大きくなります。
- Playback Captureでは、録音元アプリがcaptureを禁止した音やDRM保護音源を取り込めません。
- 他社製品を1対1で再現するものではなく、独自UI・独自project formatです。

## 検証スクリプト

Android SDKを使わないDSP回帰検査:

```bash
./scripts/validate_project.sh
```

これは、純粋Kotlinのstereo WAV／Song／Undo／offline render／stems／transient検査、C++20 native DSPの厳格warning buildと発音検査、Android XML、Gradle Wrapper checksumを確認します。

## ライセンスと商標

アプリ固有ソースは[`LICENSE`](../../LICENSE)のMIT Licenseです。OboeはApache License 2.0です。Gradle Wrapperと各依存ライブラリはそれぞれのライセンスに従います。詳細は[`NOTICE.md`](../../NOTICE.md)を参照してください。

AKAI、AKAI Professional、MPCは各権利者の商標です。本プロジェクトはAKAI Professionalによる承認、提携、後援を受けていません。
