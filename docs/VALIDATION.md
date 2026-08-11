# 検証記録

作成日: 2026-07-15

## 2026-08-12 v0.11.1 live-control and realtime-reliability candidate

- TDD RED/GREEN seams: live loop pitch/tone/level without cursor restart, reusable playback cursor/voice, bounded command overflow/order, out-of-band Stop All boundary, concurrent source stop state, and microphone worker completion
- full host gate: 137 tests / failures 0 / errors 0 / skipped 0; Lint 0 errors / 11 advisories; assemble PASS
- configured offline project validation PASS; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; UI scroll API scan zero matches
- local APK: versionCode 16 / versionName 0.11.1; 30,739,399 bytes; SHA-256 `354571D8390BA8F86B20DBEA53E3954912A8FECA47D9171253E38B864FAB4059`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- dedicated Pixel 9 / API 36 emulator `emulator-5590`: data-preserving `adb install -r` PASS; retained 5,316,915-byte autosave stayed SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513` before and immediately after install
- emulator runtime: version 0.11.1 cold launch, source playhead movement, Chop, Beat, selected A-04 loop playhead, live KEY change-and-return, and direct Scratch entry observed; process alive; scoped fatal/ANR matches 0
- accepted candidate captures: `work/v0111-final/01-launch.png`, `02-source-playing.png`, `03-chop.png`, `04-beat.png`, `05-live-key-loop.png`, and `07-scratch.png`
- after the install-integrity checkpoint, intentional KEY test operations produced a newer autosave; no claim is made that the archive stayed byte-identical after those user-equivalent edits
- physical Pixel 9a `5A121JEBF08094`: not present in ADB/mDNS/current Windows USB inventory; data-preserving phone install and physical sound/touch checks pending
- public PR/CI/tag/Release and reverse-downloaded asset identity pending; local/emulator evidence is not promoted to PUBLIC_PASS or HUMAN_GO

## 2026-08-12 v0.11 safety, coaching, and fixed-landscape validation

- TDD RED/GREEN seams: source/project operation epochs, delayed mic/device/vocal completion, autosave revision arrival order, applied source-playback state, finite Scratch input, destructive import intent, state-based Chop coaching, compact Beat coaching, and landscape workspace policy
- full host gate: 125 tests / failures 0 / errors 0 / skipped 0; Lint 0 errors / 11 advisories; assemble PASS
- configured offline project validation PASS; Gradle Wrapper SHA-256 matched
- UI source scan: zero `verticalScroll`, `horizontalScroll`, `LazyColumn`, or `LazyRow` matches
- local APK: versionCode 15 / versionName 0.11.0; 31,516,578 bytes; SHA-256 `37D60CB25D7FC996B68BC83F7FDDCAFA3DE770117ABC1A072A53A8C256B7CC85`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- dedicated Pixel 9 / API 36 emulator `emulator-5590`: data-preserving `adb install -r` PASS; 5,316,915-byte autosave SHA-256 stayed `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513` before and after
- emulator cold launch: package version 0.11.0, process alive, focused `FATAL EXCEPTION` / app ANR count 0
- accepted fixed-layout captures: portrait Chop `work/v011-audit/20-chop-final.png`; landscape Chop `23-chop-landscape-final.png`; compact landscape Beat `26-beat-landscape-final.png`; landscape Beat Details `28-beat-details-landscape.png`
- independent read-only review found the remaining P1 ViewModel optimism and P2 pending-start navigation gaps; start/stop now preserve the last audio-thread-applied value, pending copy explains when PAD capture is safe, a second tap cancels pending playback, and every non-Chop stage stops source playback. The transition layers are covered by the 125-test gate
- final exact-HEAD read-only review at `a91a3b433f173799db8ed00b63b587bda26c8a61`: P0/P1/P2 none; `git diff --check` PASS; effective delegated model metadata was unavailable, so no runtime model claim is made
- physical Pixel 9a `5A121JEBF08094`: absent from the final ADB inventory; install and physical sound/touch checks remain pending
- PR #20 merged as `1e0446a29ba245383149de9bfab7863bd69b87e8`; branch `31522964955`, PR `31522968714`, main `31523293224`, tag verification `31523626784`, and release `31523626790` runs PASS
- public prerelease: `v0.11.0-preview.1`; reverse-downloaded APK 30,723,019 bytes; SHA-256 `04F7284DB3EF90F37561259BF1E0DBCDE59D4AD6A06A448B8729A942AC902B39`
- GitHub asset digest and checksum sidecar match; package/version/minSdk/targetSdk and APK Signature Scheme v2 verified; public certificate SHA-256 `E2A9863BAAB8940BD1716D088118C1E766867CCEA48641678192F7B187F2CD1F`
- exact public APK install is not claimed: its CI certificate differs from the installed local build, and preserving retained app data takes priority; Human GO is not claimed

## 2026-08-12 simple Chop and project-isolation validation

- RED/GREEN seams: complete project reset, new-source replacement, PAD start/end trim, and assigned-vs-empty live Chop routing
- full host gate: 103 tests / failures 0 / errors 0; Lint PASS; assemble PASS
- offline project validation PASS; Gradle Wrapper SHA-256 matched; `git diff --check` PASS
- UI source scan: zero `verticalScroll`, `horizontalScroll`, `rememberScrollState`, `LazyColumn`, or `LazyRow` matches
- local APK: versionCode 14 / versionName 0.10.0; 30,641,099 bytes; SHA-256 `2AD63450619685094DBFAB4B5E49E10AD4A51432181995767091023F8AF28E9C`
- physical Pixel 9a `5A121JEBF08094`: data-preserving `adb install -r` PASS; app data was not cleared; installed metadata reports versionCode 14 / versionName 0.10.0
- phone Download copy: `/sdcard/Download/ChopLab-v0.10.0-preview.1-local-debug.apk`; device SHA-256 matches the PC artifact
- physical Pixel restored its prior source before the user switched foreground apps; destructive source replacement/reset was intentionally not invoked on the user's saved project
- clean emulator launch showed `A MELODY`, no source, and no residual PAD content; further emulator interaction was stopped when another active task took over the shared emulator
- two-axis local parent review found two implementation gaps and both were fixed: reset-save job ownership, and active feedback during PAD scratch
- not claimed: subjective scratch/audio quality, measured latency, physical long-press trim flow, destructive reset on the user's project, exact-public-APK installation, or Human GO
- PR #18 merged as `74944a1c806b312d19364fcb11dfa6d4759cd5a0`; branch `31511983934`, PR `31511988332`, main `31512350479`, tag verification `31512681213`, and release `31512681328` runs PASS
- public prerelease: `v0.10.0-preview.1`; reverse-downloaded APK 30,641,099 bytes; SHA-256 `83F641A154A0287BAA29230F863257CB0C91698F65F7FF2BFE045A1CBB12FD25`
- GitHub asset digest, checksum sidecar, package/version metadata, APK Signature Scheme v2, PC reverse download, and Pixel `/sdcard/Download/ChopLab-v0.10.0-preview.1-public-debug.apk` all match
- exact public APK install is not claimed: its CI debug certificate differs from the installed local build, and preserving the user's app data takes priority over uninstall/reinstall

## 2026-08-11 v0.9.3 playable Beat selection validation

- TDD: `PlayablePadSelectionTest` and `BeatLaneAccessibilityTest` observed RED for the new public seams, then PASS
- full host gate: 98 unit tests / failures 0 / errors 0 / skipped 0; Lint PASS; assemble PASS
- offline project validation PASS; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; scroll API scan 0 matches
- local APK: 31,360,414 bytes; SHA-256 `3587D5CCC3BCB216D9E8FA231267420F785206388E4396F8389E023E13C34C20`
- Pixel 9 / API 36 emulator: in-place `versionCode=13`, `versionName=0.9.3` install; existing project restored; Beat entry selected playable `A-04`
- emulator interaction: tapping empty `A-06` retained `A-04` and showed `A-06は空です。音の入ったPADを選んでください`
- emulator interaction: tapping empty `PAD 17–32` retained `A-04`, showed the empty-page guidance, and runtime hierarchy contained no scrollable node
- physical Pixel and public GitHub Release remain separate pending gates
- PR #15 merged as `27d1c7ce3e1487ac23311a48674014b4edad4e22`; branch `31496922708`, PR `31496975115`, main `31497276645`, tag verification `31497582713`, and release `31497582655` runs PASS
- public prerelease: `v0.9.3-preview.1`; reverse-downloaded APK 30,591,947 bytes; SHA-256 `2B1A8453830CC7D2BBB6DE2CFB8064054EE208A14C22B4108171F889F841B600`
- GitHub digest, checksum sidecar, package/version metadata, and APK Signature Scheme v2 all match
- public APK emulator update: not claimed; Android rejected the CI-signed APK over the locally signed install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; no app data was deleted
- physical Pixel: not connected; exact public APK install/copy remains pending

## 2026-08-11 GitHub Actions runtime maintenance

- official latest stable releases were resolved through the GitHub API and pinned to exact commit SHAs for checkout v7.0.1, setup-java v5.7.0, setup-android v4.0.1, setup-gradle v6.3.0, upload-artifact v7.0.1, and download-artifact v8.0.1
- this removes deprecated Node.js 20 / setup-java v4 dependencies while preserving immutable action pins
- Android verification and release-workflow smoke results are recorded after provider execution

## 2026-08-11 v0.9.2 accessibility semantics validation

- regression-first host test reproduces and covers the 32-PAD Beat announcement bug
- Beat states announce plain Japanese labels instead of internal enum names
- selected semantics added to workflow tabs, machine toggles, PADs, sound rails, and Beat-bank selectors
- focused accessibility tests: 2 / failures 0 / errors 0 / skipped 0
- full host gate: 85 unit tests / failures 0 / errors 0 / skipped 0; Lint PASS; assemble PASS; offline project validation PASS; scroll API scan 0 matches
- local APK: 31,362,206 bytes, SHA-256 `0F279F715AF9341BD47FA1FCB3463F1D98607EA0291B84618EC111F8C25283F2`
- Pixel 9 / API 36 emulator: in-place `versionCode=12`, `versionName=0.9.2` install; restored-project launch and Beat A-20 selection stayed alive with no fatal exception and no scrolling
- runtime UI hierarchy contains `BANK A メロディー PAD 20`, `メロディー ステップ1 オフ`, and no `SELECTED_SOUND`/`OTHER_SOUND` enum leakage
- physical TalkBack traversal remains pending until the phone reconnects
- PR #12, main verification, tag verification, and preview release workflows: PASS
- reverse-downloaded public APK: 30,575,563 bytes, SHA-256 `BCE8A07E57E25255C57816DA21D9067A88C7B41A94E6485CA92D7A32C7B0BC5F`; GitHub digest and checksum sidecar match

## 2026-08-11 v0.9.1 clarity audit validation

- combined screenshot/UX audit captured seven v0.9.0 flow states and four accepted post-fix states on Pixel 9 / API 36 emulator
- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: PASS
- unit tests: 83 / failures 0 / errors 0 / skipped 0
- lint: task PASS, errors 0
- local APK: 30,575,559 bytes, SHA-256 `5F5059DDC6C1EFC7BA1F1FFDCED37F7BACCC81AAA7731437F0C616231E227546`
- improved CHOP: duplicate input row removed and waveform expanded without scrolling
- improved PADS/Layer: page occupancy labels visible; Layer loop START label no longer clipped
- improved Scratch: waveform tap says and performs slice selection; `SOURCE RANGE` preserves existing chop markers
- physical audio, latest-device screen, TalkBack, multi-touch, and haptic quality remain human checks

## 2026-08-11 v0.9.0 current validation

- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: PASS
- unit tests: 81 / failures 0 / errors 0 / skipped 0
- `scripts/validate_project.sh`: PASS with the pinned JDK/Kotlin toolchain on PATH
- `git diff --check`: PASS; app source scroll API scan: 0 matches
- lint: task PASS, errors 0 (10 Android/toolchain advisories reported)
- local APK: 30,716,854 bytes, SHA-256 `F27FAB5034687E165554578C8F859E12A096FC7C05A93DED0BA499C3070AC867`
- Pixel 9 / API 36 emulator: exact schema-4 Pixel archive restored under schema 5; CHOP/PADS, PAD 17–32, BEAT direct KEY controls, Layer SOUNDS, and source-range Scratch were captured without scrolling
- BEAT navigation regression: reproduced one stale 32-vs-16 size assertion crash, corrected it to the visible page size, rebuilt, and verified the process remained alive on the same route
- Pixel 9a: in-place install/launch, `versionCode=10`, `versionName=0.9.0`, four-stage fixed UI, editable source waveform in PADS, on-device manual boundary insertion, role-aware square PADs, and four-lane Beat board observed
- Pixel 9a latest APK: installed in place and copied to `/sdcard/Download/ChopLab-0.9.0-latest.apk`; PC/device SHA-256 matched. The phone remained locked, so latest-screen and subjective-audio checks are not claimed
- source-end replay regression: host test passed and physical device changed `SOURCE PLAY` to `SOURCE STOP` after a previously completed source
- source-playing B-01 press with `LIVE CHOP OFF`: autosave hash unchanged immediately before/after, source remained playing, process remained alive
- physical microphone capture was not activated; loop de-duplication audio, source scratch sound, latency, multi-touch endurance, TalkBack, and haptic quality remain human checks

## 2026-08-11 v0.8.0 current validation

- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`: PASS
- unit tests: 66 / failures 0 / errors 0 / skipped 0
- lint: task PASS, errors 0, Android/toolchain advisories 10
- `scripts/validate_project.sh`: PASS with the pinned JDK/Kotlin toolchain on PATH
- `git diff --check`: PASS
- app source scroll API scan: 0 matches
- Pixel 9a / Android 17 / arm64-v8a: final APK install, launch, kit application, square PAD layout, fixed Layer Studio, and schema-4 autosave restart observed
- public PR #8, main verification, tag verification, and v0.8.0 preview release workflow: PASS
- reverse-downloaded public APK: 30,477,259 bytes, SHA-256 `D3C26D20023A9D25B19E316D1C77A44D067DCA7717DDA3BDA2F82067A58EC1A8`; GitHub digest, checksum sidecar, PC download, and Pixel `/sdcard/Download` copy matched
- the installed Pixel app is the same-source locally signed build; the exact public CI-signed APK was copied to Downloads but not installed
- microphone vocal capture was not activated on the physical phone to avoid recording ambient user audio; scratch sound quality and latency remain human/device-audio checks

