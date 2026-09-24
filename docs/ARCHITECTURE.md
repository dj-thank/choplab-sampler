# アーキテクチャ

現行0.18.0と再構築の設計を分けます。採用・移植・廃止の進捗は [ROADMAP](ROADMAP.md) が管理します。

## 現行の境界

| Module | 0.18.0の責務 |
|---|---|
| `shared` | Compose画面、sampler/domain、ProductionCommand reducer、ProductionSession、共有DSP primitive |
| `jvm-core` | Android/Windowsのarchive・autosave・WAV/export・library・online取込・分離 |
| `app` | Android ViewModel、AudioTrack、MediaCodec、AudioRecord、SAF、Playback Capture |
| `desktop` | Windows controller、Java Sound、native dialog、provider UI、WASAPI endpoint probe |

編集の計画→必要な副作用→commit/cancelを維持します。PROJECT/SESSION/NONEを分け、失敗した停止や読込を編集成功として公開しません。2つのcontrollerとreal-time/offline voice実装は残っており、共通primitiveを使うことだけでは統一engine完成になりません。

`PcmAudio`はsample rateと1/2channelのPCM16を保持し、PADは同じaudioの範囲を参照します。フレーム範囲は `[start, end)`。波形用mono投影は音声channelを置き換えません。schema7 writer/1–7 readerは現行の保存境界です。schema8/9の後続ローカル実験を基準mainへ読み込み済みと扱いません。

## 目標の6 module（段階2から追加）

| Module | 責務と依存 |
|---|---|
| `engine` | 純Kotlin DSP、EngineCore、voice/sequence/mix/scratch。標準library以外のruntime依存なしを目標 |
| `core` | engine＋coroutines/serialization。Project、Intent/Reducer/EditSession、Studio、ports、取込・歌詞timing |
| `ui` | 共通Compose画面・部品・ja/en resources。coreのstateを描画しintentを送る |
| `jvm` | core portsの保存、asset、network、AI、ONNX、WAV実装 |
| `app` | AndroidHostがcore/ui/jvmとOS driverを組み立てる |
| `desktop` | DesktopHostがcore/ui/jvmとWindows driverを組み立てる |

依存は `ui → core → engine` と `jvm → core`。OS参照をengineへ入れず、DI frameworkは導入せずhostで組み立てます。旧moduleは移植が合格するまで残します。KMPの対象はAndroid/JVMであり、Native互換を同時に証明しません。

## 状態・時刻・仕事の所有

`Project`は不変の保存文書。`Intent → Reducer → EditSession` がrevision、結合キー、最大100段のUndoを管理します。Studioが唯一の振る舞いの持ち主となり、文書・選択・job・playback stateを分離します。coreは文言でなくtyped `Notice`を出します。

- UI/main: intent受付、状態と必要な表示の更新。表示の時計はLiveReadoutを描画へ橋渡しする。
- Studio/control: producerを一つへ集約。命令に絶対 `effectiveFrame` と順序IDを付ける。
- render: ControlRingを読み、命令/Program変更のframeでblockを分割。EventRingは適用frameを返す。late/overflow/Stop優先/取りこぼし/再同期を定義する。
- worker: decode、prefetch、network、推論、録音書込み、保存、offline render。job IDとproject revisionでcancel後やproject交換後の応答を拒否する。

音声クロックが主時計です。tick（960/四分音符）とframeの変換は端数を保持。LiveReadoutは整合した小さなsnapshotで、原子変数を読むだけで再描画が発生すると仮定しません。render内にI/O・ロック・確保を持ち込まず、実際のJVM/ARTで測定します。

## 音声資産と保存（schema10の受入契約）

- `.choplab`: 先頭 `project.json`、`assets/<sha256>.<許可拡張子>`。元圧縮素材、編集/録音/生成float32 WAV、再生成可能PCM cacheを区別する。manifestから必須/派生/再生成可能を判断し、含まれるbytesをhash検証する。
- AssetStore: 短いPADはresident、長い伴奏/歌はprefetch。全音声を128PAD分展開しない。cache missの無音/停止通知、再読込、RAM/disk上限をengine spike前に決める。
- Autosave: 資産をtempへ書込み→flush/検証→atomic publish→参照する小さなdocumentを3世代で確定。文書だけが先に残らない順序にする。
- Asset lifetime: Undo/Redo、autosave全世代、実行中jobが参照する資産を保持。GCはrender外で、参照保護・中断復旧の検証後に導入する。
- Import validation: 未知schema、path traversal、case衝突、重複ID/entry、不整合hash、ZIP bomb、過大size/frame、非有限値を拒否。拡張子・metadataだけを信頼しない。
- Legacy salvage: schema1–7をまず実fixtureで音声救出。schema8/9は別履歴のfixtureが揃った場合だけ追加し、対応を自称しない。hash・frame長・channelを照合し、元を保持。容量不足・cancel・再試行は再実行可能にする。

PreviewはAndroid package/authority/deep link、Windows設定/autosave/library/cache/lock領域を分離します。正式な同一データ領域切替でのみ旧autosaveを `legacy/` へ退避し、Previewから旧アプリの私有データへ直接入れるとは仮定しません。

## 移植と判断

ProductionCommand/Sessionはcore edit、モデルはcore doc、mailbox/voice ownership/DSPはengine、zero-crossing/合成drumは共通実装へ移します。archiveの保護機能と振る舞いテストは意味を保って移植します。新旧の小さい制作経路が通ってから旧codeを削除します。

JDK21・Java/Kotlin target17、CMP更新、NewPipe、YIN/PSOLA、WASAPI等は段階ごとの候補です。versionの存在だけでこのrepositoryの互換性を保証しません。pure Kotlin engineが計測条件を満たさなければ、全面UI配線へ進む前にADRで再設計します。C++/Oboeへ自動的に切り替えません。

歴史的判断は [ADR1](adr/ADR-0001-production-command-effect-seam.md)、[ADR2](adr/ADR-0002-production-session-transactions.md)、[ADR3](adr/ADR-0003-audio-parity-primitives.md)、[ADR4](adr/ADR-0004-pattern-master-parity-gate.md)、[ADR5](adr/ADR-0005-guided-first-screen-flow.md) に保持します。旧4画面など置換する契約は新要件の受入まで現行に適用し、[ADR6](adr/ADR-0006-android-windows-focus.md) / [ADR7](adr/ADR-0007-concise-development-governance.md) とROADMAPで変更を明示します。
