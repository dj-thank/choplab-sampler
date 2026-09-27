# ChopLab Desktop — Windows / Mac

現行0.18.0は `shared` のCompose画面と `jvm-core` の保存/取込/分離処理を使い、共通desktop controllerがJava Sound、ファイルdialog、録音、provider UIをつなぎます。再構築の計画と実装範囲は [ROADMAP](../docs/ROADMAP.md) が正本です。

## 起動・package

repository rootから、checkoutに合うJDKで実行します。

```powershell
.\gradlew.bat :desktop:test
.\gradlew.bat :desktop:run
.\gradlew.bat :desktop:packageWindows
```

app-imageは `desktop/build/windows-app-image/ChopLab/ChopLab.exe`。`app` / `runtime` 等の隣接ファイルを含めて保持します。EXE単体やpackage成功だけで音声出力を確認したとは扱いません。

現行launcherは最初の引数に `.wav` / `.choplab` を受け取ります。表示中の4×4PADは `1234 / QWER / ASDF / ZXCV` で演奏でき、keyupは元のPADを解放します。録音・loading・source再生中や修飾shortcutとの競合では所有を守ります。native menuからopen/save/export、Undo/Redo、transportへ到達します。

ローカルchooserの基準はWAVです。オンライン取込で使う外部取得/decode経路とは別であり、新しいpackaged decoderのfixtureが通る前に全形式対応と表示しません。現行loopbackはdriverの「Stereo Mix」等に依存し、対応しない時は理由を表示してmicへ勝手に切り替えません。WASAPI endpoint診断は形式を読むprobeで、出力/input/loopbackの全面採用は段階11です。

## Macでの起動と取り込み

JDK21とXcode Command Line Toolsを用意し、repository rootで `./gradlew :desktop:run` を実行します。`run` と `installDist` はMacでScreenCaptureKit helperをbuildします。圧縮音声・YouTube取込にはffmpeg、ffprobe、yt-dlp、nodeが必要です。同梱directory、PATH、Homebrewの順で解決します。Homebrewを使う場合は `brew install ffmpeg yt-dlp node`、既存版がある場合は互換性を確認して使います。

音源選択はネイティブのファイルパネル、ウィンドウへのドロップにも対応。Commandキーのopen/save/Undo/Redo/quitを使います。新規データはApplication Support、既存の旧データ領域がある場合は移動せず維持します。システム音はhelperを優先し、OSの画面収録・システムオーディオ録音の許可が必要です。マイク許可は別です。

`youtube.com/watch?...` や `youtu.be/...` の貼り付けはHTTPS動画URLへ正規化します。複数ファイルは件数と現在のファイルを表示し、一部が失敗しても残りを処理します。完了後に成功分と失敗ファイルを確認して再試行できます。圧縮音源の取込直後の使用では、同じbytesの検証済みPCMを1回だけ再利用します（64MiB以内の1素材）。音質・フレーム・左右は変えず、超過した素材も従来と同じdecodeで扱います。

Macの配布・OS/CPU別の受入と、開発起動の成功は区別します。最新結果はROADMAPで確認してください。

## Mac同梱Preview

`./gradlew :desktop:packageMacPreview` は `desktop/build/mac-preview-app-image/ChopLab Preview.app` を生成します。Java、録音helper、取得/decodeツール、分離モデルを含み、起動folderやHomebrew PATHに依存しません。Previewの保存先は正式版と分離します。初回準備はHomebrewのffmpeg/nodeとXcode CLI、Python3.11以降が必要です。yt-dlpはupstreamの固定version/hashで取得し、native依存の一覧変更は `config/mac-media-tool-files.txt` のreviewが必要です。検証用Ogg fixtureには `brew install vorbis-tools` を使います。

このtargetは**ローカルad-hoc署名**です。Developer ID署名を求める場合は `CHOPLAB_MAC_SIGNING_IDENTITY` にKeychainの証明書名を設定し、`:desktop:packageMacSignedPreview` を使います。未設定/無効な証明書で自動的にad-hocへ切り替えません。署名・公証・再配布条件の実確認は別の受入です。product versionとMac buildは同じ `gradle.properties` のversion/buildNumberから生成します。

登録済みの公開Spotify Client IDは、Windowsと同じ `CHOPLAB_SPOTIFY_CLIENT_ID` をビルド時に指定するとMacアプリにも引き継がれます。Client Secretやtokenは同梱しません。未設定でもファイル取込などの制作は使え、Spotify接続時にClient IDを入力できます。実アカウントでのOAuthとAPI操作は別途確認します。

```sh
python3 scripts/run_mac_acceptance.py \
  --app 'desktop/build/mac-preview-app-image/ChopLab Preview.app' \
  --java-home "$JAVA_HOME"
```

