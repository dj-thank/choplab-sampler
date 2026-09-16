# ChopLab UI・編集処理の監査と改善 — 2026-09-05

主要4工程（入れる・チョップ・ビート・保存）、A/B・レイヤー・PAD編集のモーダル、共有編集処理とAndroid/Windowsの呼出順を監査しました。画面整理に加え、録音・読込中の副作用と確認操作の不整合を修正しています。

| 問題 | 修正・確認方法 |
|---|---|
| 保存画面のボタンが37dpまで縮む | 8ボタン6段から6ボタン4段へ整理し、各段56dp。360×800・文字130%の実コンポーネントで測定 |
| 件数表示が縦に引き伸ばされ、主要操作を圧迫 | 情報欄を56dpのコンパクトな行へ。フォームを最大720dpでまとめ、足りない高さではスクロール |
| 保存画面に編集破棄・重複ナビゲーションが混在 | 全配置消去はA/B画面へ移動。BACKは上部の工程切替へ統一。Undo/Redo・保存・読込・書出しは維持 |
| A/Bコピーの確認が期限切れせず、内容変更にも追従しない | 共通の4秒タイムアウト確認へ統合。対象内容や選択PADが変われば解除。実クリックで検証 |
| 空配置の消去・変化しないシフト・録音中の操作が押せる | 状態から有効/無効と理由を導出。録音全phase/loadingを共有host testsで確認 |
| 録音中のキット交換が編集拒否前にループを止める | 両platformの入口で共有ガードを実行。Vocal録音中のドラムループ維持と読込中の制作保持をDesktop controllerで確認 |
| Androidの通常編集がloading中に入り込む | commitEditも共有ガードへ統一。Capture/Chop/Beat/PADの関係するUI入口を同期 |
| 声の保存完了がSTOPPINGの編集拒否へ入る | current operationの正常終了時に録音状態を解除してからtakeを割当。自動チョップもrevision/source確認後にloadingを解除してcommit |
| 未接続の自動チョップ内部APIに古い完了の競合がある | 解析も既存の処理世代に参加し、成功・失敗・中止・破棄の全経路を世代確認後だけ適用 |
| 「閉じて16ステップを編集」が閉じるだけ | 誤解を招く重複ボタンを削除。ヘッダの閉じる操作と元の工程切替を保持 |

## 実画面の比較

同じ360×800、文字130%のJVM/Skiko描画です。

変更前（ボタン37dp）:

![変更前](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-ui-audit-save-before-20260905.png)

変更後（ボタン56dp）:

![変更後](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/reports/tests/desktopLongPressUiTest/evidence/audit-save-portrait.png)

## 検証

**LOCAL_PASS**。通常テスト777件（共有103×2、Android289、JVM core88、Desktop194）と操作検証29件が成功。操作検証には通常テストと重なるcontroller12件を含みます。失敗・error・skipは0。Android lint/APK/androidTest compile、Windows package、project validationも成功。

[機械可読の検証記録](ui-audit-refinement-20260905.json)にAPKとWindows bundleのハッシュを記録しています。

## 監査範囲と残る確認

基準commit `e5aa359`。rootが統合し、2つのGPT-5.6 Lunaレビュー（UX/仕様、構造・正しさ）の具体的指摘を照合しました。role名だけではなくsession metadataでmodelとparent-edgeを確認。実sandboxはdanger-full-accessであり、read-onlyの担当指示をOSの書込み制限とは扱っていません。

実機の音声・マイク・タッチ・TalkBack、iOS実行、公開Release、利用者の使い心地は今回の確認範囲外です。Androidの非同期完了修正はコード照合・共有状態契約・ビルドまでで、Android ViewModel/実マイクのE2Eを実行したとは報告しません。DSP・依存関係全体の網羅的セキュリティ監査でもありません。`autoChopTransient`は現行ソース内に呼出箇所がなく、競合修正は未接続の内部APIの保守であり、通常UIから再現した不具合として数えません。

## 起動先

- [Windows ChopLab.exe](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/windows-app-image/ChopLab/ChopLab.exe)（隣接app/runtimeを保持して使用）
- [Android debug APK](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/app/build/outputs/apk/debug/app-debug.apk)
- [実施計画](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/plans/completed/ui-audit-refinement-20260905.md)
