# AI assist vision

ChopLabのAIは、ユーザーの操作を奪う自動編集ではなく、現在の制作を壊さない提案機能として追加する。

## One-entry workflow

基本のループを作った後にだけ、1つの`AIで整える`入口を表示する。入口から次のような目的を選ぶ。

- 音量を揃える
- ノイズや無音を整える
- チョップ位置を提案する
- ノリを出す
- 別パターンを提案する
- 「もっと跳ねる」「キックを強く」のように言葉で頼む

AI操作を通常のPAD・ビートループ・配置フローへ混ぜず、ボタン密度を再び増やさない。

## Safety and trust contract

1. AIは現在のproject snapshotから`proposal`を作り、直接状態を書き換えない。
2. 変更前後を試聴し、対象PAD、step、gain、sliceなどの差分を表示する。
3. `適用`まで元の制作を維持し、適用後も既存Undoで完全に戻せるようにする。
4. transient、無音、音量、tempo候補などはlocal-firstで解析する。
5. cloud modelへ音声を送る場合は、送信対象、目的、保持方針を実行前に表示し、明示同意を取る。
6. AIが不確かな場合は勝手に確定せず、複数候補または`変更なし`を返す。

## Architecture seam

将来の実装は、UIやaudio callbackからモデルを直接呼ばず、次の境界を持つ。

- `AssistRequest`: project snapshot、目的、対象範囲、local/cloud policy
- `AssistProposal`: versioned edits、説明、confidence、preview render request
- `AssistPreview`: before/after試聴と構造差分
- `ApplyAssistProposal`: 通常のedit historyを通る単一command

リアルタイムaudio callbackでは推論、allocation、I/O、networkを行わない。解析とpreview renderは停止可能なbackground jobにする。

## First practical slice

最初はLLMより、端末内で再現性のある`音量を揃える + 無音trim候補 + transient再提案`が適している。次にpattern variationを追加し、最後に自然言語を各安全なcommandへ変換する。
