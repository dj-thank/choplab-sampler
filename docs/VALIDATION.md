# 検証記録

このファイルは revision-bound な検証履歴です。現在の branch、HEAD、tree、dirty boundary、receipt の採用範囲は [`docs/PROJECT_STATE.md`](PROJECT_STATE.md) の先頭 `Current snapshot` を参照してください。下記の過去セクションは削除せず、記録された revision と gate の範囲を越えて current proof として再利用しません。

## Guided first-screen GitHub review repair — 2026-08-24

- Product source: reachable follow-up commit `dfe52d72946e0deefa64eed02539178a03558c0a`, tree `ef1ce320740fc1254023efe5aa2ef648ec38792e`; PR #52 is merged at `main@495ddc9dfac02a9e72160c637f65d2b53d6829ce`, and the follow-up is integrated with `main@ae77cd92d3ee14baecc01f4862c639328bae43bb`. The final follow-up commit is a documentation-only anchor update.
- Review closure candidate: compact-landscape CAPTURE now opts into bounded scrolling; large-text CHOP plus BEAT quick/detailed bodies use stacked bounded-scroll compositions with explicit waveform and computed 48 dp-safe PAD-grid heights. CHOP and ONE SHOT commit on completed tap, including stationary holds on empty CHOP pads. GATE waits through a 120 ms scroll-classification window and, once activated, remains held until pointer-up; a shorter completed tap receives at least 80 ms of preview. Parent cancellation before activation dispatches zero model/audio actions, and pointer-node cancellation after activation releases exactly once. `pad.playMode` is a pointer-input key, so live ONE_SHOT/GATE changes restart arbitration. Compact-landscape large text exposes transient status in the fixed header when the separate strip is suppressed. Normal-text layouts retain their previous fixed/responsive and press-down paths.
- Instrumentation isolation: `FirstScreenFlowDeviceTest` renders deterministic in-memory CAPTURE and CHOP states/controller seams and never launches autosave recovery, clears app data or mutates retained projects. Real pointer regressions require PAD-origin CHOP, ONE SHOT and pre-activation GATE swipes to move their parent with zero controller calls; a completed ONE SHOT tap to dispatch ordered actions; a post-composition ONE_SHOT→GATE change to activate before pointer-up and release afterward; a short GATE tap to retain at least 80 ms; GATE-trigger ownership to release exactly once when long-press navigation removes the grid; a held empty CHOP pad to wait through long-press timeout and capture on pointer-up; and a non-scroll PAD to select and trigger before pointer-up. The prior exact-head CI run `32712301910` stopped in Android test compilation because `longClick` was unavailable; the current source uses supported explicit `down` / clock advancement / `up` or `cancel` injection instead. These new device tests are source coverage until hosted execution succeeds.
- Current-container checks: `git diff --check` PASS; Python policy suite 39/39 PASS; public-surface scan 394 candidates PASS; all six Android XML files parse successfully.
- Environment boundary: this Linux container has no Android SDK or cached Gradle distribution and cannot reach `services.gradle.org`; the focused Gradle unit/androidTest compile command stopped while downloading Gradle 9.7.1, so fresh compilation/instrumentation is not claimed. Hosted PR CI must pass before merge or gate promotion. The artifact/runtime evidence in the next section remains bound to `43d8ace` and is not proof for `dfe52d7`.

## Sample-rate-bounded streaming decode candidate — 2026-08-24

- Product source: reachable integration commit `8279ea4f7e04cfec2c41440e65f4a40bc4d68451`, tree `f6a5bc3844317169edf1100e79da1ea08b46c524`, joining the original PR head with `main@a930da4cdaf1f5035b3ea21196f802801fa4c46f`. Later documentation commits are tracked separately and do not change these product bytes.
- Historical pre-rebase receipt: `9f01f42beb4e37ef5d4f66606af5917f8620f2ea`, tree `1071acbd11593cab3eb1b7531857a9d9f7bb8c12`, remains the revision boundary for its original local checks only.
- Contract: imported mono PCM is bounded by `min(30,000,000, sampleRate × 600)` frames. Exact 8 kHz / 4,800,000 and 48 kHz / 28,800,000 boundaries are accepted; the next frame is rejected. The arithmetic tests do not materialize multi-million-frame buffers.
- Adapter coverage: Android updates the streaming builder when the decoder output rate becomes authoritative and revalidates accepted PCM; Desktop applies the effective limit before known-length allocation, during unknown-length streaming, and after decode.
- Historical checks: at `9f01f42` / tree `1071acb`, the public-surface scan passed 389 candidates and `git diff --check` passed; `scripts/doctor.sh` confirmed Java 17/Git and reported the expected absent Android SDK/ADB.
- Integrated-tree checks: on a docs-only descendant of product `8279ea4`, Python policy tests passed 39/39, `python3 scripts/check_public_surface.py` passed 394 candidates, and `git diff --check` passed. `scripts/doctor.sh` confirmed Java 17/Git and the expected absent Android SDK/ADB. `scripts/write_release_manifest.py` and `scripts/tests/test_write_release_manifest.py` match integrated `main@a930da4`, so #63 checksum enforcement is retained unchanged.
- Blocked local execution: the focused shared/Android/Desktop Gradle command could not provision uncached Gradle 9.7.1 because the distribution host is unreachable. The focused test sources are present, but no new Gradle result is claimed; hosted CI is required.
- Gate: source/static evidence only. Device import, codec variance, physical memory pressure, audio quality, provider/public and Human gates remain unclaimed.

## Release checksum sidecar hardening candidate — 2026-08-24

- The release manifest writer now fails before attestation/publication unless the Android APK, iOS Simulator archive, Windows app-image archive, and CycloneDX SBOM each have a checksum sidecar that names the exact target and matches its bytes.
- Every discovered `.sha256` sidecar is validated, so malformed, mismatched, cross-named, and orphan sidecars cannot be published alongside the generated `SHA256SUMS`.
- Focused unit coverage includes the accepted four-asset set plus missing, digest-mismatch, filename-mismatch, and orphan-sidecar failures. Hosted PR CI remains the integration proof; no tag or Release is created by this candidate.

## Desktop recorder startup cleanup candidate — 2026-08-24

