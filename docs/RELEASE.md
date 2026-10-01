# 配布とreleaseの契約

進捗と実際に取得可能な配布物は [ROADMAP](ROADMAP.md) と各releaseへ記録します。以下は再構築の配布要件です。段階1Cのworkflow/署名/readbackが通る前に、新方式で配布済み・保護済みとは報告しません。

## 種類・identity・公開

- version/buildNumberは `gradle.properties` の `choplabVersion` / `choplabBuildNumber` 一つから生成。moduleごとに手書きの版数を増やさない。
- Preview CI artifactの作成とGitHub Releaseの公開を分ける。PreviewはAndroid `com.choplab.sampler.preview`、ja「おとひろい Preview」/en「Earth Song Preview」、正式版と別の継続署名鍵。authority/OAuth/deep linkとWindowsデータ領域も併存確認。
- 正式release tagはmain由来の `v${choplabVersion}`。rootが認可された公開範囲を実行し、必要check、差分、対象commit、署名、公開するbytesを直前確認する。既存tag/assetを上書きせず、失敗した公開のversionを再利用しない。
- 段階1Cでsigned Android APK、Windows zip、SHA-256、依存一覧/SBOM、source manifestを一組にする。secretがなければdebug/unsignedへ黙ってfallbackしない。Windowsのcode-signing状態はAndroid署名と別に明記。
- fork PRへ署名鍵を渡さない。署名はtrusted commitの配布runに限り、workflowの権限は既定read-only、publication jobだけ必要なwrite scopeを持つ。
- 配布後に公開先からartifactを取得してpackage/version/signature/hash/内容をreadback。CIの成功やアップロード応答だけでPUBLIC_PASSにしない。

Windows app-imageはprivate Java runtimeを含む一式であり、EXE単体ではありません。署名installer・更新は段階11。既存版とPreviewは設定/autosave/library/cache/lockを分け、署名変更を含む正式切替前に音声救出と復元を確認します。利用者の既存app/dataを削除して問題を回避しません。

## 配布サイズの測定境界

正式Android APKはarm64-v8aのみ、R8とresource shrinkingを有効にして50,000,000 bytes以下を検査します。debug/Previewはemulator ABIを含む別経路です。Previewの明示 `-PchoplabNewPipeR8Probe=true` は縮小時の互換確認用であり、正式署名・実機受入の代わりにはなりません。ORT JNI、YoutubeDLのJackson mapper、NewPipeのRhino/timeago/protobufの動的参照は保持規則の対象です。上流native bytesの固定値は引き続き検査し、codecや機能を外してサイズを通しません。

NEXTの50MB候補は `-PchoplabNextSizeProbe=true` で既存Previewのarm64/R8/resource shrinkingを明示して作ります。通常Preview、正式release、署名・application ID・NEXTの保存先は変更しません。releaseのsource setにはNEXTがないため、そのAPKのサイズをNEXTへ転用しません。候補の採用判断は同じ制作機能を含む統合treeと対象Androidでの受入後です。

