# 再構築 ROADMAP — 唯一の進捗・受入索引

更新: 2026-09-26。対象はおとひろい / Earth SongのAndroid 10+・Windows、および全制作機能を使うMac（[ADR8](adr/ADR-0008-mac-function-parity.md)）。**段階0〜11の実装、必要な検証、PR統合、仕上げまで承認済み**です。レビュー案にあった「レビューのみ」「各PRで再承認」は [ADR7](adr/ADR-0007-concise-development-governance.md) で置き換えます。device/providerの実観測と人間の音質・操作感の受入は、実施結果が得られるまで未確認のままです。

## UIの固定条件

共有タスク「Choplab UIを改善」で選んだ案2の改訂版を維持する。8月版の4工程・大きなPAD・濃い波形面を土台に、右側の自由配置、原曲の連携、試聴音量、可変幅を組み込む。5画面カード案と9月21日の素材棚中心版は採用しない。詳しくは [DESIGN](DESIGN.md)。

## 現在地・選択中の作業

- 基準: [PR101](https://github.com/dj-thank/choplab-sampler/pull/101) のmerge `2866683a5118681cf518ef47e29cac8baf882edb`、保存tag `archive/pre-rebuild-v0.18.0`。0.18.0/build30のAndroid/Windowsを出発点にする。後続の別ローカルeditor/schema8/9やDDJ-200を混ぜない。
- sourceの現状: 本番は `app / desktop / shared / jvm-core`、4工程、schema7 writer / schemas1–7 reader。独立した `engine / core / jvm` とschema10は導入済みで、4工程UI/本番hostへの全面接続は未完。AI・4stem等は下表で受入が完了するまでは計画。
- 選択中: **2A/2Bの4工程UI接続と、Mac全機能対応・取込速度/UXの検証**。1Aは[PR106](https://github.com/dj-thank/choplab-sampler/pull/106)、1Bは[PR107](https://github.com/dj-thank/choplab-sampler/pull/107)で各3件のCI成功後にmainへ統合済み。1Cも[PR110](https://github.com/dj-thank/choplab-sampler/pull/110)で3件のCI成功後、mainへ統合済み。新engineは独立moduleで、本番hostは従来のものを維持する。
- 1Cローカル候補: 専用ID/署名の非debuggable Preview APK、別profileのWindows Previewを作成。APKのpackage/version/署名指紋、Windowsの隔離起動・AWT応答・全所有processの正常終了を確認。実機音声/マイク/Pixel/Humanは未確認。
- engineは[PR112](https://github.com/dj-thank/choplab-sampler/pull/112)で3件のCI成功後にmainへ統合済み。core/JVMはschema10、frame固定配置、Undo、atomic save、3世代autosave、PCM cache、streaming WAV、独立原曲と共通output driverを追加。
- core/JVMは[PR113](https://github.com/dj-thank/choplab-sampler/pull/113)でmain統合済み。Mac取込高速化/UXは[PR118](https://github.com/dj-thank/choplab-sampler/pull/118)のhead `749132d`で必須CI3件成功、merge `d254e3c`。Mac host対応は[PR117](https://github.com/dj-thank/choplab-sampler/pull/117)の最新head `544f2c7`で必須CI3件成功、merge `29c80b2`。
- 次の一手: 指定された4工程UIとPreview接続のPR114を検証・統合し、Macの機能別受入と取り込み速度/UXを確認する。長尺prefetch、残る機能移行、実機・実音・Human受入は未完。

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
| 2B-1 NEXTの制作通し | 計画 | Previewだけに実hostを配線。取込→chop→PAD→step→再生→保存/再開→書出し/Undoを両OSで通す。Java Sound/AudioTrack driverと診断、Pixel CPU/underrunを測る |
| 2B-2 移植・切替 | 計画 | 残機能と音声救出/復旧を機能表で合格させ、別削除PRで旧codeを外す。kit/loop/choke/record/interrupt/route loss/共有/.choplib/アクセス不能資産。画像だけで実音等を合格にしない |
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
| 自己音声の除外 | 開発JDK/実ScreenCaptureKit、5秒の非対称周波数fixture。外部音振幅828.10、自分の音0.0953、比-78.78dB、277,440frame、helper exit0。生録音はメモリのみ | 同梱Javaの再確認は「表示中のディスプレイが見つかりません」で失敗。後続確認でMacの画面ロック状態をOSから観測。解除後に再試験し、旧成功で代用しない |
| 同梱codec/長尺 | HomebrewなしPATH、作業folder外で11素材+破損1件。FLAC/ALAC/AIFFは全PCM一致。MP3/AAC/Ogg/Opus/MP4/WebMは左右保持、590秒FLACは28,320,000frame。再現は `scripts/run_mac_acceptance.py` | raw AACのみgapless metadataがなく1,408frame余白を観測。暗黙に切り捨てない。全素材の音質保証ではない |
| 同梱provider/分離 | 指定YouTube1素材の取得/保存/読込、48kHz stereo/12,276,298frame。0.5秒fixtureの実モデル分離11秒 | Spotify実接続は登録Client ID/ChopLab内認証の特定待ち。公式Spotifyのログインと別 |
| 同梱録音/制作 | 実マイク48kHz mono/18,944frame（検証後削除）、出力48kHz stereo/4,800frame。schema7制作の全PCM/位置/14step再読込、WAV出力成功。隔離起動でPreview領域にautosave作成 | 同梱アプリのnative操作はmacOSが補助アクセスを拒否。設定画面を開いた後もMacはロック中。解除後の再試験で権限反映を判定する。人間の聴感/操作感は未判定 |
| package検査 | `0.18.0`/build30・専用bundle ID・マイク説明・helper/model/tools配置をreadback。ツール40ファイルはハッシュ付き固定名一覧。公開証明書と秘密鍵の区別、未知library/改変/別manifest/nestedを検査 | 他Mac/Intel、Apple Developer ID/公証、公開download readbackは未受入 |

この候補のrepository/policyは303件成功。ローカル生成した全app ZIPのarchive検査も成功し、最終revision/bytes/必須CIはPR119で追跡する。受入目標は音声品質・入力の安全策・元の4工程を保持したMac利用。実機権限・実account・人間の評価の未回答を成功へ変換しない。段階3〜11の計画機能や未統合PR114の成功を、この現行hostの測定から推定しない。

### 表示先がない場合の録音案内

担当root、起点main `bb2b66b`。helperの `NO_DISPLAY` を録音adapterで区別し、画面ロック解除・デスクトップ表示・再試行を案内する。すべての起動失敗を権限不足と扱って設定変更を繰り返させない。Swift実buildとMac上のhelperでロック時のcode/exit1を確認し、子processを使う回帰testで録音中にならず空ファイルも残さないことを検証。Rollbackはこの変更のrevert。録音成功の受入はロック解除後の実測まで未完のまま。

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

## 履歴の入口

- 2026-09-26: PR117の統合前確認で、ScreenCaptureKitの自プロセス除外が子helperだけを対象にしていたため、親Java hostのPIDをapplication filterへ追加。Swift compile、`:desktop:test`、`:jvm-core:test`、policy 294件が成功。ChopLab再生音の実録音による除外確認は未確認。

[保存点全体](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb) / [旧docs](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/docs) / [旧plans](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/plans) / [旧prompts](https://github.com/dj-thank/choplab-sampler/tree/2866683a5118681cf518ef47e29cac8baf882edb/prompts)。旧記録を別のcurrent ledgerとして再開しません。

ADR1–5は元の決定を保持し、[ADR6](adr/ADR-0006-android-windows-focus.md) / [ADR7](adr/ADR-0007-concise-development-governance.md) が新しい対象と運用を定めます。[research](research) は調査日時付きの入力資料であり、現行の機能・性能・権限の証明ではありません。

- 2026-09-25: CMP1.12.0はWindows installDistの重複JARを再現したため1.11.1を維持。Kotlin2.4.20/AGP9.4.0/JDK21/出力Java17は対象repositoryで確認済み。JDKの公開truststoreは公開証明書だけを検証し、秘密鍵検出を緩めずに対応した。
