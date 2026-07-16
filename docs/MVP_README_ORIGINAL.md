# ChopLab Android

Android 10以降で動作する、MPC系ワークフローを意識したモバイル・サンプラーのMVPです。

音声ファイル、マイク、またはAndroidのPlayback Captureで許可された端末再生音を取り込み、波形上で使用範囲を決め、手動／等分／トランジェント検出でチョップし、境界を近傍のゼロクロスへ自動スナップして、16パッドへ割り当ててビートを組み立てられます。

> **位置づけ**  
> AKAI/MPC系サンプラーの核となる制作フローを、独自の名称・画面構成・実装で再構成した試作です。AKAI Professionalのロゴ、製品画像、画面素材、固有のトレードドレスは含みません。

## 実装済み機能

| 領域 | 内容 |
|---|---|
| 音源取り込み | Storage Access Frameworkによる音声ファイル読込、マイク録音、端末再生音のPlayback Capture |
| 長尺編集 | 波形ズーム、表示位置スクロール、開始／終了ハンドル、最大10分のデコード制限 |
| チョップ | 波形タップによる手動追加、4/8/16等分、トランジェント自動検出、ゼロクロス自動スナップ、境界ドラッグ微調整、境界削除 |
| PAD | 4 BANK × 16 PAD、単一スライス割当、全スライス連続割当、AUTO NEXTでPADとスライスを自動前進 |
| PAD編集 | PITCH ±24 semitone、TONE/Low-pass、GAIN、REVERSE、ONE SHOT/GATE、CHOKE GROUP 1–4 |
| 再生エンジン | AudioTrack低遅延モード、32 voice、線形補間リサンプリング、クリック抑制フェード、ソフトリミッター |
| ビート制作 | 16-step sequencer、BPM 40–240、Swing 50–75%、ステップRECクオンタイズ |
| 書き出し | 4小節をモノラルPCM-16 WAVへオフラインレンダリングして保存 |

## 必要環境

- Android Studio（JDK 17を使用）
- Android SDK Platform 36
- Android 10 / API 29以上の実機またはエミュレーター
- 端末音声録音の確認には実機を推奨

プロジェクトは以下のツールチェーンで固定しています。

- Android Gradle Plugin 9.3.0
- Gradle 9.5.0
- Kotlin / Compose Compiler plugin 2.3.21
- Compose BOM 2026.06.00
- Java 17

## Android Studioで開く

1. ZIPを展開します。
2. Android Studioで展開先の `ChopLabAndroid` フォルダーを開きます。
3. SDK Managerで Android SDK Platform 36 を導入します。
4. Gradle JDKを17に設定し、Gradle Syncを実行します。
5. API 29以上の端末を接続して `app` を実行します。

コマンドラインでビルドする場合は、Android SDK設定後に次を実行します。

```bash
./gradlew :app:assembleDebug
```

生成先は通常 `app/build/outputs/apk/debug/app-debug.apk` です。

## 基本操作

### 1. 音を取り込む

- **音声読込**: MP3、AAC、WAVなど、端末のMediaCodecがデコードできる音声を選択します。
- **マイク録音**: 録音開始後、停止すると自動的に波形へ読み込みます。
- **端末音録音**: 画面共有の許可画面を通して、録音を許可している別アプリの再生音を録音します。

端末音録音はAndroid 10以降のPlayback Captureを使用します。録音元アプリがキャプチャを禁止している音、DRM保護された配信音源、異なるユーザープロファイルの音などは取り込めません。

### 2. 長い音声から使用範囲を決める

波形上の **S** と **E** をドラッグして、サンプリングに使う開始点と終了点を設定します。ズームと表示位置スライダーで細部まで移動できます。

### 3. チョップする

- **手動CHOP**をONにして波形をタップ
- **4/8/16分割**で均等チョップ
- **TRANSIENT**で立ち上がりを自動検出
- 番号付き境界を横へドラッグして微調整（近傍のゼロクロスへ自動スナップ）
- 手動CHOPをOFFにして波形をタップすると、割り当て対象のスライスを選択

### 4. PADへ割り当てる

- **選択 → PAD**で現在スライスを選択PADへ割り当てます。
- **全SLICE → PAD**で現在BANKへ連続割り当てします。
- **AUTO NEXT**がONの場合、単一割り当て後にPADと選択スライスが次へ進みます。
- PADはタップで発音、長押しで編集対象を選択します。

