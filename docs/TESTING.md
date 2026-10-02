# 検証の契約

変更の影響に合う最小の意味ある試験から始め、必要なCIを完了して差分を確認します。結果の索引は [ROADMAP](ROADMAP.md)、詳細はrevisionに束縛されたPR/CI artifactに置きます。同じ入力の無変更な高コスト試験を毎回やり直す必要はありません。

## 層と言えること

| 層 | 言えること | 言えないこと |
|---|---|---|
| LOCAL unit/host/DSP | reducer、範囲、保存、取消、fixtureに対する音声差/性能 | 実端末の音・権限・provider・主観品質 |
| Compose / ImageComposeScene / Xvfb | state、geometry、semantics、合成入力、48dp、選択した画面 | native dialog、physical touch、マイク、読み上げ |
| Android instrumentation / framework node / emulator | Android frameworkのnode/action/gesture、選択したemulator上の動作 | TalkBackが実際に何を読むか、物理音・route品質 |
| DEVICE | 指定bytes・機種・routeのinstall、lifecycle、音声/録音/割込み | 全機種保証、人間の品質評価 |
| PROVIDER | 実アカウントの指定操作と結果・拒否/取消 | 公開配布の適合、人間の受入 |
| PUBLIC | 公開先からのversion/signature/hash/内容readback | 実機品質、Human GO |
| HUMAN_GO | 人間による試聴、TalkBack、操作感、製品受入 | 全自動回帰の代替 |

`LOCAL_PASS → DEVICE_PASS → PROVIDER_PASS → PUBLIC_PASS → HUMAN_GO` は別の境界です。必要な層を省略・昇格せず、scopeと未確認を併記します。historical receipt、画像、health応答、子agentの文章は新しい上位passではありません。

Windows 音声専用 FFmpeg 候補の実行受入は、package された `tools` directory を指定して行います。隔離 Python に `python -m pip install -r config/windows-audio-acceptance-requirements.txt` を入れ、`python scripts/acceptance/windows_audio_tools.py --tools <app-image>/tools --work <new-private-directory>` を実行します。既存 `native_audio_codecs.py` を再利用して 11 codec・24bit/先頭末尾・左右極性・delay/end trim・44.1→48kHz・range seek・legacy converter と原本 hash を検査します。追加は実 CLI の 221 decoder/86 encoder と protocol/filter 名、Rubber Band の有限非無音出力、生成した一時 CA による loopback HTTPS の全 PCM 一致と不信 CA 拒否、同梱 yt-dlp→同梱 FFmpeg の MP3 後処理だけです。一時秘密鍵は試験後に消し、利用者の trust store や既存 profile を変更しません。

この receipt は実行した個別 fixture の LOCAL 結果です。通常 EXE の終了再開、制作を通した全新機能、実モデル分離、QuickJS EJS、実 audio endpoint、provider、署名・公開・Human はそれぞれ既存の別 gate で追跡します。Mac の同 helper の部分試験を Windows 成功へ転用しません。cross-build、対応 source/hash と公開面検査の成功だけで audio runtime を既定に切り替えません。

## 0.18.0 moduleのコマンド

checkoutのJDK/SDKを設定してrepository rootから実行します。Windowsは `gradlew.bat`、他hostは `./gradlew` を使います。

```powershell
.\gradlew.bat :shared:desktopTest :shared:testAndroidHostTest :jvm-core:test :desktop:test
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\gradlew.bat :desktop:packageWindows
```

関連するUI suiteと公開surface/配布検査も選びます。新module/Preview variantを追加したPRで実在するtask名をROADMAP/CIへ反映し、全体 `check`だけで全module実行とみなしません。文書だけならリンク、契約の整合、機密/個人path、必須CIを確認し、fresh buildと報告しません。

