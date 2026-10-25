# 構想形成ワークスペース

旧 #221–#227 の要件からの新規実装。記録された添付ZIPは取得していない。保全タグ `archive/consolidation-20261006/codex/inception-workspace-foundation-20261004` は PR220 の基点 `fd6990ff` を指し、構想のsourceは含まれていなかった。進捗・残る受入の唯一の索引は [ROADMAP](../ROADMAP.md)。本書は実装の契約を説明する。

## 手動の縦断

既存の4工程のヘッダーにある「構想」から「構想を育てる」補助窓を開く。独立したノートに素材を残し、選択した根拠IDから0〜3案を作成する。上部に、いまの中心・大事にしたい感覚・考え中の方向・未決事項を手動で残せる。原文は不変、訂正と過去の訂正は別field。素材／ASIDE／UNKNOWNを明示的に再分類できる。原文への参照、編集、採用、保留、不採用理由、判断時点の案snapshotを保持する。編集は保存成功後に公開し、直前の編集は1回Undoできる。確定済みノートは画面の閉鎖・再起動後にも再開する。

採用した案だけを歌詞または制作タイトルへプレビューする。snapshotは選択案、その根拠素材、ノートrevision、制作DocumentStateに束縛する。歌詞への適用は案の文章を既存歌詞の末尾へ1行4拍で追加する明示操作で、AIによる歌詞生成ではない。制作への適用はタイトルだけを置換する。それぞれ既存Studioの1編集／1Undoを使用し、SOURCE/HAND、PAD、音源、配置、テンポを変更しない。プレビュー後のノート／制作変更は適用を拒否する。

## 保存と削除

`InceptionWorkspace` のversion 1は制作schema15/16から独立する。Android/Desktopの既存隔離profileの `inception-v1/` だけに保存し、制作archiveへ自動混入しない。1文書256KiB、32ノート、総容量8MiB（旧版と書込中の両方を含む）、128素材、各素材8,192文字、3案、256判断の上限を持つ。

JVM storeはプロセス内排他とOS file lock、revision CASを組み合わせる。UTF-8と重複key／未知field／型／深さ／参照整合性を検証する。payloadのSHA-256付き封筒を書き、flush・読戻し検証の後にatomic moveで公開する。atomic move非対応や容量不足を成功扱いせず、既存版を保持する。空き容量はAndroidでも利用できる保存先の `File.usableSpace` の実値を使い、0や不足を拒否する。電源断に対するdirectory fsyncの保証はしない。中断で残った同ノートの完全な一時版は検証して回収し、不完全または未知の一時版は保全して失敗を表示する。

素材の削除は参照する案・判断snapshot・関係・仮説も削除し、controllerのUndo、検索結果、プレビュー、provider候補を破棄する。保存storeに別のautosave世代・検索cacheを作らない。別windowの古revisionは保存できない。ノート全体の削除もCASと同じ排他を使用する。明示的に制作へコピーした歌詞・タイトルは独立した制作編集であり、その制作の履歴・archiveをノート削除から書き換えない。削除の確認には、制作側のコピー、外部バックアップと送信先の保持分をこの操作では消せないことを表示する。未知ファイルは自動で消さない。

## 根拠検索と仮説

検索は利用者がこの画面で許可したノートに限定した、決定的な文字列検索。結果にはノートID、素材ID、revision、実際の一致部分を付ける。古revisionや許可解除後の結果は開けない。類似・対比・変化・補完は利用者が選ぶ関係の種類で、自動の性格推定や類似度スコアは作らない。選択した2素材に説明付きの関係を残せる。

仮説は選択素材の根拠を持ち、初期状態は未確認。本人確認・編集・確認解除・削除を別操作にする。確認した仮説からだけ、明示的に仮の構想を作れる。確認済みであっても構想の採用や制作への適用には別操作が必要。長期人格・好みの推定や他アプリへの適用は行わない。

## 任意のprovider

Google adapterは既存の固定HTTPS origin・redirect禁止・自動retry禁止transportと、整数nano通貨で計算する `GoogleLyricSession` admissionを再利用する。専用のsessionを使用し既存歌詞dialogと承認を共有しない。通常hostは未確認から始まり、キー入力や同意checkboxだけでは送信できない。ownerが現在のopaque bindingへ用途・アカウント・model・価格・system/schemaを含むinputとthinkingを含むoutput上限・予算・期限を確認した `ReviewedGoogleUse` をinstallした場合だけ、表示上限への同意後に1回のpermitを消費する。

確認する上限は本構想protocolの全入力・出力に対する上限であり、歌詞protocolの条件を流用してはならない。ネットワークを実行する前にも同じkey/modelと期限を検証する。UIのキーはsessionメモリだけで保持し、閉鎖時に消去する。モデル・価格・無料枠を既定で仮定せず、実送信と最新規約の確認はownerによる別の受入である。

