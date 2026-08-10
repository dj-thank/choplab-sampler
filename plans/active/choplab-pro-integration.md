# ChopLab Pro を段階的に完成させる

## Purpose and user-visible outcome

Android 10 以降の実機で、許可された音声の取込・録音から、ステレオ対応の範囲選択とチョップ、64 PAD、パターン／Song、保存復元、Undo/Redo、MIDI、マスター／ステム書き出しまでを一つのオリジナルアプリで行える状態へ進める。各機能はコードの存在ではなく、公開境界のテスト、Gradle gate、APK、可能なら接続済み実機での観測結果により完了判定する。

## Current state

- 作業ルートは `C:\Users\rambo\Documents\ChatGPT\pad\work\codex-workspace\ChopLab-Codex-Workspace`。
- 入力 `ChopLab-Codex-Workspace.zip` の SHA-256 は `77d5ee4cc1ed40633a49aa67f87bfce75ab9893d70339aba93efd482a6ff70e4` で、ユーザー提示値と一致した。
- 入力 `ChopLab-Complete-Bundle.zip` の SHA-256 は `8cf8676b06eff129e310c448d927f895274129ab3b5d53422f60e27d0cd5dd5e` で、`original-archives/ChopLab-Complete-Bundle.zip` と一致した。
- Git の開始点は `65ee89a3982d2d5e51e91a055dc7e71937210c8e` (`main`, `codex-workspace-baseline`)。2026-08-09 19:10 JST 時点で tracked diff はない。
- `app/` は mono PCM、AudioTrack、単一 16-step pattern、4 bar mono WAV export の MVP。UI は Compose、状態と Android adapter の組み立ては `SamplerViewModel` が直接所有する。
- `reference/pro-v0.2/` は欠落型、欠落ヘッダー、欠落ビルド設定を含む未検証資料であり、ビルド対象ではない。
- 既存公開テスト境界は `SamplerUiState.sliceRanges()`、`stepKey()`、`TransientDetector.detect()`、`WavFileWriter`、`PatternRenderer.renderToWav()`。
- `scripts/doctor.ps1` の観測結果: Git とワークツリーは正常。JDK、Android SDK、PATH 上の adb は未検出。Codex CLI は見つかったが同スクリプトから認証済みとは確認できなかった。
- Git for Windows の Bash は `C:\Program Files\Git\bin\bash.exe` にある。`kotlinc` は未検出のため、純粋 Kotlin smoke はまだ再実行していない。
- Luna サブエージェントの最小起動は `Unknown model gpt-5.6-luna` で失敗した。現在の実行面が列挙したモデルは `gpt-5.6-sol` と `gpt-5.6-terra` のみ。別モデルへの自動フォールバックは行わない。

## Constraints and invariants

- `app/` のみを本番 Gradle target とし、`reference/pro-v0.2/` は変更しない。
- `minSdk = 29`、`compileSdk/targetSdk = 36` を明示的な製品判断なしに変更しない。
- 音声 frame range は start-inclusive / end-exclusive。sample rate、channel count、L/R 順を全境界で保持する。
- リアルタイム callback では heap allocation、blocking lock、file/network I/O、logging loop、UI 呼出し、重い JNI を行わない。
- control-to-audio は bounded queue、immutable snapshot、atomics のいずれかを用い、overflow policy と sample lifetime をテスト可能にする。
- import、project archive、render duration、asset count、frame count、総 PCM bytes、Undo history、MIDI queue、voice count を上限付きにする。
- `.choplab` は versioned schema、path traversal 拒否、duplicate ID 拒否、entry/展開サイズ上限、atomic/recoverable save を備える。
- Android Playback Capture は公式 API と録音元 opt-in の範囲だけを扱い、DRM／capture policy 回避を実装しない。
- AKAI/MPC のロゴ、固有 asset、firmware、project format、特徴的 trade dress を複製しない。
- local SDK path、signing key、secret、生成 APK は Git に commit しない。
- local/unit/build/emulator/physical-device/latency の証拠を分離し、未実施を成功扱いしない。
- 既存 MVP を各 milestone の終端で buildable に保ち、参照コードを一括移植しない。