Android NEXTの縮小候補は[RELEASE](RELEASE.md)の `choplabNextSizeProbe=true` で、本体と `:app:assemblePreviewAndroidTest` を同じtree・property・runtime AARで作ります。[AGPのtestBuildType](https://developer.android.com/studio/test/advanced-test-setup#change-test-build-type) を `preview` に設定し、本体のR8 mappingでtest APKも縮小・参照変換します。DebugAndroidTestをR8本体へ組み合わせると、Kotlin等の難読化済みclassへ到達できません。probeだけは `src/nextRuntimeTest`、明示実行専用の `src/nextAndroidTest` と既存codec testを対象にし、Compose内部APIへ直接入る通常Debug UI suiteと分けます。runnerはEspressoの間接依存にせずtestへ明示依存します。本体だけの最適化で `Trace` / `LazyKt` のholderやcodec入口が消えるため、probe専用規則はAGPの縮小前classfile入力に対してR8 `TraceReferences --keep-rules` で抽出したtest→本体の共有APIを保持します。名前付きholderと実際の参照memberだけが対象で、package wildcardや広いCompose/制作class保持、本体の欠落class警告抑制は加えません。annotationにしか現れずtestのmethod/field参照がない型は除外し、`kotlin.Metadata` による本体全体のmetadata保持を避けます。fixture・共有依存を変えたらこの境界も再抽出し、実APKとmatching testを実行し直します。

境界抽出では `minifyPreviewWithR8.classes` をtarget、`minifyPreviewAndroidTestWithR8.classes` をsource、AGPの `bootClasspath` をlibraryにします。directory入力はclassfile JARへ束ね、AGP同梱R8の `TraceReferences --keep-rules` を使います。Android SDK stubが持たないrunnerの `ExposedInstrumentationApi.execStartActivity` 継承先はframework側の診断として確認し、共有APIの未解決参照と混同しません。

APKのclass定義から本体・testの全superclass/interfaceを照合し、別packageから非public型を継承する成果物を拒否します。Kotlin collection facadeの `CollectionsKt__IterablesKt` は移動する子クラスとの境界に限って [allowaccessmodification](https://developer.android.com/topic/performance/app-optimization/add-keep-rules#keep-option-modifier) を許可し、保持するAPIを増やさずアクセス整合を保ちます。

test専用の欠落診断抑制は、AndroidX runner/monitorが参照するError Proneの `CanIgnoreReturnValue` / `MustBeClosed` だけです。両者はCLASS retentionのbuild時annotationで、実行時classの欠落を隠しません。

端末ownerだけが専用arm64対象の隔離profileへ対応APK一式をinstallし、両APKの署名・hash・対象packageを記録します。`app-preview-androidTest.apk` も名前から署名済みと判断せず `apksigner verify` で確認します。継続Preview鍵がない場合の検証copyへの明示署名は、既存Previewを上書きしない隔離対象だけに限定します。署名後のbytesでも公開面/サイズを再確認します。以下はinstall後に明示serialへ行うオフライン検証です。

```sh
adb -s "$CHOPLAB_TEST_SERIAL" shell am instrument -w -r \
  -e class com.choplab.sampler.next.NextRuntimeShrinkTest -e choplabNextRuntimeFixture true \
  com.choplab.sampler.preview.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$CHOPLAB_TEST_SERIAL" shell am instrument -w -r \
  -e class com.choplab.sampler.audio.AndroidNativeCodecTest -e choplabCodecFixture true \
  -e choplabCodecExpectedFfmpegSha256 "$CHOPLAB_FFMPEG_SHA256" \
  com.choplab.sampler.preview.test/androidx.test.runner.AndroidJUnitRunner
```

`CHOPLAB_FFMPEG_SHA256` は固定候補のFFmpeg entry pinです。runtimeの4 testはja/en launcher、Rhino interpreter/timeago reflection、ORT JNI tensor、公開Android入口からの実Activity起動・初回案内を閉じる操作・4工程のframework accessibility表示を検査します。model/provider/マイクへ接続せず、testは所有する隔離profileだけで実行します。通常Debugの `NextEditorDeviceTest` は合成WAV取込・kit配置・Undoも引き続き確認しますが、縮小本体の受入へ転用しません。codec testは11形式・区間seek・24bit/左右・resample・local yt-dlp変換に加え、local FLAC metadataをproduction Jackson mapperで読む経路を確認します。テスト数/失敗/runner crashをreadbackし、未実行やskipを成功にしません。native loaderで止まった場合はcodec未受入のまま保持し、Java/R8検査成功から繰り上げません。実provider、物理音/route、縮小本体の全制作通し、署名配布は別の受入です。

旧Python cacheの移行は、端末ownerが所有する隔離Previewでcodec試験を新しいprocessから実行し、上の引数へ `-e choplabCodecSeedLegacyPythonCache true` と `-e choplabCodecExpectedPythonSha256 <config/android-ffmpeg-audio.json の pythonLinkage.member.sha256>` を追加して確認します。fixtureはnative初期化前に、固定ZIPの変更されていない版付きliblzmaを版なしaliasへ複製し、上流のprefsへ旧ZIP長 `14305904` を設定します。実際の `YoutubeDL.init` がcacheを再展開し、version `41890175`・alias不在・版付きELFのSHA不変を満たしてから、既存11codec/metadata試験を続けます。receiptの `cacheMigration` はこの明示的な旧cache模擬だけの結果であり、旧APK/元ZIPのinstall試験や利用者data移行の証拠にはしません。flagなしではcacheを模擬せず `NOT_RUN` と記録し、新規cacheの試験と区別します。元ZIP・音声・利用者dataはfixtureへ同梱しません。

端末TTSの実software受入は、ownerが同じpackageのJava/libraryで `com.choplab.desktop.next.NextTtsProductionSelfTest --choplab-offline-tts-fixture` を実行します。Macは導入済み `say`、Windowsは `System.Speech` を使い、固定された日英の合成用短文だけを無音でWAVへ生成します。言語別receiptは、実PCM→本番VocalTtsService→明示guide配置1Undo/Redo→24bit→archive/autosave再開、原音bytes・推定word timing・終了PCM0を検査します。声がなければ `UNAVAILABLE` / process exit 2であり、fake音声へ置換しません。必要なfixtureのtempoはtyped `CANNOT_FIT` の実測値から明示選択し、40–240 BPM・WSOLA 0.6–1.6を維持します。

Windowsで新しいproject JARを既存private app-imageのJRE/第三者依存と組み合わせる隔離診断は、fresh packageの受入から分けます。rootがschema互換のproject7個を一式固定し、元project7個を除外した新7＋固定第三者57個の明示classpathを使います。元64JARの前後hashを照合し、image外の新規profile/tmpだけで実行します。既存 `NextFourStemProductionSelfTest` はownerが指定した固定165,612,636 bytes・SHA-256を満たすモデル単一引数だけを受け、モデルをdownloadせず実推論・原音・明示1UndoRedo・24bit/archive/autosave・PCM終了0を検査します。モデルも前後hashを照合します。このDesktop entryはnative取消を検査せず、Androidのnative出力直後取消の証拠と混同しません。seed済みcacheの推論試験だけでは初回download/破損修復を通りません。Androidの追加caseは固定supplied modelをcontrolled-local `ModelDownload` として実Storeへ渡し、空cacheからの取得・途中取消後のpart削除/旧corrupt cache保全・hash検証後の修復を確認してから既存seed/inferを続けます。これはネットワークを使わない取得処理の検証で、receiptの `modelHttpDownloadVerified=false` を保持し、実HTTPS取得やproviderの成功にしません。診断のexit0/LOCAL_PASSをfresh package・物理音・provider・公開・Humanへ昇格しません。

```sh
"$CHOPLAB_BUNDLED_JAVA" -cp "$CHOPLAB_APP_LIBS/*" \
  com.choplab.desktop.next.NextTtsProductionSelfTest --choplab-offline-tts-fixture
adb -s "$CHOPLAB_TEST_SERIAL" shell am instrument -w -r \
  -e class com.choplab.sampler.next.NextOfflineTtsProductionTest -e choplabOfflineTtsFixture true \
  com.choplab.sampler.preview.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$CHOPLAB_TEST_SERIAL" shell am instrument -w -r \
  -e class com.choplab.sampler.next.NextFourStemProductionTest -e choplabFourStemFixture true \
  com.choplab.sampler.preview.test/androidx.test.runner.AndroidJUnitRunner
```

Androidの2言語TTSと1件の実4stemは、ownerの隔離Preview・対応test APKだけで実行します。新fixtureのtest→本体参照は、上のTraceReferences手順で最終同一sourceから再抽出してからpairを作ります。4stem用モデルはownerがappの `getExternalFilesDir(null)/acceptance/htdemucs_fp16weights.onnx` へ置いた固定165,612,636 bytesだけを読み、[FourStemSpec](../jvm/src/main/kotlin/com/choplab/jvm/separation/FourStemSpec.kt)のsize/SHAを外部入力と私有seedの両方で検査します。downloadやモデル同梱はせず、実ActivityManagerのRAM判定を保った本番workerで24,000 source frames→4つの44.1kHz float資産・各22,050 native frames→明示INSTRUMENTAL/1Undo→24bit→再開を検査します。取消は実native結果返却直後・資産公開前の境界へ固定し、公開資産0と再試行、原音・文書保全を検査します。native実行中の停止応答や物理音の証拠へは広げません。 Androidの空き容量は対象pathの `File.usableSpace` 実値で4出力＋16MiBを満たすことを要求し、0/不足はNO_SPACEとします。NIOのfilesystem情報取得がAndroidで拒否されても、架空の空きを返したり判定を省いたりしません。fixtureのmemory preflightは実ActivityManagerのtotal/available/lowMemoryを使い、CLIで容量を割り当てたsoftware emulatorの結果を物理端末へ読み替えません。

これらのAndroid fixtureは明示flagなしで `NOT_RUN` skip、声・モデル・実RAM不足なら `UNAVAILABLE` receiptとskipです。この2classは `choplabNextSizeProbe=true` のPreview test source setだけへ含め、通常Debug CIのsource setへ追加しません。XMLの失敗・skip拒否自体は保持します。実行receiptはinstrumentation statusと同じapp外部acceptance directoryの新規JSONへ出し、原音・モデル・voice名・端末識別子・個人pathを含めません。source/hash・本体/testの署名後bytesと実行時刻に束縛し、成功した言語/fixtureだけをLOCALのsoftware証拠に数えます。Googleの利用適合・費用・actor/毎回同意、物理音、TalkBack、Human、公開配布は別の未確認条件です。

Macでは同じJVM/desktop suiteに加え、`:desktop:compileMacSystemAudioHelper`、`:desktop:desktopUiQualityTest`、`:desktop:desktopLongPressUiTest` を実行します。実ファイル・providerの取込は私有の隔離ライブラリで確認し、取得時間とdecode/再読込を分けて測ります。合成音源の比較ではフレーム・rate・左右・サンプル一致も確認します。

Mac NEXTの梱包後は次を実行します。manifestの全ファイル・署名、PATHをシステム標準だけにした同梱codecで11種類（FLAC/MP3/AAC in M4A/raw AAC/Ogg/Opus/ALAC/AIFFとAIF/MP4/WebM）の取込、24bit losslessの値一致・44.1 kHz monoの共有変換・原本bytes保持・取消後再試行、同梱Javaでの取込/chop/PAD/pattern/保存/再開/UndoRedo/16・24bit書出し、合成マイクから実presenter/host窓口を通した原曲録音（44.1 kHz floatの全sample保持、PAD・曲・24bit出力・autosave再開）、実libraryでの追加/明示選択/タイトルと元bytes保持/bundle往復/UndoRedo/PAD配置/24bit書出し/保存再開、実launcherの画面応答と通常終了・再起動2回、自動保存の編集内容と全音源hash一致を確認します。合成素材・一時profile・無音出力を使い、利用者データは読み書きしません。実音・実マイク・ファイル窓口の手操作・聴感は別の確認です。

```sh
python3 scripts/run_mac_next_acceptance.py \
  --app 'desktop/build/mac-linked-preview-app-image/ChopLab NEXT.app' \
  --java-home "$JAVA_HOME"
```

Windows の同等な配布検証は `scripts/run_windows_acceptance.py` を実 Windows の対話 desktop 上で実行します。Python 3.11+、PowerShell 7、隔離 Python 環境の `yt-dlp[default]==2026.8.19` が必要です。後者は既存 `scripts/acceptance/quickjs_offline.py` が同梱 QuickJS を実行するための、同 version の EJS adapter です。同梱 `yt-dlp.exe` 自体の version/hash は別に検査し、実 provider 接続は行いません。

対象は `:desktop:packageWindowsLinkedPreview` が生成する `desktop/build/windows-linked-preview-app-image/ChopLab Preview` 一式です。旧 `DesktopAppKt` の Preview ZIP は NEXT の証拠に使えません。梱包する writer は、専用出力先に `Compress-Archive -Path 'desktop/build/windows-linked-preview-app-image/ChopLab Preview' -DestinationPath 'dist/ChopLab-windows-next.zip' -CompressionLevel Optimal` で ZIP を作り、元 CI の source revision と SHA-256 を取得します。検証する側はこの値を別途照合して指定します。script は ZIP bytes を照合しますが、自己申告の source revision から source provenance を生成しません。

```powershell
# 先に隔離環境へ固定した adapter を導入する。実配布 tool は ZIP 内のものを使う。
py -3 -m venv work/windows-acceptance-python
& work/windows-acceptance-python/Scripts/python.exe -m pip install 'yt-dlp[default]==2026.8.19'
& work/windows-acceptance-python/Scripts/python.exe scripts/run_windows_acceptance.py `
  --archive dist/ChopLab-windows-next.zip `
  --sha256 <independently-obtained-zip-sha256> --source-revision <full-ci-source-revision> `
  --version <numeric-package-version> --output work/windows-acceptance-new-run `
  --allow-drum-model-download
```

新規の `--output` にだけ展開・隔離 profile・合成旧版 data sentinel を作り、同梱 Java 21/tools、公開面、署名の観測状態、初期 ZIP 200 MB 条件と最終 150 MB 目標を記録します。`NextWholeCreationSelfTest` と既存 `NextSelfTest`、`NextCodecSelfTest`、`NextMicrophoneSelfTest`、`NextLibrarySelfTest`、`NextOnlineSelfTest` と QuickJS offline fixture を再利用し、同じ合成制作の profile を保って通常 EXE を 2 回起動・正常終了します。両回で autosave の Project/revision/全 asset hash、旧版 sentinel、終了後の package bytes を照合します。保存 bytes の一致は、復元した制作を人間が画面で確認した証拠にはしません。無音 endpoint の通常終了は実音・マイク・手操作の合格ではありません。

`--allow-drum-model-download` を指定した場合だけ、既存 `NextSeparationProductionSelfTest` が初回の固定モデルを私有 cache へ取得し、次の新 JVM では HTTPS proxy を閉じて同じ cache の再使用を確認します。既にある固定モデルを `--drum-model <path>` でコピーして使う場合は、初回 download 成功とは記録しません。いずれも未指定なら分離は `NOT_RUN` です。4 stem は別の `--four-stem-model <path>` と `NextFourStemProductionSelfTest` により検証し、未指定なら `NOT_RUN` のままです。

`acceptance.json` は check ごとに scope、PASS/FAIL/NOT_RUN、revision、ZIP hash と所要時間を残し、全体の `COMPLETED` は選択した手順の終了だけを示します。最終全機能通し・署名済み正式配布・provider・実音・Human GO へ昇格させません。ログと合成素材を含む run directory は私有の readback 用に保持し、公開前に対象を選んで既存 scanner を通します。既存版の installer は別の `scripts/test-install-windows-app.ps1` で隔離して確認し、この Preview harness から既存アプリを置換しません。

全制作の入口 `com.choplab.desktop.next.NextWholeCreationSelfTest` は確認したmain `05db49ea` では未導入です。この配布変更は制作実装を取り込まず、helperが全6入口の一意なclassを確認してから実行します。欠けていれば `self-test-entrypoints=FAIL`、各制作試験は `NOT_RUN` で停止します。rootは必要な制作sourceを統合して同じZIPを作り直した後に、全制作を含む手動workflowを実行します。

## 必須の振る舞い

- editing: 純reducer、plan/effect/commit/cancel、revision、Undo/Redo、busy/no-op、対象を固定した確認、stale job拒否。
- persistence: roundtrip、hash/channel/frame、未知schema/重複/path/ZIP bomb/上限、atomic publish、中断/容量不足、復旧3世代とUndo資産保全。
- audio: [AUDIO](AUDIO.md) の固定fixture、command frame、block一致、左右、quantize/tail、停止/steal/loopと負経路。
- UI: SOURCE→CHOP→PAD→step→再生→書出し→保存→再開→Undoの通し、文字倍率1.3/2.0、scroll/focus/keyboard。kit変更・loop・record/interrupt/route loss・共有/.choplib・アクセス不能資産を追加。
- BEATテンポ伸縮: ja/en・広幅/390px文字2倍の実host入力でPAD/clipの素材BPM→準備→原音/処理後A/B→明示1Undo/Redo→24bit WAV→archive/autosave再開。BPM変更だけでは音源不変、明示再処理は元音から、1:1は元範囲へ戻ることを確認する。取消/閉じる/対象変更/保存/録音/古revision/終了後の遅着、不完全結果、worker busy、RAM/disk/scratch/project上限を拒否し、原bytes/左右/来歴とSOURCE復帰を照合する。長尺400秒のpaged/4096frame/shared budget、音程・transient oracleとHuman聴感は別結果にする。
- online/AI: fake contractの後に明示された実accountで確認。取消/429/失効/遅い応答、metadataと音声の分離を試す。
- package: Windowsの実app-imageをWindowsで起動/停止、native処理を確認。Androidは対象variantのlint/assemble、signature/package/version/hashをreadback。

## 実機の所有

ADBは一つの明示serialだけを対象にし、全connected device taskを使いません。install前にowner/lease、対象APKと署名、残すデータを確認します。無断uninstallや端末初期化を行いません。合成fixtureと隔離profileを優先し、最終状態をreadbackして解放します。

既存の `config/choplab-review-avd.json` とemulator runnerは、対象AVD・source provenance・実test数・fatal/ANR・font/rotation復元を検査します。テストをcompileしただけでは実行済みになりません。emulator runnerがphysical serialを拒否する保護を保持してください。

性能試験はwarm-up、rate/buffer、機種、OS、冷間/熱時、反復数、p99/最大/underrunを添えます。サイズは同じbuild種別・圧縮形式で測り、初回DL・展開・利用者素材/cacheを分けます。human確認は最大5項目に絞り、未回答は未確認のまま残します。

研究資料は [Android audio/accessibility review](research/android-audio-accessibility-reference-review-2026-08-17.md) に保存しています。資料の日時とsource revisionを現在の検証結果へ読み替えません。

Mac NEXTのオンライン取込は `NextOnlineSelfTest` で合成providerから候補確認→明示取得→原本保持→制作・24bit書出し・保存再開を検証する。これは実YouTube/provider合格ではない。`run_mac_next_acceptance.py` は同梱Node/yt-dlpの起動も確認する。実providerとnative候補選択窓の操作は別に記録する。