- Product source: branch commit `53f4bf5a62d23d9db63f538be3a06298eaf48936`, tree `d74f6314b4efd4a5604568e3c21395cfae42aaf6`, base `main@495ddc9dfac02a9e72160c637f65d2b53d6829ce`.
- Regression contract: the injected `TargetDataLine` accepts `open`, throws from `start`, is closed exactly once even after later `stop` / `close`, leaves `isRecording=false`, deletes the owned partial WAV, and cannot return stale output. The fixture does not open audio hardware.
- Static gates: `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` passed 23 tests; `python3 scripts/check_public_surface.py` passed 390 candidates; six Android XML files parsed; wrapper SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` and wrapper UTF-8 policy matched; `git diff --check` passed.
- Blocked local gate: `./gradlew :desktop:test --tests com.choplab.desktop.audio.DesktopAudioRecorderTest --no-daemon --max-workers=1 --no-watch-fs --console=plain` could not start because Gradle 9.7.1 is not cached and the distribution host is unreachable. `./scripts/validate_project.sh` reached and passed the public-surface phase, then stopped at the same Gradle prerequisite. Hosted `:desktop:test` is the required executable proof.
- Gate: source/static candidate only. Physical Windows input, actual WAV content, route loss, audio quality, provider, publication, and `HUMAN_GO` remain unclaimed.

## Guided first screen and coherent workflow candidate — 2026-08-24

- Product source: `codex/choplab-screen-flow@43d8ace6aa43f3eb6e3b9dc01ea74604ee600705`, tree `798212c33d1dcc3eb52ea79fb20e13b87a9b2d9a`, base `3cc4cd5`; dirty canonical checkout untouched.
- Design contract: pristine CAPTURE is an explicit own-audio/project/recording choice surface with a named DUSTY JAZZ demo route. Font scale 1.2+ uses a simplified header, two-row stage strip and multi-line status. Loaded, loading and recording safety surfaces are unchanged.
- TDD: pure policy tests cover pristine/loaded/recording entry, SAVE-vs-WAV truth and 1.0/1.3/2.0 layout; Desktop regression proves playable-PAD selection moves PAD, bank and page together; Android instrumentation checks all first-entry CTAs, 48 dp bounds and demo transition to B DRUMS/B01.
- Full clean Gradle gate after the independent review's landscape finding: `:shared:testAndroidHostTest :shared:desktopTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — `BUILD SUCCESSFUL`, 191 tasks. The final scroll-end and demo-target deltas passed the same task set incrementally in 184 tasks. Shared host 25/25, Android 234, JVM-core 52 and Desktop 77; failures/errors/skips 0. Lint debug/release errors 0, warnings 7.
- Other local gates: configured `scripts/validate_project.sh` PASS; Python release/public policy 23 tests PASS; public-surface scan 389 candidates PASS; Android release identity `0.17.0 (27)` PASS and intentionally unsigned.
- Android artifacts: debug APK 32,447,992 bytes / SHA-256 `EAF275AC902D955410E3D9C6B9FB39BF28AA196D4E89FD97E8CB981619F354FA`; androidTest APK 10,874,825 / `77608CADDEBF300E0720E54E7537FD71FA081D40C4F94E9082A52E1DBFAC325B`; unsigned release APK 24,061,044 / `A21DDEB26A5DBF4B0BFB7105E99BB09BF979305C6B2442FFFEA07A7E91879F67`.
- Emulator runtime: exact final debug/test APKs installed data-preservingly on dedicated API 36 `emulator-5592`; full suite `OK (7 tests)`. Portrait font scale 1.0/2.0, 640 × 360 dp landscape 1.3/2.0, large-text scroll end and demo BEAT were captured from the exact APK; stage rows measured 49 dp and the final demo target 59 dp. System size/density/font scale/rotation restored. No fatal/ANR signature was observed.
- Windows artifact/runtime: app-image 405 files / 176,494,912 bytes / digest `2e8c568ec1746bae1a4500bd58e25b4d3751b5a0e1fbcc143e44e51639ecc773`; EXE 449,024 bytes / SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`. Isolated `LOCALAPPDATA` CAPTURE and demo BEAT windows responded; the demo showed B DRUMS/B-01/page 01–16 and the exact tracked process pair exited.
- Evidence: parent PAD `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/accepted/` and `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/BASELINE_AUDIT.md`. Screenshots were visually inspected, not treated as test results by themselves.
- Gate: `LOCAL_PASS` plus scoped emulator runtime. Physical Pixel `DEVICE_PASS`, listening, recording, route loss, complete TalkBack speech, provider/public binary Release and `HUMAN_GO` remain unclaimed.

## Windows daily-use v0.17.0 local candidate — 2026-08-24

- Product source: `codex/choplab-desktop-daily-release@b6efbde30a0fc1d8ce8a944405b20422fc238782`, tree `9760029f723c55465004908899255a7ad1c165a3`, base `c4956cf`; dirty canonical checkout untouched.
- Functional TDD: Windows 4×4 PAD mapping, key-repeat suppression, modifier/context admission, exact global-PAD key-up ownership, and focus-loss release were each exercised through `DesktopPadKeyboardTest`. Shared Android touch PAD behavior was restored unchanged after review rejected PC-only visual noise on mobile.
- Final Gradle gate: 142 tasks PASS; Android unit 226 / 44 suites, JVM-core 49 / 8 suites, desktop 72 / 16 suites; failures/errors/skips 0. Debug/release Lint, debug/unsigned-release APK, and Windows app-image package PASS.
- Policy/package gates: configured Git Bash validation PASS; Python 22/22 PASS; public surface 369 candidates PASS; wrapper 9.7.1 JAR SHA-256 `7A9CE74CFF467CA1BF60A4FCD9F05185ACCEDA4D0F382434D393E17864262C5D`; wrapper UTF-8 policy PASS; `git diff --check` PASS.
- Android release identity: `0.17.0 (27)`, intentionally unsigned local candidate. compileSdk 37; targetSdk 36; minSdk 29.
- Supply chain: CycloneDX 1.6 identity `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies. CI now rejects empty/unspecified SBOM identity.
- Windows identity: ProductVersion `0.17.0`; EXE SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; desktop JAR `7164DFF0B7521FFCB2DF10032F5D45790F63D542FD656623378CC52BC80953BA`; complete app-image digest `8487C2376FBCB5A4B83D84631E50A6165ECB0E1E772E5CAC0BFA0A2F65F98CC6`.
- Installer E2E: full-tree digest staging, identical-byte idempotence, non-launcher tamper rejection, shortcut target readback, and project sentinel preservation PASS. Actual user install path is `%LOCALAPPDATA%/Programs/ChopLab/0.17.0-8487c2376fbc`; Start Menu/Desktop shortcuts target it.
- Installed runtime: temporary app-data sandbox, no provider credentials, no recording/audio action; responding title observed, empty `1` key smoke PASS, exact process pair stopped. User project digest stayed identical.
- Receipt: parent PAD `work/PAD_CHOPLAB_WINDOWS_0.17.0_LOCAL_RECEIPT.json`. Gate is `LOCAL_PASS`; GitHub PR/merge/Release reverse-download, device/provider/audio/accessibility/Human gates remain pending.

## Full hardening + Spotify Connect + production continuity integration — 2026-08-24

- Integration source: `codex/choplab-session-integration@6914e3c4d7bfabc85b43eaadfcfaa8de69072739`, tree `94fbc43839d2d74ae383ac973b456ceb4fea9dca`; base parent `261d034`, merged parent `df61bb5`, merge base `9a4e9edc`.
- Merge resolution: implementation auto-merged; only this validation history and plan registry conflicted. Both source receipts were retained; the integration plan completed and moved to `plans/completed/session-integration-20260823.md`.
- Fresh clean Gradle gate: 184 tasks PASS. Android unit 226 / 44 suites, JVM-core 49 / 8 suites, desktop 66 / 15 suites; failures 0, errors 0, skipped 0. Android Lint fatal 0 / errors 0 / warnings 6. Debug, unsigned release, androidTest APK, Windows app-image, and combined CycloneDX SBOM built successfully.
- Other local gates: configured Git Bash validation PASS; Python release/public policy 19/19 PASS; public-surface 355 current / 360 reachable-history candidates PASS; packaged desktop JAR 138-entry credential/signing/audio-name scan PASS; UI contract 9 regions (`exact 4 / semantic 4 / adapted 1`) and 3 states PASS; Android unsigned release policy `0.16.2 (26)` PASS; Windows ProductVersion `0.16.2` PASS; `git diff --check` PASS.
- Integrated artifacts: `outputs/build-provenance-6914e3c4d7bf.json`, `outputs/windows-metadata-6914e3c4d7bf.json`, and `outputs/session-integration-receipt-6914e3c4d7bf.json`. Exact hashes are recorded in the integrated receipt.
- Windows runtime: credential-free packaged launch responded with title `ChopLab — おとひろい PC`; exact launcher/UI process tree was stopped. No Spotify login/provider or audio operation occurred.
- Device reconciliation: integrated Android/shared/JVM/build Git objects equal `8306ed2`; debug/test APK hashes equal that accepted Pixel host/install/read-back receipt exactly. Scoped receipt carries only data-preserving install/readback, package/version/signer, 6 instrumentation tests, autosave preservation, cold launch, and phone-state restoration. Current device is absent; no fresh install or mutation occurred.
- Review: local parent Standards 0 / Spec 0; no substitute child model. The prior Luna packet remains rejected because effective sandbox was writable.
- Gate: `LOCAL_PASS` plus scoped `DEVICE_PASS` for exact Android bytes. Provider, public, actual recording/audio quality, TalkBack speech, signed distribution, and Human gates remain unclaimed.

### Input receipt A: v0.16.2 Spotify Connect integrated local candidate

## v0.16.2 Spotify Connect integrated local candidate — 2026-08-23

