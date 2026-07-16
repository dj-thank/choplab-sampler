# Codexでの使い方

## 1. 作業フォルダーを開く

ZIPを展開し、リポジトリ直下で作業します。`AGENTS.md`と`.codex/config.toml`は、プロジェクトを信頼したときにCodexから読み込まれます。

## 2. 環境診断

macOS / Linux / WSL:

```bash
./scripts/doctor.sh
./scripts/bootstrap.sh
```

Windows PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap.ps1
```

Android SDKのパッケージも導入する場合:

```bash
./scripts/bootstrap.sh --install-sdk
```

```powershell
.\scripts\bootstrap.ps1 -InstallSdk
```

## 3. 対話モード

```bash
./scripts/codex-start.sh
```

または直接:

```bash
codex -C .
```

開始後に`/status`で作業ディレクトリ、モデル、権限を確認します。大規模統合では`/plan`を使い、`prompts/00_MASTER_PROMPT.md`を貼り付けます。

最小の開始文は`CODEX_PROMPT.txt`にあります。

## 4. 非対話モード

主プロンプトを一括実行:

```bash
./scripts/codex-run-master.sh
```

これは次と同等です。

```bash
codex exec -C . --ask-for-approval never --sandbox workspace-write - < prompts/00_MASTER_PROMPT.md
```

Windows:

```powershell
Get-Content .\prompts\00_MASTER_PROMPT.md -Raw |
  codex exec -C . --ask-for-approval never --sandbox workspace-write -
```

非対話実行はワークスペース内を書き換えます。Gitの初期コミットから差分を確認してください。

## 5. 段階実行

一括実行が大きすぎる場合は、次の順に投入します。

1. `prompts/01_AUDIT_AND_PLAN.md`
2. `prompts/02_BASELINE_AND_MODEL.md`
3. `prompts/03_NATIVE_OBOE.md`
4. `prompts/04_PERSISTENCE_UNDO_STEREO.md`
5. `prompts/05_DSP_MIDI_SONG_STEMS.md`
6. `prompts/06_UI_RELEASE_QA.md`
7. `prompts/07_FINAL_REVIEW.md`

同じセッションを続ける場合:

```bash
codex exec resume --last "ExecPlanを読み、未完了の次のマイルストーンから継続してください。"
```

## 6. サブエージェント

主プロンプトは次の専門役割を使えるようにしています。

- Android構造調査: `android_architect`
- Oboe/DSP: `audio_dsp_engineer`
- Gradle/NDK/CI: `build_engineer`
- 最終レビュー: `qa_reviewer`

調査やレビューは並列化できます。同じGradle、モデル、JNI、ViewModelファイルを複数エージェントに同時編集させないでください。

## 7. 検証

```bash
./scripts/verify.sh
```

実機が接続されている場合は、Codexへ機種・Androidバージョン・USB/BT MIDI機器・検証したい音声経路を伝え、端末固有テストを別マイルストーンにします。

## 8. 失敗時

Codexにはエラー全文、実行コマンド、変更直前のコミットを与えます。依存バージョンを推測で大量更新させず、まず最小の失敗タスクを再現させてください。

推奨プロンプト:

```text
現在の失敗だけを再現し、根本原因を特定してください。無関係な依存更新や広範なリファクタリングは行わず、最小修正を実装し、同じコマンドと関連回帰テストを実行してください。
```