## Architecture and interfaces

### State and domain

- `SamplerUiState` は画面投影に限定し、永続化対象の中心は versioned `ProjectSnapshot` とする。
- `PcmAudio` を stereo interleaved または明示 channel planes を持つ immutable/copy-safe asset へ移行する。UI waveform は bounded summary を参照し、長い PCM 全体を Compose が走査しない。
- `ProjectSnapshot` は audio asset IDs、pads、patterns、song sections、MIDI mappings、master settings を保持し、PCM buffer は history snapshot 間で共有する。
- pure command functions が assign/chop/pattern/song/history を更新し、ViewModel と MIDI の両方が同じ command path を使う。

### Engine and threading

- `SamplerPlaybackEngine` を Kotlin boundary とし、当面 `LegacyAudioTrackEngine` と `NativeSamplerEngine` を同居させる。
- UI/main thread は intent と state publication、background dispatcher は decode/archive/render、MIDI handler は parse と bounded forwarding、audio control thread は native snapshot 更新、Oboe callback は bounded DSP のみを所有する。
- JNI handle は create/start/stop/destroy を idempotent にし、destroy 後の呼出しと stream disconnect/restart を明示的に扱う。
- realtime と offline は同じ domain timeline と可能な限り同じ DSP primitive を用い、使えない箇所は numerical equivalence fixture を置く。

### Persistence and export

- `ProjectRepository` は app-owned autosave file と SAF URI の両方を扱う。保存は同一 directory の temp file、flush/sync、atomic move または回復可能な二世代方式とする。
- archive reader は entry を正規化し、root 外、absolute path、duplicate name/ID、symlink 相当、上限超過、truncated WAV/JSON を state mutation 前に拒否する。
- `OfflineRenderService` は progress/cancel と bounded duration を持ち、master/stem の wet/dry/master-FX policy を docs とテストに固定する。

### Migration and rollback

- MVP model から schema v1 への adapter を先に置き、UI と legacy engine の挙動を維持する。
- native engine は debug/runtime switch の背後で導入し、parity が得られるまで legacy engine を削除しない。
- schema は読み込み migration を追加方式にし、未知の newer schema は actionable error で拒否する。
- 各 milestone は個別 commit とし、最後の通過 commit へ通常の `git revert` で戻せる粒度にする。

## Test seams agreed for this run

ユーザーの「全部をCodexで進める」と同梱 `PRODUCT_REQUIREMENTS.md`／master prompt を合意済みの振る舞い仕様として、次の公開境界で red-green を行う。

1. domain command: chop/assign/pattern/song/history の入力 snapshot から出力 snapshot。
2. engine contract: start/stop/load/configure/trigger/release/transport/diagnostics と lifecycle。
3. project repository: stream/file save-load、migration、corrupt/oversized input。
4. render contract: timeline から stereo master/stems、frame count、channel identity、cancel。
5. MIDI parser/clock: raw byte stream から domain event。
6. ViewModel intent: fake interfaces を通じた permission/error/progress/state transition。
7. Android device seam: install/launch/import/record/capture/pad/save/reopen/export。端末上の観測だけを device PASS とする。

## Milestones

### Milestone 1: Baseline and portable build environment

- Scope: ZIP/Git truth、toolchain、既存 MVP の再現可能な tests/lint/APK。
- Files/interfaces expected to change: `plans/active/choplab-pro-integration.md`、必要なら Windows scripts と truthful docs のみ。machine paths は `local.properties`（ignored）に限定。
- Implementation steps:
  1. JDK 17 と Android SDK の既存場所を確認し、なければ workspace 内 portable toolchain を導入する。
  2. `scripts/validate_project.sh`、Gradle wrapper、unit、lint、assemble を最小失敗から実行する。
  3. baseline-only defects があれば依存の一括更新なしで修正する。
  4. adb device が見える場合は model/API/ABI を読み取りで記録する。