- Source: isolated single integration branch `codex/choplab-spotify-connect`, merge base `9a4e9edc2686914c28c91b2d614dfb95281935c2`; source/device receipt commit `8306ed2114398a0d1adc89a9a4a653c1db409c1f` followed by documentation-only commits. The dirty canonical checkout was not reset, cleaned, staged, or modified.
- Spotify boundary: Authorization Code with PKCE, dynamic-port `127.0.0.1` callback, exact state validation, memory-only Client ID/access/refresh tokens, saved-track/current-playback metadata, and user-triggered Connect pause/resume only. Spotify Content download, capture, recording, extraction, transcoding, and MP3 creation are absent.
- UX/lifecycle: malformed environment Client IDs fail closed; setup shows the exact portless loopback registration; cancel/disconnect/reconfiguration invalidate late callbacks; denial, timeout, default-browser failure, loopback-bind failure, network failure, 401/403/404/429/5xx, no-current-playback, empty library, and malformed library responses have distinct recoverable Japanese states. Current track, library summary, connection state, and guidance use polite live-region semantics.
- Full clean Gradle gate before the final desktop-only guidance patch: `clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :jvm-core:test :desktop:test :desktop:packageWindows cyclonedxBom --no-daemon` BUILD SUCCESSFUL, 184 tasks. The final desktop-only patch then passed `:desktop:test :desktop:packageWindows` with 62 tests / 15 suites, failures 0, errors 0, skipped 0.
- Current test counts for unchanged platform source: Android unit 226 / 44 suites; JVM-core 49 / 8 suites; final desktop 62 / 15 suites; Python release/public-surface suite 19. Failures 0, errors 0, skipped 0. Android Lint task passed.
- Configured project validation: explicit Git Bash `scripts/validate_project.sh` PASS; Android XML parse PASS; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`; public-surface scan PASS over 348 current and reachable-history candidates.
- Android artifacts: debug APK 30,970,285 bytes / SHA-256 `797531839DEBF5B3E589BB56038366AFDCBE47754707332E80785E5EEE206DE6`; androidTest APK 10,564,866 bytes / SHA-256 `13A7E1EC8312DC2226AFA419312D65A1DF5C500601739B6C4BB05C1C193C1191`; unsigned release candidate 23,752,115 bytes / SHA-256 `9F0D4CCF1FB9D024A2243C5C7645BE72976C80B7FEBD8E2A952C9B65B81F1325`, verified as `0.16.2 (26)` and intentionally unsigned.
- Windows artifact: app-image `ProductVersion=0.16.2`; `ChopLab.exe` 449,024 bytes / SHA-256 `2DCBA5BED76C97E4D2EF85B5F18304C325653ADF4BFFA66A77A443EB80C2622A`; final `app/desktop.jar` 303,766 bytes / SHA-256 `85A51849256511F45028E4D05946F7AF4222146D4B8555493553D736A6A31814`. File and JAR denylist scans found no credential, signing, or audio artifact. A Client-ID-free hidden launch produced the exact responding title `ChopLab — おとひろい PC`, then both exact package PIDs closed cleanly.
- Security diff scan fixed at `9a4e9ed...4b890069`: 48/48 security-relevant/supporting files closed, sealed coverage complete, reportable findings 0. One same-user/app-sandbox-only iOS pre-check copy candidate was suppressed by reportability policy and nevertheless remediated with bounded streaming copy plus a size-unknown regression test. Report: `%LOCALAPPDATA%/Temp/codex-security-scans/choplab-spotify-connect-20260823/4b890069_20260823T014342+0900/report.md`.
- The first attached-device run `work/device-evidence/20260823-024658-d6f2810e/` intentionally failed closed at state restoration because Android 16 omitted `topResumedActivity` from one `dumpsys activity` response; APK install/readback, instrumentation, autosave, rotation, volume, and actual `ResumedActivity` restoration were already intact. The evidence was not promoted.
- After the bounded parser fallback fix in `8306ed2`, the accepted run is `work/device-evidence/20260823-025301-8306ed21/`. Exact serial `5A121JEBF08094` Pixel 9a received data-preserving `adb install -r`; app/test APK readback hashes and signer matched host; installed package is `com.choplab.sampler` `0.16.2 (26)`; autosave three-generation hashes are identical before/after; `SourceWaveformDeviceTest` returned `OK (6 tests)`; the separate `launch-smoke.json` started `com.choplab.sampler/.MainActivity`, observed foreground, found zero fatal/ANR/crash signals, and restored foreground `com.twitter.android/com.x.android.main.MainActivity`; rotation `1` and media volume `8` were restored. No uninstall, clear-data, permission change, capture, or audio operation occurred.
- Gate: source, tests, packaging, launch smoke, public-surface/history scan, security review, and the bounded Pixel instrumentation/readback establish `LOCAL_PASS` plus scoped `DEVICE_PASS`. Real browser OAuth, Premium/allowlist/account behavior, a Spotify Connect device, Windows screen-reader speech, iOS/macOS test execution, physical audio quality, publication, and `HUMAN_GO` are not claimed.
### Input receipt B: Android / Windows production continuity candidate

## Android / Windows production continuity candidate — 2026-08-23

- Source: isolated `codex/choplab-cross-platform-polish`, reviewed implementation commit `31061be2cc8f82327a2881f5dcc56c54b9753482` / tree `27c3c22be94716d7315231ac4c5f791f951dd196`, based on `9a4e9edc2686914c28c91b2d614dfb95281935c2`; canonical dirty checkout and Spotify/full-hardening branch untouched.
- TDD RED→GREEN: source-recording decode publishes CHOP launch target/revision; successful vocal recording restarts and retains the selected Beat loop through the public audio port; startup project policy skips stale recovery while retaining future autosave. Focused tests use a bounded fake `DesktopAudioRecorder` and do not open recording hardware.
- Full local Gradle gate: `:desktop:test :jvm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktop:packageWindows --offline --no-daemon --max-workers=1 --no-watch-fs --console=plain` BUILD SUCCESSFUL, 91 tasks.
- Tests: Android unit 225 / 44 suites; JVM-core 44 / 8 suites; desktop 39 / 12 suites; failures 0, errors 0, skipped 0. Android Lint: fatal 0, errors 0, warnings 4.
- Configured gate: Git Bash `scripts/validate_project.sh` PASS; public-surface 322 candidates PASS; six Android XML files parse; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
- UI evidence: existing Android-origin contract validates 9 regions (`exact 4 / semantic 4 / adapted 1`) and 3 states. No UI pixels changed; the existing 1080×2424 Android and 1106×2202 Windows captures remain appearance references, not runtime proof.
- Source-bound artifacts: `outputs/build-provenance-31061be2cc8f.json` binds APK 30,937,621 bytes / SHA-256 `040570008F4B2CD9CA4E27419C321AB830E07B8B47705F1CD383CD8DC4CDF33B`, test APK 10,564,866 bytes / SHA-256 `13A7E1EC8312DC2226AFA419312D65A1DF5C500601739B6C4BB05C1C193C1191`, package/version/signer, exact source commit and tree. `outputs/windows-provenance-31061be2cc8f.json` binds `ChopLab.exe` 449,024 bytes / SHA-256 `40903D73A17CD6DE66D33567779C2350B72C3FD6B16701662008265534F8E69A` and a responding packaged window whose exact process tree was stopped.
- Post-commit artifact refresh: `:app:assembleDebug :desktop:packageWindows --rerun-tasks` BUILD SUCCESSFUL (62 tasks), followed by `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL (71 tasks). The provenance checker then passed.
- Device: `adb devices -l` returned no attached device at both bounded checks (`20:03:47` and `20:27:57` JST); install, data mutation, recording, and device-audio capture were not attempted. No further polling in this run.
- Review repair: local parent Standards/Spec review exposed an output-device exception after vocal recorder start. The negative test was RED, then GREEN after bounded asynchronous recorder stop, owned temporary-file deletion, idle loop state, and actionable Windows-output guidance. Final `:desktop:test :desktop:packageWindows` BUILD SUCCESSFUL (19 tasks).
- Gate: `LOCAL_PASS`; fresh physical `DEVICE_PASS` is blocked on Pixel reconnection. Actual recording alignment/audio quality, TalkBack speech, Spotify provider, public and Human gates are not claimed.

## v0.16.1 precision trim local candidate — 2026-08-21

