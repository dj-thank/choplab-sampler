# H13 Desktop long-press: local input evidence

観測: 2026-09-01T05:02:25.8775265+09:00

## 結果と修正範囲

H13の実Desktop Compose/JVM mouse入力fixtureは14件PASS（UI入力9件＋既存controller入口5件、failure/error/skip 0）。これはoffscreen component evidenceであり、OS mouse配送やphysical/Human品質の成功ではない。

mainのCHOPでSource STOPPEDの割当済みPADを長押しすると、TRIMへ移る前のpointer-downで既存範囲が上書きされていた。8 kHz/10秒Source、A02の16000..32000が0..8000となる実stateと描画をrun03で再現。rootが読戻し、`DesktopSamplerController.capturePad`だけの修正を許可した。

修正は既存shared `resolvePadPressAction` のCAPTURE規則を使い、STOPPED assignedは既存trigger、空PADは既存select、PLAYINGは従来のlive assignment、recording/pending-sourceは拒否へ振り分ける。既存loading guardも維持した。shared UI・TRIM数値・audio/decoder/Spotifyは変更していない。

## Exact object

- Worktree: `F:/CodexWork/choplab-desktop-longpress-20260901`
- Branch: `codex/desktop-longpress-ui-20260901`
- Base: `0f5b672afb0e6b67e95290c31900ff5c8abc0ef4` / tree `939f954e978b86b509ed162ed68cc4dd5f091372`
- Tested implementation commit: `1f96ef8db2e6f55efb7b8764900293338e70fd2d`
- Tested implementation tree: `738c27018ad635631847f36b5382802e2d80f1aa`
- Production/test/build bytes remain frozen at the tested implementation above. Root authorized one documentation-only successor commit for this receipt and the four prepared SSOT/plan documents. The original `work/h13-local/final-state.json` is local-only historical evidence and is not available in a clean clone.
- Scope and root's accepted production correction: `C:/Users/rambo/Documents/Codex/2026-08-29/new-chat-3/work/session-governance-20260831/choplab-h13-desktop-longpress-scope.md`
- Clean-clone equivalents are the committed [controller tests](../desktop/src/test/kotlin/com/choplab/desktop/DesktopSamplerControllerTest.kt), [actual-input tests](../desktop/src/test/kotlin/com/choplab/desktop/ui/DesktopLongPressUiTest.kt), and the later [v0.17.1 review-repair receipt](PR83-second-review-repair-20260902.md). Raw run logs and PNGs listed below remain hash-addressed provenance only; they were deliberately not published as repository files.

## 実入力と期待値

全UIケースは実`OtohiroiDeck`＋実`DesktopSamplerController.state`を使う。初期値はF内のsynthetic projectをpublic `openProject`で読み、gesture後のstateを直接設定しない。mouseイベントは`ImageComposeScene.sendPointerEvent`のMouse Move/Press/hold/Releaseで送り、semanticsのOnLongClickを直接呼ばない。

| 対照 | 観測契約 |
| --- | --- |
| CHOP assigned hold | selection 0→1、範囲16000..32000保持、TRIM viewport14000..33999 |
| BEAT assigned hold | 同じ既存範囲と初期fit。変更前からPASSし、TRIM数式が原因ではない対照 |
| assigned普通click | 範囲保持、TRIMなし、silent PAD要求1回 |
| empty click/hold | 選択のみ、割当なし、TRIMなし、PAD音声要求なし |
| waveform長押し | 75%位置でEND=29000、viewport25000..32999（1秒） |
| waveform普通click | 同じEND移動、viewport14000..33999を保持し精密zoomしない |
| Source先頭 | Chop0..4000、初期viewport0..7999、中央tieはSTART=2000、focusはSource先頭でclamp |
| Source末尾 | Chop76000..80000、初期viewport72000..79999、END=78400、focusはSource末尾でclamp |
| live mouse capture | UIのチョップ開始→silent Sourceを24000へ進める→空PAD clickで24000..80000をcapture |
| controller対照5件 | STOPPED assigned/empty、PLAYING capture、recording拒否、実pending Source import拒否 |

初期fitの数値は現行`SamplerCommands`の1秒floor／5:4 context／Source clamp、精密focusは1秒上限に由来する。新しいユーザー評価metricではない。START/STOPの`PendingSourceCommand`は既存shared routingへ渡す配線を保持する。Desktopが通常公開しないenum stateをprivate fieldで注入したUI成功とは主張しない。

## REDと感度負対照