送信するのはその回に選択した素材の現在の文章・分類・根拠ID、request ID、ノートrevision、通常／別角度の指定だけ。原音・原文履歴・未選択素材・制作・検索結果・仮説は自動送信しない。0〜3案・根拠付き関係・最大1質問のstrict schema、ID・revision・選択根拠を検証する。結果は未採用の候補であり、仮案として残す／本人が採用する／制作へ適用する、を分離する。新入力・選択変更・閉鎖・ノート切替・host文脈変更・停止は進行中jobとpreviewを無効にする。二重起動、古い応答、二重HTTP開始、未知field、過大応答、timeout、429を拒否し、自動で再送しない。送信後の取消／失敗は費用不明を保持する。原文・key・raw requestをlogへ出さない。

## 音声の範囲

Android API31以降で実際に利用可能な端末内SpeechRecognizerがある場合のみ、明示マイク許可から1発話の認識を開始する。cloud認識へのfallbackやmodel自動downloadはしない。QUIETは返答せず、JAMも発話の終わりに文字起こしを置くだけで、AI相談には本人による別の送信と同意が必要。停止・画面閉鎖・割込・古い世代でcapture/認識を取消し、遅着を捨てる。録音中や制作の排他中は新しい入力を拒否する。認識の失敗や無声を空の成功素材にしない。

このAndroid APIは原音を返さないため、`audioRef=null` の文字起こしとして保存し、訂正と分ける。原音との対応を保った録音、伴奏かぶり／不明話者の判定、JAMの自動応答、Desktopの実ASRは未実装である。原音保存が完了したと表示しない。物理マイク、拒否、無声、音楽かぶり、再開、TalkBack/IME、実Google往復と意味品質はLOCALの合成試験から推定しない。

## 検証入口

- core: `InceptionTest` — strict JSON、参照、0/3案、原文と訂正、Undo、削除、許可検索、応答schema。
- jvm: `FileInceptionStoreTest` / `GeminiInceptionProviderTest` — atomic失敗・CAS競合・hash破損・quota・中断残骸と、合成transportによる送信境界。
- ui: `InceptionControllerTest` / `InceptionPanelTest` —編集から保存・採用・プレビュー・1Undo、古応答・取消・429・失敗、実Composeの入力と4工程入口、日英390px文字2倍。
- desktop: `NextInceptionTest` —実Studio／保存adapter、歌詞・タイトル適用、1Undo、既存制作のarchiveとautosave再開。

これらの通過はLOCALのsoftware検証。実端末、実provider、人による体験の受入とは区別する。

## ホスト接続の契約

共通APIは `core/.../inception/Inception.kt` の `InceptionStore` と `ui/.../inception/InceptionController.kt` にある。Apple固有adapterはこの変更に含まない。未接続hostでは `ContinuousEditor` の `inception` は既定の `null` で、入口を表示しない。

- `list(): List<InceptionWorkspace>` と `load(id): InceptionWorkspace?` は検証済みの独立snapshotを返す。
- `save(document, expectedRevision)` は `null` の場合だけ新規作成し、既存文書はrevision一致・次revisionでatomic置換する。競合は `InceptionException(STALE)`、破損・未知version・容量不足もtyped失敗にする。失敗時には旧版を保持する。
- `delete(id, expectedRevision)` は同じCAS境界を持つ。保存本体、adapterが所有する中断一時版、cacheや別世代を残さず、未知のbytesを削除したことにしない。coreの `InceptionJson` で同じstrict形式を検証できる。

hostは `InceptionController(store, scope, studio, google, providerFactory, voice, canApply, canListen)` を1つ所有する。`Studio` のないhostは `currentDocument` と `applyIntent(Intent, expectedRevision)` を渡す主constructorを使う。`canApply` は録音・閉鎖・制作排他を反映し、`canListen` は既存マイク所有者との競合も反映する。provider未接続は `providerFactory=null`、ASR未接続は `UnavailableInceptionVoice` とし、手動テキスト縦断は利用できる。

`ContinuousEditor(..., inception=controller)` が4工程の入口、表示と文書／工程／録音変化時の `hostChanged()` を接続する。hostの全停止（メニューとdialog shortcutを含む）、音声割込・background移行では `stop()` を呼ぶ。破棄時にはscopeをcancelする前に `close()` を完了させ、認識・通信・鍵を破棄する。外部送信を有効にする場合だけ、hostの実時計を持つ専用 `GoogleLyricSession` と、その回の選択素材・protocol上界について確認したreviewを使う。

JVM実装 `FileInceptionStore` は参考になるが、Apple adapterのatomic保存・実機のファイル保護・容量・中断・再開は別途そのhostで検証する。JVMの通過をApple保存やiPad UIの受入へ読み替えない。