- Source: isolated `codex/choplab-precision-trim`, baseline main `923d7bb711d399efdf7ea8726e9a72769f1d97a5`; reviewed implementation commit `f89877c10371dcd57077dc0413a46f536386422d`, tree `2122bb183fbb2f87c9b40384a6d13f9c526852aa`; dirty canonical checkout untouched.
- TDD/policy: nearest-boundary tie, centered/edge/short-source one-second window, focused viewport, frame/1 ms/10 ms stepping, range clamp, arithmetic overflow, dial progress, exact time/copy, and waveform long-press-without-tap all PASS.
- Full local Gradle gate: `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug :jvm-core:test :desktop:test :desktop:packageWindows --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL, 124 tasks.
- Final tests after review fixes: Android unit 225 / 44 suites; JVM-core 44 / 8 suites; desktop 35 / 12 suites; API 36 instrumentation 6 / 2 suites. Failures 0, errors 0, skipped 0. Android Lint: errors 0 / warnings 8.
- Configured project gate: Git Bash `scripts/validate_project.sh` PASS; public-surface 320 candidates PASS; six Android XML files parse; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
- API 36 emulator manual path: assigned A02 long press opened full portrait TRIM; waveform long press moved END to `3:38.873` and focused exactly one second; ZOOM+ retained that focus at half a second; 10 ms wheel moved END to `3:38.883`; Revert restored `0:36.703–6:28.563` and full-source `1.0x`. No microphone/system recording or public/provider operation occurred.
- Final local artifacts: debug APK version `0.16.1 (25)`, 30,937,621 bytes, SHA-256 `040570008F4B2CD9CA4E27419C321AB830E07B8B47705F1CD383CD8DC4CDF33B`; Windows app-image ZIP 88,675,862 bytes, SHA-256 `4BE4FAEFEA04436500EC295DFB8CB7EF0555056F9DA235D342E22ED97EA2009C`; contained `ChopLab.exe` 449,024 bytes, SHA-256 `40903D73A17CD6DE66D33567779C2350B72C3FD6B16701662008265534F8E69A`.
- Gate: `LOCAL_PASS` plus scoped emulator UI/instrumentation. Physical device audio/touch, signed iPhone behavior, provider/public Release, accessibility speech, and Human acceptance are not claimed.
- Review: local parent Standards/Spec two-pass against `923d7bb`; no substitute child model. Resolved Standards findings: overflow-safe absolute boundary setting and 48 dp precision controls. Final unresolved Standards 0 / Spec 0. API 36 visual recheck shows the full fixed TRIM screen with 48 dp precision controls and frame-mode six-digit sub-millisecond values without clipping.

## v0.16.0 production continuity and public preview — 2026-08-20

- Source: isolated branch commits `1813385` + review fix `1e15fe3`, tree `bce75c1ecb4f30e193e12da38339c50c0cbc078c`; dirty canonical checkout untouched.
- TDD/policy: launch destination, starter installation/preservation, scratch return validity, gesture dead zone/curve, default Beat Chop surface, project runtime-field omission, and Windows replacement confirmation all PASS.
- Full local Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :jvm-core:test :desktop:test :desktop:packageWindows --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL, 91 tasks.
- Tests: Android 217 / JVM-core 44 / desktop 35; failures 0, errors 0, skipped 0. Android lint: errors 0, warnings 8.
- Configured project gate: explicit Git Bash `scripts/validate_project.sh` PASS; public-surface 315 candidates PASS; Android XML parse PASS; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.
- API 36 emulator: `:app:connectedDebugAndroidTest` BUILD SUCCESSFUL; `SourceWaveformDeviceTest` 4/4 PASS on `medium_phone(AVD) - 16`. Manual scoped interaction proved CAPTURE OPEN→DocumentsUI, 4×4 default BEAT, live direction/speed/playhead, B-01 loop return after scratch, and edited-autosave BEAT routing. It did not record microphone/system audio.
- Final local debug APK: package `com.choplab.sampler`, version `0.16.0 (24)`, minSdk 29, targetSdk 36; 30,872,085 bytes; SHA-256 `D12F572C70525E4218E03D1326771F688430528AA8221523C6B0FB33A06125F6`.
- Final local Windows ZIP: 88,640,599 bytes; SHA-256 `4D740801C091B165716ECAB921045750FD4F352B07FAE57823E1146721DFDD32`; contained EXE SHA-256 `40903D73A17CD6DE66D33567779C2350B72C3FD6B16701662008265534F8E69A`.
- Review: local parent two-pass Standards and Spec review after fixed point `8c12f71`; Windows `replaceExisting` regression found and fixed in `1e15fe3`; final unresolved findings 0/0. Luna runtime was not verified, so no child-model claim.
- Gate: `LOCAL_PASS` + scoped emulator UI/instrumentation only. No physical-device, subjective audio, provider, public Release, signed iOS device, or Human promotion.
- GitHub final: PR #35 head `8d1f79c` passed Android/Windows/iOS twice each; squash merge `64e84b8`; merged-main runs Android `32374131628`, Windows `32374131637`, iOS `32374131624` all success.
- Release final: annotated tag object `4d881d5998381682e3739f8f0e0343d77d114f77` peels to `64e84b8`; Release run `32374833191` passed all four jobs; public prerelease is non-draft.
- Public hashes: APK `2F04339524022F25B4D1ABB513152195C331A3C955168C4E84140D523F01E437`; Windows ZIP `60C78C1D23BB2FE959C325C3AD42995EFF2758D242ED21E90602E92CA145C27A`; contained EXE `A69373FE39324619903D7B575509AF976CF8ED8D2A5C6921C2E18F3B40F790CF`; iOS Simulator ZIP `B704C1861477F7D5C2CD6297CAFAA5C95984B67778ED63FF4B7A8697A5008267`. GitHub asset digests, sidecars, reverse downloads, and anonymous HTTP 200 agree.
- Public gate is scoped to artifact distribution and integrity. Physical device, signed iOS IPA, audio/touch quality, TalkBack/VoiceOver speech, and Human acceptance are not claimed.

## 専用 API 36 review AVD — 2026-08-17

- Source: clean `9177229de91f2560b93f381fffda26909eaf4d75`, tree `2fe15415cef8a7a2907ea71ac840996a0d847e0b`.
- Local gate: 49 suites / 250 tests, failures 0, errors 0, skipped 0; lint errors 0, warnings 11; debug app and androidTest builds PASS.
- App: `com.choplab.sampler` `0.13.1 (21)`, SHA-256 `6178E499E53502AD6ABA0C16F2FE057015F795A305144417E8220097E3167909`.
- androidTest: SHA-256 `EE34FA4EEF5CBC48FFCB708207464E27B34C8205462D4CAFDED54D9F08C1FFE0`.
- Both APKs use signer SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.
- Dedicated `emulator-5592`, API 36 Google Play, locale `ja-JP`: portrait font 1.0, 1.3, 2.0 and landscape font 1.0 each `OK (4 tests)`.
- ChopLab fatal/ANR 0, emulator Bluetooth fatal 0, font/rotation readback restored, app force-stopped.
- Gate: `LOCAL_PASS`, `COMPOSE_INSTRUMENTATION_PASS`, `FRAMEWORK_NODE_PASS`. Physical `DEVICE_PASS`, `PUBLIC`, and `HUMAN_GO` are not claimed by this run.

初版作成日: 2026-07-15（以下は追記型の検証履歴）

現在HEADへ結合したWindows検証は、tracked-clean checkoutとプロジェクト用JDK/SDKを設定して `scripts/verify.ps1` を実行する。clean unit/lint/app/androidTest build後に `outputs/build-provenance-<HEAD>.json` を作成し、source HEAD/tree、fresh APK bytes、package/version、signerを一つのreceiptへ固定する。現在HEADのreceiptがない既存 `app/build` APKは、hashだけで最新成果物として扱わない。

## 2026-08-16 waveform evidence hardening

- test isolation: `SourceWaveformDeviceTest` renders deterministic in-memory PCM and no longer reads/writes Pixel autosave or requires a pre-existing chop marker
- semantics boundary: tests prove Compose state descriptions and custom-action callbacks; they do not claim a running TalkBack service, spoken output, or focus traversal
- geometry: host tests cover whole/zoomed/invalid overview geometry; device tests cover true two-pointer pinch/pan and S/E/chop target width, height, clipping, endpoints, and exact reversible nudge
- accessibility behavior: viewport and handle actions report `false` when clamped/no-op instead of announcing a false success
- recording boundary: existing pure recording-session and interruption coordinator tests cover mutual exclusion without activating a real microphone; physical recording quality/contention remains unclaimed
- evidence: `scripts/collect-device-evidence.ps1` records clean source identity, Gradle logs, APK identities/signers, signer preflight, autosave before/after, `install -r`, base.apk readback, instrumentation output, package dumps, timestamp-bounded logcat, and final launcher/volume/rotation/project state under one manifest
- gates remain split: `LOCAL_PASS` / `INSTRUMENTATION_PASS` / physical observation / spoken TalkBack / `HUMAN_GO`
- official Android test hardening: Compose Accessibility Test Framework plus `UiAutomation` / `AccessibilityNodeInfo` inspect the deterministic fixture through the Android framework tree; a dedicated normal API 36 Google Play AVD passed four tests including S/E/chop1-5 depth-first tree order, advertised focus actions, and framework custom-action state mutation. This is `FRAMEWORK_NODE_PASS`, not a claim about TalkBack focus traversal or TTS.
- clean official-test gate: `clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL; 224 unit tests / 45 suites / zero failures, errors, or skips; clean app SHA-256 `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162` (30,855,284 bytes), final test SHA-256 `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE` (10,589,229 bytes), both signer SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`; the exact app/final-test APK pair reinstalled on `emulator-5562` and `SourceWaveformDeviceTest` returned `OK (4 tests)` in 9.335 s.
- 2026-08-16 TalkBack continuation: the actual TalkBack service plus touch exploration produced visible accessibility-focus rings and exposed the corrected S/E/clustered-marker tree on Pixel. ADB touch and the virtual keyboard could not reliably dispatch TalkBack's own next/custom-action gesture path, so spoken labels and complete TalkBack traversal remain `HUMAN_GO`; they are not promoted from framework-node automation. Separate bounded real-microphone checks proved recording ownership against song playback, selected-source loop, and source preview without retaining or reporting microphone content.
- final exact Pixel run: `work/device-evidence/20260816-220355-233297e3/manifest.json`; clean HEAD `233297e39f404bb8e0080110c3d29a528dd8c615`, app/readback SHA-256 `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162`, test/readback SHA-256 `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE`, Pixel instrumentation `OK (4 tests)` in `7.484 s`, autosave preservation and phone-state restoration PASS, 929 ms cold relaunch PASS, fatal/ANR 0. The real TalkBack service accepted a next-item gesture and visibly focused the formerly occluded S/marker cluster; TTS content and full service-dispatched custom-action order remain `HUMAN_GO`.
- authoritative exact run: `work/device-evidence/20260816-185953-b3579f05/manifest.json`; clean HEAD `b3579f0592738ccf2e95f10d1f0bba42cc343578`, app/readback SHA-256 `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`, test/readback SHA-256 `DE97432A1C1278E7661FD656DFCC054CFABA6A4BCC6D9DECF44B810564F83EC8`, deterministic Pixel instrumentation `OK (3 tests)` in `5.138 s`, lower/upper marker endpoints, autosave preservation, and phone-state restoration machine-gated, bounded app fatal/ANR 0

## 2026-08-14 v0.13.1 playback interruption safety candidate

- architecture: pure `PlaybackInterruptionCoordinator` owns focus-session state and interruption/recording policy; `AndroidPlaybackFocusAdapter` owns only `AudioManager` and the protected noisy-output receiver; no UI or persistence schema change
- behavior: every audible start is focus-gated; focus loss/transient/duck, Home, and output-route loss stop once; gain never auto-resumes; source seek/KEY retarget requires active coordinator ownership; rotation is exempt from background interruption
- recording policy: microphone and vocal sessions request graceful stop; Android Playback Capture continues in background while app playback stops
- independent review: Standards and Spec passes plus final parent-side verification; corrected missing state docs, unknown non-gain focus handling, and unproven retarget ownership; no scope-creep or additional clear behavior defect found. Effective child model metadata was not exposed, so no runtime-verified Luna claim is made for this milestone
- focused TDD: missing retarget ownership API was observed RED at Kotlin test compilation; unknown focus mapping and coordinator ownership are GREEN after the smallest production change
- configured `scripts/validate_project.sh`: PASS; pure Kotlin smoke PASS; four Android XML files parsed; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` BUILD SUCCESSFUL
- unit tests: 207 tests / 44 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 11; debug and unsigned release APK assembly PASS
- local APK: `outputs/ChopLab-v0.13.1-playback-interruption-safety-local-debug.apk`; 30,821,319 bytes; SHA-256 `9A11118395AEC68AF6A739416514135FAEFF562302EB541573A49CF48A038668`
- metadata: package `com.choplab.sampler`; versionCode 21 / versionName 0.13.1; minSdk 29 / targetSdk 36; APK Signature Scheme v2; local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- unsigned release APK: 23,603,385 bytes; SHA-256 `41C318EEE607EF28391A9BE38751F2D82D9B4B3934AEFC7F42E1702F9343A4D9`
- dedicated tracked emulator `emulator-5590`, Android 16/API 36: exact data-preserving `adb install -r` PASS; installed package reports versionCode 21 / versionName 0.13.1
- runtime focus: one `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` GAIN entry with `PAUSES_ON_DUCKABLE_LOSS`; Home emptied the live stack and return status reported the background stop; portrait/landscape recreation retained focus; `ALL STOP` emptied it
- automation boundary: shell injection of protected `ACTION_AUDIO_BECOMING_NOISY` is rejected by Android, so actual wired/Bluetooth route loss remains physical-device evidence
- provider runs for exact `903c698c2fdc443027a8190aa31985253ff3050a`: branch push `31764219592`, PR `31764223167`, tag verification `31764417666`, and Release `31764417670` all PASS
- annotated tag object `b11eaa13be6c7e4d8bc7cbfcf805dc8ab25dc436` peels locally and remotely to the exact commit; public non-draft prerelease: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.1-preview.1`
- public APK: `outputs/ChopLab-v0.13.1-preview.1-debug.apk`; 30,821,319 bytes; SHA-256 `5EE5183C2CA6574E964CC4A6AE44B4BE72813A691843345D9FA78B5ADE6598D6`; GitHub asset digest, sidecar, authenticated reverse download, and anonymous reverse download all match
- public metadata: package `com.choplab.sampler`; versionCode 21 / versionName 0.13.1; targetSdk 36; APK Signature Scheme v2; CI debug certificate SHA-256 `A04BC943A7F0C31ABC619839CDE0B28B2165700DE2F57D501F5B9DA0D0F9A2E2`
- anonymous HTTP: repository 200, Release page 200, direct APK 200; scoped `PUBLIC_PASS` established
- prepared device runner: `work/install-v0131-pixel9a.ps1`; syntax PASS; local/public hash and certificate preflight PASS; SHA-256 `B33B47EA2D9026FDF7C4FAA72184439B89CE7777644BE99117E23B8AF37FF721`; stopped truthfully at `Pixel 9a 5A121JEBF08094 is not attached`
- current boundary: physical retained-data install, route/focus contention, actual microphone/system capture, subjective audio, and `HUMAN_GO` remain unclaimed; local and public debug certificates differ, so the public APK is copied to Downloads but not installed over retained data

## 2026-08-14 v0.13.0 Luna interaction integrity candidate

- review fan-out: 20 independent `gpt-5.6-luna` medium/default packets, followed by fixed-point Standards/Spec passes and one final independent verifier; every accepted child runtime was verified; final verifier found no P0-P2 blocker
- review-driven correction: the first full-Bank-A change still selected A01 when another bank was active; final policy keeps bank/selection unchanged and requires explicit overwrite/clear; focused regression passes
- implemented contracts: completed-tap-only destructive Chop, long-press trim safety, A-only empty destination, REC auto-start plus deterministic first hit, truthful stage availability, project/source reconciliation, operation-specific permission copy, fixed indexed PAD mailbox, out-of-band transport Stop All, STOPPING-safe recorder failures, failed-vocal loop cleanup, revision-safe analysis, event-rate-independent Scratch, target-correct PAD range, cached waveform envelopes, bounded readouts, and TalkBack waveform actions
- open-source boundary: original deterministic built-in drum synthesis only; no downloaded artist-named kits; `PRIVACY.md`, `NOTICE`, issue templates, README links, and feature/evidence matrix added
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL, direct exit 0
- unit tests: 194 tests / 42 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 10; assemble PASS
- configured `scripts/validate_project.sh`: PASS; pure Kotlin smoke PASS; four Android XML files parsed; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- `git diff --check`: PASS; UI source scroll API matches: 0
- local APK: `outputs/ChopLab-v0.13.0-luna-interaction-integrity-local-debug.apk`; 30,804,939 bytes; SHA-256 `3438CCD65D3C84BAEA47B9385B1EF465ED9A2E517C155D7A7E0C93E4D6FFB56B`
- metadata: package `com.choplab.sampler`; versionCode 20 / versionName 0.13.0; minSdk 29 / targetSdk 36; APK Signature Scheme v2; local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- emulator `emulator-5588`, Android 16/API 36: exact data-preserving `adb install -r` PASS; pulled installed-base size/hash equals host APK; cold-launch `MainActivity` PASS; package exit-info crash/ANR reasons 0
- emulator retained archives unchanged before install, after install, and after launch: 10,529-byte autosave `06689B6194D18E3808E7CBB9533F8B9D4A13D0093676B39DA89046362E5B1128`; 23,614-byte previous `5D81576BDB43F0ABD549947B38C050698A8610EDF932288DD8225E1AA3471BF8`; 23,594-byte previous2 `2E0111AD2F586344A23071A69D1455605B573A98851C60B43CD32821E51B2D0B`
- emulator UI: Chop hierarchy 175 package nodes, scrollable nodes 0; A Melody PAD 01-16 all visibly empty; evidence `work/v013-emulator/v013.{png,xml}` and `installed-base.apk`
- provider runs: branch push `31724970140`, PR `31724972880`, tag verification `31725302532`, and release `31725302549` all PASS for exact commit `61f1044610ee172785d87478659862fb4f342be3`
- annotated tag `v0.13.0-preview.1` peels to exact commit; public prerelease `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.0-preview.1`
- public APK: 30,804,939 bytes; SHA-256 `B25E018C8743D9EC7459FDDF5698F008E41D34D7FB34336961865B34F867C86A`; GitHub digest, checksum sidecar, authenticated reverse download, and anonymous reverse download all match
- public metadata: package `com.choplab.sampler`; versionCode 20 / versionName 0.13.0; targetSdk 36; APK Signature Scheme v2; CI debug certificate SHA-256 `5B499749A2C9392A90DB2C099E6EAD00D49D90A89DC1B9A36577959EED411182`
- anonymous HTTP: public repository 200, Release page 200, direct APK 200; scoped `PUBLIC_PASS` established
- current boundary: physical Pixel 9a `5A121JEBF08094` absent from ADB/mDNS/Windows USB inventory, so physical `DEVICE_PASS`, touch/audio/TalkBack, exact Pixel Download copy, and `HUMAN_GO` remain pending; CI and local debug certificates differ, so no data-destructive public APK replacement was attempted

## 2026-08-13 v0.12.0 Production Dock contract and autosave recovery truth

- focused TDD RED/GREEN: Capture/Chop/Beat Dock order and enabled/active/confirmation state; autosave recovery begin/empty/failure reducers; Capture status and empty-waveform loading copy
- final offline validation: PASS; Gradle Wrapper SHA-256 matched
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1` BUILD SUCCESSFUL
- unit tests: 169 tests / 37 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 7
- `git diff --check`: PASS; UI source scroll API matches: 0
- local APK: `outputs/ChopLab-v0.12.0-production-dock-contract-local-debug.apk`; 31,615,690 bytes; SHA-256 `B0CF6B6DFE21FF24B5AC5BD457E6EEE637B75BFDB4EA438044CB84A5A07B1C29`
- metadata: package `com.choplab.sampler`; versionCode 19 / versionName 0.12.0; minSdk 29 / targetSdk 36; APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- physical Pixel 9a `5A121JEBF08094`, Android 17 / API 37 / arm64-v8a: exact data-preserving `adb install -r` PASS; host, installed-base, and phone Download APK size/hash match
- device UI: startup shows `LOADING / 音声を読込中 / PLEASE WAIT` instead of false `NO SOURCE`; FILE/MIC REC/DEVICE REC parent buttons are all `enabled=false` during recovery; retained source then restores; Capture/Chop/Beat each have 0 scrollable nodes; Chop has BEAT/PAD EDIT/ADD/SCRATCH and Beat has QUICK/STEPS/ADD/SCRATCH; focused fatal/ANR matches 0
- retained data: four project archive sizes and hashes unchanged before install, after install, and after startup recovery/navigation
- evidence: `work/pixel9a-v0120-dock-contract/final4-loading.{png,xml}`, `final4-capture.png`, `final4-restored.xml`, `final4-chop.{png,xml}`, `final4-beat.{png,xml}`, `installed-base-final4.apk`
- boundaries: no subjective audio, duplicate-audio listening, TalkBack/large-font/landscape matrix, public release, or Human GO claim