- run02 / run20: 40 msのshort clickをlong-press期待へ流すとTRIM nodeが存在せず失敗する。**テスト感度の負対照**であり製品不具合のREDではない。
- run03: 実700 ms holdでTRIMが開いた一方、既存start16000→0へ変わる。これは**実製品RED**。
- run07: public controllerの最小STOPPED-assigned呼出しでも範囲が変わるRED。
- run08以降: 同じoriginal UIシナリオがGREEN。run21全14件、run22 exact implementation commitの全14件がPASS。
- run06のconstructor argument誤りとrun11のlive end期待誤りはfixture側で訂正し、製品不具合数に含めない。

## 実行環境・コマンド

- Windows 11 / JavaのOS version `10.0`、既存Temurin JDK `17.0.20+8`
- 既存Gradle `9.7.1`、Kotlin `2.4.10`、Compose ui-desktop `1.11.1`
- `java.awt.headless=true`、`skiko.renderApi=SOFTWARE`、offscreen 1100×1000 / density1
- Gradle workers最大2、test fork1、in-process Kotlin compiler。test targetは毎回実入力を実行し、古いXMLのUP-TO-DATE再利用をしない。
- audio/recorderはsynthetic/fail-closed port。autosaveなし。Source/importと録音guardのfixtureファイルだけFのtest tempへ置く。
- 新依存0、dependencyネット取得0、version変更0。既存ChopLab module cacheだけをFへローカルコピー（3491 files copied、lock/gcは除外）。既存cacheへ書き込まず、[Gradleのcache再利用手順](https://docs.gradle.org/current/userguide/dependency_caching.html#sec:copying-dependency-cache)の配置を使用。

再実行runner（このF lane内だけ）:

```powershell
& .\work\h13-local\run-gradle.ps1 -RunLabel <unused-label>
```

実際のGradle呼出し:

```text
C:/Users/rambo/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle.bat
:desktop:desktopLongPressUiTest --offline --no-daemon --max-workers=2
--no-watch-fs --console=plain -Pkotlin.compiler.execution.strategy=in-process
-Porg.gradle.java.installations.auto-download=false
-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
-Djava.io.tmpdir=F:/CodexWork/choplab-desktop-longpress-20260901/work/h13-local/tmp
```

`JAVA_HOME`は既存JDK17、`GRADLE_USER_HOME`は当laneの`work/gradle-user-home`、TEMP/TMPは`work/h13-local/tmp`へprocess限定。上の最後の`-Dorg.gradle.jvmargs`は実commandでは一引数だった。正確な引数配列・PID・creation・cwdを持つ元の`work/h13-local/runs/22-fixed-product-readback/process.json`はlocal-onlyで、clean cloneには含まれない。

run22は`BUILD SUCCESSFUL in 27s`、16 tasks（3 executed/13 up-to-date）。UI9 tests=14.472秒、controller5 tests=0.798秒。No Android/packageWindows/full historical suite。同じ14件を別runで再実行してもunique test数を加算しない。

## XML

| Exact XML | Tests | Fail/Error/Skip | SHA-256 |
| --- | ---: | --- | --- |
| `work/h13-local/runs/22-fixed-product-readback/TEST-com.choplab.desktop.DesktopSamplerControllerTest.xml` | 5 | 0/0/0 | `7402FA03E4A4F66F4F4EAD682A004975C35B2FD7A9CD9825EA90E600F873AAB1` |
| `work/h13-local/runs/22-fixed-product-readback/TEST-com.choplab.desktop.ui.DesktopLongPressUiTest.xml` | 9 | 0/0/0 | `4C673E090FAE23A79AD1D65F0F77CEC03CCDAD219A9EF5B6CC805190A2A3B7F1` |

## 描画と負対照artifact

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `assigned-before.png` (local-only) | 69289 | `D188383973034148EE9A7DC15EB7D782EF23886DBACC0940C5EBC6A5788A6749` |
| `assigned-after.png` (local-only) | 97283 | `1B9AAECC2C97B5645AE1F1B79155C5C2573AF1940D10CE68A361911D3E07E0C6` |
| `waveform-end-focus.png` (local-only) | 99218 | `49ABF6CFC2AAEC73A26D09B62A592648DD78F33D9D9412F4FE1E2E0413991E06` |
| `source-start-focus.png` (local-only) | 98437 | `3B5150BE9DE2F95E52BC5EA3CA5AF60431468D6AE1FBFCFE927E4AC17B252673` |
| `source-end-focus.png` (local-only) | 98861 | `5E802F33309C5AA13F3D98F9CE44CD5837B1F40BAA8817196EC1DA98357716BB` |
| `assigned-after.txt` (local-only) | 14333 | `D522BC3198CC04FE76419D96EA5A78DA62DB4783D328A45B9C0639D3927EDA86` |
| `run03/test-results.xml` (local-only) | 3215 | `20093F69D7BA0E170C5A5626D6123ED9754E38C7B4D6E02E6321639D2836CA65` |
| `run03/assigned-after.png` (local-only) | 99215 | `7E137B5E02099AC45F40EA67B12E2405915959159DCDDC7C41517FCD5ACFCCB6` |
| `run20/test-results.xml` (local-only) | 3274 | `76BBBB88A1119AE4CD6D834756748C0ED00E504322DCB7012A8456F2DB5C8696` |

PNGはruntime出力の観測でありgolden expected imageではない。現在の後置state、波形viewport、画像boundsはテストassertで確認している。

## Source hashes

SHA-256は実行したF working-file bytes。Git blobも併記し、改行表現によるraw hash差とGit objectを区別する。

| Source | SHA-256 | Git blob |
| --- | --- | --- |
| `desktop/build.gradle.kts` | `D400806387A04B46B4D73894C57B65883C6DD48AE9092C689C165536B8D36680` | `d137ce914e4954fbc87bc65e94de731d2861c06f` |
| `desktop/src/main/kotlin/com/choplab/desktop/DesktopSamplerController.kt` | `43FC2F42DC7B054E95673D27B0FAD9442C07F54D663D713C13DF623BD8737256` | `b8873dbb8255a161a7dfd8bb956b692127ca54b4` |
| `desktop/src/test/kotlin/com/choplab/desktop/DesktopSamplerControllerTest.kt` | `35FE4AFC0626AE9D84403F1A112778F1269466E489E9A0F083B4B6FE9CB6D772` | `6063d165a06b0779a712a9f6a65a95da27497c0e` |
| `desktop/src/test/kotlin/com/choplab/desktop/ui/DesktopLongPressUiTest.kt` | `BCD431019D3400B24B2DB33C0C99733C48959ED64E70E7F40F4FBAA978BBC7AE` | `303142a4dee19b7dd87a78c842f58f761bc03618` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/model/SamplerCommands.kt` | `8928D7E55C11304E33B730711369146EE9950AE37039A458AB833B344F01941A` | `8cd07d0774a3e70a19fbc64f472a59f60454be31` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/model/PadPressRouting.kt` | `05176C2145563CDF8212291B3EE850C4CC8AE5632AF05BD6AE6AFE838FF34F83` | `13910a6b15acf8b197aaeddd4e79182fe1dd5377` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/ui/OtohiroiDeck.kt` | `9CEE5F58ECA5C237F5005666EDC05845B7553FC908D4B06A4C24B8889FCB2E22` | `5be5c4794db9b299ce0d602eafd1a9a9baceccf7` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/ui/PadGrid.kt` | `647FDAF1847D5017BD4872F8DD3F0C9D4893A5B573ED9F0C75688615DE2B7C33` | `379e07f649d5e96ee96574395c0780f68ba7f877` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/ui/WaveformEditor.kt` | `16BBAE69212C54CBBC3DCFA7B45E81A6DAF39A03E7343DAC88D84FDA8415028F` | `2b5fa199f0e3b7a95546870fa54815da47753ecf` |
| `shared/src/commonMain/kotlin/com/choplab/sampler/ui/PrecisionTrimOverview.kt` | `E67CC71F75C404A13E1BA01F2AD23471C23D8C9AF5AE8452110445061A7A6973` | `53e79b25d731f6d77bdf614d27e1efdcffa4c6a9` |

## 原状readbackと残る境界

- Primary `6033d85b68c9b67f767a31b8878dbe4f4be3392c`、status8 tracked/165 untracked、status digest `CAB9B1DAB6DFD83C16189DC2FC08D5583731268FF597BC295A86F6E79EA6437F`は開始時と一致。これはstatusの比較であり、旧dirty全file内容の新たな監査ではない。
- 旧creative branch `4978c4c715fdc7116364e748f0a34cb1c2964e48`、carrier `f3051e4f2c82f9a293d24795b3391929f3cf157e` / tree `ea6645f26c199129f3cc604fef0eaabdfe8b4f20`を保持。PR69へ操作なし。
- app/shared-main/jvm-core-main/ios、Desktop audio/provider/persistenceはbaseからdiffなし。production変更はcontrollerのcapturePadと必要なimportのみ。
- run22の記録済みowner/process PID 57184/11232/52436/48700はcreationを照合して全て終了済み。名前による一括killなし。Fのignored cache/log/screenshotは意図して保持する。
- `doctor`（ADB/auth照会）、通常validator（広いsuite）、`packageWindows`（app-image削除再作成）は作用確認後に実行しなかった。SDK/JDK/global install/config/ACL/cache cleanupなし。
- 実audio line/Clip、録音、Spotify、ADB、visible window、OS設定、GitHub/push/merge/releaseは未実施。APK/app-imageも未作成。
- Gate: **LOCAL_PASS / Desktop component input only**。OS pointer配送、実window/DPI、physical audio、Narrator、Human品質、provider/publicは未検証。独立V21 Standards/Specはroot担当でpending。