## 実施済み

### 1. Android非依存Kotlinコンパイル

次のファイルをローカルのKotlin/JVM compilerでコンパイルしました。

- `SamplerModels.kt`
- `TransientDetector.kt`
- `WavFileWriter.kt`
- `PatternRenderer.kt`

### 2. Pure logic smoke test

`scripts/run_pure_logic_smoke.sh`により次を確認します。

- synthetic percussionから複数transientを検出
- unordered/duplicate markerからcontiguous slicesを生成
- WAV RIFF/data sizeがclose時に更新される
- 16-step patternをWAVへrenderできる
- output frame countとheaderが矛盾しない

### 3. XML

- `AndroidManifest.xml`
- `strings.xml`
- `ic_launcher.xml`
- `ic_stat_waveform.xml`

をXML parserへ通します。

### 4. Android依存コードのオフライン型検査

`app/src/main`と`app/src/test`の全Kotlinファイルを、Android／Compose／Lifecycle／Coroutineの必要シグネチャを持つ軽量スタブと合わせてKotlin/JVM compilerへ通し、最終状態でerror 0、project-source warning 0を確認しました。

これは構文、return、nullability、関数シグネチャ、主要な型の接続を検査するための補助テストであり、実Android SDK、Compose compiler plugin、AGPによるビルドの代替ではありません。