## 2026-08-12 v0.12.0 state-truth playback and Production Dock candidate

- GPT Pro full-file review: one privacy-scanned bundle contained all 188 Git-tracked files and 193 total packet entries; accepted transcript SHA-256 `D27FFE3765E23A3CE7E05A138F387BD8874B5AD260C9441462EBFBB78CE7C52E`
- focused TDD RED/GREEN: source phase/reconciliation, pending-start PAD accessibility, stage navigation, fixed Production Dock, Stop-All ordering/state/completion copy, source replacement/reset STOPPING truth, and runtime-only Undo intent
- full host gate: 163 tests in 36 suites / failures 0 / errors 0 / skipped 0; Lint 0 errors / 10 warnings; assemble PASS
- configured offline project validation PASS with constrained validation JVM; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; UI scroll API scan zero matches
- local APK: versionCode 19 / versionName 0.12.0; 31,592,862 bytes; SHA-256 `028737528EE6211DE8A9497216161FA870E4AAB8D4C193CE9C45AC26771A966F`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- physical Pixel 9a `5A121JEBF08094`, Android 17 / API 37 / arm64-v8a: installed exact APK with data-preserving `adb install -r`; upgraded 0.10.0 to 0.12.0; cold launch PASS; process alive; focused fatal/ANR matches 0
- retained data checkpoint: four project archives preserved identical sizes and SHA-256 before install, after install, and after launch/navigation; no reset, playback, or project-destructive interaction was used
- UI checkpoint: Capture, Chop, and Beat dumps each had zero scrollable nodes; Chop showed 4 x 4 square PADs and `BEAT / PAD EDIT / ADD / SCRATCH`; Beat showed `QUICK / STEPS / ADD / SCRATCH`
- accepted exact-final runtime evidence: `work/pixel9a-v0120-exact-final/exact-final.{png,xml}`, `exact-final-chop.{png,xml}`, `exact-final-beat.{png,xml}`, and pulled `installed-base.apk`; installed-base SHA-256 matches the host and phone-Download APK
- review execution: the live child route was declared Luna-pinned, but returned completion metadata omitted the effective model; final Standards and Spec passes were therefore independently rerun by the local parent. The first passes found stale docs, duplicated transition logic, replacement/reset truth loss, and Undo runtime-intent leakage; all actionable findings were fixed. No substitute child model was used
- evidence boundary: scoped `DEVICE_PASS` covers exact install, launch, fixed UI, and data preservation only; physical audio duplication/latency/quality, TalkBack, landscape/font scale, public release, and `HUMAN_GO` remain unverified