### 5. ビートを作る

選択PADの16ステップをON/OFFし、BPMとSwingを調整します。RECをARMして再生中にPADを叩くと、現在ステップへクオンタイズして記録します。

### 6. WAVへ書き出す

シーケンサーにステップを配置し、**4小節をWAV書き出し**を押して保存先を選びます。PADごとのPitch、Tone、Gain、Reverse、ChokeとSwingが反映されます。

## 端末音声録音に関する重要事項

Playback Captureには次の条件があります。

- Android 10 / API 29以上
- `RECORD_AUDIO` 権限
- ユーザーによるMediaProjection（画面共有）許可
- 録音元と録音側が同じAndroidユーザープロファイル
- 録音元アプリがAudio Playback Captureを許可していること

参考:

- Android Playback Capture: https://developer.android.com/media/platform/av-capture
- Media projection foreground service: https://developer.android.com/media/grow/media-projection

録音・サンプリングする音源について、著作権、利用規約、配信サービスの規約、地域の法令を確認してください。

## アーキテクチャ

詳細は [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) を参照してください。

中心コンポーネントは次のとおりです。

- `AudioDecoder`: MediaExtractor/MediaCodecで音声をPCM-16モノラルへデコード
- `PlaybackCaptureService`: Foreground Service + MediaProjection + AudioRecord
- `MicrophoneRecorder`: マイクを48 kHz PCM-16 WAVへ録音
- `SamplerViewModel`: 波形範囲、スライス、PAD、シーケンサー状態を管理
- `SamplerEngine`: リアルタイム再生、Pitch、Tone、Choke、シーケンス
- `PatternRenderer`: 4小節のオフラインWAVレンダリング
- `WaveformEditor`: 波形、ズーム、範囲・チョップ境界のドラッグ操作

## 現在のMVP制限

この版は実際に制作フローを試せる機能実装版ですが、商用のプロ用サンプラーへ仕上げるには追加開発が必要です。

- AudioTrack実装のため、最小レイテンシは端末依存です。より厳密な低遅延化にはC++/OboeまたはAAudioへの移行と実機別チューニングが必要です。
- タイムストレッチとピッチを独立させるアルゴリズムは未実装です。現在のPitchは再生速度も変化します。
- 内部サンプルはモノラルです。入力がステレオの場合はデコード時にモノラルへダウンミックスします。
- プロジェクト保存／復元、オートセーブ、Undo/Redo、MIDI、USB MIDI、Ableton Link、マルチトラックSong/Arrangement、ADSR、LFO、Send FX、EQ、コンプレッサー、ステム書き出しは未実装です。
- 長尺音源はメモリへ展開します。1音源は最大10分に制限しています。多数の異なる長尺音源をPADへ保持する用途には、ファイルストリーミングとサンプルキャッシュが必要です。
- 他社製品のファームウェア、プロジェクト形式、名称、画面を1対1で複製するものではありません。

低遅延Android音声の参考:

- Android audio latency: https://developer.android.com/ndk/guides/audio/audio-latency
- Oboe: https://github.com/google/oboe

## オフライン検証スクリプト

Android SDKを使わない純粋ロジックとXML／Wrapper検査は、Kotlin compilerとPython 3がある環境で次を実行できます。

```bash
./scripts/validate_project.sh
```

## 検証状況

この配布物では、Android非依存の音声処理部分についてローカルKotlinコンパイルとスモークテストを実施しています。

- トランジェント検出
- スライス境界生成
- WAVヘッダーとPCMサイズ更新
- Pitch/Tone/Reverse/Choke/Swingを含むパターンレンダリング
- AndroidManifestとVector DrawableのXML構文
- 全main/test Kotlinコードの軽量APIスタブによるオフライン型検査
- Gradle Wrapper JARのSHA-256照合

本ファイルを生成した実行環境にはAndroid SDKとMaven/Gradle配布物への通常ネットワークアクセスがないため、`assembleDebug`、エミュレーター起動、実機レイテンシ測定までは実施していません。詳細は [`docs/VALIDATION.md`](VALIDATION.md) に記録しています。

## ライセンスと商標

ソースコードは [`LICENSE`](../LICENSE) のMIT Licenseで提供します。Gradle WrapperはGradleプロジェクトのライセンスに従います。

AKAI、AKAI Professional、MPCは各権利者の商標です。本プロジェクトはそれらの権利者による承認・提携・後援を受けたものではありません。
