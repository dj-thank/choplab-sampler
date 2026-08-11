# 検証記録

作成日: 2026-07-15

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
