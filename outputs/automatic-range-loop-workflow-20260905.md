# 選択音の自動表示と、ループ中心のビート制作

元曲全体を残し、選択した音の調整を自動表示とS/Eダイヤルにまとめました。元曲の地図を押すと、その場所からチョップし直せます。

- 選択音のズーム、精密モード、拍合わせボタンを撤去。選択したS..Eが常に波形の表示範囲です。
- S/Eは常時表示するダイヤル。表示中のPADを相対調整し、再生中のループにも反映します。
- 元曲全体には同じ元曲の切れ目を表示。再生開始が受け付けられた後だけCHOPへ移動し、失敗時は画面・選択・履歴を保持します。
- 切り直し後のチョップ追加が、以前の切れ目や手動調整済みの範囲を動かす不具合を修正しました。
- BEATはループを軸に、ドラム追加、スクラッチ、詳しい配置編集へ進む構成です。同じループを再選択しても再始動しません。ループ＋ドラムをまとめて開始・停止し、スクラッチからも戻します。

![選択音の自動表示](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/reports/tests/desktopLongPressUiTest/evidence/automatic-range-portrait.png)

![ループ中心のビート](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/reports/tests/desktopLongPressUiTest/evidence/loop-first-beat.png)

画像は共有Compose UIを合成音源で描画した入力検証の出力です。

## 検証

通常テスト789件、UI/controller検証28件（controller12件は通常テストと重複）のXMLは失敗・error・skip 0。新しい自動表示、ダイヤル、元曲タップ、既存切れ目の保持、ループ中のドラム追加、開始失敗、停止待ちを確認しました。Android lint/APK、Windowsアプリ一式、project validationも通過。Androidテスト用APKの最終再ビルドも通過しました。

Androidの旧BEAT PADジェスチャーテストは、残したCHOP PAD画面へ移しました。ONE SHOTとGATEの異なるcallbackを保持します。Android instrumentationはコンパイルまでで、実行はしていません。

並列実行時に既存非同期テストのタイムアウトが発生しました。条件を緩めず直列実行で通過しましたが、タイムアウト原因を解消したとはしていません。

実機の音・タッチ・録音・読み上げ、Android ViewModel E2E、iOS、公開/Human評価は未確認です。スクラッチはライブ中断・復帰で、録音による重ね合わせではありません。

## 成果物

- [Windowsアプリ一式](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/windows-app-image/ChopLab)（ChopLab.exe単体ではなくフォルダ一式で利用）
- [Android APK](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/app/build/outputs/apk/debug/app-debug.apk)
- [検証とSHA-256](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/outputs/automatic-range-loop-workflow-20260905.json)

Base `1e55128c079cb4d789d4e61526f1a0cedcd1d48e`、branch `codex/choplab-production-refinement-20260905`。元のdirty canonical checkoutを保持した専用worktree内の変更です。公開・端末へのインストールは行っていません。
