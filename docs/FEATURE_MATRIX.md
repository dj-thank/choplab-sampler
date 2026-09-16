# UI follow-up — 2026-09-11

| Area | Candidate change | Current boundary |
|---|---|---|
| Buttons/tabs/settings | Readable text hierarchy, disabled labels, keyboard focus, named +/- and slider state | Pure presentation/type diagnostics; new exact-head Compose/physical checks required |
| SAVE | >=48dp action-row budget, large-text/narrow scrolling, bounded two-column layout |60 geometry cases; real rendering checks are a separate target |
| PAD | Opaque high-contrast palette, visible press, unique BANK/address suffix |32 palette combinations,128 unique descriptions; pointer/voice logic unchanged |
| Step sequencer | Checked semantics, non-color active underline, larger numbers |Existing step policy preserved; new real UI test target added |
| Status | Read-only full text/source/PAD dialog from the header |Physical dialog focus/dismissal and screen-reader speech pending |

See [UI receipt](ui/UI_QUALITY_20260911.md) and [selected plan](../plans/active/ui-quality-20260911.md). No audio-source/PCM/schema/release change. Existing features and their revision-bound evidence remain in the linked historical matrix; this table only records the current UI delta.


## Android対応・統合 — 2026-09-16（0.18.0 / 30、未merge）

| 要望 | 実装 / 確認層 | 備考 |
|---|---:|---|
| Spotifyお気に入りの自動取り込み（Android） | LOCAL_PASS（JVM単体）+ エミュレータ表示確認 | Windowsと同じ同期コーディネーター（SpotifyFavoritesAutoImport）を使用。ログイン後に最大2000曲を読み、未取り込みの曲だけ対応するYouTube音源から追加。取り込み画面を閉じても継続。実Spotifyログインと実取得は未確認 |
| Spotifyで検索して追加（Android） | LOCAL_PASS（JVM単体） | Android用セッションにメタデータ検索を追加し、Windowsと共通の接続後パネル（SpotifySearchPanel）を表示。一時的な失敗では接続を保ち、認証切れのときだけ切断 |
| ドラム分離（Android） | エミュレータE2E（4GB）+ Windowsとの出力比較 | CAPTUREの「ドラムを分離」。初回にcommit固定のモデル（約166MB）を取得しSHA-256で検証。20秒の検証音を60秒で分離、ピークPSS 699MB、ステムは自動でライブラリへ。Windowsのステムと相関1.000000（1LSB差が0.05%）。Pixel実機での分離は未実施 |
| ドラム分離の共通化とストリーミング化 | LOCAL_PASS + 実モデル比較 | DSP・パイプライン・サービスをjvm-coreへ移し、1区間分の蓄積だけでoverlap-add。20秒の検証音でWindowsの出力は0.17.2と完全一致（SHA-256同一） |
| 省メモリ推論と端末メモリ確認（Android） | LOCAL測定 + 単体テスト | 最適化ありのONNX Runtimeは1区間でピーク4.7GB、グラフ最適化なしで1.0GB（出力差は最大1.6e-7）。3.5GiB未満の端末とシステムの低メモリ状態では、モデル取得前に理由を表示して中止 |
| 版数 0.18.0 (30) | LOCAL_PASS + 端末更新 | Windows・Android・iOSの版数を更新。Pixel 9aへデータを保ったまま上書き更新 |

## ローカル制作ライン — 2026-09-05〜09-14（未merge、2026-09-16統合）

