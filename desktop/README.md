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

```sh
python3 scripts/run_mac_acceptance.py \
  --app 'desktop/build/mac-preview-app-image/ChopLab Preview.app' \
  --java-home "$JAVA_HOME"
```

`--system-audio` は短い確認音を鳴らし、他process音の取得と自分の音の除外を測ります。無関係な再生を止め、画面収録/システム音声の許可がある表示sessionで実行します。raw録音はメモリだけ、codec fixtureは一時領域に作成し最後に削除します。聴感を自動認定しません。

## データ・接続・検証

制作autosaveとlibraryはapp専用領域にあり、実行ファイルの更新と分けます。既存install scriptはversion/hashに結び付いたapp-imageを保持し、利用者の制作を上書きしません。Previewの専用設定/autosave/library/cache/lock分離は段階1Cで検証します。

Spotifyには公開Client IDを設定し、PKCEのOAuthで接続します。tokenをsource/log/projectへ保存しません。現行のお気に入り自動取込は曲情報をYouTube候補へ照合し、取得・decode・library保存の成功後に使える音声となります。選択式への刷新は段階3です。登録/redirect/mode/scopeは採用時の公式設定と実accountを照合し、UI表示だけでprovider成功としません。

Windows配布物はWindowsで起動・応答・停止、native dialog、音声routeを試し、対象revisionと全app-image bytesを結果に結び付けます。詳しくは [TESTING](../docs/TESTING.md)、[RELEASE](../docs/RELEASE.md)、[PRIVACY](../PRIVACY.md) を参照してください。
