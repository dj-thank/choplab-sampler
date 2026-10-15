# ROADMAP — 現在地と残課題

更新: 2026-10-15（JST）。**開発の正本はmain、本書は現在の進捗・残課題の唯一の索引**です。対象はAndroid 10+・Windows・Macと、対応再開中のiPadOS。段階0〜11の実装・検証・PR統合は承認済みです。新しい配布版の公開、実機・providerの成功、人の受入は、それぞれ実際の結果で判断します。

## 現在地

- 制作・録音・編集の保全と操作UX改善 [PR239](https://github.com/dj-thank/choplab-sampler/pull/239) をmain `a3454724`へ統合済み。保存/取消、STEP下書き、録音終了通知、大文字でのミキサー、Mac梱包を改善しました。
- Android試験の古いXMLによる誤判定を防ぐ [PR240](https://github.com/dj-thank/choplab-sampler/pull/240) は [PR241](https://github.com/dj-thank/choplab-sampler/pull/241) でmain `cce5207d`へ統合済み。元コミット `3b66138a` を履歴に保持。統合後mainのWindows/Android/履歴とMac CIも成功しました。
- `main-onmp9o` の変更履歴とVOCAL試験の保存再開timeout診断を採用。Applyの診断はmainの新しい実装を保持しました。`claude/mac-status-20261007` の内容は既にmainへ含まれています。
- iPadホスト試作 `15563646` は未統合の保存候補です。アプリの入口・署名・受入がなく、`apple/build/`の生成物も含むため、製品へそのまま取り込みません。元コミットを保存タグで保全しています。
- PR/Issueの追跡を本書へ集約し、前回整理でopen PR/Issueは0、remote作業ブランチはmainのみにしました。追加改善は限定PRで検証してからmainへ戻します。未実装の機能や実機で未確認の項目は、下の残課題として引き継ぎます。

## 使う・開発する

| 目的 | 入口 |
|---|---|
| 入手・対応OS・基本操作 | [README](../README.md) |
| Macの開発起動・debug・Preview梱包 | [desktop/README](../desktop/README.md) |
| 開発環境・コマンド | [CONTRIBUTING](../CONTRIBUTING.md) |
| 検証の実行と証拠の扱い | [TESTING](TESTING.md) |
| 署名・配布・Previewと正式版の区別 | [RELEASE](RELEASE.md) |
| 変更ルール | [AGENTS](../AGENTS.md) |

## 実装と受入の状況

「統合済み」はソースの状態です。最新の配布物や全OSの実機受入を意味しません。過去の端末記録は、そのrevision・artifact・経路だけの証拠です。

| 段階 | mainの実装 | 残る受入 |
|---|---|---|
| 0–1 基盤・文書・build・CI | 統合済み。Previewのidentity/data・署名・公開面検査を分離 | 最新の対象artifactごとに検証 |
| 2 engine・4工程・制作通し | 統合済み。SOURCE/HAND独立、PAD、Undo、保存再開 | 実音・物理入力・正式切替。旧経路は保護試験と置換完了まで保持 |
| 3 取込・ライブラリ | ファイル/録音/オンライン取込、codec、Library、圧縮原音保全 | 実provider、対応範囲、完全source提供、正式署名・配布サイズ |
| 4 ビート | BPM/tap/swing、STEP/pattern、3連、LiveChop、WSOLA、Loop | 実端末のタイミング・聴感、trim済み加工音の再編集 |
| 5 ボーカル・歌詞 | LRC、take/comp/punch、区間練習、録音受理・復帰 | 物理マイク、route別補正後±5ms、聴感 |
| 6 作詞・TTS | Google提案の同意/費用/取消境界、構造歌詞、端末TTS | 実Google往復・価格/modelの確認、物理音。実送信の成功は未宣言 |
| 7 ミックス・FX | ミキサー、FX、24bit/STEM/LRC書出し、同一graph | 実音・64track性能・長時間underrun |
| 8 ピッチ補正 | 非破壊加工、原音/加工来歴、保存再開 | trim済み再編集、実音・人の品質受入 |
| 9 4パート分離 | 本番ORT経路、メモリ境界、取消、固定モデル推論 | 長尺/実曲品質、実HTTPS初回取得・修復、実機受入 |
| 10 練習coach | 練習・履歴・掛合いの制作接続 | 実音・人の体験受入 |
| 11 仕上げ・1.0 | WASAPI、初回guide、配布/NOTICE基盤 | 正式署名/公証、完全source、実route/性能/VoiceOver/TalkBack、HUMAN_GO |
| iPadOS | 共通codec・Native frameworkとSimulator基盤 | 保存候補の精査、Apple host、アプリ、署名、所有iPadでの制作通し |

## 次に取り組む残課題

Issueを閉じることと、機能の実装完了は区別します。下記は未完のままmainで追跡します。実装時はこの順で関係する契約を読み、限定PRで更新します。

| 優先 | 課題 | 完了条件・次の作業 |
|---|---|---|
| P1 | 異常終了後の資産staging回収（旧 [#207](https://github.com/dj-thank/choplab-sampler/issues/207)） | `.pending-UUID` がquotaを占有し続ける問題は未修正。単純なprefix削除をせず、プロセスをまたぐ所有/leaseを設計。生きたpublish/adopt、公開済み資産、未知ファイル、symlink、directoryを保護し、小quotaで異常終了→回収→再試行と拒否を試験 |
| P1 | trim済みpitch/stretch音の再編集 | 現状はSTALEで拒否。原音native frame/48kHz端点とWSOLA文脈を保持する設計が必要。複製/反復/trim/Undo/保存再開を含める。比例計算だけで再加工範囲を推測しない |
| P1 | 64trackの実時間性能 | Linux合成p99=0.821ms・追加割当0・PCM欠落0は確認済み。不変のbypass判定を事前計算し、16trackのLinux render CPUは2対の比較で10〜14%減。64trackの差は計測幅内で改善未確定。Mac既存p99=2.059msと対象Mac/Pixelの10分underrunは未解決 |
| P1 | Macの日常制作受入 | main由来artifactで導入/通常終了/保存再開、実音/物理マイク/システム音、VoiceOverを確認。今回Desktop Commanderはオフラインで新しい端末観測なし |
| P2 | iPadOSアプリ | 保存タグの試作を精査し、生成物を除外して限定実装へ。共通の保存/音声契約を満たすアプリと署名を用意し、所有iPadで検証 |
| P2 | 正式配布 | [RELEASE](RELEASE.md)の署名・公証・サイズ・完全source/NOTICEと実artifactのhashを確認。main更新から公開を推定しない |

### 構想形成ワークスペース（旧 #221–#227）

テーマが未定の素材・独り言から「何を表現したいか」を育てる構想です。歌詞の自動量産とは区別し、原文・AI仮説・本人の採用を分離します。**mainにはまだ実装されていません。** 元の添付候補は下のIssueに記録されており、閉鎖は追跡先の集約です。添付の存在や独立source試験をrepository/実AIの成功に読み替えません。

| 順序・旧Issue | 実装と受入条件 |
|---|---|
| IN-01 / [#221](https://github.com/dj-thank/choplab-sampler/issues/221) | 添付候補を取得・精査し、素材/仮構想/関係/採用履歴、呼出し/取消/古い応答拒否/明示適用を正式Gradleで検証。候補packageのSHA-256は `67b26a58e3b2f71aa8dfc253348e3eca01111caecaefa342019169abca765355`。`docs/inception/`は現在のmainに存在しない |
| IN-02A / [#222](https://github.com/dj-thank/choplab-sampler/issues/222) | schema15と独立したversion付きローカル保存。原文/根拠ID/判断の保存再開、atomic publish、中断/破損/未知version/容量不足/過大入力の拒否。削除は索引/cache/Undo/autosaveへ伝播し、backup/provider保持は明示 |
| IN-02B / [#223](https://github.com/dj-thank/choplab-sampler/issues/223) | 既存4工程から明示入口。AIなしの手動作成/更新/Undo、原文と根拠の往復、0〜3案の比較/編集/採用/保留。連打/新入力/停止/画面閉鎖/workspace切替で旧応答を破棄。IME/長文/幅/キーボード/空・エラーを検証 |
| IN-03 / [#224](https://github.com/dj-thank/choplab-sampler/issues/224) | provider実接続は毎回同意・選択素材・整数費用上限・期限・取消境界を守る。strict応答schema、根拠/ID/revision、未知field/過大/二重応答/timeout/429を試験。自動retryなし。公式model/価格と意味評価を実providerで確認し、合成fixtureと分離 |
| IN-04 / [#225](https://github.com/dj-thank/choplab-sampler/issues/225) | テキスト縦断後に明示マイク入力とASRへ。原音/文字起こし/訂正を分離。QUIETは無応答、JAMも本人の発話境界だけ。停止でcapture/ASR/TTS/provider取消・遅着破棄。拒否/音楽かぶり/無声/不明話者/再開を実機検証 |
| IN-05 / [#226](https://github.com/dj-thank/choplab-sampler/issues/226) | 許可workspaceだけを横断検索。類似/対比/変化/補完に根拠。傾向は仮説→本人確認→適用、編集/解除/削除。候補表示を採用扱いせず、今回の好みやAI生成文から人格を推定しない。通常/違う案と小さい評価集合で価値を確認 |
| IN-06 / [#227](https://github.com/dj-thank/choplab-sampler/issues/227) | 選択した構想snapshotと素材IDだけをプレビュー→明示適用→1Undoで歌詞/制作へ。テーマ未定→素材→呼出し→根拠→修正→採用→保存再開を通す。歌詞を作らず構想が明確になる体験も独立に受入 |

各Issueの元の受入条件はリンク先に保全しています。原文・音声・認証情報をログ/公開repositoryへ置かず、送信/音声/長期学習は別々の同意です。既存4工程とSOURCE/HAND・音声engine・制作dataを保持します。

## 検証の根拠

- PR239 head `da1270cd` とCI checkout `66fdaf9f` はtree `56a3865a`が一致。[Windows/Android/履歴](https://github.com/dj-thank/choplab-sampler/actions/runs/38377899819)、[Mac](https://github.com/dj-thank/choplab-sampler/actions/runs/38377899768)、[Apple共通](https://github.com/dj-thank/choplab-sampler/actions/runs/38377899828)の5チェックが成功。UI355件、関連Desktop13件は失敗/skip 0。実機導入ではありません。
- PR240元head `3b66138a` の必須3チェックは成功。本整理の合成後にもAndroid gate/集計39件が成功。PR241最終CIと統合後main CIも成功しました。
- 旧 [#162](https://github.com/dj-thank/choplab-sampler/issues/162) のFill後PAD操作は `4b5a23fe` で窓の終了レイヤー解放を待つ修正が統合済み。現行試験はviewport名と操作差分を診断し、7組合せの実pointer判定を保持。PR239のWindows UI全体成功を確認しました。
- PR239のMac artifact `11802760823` は392,736,557 bytes、archive SHA-256 `b5de65dc984ea8115d18445695758f57db132f1514f2d6d522fd46f1789f6946`。保存期間7日、ad-hoc Previewです。恒久的な正式配布の入口にはしません。
- 整理後main `cce5207d`（tree `ba6367ae`）の [Windows/Android/履歴](https://github.com/dj-thank/choplab-sampler/actions/runs/38595531602) と [Mac](https://github.com/dj-thank/choplab-sampler/actions/runs/38595531628) が成功。[Mac配布の改善 PR242](https://github.com/dj-thank/choplab-sampler/pull/242) はapp/manifest/installerを一緒に配布し、最終ZIPからの導入・更新・通常終了再開をCIへ追加。 初回CIではHomebrew SQLite 3.54.0への更新で既存の完全一致検査が停止。公式source/hash/blessingを確認し、1ライブラリだけ異なる新しい完全集合を追加しました。修正後のCI結果はPR242を参照。
- ミキサーのbypass事前計算は旧main `cce5207d` とmaster/pre-master・全134 stem sample・meter・停止/交換/tailのbit一致を比較。Linux amd64/JDK21、192frame、warm-up/計測各1万block、32同時clipの2対比較で16track render CPU 3.2640/3.1400s→2.8020/2.8254s、64track 4.4458/4.1180s→4.2333/4.1906s。全回割当/PCM欠落0。並行build負荷があるためwall p99や実機改善には読み替えません。
- `LOCAL_PASS → DEVICE_PASS → PROVIDER_PASS → PUBLIC_PASS → HUMAN_GO` を区別します。新しいソースや合成試験の成功を過去の実機結果へ接ぎ合わせません。

- main `a3454724` のWindows再実行では、H13録音中PAD試験がSTARTING→RECORDINGの正常遷移と競合して失敗しました（[run38584688682](https://github.com/dj-thank/choplab-sampler/actions/runs/38584688682)）。recorderのflagはcontrollerの状態通知より先にtrueになるため、試験は開始をgateで保持してSTARTINGを検査し、解除後に正確なRECORDING通知を待って同じPAD/Undo/試聴禁止を検査します。本番録音・状態比較・既存5秒上限は保持し、PR241最終headとmain `cce5207d` のWindows CIで成功を確認しました。

## 正本と固定条件

製品要件は [PRODUCT](PRODUCT.md)、画面は [DESIGN](DESIGN.md)、保存/job所有は [ARCHITECTURE](ARCHITECTURE.md)、音は [AUDIO](AUDIO.md)、任意AIは [AI](AI.md)、判断は [ADR](adr/) に置きます。

元の4工程・大きな4×4 PAD・案2の連動配置・SOURCE/HAND独立を保持します。5画面カードへの全面刷新は採用しません。元音・stereo・start-inclusive/end-exclusive frame・Undo・既存dataを保護し、renderで割当/lock/I/Oを行いません。開発者の担当や古いセッション手順を現在の利用者指示より優先しません。

## 要求を落とさない対応表

| 要求 | 正本・担当段階 | 受入の焦点 |
|---|---|---|
| 日本語「おとひろい」/英語「Earth Song」、内部ChopLab | [DESIGN](DESIGN.md) / 1C–2B | resource/実APK/Windows/title、2段label廃止、Preview識別 |
| Android10+・Windows・Mac、iPadOS対応再開 | [ADR8](adr/ADR-0008-mac-function-parity.md)・[ADR10](adr/ADR-0010-ipad-native-support.md) | OS別の実装・配布・実機受入を区別 |
| 元の4工程とPAD/自由配置の編集構成 | [PRODUCT](PRODUCT.md)・DESIGN / 2A–2B | stateを伴う制作通し、狭画面/横/font1.3/2.0 |
| 既存のライブchop/Undo/合成drum/安全策 | PRODUCT・[ARCHITECTURE](ARCHITECTURE.md) / 2B-2 | 機能別の移植・意図的廃止・未完をPRへ列挙 |
| 128PADをA–H×16へ、役割/名前/色自由 | PRODUCT・DESIGN / 2B–4 | selection/identity、保存、音数、scroll/input |
| 統一DSP・高音質・元音保持 | [AUDIO](AUDIO.md) / 2A–7 | 同一命令/seed/block一致、左右/headroom、帯域/alias/click/tail/ディザ |
| 1–8小節と表示16/32/64列の分離 | PRODUCT・AUDIO / 4 | 4/4・16分8小節=128step、tick/3連/録音補正 |
| 歌詞を見て自分で歌う、take/comp/punch | PRODUCT・AUDIO・DESIGN / 5 | mic/伴奏clock、同期行、count-in、route補正、非破壊編集 |
| 作詞、TTSをbeatへ、練習相手 | [AI](AI.md) / 6・10 | 提案/試聴/適用/Undo、構造/かな検証、timingとscore根拠 |
| mixer/FX/pitch補正/4stem | AUDIO / 7–9 | mix graph/出力点、tail、bypass品質、実model/長尺メモリ |
| NEXTの現行schemaと旧音声救出、autosave保持 | ARCHITECTURE / 2A–2B | 1–7実fixtureから開始、8/9は別fixture合格時のみ。atomic save/3世代/Undo資産、取消・容量不足・再試行 |
| Spotify/YouTubeを両OSへ残し改善 | PRODUCT・[PRIVACY](../PRIVACY.md) / 3 | 選択だけ取込、候補確認、metadata≠音声、実scope/mode/terms、失敗時local継続 |
| 正式arm64 APK≤50MB | [RELEASE](RELEASE.md) / 3・11 | release/R8で計測。emulator ABIは別。初回DL/cacheを別欄 |
| Windows初期zip≤200MB、最終≤150MB目標 | RELEASE / 3・11 | 同じ圧縮方式/variantで比較、展開/初回DL/cacheを別計測。音質を下げて達成しない |
| 短いルール、PR/CIに根拠、single writer | [ADR7](adr/ADR-0007-concise-development-governance.md)・[TESTING](TESTING.md) / 全段階 | 一つのcurrent index、適切な検証、fresh readback、機密混入0 |
| 保留候補: MIDI/DDJ-200/歌唱AI/声複製/ASR/store/cloud | PRODUCT / 今回対象外 | adapterの余地だけを残し、完成範囲へ混ぜない |

## 整理と履歴の入口

過去の長い検証記録は[整理直前のROADMAP](https://github.com/dj-thank/choplab-sampler/blob/a34547243b184439759a5e9ff30902c6a0a846f1/docs/ROADMAP.md)に全て残しています。古い「次の一手」やCI待ちは当時の記録で、現在の作業指示にはしません。完了PRの議論・CIも削除しません。

| 保存した枝 | 保存タグ | 扱い |
|---|---|---|
| `claude/ipad-app-20261007` | `archive/20261014/claude/ipad-app-20261007` (`15563646`) | 未完成のApple hostと生成物を含む元treeを保存。main未統合 |
| `claude/mac-status-20261007` | `archive/20261014/claude/mac-status-20261007` (`020893b3`) | mainに包含済み |
| `main-onmp9o` | `archive/20261014/main-onmp9o` (`cce029ad`) | 変更履歴/保存再開診断を採用。Apply診断は新しいmainを保持 |
| `codex/fix-stale-instrumentation-results-20261013` | `archive/20261014/codex/fix-stale-instrumentation-results-20261013` (`3b66138a`) | 本整理へ元履歴ごと合成 |

タグは保存点で、開発ブランチや進捗の正本ではありません。ローカルの未コミットsource・制作data・別端末のworktreeはこの整理では削除しません。必要ならタグから独立branchを作って復元できます。今回の整理のrollbackは統合PRのrevertです。

0.18.0以前は [archive/pre-rebuild-v0.18.0](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb) を参照してください。古いiOS配布物は現行iPadアプリではありません。
