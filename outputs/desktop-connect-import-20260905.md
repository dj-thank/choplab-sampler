# デスクトップの連携・音源追加

上の「連携」メニューとコネクトパネルを、内部ライブラリへの取り込みにつなぎ直しました。

| やりたいこと | 操作 |
|---|---|
| Spotifyのお気に入りを追加 | 連携 → Spotifyのお気に入りから追加 → ログイン → 曲を選択 |
| YouTubeの音を追加 | 連携 → YouTubeから追加 → URLまたは曲名 |
| PCのファイルを追加 | 連携 → PCのファイルから追加 → ファイルを選択 |
| 取り込んだ音を使う | 連携 → 音源を追加、またはパネルの「取り込んだ音源を見る」 |

PCファイルのボタンはファイル選択を直接開きます。Spotify・YouTubeは対象の取り込みタブを直接開きます。取得した音は内部ライブラリへ保存し、使う音からチョップへ進みます。Spotifyで選んだ曲の音声は対応するYouTube動画から取得し、候補が一意でない場合は選択します。

パネルの先頭にあったClient ID・開発者設定の説明と多数の再生ボタンを整理しました。再生操作は下の「Spotifyの再生操作」を開くと使えます。未設定ビルド向けの初回設定は取り込み画面側に残っています。

[新しいWindows版を開く](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/windows-app-image-connect-20260905/ChopLab/ChopLab.exe)

現在の作業を保存して旧版を閉じ、上の新しいEXEから起動してください。app/runtime/toolsが隣にある一式で使用します。既存のライブラリと制作データはそのまま使います。

![新しいコネクトパネル（ログイン前・4件のテスト状態）](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/outputs/desktop-connect-import-20260905/connect-760.png)

Product `58e543199cb7e945940bfc4dfb366327fe7e236e`。Desktop 202、JVM core 100、UI/controller 35件（12件重複）、最終failure/error/skip 0。760/1100幅でパネルの実ポインター入力→各取り込み画面とログインcallbackを確認。Windows一式とproject validationも通過しました。

既存ループUIテストで一度待機timeoutを確認し、対象位置が安定するのを待ってから入力するようテスト補助を修正しました。修正後の全体検証は通過しています。今回のOSネイティブのファイル選択操作・新しいOAuth通信・実機Androidは未実施です。音源処理とAndroidの実装は前タスクのままです。隣のJSONに完成アプリ全ファイルのSHA-256があります。