現在HEADへ結合したWindows検証は、tracked-clean checkoutとプロジェクト用JDK/SDKを設定して `scripts/verify.ps1` を実行する。clean unit/lint/app/androidTest build後に `outputs/build-provenance-<HEAD>.json` を作成し、source HEAD/tree、fresh APK bytes、package/version、signerを一つのreceiptへ固定する。現在HEADのreceiptがない既存 `app/build` APKは、hashだけで最新成果物として扱わない。

## 2026-08-16 waveform evidence hardening

- test isolation: `SourceWaveformDeviceTest` renders deterministic in-memory PCM and no longer reads/writes Pixel autosave or requires a pre-existing chop marker
- semantics boundary: tests prove Compose state descriptions and custom-action callbacks; they do not claim a running TalkBack service, spoken output, or focus traversal
- geometry: host tests cover whole/zoomed/invalid overview geometry; device tests cover true two-pointer pinch/pan and S/E/chop target width, height, clipping, endpoints, and exact reversible nudge
- accessibility behavior: viewport and handle actions report `false` when clamped/no-op instead of announcing a false success
- recording boundary: existing pure recording-session and interruption coordinator tests cover mutual exclusion without activating a real microphone; physical recording quality/contention remains unclaimed
- evidence: `scripts/collect-device-evidence.ps1` records clean source identity, Gradle logs, APK identities/signers, signer preflight, autosave before/after, `install -r`, base.apk readback, instrumentation output, package dumps, timestamp-bounded logcat, and final launcher/volume/rotation/project state under one manifest
- gates remain split: `LOCAL_PASS` / `INSTRUMENTATION_PASS` / physical observation / spoken TalkBack / `HUMAN_GO`
- official Android test hardening: Compose Accessibility Test Framework plus `UiAutomation` / `AccessibilityNodeInfo` inspect the deterministic fixture through the Android framework tree; a dedicated normal API 36 Google Play AVD passed four tests including S/E/chop1-5 depth-first tree order, advertised focus actions, and framework custom-action state mutation. This is `FRAMEWORK_NODE_PASS`, not a claim about TalkBack focus traversal or TTS.
- clean official-test gate: `clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL; 224 unit tests / 45 suites / zero failures, errors, or skips; clean app SHA-256 `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162` (30,855,284 bytes), final test SHA-256 `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE` (10,589,229 bytes), both signer SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`; the exact app/final-test APK pair reinstalled on `emulator-5562` and `SourceWaveformDeviceTest` returned `OK (4 tests)` in 9.335 s.
- 2026-08-16 TalkBack continuation: the actual TalkBack service plus touch exploration produced visible accessibility-focus rings and exposed the corrected S/E/clustered-marker tree on Pixel. ADB touch and the virtual keyboard could not reliably dispatch TalkBack's own next/custom-action gesture path, so spoken labels and complete TalkBack traversal remain `HUMAN_GO`; they are not promoted from framework-node automation. Separate bounded real-microphone checks proved recording ownership against song playback, selected-source loop, and source preview without retaining or reporting microphone content.
- final exact Pixel run: `work/device-evidence/20260816-220355-233297e3/manifest.json`; clean HEAD `233297e39f404bb8e0080110c3d29a528dd8c615`, app/readback SHA-256 `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162`, test/readback SHA-256 `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE`, Pixel instrumentation `OK (4 tests)` in `7.484 s`, autosave preservation and phone-state restoration PASS, 929 ms cold relaunch PASS, fatal/ANR 0. The real TalkBack service accepted a next-item gesture and visibly focused the formerly occluded S/marker cluster; TTS content and full service-dispatched custom-action order remain `HUMAN_GO`.
- authoritative exact run: `work/device-evidence/20260816-185953-b3579f05/manifest.json`; clean HEAD `b3579f0592738ccf2e95f10d1f0bba42cc343578`, app/readback SHA-256 `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`, test/readback SHA-256 `DE97432A1C1278E7661FD656DFCC054CFABA6A4BCC6D9DECF44B810564F83EC8`, deterministic Pixel instrumentation `OK (3 tests)` in `5.138 s`, lower/upper marker endpoints, autosave preservation, and phone-state restoration machine-gated, bounded app fatal/ANR 0

## 2026-08-14 v0.13.1 playback interruption safety candidate

- architecture: pure `PlaybackInterruptionCoordinator` owns focus-session state and interruption/recording policy; `AndroidPlaybackFocusAdapter` owns only `AudioManager` and the protected noisy-output receiver; no UI or persistence schema change
- behavior: every audible start is focus-gated; focus loss/transient/duck, Home, and output-route loss stop once; gain never auto-resumes; source seek/KEY retarget requires active coordinator ownership; rotation is exempt from background interruption
- recording policy: microphone and vocal sessions request graceful stop; Android Playback Capture continues in background while app playback stops
- independent review: Standards and Spec passes plus final parent-side verification; corrected missing state docs, unknown non-gain focus handling, and unproven retarget ownership; no scope-creep or additional clear behavior defect found. Effective child model metadata was not exposed, so no runtime-verified Luna claim is made for this milestone
- focused TDD: missing retarget ownership API was observed RED at Kotlin test compilation; unknown focus mapping and coordinator ownership are GREEN after the smallest production change
- configured `scripts/validate_project.sh`: PASS; pure Kotlin smoke PASS; four Android XML files parsed; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` BUILD SUCCESSFUL
- unit tests: 207 tests / 44 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 11; debug and unsigned release APK assembly PASS
- local APK: `outputs/ChopLab-v0.13.1-playback-interruption-safety-local-debug.apk`; 30,821,319 bytes; SHA-256 `9A11118395AEC68AF6A739416514135FAEFF562302EB541573A49CF48A038668`
- metadata: package `com.choplab.sampler`; versionCode 21 / versionName 0.13.1; minSdk 29 / targetSdk 36; APK Signature Scheme v2; local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- unsigned release APK: 23,603,385 bytes; SHA-256 `41C318EEE607EF28391A9BE38751F2D82D9B4B3934AEFC7F42E1702F9343A4D9`
- dedicated tracked emulator `emulator-5590`, Android 16/API 36: exact data-preserving `adb install -r` PASS; installed package reports versionCode 21 / versionName 0.13.1
- runtime focus: one `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` GAIN entry with `PAUSES_ON_DUCKABLE_LOSS`; Home emptied the live stack and return status reported the background stop; portrait/landscape recreation retained focus; `ALL STOP` emptied it
- automation boundary: shell injection of protected `ACTION_AUDIO_BECOMING_NOISY` is rejected by Android, so actual wired/Bluetooth route loss remains physical-device evidence
- provider runs for exact `903c698c2fdc443027a8190aa31985253ff3050a`: branch push `31764219592`, PR `31764223167`, tag verification `31764417666`, and Release `31764417670` all PASS
- annotated tag object `b11eaa13be6c7e4d8bc7cbfcf805dc8ab25dc436` peels locally and remotely to the exact commit; public non-draft prerelease: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.1-preview.1`
- public APK: `outputs/ChopLab-v0.13.1-preview.1-debug.apk`; 30,821,319 bytes; SHA-256 `5EE5183C2CA6574E964CC4A6AE44B4BE72813A691843345D9FA78B5ADE6598D6`; GitHub asset digest, sidecar, authenticated reverse download, and anonymous reverse download all match
- public metadata: package `com.choplab.sampler`; versionCode 21 / versionName 0.13.1; targetSdk 36; APK Signature Scheme v2; CI debug certificate SHA-256 `A04BC943A7F0C31ABC619839CDE0B28B2165700DE2F57D501F5B9DA0D0F9A2E2`
- anonymous HTTP: repository 200, Release page 200, direct APK 200; scoped `PUBLIC_PASS` established
- prepared device runner: `work/install-v0131-pixel9a.ps1`; syntax PASS; local/public hash and certificate preflight PASS; SHA-256 `B33B47EA2D9026FDF7C4FAA72184439B89CE7777644BE99117E23B8AF37FF721`; stopped truthfully at `Pixel 9a 5A121JEBF08094 is not attached`
- current boundary: physical retained-data install, route/focus contention, actual microphone/system capture, subjective audio, and `HUMAN_GO` remain unclaimed; local and public debug certificates differ, so the public APK is copied to Downloads but not installed over retained data

