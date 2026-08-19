# Toolchain source record

これは 2026-08-09 時点の toolchain 準備・出典記録です。インストール済み状態や最新の build/AVD 成否をこの古い記録だけから推定しないでください。最新の source/receipt と gate は [`docs/PROJECT_STATE.md`](PROJECT_STATE.md) および [`docs/VALIDATION.md`](VALIDATION.md) の current snapshot / dated entries を参照します。

Checked on 2026-08-09. All portable tools were downloaded into the task workspace; no machine-wide installation was performed. To keep build/download churn off the system NVMe SSD, `C:\Users\rambo\Documents\ChatGPT\pad\work\tools` is now an NTFS junction to `F:\CodexData\ChopLab\tools` on the 4 TB HDD. Existing project-local paths remain valid.

## JDK 17

- Provider: Eclipse Temurin / Adoptium.
- Asset: `OpenJDK17U-jdk_x64_windows_hotspot_17.0.20_8.zip`.
- Official API: `https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse`.
- Installation guidance: `https://adoptium.net/installation/`.
- Expected and observed SHA-256: `418497be5cf585bdd2203d6486a565d66d3f5e992d5630d45104cb873fab8122`.
- Observed runtime: Temurin `17.0.20+8` for both `java` and `javac`.

The JDK is extracted under the workspace tool directory and selected only through process-local environment variables.

## Kotlin compiler

- Provider: JetBrains Kotlin official GitHub release.
- Asset: `kotlin-compiler-2.3.21.zip`.
- Official release API: `https://api.github.com/repos/JetBrains/kotlin/releases/tags/v2.3.21`.
- Expected and observed SHA-256: `a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f`.
- Observed compiler: `kotlinc-jvm 2.3.21` on JRE `17.0.20+8`.

This compiler was used by the offline smoke script and the direct JUnit test harness while the Android SDK license gate prevented the Gradle Android task from resolving packages.

## Android command-line tools

- Provider: Android Developers.
- Asset: `commandlinetools-win-15859902_latest.zip`.
- Official download page: `https://developer.android.com/studio`.
- Official and observed SHA-256: `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a`.
- `sdkmanager` documentation: `https://developer.android.com/tools/sdkmanager`.
- Observed command-line tools version: `22.0`.

The tool emits a deprecation notice recommending the newer Android CLI. The current project scripts and package manager still use `sdkmanager`; no global SDK state was changed.

## Platform-Tools / adb

- Provider: Android Developers.
- Release notes: `https://developer.android.com/tools/releases/platform-tools`.
- Stable Windows URL: `https://dl.google.com/android/repository/platform-tools-latest-windows.zip`.
- Observed SHA-256: `45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99`.
- Observed `adb` version: `37.0.1-15733141`.

The stable URL is rolling, so the recorded hash identifies the bytes observed on 2026-08-09 rather than a permanent upstream version contract.

## Required Android SDK packages

The Gradle project requests or documents the following packages:

- `platform-tools`
- `platforms;android-36`
- `build-tools;36.0.0`
- `ndk;27.2.12479018` for the later native milestone
- `cmake;3.22.1` for the later native milestone

The platform and build-tools packages are not installed yet. The official license prompt requires acceptance by an authorized user; Codex did not accept it on the user's behalf. Consequently, no Gradle Android test/build result or APK is claimed.

The command-line tools also point to the newer Android CLI documentation at `https://developer.android.com/tools/cli`. Migration is deferred until it can be evaluated without changing the baseline build contract.
