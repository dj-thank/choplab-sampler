# おとひろい / Earth Song — ChopLab

音を取り込み、好きな部分を切り出し、PADで鳴らしてビートを作る Android 10+ / Windows 向けサンプラーです。自分の音声を端末内で編集・保存でき、オリジナルの合成ドラムからも始められます。

現在は **0.18.0 を土台に再構築中**です。Macでも全制作機能を使う要件を追加し、機能ごとに検証しています（[ADR8](docs/adr/ADR-0008-mac-function-parity.md)）。現行版は「入れる → チョップ → ビート → 保存」の4工程。計画している「歌う」、AI作詞、統一音声エンジン、ミキサーなどは、受入が終わるまで現行機能として案内しません。実装・配布・確認の最新状況は [ROADMAP](docs/ROADMAP.md) にまとめています。

## 入手と使い方

[GitHub Releases](https://github.com/dj-thank/choplab-sampler/releases) で、対象OSと各版の説明・署名・チェックサムを確認してください。ソースのmain更新は新しい配布版の公開を意味しません。Windowsのapp-imageは、EXEと一緒に `app` / `runtime` フォルダーを保持して使います。開発起動とパッケージ作成は [デスクトップ手順](desktop/README.md) を参照してください。

1. 「入れる」でファイルや録音を選びます。まず触ってみるなら内蔵デモを使えます。
2. 「チョップ」で元の音を聴き、使いたい範囲をPADへ割り当てます。
3. 「ビート」でPADを鳴らし、ドラムやステップを並べます。
4. 「保存」で制作ファイル `.choplab` やWAVを書き出します。音源ライブラリの持ち出しには `.choplib` を使います。

録音はOSの許可を得た音声だけを扱います。オンライン取込は通信・提供元の利用条件に依存します。Spotifyの曲情報と、取得・検証が終わった再生可能音声は別の状態です。詳細は [製品契約](docs/PRODUCT.md) と [プライバシー方針](PRIVACY.md) を参照してください。

### 新しい編集画面を Mac で試す（おとひろい NEXT）

作り直し中の4工程の編集画面を、Apple Silicon（M1 以降）の Mac で試せます。正式な配布版ではなく、Apple の公証を受けていない試用版です。

1. GitHub にログインし、[Actions の「ChopLab Mac NEXT preview」](https://github.com/dj-thank/choplab-sampler/actions/workflows/mac-preview.yml) を開きます。main の最新の成功した実行（緑のチェック）を選びます。
2. ページ下の「Artifacts」から `choplab-mac-next-preview` をダウンロードします（作られてから7日間だけ置いてあります）。
3. ダウンロードしたものを開き、中の `ChopLab-mac-next-preview.zip` をダブルクリックすると `ChopLab NEXT.app` が出ます。「アプリケーション」フォルダーへ移します。
4. 初めて開くときは「開けません」と表示されます。「システム設定」→「プライバシーとセキュリティ」の下にある「このまま開く」を押し、もう一度開きます。
5. 声を録るときは、マイクの使用を許可してください。

WAV・FLAC・MP3・M4A（AAC/ALAC）・AAC・Ogg・Opus・AIFF、MP4・WebM内の音声を取り込めます。「音源ライブラリ」から既存Previewの素材を検索し、選んだ1つを原曲にできます。複数ファイルや`.choplib`の追加・音源セットの書き出しにも対応します。ライブラリへの追加だけでは編集中の曲を変更しません。今までのアプリで保存した制作ファイル（`.choplab`）を開くと、中の音声だけを取り出して新しい制作にします（1つ目の音声が原曲になり、音声は入りきる分だけ PAD に順に入ります。PAD の切り方や曲の並びは引き継ぎません。元のファイルは変わりません）。データは今までの Preview と別のフォルダーに保存します。Intel の Mac では動きません。

## 開発と文書

- [参加・ローカル起動](CONTRIBUTING.md) / [変更ルール](AGENTS.md)
- [PRODUCT](docs/PRODUCT.md) — できることと再構築の要件
- [ARCHITECTURE](docs/ARCHITECTURE.md) / [AUDIO](docs/AUDIO.md) — モジュール、保存、音の契約
- [DESIGN](docs/DESIGN.md) / [AI](docs/AI.md) — 画面・操作と任意のAI機能
- [TESTING](docs/TESTING.md) / [RELEASE](docs/RELEASE.md) — 検証・配布
- [ROADMAP](docs/ROADMAP.md) / [GLOSSARY](docs/GLOSSARY.md) — 進捗・用語
- [変更履歴](CHANGELOG.md) / [脆弱性報告](SECURITY.md) / [ライセンス](LICENSE) / [第三者表示](NOTICE.md)

旧文書・未統合Pro資料・iOS previewの履歴は [0.18.0保存点](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb) から参照できます。再構築ではAndroidとWindowsを維持し、Macでの制作にも対応します。

## English

Earth Song (internal project name: ChopLab) is an Android 10+ and Windows sampler. Import your audio, chop it onto pads, make a beat, and save or export it. The 0.18.0 baseline is being rebuilt in stages, with Mac feature parity now included in the requirements. See the [roadmap](docs/ROADMAP.md) for implemented scope, available builds and remaining verification; planned vocal and AI features are not yet a release promise.
