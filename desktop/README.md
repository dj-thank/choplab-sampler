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

ローカルchooserの基準はWAVです。オンライン取込で使う外部取得/decode経路とは別であり、新しいpackaged decoderのfixtureが通る前に全形式対応と表示しません。NEXTのWindows既定はWASAPI shared event出力です。「音声出力」メニューでJava Soundへの切替・再接続を明示できます。入力は録音操作まで開かず、マイクはmono、通常endpoint mixのシステム音はstereoで保持します。録音中や旧route未解放は切替を拒否し、自動fallbackは行いません。endpoint probe・合成host試験と、対象Windows実機の長時間・実音・Human受入は別です。

## Macでの起動と取り込み

JDK21とXcode Command Line Toolsを用意し、repository rootで `./scripts/run_mac_next.sh` を実行すると、最新の4工程NEXTをソースから起動します。従来画面は `./gradlew :desktop:run` です。`run`、`runLinkedPreview`、`installDist` はMacでScreenCaptureKit helperをbuildします。圧縮音声・YouTube取込にはffmpeg、ffprobe、yt-dlp、nodeが必要です。同梱directory、PATH、Homebrewの順で解決します。Homebrewを使う場合は `brew install ffmpeg yt-dlp node`、既存版がある場合は互換性を確認して使います。

開発起動の制作・ライブラリ・モデルはcheckout内の `work/desktop-development/data/ChopLab Preview`、録音等の一時領域は同じ開発rootの `tmp` に保存します。インストール済みNEXTの制作と分離し、`gradlew clean` でも開発制作を消しません。通常終了で保存した制作は次の開発起動で復元します。Gradleから直接起動する場合も `:desktop:runLinkedPreview` が同じ境界を使います。別の隔離rootは `-PchoplabDevelopmentRoot=<directory>` で選べます。

`./scripts/run_mac_next.sh --debug` は、アプリのmain実行前に `127.0.0.1:5005` でJVMデバッガの接続を待ちます。IDEのRemote JVM Attachで同じhost/portへ接続し、`LinkedPreviewMain.kt`、`NextBackend.kt`、共有Presenter/engineにbreakpointを置けます。JDKの `jdb -attach 127.0.0.1:5005` でも接続できます。別portは `./gradlew :desktop:runLinkedPreview --debug-jvm -PchoplabDebugPort=5006` です。音声threadを停止するbreakpointは音切れと時刻の停滞を起こすため、デバッガ停止中の遅延値を音声性能の測定に使いません。

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

窓の可視領域はOSの作業領域と文字サイズで確認します。文字を大きくした場合はPAD/詳細面をスクロールでき、曲と全停止は固定です。原曲の音量は大文字時にラベルを上へ配置して操作幅を保ちます。実ウィンドウの受入ではouter/content/work areaを測り、その内容sizeで16PADと主要操作の可視領域・実入力を別途確認します。PNGだけを実端末入力や音質の受入にはしません。

梱包後の受入は `scripts/run_mac_next_acceptance.py` を使います。インストール済みアプリでは、インストール時に保存したmanifestを `--manifest` で指定します。標準のbuild出力では省略できます。全ファイル・hash・署名を照合してから隔離profileで制作通しと通常終了・再開を確認します。

```sh
python3 scripts/run_mac_next_acceptance.py \
  --app "$HOME/Applications/ChopLab NEXT.app" \
  --manifest "$HOME/Applications/.choplab-manifests/ChopLab NEXT.app.json" \
  --java-home "$JAVA_HOME"
```

## データ・接続・検証

制作autosaveとlibraryはapp専用領域にあり、実行ファイルの更新と分けます。既存install scriptはversion/hashに結び付いたapp-imageを保持し、利用者の制作を上書きしません。Previewの専用設定/autosave/library/cache/lock分離は段階1Cで検証します。

NEXTの「Spotify情報」は曲名・artist・お気に入り・検索結果を表示し、「Spotifyで開く」から対象曲へ戻る専用窓です。制作・音声・Undo・ライブラリを変更せず、旧PreviewのYouTube自動照合へ接続しません。認可は[PKCE S256](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)、scopeは `user-library-read` のみです。tokenと取得した曲情報はメモリ内だけに保持し、連携解除・アプリ終了で破棄します。窓を閉じると進行中の認証・取得を取り消し、成立済みの接続はアプリ終了まで保持します。429は `Retry-After` の待ち時間を表示して取得を止め、時間経過後に利用者が再試行できます。

公開Client IDは `CHOPLAB_SPOTIFY_CLIENT_ID` でMac NEXTのビルドへ渡すか、窓内でその起動中だけ設定します。Client Secret・token・秘密鍵は指定・同梱しません。Spotifyの登録先[Redirect URI](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)は `http://127.0.0.1/callback`（ポートなし）。実際の認証では空いている動的ポートを使い、token交換にも同じURIを渡します。`localhost` は使いません。[Development mode](https://developer.spotify.com/documentation/web-api/concepts/quota-modes)ではアプリ所有者のPremiumと最大5人の許可ユーザー登録が前提です。OAuth画面を通っても未許可ユーザーのAPIは403になるため、接続表示をAPI成功としません。

2026-09-28に[Developer Policy](https://developer.spotify.com/policy)を照合しました。リンク付きmetadata閲覧を独立させていますが、他サービスのcontentとの統合禁止、mix禁止、帰属表示などの製品全体の条件を満たしたとの判定は未了です。実accountのOAuth/API、native窓の操作、一般配布の適合確認は独立した受入としてROADMAPに残し、未解決の連携を一般配布へ有効化しません。旧Previewの自動取込は移行対象であり、NEXTでの受入証拠ではありません。

Windows配布物はWindowsで起動・応答・停止、native dialog、音声routeを試し、対象revisionと全app-image bytesを結果に結び付けます。詳しくは [TESTING](../docs/TESTING.md)、[RELEASE](../docs/RELEASE.md)、[PRIVACY](../PRIVACY.md) を参照してください。

NEXTの圧縮音源は元のbytes・rate・左右を保って保存し、再生時はfloatへデコードして共有の48 kHz変換を使います。新engineの常駐上限（48 kHz stereoで約349秒）が適用されます。圧縮原本を含むschema10制作ファイルのAndroid NEXTでの再開は未対応です。Mac/Windows NEXT間は同じdecoder経路ですが、実Windows端末でのcodec受入は別途行います。

NEXTの「1 入れる」→「マイクで録音」は空の制作でも使えます。停止すると音全体が原曲になり、既存のPAD・曲を保持したままチョップできます。録音の取消は制作を変更せず、停止後はUndoで前の原曲へ戻れます。最大5分で、制作内の音声量上限・保存容量によって短くなります。入力は既存Java Sound経路の48/44.1 kHz・PCM16、ステレオ入力はモノラルに合成し、保存はfloat WAVです。実機の録音品質・遅延は別途確認します。