この候補だけはAGPの [DEX packaging](https://developer.android.com/reference/tools/gradle-api/9.2/com/android/build/api/variant/DexPackagingOptions) を `useLegacyPackaging=true` にし、最適化済みDEXの内容を変えずAPK内で可逆圧縮します。R8のreflection/TraceReferences保持、全nativeの固定bytes、ja/en資源は維持します。圧縮前後の全entry名・展開後bytes一致と同じmappingを検証し、APKのdownloadサイズとインストール後のDEX展開・最適化cacheを区別します。対象Androidの起動・codec・制作受入は対応するtest APKで別に確認します。

同じ固定入力の再現は `python3.14 scripts/prepare_next_native_candidate.py android --ndk "$ANDROID_NDK_HOME" --work work/next-native-android` でも実行できます。下位recipeを順に呼び、native入力の固定値を確認してから候補をstageします。以下の本体buildへは、使用したrecipeの実出力pathを渡します。

```sh
./gradlew --no-daemon --max-workers=1 --no-watch-fs :app:assemblePreview :app:assemblePreviewAndroidTest \
  -PchoplabNextSizeProbe=true \
  -PchoplabAndroidAudioRuntime=work/native-build/output/ffmpeg-audio-arm64.aar \
  -PchoplabAndroidPythonRuntime=work/python-linkage/library-linkage-arm64.aar
# version/buildNumberはgradle.propertiesの値を渡す。署名済みなら、その最終APK名を使う。
python3 scripts/verify_android_next_probe.py \
  --apk app/build/outputs/apk/preview/app-preview-unsigned.apk \
  --mapping app/build/outputs/mapping/preview/mapping.txt \
  --version <choplabVersion>-preview --version-code <choplabBuildNumber> \
  --test-apk app/build/outputs/apk/androidTest/preview/app-preview-androidTest.apk \
  --test-mapping app/build/outputs/mapping/previewAndroidTest/mapping.txt \
  --test-configuration app/build/outputs/mapping/previewAndroidTest/configuration.txt \
  --output work/next-size-probe.json
python3 scripts/check_public_surface.py --archive app/build/outputs/apk/preview/app-preview-unsigned.apk
```

この検査はNEXT launcher/task、非debuggable、ja/enラベル・Compose資源、APK内のR8 IDとmapping、動的参照class名・runner共有Trace/LazyKt・codec入口、DEX継承先のpackage/publicアクセス、arm64と実bytes/hashを結びます。対応test APKを指定するとtarget package、DEX/mapping、AGPが適用した本体mappingと両APK間の継承アクセスも照合します。JSONは常にcandidateとし、runtime/device/providerを未実行と記録します。resource shrinkingの実行はbuild task出力も保持します。署名情報がなければPreviewは未署名のままで、検査が署名鍵を代用しません。runtimeの実行は[TESTING](TESTING.md)の対応するPreviewAndroidTestで確認します。

`python scripts/measure_distribution.py --archive <APK> --kind android-release --output <JSON>` はABI・実bytes・hashと50MB条件を検査します。Windows ZIPは同じコマンドの `--kind windows` で初期200,000,000 bytes以下（最終目標150,000,000 bytes以下）を検査します。測定だけの `--measurement-only` でも未達はJSONに `FAIL` と残ります。Windowsの正式比較はCIの `Compress-Archive -CompressionLevel Optimal` で作成した最終ZIPに対して行います。展開サイズ、初回追加download、私有cacheは別に記録します。未署名APKの計測成功を正式配布へ昇格させず、最終署名後の同一bytesで既存の署名・version・native・公開面の検査を再実行します。

Androidの音声runtime候補は明示 `-PchoplabAndroidAudioRuntime=work/native-build/output/ffmpeg-audio-arm64.aar` でだけ選択します。既定の上流AAR・署名設定を変更しません。候補AAR全体はGradleで固定size/hash照合、APK内の3置換entryは既存公開面scannerで別の固定値へ照合し、元33 pinを維持します。生成物内の自己申告manifestを許可根拠にしません。

```bash
python3 scripts/build_android_audio_runtime.py --ndk "$ANDROID_NDK_HOME" \
  --aar "$CHOPLAB_ORIGINAL_FFMPEG_AAR" --python-aar "$CHOPLAB_ORIGINAL_PYTHON_AAR" \
  --work-dir work/native-build --jobs 2
# 上のAARはMavenのffmpeg:0.18.1/library:0.18.1。NDKは28.2.13676358。
# ビルドは固定hashを検証し、私有work内へ公式sourceを取得する。
./gradlew --no-daemon --max-workers=1 --no-watch-fs :app:assembleRelease \
  -PchoplabAndroidAudioRuntime=work/native-build/output/ffmpeg-audio-arm64.aar
```

JDK 21とNDKを明示して実行し、`ffmpeg-audio-receipt.json` と最終APKの実bytes/hashを対に保存します。source/header cacheの改変、欠けた音声codec/TLS/filter、解決できない共有依存、異なるAARは拒否します。生成物は追跡せず、配布に必要なsource/notice条件は[NOTICE](../NOTICE.md)の未完項目も含めて確認します。

`scripts/acceptance/native_audio_codecs.py` は実FFmpeg/FFprobeで合成11形式、24bit/左右/極性、delay/end trim、区間seek、44.1→48kHzと旧converterコマンドを検査します。Macでの同version source試験はAndroid実行の代用ではありません。rootは隔離したarm64 Android対象で `AndroidNativeCodecTest` を明示実行し、`choplabCodecFixture=true` と固定候補の `choplabCodecExpectedFfmpegSha256` をinstrumentation引数へ渡します。試験は所有cache内の合成音とlocal file URLのyt-dlp変換だけを使い、マイク・制作data・providerへ接続しません。通常のdebug test APKを利用者の正式appへ上書きせず、既存Previewの署名も変更しません。software試験が通っても実音、route、provider、正式署名、PUBLIC_PASSは別の受入です。

音声候補は `scripts/prepare_android_python_runtime.py --python-aar <固定元library AAR> --ffmpeg-aar <固定元FFmpeg AAR> --ndk <固定NDK> --work-dir <私有出力先>` の Python linkage 派生と組で検証します。`-PchoplabAndroidPythonRuntime=<library-linkage-arm64.aar>` を既存 `-PchoplabAndroidAudioRuntime=<ffmpeg-audio-arm64.aar>` に追加すると、Gradle artifact transform が元の module/依存 metadata を保持して固定 Python AAR だけを置換します。既定の上流 AAR は変えません。除去するのは system と衝突する版なし liblzma alias 1 個であり、版付き codec と Python/TLS は同 bytes です。元の初期化 source は ZIP の長さを `pythonLibVersion` として比較するため、recipe は元と異なる長さを必須とし、次の process 初期化で既存の展開 cache を再作成させます。利用者の制作・原音・署名には触れません。新しい APK bytes を再検査し、更新前の展開 cache がある状態と新規 cache の双方で root が実 Android codec 試験を完了するまでは採用候補です。

Windowsの初期ZIPにドラム専用モデルを同梱せず、初回の分離操作でcommit/hash/size固定のモデルを取得します。従来画面とNEXTはprofile内の `models` cacheを共有し、別process間の排他・取消・hash不一致・原子的確定を維持します。元音やProject/archiveにmodelを混ぜません。明示指定／既存Mac bundleのモデルを優先する経路は維持します。初回downloadは165,612,636 bytes、必要空き容量はこれに64MiBの余裕を加算。4stemの別モデルと実推論受入は別に扱います。Windows ORTは完全な上流JARから全Java/noticeとwin-x64 nativeを残す再現可能な派生とし、元artifact・entry集合・派生bytesを固定manifestと公開面検査で照合します。Mac/Androidのruntime梱包をこの派生へ置換しません。

WindowsのEJS runtimeは `config/windows-quickjs.json` に固定した公式QuickJS-NG 0.17.0 x64を使用します。Nodeを含む既存の外部tool directoryとMacは従来経路を維持。Windows package gateでは `tools/qjs.exe` のexact bytes、source receipt、同梱MIT本文、`scripts/acceptance/quickjs_offline.py` によるyt-dlp 2026.08.19のhash検証済みEJS fixture、同条件のZIPサイズを確認します。実Windows artifact上で実行した結果とMac上の同version fixtureを区別し、新runtimeでのprovider受入も別に残します。公式EJS実行環境の置換は音声codecを変更しません。

Windows の最終 150 MB 目標用に、`config/windows-ffmpeg-audio.json` の FFmpeg 8.1.2 音声専用候補を opt-in で用意します。全 native 音声 codec、既存の外部音声 codec、Media Foundation の音声 encoder、audio filter、demux、GnuTLS/SRT/SSH/ZMQ を維持し、video codec と表示用 filter、不要な debug 情報を同梱対象から外します。Rubber Band は公式 4.0.0 source から構築。compiler と UCRT の DLL/headers、元 package と対応 source、派生 files を固定し、PE import の DLL と名前付き export を照合します。これだけで Windows 実行・音質・150 MB を合格にしません。

Mac の隔離 Python 3.14 環境に Meson 1.9.0、Ninja 1.13.0 と既存の pkg-config/make を用意し、`python scripts/prepare_next_native_candidate.py windows --work work/next-native-windows` を実行します。下位 recipe の再build結果をrepository内の全file/ZIP固定値へ照合してから、`candidate/ffmpeg-windows-audio.zip` へstageします。固定LLVM-MinGWはmacOS universal版なので、このWindows cross buildはMacで行い、未固定のLinux compilerへ切り替えません。対応 source は同work配下の `windows/sources/` の依存 package と `windows/downloads/` の FFmpeg/Rubber Band の固定 archive です。MSYS2 source archive には upstream code と PKGBUILD/patch を含み、`.SRCINFO` の版を binary package と照合します。source/recipe/NOTICE の提供を release に含める前提は維持し、source を用意しただけで公開提供済みとは記録しません。

Windows package へは `-PchoplabWindowsAudioRuntime=<展開した候補 directory>` を明示して渡します。候補の全 file set/hash/source/notice を検証し、従来の generated tools と別 directory に stage。既定の Gyan tools と Mac の標準 model 同梱経路は維持します。採用は同一 combined NEXT app-image の実 Windows codec/TLS/通常終了再開、全 archive 検査と CompressionLevelOptimal の ZIP 実測後に判断します。unsigned tools の cross build や旧 app-image への容量 projection を正式配布、署名成功、最終目標達成の証拠へ読み替えません。rollback は opt-in property を外して元の tools に戻すことと本変更の revert です。

Windowsへ同じrevisionと候補ZIPを渡し、次を実行します。展開はZIP全体と内部file集合・hashをrepositoryの固定値へ照合し、新しいdirectoryだけへ確定します。受領したJSONの自己申告hashだけでは許可しません。

```powershell
python scripts/prepare_next_native_candidate.py unpack-windows `
  --archive work/native-transfer/ffmpeg-windows-audio.zip --output work/next-native-runtime
.\gradlew.bat --no-daemon :desktop:packageWindowsLinkedPreview `
  -PchoplabWindowsAudioRuntime=work/next-native-runtime
```

手動起動の [NEXT native candidate workflow](../.github/workflows/next-native-candidate.yml) は、この同じrecipeからAndroidのNEXT本体と対応R8 test APK、Windowsの全NEXT app-imageを作ります。Androidは50,000,000 bytesと両APKの静的整合、Windowsは実同梱codec/TLSと、同じ最終ZIPからの全制作・通常終了再開を検査し、Optimal圧縮した150,000,000 bytes以下の候補だけをuploadします。Windows候補専用の `--kind windows-next-candidate` を使い、既存の初期200MB gateを成功として流用しません。artifact名は `choplab-android-next-native-candidate` / `choplab-windows-next-native-candidate`（7日保存）で、source revision・native入力hash・受入JSON・最終bytesのSHA-256を添付します。Android artifactは明示的に未署名であり、ownerが既存Previewと整合する鍵で署名後、最終bytesと対応instrumentationを再検査します。

このworkflowは既定native、正式legacy identity、署名、release公開を変更しません。未知の再build hashは失敗にし、自動的にpinを更新しません。workflow作成・cross build・静的検査だけで対象runtime、実音、provider、公開配布、人の受入を成功と記録しません。rootが同じ全機能の統合sourceと対象runtimeを確認してから既定採用を判断し、署名・source/license提供・公開readbackは別のrelease条件として維持します。

Windowsの手動workflow実行には、対象revisionへ `NextWholeCreationSelfTest` とその制作機能が統合されている必要があります。起点main `99f87a37` には他の5つの通常self-testがあり、全制作classはまだありません。配布用変更の先行統合とworkflowの実行受入は別で、helperはrequired classの欠落・重複を実行前に拒否します。全制作を省略するflagや成功への条件分岐は設けず、rootが制作sourceの統合後にdispatchします。通常CIの既存検査とNEXT ZIP生成はこの手動受入に依存しません。

Mac NEXT preview（`:desktop:packageMacLinkedPreview`、workflow `mac-preview.yml`、artifact `choplab-mac-next-preview`）は、新しい4工程の編集画面だけを試すための、ローカルad-hoc署名・未公証のApple Silicon用CI artifact（7日保存）です。固定済みmedia tool・ドラム分離モデル・ScreenCaptureKit helperを同梱し、複数codecの音源取込、オンライン取込、ドラム分離、端末音録音へ接続しています。各routeの検証範囲はROADMAPに記録し、同梱だけでprovider・録音許可・聴感受入の成功とは扱いません。Spotify情報の接続は専用metadataセッション（`user-library-read`）で扱い、音源・制作と分離します。public Client IDだけを同梱設定へ渡し、tokenはsession内メモリに保持して終了時に破棄します。OAuth/APIの実観測と、製品全体の一般配布条件の適合が確認できるまではPUBLIC_PASSとしません。旧YouTube自動照合は接続しません。専用bundle ID `com.choplab.sampler.preview.next`、データはPreview領域の `next-v10`。GitHub Releaseの公開物ではなく、Developer ID署名・公証・PUBLIC_PASSの成功にも数えません。

## 鍵と設定の区分

| 設定名 | 内容 | 取り扱い |
|---|---|---|
| `CHOPLAB_ANDROID_KEYSTORE_BASE64` | stable release keystore | secret。repository/protected environmentに保管 |
| `CHOPLAB_ANDROID_STORE_PASSWORD` | keystore password | secret |
| `CHOPLAB_ANDROID_KEY_ALIAS` | release-key alias | signing設定。CI secretとして扱う |
| `CHOPLAB_ANDROID_KEY_PASSWORD` | key password | secret |
| `CHOPLAB_ANDROID_CERT_SHA256` | 期待するrelease証明書のSHA-256 | 公開可能なfingerprint。private keyと混同しない |
| `CHOPLAB_SPOTIFY_CLIENT_ID` | 登録したSpotify appの識別子 | public ID。値と適用mode/scopeを確認して設定し、tokenとは別管理 |

Previewの鍵名/証明書は実装PRで明示し、正式鍵を使い回しません。keystoreと復旧情報はoffline暗号backupを保持。鍵の喪失/回転は通常CI変更ではなく製品移行です。秘密値をlog、artifact、サンプル、制作ファイルに入れません。

## 維持する検査能力

source/history scannerを短くすること自体は合格条件ではありません。旧検査の検出例・誤検出例を移し、新旧比較の後に置換します。

1. current tracked/nonignored候補とreachable Git historyを別に検査。key/token/private signing、個人path、音声/モデル/binary、symlink targetを扱い、secret-shaped findingはredactして出力。
2. Androidのsignature fingerprint、debuggable、package/version、permission/exported component、alignmentを検査。正式/Preview/debugは明示した別契約であり、見つかったbytesを後から適切な種類と呼び替えない。
3. Windows全app-imageのversionとruntime/library/resourceを含むhash、最終APK/ZIP内部を検査。source検査だけでは生成物の安全を証明しない。
4. ZIPは展開前にentry/path/count/size/CRC/local-central整合、compressed span/descriptorの連続所有を検証。safe名のtextでも音声magic/secretを走査。UTF-16/32、comment/extra field、nested ZIPも対象にする。
5. 既存上限の意味を保持: 4,096 entries、通常text512KiB/member、metadata/output4MiB/archive、decoded text入力4MiB、100:1、LZMA辞書16MiB。nested depth3、通常64archives、16MiB/member、256MiB共有container/expanded上限。NewPipe同梱後の明示Windows app-image/Preview/NEXTとMac NEXT Previewの4種の配布ZIPだけはnested JARを最大80件まで検査する（他の上限と全JAR内容検査は維持）。root/history集計やJIMAGEの例外も無制限化せず、置換にはattack/negative fixtureを付ける。
6. 正式download後、manifest/hash/attestation/publication前にも最終配布surfaceを検査。SBOMとsource/artifact identityを結び付け、依存licenseと再配布条件をNOTICEへ反映。

公開証明書とprivate keyを区別し、合成fixtureの許容は必要箇所だけに限定します。第三者依存のGPL等はNOTICE追記だけで完了にせず、combined workの配布条件・対応source・build手順を確認します。

## NewPipe/NIOを含む配布の条件

NewPipeExtractorはGPL-3.0-or-laterで、直接リンクした組合せをChopLabのMIT表示だけで配布可能とは扱いません。元コードのMIT表示を保持しながら、組合せ全体と各依存に必要な条件を満たします。[GNUの組合せに関する説明](https://www.gnu.org/licenses/gpl-faq.en.html#GPLStaticVsDynamic)と[GPLv3の対応source・配布条件](https://www.gnu.org/licenses/gpl-3.0.html)を根拠とし、次を公開判定へ含めます。Preview/CI artifactという呼称だけでは、第三者への配布に伴う条件を免除しません。

1. **配布bytesとの対応**: `config/newpipe-dependencies.json` のruntime 7 JARとAndroid desugar 2 JARの解決graph・bytes/hashを検証し、APK内のD8/R8生成classとdesktopに同梱する実JARをsource・licenseへ対応付ける。config JARにも補助classがあるため、build用設定だけとして除外しない。hash一致は取得物の同一性であり、licenseやsourceの完全性の代用ではない。
2. **対応sourceとbuild**: 配布revisionのChopLab、NewPipeと必要な依存の改変に適したsource、生成設定・変換・build/install用script、必要なversion/取得手順を提供する。一般のtool/system libraryの範囲はGPL本文に従って判定する。source JAR、Git source manifest、ChopLabだけの `git archive` は単独で完全なCorresponding Sourceを示さない。NIOの公式source候補とbuild targetは[NOTICE](../NOTICE.md#android-nio-desugar-215--source-and-license-evidence)に記録済みだが、全class/header対応と再build一致は未検証。byte一致の再buildは対応を確認する検証方法であり、それだけをGPLの法的要件と読み替えない。
3. **実際の提供経路**: 配布binaryと一緒に完全なlicense本文・著作権/noticeを渡し、binary取得ページのすぐ近くから対応sourceを同等に取得できるようにする。GPLv3 6(d)の第三者server利用を選ぶ場合も、正しいsourceへの明確な案内と必要な可用性の責任は配布者に残る。最終APK/ZIPを再取得して本文とsource linkをreadbackする。現状のrelease asset一覧/SBOMだけでは、NIOを含む完全な対応source提供とAPKへのnotice配達は確認できていない。
4. **未確定なlicense対応**: NIO本体の `NOASSERTION` を残したまま一律Classpath例外の適用済みとはしない。configurationのBSD本文を含め、実classと元header/例外を照合する。既存のyoutubedl-android、FFmpeg、yt-dlp、Java runtime等も、それぞれの配布構成に必要な条件を別に満たす。機能やcodecを削ってサイズ・license判定だけを通過させない。
5. **provider受入との分離**: 実サービスへの取得試験は、素材の権利とサービスが許す取得・自動アクセスの条件、利用者の明示同意をそれぞれ確認してrootが実行する。[YouTube利用規約](https://www.youtube.com/static?template=terms)はダウンロード等と自動アクセスを別に制限しているため、権利者の許可・CC表記・アプリの同意だけでNewPipeによる取得が許可されたと推定しない。今回の依存調査では実providerを呼び出しておらず、許可根拠のある対象・方法を確定するまでは `PROVIDER_PASS` は未確認。softwareの配布条件も満たしたことにはならない。

これらの未確認事項が残る間は `PUBLIC_PASS` にしません。sourceの公開場所や配布条件を変更する外部操作はrootの配布判断で実施し、秘密鍵・token・私有音声を対応sourceへ含めません。

## CIと運用のreadback

段階1CではPR/main push/手動を使い、branch push重複を抑えます。移行中は `verify`、`Test and package EXE`、`History scan and dependency SBOM` の既存check名を維持。Linuxはcommon/JVM/Android test・lint・APK・小emulator、WindowsはOS test/package/start-stop smoke、policyはsource/history/artifactを担当します。skip時もrequired checkがpendingのまま残らない構成にします。artifactは7日を基本とし、SBOMの重い収集はrelease/full runへ集約可能です。

main/tag rules、CODEOWNERS、review、force-push/delete禁止、secret scanning/push protection、private vulnerability reportingは管理者設定です。設定のreadbackなしに有効と断定しません。404/403は保護存在の証拠になりません。失敗や保護を迂回せず原因を示します。

```bash
gh api repos/dj-thank/choplab-sampler/rulesets
gh api repos/dj-thank/choplab-sampler/branches/main/protection
gh api repos/dj-thank/choplab-sampler/actions/permissions/workflow
```

配布を受け取る人は同梱SHA-256を照合し、attestationがある場合はrepository/sourceと一致するか検証します。attestationはplatform code-signing、脆弱性不存在、実機品質の保証ではありません。
