# ChopLab — Android sampler

[![Android verification](https://github.com/dj-thank/choplab-sampler/actions/workflows/android.yml/badge.svg)](https://github.com/dj-thank/choplab-sampler/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Android 10以降で動くモバイル・サンプラー **ChopLab** のオープンソース開発リポジトリです。

現在は、音声の取り込み・チョップ・PAD割当・16ステップシーケンス・WAV書き出しを備えたMVPと、次段階へ移行するためのステレオ対応ドメイン基盤を公開しています。GitHub Releasesには、タグから自動生成する開発プレビューAPKを添付します。

初回タグReleaseが作成されるまではReleasesページが空の場合があります。その場合は、GitHub Actionsの`Android verification`実行に添付されたAPK artifactを開発用に取得できます。

## まず使う

1. [Releases](https://github.com/dj-thank/choplab-sampler/releases)から最新の`ChopLab-*-debug.apk`をAndroid端末へダウンロードします。
2. Androidの設定で、使用するブラウザまたはファイルアプリに「不明なアプリのインストール」を一時的に許可します。
3. APKを開いてインストールし、音声録音などの権限を必要な範囲で許可します。

リリースAPKは現時点ではGitHub Actionsのデバッグ署名による開発プレビューです。端末によっては、別のビルドへ更新する前に既存版のアンインストールが必要です。個人データを扱う前に、コードと権限要求を確認してください。

## 現在の範囲

このリポジトリは、次の二層を明確に分けています。

- `app/`: 現在のビルド基準線。AudioTrackベースのMVP実装です。
- `reference/pro-v0.2/`: Oboe、保存、ステレオ、独立タイムストレッチ、ADSR、LFO、FX、MIDI、Song、ステム書き出しの未統合参照コードと設計資料です。

`reference/pro-v0.2/` は完全なAndroid Studioプロジェクトではなく、そのままではコンパイルできません。Codexには、参照コードを盲目的にコピーさせず、MVPへ段階的に統合し、各段階でビルドとテストを通すよう指示しています。

実装済みのMVP範囲と未実装のPro範囲は[`docs/FEATURE_MATRIX.md`](docs/FEATURE_MATRIX.md)に記録しています。現時点で、プロジェクト保存、MIDI、独立タイムストレッチ、ネイティブOboeエンジン、ステレオ再生は完成扱いにしていません。

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

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

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

アプリ固有コードはMIT Licenseです。依存ライブラリ、Gradle Wrapper、Oboeなどは各ライセンスに従います。AKAI、AKAI Professional、MPCは各権利者の商標です。

## コントリビューション

提案・バグ報告・プルリクエストを歓迎します。詳しくは[`CONTRIBUTING.md`](CONTRIBUTING.md)を参照してください。音声データや認証情報、`local.properties`、署名鍵、生成APKはコミットしないでください。
