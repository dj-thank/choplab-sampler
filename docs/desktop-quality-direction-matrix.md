# ChopLab Windows EXE — quality direction matrix

更新: 2026-08-19

この台帳は、単なる EXE 化から「世界水準の制作ツール品質」へ引き上げるための bounded discovery の判断を記録する。対象は Windows `:desktop` と GitHub の PR/CI まで。Android `:app` の挙動、Spotify Content の音声取得・録音・stream ripping・MP3 化、署名配布、実機音質評価は別境界として保持する。

## Baseline

現状は、Android の `OtohiroiDeck` / `PadGrid` / `BeatLaneBoard` / `GuidedWorkflow` / `ProductionDockPolicy` を `:shared` へ移し、Android と Windows が同じ Compose UI・モデルをコンパイルする構成である。Windows はローカル WAV の読み込み／再生と共有編集 state を持つ。Spotify PKCE は metadata/control adapter として別 seam に残す。

## Selection criteria

1. **Original fidelity:** parity contract の4工程、全コピー、色トークン、CHOP/BEAT/FINISH state が崩れないこと。
2. **Flow integrity:** 未準備状態の次工程へ進めず、source → PAD → arrange の状態遷移が決定的であること。
3. **Professional interaction:** キーボード、フォーカス、アクセシブルな名前、High-DPI、失敗時の説明があること。
4. **Delivery reliability:** `:desktop:test`、Android regression、Windows app-image、GitHub PR CI が同じ境界を検証できること。
5. **Rights and evidence:** Spotify は metadata/control only、local evidence は `LOCAL_PASS` を超えて主張しないこと。

## Directions considered

| Direction | User promise / key assumption | Smallest reversible test | Upside | Main downside | Decision |
|---|---|---|---|---|---|
| A. 原作デック忠実版 | Android を知るユーザーが一目で同じ楽器だと分かる。正本の文脈・工程を守れば学習コストが下がる。 | Android-origin UI source を Windows Compose target でコンパイルし、同一 state/copy contract を確認する。 | ユーザー意図に最も近く、二重実装の drift を防ぐ。 | Compose Multiplatform と native audio adapter の移行コストがある。 | **採用する基盤** |
| B. Context-aware guided deck | `GuidedWorkflow` と `ProductionDockPolicy` の文脈を状態機械として使い、今できる次の行動と未対応境界を常に説明する。 | source 未読込／PAD 0件／PAD 1件／arrange の状態表を model test と GUI smoke で検証する。 | 「次に何をするか」が明確で、元UIのコピーを意味のある体験にできる。 | 状態を増やしすぎると説明過多になる。 | **主方向** |
| C. Pro studio hybrid | 高密度な波形編集、ショートカット、保存・UNDO を先に足し、デスクトップDAWらしさを出す。 | 1つの undoable PAD assignment と Ctrl+O/Esc のキーボード操作を先に試す。 | 生産性と将来の拡張性が高い。 | 早期に追加すると原作の画面契約を壊し、スコープが膨らむ。 | 段階的に採用 |
| D. Project-first session | `.choplab` 保存、開く、再開、バックアップを先に固める。 | 1セッションの source／PAD／steps の round-trip test。 | 制作物を失わない製品品質へ直結する。 | 今回の EXE UI fidelity の acceptance からは独立した大きな seam。 | 次フェーズ |
| E. Native audio foundation | WASAPI/低レイテンシ基盤とデバイス録音を先に実装する。 | 1台の Windows route の latency/underrun benchmark。 | 音楽ツールとしての最終品質に必要。 | 実機・driver・人間評価が必要で、現在の `LOCAL_PASS` を超える。 | 別 bounded task |

## Selected delivery contract

Primary direction is **A + B**: 原作デックを視覚契約として維持し、状態・操作の seam を深くして、各画面が正直な次アクションを示す。今回の local slice では次を実装した。

- machine button、custom PAD、波形、16-step に accessible name/description と可視フォーカスを付ける。
- Ctrl+O（WAVを開く）、Escape（全停止）、Ctrl+1〜5（工程選択）、PAD／波形／step のキーボード操作を追加する。
- source → PAD → beat の state and stage policy を `:shared` の model/UI policy に集約し、UIから状態判定を重複させない。
- High-DPI の初期サイズ、版番号付き app-image、PRごとの Windows test/package CI、EXE SHA-256 receipt を揃える。
- PAD の KEY/TONE/LEVEL は現在、原作UIの操作状態を保持する seam であり、音声DSPへの接続は次の明示的なマイルストーンとする。見かけだけの機能を完成扱いしない。

C と D は次の拡張候補、E はデバイス／ドライバー／人間による証拠が必要な別 bounded task とする。

## Evidence ledger

- 読み取り専用の品質レビューから、再生終了時の stale state、source/PAD切替、狭い arrange 欄の文字衝突、フォーカス、署名境界を gap として得た。根拠を local code/capture に照合し、再生終了・source再ロード・狭幅表示・フォーカス・版番号/CIをこの slice で補正した。
- Luna の追加 verifier は runtime provenance checker が完了しなかったため dispatch せず、子結果は採用済みの gate 証拠にしない。最終判断と検証は root の local pass とする。
- Current gate ceiling: `LOCAL_PASS` only。GitHub branch/PR は repository management であり、public release・provider/device proof ではない。