- Tests/checks: doctor、offline validation、`:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`。
- Acceptance evidence: exact logs、APK path/SHA-256、または exact blocker。tracked tree が意図した差分だけであること。

### Milestone 2: Stereo-capable domain and coexistence interfaces

- Scope: 既存挙動 characterization、versioned model、MVP adapter、engine/render interfaces。
- Expected paths: `app/src/main/java/com/choplab/sampler/model/`、`audio/` interfaces、対応する `app/src/test/`。
- Implementation steps: slice/auto-next/pad/sequence/export characterization を追加し、stereo asset と bounded snapshot を pure types で導入する。legacy adapter を通して現 UI を維持する。
- Tests/checks: one test per public behavior、unit suite、offline validation、lint、assemble。
- Acceptance evidence: mono MVP が adapter 経由でも同じ結果、stereo L/R identity と bounds の pure tests、build gate PASS。

### Milestone 3: Native Oboe foundation

- Scope: NDK/CMake/Oboe、`SamplerCore.h`、JNI/Kotlin wrapper、最小 sample path、bounded command transport。
- Expected paths: `app/src/main/cpp/`、`app/src/main/java/.../audio/NativeSamplerEngine.kt`、Gradle/CMake、native tests。
- Implementation steps: primary sourcesでversions/contractsを固定、create/start/stop/destroy、diagnostics、known sample、voice/transport、disconnect recovery の順に小さく統合する。
- Tests/checks: host/native tests、configured ABI builds、Kotlin boundary tests、real-time checklist、lint、assemble。
- Acceptance evidence: configured ABIs が build、callback allocation/blocking scan、legacy fallback 維持、device stream proof は実機観測時のみ。

### Milestone 4: Stereo pipeline, persistence and Undo/Redo

- Scope: decode/record/waveform/play/render の stereo、`.choplab`、autosave/recovery、bounded history。
- Expected paths: model、decoder、engine adapters、persistence package、ViewModel/UI contracts、tests。
- Implementation steps: schema/WAV codec と malicious input tests を先に置き、repository、atomic save、history、UI intent の順に接続する。
- Tests/checks: round-trip、migration、traversal、bomb/size、duplicate、truncation、rollback、coalescing、L/R identity。
- Acceptance evidence: valid round-trip equality、invalid input が旧 state を破壊しない、save-close-reopen は device-only gate を分離。

### Milestone 5: DSP, MIDI, Pattern/Song and stems

- Scope: independent pitch/time、ADSR/LFO/filter/FX、multi-pattern/Song、MIDI、master/stems。
- Expected paths: domain/DSP/native/offline/MIDI/export と UI wiring、tests。
- Implementation steps: DSP primitives と stability fixtures、sequence compiler、MIDI parser/clock、Android adapter、stem policy の順。各 slice で buildable を維持する。
- Tests/checks: NaN/Inf/clipping/feedback、envelope/LFO、pitch-duration independence、running status/clock、song expansion、stem isolation/equivalence。
- Acceptance evidence: numerical fixtures と full build gate。MIDI hardware/latency は物理機器観測時のみ。

### Milestone 6: Phone UI, lifecycle and release candidate

- Scope: すべての verified feature を original Compose UI から到達可能にし、permissions、foreground service、audio focus、process/lifecycle、accessibility、release docs を仕上げる。
- Expected paths: Compose、ViewModel/adapters、manifest/resources、docs、tests。
- Implementation steps: critical user journey、error/progress/cancel、destructive confirmation、touch target/content description、dead migration removal（parity 後のみ）。
- Tests/checks: unit/Compose/instrumented where feasible、`scripts/verify.ps1`、install/launch smoke、manual audio workflow checklist。
- Acceptance evidence: full gate logs、APK/SHA、device model/API/ABI と観測 workflow、truthful feature matrix。満たさない場合 plan は active のままにする。

## Progress