## 2026-08-14 v0.13.0 Luna interaction integrity candidate

- review fan-out: 20 independent `gpt-5.6-luna` medium/default packets, followed by fixed-point Standards/Spec passes and one final independent verifier; every accepted child runtime was verified; final verifier found no P0-P2 blocker
- review-driven correction: the first full-Bank-A change still selected A01 when another bank was active; final policy keeps bank/selection unchanged and requires explicit overwrite/clear; focused regression passes
- implemented contracts: completed-tap-only destructive Chop, long-press trim safety, A-only empty destination, REC auto-start plus deterministic first hit, truthful stage availability, project/source reconciliation, operation-specific permission copy, fixed indexed PAD mailbox, out-of-band transport Stop All, STOPPING-safe recorder failures, failed-vocal loop cleanup, revision-safe analysis, event-rate-independent Scratch, target-correct PAD range, cached waveform envelopes, bounded readouts, and TalkBack waveform actions
- open-source boundary: original deterministic built-in drum synthesis only; no downloaded artist-named kits; `PRIVACY.md`, `NOTICE`, issue templates, README links, and feature/evidence matrix added
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL, direct exit 0
- unit tests: 194 tests / 42 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 10; assemble PASS
- configured `scripts/validate_project.sh`: PASS; pure Kotlin smoke PASS; four Android XML files parsed; Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
- `git diff --check`: PASS; UI source scroll API matches: 0
- local APK: `outputs/ChopLab-v0.13.0-luna-interaction-integrity-local-debug.apk`; 30,804,939 bytes; SHA-256 `3438CCD65D3C84BAEA47B9385B1EF465ED9A2E517C155D7A7E0C93E4D6FFB56B`
- metadata: package `com.choplab.sampler`; versionCode 20 / versionName 0.13.0; minSdk 29 / targetSdk 36; APK Signature Scheme v2; local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- emulator `emulator-5588`, Android 16/API 36: exact data-preserving `adb install -r` PASS; pulled installed-base size/hash equals host APK; cold-launch `MainActivity` PASS; package exit-info crash/ANR reasons 0
- emulator retained archives unchanged before install, after install, and after launch: 10,529-byte autosave `06689B6194D18E3808E7CBB9533F8B9D4A13D0093676B39DA89046362E5B1128`; 23,614-byte previous `5D81576BDB43F0ABD549947B38C050698A8610EDF932288DD8225E1AA3471BF8`; 23,594-byte previous2 `2E0111AD2F586344A23071A69D1455605B573A98851C60B43CD32821E51B2D0B`
- emulator UI: Chop hierarchy 175 package nodes, scrollable nodes 0; A Melody PAD 01-16 all visibly empty; evidence `work/v013-emulator/v013.{png,xml}` and `installed-base.apk`
- provider runs: branch push `31724970140`, PR `31724972880`, tag verification `31725302532`, and release `31725302549` all PASS for exact commit `61f1044610ee172785d87478659862fb4f342be3`
- annotated tag `v0.13.0-preview.1` peels to exact commit; public prerelease `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.0-preview.1`
- public APK: 30,804,939 bytes; SHA-256 `B25E018C8743D9EC7459FDDF5698F008E41D34D7FB34336961865B34F867C86A`; GitHub digest, checksum sidecar, authenticated reverse download, and anonymous reverse download all match
- public metadata: package `com.choplab.sampler`; versionCode 20 / versionName 0.13.0; targetSdk 36; APK Signature Scheme v2; CI debug certificate SHA-256 `5B499749A2C9392A90DB2C099E6EAD00D49D90A89DC1B9A36577959EED411182`
- anonymous HTTP: public repository 200, Release page 200, direct APK 200; scoped `PUBLIC_PASS` established
- current boundary: physical Pixel 9a `5A121JEBF08094` absent from ADB/mDNS/Windows USB inventory, so physical `DEVICE_PASS`, touch/audio/TalkBack, exact Pixel Download copy, and `HUMAN_GO` remain pending; CI and local debug certificates differ, so no data-destructive public APK replacement was attempted

## 2026-08-13 v0.12.0 Production Dock contract and autosave recovery truth