`--system-audio` は短い確認音を鳴らし、他process音の取得と自分の音の除外を測ります。無関係な再生を止め、画面収録/システム音声の許可がある表示sessionで実行します。raw録音はメモリだけ、codec fixtureは一時領域に作成し最後に削除します。聴感を自動認定しません。

## Macの4工程NEXT

`./gradlew :desktop:packageMacLinkedPreview` で `desktop/build/mac-linked-preview-app-image/ChopLab NEXT.app` を作成します。Java、録音helper、取得/decodeツール、ドラム分離モデルを含み、単体で起動できます。初回ビルドはHomebrewのffmpeg/node、Python3.11以降とXcode CLIが必要です。同梱ツールとnative依存は `config/mac-media-tool-files.txt` で固定し、実行時はHomebrewやPATHを使いません。元の4工程・大きなPAD・右側の曲配置を使う新しい編集入口です。既存Previewと別のapp identityを持ち、制作はPreview領域の `next-v10` に分離します。ローカルad-hoc署名で、公証済みの一般配布とは別です。

WAV・FLAC・MP3・M4A（AAC/ALAC）・AAC・Ogg・Opus・AIFF/AIF・MP4・WebMの取込、チョップ、PAD、合成ドラム、曲への配置、原曲用のマイク録音、曲に合わせた声の録音、スクラッチ、保存/再開、WAV書出しを接続しています。オンライン候補からの明示取込、Mac端末音録音、音源ライブラリ、ドラム分離もNEXTへ接続済みです。Spotify接続と長尺prefetchは移植中で、全制作機能の受入はROADMAPに沿って進めます。起動と保存復元の再現手順は [TESTING](../docs/TESTING.md)、移行とMac実測は [ROADMAP](../docs/ROADMAP.md) で管理します。

helperはmacOS14.0を明示してbuildし、最終アプリの最低OSは同梱helper・ツール・Java runtime・JAR内native依存の実バイナリから判定して、Info.plistと配布manifestへ記録します。このMacで用意したcodecにはmacOS27.0を要求するものがあるため、そのbundleはmacOS27.0以降用です。Apple Silicon以外や古いOSは別のbytesでの検証が必要です。

梱包後の受入は `scripts/run_mac_next_acceptance.py` を使います。インストール済みアプリでは、インストール時に保存したmanifestを `--manifest` で指定します。標準のbuild出力では省略できます。全ファイル・hash・署名を照合してから隔離profileで制作通しと通常終了・再開を確認します。

```sh
python3 scripts/run_mac_next_acceptance.py \
  --app "$HOME/Applications/ChopLab NEXT.app" \
  --manifest "$HOME/Applications/.choplab-manifests/ChopLab NEXT.app.json" \
  --java-home "$JAVA_HOME"
```

## データ・接続・検証

制作autosaveとlibraryはapp専用領域にあり、実行ファイルの更新と分けます。既存install scriptはversion/hashに結び付いたapp-imageを保持し、利用者の制作を上書きしません。Previewの専用設定/autosave/library/cache/lock分離は段階1Cで検証します。

Spotifyには公開Client IDを設定し、PKCEのOAuthで接続します。tokenをsource/log/projectへ保存しません。現行のお気に入り自動取込は曲情報をYouTube候補へ照合し、取得・decode・library保存の成功後に使える音声となります。選択式への刷新は段階3です。登録/redirect/mode/scopeは採用時の公式設定と実accountを照合し、UI表示だけでprovider成功としません。

Windows配布物はWindowsで起動・応答・停止、native dialog、音声routeを試し、対象revisionと全app-image bytesを結果に結び付けます。詳しくは [TESTING](../docs/TESTING.md)、[RELEASE](../docs/RELEASE.md)、[PRIVACY](../PRIVACY.md) を参照してください。

NEXTの圧縮音源は元のbytes・rate・左右を保って保存し、再生時はfloatへデコードして共有の48 kHz変換を使います。新engineの常駐上限（48 kHz stereoで約349秒）が適用されます。圧縮原本を含むschema10制作ファイルのAndroid NEXTでの再開は未対応です。Mac/Windows NEXT間は同じdecoder経路ですが、実Windows端末でのcodec受入は別途行います。

NEXTの「1 入れる」→「マイクで録音」は空の制作でも使えます。停止すると音全体が原曲になり、既存のPAD・曲を保持したままチョップできます。録音の取消は制作を変更せず、停止後はUndoで前の原曲へ戻れます。最大5分で、制作内の音声量上限・保存容量によって短くなります。入力は既存Java Sound経路の48/44.1 kHz・PCM16、ステレオ入力はモノラルに合成し、保存はfloat WAVです。実機の録音品質・遅延は別途確認します。
