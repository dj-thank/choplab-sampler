# 音源を追加する

Windows・Androidに、ファイル／YouTube／Spotifyのお気に入りを入口にする音源ライブラリを実装しました。

- **ファイル・音源セットを追加**: WAV/MP3/M4A/動画などをアプリ内に保存し、次回は一覧から選べます。壊れた音源は登録前に拒否します。
- **YouTube**: URLなら取得へ、曲名なら候補一覧へ進みます。過去のPart 4作業と同じyt-dlp＋FFmpeg方式です。
- **Spotify**: ログイン後、お気に入りの曲をタップして対応するYouTube音源を取り込みます。候補が複数ある場合は選択画面になります。Spotify音声の直接ダウンロードではありません。
- ライブラリから別の音を選んでも、作ったPAD・配置・曲構成は保持します。

## 起動・取り込み

- [新しいWindows版](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/windows-app-image-audio-source-20260905/ChopLab/ChopLab.exe) — 隣のapp/runtime/toolsを含む一式で使用してください。
- [Android APK](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/app/build/outputs/apk/debug/app-debug.apk)
- [既存4音源のAndroid用セット](C:/Users/rambo/Documents/ChatGPT/pad/outputs/ChopLab-personal-audio.choplib) — Android側へ渡し、**音源を追加 → ファイル・音源セットを追加**で選びます。

Windowsの既存4音源は個人用ライブラリへ追加済みです。元の音源ファイルは変更していません。APKには音楽を同梱していません。今回の両版は公開Client IDを設定済みなので、Client ID入力なしでログインできます。認証情報は起動中のメモリだけに保持します。

起動中だった旧Windows版の出力先は、最初のパッケージ処理による削除が途中で止まった状態です。作業を保存して閉じた後は、必ず上の**新しいWindows版**から起動してください。今後は起動中の出力先へ触る前に拒否する保護を追加し、実際の拒否も確認しました。

## 確認したこと

通常テスト826件、UI/controller 34件（通常テストとの重複を含む）、Android lint/APK/test-APKビルド、Windows一式作成、project validationを確認。最終XMLのfailure/error/skipは0です。既存UIテストで一度タイムアウトし、変更せず全体を再実行して通過しました。間欠性が解消したとはしていません。

Spotifyは実アカウントのお気に入り20曲の取得まで確認。YouTubeはPart 4の実取得・PCMデコードを確認しました。Androidの認証戻り先も同意後に保存・再読取り済みです。**Android実機での取得・転送・再生、iOS、公開Releaseは未実施**です。

画面例は共有UIのテスト用データで、実際のお気に入り情報ではありません。

![小画面の音源追加](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/outputs/audio-source-library-20260905/favorites-390.png)

## 実装・再現

Product `6f7bc7c36a3ee667d4890eeed9c2c762f5e784e4`。build guard `cda7e5fa633ea2ecd6bea04f27dc636ff7fdf6f0`。詳細なファイルSHA-256・検証scopeは隣のJSONを参照。

Windowsの再パッケージは `:desktop:packageWindows -PwindowsPackageDirectory=新しい出力名`。公開Client IDを含めるときだけ `CHOPLAB_SPOTIFY_CLIENT_ID` を設定します。AndroidはPC常駐を要求せず、bundled yt-dlp/FFmpegを使います。抽出器更新は新しい検証済みビルドで行います。

音源セットは32音源・128entry・展開後1GiBまで。上限超過の書き出しは既存の保存先へ触る前に拒否します。ZIP/音声検証は一時領域で行い、検証失敗の音源を公開しません。登録段階のディスクI/O障害では、それ以前に正常登録された音源が残る場合があります。
