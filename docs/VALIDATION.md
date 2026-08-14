# 検証記録

作成日: 2026-07-15

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