- [x] 2026-08-09 19:10 JST — 2 ZIP を path traversal 検査後に `work/` へ分離展開し、SHA-256 と同梱 Git baseline を確認。
- [x] 2026-08-09 19:10 JST — root/project guidance、ChopLab skill、master/phase prompts、要件、完了条件を読了。
- [x] 2026-08-09 19:10 JST — Windows doctor を実行し、JDK/SDK/adb 未検出と clean Git を観測。
- [x] 2026-08-09 19:24 JST — portable Temurin JDK 17、Android command-line tools、Platform-Tools を workspace 内へ checksum 検証付きで導入。
- [x] 2026-08-09 19:24 JST — adb が Pixel 9a を `device` として列挙することを確認（serial は報告から除外）。
- [x] 2026-08-09 — Kotlin 2.3.21 と workspace-local Gradle home を導入し、Gradle wrapper が JDK 17 上で起動することを確認。
- [x] 2026-08-09 — Red/Green で stereo-capable project domain、legacy adapter、pure pad assignment、engine/render coexistence interfaces を追加。
- [x] 2026-08-09 — offline validation と pure Kotlin JUnit 12 tests を通過。
- [x] 2026-08-09 — portable toolchain、Gradle caches、build/test output を `F:\CodexData\ChopLab` へ保全移設し、元の C: path を NTFS junction として維持。移設後の JDK/adb/Kotlin/Gradle/offline validation を再確認。
- [x] 2026-08-10 — Milestone 4のMVP persistence sliceとして、version 1 `.choplab`、manual save/open、900ms debounce autosave、fsync後の二世代復旧、最大40操作Undo/Redoを固定5工程UIへ接続。
- [x] 2026-08-10 — archive round-trip、共有audio identity、path traversal、過大manifest、truncated PCM、二世代fallback、history bounds/coalescingをpublic seamのJUnitで固定。
- [ ] 2026-08-09 19:24 JST — Android SDK license の authorized acceptance 待ち。Platform 36 / Build Tools 36.0.0 は未導入。
- [ ] 2026-08-09 19:10 JST — Milestone 1 portable toolchain と baseline build を確立中。

## Discoveries

- PowerShell `Expand-Archive` は空 directory を含む Workspace ZIP で内部 cleanup error を出した。事前に entry traversal を検査したうえで `tar -xf` により同じ workspace 内 target へ完全展開した。
- Windows filesystem は executable bit を表現しないため、展開直後に shell scripts が mode-only modified と表示された。local Git config `core.filemode=false` により byte diff なしの clean state を確認した。
- `codex-workspace-baseline` は annotated tag object を持つため、commit 比較では peel または merge-base を使う。
- WSL の既定 `bash.exe` は `docker-desktop` distribution を指すが、Git for Windows Bash は別途利用できる。
- 現タスクの spawn surface は project-local custom agent model の Luna を許可しておらず、独立監査は未実行。
- C: free space は portable tool archives 展開後に一時 0 bytes まで低下した。再取得可能な download ZIP 4個だけを検証後に削除して約397 MiBを回復し、後続の保全移設で解消した。
- 追加の storage maintenance 後、C: は NVMe SSD、F: は 4 TB HDD と確認。ChopLab の write-heavy paths を F: へ移し、C: free space は約39.8 GiBまで回復した。NDK/CMake は junction 配下の F: 実体へ導入する。
- Android SDK license prompt は法的権限を持つ本人の受諾を要求した。自動回答せず、Platform/Build Tools install は skip された。
- `PcmBuffer` の defensive copy、partial-frame rejection、project asset cross-reference bounds は初期 Green 後のレビュー用 Red test で追加し、validation を強化した。
- 固定点レビューで project/asset/pattern metadata と pattern event 数の未制限を検出し、失敗テストを追加して上限を導入した。PAD 再割当時に pitch/gain/reverse を保持する既存方針も characterization test で固定した。
- MVP archive writerが呼び出し元streamを先にcloseして`fsync`を失敗させる問題を二世代復旧testが検出した。codecをnon-closing boundaryへ変更し、呼び出し元がflush/sync後に世代交代する所有権へ固定した。

## Decision log