### 5. Gradle Wrapper

同梱の`gradle-wrapper.jar`についてSHA-256を検査しました。

Expected / Gradle 9.5.0 wrapper JAR:

```text
497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
```

Gradle distributionは`gradle-wrapper.properties`で次のSHA-256へ固定しています。

```text
553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746
```

## 初期生成環境で未実施だった項目（履歴）

- `./gradlew :app:assembleDebug`
- Android Lint
- Compose Preview rendering
- Emulator boot
- Physical-device recording/playback test
- Device-specific input/output latency measurement
- Playback Capture compatibility matrix
- Android 14/15/16 background/foreground lifecycle test

理由: この生成環境にはAndroid SDKがなく、Gradle/Maven/Android SDK配布先への通常のネットワーク解決も利用できませんでした。Gradle Wrapperはその制約によりdistributionを取得できません。

## Android Studio側で推奨する最終確認

```bash
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

実機では最低限、次を確認してください。

1. ファイル読込と10分制限
2. マイク開始／停止／権限拒否
3. 端末音録音の許可／拒否／録音元opt-out
4. 録音中にアプリをbackgroundへ移動
5. 4/8/16/transient/manual chop
6. S/Eと境界dragを最大zoomで操作
7. AUTO NEXTでsliceとPADが同期して前進
8. Gate releaseとchoke group
9. 長時間PAD連打時のunderrun/thermal behavior
10. 4-bar WAVのduration、tempo、swing、pitch、reverse
