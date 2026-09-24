# 音を選び、ループを核にして、一つずつ重ねる

3のビート画面を、選択できる音と、いま重ねている音が分かる構成へ戻しました。

1. 音のカードを選びます。選択だけでは再生は切り替わりません。
2. 「ループ」を押すと、選んだ範囲を繰り返します。
3. 別の音を選び「重ねてループ」で追加します。核のループを止めたり再始動したりせず、最大8音まで重ねられます。
4. 追加音を選び直してS/Eを調整したり、その音だけ外したりできます。核の停止では全体を止めます。

![選択・ループ・重ねる音の一覧](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/outputs/beat-loop-layers-20260905/selection-and-layers.png)

元曲全体の切れ目と、選択範囲の自動表示も残しています。小画面ではスクロールして各操作へ届きます。

## 一緒に修正したこと

- ドラム再生を始めても、すでに回っている核を再スタートしません。
- ドラムキットを変えても、作ったA/Bの配置とSong順序を保持します。Undo/Redo、保存後の再読込まで確認しました。
- キット交換時に古いドラムループだけ鳴り続ける問題を修正。交換対象のループを停止してから音色を替え、停止が受け付けられなければ変更を中止します。核自体を交換する場合は、その再生セッション全体を停止します。
- 停止後の再生、スクラッチからの復帰、録音時のループ構成にも追加音を反映します。

## 検証とレビュー

モデル、Android/Windowsの制御、WAVのPCM、プレイヤーの開始/停止呼び出し、共有画面の入力を検証しました。ClipProbeでは追加・取り外しで核の停止/再開が増えないこと、WAVでは核と追加音が終盤まで両チャンネルに残ることを確認しています。通常812件＋UI/controller33件（12件重複）、最終XMLの失敗・error・skipは0。Android lint/APK/テストAPKコンパイル、Windowsアプリ一式、project validationも通過しました。件数・SHA-256は[検証receipt](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/outputs/beat-loop-layers-20260905.json)に記録しています。

Execution: verified Luna sub-agents（gpt-5.6-luna max）。StandardsとSpecを別々にsource-onlyで確認し、rootが実行検証と統合を担当しました。

### Standards

必須指摘1件（キット交換時のループ停止漏れ）を修正し、再レビューで解消確認。任意のUI判定重複の整理案1件は記録し、今回は追加抽象化を行っていません。

### Spec

8音上限を明示する指摘1件を解消。初期版はドラム・声の再生余力を残すため8ループとし、9音目は理由を表示して追加を拒否します。残るSpec指摘は0件です。

## 現時点の範囲

各ループは切り出した長さのまま繰り返します。自動のテンポ合わせやタイムストレッチは行いません。保存したループを再生し直すと、それぞれの先頭から始まります。演奏中に追加した瞬間のタイミングを、そのまま録音・再現する機能ではありません。

画像は合成音源を使った共有Compose画面です。実機の音質・タッチ・録音・読み上げ、Android instrumentation実行、iOS、公開/Human評価は未確認です。

Product revision `360ccb0b14d7ed2fb4018d280335242904a2fc54`、base `c83cad8db221e80f8857dfc8d7afc542cc3a7bf4`。元のdirty checkoutを保持した専用worktree内の変更です。

- [Windowsアプリ一式](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/desktop/build/windows-app-image/ChopLab)（フォルダ一式で使用）
- [Android APK](C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-refinement-20260905/app/build/outputs/apk/debug/app-debug.apk)