- 2026-08-09 19:10 JST — 完成 Bundle は provenance/reference、Codex Workspace は唯一の active repository とする。二重実装を避けるため。
- 2026-08-09 19:10 JST — ユーザーの「全部」と master prompt を製品 scope、PRODUCT_REQUIREMENTS と既存 public interfaces を TDD seams の合意根拠とする。
- 2026-08-09 19:10 JST — JDK/SDK が既存 machine scope にない場合、global install ではなく `work/tools` 以下の portable toolchain を優先する。作業境界外の変更を避けるため。
- 2026-08-09 19:10 JST — Luna failure 時に Sol/Terra へ自動 fallback しない。global routing rule に従い、parent で作業を継続する。
- 2026-08-09 19:24 JST — Android SDK license は Codex が代理受諾しない。authorized user の明示的同意後に workspace-local SDK へ導入する。
- 2026-08-09 — MVP を一括置換せず、pure domain と interface seam を先に追加する。既存 AudioTrack path を動作 fallback として保持し、後続 Oboe 実装を段階的に差し替え可能にするため。
- 2026-08-09 — source/Git は小容量のため C: に保持し、SDK/Gradle/build/test の write-heavy data だけを F: HDD へ junction で分離する。portable path compatibility と SSD write reduction を両立するため。
- 2026-08-10 — 完全なstereo Pro archiveを一括移植せず、現在のmono MVPで実際に再生可能な状態だけをversion 1 `.choplab`として先に保存する。未知schemaはfail-closedとし、将来migrationを追加する。

## Validation log

