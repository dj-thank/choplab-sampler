# ChopLab — おとひろい sampler

[![Android verification](https://github.com/dj-thank/choplab-sampler/actions/workflows/android.yml/badge.svg)](https://github.com/dj-thank/choplab-sampler/actions/workflows/android.yml)
[![iOS verification](https://github.com/dj-thank/choplab-sampler/actions/workflows/ios.yml/badge.svg)](https://github.com/dj-thank/choplab-sampler/actions/workflows/ios.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Android 10以降と iOS 16以降を対象にしたモバイル・サンプラー **おとひろい（ChopLab）** のオープンソース開発リポジトリです。

現在は、Android側に曲を流しながら16 PADを叩いてその瞬間を刻むライブチョップ、1/2ch音声の取り込みと左右を保つ再生、PAD別トーン、4 BANK、チョップ済み音声全体の連続ループ、実波形上のループ再生位置、別PAD用の4つ打ち・8分・16分配置プリセット、A/B二つの16-step variationを並べる4小節Song、mono/stereo WAV書き出し、WAV音声を内包する`.choplab`制作保存、revision安全な三世代自動保存、40操作のUndo/Redoを備えたMVPがあります。iOS側にはSwiftUI + AVFoundationで音源取込、16 PAD、範囲編集、録音、停止を備えたpreviewがあります。GitHub Releasesには、タグからAndroid debug APKとiOS Simulator app zipを添付します。Windows app-imageは同じtag workflowで検証しますが、公開Release assetには含めません。

画面は、クリーム色のデッキ、オレンジのサンプリング表示、緑の波形、4 × 4 PADを中心とするオリジナルの「おとひろい」UIです。縦横どちらでも画面スクロールを使わず、`入れる / チョップ / ビート / 保存` の固定4工程から取込、波形チョップ、PAD演奏、16-step制作、WAV書き出しへ直接移動できます。

操作の中心となる16 PADと`ALL STOP`は制作画面で見失わない位置に保ちます。録音中はヘッダーを録音種別ごとの`MIC / DEVICE / VOICE STOP`へ切り替え、準備・録音・停止保存を一つのセッションとして表示します。録音を汚すPAD、元曲、ループ、スクラッチ、トランスポートの追加再生と競合録音を遮断し、Layer Studio内にもSTOPを残します。元曲、範囲試聴、Beat loop、シーケンサー、scratchは同時に主再生を所有しません。WindowsでBeat loopを始めるときは、選択ループと対象VOICEを全て開始できた後だけ旧再生と制作履歴を切り替え、出力準備に失敗した場合は現在の再生とUndo/Redoを保持します。Androidでは選択ループと対象VOICEを一つのrealtime命令として受理できた時だけLOOP状態と制作履歴を公開し、engine停止や操作集中時に無音のループを成功表示しません。通常PADはその後からドラム等を意図的に重ねられます。`チョップ`では波形タップで頭出しし、空PADで追加、音入りPADで現在素材へ上書き、長押しで開始・終了位置を微調整できます。`ビート`では`PADを選ぶ → 選択音をループ／並べる → 足す／擦る`の順を常時表示し、チョップ範囲の末尾から先頭へ戻る再生位置を実波形上で確認できます。4つ打ち・8分・16分はループとは別の`配置プリセット`として細かい調整に残し、別BANKのドラムを重ねられます。曲と選択中PADのKEY/TONE/LEVELは通常画面から直接変更できます。新しい音源への入れ替えや新しい制作の開始は二度押し確認を挟み、押下、選択、再生、録音の状態を文字と色の両方で示します。

## まず使う

### Android

1. [Releases](https://github.com/dj-thank/choplab-sampler/releases)から、Androidの`android-debug.apk`またはiOSの`ios-simulator.app.zip`と対応するSHA-256をダウンロードします。
2. SHA-256を確認してから、Androidの設定で使用するブラウザまたはファイルアプリに「不明なアプリのインストール」を一時的に許可します。
3. APKを開いてインストールし、音声録音などの権限を必要な範囲で許可します。

Androidの素材pickerは、providerが音声として公開するファイルだけを表示します。MP3、AAC/M4A、FLAC、Ogg/Vorbis/Opus、WAVなどは、Android platformと端末decoderが対応するcontainer/形式の範囲で読み込み、選択後も実際のaudio trackと長さ・メモリ上限を検証します。動画はpickerの対象にしません。

### iOS

Releaseの`ChopLab-*-ios-simulator.app.zip`は、Apple署名を使わない **iOS Simulator用プレビュー** です。macOSとXcodeがある環境で展開し、起動済みSimulatorへ `xcrun simctl install booted ChopLab.app` でインストールします。iPhone/iPadへ直接インストールできるIPAではありません。実機版には利用者自身のApple Developer team、provisioning profile、署名が必要です。

実機iPhoneで試す場合は、macOS/Xcodeで`ios/project.yml`からプロジェクトを生成し、Apple Developer teamをXcodeのSigning & Capabilitiesへ設定して接続したiPhoneを実行先に選びます。署名鍵やprovisioning profileはリポジトリやGitHubへ置きません。

### Windows

Windows app-imageはローカルの`:desktop:packageWindows`とGitHub Actionsで構築・検証しますが、現在の公開`v*` Releaseには添付しません。Actionsの短期verification artifactを使うか、ソースからbuildした`desktop/build/windows-app-image/ChopLab`をそのまま使用します。これはJDK runtimeを含むapp-imageで、単体EXEだけを取り出して実行する配布形式ではなく、コード署名済みinstallerでもありません。

Windows版の素材取込は、実装済みdecoderに合わせてWAVだけを表示します。ファイルpickerの「すべてのファイル」は無効です。MP3等を選べるように見せて後から失敗させることはせず、Windows向けMP3 decoderを導入する場合は別の権利・供給網・decode上限レビューを通します。

リリースAPKは現時点ではGitHub Actionsのデバッグ署名による開発プレビューです。端末によっては、別のビルドへ更新する前に既存版のアンインストールが必要です。アンインストール前に「完成」から`.choplab`制作ファイルを書き出してください。個人データを扱う前に、コードと権限要求を確認してください。

録音、保存、権限、端末内データの扱いは[`PRIVACY.md`](PRIVACY.md)、内蔵ドラム音と第三者表示は[`NOTICE`](NOTICE)に記載しています。内蔵5キットはダウンロード音源ではなく、このリポジトリのコードが生成するオリジナルの決定論的PCMです。ユーザー音源、認証情報、署名鍵、provisioning profileはソースにもReleaseにも含めません。

## 現在の範囲

このリポジトリは、モバイル本体・デスクトッププレビュー・参照資料の境界を明確に分けています。

- `app/`: 現在のビルド基準線。AudioTrackベースのMVP実装です。
- `ios/`: SwiftUI + AVFoundationのiOS 16向けプレビューMVP。音源取込、16 PAD、範囲編集、録音、停止を実装し、署名不要のSimulator previewとして検証します。
- `desktop/`: 元Androidデックの色・工程・PAD vocabularyを踏襲したWindows EXE previewです。ローカルWAV、Spotify PKCE metadata/control、A/B 16-stepと4小節Songまでを対象にし、署名済みinstallerやSpotify音声取込は対象外です。
- `reference/pro-v0.2/`: Oboe、独立タイムストレッチ、ADSR、LFO、FX、MIDI、pan/mixer、任意数・可変repeatの高度なSong、ステム書き出しの未統合参照コードと設計資料です。

`reference/pro-v0.2/` は完全なAndroid Studioプロジェクトではなく、そのままではコンパイルできません。Codexには、参照コードを盲目的にコピーさせず、MVPへ段階的に統合し、各段階でビルドとテストを通すよう指示しています。

実装済みのMVP範囲と未実装のPro範囲は[`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)に記録しています。MVPの制作保存・自動復旧・Undo/Redo、bounded A/B 4小節Song、Android/Windowsの1/2ch channel identityはローカル実装済みです。物理端末の左右出力と音質は別gateであり、MIDI、pan/mixer、独立タイムストレッチ、ネイティブOboeエンジン、stems、任意数・可変repeatのSong editorは完成扱いにしていません。

## 最短開始手順

### macOS / Linux / WSL

```bash
cd ChopLab-Codex-Workspace
./scripts/doctor.sh
./scripts/bootstrap.sh
./scripts/codex-start.sh
```

Codexが開いたら、次のファイルを貼り付けます。

```text
prompts/00_MASTER_PROMPT.md
```

一括で非対話実行する場合:

```bash
./scripts/codex-run-master.sh
```

### Windows PowerShell

```powershell
Set-Location ChopLab-Codex-Workspace
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\codex-start.ps1
```

非対話実行:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\codex-run-master.ps1
```

## まず確認するファイル

| ファイル | 用途 |
|---|---|
| `AGENTS.md` | Codexが毎回読む恒久ルール |
| `.codex/config.toml` | このプロジェクト用のCodex設定 |
| `.codex/agents/` | Android、音声DSP、ビルド、QAの専門サブエージェント |
| `.agents/skills/choplab-android/SKILL.md` | ChopLab専用の反復可能な開発ワークフロー |
| `.agent/PLANS.md` | 長時間作業用ExecPlanの規約 |
| `prompts/00_MASTER_PROMPT.md` | 全機能を完成へ進める主プロンプト |
| `prompts/01_...07_*.md` | 段階実行用プロンプト |
| `docs/PROJECT_STATE.md` | 現時点の事実と未実装範囲 |
| `docs/PREPARATION_VALIDATION.md` | このZIP作成時に実行できた検証と未検証事項 |
| `docs/PRO_REFERENCE_GAPS.md` | Pro参照コードに不足している要素 |
| `docs/DEFINITION_OF_DONE.md` | 完了判定 |

## 開発環境

推奨構成:

- JDK 17
- Android SDK Platform 36
- Android Build Tools 36.0.0
- Android NDK `29.0.14206865`
- CMake `3.22.1`
- Android Studio現行安定版
- macOS + Xcode 15.4以上 + XcodeGen（iOS Simulator previewの生成と検証）
- Codex CLIまたはChatGPTデスクトップアプリのCodex

MVPの固定ツールチェーン:

- Android Gradle Plugin `9.3.0`
- Gradle `9.5.0`
- Kotlin / Compose Compiler plugin `2.3.21`
- Compose BOM `2026.06.00`
- minSdk 29 / targetSdk 36

`local.properties`は配布していません。`ANDROID_HOME`または`ANDROID_SDK_ROOT`が設定されていれば、`scripts/bootstrap.sh`または`bootstrap.ps1`が生成します。

## 検証

Android SDKなしで実行できる検査:

```bash
./scripts/validate_project.sh
```

Android SDK込みの標準検査:

```bash
./scripts/verify.sh
```

個別コマンド:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

最終APKのpackage/version、permission、debuggable、exported component、16 KiB alignment、署名状態をread-backする場合:

```bash
python scripts/verify_android_release.py \
  --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --version 0.17.1 \
  --version-code 28
```

manifest検査はSDKに`apkanalyzer`があればそれを優先し、未導入ならbuild-toolsの`aapt2`へfail-closedでfallbackします。署名必須の配布候補では`--require-signed`と、CIのsecretから渡す`--expected-cert-sha256`を併用します。certificate値や署名鍵をコマンド履歴・文書・リポジトリへ書きません。

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

iOS Simulator preview:

```bash
xcodegen generate --spec project.yml
bash scripts/build-ios-simulator.sh
```

GitHub Actionsは、Android、iOS Simulator、Windows app-imageのテスト、public-surface scan、SHA-256作成を行います。`v*`タグの公開Releaseへ添付するのはAndroid debug APKとiOS Simulator previewだけで、stable-signed Android release candidateとWindows app-imageは非公開verification artifact/stepに留めます。署名済みiOS実機IPA、App Store公開、実機音声、人間評価はこの公開previewの完了条件に含めません。

## Codex運用の基本

1. 最初に`AGENTS.md`、`docs/PROJECT_STATE.md`、`docs/PRO_REFERENCE_GAPS.md`を読ませます。
2. 大規模変更では`plans/active/`にExecPlanを作らせます。
3. 参照コードを一括コピーせず、コンパイル可能な小さい単位で統合させます。
4. 読み取り中心の調査はサブエージェントへ並列委譲し、同じファイルへの書き込みは直列化させます。
5. 各マイルストーンでテスト、Lint、APKビルド、差分レビューを行わせます。
6. 実機でしか確認できない項目は、未検証として明記させます。

詳細は[`docs/CODEX_USAGE_JA.md`](docs/CODEX_USAGE_JA.md)を参照してください。

## 重要な制約

- AndroidのPlayback Captureは、録音元アプリが許可した音声のみ取得できます。
- DRMや利用規約を回避する実装は行いません。
- AKAI ProfessionalやMPCのロゴ、固有画面、ファームウェア、プロジェクト形式、トレードドレスを複製しません。
- オーディオコールバック内でロック、ファイルI/O、ヒープ確保、JNIの重い処理を行いません。
- 「ビルド成功」「実機確認済み」などの主張は、実際のログまたは検証結果がある場合に限ります。

## 元資料

- 元MVP説明: `docs/MVP_README_ORIGINAL.md`
- 元MVP設計: `docs/ARCHITECTURE.md`
- Pro構想: `reference/pro-v0.2/README.md`
- 元配布ZIP: `original-archives/`

## ライセンス

アプリ固有コードは[`MIT License`](LICENSE)です。依存ライブラリ、Gradle Wrapper、Oboeなどは各ライセンスに従います。追加表示は[`NOTICE`](NOTICE)を参照してください。AKAI、AKAI Professional、MPCは各権利者の商標です。

## コントリビューション

提案・バグ報告・プルリクエストを歓迎します。詳しくは[`CONTRIBUTING.md`](CONTRIBUTING.md)を参照してください。音声データや認証情報、`local.properties`、署名鍵、生成APKはコミットしないでください。