- focused TDD RED/GREEN: Capture/Chop/Beat Dock order and enabled/active/confirmation state; autosave recovery begin/empty/failure reducers; Capture status and empty-waveform loading copy
- final offline validation: PASS; Gradle Wrapper SHA-256 matched
- final Gradle gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1` BUILD SUCCESSFUL
- unit tests: 169 tests / 37 suites; failures 0, errors 0, skipped 0
- Android Lint: errors 0, warnings/advisories 7
- `git diff --check`: PASS; UI source scroll API matches: 0
- local APK: `outputs/ChopLab-v0.12.0-production-dock-contract-local-debug.apk`; 31,615,690 bytes; SHA-256 `B0CF6B6DFE21FF24B5AC5BD457E6EEE637B75BFDB4EA438044CB84A5A07B1C29`
- metadata: package `com.choplab.sampler`; versionCode 19 / versionName 0.12.0; minSdk 29 / targetSdk 36; APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- physical Pixel 9a `5A121JEBF08094`, Android 17 / API 37 / arm64-v8a: exact data-preserving `adb install -r` PASS; host, installed-base, and phone Download APK size/hash match
- device UI: startup shows `LOADING / 音声を読込中 / PLEASE WAIT` instead of false `NO SOURCE`; FILE/MIC REC/DEVICE REC parent buttons are all `enabled=false` during recovery; retained source then restores; Capture/Chop/Beat each have 0 scrollable nodes; Chop has BEAT/PAD EDIT/ADD/SCRATCH and Beat has QUICK/STEPS/ADD/SCRATCH; focused fatal/ANR matches 0
- retained data: four project archive sizes and hashes unchanged before install, after install, and after startup recovery/navigation
- evidence: `work/pixel9a-v0120-dock-contract/final4-loading.{png,xml}`, `final4-capture.png`, `final4-restored.xml`, `final4-chop.{png,xml}`, `final4-beat.{png,xml}`, `installed-base-final4.apk`
- boundaries: no subjective audio, duplicate-audio listening, TalkBack/large-font/landscape matrix, public release, or Human GO claim

## 2026-08-12 v0.12.0 state-truth playback and Production Dock candidate

- GPT Pro full-file review: one privacy-scanned bundle contained all 188 Git-tracked files and 193 total packet entries; accepted transcript SHA-256 `D27FFE3765E23A3CE7E05A138F387BD8874B5AD260C9441462EBFBB78CE7C52E`
- focused TDD RED/GREEN: source phase/reconciliation, pending-start PAD accessibility, stage navigation, fixed Production Dock, Stop-All ordering/state/completion copy, source replacement/reset STOPPING truth, and runtime-only Undo intent
- full host gate: 163 tests in 36 suites / failures 0 / errors 0 / skipped 0; Lint 0 errors / 10 warnings; assemble PASS
- configured offline project validation PASS with constrained validation JVM; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; UI scroll API scan zero matches
- local APK: versionCode 19 / versionName 0.12.0; 31,592,862 bytes; SHA-256 `028737528EE6211DE8A9497216161FA870E4AAB8D4C193CE9C45AC26771A966F`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- physical Pixel 9a `5A121JEBF08094`, Android 17 / API 37 / arm64-v8a: installed exact APK with data-preserving `adb install -r`; upgraded 0.10.0 to 0.12.0; cold launch PASS; process alive; focused fatal/ANR matches 0
- retained data checkpoint: four project archives preserved identical sizes and SHA-256 before install, after install, and after launch/navigation; no reset, playback, or project-destructive interaction was used
- UI checkpoint: Capture, Chop, and Beat dumps each had zero scrollable nodes; Chop showed 4 x 4 square PADs and `BEAT / PAD EDIT / ADD / SCRATCH`; Beat showed `QUICK / STEPS / ADD / SCRATCH`
- accepted exact-final runtime evidence: `work/pixel9a-v0120-exact-final/exact-final.{png,xml}`, `exact-final-chop.{png,xml}`, `exact-final-beat.{png,xml}`, and pulled `installed-base.apk`; installed-base SHA-256 matches the host and phone-Download APK
- review execution: the live child route was declared Luna-pinned, but returned completion metadata omitted the effective model; final Standards and Spec passes were therefore independently rerun by the local parent. The first passes found stale docs, duplicated transition logic, replacement/reset truth loss, and Undo runtime-intent leakage; all actionable findings were fixed. No substitute child model was used
- evidence boundary: scoped `DEVICE_PASS` covers exact install, launch, fixed UI, and data preservation only; physical audio duplication/latency/quality, TalkBack, landscape/font scale, public release, and `HUMAN_GO` remain unverified

## 2026-08-12 v0.11.3 clear Chop actions and accessibility candidate

- focused TDD: the assigned-PAD capture-mode accessibility test failed twice with `expected タップで試聴。長押しで微調整 but was 現在位置をチョップ`, then passed after the assigned/empty split
- full host gate: 144 tests in 33 suites / failures 0 / errors 0 / skipped 0; Lint 0 errors / 10 warnings; clean assemble PASS
- configured offline project validation PASS; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; UI scroll API scan zero matches
- local APK: versionCode 18 / versionName 0.11.3; 30,739,403 bytes; SHA-256 `463C58518F0D47B58DAD75C9DF0F0893D8838DD05372E7C74036FDBBB6908E3C`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- dedicated Pixel 9 / API 36 emulator `emulator-5590`: exact clean APK accepted through data-preserving `adb install -r`; retained 5,317,098-byte autosave stayed SHA-256 `C5B66AF4A464186571FEBE718B307FC411D33D2A2316DBD3D87D2A31D4AE3689`
- normal and 130% font-scale UI: complete `空PAD＝追加／音ありPAD＝試聴・長押し微調整 → ビートへ` TIP; assigned A-01 accessibility says audition/long-press trim; empty A-06 says chop current position; scrollable nodes 0
- accepted runtime files: `work/v0113-final.png`, `work/v0113-final.xml`, `work/v0113-final-font130.png`, and `work/v0113-final-font130.xml`; process alive; scoped fatal/ANR matches 0; system font scale restored to 1.0
- Sol-specified review found the visual guidance issue and the contradictory TalkBack label; effective child-model metadata was unavailable, so no runtime-verified Sol claim is made
- physical Pixel 9a `5A121JEBF08094`: not attached; data-preserving phone install and physical sound/touch checks pending
- PR #26 merged as `17d2e203bbece5d1f1be7e46042a0389256596bc`; branch `31540964591` / `31540979286`, main `31541222720`, tag verification `31541469351`, and release `31541469492` runs PASS
- annotated tag `v0.11.3-preview.1` peels locally/remotely to the merge commit; Release is public and marked prerelease
- reverse-downloaded public APK: 30,739,403 bytes; SHA-256 `D1DB9F44054C239C2B0C9438FB97487B34CB678E7EA4E5366DDEA7BBBF053867`
- downloaded APK, GitHub asset digest, and checksum sidecar three-way match; package/version/minSdk/targetSdk and APK Signature Scheme v2 verified
- public certificate SHA-256 `3383BD82CBF84972CFF3A8C8B4EC39061868A2B2B08A05056823FD08CACDCBAA` differs from the installed local certificate, so no data-destructive replacement was attempted
- anonymous HTTP checks: repository 200, Release page 200, direct APK 200; this establishes `PUBLIC_PASS`, not physical `DEVICE_PASS` or `HUMAN_GO`
- final Pixel check: exact serial absent from ADB and mDNS; both Windows Pixel 9a PnP records `Present=False`

## 2026-08-12 v0.11.2 truthful step-placement candidate

- TDD RED/GREEN seams: PAD step eligibility, normal and record-armed mutation, performance-pad routing, Beat-lane fallback/disabled accessibility, LOOP/VOCAL guidance, archive round-trip, and audible legacy-step filtering
- full host gate: 143 tests / failures 0 / errors 0 / skipped 0; Lint 0 errors / 7 warnings; assemble PASS
- configured offline project validation PASS; Gradle Wrapper SHA-256 matched; `git diff --check` PASS; UI scroll API scan zero matches
- local APK: versionCode 17 / versionName 0.11.2; 30,739,403 bytes; SHA-256 `F706923F28495754CCB5B5DFEB42E2D7D89F574A6B27DEE10563A1A83344DAB4`
- local APK metadata: package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2; certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- dedicated Pixel 9 / API 36 emulator `emulator-5590`: data-preserving `adb install -r` PASS; install-checkpoint autosave SHA-256 stayed `76BF3EACA193F877033123590A5360E3D3A083696A812C254B029EB9EA151BF4`
- emulator LOOP-path evidence: selected A-04, coaching changed to `ループは音声全体を反復。配置は別PAD`, and A step 2 exposed `配置できません` with `enabled=false`
- disabled-step mutation check: stable 5,317,098-byte autosave remained SHA-256 `C5B66AF4A464186571FEBE718B307FC411D33D2A2316DBD3D87D2A31D4AE3689` before and more than four seconds after the press
- accepted runtime files: `work/v0112-loop-disabled-after.png` and `work/v0112-loop-disabled-after.xml`; process alive; scoped fatal/ANR matches 0
- saved invalid LOOP/VOCAL step keys remain archive-compatible but are filtered from realtime playback, arrangement markers, Finish/preset truth, and export; new direct, preset, and record-armed mutation is blocked
- Sol-specified audit/review closed the primary and follow-up paths; effective child-model metadata was unavailable, so no runtime-verified Sol claim is made
- physical Pixel 9a `5A121JEBF08094`: not attached; data-preserving phone install and physical sound/touch checks pending
- PR #24 merged as `cf6996873b446f61f2e74910e93ad4495e74b263`; branch `31536276746` / `31536297883`, main `31536570140`, tag verification `31536868910`, and release `31536868984` runs PASS
- annotated tag `v0.11.2-preview.1` peels locally/remotely to the merge commit; Release is public and marked prerelease
- reverse-downloaded public APK: 30,739,403 bytes; SHA-256 `7FE63CEADB27BBA59142EEDBFEB7A346C9F487E6CB00C5CD4B3EB7182EE3FCEE`
- downloaded APK, GitHub asset digest, and checksum sidecar three-way match; package/version/minSdk/targetSdk and APK Signature Scheme v2 verified
- public certificate SHA-256 `F100B8D8C189BDBA933779AB2ACCD6BBE374BC7D01E592F92684A26595C6B196` differs from the installed local certificate, so no data-destructive replacement was attempted
- anonymous HTTP checks: repository 200, Release page 200, direct APK 200; this establishes `PUBLIC_PASS`, not physical `DEVICE_PASS` or `HUMAN_GO`
- final Pixel check: exact serial absent from ADB and mDNS; both Windows Pixel 9a PnP records `Present=False`

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
- PR #22 merged as `755c30ffced5db408d89e37cf80c4caf53f02896`; branch `31530032522`, PR `31530071852`, main `31530374176`, tag verification `31530698604`, and release `31530698633` runs PASS
- annotated tag `v0.11.1-preview.1` peels locally and remotely to the merge commit; Release is public and marked prerelease
- reverse-downloaded public APK: 30,739,399 bytes; SHA-256 `BB4502733C3382C91BE6391F9A1EADC5E9F3BC5F0B6621E54B179B8BB16F4C65`
- downloaded APK, GitHub asset digest, and attached checksum sidecar three-way match; package/version/minSdk/targetSdk and APK Signature Scheme v2 verified
- public certificate SHA-256 `F2F5461C71A08CC71FF074B00E0F99DFCDB1489BBAD9545C29D0C93C6F86DA3D` differs from the installed local certificate, so no data-destructive replacement was attempted
- anonymous HTTP checks: repository 200, Release page 200, direct APK 200; this establishes PUBLIC_PASS for availability and artifact identity, not physical DEVICE_PASS or HUMAN_GO
- release workflow now passes `--prerelease` for preview-tag publication so future previews do not momentarily appear as stable releases

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

## 2026-08-16 Pixel 9a waveform/accessibility continuation

Clean HEAD `6943b5e` passed `clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
:app:assembleDebugAndroidTest`. The fail-closed exact-device run is bound by
`work/device-evidence/20260816-195805-6943b5ea/manifest.json`: app/test APK identity and signer, both installed
readbacks, `adb install -r`, three autosave hashes, `OK (3 tests)`, fatal/ANR scan, and phone-state restoration
passed. Real TalkBack exposed and focused the corrected clustered-handle region, but synthetic ADB swipe could
not prove complete spoken focus order or service-dispatched custom actions. A bounded real microphone capture
rejected source playback and BACK cancelled without changing any of the three autosave hashes. These results
are `DEVICE_DEPLOY_PASS`, retained-data PASS, and bounded objective device evidence, not full `DEVICE_PASS` or
subjective `HUMAN_GO`.

The user-authorized unattended continuation additionally verified real selected-source loop and source-preview
ownership before microphone capture. Starting `MIC REC` replaced each prior audio owner; BACK cancelled the
capture, the relaunch state was stopped, and all three autosave hashes remained equal to the baseline. ADB
virtual-keyboard and gesture input could not reliably dispatch TalkBack next/custom-action commands, so actual
spoken S/E/marker traversal remains outside `DEVICE_PASS` rather than being inferred from focus rings or
Compose semantics.