- Command: `scripts/validate_project.sh`, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`, scroll API scan and `git diff --check`.
  - Date/environment: 2026-08-10, Windows x64, Temurin 17.0.20+8, Android SDK 36.
  - Result: PASS。31 tests、0 failures/errors/skips、Lint 0 errors / 9 warnings、APK 30,215,115 bytes / SHA-256 `45A8338FE745E87C77EFE7FD05D27FB8B1CE9044F71CE82897043A416B45A8BA`、scroll API match 0。
- Command: Pixel 9 AVD API 36 install/launch plus DocumentsUI save/open, BPM edit, Undo/Redo, app restart autosave and deliberately truncated latest-generation recovery.
  - Date/environment: 2026-08-10, Android 16/API 36 x86_64 emulator, 1080×2424 density 420.
  - Result: focused `EMULATOR_PASS`。92 save → 93 edit → Undo 92 → Redo 93 → open 92、autosave restart 94、latest autosave 4-byte truncation後previous 92 recovery、fatal exceptionなし。物理`DEVICE_PASS`ではない。

- Command: `Get-FileHash -Algorithm SHA256` on both input ZIPs and archived payloads.
  - Date/environment: 2026-08-09, Windows PowerShell.
  - Result: PASS。Workspace `77d5...70e4`、Complete Bundle `8cf8...dd5e`。
- Command: `git status --short --branch`, `git log`, `git fsck --no-reflogs`.
  - Date/environment: 2026-08-09, Git 2.54.0.windows.1.
  - Result: clean `main` at `65ee89a`; fsck fatal/error なし。
- Command: `powershell -ExecutionPolicy Bypass -File .\scripts\doctor.ps1`.
  - Date/environment: 2026-08-09, Windows PowerShell.
  - Result: partial。Git/worktree PASS、JDK/SDK/adb not configured、Codex CLI auth not confirmed。
- Command: Luna `spawn_agent` minimal read-only architecture audit.
  - Date/environment: 2026-08-09, current Codex task.
  - Result: BLOCKED for delegation only: `Unknown model gpt-5.6-luna`; allowed list reported Sol/Terra。
- Command: verified portable Temurin extraction and `java -version` / `javac -version`.
  - Date/environment: 2026-08-09, Windows x64.
  - Result: PASS。Temurin `17.0.20+8`, `javac 17.0.20`; archive SHA-256 matched Adoptium API。
- Command: verified Android command-line tools extraction, `sdkmanager --version`, `adb devices -l`.
  - Date/environment: 2026-08-09, workspace-local SDK.
  - Result: partial。command-line tools `22.0`, adb `37.0.1`; Pixel 9a connected as `device`。Platform 36 / Build Tools install skipped because license was not accepted。
- Command: `gradlew.bat --stacktrace :app:testDebugUnitTest` with workspace-local JDK, SDK path, and Gradle home.
  - Date/environment: 2026-08-09, Windows x64.
  - Result: BLOCKED before compilation。Gradle wrapper 9.5.0 starts, but Platform 36 and Build Tools 36.0.0 licenses are not accepted; no Android unit-test result is claimed。
- Command: `scripts/validate_project.sh` through Git for Windows Bash with workspace-local JDK/Kotlin.
  - Date/environment: 2026-08-09, Windows x64.
  - Result: PASS。transient detection, 3 slices, stereo 2-frame buffer, versioned project, WAV header, and 120000-frame render smoke completed。
- Command: direct Kotlin compilation plus `org.junit.runner.JUnitCore` for six pure test classes.
  - Date/environment: 2026-08-09, Kotlin 2.3.21 / JUnit 4.13.2 / Hamcrest 1.3 / JDK 17.0.20+8.
  - Result: PASS。`OK (12 tests)`。
- Command: read-only adb device properties and `pm path com.choplab.sampler`.
  - Date/environment: 2026-08-09, physical device.
  - Result: Pixel 9a, Android 16, API 36, `arm64-v8a`, state `device`; application package not installed。Serial is intentionally omitted。
- Command: fixed-point review from peeled `codex-workspace-baseline` (`65ee89a3982d2d5e51e91a055dc7e71937210c8e`).
  - Date/environment: 2026-08-09, parent Codex task.
  - Standards axis: AGENTS real-time/data-boundary rules and `.editorconfig`; no new callback allocation/I/O/logging path was introduced. One material unbounded-metadata finding was fixed and retested.
  - Specification axis: `prompts/02_BASELINE_AND_MODEL.md` and product requirements; domain/migration/coexistence seams and characterization coverage are present. Gradle/lint/assemble and independent `qa_reviewer` remain unverified because of the SDK license gate and unavailable Luna model, respectively.
  - Result: no remaining material source finding in the reviewed checkpoint; this is a local fallback review, not the required independent two-agent review.
- Command: post-relocation JDK/adb/Kotlin/Gradle probes and `scripts/validate_project.sh` with temporary output on F:.
  - Date/environment: 2026-08-09, C: NTFS junctions backed by `F:\CodexData\ChopLab`.
  - Result: PASS。Temurin 17.0.20+8、adb 37.0.1、Kotlin 2.3.21、Gradle 9.5.0、offline project validation、Pixel 9a `device` state。Final wrapper shell returned 1 only because of a PowerShell `exit $g` spacing typo after all recorded component exit codes were 0; this is not reported as a test failure。

## Risks and rollback

- Full product scope is multi-milestone and native/audio/device risk is high。各 milestone で commit、full gate、legacy fallback を保持する。
- Portable Android SDK/NDK は大容量。workspace free space と archive checksum を確認し、`work/tools` のみを使用する。
- Reference DSP はコンパイルされておらず real-time violation や数値不安定を含み得る。必要な概念だけを tests 先行で再構築する。
- Model migration は data loss risk。schema reader と旧 snapshot adapter を先に固定し、autosave を最後の有効版から分離する。
- Device install は user app state を変更する。debug package `com.choplab.sampler` のみを対象とし、アンインストールやデータ消去は行わない。

## Remaining device validation

- adb で device authorization、model、API、ABI、audio route を観測。
- APK install/launch/crash-free smoke。
- SAF import、microphone permission allow/deny、録音 start/stop。
- Playback Capture consent allow/deny、source opt-out、foreground/background lifecycle。
- waveform/chop/auto-next/pad/gate/choke/pattern/song/save/reopen/export の end-to-end。
- stereo L/R identity、headphone/speaker route、thermal/underrun/xRun、measured latency。
- USB/Bluetooth/virtual MIDI note/velocity/CC/clock/transport と disconnect/reconnect。