| 要望 | 実装 / 確認層 | 備考 |
|---|---:|---|
| ビート統合仕上げ場（グリッド既定） | LOCAL input tests | BEAT既定グリッドに仕上げ行（ドラム追加ワンタップ/音色・仕上げ表示）と選択音の定型リズム配置を追加。ループ/再生/スクラッチ/曲構成と同一画面で完結。かんたんループ・詳細・A/Bは維持。FINISHは保存特化のまま |
| ドラム分離の内蔵（Windows・1入れる） | LOCAL_PASS + 実モデル分離観測 | HT-Demucs FTドラム特化ONNX（StemSplitio輸出・fp16・166MB・commit-pin＋SHA検証）を同梱しONNX Runtime Javaで実行。44.1k再サンプル・343980/25%重複・重み付きOLAは純粋テスト済み。10秒実音源で実モデル分離・WAV出力を確認。6分上限・進捗/中止・完了後はライブラリへ自動取込 |
| 検索→追加→取得済み音源（Windows） | LOCAL_PASS + isolated EXE launch | 98073b6。Spotify検索結果から追加し、対応YouTube音源を自動取得・検証・保存。取込中の追加予約と検索エラーを確認。実provider/実音は未確認 |
| WindowsのSpotify連携からライブラリへ自動追加 | LOCAL_PASS + isolated EXE launch | Product 3e67984。最大2000お気に入りを選択なしで順次追加。適合候補の自動選定、永続重複防止、中止/再同期、画面を閉じた後の継続、手動取込との排他を検証。現在のSpotify/YouTube実通信・実音は未確認 |
| デスクトップ連携から音源追加 | LOCAL_PASS | メニューから各取込先へ直接移動。PCファイル選択、内部ライブラリへ接続。設定説明を整理し再生操作は折り畳み。760/1100幅でポインター導線確認 |
| 個人用音源ライブラリ・YouTube取り込み | LOCAL_PASS + Windows実取得観測 | ファイル/YouTubeをdecode検証後に保存。制作保持、中止/ZIP/同名候補を検証。Android実機は未確認 |
| Spotifyお気に入りから手動追加（Android） | historical LOCAL_PASS | 従来の曲選択・候補確認を維持。9月5日のDesktop OAuth/20曲取得は過去の別観測で、Windows自動同期版のprovider証明には使わない。Android実機OAuthは未確認 |
| 明示ループ・追加レイヤー | LOCAL_PASS | 音選択と再生を分離、核を再始動せず追加/除去。既存LOOP属性で保存。自動BPM合わせ・ライブ追加時刻の記録は含まない |
| ドラム音色変更で配置保持 | LOCAL_PASS | A/B・Songを保持しUndo/保存/再読込確認。空のキット領域かつ保存済みドラム配置がない場合のみ初期リズム追加 |
| 調整音からビートへの引き継ぎ・小画面 | LOCAL input tests | この音を回してビートへ、成功後のみ遷移。旧ループが選択音を奪わない。360×520/font1.3で波形表示とスクロール後48dp操作を確認。最終gateはoutputs/loop-handoff-compact-20260905.md |
| 選択音の自動表示・S/Eダイヤル | LOCAL検証済み | 手動ズーム・精密モード・拍合わせボタンを撤去。選択S..E全体を表示。相対調整は表示中PADとライブ音声へ反映 |
| 元曲全体からチョップし直す | LOCAL検証済み | 全体と切れ目を保持。タップ位置で再生受付成功後だけCHOPへ移動。既存/手動調整した切れ目を後続captureが動かさない |
| ループ中心のビート制作 | LOCAL検証済み | ループ＋ドラムtransportの同時開始/停止、同じループ選択の維持、スクラッチ後の復帰。scratchはライブ中断/復帰で録音重ね合わせではない。詳細はoutputs/automatic-range-loop-workflow-20260905.md |
| UI監査・編集受付の整合 | LOCAL_PASS | 保存56dp、確認の期限・内容・PAD所有、busy状態の操作制限、録音/読込中kit交換の副作用防止。777通常tests＋29操作checks。詳細はoutputs/ui-audit-refinement-20260905.md。実音声・Android VM E2Eは未確認 |
| 選択音の配置シフト | LOCAL_PASS | レイヤー画面で前後1step、小節端で折返し。他PADと別A/Bを保持。Undo/Redo・既存保存経路 |
| 選択A/Bだけの配置消去 | LOCAL_PASS | A/B editorで二度押し確認。音源・別variation・Song順序を保持。transport中拒否 |

## 機能マトリクス：継承する実装と確認範囲

PR96時点の完全な機能表は [FEATURE_MATRIX_HISTORY_20260910.md](FEATURE_MATRIX_HISTORY_20260910.md) に内容を変えず保管しています。音声、録音、保存、履歴、全128 PAD、4工程の実装は継承し、その過去の device / provider / public 表示を今回のUIの検証結果には読み替えません。

新しいUIの実装・テスト範囲は上表、最新の受入状況は [PROJECT_STATE.md](PROJECT_STATE.md) と [UI検証記録](ui/UI_QUALITY_20260911.md) を参照してください。
