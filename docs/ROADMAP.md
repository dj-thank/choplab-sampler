# 再構築 ROADMAP — 唯一の進捗・受入索引

更新: 2026-09-27。対象はおとひろい / Earth SongのAndroid 10+・Windows、および全制作機能を使うMac（[ADR8](adr/ADR-0008-mac-function-parity.md)）。**段階0〜11の実装、必要な検証、PR統合、仕上げまで承認済み**です。レビュー案にあった「レビューのみ」「各PRで再承認」は [ADR7](adr/ADR-0007-concise-development-governance.md) で置き換えます。device/providerの実観測と人間の音質・操作感の受入は、実施結果が得られるまで未確認のままです。

## UIの固定条件

共有タスク「Choplab UIを改善」で選んだ案2の改訂版を維持する。8月版の4工程・大きなPAD・濃い波形面を土台に、右側の自由配置、原曲の連携、試聴音量、可変幅を組み込む。5画面カード案と9月21日の素材棚中心版は採用しない。詳しくは [DESIGN](DESIGN.md)。

## 現在地・選択中の作業

- 基準: [PR101](https://github.com/dj-thank/choplab-sampler/pull/101) のmerge `2866683a5118681cf518ef47e29cac8baf882edb`、保存tag `archive/pre-rebuild-v0.18.0`。0.18.0/build30のAndroid/Windowsを出発点にする。後続の別ローカルeditor/schema8/9やDDJ-200を混ぜない。
- sourceの現状: 本番は `app / desktop / shared / jvm-core`、4工程、schema7 writer / schemas1–7 reader。独立した `engine / core / jvm` とschema10は導入済みで、4工程UI/本番hostへの全面接続は未完。AI・4stem等は下表で受入が完了するまでは計画。
- 選択中: **2A/2Bの4工程UI接続と、Mac全機能対応・取込速度/UXの検証**。1Aは[PR106](https://github.com/dj-thank/choplab-sampler/pull/106)、1Bは[PR107](https://github.com/dj-thank/choplab-sampler/pull/107)で各3件のCI成功後にmainへ統合済み。1Cも[PR110](https://github.com/dj-thank/choplab-sampler/pull/110)で3件のCI成功後、mainへ統合済み。新engineは独立moduleで、本番hostは従来のものを維持する。
- 1Cローカル候補: 専用ID/署名の非debuggable Preview APK、別profileのWindows Previewを作成。APKのpackage/version/署名指紋、Windowsの隔離起動・AWT応答・全所有processの正常終了を確認。実機音声/マイク/Pixel/Humanは未確認。
- engineは[PR112](https://github.com/dj-thank/choplab-sampler/pull/112)で3件のCI成功後にmainへ統合済み。core/JVMはschema10、frame固定配置、Undo、atomic save、3世代autosave、PCM cache、streaming WAV、独立原曲と共通output driverを追加。
- core/JVMは[PR113](https://github.com/dj-thank/choplab-sampler/pull/113)でmain統合済み。Mac取込高速化/UXは[PR118](https://github.com/dj-thank/choplab-sampler/pull/118)のhead `749132d`で必須CI3件成功、merge `d254e3c`。Mac host対応は[PR117](https://github.com/dj-thank/choplab-sampler/pull/117)の最新head `544f2c7`で必須CI3件成功、merge `29c80b2`。
- [PR114](https://github.com/dj-thank/choplab-sampler/pull/114) はmerge `02ac506` でmainへ統合済み。4工程UIを新しい編集・再生・保存へ接続。原曲をPAD選択で置換せず、曲全体と試聴音量を分ける。Windows専用Preview app-imageは隔離profile/無音adapterで起動・応答・正常終了を確認。レビュー指摘（96kHzの長さ0 clipでの起動不能、slider操作ごとのUndo、曲末からの再生、自動保存失敗時に閉じられない、tick丸め、loop切替によるPAD modeの上書き、操作順の入れ替わり、起動失敗時の解放、出力停止直後の編集拒否）を修正し回帰テストを追加。main（Mac host、[PR116](https://github.com/dj-thank/choplab-sampler/pull/116)/[PR117](https://github.com/dj-thank/choplab-sampler/pull/117)）との統合後は、MacのCommand-Qも同じ自動保存付きの終了経路を通す（Mac実機では未確認）。engine58/UI23テスト、desktop全体268件（Mac実録音1件skip、うちNextBackend13件）、Android Preview Kotlin/依存DEX、core27/JVM33テストが成功。
- Android NEXT: [PR124](https://github.com/dj-thank/choplab-sampler/pull/124) のhead `c27f005` で必須CI3件成功（同じcommitで2回）、merge `f69ecd6` でmainへ統合済み。debug/Previewだけに2つ目の入口「おとひろい NEXT」/「Earth Song NEXT」を追加し、同じ4工程の編集画面を共通の `EditorBackend`、AudioTrack出力、SAFのファイル窓口（取込・制作の開く/保存・WAV書出し）へ接続。WAV以外は既存のMediaCodec decoderで16bit WAVにしてから取り込む。新エンジンが常駐できる349秒を超える音源は取込前に案内して止める。曲/原曲の再生中だけ音声フォーカスを取り、フォーカス喪失・イヤホン抜けで停止（自動再開しない）。画面を離れたら停止・出力解放・自動保存し、戻ったら出力を開き直す。経路変更で外れた出力は表示中に数回つなぎ直す。戻る操作は最終自動保存に失敗したら確認する。release APKには入れない
- 次の一手: Macの機能別受入と取り込み速度/UXを確認する。Android NEXTの実機（Pixel）での制作通し・音・遅延・CPU/underrunを測る。残るonline/長尺prefetchを移行する（ドラム・PAD音色・原曲キー・ライブチョップ・声の録音・スクラッチ・加工PADの配置は接続済み）。既定の本番入口は保持。実機・実音・Human受入は未完。

Rollbackは対象commitのrevert/前のartifactへの復帰を基本とし、利用者data・dirty checkoutをreset/cleanしない。範囲外write、private data混入、未移植の保護削除、required check失敗、実行所有の衝突を検出したら、その依存する操作だけを止めてここへ理由と次の一手を残す。

## 段階と受入

「計画」は実装済みを意味しません。PR総数は固定せず、独立して検証できる単位へ分けます。

| 段階 | 状態 | 完成状態と必須の確認 |
|---|---|---|
| 0 出発点 | 完了 | PR101を通常mergeし保存tagを固定。cleanな専用worktree。#92–97は統合済み、#103は同一patchを照合してclose、#102は見送り・branch保持。source保存点はuser audio/未commit作業のbackupではない |
| 1A 文書・iOS整理 | main統合済み / PR106 | 9文書と短いAGENTSへ統合。ADR1–5/research保存、ADR6/7、iOS/referenceと旧記録のtracked allowlist整理。参照切れ/iOS依存/必須CIを確認。appの音・編集挙動は変更しない |
| 1B build・依存 | main統合済み / PR107 | version一元管理を維持しcatalog化。JDK/SDK検出と非破壊setup、依存更新を一組ずつ検証。Android/JVM/Windowsのtest/lint/packageと実行可能性 |
| 1C CI・Preview | main統合済み / PR110 | push重複除去、既存required check名、source/history/artifact検査、署名/identity。Previewのpackage/authority/OAuth/data/鍵を分離。ja/en名、旧版併存、Windows実package起動 |
| 2A engine・core・design | 独立engine導入・後続接続待ち | 1素材→1PAD→step→render→WAV spikeを先に測定。DSP/finite memory成功後にschema10/edit/saveへ拡張。fake portの制作通し、指定された4工程UIの比較/PNG、新旧音A/B。旧app回帰なし |
| 2B-1 NEXTの制作通し | 接続中（Windows Linked Preview、Android NEXT入口） | Previewだけに実hostを配線。取込→chop→PAD→step→再生→保存/再開→書出し/Undoを両OSで通す。Java Sound/AudioTrack driverと診断、Pixel CPU/underrunを測る |
| 2B-2 移植・切替 | 移行中（合成ドラム・PAD音色・原曲キー・ライブチョップ・声の録音・スクラッチ・加工PADの配置接続済み） | 残機能と音声救出/復旧を機能表で合格させ、別削除PRで旧codeを外す。kit/loop/choke/record/interrupt/route loss/共有/.choplib/アクセス不能資産。画像だけで実音等を合格にしない |
| 3 取込・サイズ | 計画 | 認証/候補/抽出→demux→decode spike後に選択式UI。元bytes/曲情報/品質/公式性/一致度。Android arm64+R8、Windows native同梱削減。利用条件と実provider、codec/取得/照合評価 |
| 4 ビート | 計画 | BPM入力/tap/検出、key候補、metronome/count-in、1–8小節、velocity/3連/swing/note repeat/quantize/録音、自由なpattern/repeat、WSOLA、位置scratch+CUT。録音1回1Undo、frame timing一致 |
| 5 ボーカル・歌詞 | 計画 | count-in/pre-roll/punch/短crossfade、take/非破壊comp、同期歌詞/LRC、loop/slow練習。route別往復補正、drift/割込み/変更、指定条件の補正後±5ms目標と測定誤差 |
| 6 作詞・TTS | 計画 | Google経路を一つ完結→契約test付きでmulti-provider。歌詞schema/FlowPlanner/行TTS/word highlight/掛合い。キー/同意/usage、offline/429/cancel/遅着/課金不明、端末TTS |
| 7 ミックス・FX | 計画 | source/bank/stem/vocal/guide/click track、gain/pan/mute/solo/meter、EQ/filter/comp/delay/reverb/master。latency/tail/loop。24bit/16bitディザ、stems/LRC/長さ、同一mix graph |
| 8 ピッチ補正 | 計画 | YIN/PSOLA等を単音voiceで比較。key/scale/retune/vibrato、無声/低信頼bypass、非破壊A/B。測定と人間の試聴が採用条件 |
| 9 4パート分離 | 計画 | drums/bass/other/vocalsを1推論から逐次出力、acapella/ボーカル抜き。commit/hash固定DL、shape/order、空きRAM/ORT/長尺peak/cancel、4資産transactionを両OSで確認 |
| 10 練習coach | 計画 | local timing/pitch指標、take履歴、苦手行反復、日本語説明。reference有無・rap・低信頼を区別し、未実装ASRや根拠のない正解率を作らない |
| 11 仕上げ・1.0 | 計画 | WASAPI event出力/入力/loopback、format/device/COM失敗とfallback。installer/更新/初回guide、起動/RAM/電池、TalkBack/keyboard、署名と配布readback、対象routeの実音とHuman受入 |

## 要求を落とさない対応表

| 要求 | 正本・担当段階 | 受入の焦点 |
|---|---|---|
| 日本語「おとひろい」/英語「Earth Song」、内部ChopLab | [DESIGN](DESIGN.md) / 1C–2B | resource/実APK/Windows/title、2段label廃止、Preview識別 |
| Android10+とWindows、iOS廃止 | [ADR6](adr/ADR-0006-android-windows-focus.md) / 1A | target/workflow/scriptの残存参照、archive復元経路 |
| 元の4工程とPAD/自由配置の編集構成 | [PRODUCT](PRODUCT.md)・DESIGN / 2A–2B | stateを伴う制作通し、狭画面/横/font1.3/2.0 |
| 既存のライブchop/Undo/合成drum/安全策 | PRODUCT・[ARCHITECTURE](ARCHITECTURE.md) / 2B-2 | 機能別の移植・意図的廃止・未完をPRへ列挙 |
| 128PADをA–H×16へ、役割/名前/色自由 | PRODUCT・DESIGN / 2B–4 | selection/identity、保存、音数、scroll/input |
| 統一DSP・高音質・元音保持 | [AUDIO](AUDIO.md) / 2A–7 | 同一命令/seed/block一致、左右/headroom、帯域/alias/click/tail/ディザ |
| 1–8小節と表示16/32/64列の分離 | PRODUCT・AUDIO / 4 | 4/4・16分8小節=128step、tick/3連/録音補正 |
| 歌詞を見て自分で歌う、take/comp/punch | PRODUCT・AUDIO・DESIGN / 5 | mic/伴奏clock、同期行、count-in、route補正、非破壊編集 |
| 作詞、TTSをbeatへ、練習相手 | [AI](AI.md) / 6・10 | 提案/試聴/適用/Undo、構造/かな検証、timingとscore根拠 |
| mixer/FX/pitch補正/4stem | AUDIO / 7–9 | mix graph/出力点、tail、bypass品質、実model/長尺メモリ |
| schema10と旧音声救出、autosave保持 | ARCHITECTURE / 2A–2B | 1–7実fixtureから開始、8/9は別fixture合格時のみ。atomic save/3世代/Undo資産、取消・容量不足・再試行 |
| Spotify/YouTubeを両OSへ残し改善 | PRODUCT・[PRIVACY](../PRIVACY.md) / 3 | 選択だけ取込、候補確認、metadata≠音声、実scope/mode/terms、失敗時local継続 |
| 正式arm64 APK≤50MB | [RELEASE](RELEASE.md) / 3・11 | release/R8で計測。emulator ABIは別。初回DL/cacheを別欄 |
| Windows初期zip≤200MB、最終≤150MB目標 | RELEASE / 3・11 | 同じ圧縮方式/variantで比較、展開/初回DL/cacheを別計測。音質を下げて達成しない |
| 短いルール、PR/CIに根拠、single writer | [ADR7](adr/ADR-0007-concise-development-governance.md)・[TESTING](TESTING.md) / 全段階 | 一つのcurrent index、適切な検証、fresh readback、機密混入0 |
| 保留候補: MIDI/DDJ-200/歌唱AI/声複製/ASR/store/cloud | PRODUCT / 今回対象外 | adapterの余地だけを残し、完成範囲へ混ぜない |

## 次段階で固定する詳細

**1B / 1C:** JDK21＋Java/Kotlin target17は候補。Kotlin/CMP/AGP/Gradleの版は採用時に公式互換表と小さなbuildで一組ずつ確認する。既存SDK/local.propertiesを上書きしない。scanner簡素化は検出case/上限/negative fixtureとrelease署名/version/hashを保持してから行う。必須check名 `verify` / `Test and package EXE` / `History scan and dependency SBOM` を移行期に維持し、Windows固有testとpackageはWindowsで走らせる。

**2A:** 初めは1素材1PADのspike。ControlRing/EventRing/LiveReadout、32voice＋fade等の予算、resident/prefetch/cache miss、命令late/full/Stop再同期を定義する。[音の9試験](AUDIO.md) をfixture条件ごと固定し、core/persistenceの拡張前に有限メモリと性能を確認。指定4工程の画面はAndroid縦/横/Windows/font1.3/2.0でPNGを出す。pure Kotlin性能が成立しなければ全面配線前にADRで設計を戻す。

**2B:** NEXTはPreview専用の実装選択と専用dataを持つ。旧online取込は段階3までadapterで包む。音声/Undo/保存の一連が通ってから残存機能を移し、保護testと旧codeを最後に削除する。Androidは実native rate/format/buffer/timestamp、Windowsは連続Java Soundを確認。実機CPU条件は2B-1で初めて判定する。

**3:** NewPipeExtractorを主候補としてAPI29/desugaring/R8、取得source/依存hash/licenseとdemux/decodeを別にspike。Windowsのyt-dlp＋JS runtimeは必要時DLの予備候補。checksumだけで信頼起点を作らない。Opus pre-skip/end trim、codec delay、rate/stereo/seek/長尺/破損、MP3/AAC/FLAC/ALACは実codec fixtureで対応を表示。旧工具/Spotify再生窓は代替成功後に削除する。

許諾・利用条件を満たす日英20素材で取得18以上はsmoke目標。既知PCMから生成したcodec fixtureでdecoder忠実度を別評価し、元にない19kHz成分を要求しない。Spotify照合は正解付き50件で採用/誤採用/保留/取りこぼし、precision95%目標とcoverageを併記し、一般性能95%の証明とはしない。85/60等の一致閾値は未校正の候補。APIのmode/scope/read endpointとlibrary更新endpointを区別し、現行の公式policy/実応答を確認する。

**5–10:** 各段階の開始時にAUDIO/AIの要件から小さな通しscenarioと失敗経路を選ぶ。4stem候補は `StemSplitio/htdemucs-onnx` のモデル。採用時にcommit/hash/license、`[1,4,2,343980]` の出力順、float32 I/O、streamingと4資産確定を確認。fp16保存weightsや総RAM4GBだけで推論余裕を保証しない。

## 証拠と残る判断

各PRで「revision / 実行commandとCI link / artifact bytes / 実際に確認したOS・route・scope / 未確認 / 次の一手」を最小限残します。全体check名だけで対象module全通過とせず、必要taskを列挙します。数値の目標、source上の構造、過去のreceipt、新しい測定を区別します。

NEXT再構築の実音・実マイク・provider・Human GOは未受入です。現行desktopのMac実マイク・YouTube・システム音の測定は下記で個別に記録し、NEXTへ昇格しません。音質A/B、デザイン案、NEXTの両OS制作通し、TalkBack/操作感などの確認用成果は実装に合わせて提示します。人間への確認は最大5項目にし、未回答を承認や合格へ変換しません。既存の包括的実装/統合許可は保持します。

## Macローカル利用の統合受入 — 2026-09-27

今回の完了条件は、このMacで取り込み→チョップ/PAD/ビート→録音→保存/再開→WAV書出しを実際に使えること。GitHub統合とCI成功は途中の確認とする。担当root、起点main `1734fef`（PR131統合後）、対象はMacの同梱Preview/NEXTと `scripts/run_mac_next_acceptance.py`・Mac CI。Rollbackは対象PRのrevertと以前のアプリへ戻すこと。利用者profile・既存素材は変更しない。

- PR131のhead `0a4121d` は必須CI3件成功後にmerge `1734fef`。coroutines/serialization/ONNX更新を取り込み、重複するPR111/121はclose。Compose 1.12はdesktop梱包の重複JARで停止するため1.11.1を維持する。PR109はDependabotがPR132へ置き換えたため、新しいPRを統合対象とする。
- 同revisionのMac/JDK21.0.12.1でengine77/core41/JVM84/shared desktop157・Android host157/jvm-core199/desktop275（システム録音1skip）/新UI55/旧UI42、失敗0。Gradleの変更なしtaskは既存の検証出力を再利用。policy320件成功。描画の1万block測定は割当0、192frame/48kHzでp99がblock時間の8.35%（配置＋PAD＋原曲）、5.47%（32voice＋fade）。Mac合成負荷の測定で、Pixelや10分間のunderrun受入ではない。
- NEXT同梱195ファイル・169,020,346 bytesのmanifestを照合。実launcherの1440×870ウィンドウ応答と正常終了を確認。同梱Javaの制作通しはschema10、16/24bit・230,400frameの出力が成功。新しい受入runnerは合成素材を復元して通常終了・再起動を2回行い、自動保存の編集内容と全音源hashの一致を検査する。CIも強制終了だけの確認からこの経路へ変更する。
- 既存Preview同梱238ファイル・521,144,600 bytesもmanifest一致。同梱codecでFLAC/ALAC/AIFF/MP3/AAC/Ogg/Opus/MP4/WebMと590秒FLACを確認。長尺FLACのdecode401ms（単回・合成素材、ネットワーク取得の速度ではない）。FLACを指定した実launcherの起動・通常終了・schema7 autosave生成も成功。
- このMacのNEXT Java Sound adapterで48kHz/stereo/PCM16を14,400frame出力し、実マイクから48kHzで12,288frameの非ゼロ音声を読み取った。マイクのraw音声は保存しない。これは機器adapterの観測であり、画面上での歌の重ね録り・声と曲のずれ・人の聴感受入とは別。
- 既存Previewの同梱ScreenCaptureKit helperで48kHz/2ch・301,440frameを取得。合成確認音の親Java音振幅6.18/外部音692.25、自己音抑制-40.98dB、正常終了を確認（`SYSTEM_CAPTURE_PARENT_EXCLUSION_PASS`）。raw録音は保存せず、NEXTの端末音録音UIの実装済み扱いにはしない。

残り: NEXTはWAV/常駐音声上限に制限され、圧縮音源・online・端末音録音・ライブラリ/分離等が既存Preview側に残る。元の全機能要件を満たした扱いにせず、このMacで使う入口と実装を揃える。Spotifyの実同意/API、native file dialogの手操作、声と曲の遅延、人の聴感/操作感も未完。公開Client ID設定は引き継ぎ、値やtokenはsourceへ記録しない。

## Mac全機能対応とWindows正本の照合 — 2026-09-26

担当はroot。起点はmain `29c80b2`、変更範囲はdesktop取込adapter・共有取込controller・既存契約文書。圧縮音源の登録時の検証済みPCMを最大64MiB/1素材だけ保持して同じbytesの初回使用へ渡し、二重decodeを除く。保持上限外も従来品質でdecodeする。複数取込は件数・失敗ファイル・再試行を表示し、成功分を保持。URLのscheme省略を補い共有パラメーターを正規化。RollbackはPRのrevert、利用者音源と保存形式は変更しない。

WindowsへSSHで入り、私有SSOTの現行ポインタ、origin、HEAD、dirty状態と通常起動先をreadbackした。指定先は同じoriginの再構築候補 `a5c6343`（tracked clean）、UI用checkoutは `d7f2a55`。通常起動先は別の0.17.2候補 `53655c46e0a1`。Android用worktreeと外側のSSOT台帳には未コミット変更があり保持した。Windowsの古い候補やインストール済みアプリをGitHub mainの成功根拠にしない。既存SSOTの入口を参照し、私有パスや台帳内容を公開repoへ複製しない。

| 機能/受入 | 今回の確認 | 残る確認 |
|---|---|---|
| ファイル/ライブラリ取込・書出し | Macのnativeパネルから合成FLACを選択、元bytes一致のlibrary保存と原曲読込成功。CLIの`.choplib`書出しも成功。重複decode削減、一部失敗/取消/再試行の回帰test成功 | 他形式・長尺の実操作 |
| YouTube | ユーザー指定の1素材をMacで取得→48kHz stereo、12,276,298frames→保存後読込成功 | 他のprovider条件、失効/制限時の実応答、公開配布適合 |
| 4工程/PAD/ループ/保存導線 | `544f2c7`のMacでdesktop249件（実録音1skip）/JVM196件/UI4件/操作38件成功 | nativeの⌘Sで514,306bytesのschema7制作を保存（stereo 5,760,000frames、ZIP CRC正常）、⌘Qでexit0。再読込後の全PCM・chop位置・14step保持とbeat WAV出力も成功。人が聴く制作通し・操作感は未確認 |
| マイク/システム音/分離 | PR117でadapter統合、Swift build・fake helperの途中終了/権限待ち/PCM分割を確認。追加で0.5秒の合成stereoを実モデルで分離し、9秒でWAV生成 | `e28307a`の実マイクは48kHz mono/17,920frames、出力lineは48kHz stereo/4,800frames書込成功（録音は検証後削除）。聴感、親Java音声の除外、分離音質は未確認 |
| Spotify | 共通PKCE/同期・取消の自動testを保持 | Spotify本体の起動は確認。ChopLab側の実接続と操作は未確認 |
| Mac配布/対応範囲 | MacでinstallDist成功。確認hostはmacOS arm64/JDK21 | 他OS版/Intel、署名・公証済みMac配布、Human GO |

速度比較は120秒/48kHz stereoの合成FLAC、同一Mac/JVMでwarm-up後3回。旧経路の検証＋使用開始の中央値392.70ms、改善後221.31ms（約44%減）、使用開始2.40ms。全PCMサンプル・rate・左右・frameが一致。ネットワーク取得速度や全素材の性能保証とは区別する。新しい変更はMacでdesktop255件（実録音1skip）/JVM199件/UI4件が成功。WAVではhashの追加が逆効果だったため保持を使わず、既存の直接decodeを維持する。必須CIはPRで確認する。

## Mac同梱Previewの受入 — 2026-09-26

担当root、[PR119](https://github.com/dj-thank/choplab-sampler/pull/119)、起点main `d254e3c`。head `73dd832`で必須CI3件成功、merge `bb2b66b`。範囲はMac用package、依存ツール配置、再現可能な音声受入、Mac構造への既存archive検査の適用。`packageMacPreview` はJava・ツール・ScreenCaptureKit helper・モデルを同梱するローカルad-hoc Preview。`packageMacSignedPreview` はDeveloper IDがないと失敗し、公証/公開の成功にはしない。公開前には署名・公証に加え、同梱した各依存の対応source/licenseを配布物へ整備する。RollbackはPR revertと前の生成物への復帰で、既存app/dataを変更しない。

| 受入 | 観測結果 | 未完/必要条件 |
|---|---|---|
| 自己音声の除外 | 開発JDK/実ScreenCaptureKitで-78.78dB。同梱Java候補 `4ff1942` でもロック解除後に再試験し、外部音振幅544.08、自分の音0.0373、比-83.27dB、364,800frame、helper exit0。5秒の非対称周波数fixture、生録音はメモリのみ | ロック中はNO_DISPLAYで失敗することも実測。人間の試聴・他Mac/routeの受入は別 |
| 同梱codec/長尺 | HomebrewなしPATH、作業folder外で11素材+破損1件。FLAC/ALAC/AIFFは全PCM一致。MP3/AAC/Ogg/Opus/MP4/WebMは左右保持、590秒FLACは28,320,000frame。再現は `scripts/run_mac_acceptance.py` | raw AACのみgapless metadataがなく1,408frame余白を観測。暗黙に切り捨てない。全素材の音質保証ではない |
| 同梱provider/分離 | 指定YouTube1素材の取得/保存/読込、48kHz stereo/12,276,298frame。0.5秒fixtureの実モデル分離11秒 | 登録済みの公開Client IDで認証画面へ到達。実accountの接続・API応答は同意画面の確認待ち。公式Spotifyのログインと別 |
| 同梱録音/制作 | 実マイク48kHz mono/18,944frame（検証後削除）、出力48kHz stereo/4,800frame。schema7制作の全PCM/位置/14step再読込、WAV出力成功。隔離起動でPreview領域にautosave作成 | ロック解除とAXIsProcessTrusted=trueを確認後も、osascriptの実UI操作は補助アクセス拒否(-25211)。System Eventsの再起動でも継続し、起動元アプリの再起動後の確認が必要。人間の聴感/操作感は未判定 |
| package検査 | `0.18.0`/build30・専用bundle ID・マイク説明・helper/model/tools配置をreadback。ツール40ファイルはハッシュ付き固定名一覧。公開証明書と秘密鍵の区別、未知library/改変/別manifest/nestedを検査 | 他Mac/Intel、Apple Developer ID/公証、公開download readbackは未受入 |

この候補のrepository/policyは303件成功。ローカル生成した全app ZIPのarchive検査も成功し、最終revision/bytes/必須CIはPR119で追跡する。受入目標は音声品質・入力の安全策・元の4工程を保持したMac利用。実機権限・実account・人間の評価の未回答を成功へ変換しない。段階3〜11の計画機能やNEXT全体の受入を、この現行hostの測定から推定しない。PR114の統合後も本番入口は従来desktopを維持する。

### Mac受入で判明した録音案内と設定の補完

担当root、[PR120](https://github.com/dj-thank/choplab-sampler/pull/120)、起点main `bb2b66b`。helperの `NO_DISPLAY` を録音adapterで区別し、画面ロック解除・デスクトップ表示・再試行を案内する。すべての起動失敗を権限不足と扱って設定変更を繰り返させない。Swift実buildとMac上のhelperでロック時のcode/exit1、解除後に同梱Javaで実録音成功を確認。子processを使う回帰testで録音中にならず空ファイルも残さないことを検証。Rollbackはこの変更のrevert。最新headの必須CI、生成物のhashとbyte数はPRで追跡する。

同じ受入で、Mac packageだけがビルド時の公開Spotify Client IDを引き継がない差分を確認し、Windowsと同じ入力検証/JVM設定に揃えた。実jpackage呼出しへ渡す引数、未設定時、無効値でbuild前に止まる契約を検証。登録済みClient IDの引継ぎと、実accountの接続成功は区別する。

### main統合後のMac readback

`0037e4b`（PR114/120統合後、tracked clean）のMac検証はengine58/core27/JVM33/shared157/jvm-core199/desktop269（実録音1skip）/UI23、計765成功・1skip。mainの[CI run](https://github.com/dj-thank/choplab-sampler/actions/runs/36237223858) は必須3件成功。repository/policyは306件成功。同revisionの同梱アプリを全238ファイルのmanifestと照合し、隔離起動で作ったschema7 autosaveの全PCM/128PAD/14stepとWAV出力をreadbackした。

同梱JavaのNEXT self-testもschema10の取込/chop/PAD/pattern/save/reopen/UndoRedo/stereo16bit・24bit出力230,400frameで成功。実Java Sound sinkは24,064frameを書込み、stop acknowledged・event loss 0・shutdown CLOSEDを確認した。これはnative画面での制作通し、人間の聴感、未移植機能の受入を意味しない。

受領した公開Spotify Client IDをMac packageへ引き継いだ候補も作成し、起動設定と全ファイルhashを検査した。Client IDやtokenをsourceへ書き込まず、起動中のアプリと利用者dataは保持。候補のartifact hash・byte数とproviderの追加結果は受入PRへ記録する。全機能受入は継続中で、native画面の操作許可、Spotify同意後の実API、人間の聴感/操作感を未確認のまま残す。

### 新しい編集画面（NEXT）のMacアプリ — 2026-09-27

オーナーの「Macで使いたい」に対応し、4工程の編集画面だけを起動する `ChopLab NEXT.app`（表示名「おとひろい NEXT」、bundle ID `com.choplab.sampler.preview.next`、ad-hoc署名・未公証）を `:desktop:packageMacLinkedPreview` で作る。新しい編集画面はWAVだけを開きマイクはJava Soundで録るため、media tool・分離モデル・ScreenCaptureKit helperの起動設定・Spotifyは同梱しない（Homebrewの固定名一覧にも依存しない）。workflow `mac-preview.yml` がmainへの統合ごとにmacOS（Apple Silicon）で作り、隔離profile・無音で30秒起動してデータ領域の作成を確かめ、archive検査の後に `choplab-mac-next-preview`（7日保存）として置く。archive検査は `ChopLab NEXT.app` を既存のMac app rootと同じ規則で扱う。jpackageのランチャーはJDK配布元の署名付きで、その上にad-hoc署名すると古い証明書が残りarchive検査が検出したため、先に署名を外してから署名する（jpackageがほかのbinaryにしている手順と同じ）。Rollbackはこの変更のrevert。Intel Mac、実機での音・マイク・操作感、公証・公開配布は未確認/対象外。

## 判断・失敗・次の一手の記録

| 日付 | 判断または未解決事項 | 次の一手 |
|---|---|---|
| 2026-09-25 | 0.18.0固定点から再構築。旧文書のschema/未merge表記は履歴へ移す | source7/reader1–7を現行として維持し、schema10はfixtureで受入 |
| 2026-09-25 | 一つのROADMAPへ統合、ADR1–5/researchを保持。stage1A文書はコード変更の証拠ではない | root統合後のlink/policy/required CIで1A全体を確認 |
| 2026-09-25 | Spotify/YouTube連携の配布条件、pure Kotlin性能、voice補正品質は採用時の判定事項 | 該当段階のspike/terms/実観測が成立するまで完成と表示しない |
| 2026-09-26 | macOSは開発hostで配布対象外（ADR6）。desktopのmenuを⌘/⌘Q/⌘⇧Zにし、app menuの終了もowned shutdownへ通す。mainではmacOSの終了要求がautosave flushを経ずに終了していた | Windowsの操作は不変。macOSのdata領域（現在 `~/AppData/Local`）、Dock名、Java Sound実音は未対応・未確認 |
| 2026-09-26 | Mac開発hostで既存desktop機能を使う。取り込みツールはアプリ一式またはPATH/Homebrew、システム音声はループバック装置かScreenCaptureKit、診断はJava Sound、新規データはApplication Support。既存のAppDataは維持。このMacでツール解決、48kHzモノラルのマイク読取、Java Soundへの短音書込、ScreenCaptureKitのWAV、0.2秒音のドラム分離6秒を確認 | WindowsのループバックとWASAPIは不変。人が聴いた音、YouTube/Spotifyの実アカウント、非bundle起動のDock名は未確認 |
| 2026-09-26 | Macの音源選択はモーダルな音源ハブを閉じてからネイティブのファイルパネルを出す。複数選択が空でも単一選択を採用し、ウィンドウへのドロップも同じ判定でライブラリへ入れる | 実ファイルをパネルから選ぶ操作と、人が聴いた読込結果は未確認 |
| 2026-09-26 | レビュー指摘を修正。システム音声の経路を録音開始ごとに選び、Windowsでステレオミキサーを後から有効にしても再起動不要。Macはヘルパーを優先。Macの録音は約100msごとに書き、開始時の待ちは最大1.5秒。停止前にキャプチャが止まったら失敗として報告。取り込み失敗の表示は人向けの文だけ | Swiftヘルパーの変更（停止エラーでの終了、常に2ch）はMacでのビルドと実録音が未確認。DropTarget、PATH上の古いyt-dlpは未変更 |
| 2026-09-26 | 編集の組み立て（Studio・出力・3世代自動保存・波形・終了処理）を `jvm` の `EditorBackend` に共通化し、desktopの `NextBackend` はJava Soundとパス用ファイル窓口を渡す薄い包みにした。経路変更・機器なし・応答遅れで出力が外れた後、編集画面を作り直さずに新しい出力を開く `reattach()` を追加。自動ではつながず、確定済みProgramを保ち、発音中の音は止まる。`:jvm:test` 41件（共通の組み立て6件・再接続2件を追加、再接続を無効にすると2件とも失敗）、`:desktop:test` 268件（Mac実録音1skip）、`:app:compilePreviewKotlin` 成功 | Android NEXTの入口・SAF・音声フォーカス・経路変更からの復帰を次のPRで接続する。desktopの再接続操作は未接続。実機の経路変更・実音は未確認 |
| 2026-09-26 | Android NEXT（debug/Preview）を接続。SAFの文書はアプリ専用の一時ファイルを通して共通のWAV/制作/書出し処理へ渡し、完成したものだけを書き込み、失敗・取消時は作りかけを破棄する。出力は画面を離れると手放し（`releaseOutput`）、戻ると開き直す。機器の故障は明示の再接続まで開き直さない。手放し/再接続の高速な繰り返しで見つかった競合を、希望状態と故障ラッチの設計に直した。画面を離れる時・フォーカス喪失時は音だけを止め、取込・保存・書出しは続ける。`:jvm:test` 53件、`:app:testDebugUnitTest`/`testPreviewUnitTest` の文書選択5件ずつ、`:desktop:test` 268件（1skip）、`:ui:desktopTest` 23件、lint 0 error、debug/Preview/release APKを生成し、NEXTはreleaseのmanifestに含まれないことを確認 | CIのAPI36エミュレーターでNEXTの起動・描画・WAV取込を確認する（音声なしのため音の証拠ではない）。Pixel実機での音・遅延・CPU・underrun、スマホ縦の見え方は未確認 |
| 2026-09-26 | PR124のWindows CIで、同梱FFmpegの取得先がHTTP 503を返し `:desktop:prepareMediaTools` が失敗した（変更とは無関係）。取得処理を、一時的なHTTP（408/425/429/5xx）と通信断だけ間隔を広げて最大4回試す形にし、途中で切れたファイルを完成扱いしないよう `.part` 経由で保存する。チェックサム検証は従来どおり。policy test 310件 | 上流が長時間止まる場合は再試行でも失敗する。その時は別の取得元の検討が必要 |
| 2026-09-26 | PR124のCIで、エミュレーター試験は成功したが、公開用APKの検査がPreview版の2つ目の入口 `NextActivity` を許可外として止めた。Preview（`com.choplab.sampler.preview`）に限り、権限なしで公開するこの入口だけを許可し、正式版で同じ入口が見つかれば従来どおり失敗にする。修正前の検査で同じ失敗を再現し、修正後は実際に作ったPreview/release APKの両方が合格。policy test 313件（1skip） | 2B-2で新画面を既定の入口にする時は、この許可を見直す |
| 2026-09-26 | PR124のCIエミュレーター試験で、WAV取込が30秒以内に終わらない失敗が断続的に出た（直前の実行は成功）。音声機器のないエミュレーターでは出力が詰まって外れ、つなぎ直しを繰り返す。出力を作り直す瞬間に届いたProgram切替・PAD停止の命令が未適用のまま捨てられ、Studioが編集ごと取り消していた（取込が失われる）。機器なしで意味の変わらない命令（Program切替・Release・Stop・Panic）に限り、作り直し・古いエンジン宛て・応答期限切れで捨てられた時だけ、新しいエンジンへ最大3回送り直す。音を鳴らす命令は従来どおり機器なしでは断る。再現試験3件（詰まり、書込みの固まり、詰まりとつなぎ直しを繰り返す中での取込6回）は修正前に失敗し、修正後は繰り返し実行で成功。エミュレーター試験は時間切れ時に出力・処理の状態とお知らせを表示する | 実機のBluetooth切替・経路変更中の編集は未確認 |
| 2026-09-26 | 送り直しの修正後も、CIエミュレーター試験で取込が取り消された（`ENGINE_REFUSED`、終了時の出力はATTACHED）。音声担当スレッドが出力機器（AudioTrack）を自分で開いており、開くのが遅い間は編集に応答できず、Studioの待ち時間（2秒）を超えていた。機器を開く処理を専用スレッドへ移し、開いている間も編集（Program切替・停止）は音なしで受け付ける。開き終わった機器は、その間に画面を離れた・終了した・故障が起きた場合は閉じ、故障は従来どおり再接続を待つ。音声担当スレッドの優先度を上げた。再現試験（機器を開くのに1.5秒かかる）は修正前に失敗し修正後に成功。開く途中の故障の試験は、この保護を外すと失敗する。エミュレーター試験は失敗時に、出力の状態の移り変わり（時刻つき）と最後の応答も表示する。`:jvm:test` 58件 | 実機で機器を開く時間・開き直しの間の音切れは未確認 |
| 2026-09-26 | 出力の自動復帰を `jvm` の `OutputRecovery` に共通化。表示中に故障（経路変更・機器なし・応答遅れ）で外れた出力を、少し間隔を空けて数回だけ開き直し、機器の出入りの通知でもう一度試す。画面を離れた時の意図的な解放は戻さず、再生も再開しない。開き直してすぐ外れる出力は同じ不調として数え、3回続いたら機器の出入り通知まで待つ（空回りしない）。外れた回数は出力側で数えて状態に含め、見張り役が途中の状態を見逃しても数える（見逃すと、つなぎ直しが早く止まることがあった。処理順を試験側で制御する再現試験で確認）。機器が見つからずに開き直しが失敗しただけなら新しい外れとは数えず、予定どおり間隔を広げて試す。Androidは機器の出入り通知を足して使い、desktopのLinked Previewもwindowを開いている間これを使う（Java Soundには機器の出入り通知がないため、windowに戻った時にもう一度試す）。`OutputRecoveryTest` 6件と、出力側の数え方の試験1件（外れの二重数え・予定の短縮を戻すと失敗）。`:jvm:test` 65件 | Java Soundでの実機の抜き差し・Bluetooth切替は未確認 |
| 2026-09-26 | 機器を別スレッドで開く修正の後も、CIエミュレーター試験で取込が終わらなかった（表示は、出力が4.7秒かけて開いたところまでで、理由の部分はCIの要約が長い行を切っていた）。調べる中で、Androidで画面を離れた時・音声の割り込み時に「音だけ止める」処理が、実際にはStudioのStopを使っており、準備中の編集・取込まで取り消していたことが分かった（PR本文の「取り込み・保存・書き出しは続ける」と矛盾）。準備中の作業を残して音だけを止める `Action.Silence` をStudioに追加し、Androidの画面離脱・割り込みはこれを使う。画面のStopボタンは従来どおり作業も止める。Studio試験1件（Stopと同じ動きに戻すと失敗）、最初の機器が開くのに3秒かかる間の取込試験1件（非同期で開く修正の前は `ENGINE_REFUSED` で失敗）。エミュレーター試験の失敗表示は、お知らせを先頭に1行ずつ、時刻つきで出す。`:core:desktopTest` 28件、`:jvm:test` 66件 | CIでの取消の直接の原因（画面の作り直しや割り込みの有無）は、次の実行の表示で確かめる |
| 2026-09-26 | `Action.Silence` の後のCIでも取込はエンジンに断られた（開始2.7秒で `ENGINE_REFUSED`、最初の出力機器は6.3秒間開いている途中、エンジンの応答は一度も記録されず）。機器を開く間も音声担当は編集に応えるはずなので、音声担当スレッドの様子を直接見る。出力ドライバーに状態報告 `DriverDiagnostics`（ループ回数・待ち命令数・処理中の命令数・機器を開いている途中か・エンジンの位置）を追加し、エミュレーター試験は0.5秒ごとの報告と、断られた瞬間の報告・音声スレッドの状態（上位の呼び出し）を1行ずつ表示する | 次のCI実行の表示で、音声担当が止まっているのか、命令を断っているのかを確かめて直す |
| 2026-09-26 | 状態報告を入れた実行ではエミュレーター試験が成功したが、失敗は断続的だった。原因の見立て: 音声担当スレッドは最初のエンジンを作るときに音程変換用の表（128タップ×512位相×10帯域）を計算する。このPCでもJITなしの解釈実行で約0.5秒（表が約0.46秒）かかり、起動直後のデバッグ版（エミュレーター）ではさらに遅い。その間はエンジンがまだ無いので、届いた編集はエンジン待ちのままStudioの2秒の上限で断られ、最初の機器を開くのも遅れていた（失敗した実行の「応答が一度も無い」「6秒開いている途中」と一致）。最初のエンジンを、音声担当スレッドを起動する前に呼び出し側（アプリの読込中）で作るように変更し、エンジン待ちのループを削除。`:jvm:test` 66件、`:desktop:test` 268件（1skip） | 直ったかはCIのエミュレーター試験の繰り返しで確かめる。失敗したら状態報告と音声スレッドの状態が表示される。同じ実行でWindowsの公開前検査が、同梱Java実行環境の証明書ファイル（cacerts）を署名素材として止めた（この変更とは無関係、以前の実行では通過）。再実行で確かめる |
| 2026-09-26 | 4工程の編集画面の「ドラムを足す」を接続。旧アプリの合成ドラム5種を `core` の `DrumKits` へ移し、80音すべてが旧実装と1サンプル単位で一致することを検証。選んだキットの16音をアプリが作ったfloat WAVとして内容アドレスで保存し、BANK Bの16 PADへ1回のUndoで入れる（`Intent.InstallKit`）。キット変更では、曲に置いたキット音のclipだけを同じ位置・長さのまま新しいキットの音へ差し替え、利用者の音のclipは残す。BANK Bに利用者の音があるときだけ確認し、確認は数えたPADの内容に結び付けて、変わっていたら聞き直す。制作ファイル・自動保存・別profileでの再読込も確認。core33/JVM59/UI27/desktop269（Mac実録音1skip）/app単体343×2、lint 0 error（警告数は変化なし） | CIのAPI36エミュレーターでボタンからBANK Bへ入る経路を確認する（音の証拠ではない）。実機での音・音量バランス・操作感は未確認。新規制作へのstarter（最初のリズム）は未移行 |
| 2026-09-26 | 4工程の編集画面のPAD調整にある「音色」を接続（選んだPADを暗くする）。旧アプリと同じ1次ローパス（角周波数 80 Hz×225^音色、左右別々、99.5%以上は素通し）をエンジンのボイスに追加し、素通しの時は音色の導入前とビット単位で同じ音を出す。音色は既定（100%）以外の時だけ制作ファイルへ書くので、音色を使っていない文書は従来のまま読み書きできる。音色を下げたPADは、原音をそのまま鳴らすタイムラインには置けない（ピッチ・逆再生と同じ扱い）。`PadToneTest` 4件、旧アプリの計算との一致試験1件（式を変えると失敗）、保存1件、画面側の操作1件 | 実機で聴いた音は未確認。タイムラインに置いたクリップへの音色（加工済みの配置）は未対応 |
| 2026-09-26 | 4工程の編集画面の「曲キー」（原曲キー）を接続。旧アプリと同じく、原曲を聴くときだけの回転数変更（音程とテンポが一緒に変わる、1半音ずつ、±24半音）。制作ファイルに保存され（0以外の時だけ書く）、1操作が1回のUndoになり、新しく取り込んだ原曲は元のキーから始まる。PAD・曲に置いた音・書き出しには影響しない（旧アプリと同じ）。エンジンは原曲の再生位置を小数で進め、PADと同じ帯域制限つき読み出しを使う。0半音では従来とビット単位で同じ。ループの継ぎ目でも端数を保つ。出力が作り直されても、次に原曲を鳴らすときにキーを送り直す。エンジン試験5件（ループの端数を捨てる実装に戻すと失敗）、保存1件、編集1件、画面側1件、原曲の再生速度を実測する結合試験1件（出力の作り直し後にキーを送り直さないと失敗）。ゼロ確保の実時間試験に、毎ブロック +24/+17半音で変わる原曲を追加し、確保0を確認。この試験の99%点は1ブロックの18%→37%に増えた（ピッチを変えたPAD 1音と同じ負担） | 実機で聴いた音・CPU（スマホでの負担）は未確認。ライブチョップの遅延補正にキーの速さを掛ける対応は、ライブチョップ移植の時に行う |
| 2026-09-26 | 4工程の編集画面の「チョップ開始」（ライブチョップ）を接続。旧アプリと同じく、原曲を範囲の頭から流し、叩いたPADがその瞬間から範囲の終わりまでを受け持ち、この回に切ったPAD（同じBANK・同じ原曲）は時間順に次の切れ目までへ詰め直す。切れ目には目印を足す（範囲の端・重複は足さず、127個まで）。1回叩くごとに1回のUndo。PADのほかの設定（音程・音色・音量・再生方法）は残す。位置はPADを押した瞬間に読み、離した時に切る（スクロールに変わった操作では切らない）。画面読み上げの操作ではその時点で切る。聞こえた位置に合わせて旧アプリと同じ60 msを引き、原曲キーの速さを掛ける。チョップ中のUndo/Redoは旧アプリと同じく原曲を止めて回を終える。画面を離れる・原曲が終わる・止める操作でも回は終わる。原曲の状態を読んだ直後に新しい回が始まっても、その古い読み取りで回を終わらせない保護を入れた（外すと試験が失敗）。編集の試験1件、画面側（Presenter）2件、描画・操作1件 | 実機で叩いた時のずれ（Bluetoothを含む）は未確認。遅延補正は60 ms固定のままで、出力の実測値による補正と手動の微調整は後の段階。切れ目の目印はまだ波形に表示しない |
| 2026-09-26 | ドラム・音色・曲キー・ライブチョップの4機能をコードレビュー（high）し、8件のうち7件を修正して各機能のコミットへ統合。ライブチョップ: 範囲の終わりを過ぎても原曲が流れ、そこで叩くと1フレームの無音PADができた→範囲の終わりで回と原曲を止め、範囲の後の打鍵は切らない（編集側も範囲の後を拒否）。同じ瞬間（同じ音声ブロック）に叩いた2つのPADの片方が1フレームになった→同じ区間を共有。曲キー: 出力がキー変更を断ると、原曲を読み直すまで古いキーのままだった→次の再生前に送り直す。ドラム: キット変更でPADの調整（音量・音程・音色・鳴らし方）が消えた→同じ太鼓のPADは調整を残して音だけ替える（同じキットの選び直しは変更なし）。試したキットの音が文書に残り続けた（最大80件、資産上限256の3割）→どこからも使われないキット音は編集ごとに文書から外す（利用者の音は外さない、Undoで戻る）。BANK Bに1音だけキット音が残っても「使用中」になった→全音がそのキットの時だけ。キット音の判定を毎回80通りの名前を作る方式から一度作った表の参照へ。各修正は、修正を外すと失敗する試験で確認（core37/JVM72/UI33）。キット選択ごとの再合成（1キット約1MB・数ms、音ごとに取消可）は変更なし | 実機での確認は未実施のまま |
| 2026-09-26 | 4工程の編集画面の「4 保存」に「音の診断」カードを追加（Android NEXT・Windows Linked Previewのみ）。音声出力の有無、形式、処理の単位（256フレーム=5.3 ms）、出力バッファ、出力の遅れの推定（Android: AudioTrackの再生時刻から。再生位置は下位32ビットしか返らないため、約25時間で一周しても正しい差を取る。Windows: Java Soundへ書いた数−再生位置）、音の計算時間（今の出力での直近4096ブロックの99%値と最大、ブロック時間に対する割合。出力をつなぎ直すと数え直す。AUDIO.mdの目標は99%値25%未満）、音切れ（Android: AudioTrackのunderrun数、Windows: 供給後に空になった回数の推定）、出力が外れた・開けなかった回数、画面のカクつき（Android: FrameMetricsで1/30秒以上かかった描画、開いてからの累計。最初の描画は除く）を1秒ごとに表示し、「数値をコピー」でクリップボードへ。分からない値は0と書かず、出力なし／この出力では分からない／この環境では数えない、を区別する。読み上げは1行（項目と値）ずつ。アプリが裏にある間は更新しない。機種名・端末ID・パスは含めない。音声担当スレッドは事前確保の配列（32ビット環境でも途中の値を読まないInt）と数値だけを使い、機器への問い合わせは表示側のスレッドで行う。コードレビュー（high）の指摘10件をすべて反映。試験: jvm 2件、UI 2件（表示・コピー・狭い画面・3種の不明・読み上げの行、コピーの成否）、desktop 1件（Java Soundの推定）、Android 2件（遅れの計算と一周） | 実機（Pixel・Windows PC）での数値は未取得。オーナーに数値の送付を依頼する。Java Soundの音切れは推定で、ミキサーによっては数えられない |
| 2026-09-27 | 4工程の編集画面の「声を録る」（ビート工程）を接続（Android NEXT・Windows/Mac Linked Preview）。旧アプリの声の重ね録り（ループ中のビートPADに重ねて録り、BANK Dの空きPADへ入れる。満杯なら入れない）を、曲の並びの上で使う形にした。ここからは新しい動き: 押すとマイクを開き（Androidは初回に許可を尋ねる）、曲を今の位置から（最後まで行っていたら頭から）流して録る。「録音を止める」（同じボタン）・全停止・曲の一時停止/停止・工程の移動・曲の終わり・出力が外れた時・マイクが使えなくなった時・長さの上限で終わる。録った声は32ビット浮動小数のWAVで保存し、BANK Dの最初の空きPAD（ワンショット・音量90%）と、曲の「VOICE」トラック（声の種類。無ければ作る）の歌い始めた位置へ、1回のUndoで入れる。BANK Dが満杯なら曲にだけ、曲が受け付けなければPADにだけ入れる。曲が鳴る前に録れた分と、その時の出力の遅れ（診断カードと同じ推定）を頭から切り（マイクの最初の音が遅れて届いたら、その分うしろに置く）、曲の終わりより後は切る。録音中はPADを叩く・止める・音量・表示の操作だけを受け付け、文書の変更は断る（旧アプリと同じ）。長さの上限は、新エンジンが常駐できる音の量（全体で約349秒、2秒の余裕を残す）の残り・制作ファイルの上限・資産置き場の容量・端末の空き（64MBは残す）から決める（最大5分）。録音は一時ファイルへ直接WAVとして書き、止めたら資産置き場へ移す（二重に持たない）。全部0の録音（ミュート・許可なし）は「録れませんでした」と案内する。Android: `VOICE_PERFORMANCE`→`UNPROCESSED`（対応時）→`MIC`、48kHzモノラルのfloat（不可なら16bit）、録音スレッドは音声用の優先度。Windows/Mac: Java Soundの16bit 48/44.1kHz、ステレオは平均してモノラル、バッファは0.5秒。コードレビュー（high）の指摘10件をすべて反映（容量不足で録音が失われる、停止と重なると録音が制作に入らない、曲の終わりで止めると次の録音が空になる、マイク切断の案内、遅れて届いた最初の音、曲の終わりの後の余り、同じ工程のタブで録音が止まる、閉じる途中に始まった録音でマイクが開いたままになる、など）。試験: core 1件、jvm 9件（一時ファイル・保存先の容量・録音・ホスト用の受け口）、UI 10件（Presenter 9件・描画1件）、desktop 3件（マイクの変換2件、実際の組み立てで録った声が曲の書き出しで鳴り、制作ファイルと自動保存に残る1件）、Android 3件（許可の橋渡し）。修正ごとに、外すと試験が失敗することを確認 | 実機（Pixel・Windows PC・Mac）のマイク・許可の画面・声と曲のずれは未確認。マイク側の遅れは補正しない（段階5の往復計測で扱う）。資産置き場は使われなくなった録音を消さない（従来どおりGCなし。容量の上限に近づくと録音できなくなる） |
| 2026-09-27 | 4工程の編集画面の「スクラッチ」（ビート工程）を接続（Android NEXT・Windows/Mac Linked Preview）。旧アプリと同じく、選んだPADか原曲の範囲を円盤でこすり、曲やループは下で流れ続ける。旧アプリの「速さ」で動かす方式から、AUDIO.mdの「位置」で動かす方式に変えた: 円盤を押さえると音をつかみ（音は止まる。鳴っているPADは、旧アプリと同じく鳴っている位置でつかむ）、左右のドラッグで音の位置を動かし（動かしている間だけ鳴る）、離すと、こすっている間止めていたもの（そのPADで鳴っていた音、再生中だった原曲）が一度だけ戻る。戻すのはエンジンなので、こすっている間に割り込み（イヤホンが抜けた等）で止まった原曲は戻らない。ドラッグで動く量は旧アプリと同じく画面の点（px）で数える3段階（細かく・標準・大きく）。独立したカットのフェーダー（音の出入り）を追加。画面読み上げには「左へこする」「右へこする」の操作（範囲の8分の1、最大0.5秒分を約0.15秒かけて）。位置は約12 msごとにエンジンへ送り、次が届くまで動き続けるよう間隔の1.5倍（最大で2回分）の時間をかける。エンジン: 原曲の音声（試聴用）に手でこする動きを追加（新しい命令4つ）。PAD・原曲とも、通常の8倍より速い手は8倍で動いて遅れて着く（以前は命令を断っていた。出す前のレビューで、速くこすると送る間隔の揺れで断られて音が途切れ、PADでは「失敗」と表示されると分かった）。動き始めと止まる瞬間は2 msでなめらかにつなぎ、止まる瞬間も直前の速さの読み方を保つのでプチッという音が出ない。止めた円盤を離しても音は出ない（以前のPADのスクラッチは、離すと最後の動きが一瞬鳴っていた）。こすっている間は文書を変えない。試験: engine 8件（原曲が手に合わせて動き範囲で止まる・動き始めと止まる瞬間に段差がない・止めた円盤を離すと無音・8倍を超える手は8倍で動く・速い動きの止まり際・鳴っているPADは鳴っている位置でつかむ・新しい音源と再生でスクラッチが終わる・PADと原曲を1万ブロックこすってもメモリ確保0）、jvm 1件（実際の出力で原曲を44.1 kHzの位置でこすり、再生中だった時だけ離すと戻る）、core 1件（Studioの命令。エンジンが断った動きは「失敗」と知らせない）、UI 8件（Presenter 7件・描画1件） | 実機での音・操作感（指の動きと音のずれ、Bluetooth）は未確認。こすっている間に「ドラッグで動く量」を変えると、次につかんだ時から効く |
| 2026-09-27 | 音程・逆再生・音色を変えたPADも「曲に置く」で曲に置けるようにした（これまでは置けなかった）。曲の並びは置いた音をそのまま鳴らすので、置く時にPADの音をPADと同じ読み方（音程・逆再生・音色）で新しい音（48 kHz・float WAV、種類はRENDERED、元の音を記録）に作り、それを置く。PADの音量・パンを引き継ぎ、1回の「戻す」で消える。同じ設定のPADは同じ音になり、二重には増えない。使われなくなった作った音は、ドラムキットの音と同じく制作ファイルから外す。新エンジンが一度に持てる音の量を超える時は置かずに案内する。試験: engine 2件（そのままのPAD・逆再生は元の音と一致、音程と音色を変えたPADはPADの再生と一致）、core 1件（作った音は置いた間だけ残る）、UI 1件（置く・戻す・作れない時・量が足りない時・作れないホストでは置けない）、desktop 1件（実際の組み立てで作った音が曲の書き出しで鳴り、制作ファイルに入る） | 実機で聴いた音は未確認。置いた後にPADの音程などを変えても、置いた音は変わらない |
| 2026-09-27 | 依存更新: kotlinx.coroutines 1.11.0・kotlinx.serialization 1.11.0・ONNX Runtime 1.30.0。ONNX RuntimeはMaven CentralからAARを新たに取得して公開SHA-1との一致を確認し、APKに同梱する8つのnative libraryのsize/SHA-256/ELFを固定値へ反映（固定値の合計サイズのtripwireも更新）。release APKの公開面検査と `android_runtime_policy.py --verify-origin` が成功。Compose Multiplatform 1.12系は `runtime-saveable-desktop-1.12.x.jar` がAndroidX側の同名jarと衝突してdesktopのinstallDistが止まるため、Dependabotで保留し1.11.1を維持する | 上流でjar名の衝突が解消したら保留を外す。ONNX Runtime 1.30.0でのAndroid実機のドラム分離は未確認 |

## 履歴の入口

- 2026-09-26: PR117の統合前確認で、ScreenCaptureKitの自プロセス除外が子helperだけを対象にしていたため、親Java hostのPIDをapplication filterへ追加。Swift compile、`:desktop:test`、`:jvm-core:test`、policy 294件が成功。ChopLab再生音の実録音による除外確認は未確認。

[保存点全体](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb) / [旧docs](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/docs) / [旧plans](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/plans) / [旧prompts](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/prompts)。旧記録を別のcurrent ledgerとして再開しません。

ADR1–5は元の決定を保持し、[ADR6](adr/ADR-0006-android-windows-focus.md) / [ADR7](adr/ADR-0007-concise-development-governance.md) が新しい対象と運用を定めます。[research](research) は調査日時付きの入力資料であり、現行の機能・性能・権限の証明ではありません。

- 2026-09-25: CMP1.12.0はWindows installDistの重複JARを再現したため1.11.1を維持。Kotlin2.4.20/AGP9.4.0/JDK21/出力Java17は対象repositoryで確認済み。JDKの公開truststoreは公開証明書だけを検証し、秘密鍵検出を緩めずに対応した。
