# 検証記録

このファイルは revision-bound な検証履歴です。現在の branch、HEAD、tree、dirty boundary、receipt の採用範囲は [`docs/PROJECT_STATE.md`](PROJECT_STATE.md) の先頭 `Current snapshot` を参照してください。下記の過去セクションは削除せず、記録された revision と gate の範囲を越えて current proof として再利用しません。

## Wave 18 Android complete Beat-loop command admission — 2026-08-27

- Base/product: `8065c898da4461717b4266c9803b555449caf9d7` / product `5812c8a993eb57308dd4bf060ca7bd8ccea98ea5`, tree `1255aca145c6d92efa7eaea3f18e13b32e1287de`.
- RED/GREEN: after JUnit4 fixture alignment, current compilation failed only on missing batch port, transaction result, session snapshots and realtime apply seam. GREEN replaces owner+separate companion enqueue with one Boolean `startPadLoopSession` command.
- Transaction controls: rejection consumes no history/revision and a later edit remains valid; success demotes the previous LOOP, commits one edit and issues one owner/companion request; fatal engine failure cancels and propagates. Runtime helper proves companions are included for scratch/initial and intentionally excluded for vocal-recording restart.
- Realtime controls: snapshot preparation forces one LOOP owner, filters unassigned/self companions and retains order. Apply order is stop prior ownership → publish owner/frame → start owner → companions. Release bytecode: branch lines 85, `new` 0, Function refs 0, blocking/I/O/lock refs 0.
- Full proof: 184 tasks (145 executed / 39 up-to-date), `BUILD SUCCESSFUL in 3m27s`. Android 280 / 49 suites, shared Android 86 / 17, shared Desktop 86 / 17, JVM 88 / 9, Desktop 163 / 24; total 703 / 116, failures/errors/skips 0. Lint fatal/error 0 / warning 4 each.
- Policy/artifacts: Python 64, public current/history 461, configured validator 18 tasks, APK unsigned positive/signed-negative, Windows package and CycloneDX 650/651 pass. Android hashes debug `8EB1CEBB...9667A`, androidTest `F8AC9B2C...FE622`, release `FD23A077...F0F8F`; Windows manifest `A98268B6...FADC6`; SBOM JSON `86B83033...B3AA2`, XML `C73D25FB...584AC8`.
- Review/gate: local parent two-pass Standards/Spec unresolved `0/0`; `LOCAL_PASS` only. Physical AudioTrack output, route/focus timing, device/provider/public/signing/Human remain separate.

## Historical Wave 17 Windows initial Beat-loop startup transaction — 2026-08-27

- Exact base/current-main anchor: `9441b32da468393f79e10e65b50cd596ee19742a`, tree `08849f6b5f4568745523454e5b8854ceac89a995`. Product `ca9fdbea82e436e6ceaacf8c43f9afd215d6bcf7`; reviewed head `e2ebd9c342d48cf5f96c563715b82f2f8b8f4ca1`, tree `925fbff786b9120cdd3639a2e1542c756ee24067`.
- Current-main RED/GREEN: tests first failed compilation on missing `beatLoopControlEnabled` and `planEdit`. GREEN adds a non-consuming edit plan, shared visible admission, complete Java Sound candidate-set startup, transport-callback serialization and commit-after-start controller ordering.
- Negative controls: loading/recording invokes no startup; owner/companion recoverable failure preserves transport/project/history/revision and exact autosave archive bytes; fatal and contract-external errors propagate; a late transport callback cannot add a voice during handoff. Direct proxy-Clip tests prove all candidates start before source/old PAD retirement and candidate failure retires neither.
- Success controls: the complete session starts one loop owner plus eligible companions, retires prior playback once, publishes one owner, commits one Undo step when PAD modes change and keeps exact GATE ownership APIs. Active-loop stop remains covered by the existing suite.
- Executable proof: focused shared Android/Desktop plus Desktop gate passes 333 / 58. Full single-worker gate passes 184 tasks (150 executed / 34 up-to-date). XML: Android 276 / 47, shared Android 86 / 17, shared Desktop 86 / 17, JVM 88 / 9, Desktop 163 / 24; total 699 / 114, failures/errors/skips 0. Lint debug/release each: fatal/error 0 / warning 4.
- Policy: Python 64/64; public current/history 457 each; configured validator 18 tasks plus six XML, executable modes, wrapper SHA and UTF-8; `git diff --check` pass. Unsigned Android positive exits 0 (`0.17.0` / code 27 / `manifest_tool=apkanalyzer`); signed-required negative exits 1. CycloneDX 1.6 verifies 650 components / 651 dependencies.
- Artifacts: debug APK 31,803,506 / `BA96E55410DACE8B753F6C60600375429946069BDD4600404AA7C54399695BC8`; androidTest 10,996,855 / `F8AC9B2C1FC97672FCFB8565127D6099D80E906F49F623BE334C61AF102FE622`; unsigned release 24,274,036 / `D8165757CF4393DBCC808118D49585A23D5ACCB3237998BDE74620DF9D686AB5`; Desktop JAR 406,445 / `6659571F558CB832E9D88D21E8E9409A2EDB6370B912ADEA167026004EB25EE7`; Windows app-image 405 / 176,776,461 / manifest `A98268B6A9F53ACA288D89811DDAEA66170795B113EBA84D06A1EF5160EFADC6`; SBOM JSON `D2C005FDA6DE4CEB9C4FC5A8EE3E58E6AD52FA1525EA5A1FEE9E0A98D6341389`, XML `DD8F19E237CC36C84430B6F2E3442FD4ED3612F8CB4D383B89F7D427E1D190F4`.
- Review/gate: local parent two-pass Standards/Spec final unresolved `0/0`. The managed sandbox denied Javac/apkanalyzer access to readable workspace artifacts; identical offline commands passed outside it. Gate is `LOCAL_PASS`; no physical audio/device/provider/public/signing/Human claim is inferred.

## Historical Waves 12–16 PR #78 integration candidate — 2026-08-27

- Product: merge `04a761c589f9370811b54224801064448def2951`, tree `12984c19307849012fade6dae538a5b3b74bbd63`, parents Waves 12–15 receipt `9cca343fae45c74747a22eb04eca98ceb8c13543` and Wave 16 source `63092410f6f249d7b89c49393efe6cfbed349827` / tree `263c1958ccf0e52fa417fa6254a43bffde392cf0`.
- Admission contract: one shared loading/recording decision controls deck buttons, Windows menu and both platform controllers. The deep history owner rejects busy plans without consuming history or revision, invalidates a stale unresolved plan, and preserves normal Undo/Redo plus same-owner loop continuity.
- Integration control: `loadingTimeHistoryRequestPreservesTheProjectAndFrontier` directly binds the Windows controller boundary. Source-branch focused Standards/Spec review and the integrated diff have unresolved findings `0/0`.
- Executable proof: focused merged tests pass in 59 tasks. The final single-worker full gate passes in 184 tasks (39 executed / 145 up-to-date). XML: Android 276 / 47 suites, shared Android 82 / 16, shared Desktop 82 / 16, JVM-core 88 / 9 and Desktop 148 / 24; total 676 / 112, failures/errors/skips 0. Lint debug/release each: fatal 0 / error 0 / warning 7.
- Policy: configured validation passes 18 Gradle tasks, six XML resources, executable modes, wrapper SHA and UTF-8. Python policy passes 64/64. Current and reachable-history public-surface scans each accept 454 candidates with no credential, signing or user-audio candidate. `git diff --check` passes.
- Android artifacts: debug 32,901,372 / `42E0987046F9790EB7A26DEFE9D1544B153892F07B09A1BA9A6D4861A28A86D1`; androidTest 11,173,354 / `147D47F27C8DF3CD488E29C1EA682B5F85E73837DB0FB084375314C968B4D358`; unsigned release 24,274,036 / `2A25D94847AC0A1F6D0842B9F9E893EDF1DCF53FAA213B08E22A23174E7976C7`. Version `0.17.0` / code 27 and `manifest_tool=aapt2`; unsigned acceptance exits 0 and signed-required negative exits 1.
- Windows/SBOM: `ChopLab.exe` 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; Desktop JAR 393,533 / `61C619DDBD791A774C4B9F2CCA3051025D7A2917C6AD00FABEED2C211B8508F0`; app-image 405 files / 176,760,428 bytes / deterministic manifest `8B161FD1604F7ABC26D059292E772EDEA068C9FEFCAFE5B959697345812600E2`. CycloneDX 1.6 has 650 components / 651 dependencies; JSON 1,581,101 / `9FB3E143B2BF5A38C3BA0B16586AF308024B4ED9B5BCD238CA52179BFAA93D59`, XML 1,431,320 / `8E6311F678C261C7CF31E2331012CDA5B733165FF23BEEF6B222A1A0B9FB9A2B`.
- Review/gate: exact merged bytes establish `LOCAL_PASS`. Update only existing PR #78, require fresh exact-head PR/push Android, Windows, iOS and supply-chain success, clean mergeability and no unresolved review thread. Do not infer device/provider/signing/tag/new Release/Human proof, and do not rewrite `v0.17.0`.

## Historical Waves 12–15 PR #78 integration candidate — 2026-08-27

- Product: merge `37312af8c502621077c3a01c569dc4d797efbff5`, tree `a9a1e0715cf8b8105c0d1fd054b70bada6c0218f`, parents reviewed read-back bound fix `a531f7be70358ec284bbc263a06dc17923cd8767` and clean Wave 15 closeout `1deb8a9ec2198e88fcde07a572bf0a8f9eea333e` / product `70b31e949a9faa42d0f459f2d03218d16e6e30b7`. Exact hosted Waves 12–14 receipt `8680fdbbc0a7346ec02fb958ad5b2dbac3254f6d` remains the previous PR #78 anchor.
- Review RED/GREEN: an endless destination stream made the new regression throw after the old implementation requested 64 KiB. The fix passes the source count into read-back, requests at most remaining plus one and fails with the typed mismatch at the first extra byte. The test proves exactly `source.length + 1` bytes are consumed.
- Merge contract: every prior loop ownership, candidate-first replacement, atomic Windows publication and verified Android publication boundary remains while Wave 15 adds exact-once history preview/cancel/commit.
- Executable proof: focused merged tests pass in 59 tasks. The final single-worker full gate passes in 184 tasks (35 executed / 149 up-to-date). XML: Android 276 / 47 suites, shared Android/Desktop 77/77 / 15+15, JVM-core 88 / 9 and Desktop 145 / 23; total 663 / 109, failures/errors/skips 0. Lint debug/release each: fatal 0 / error 0 / warning 7.
- Policy: configured validation passes 18 Gradle tasks, six XML resources, executable modes, wrapper SHA and UTF-8. Python policy passes 64/64. Current and reachable-history public-surface scans each accept 450 candidates with no credential, signing or audio candidate. `git diff --check` passes.
- Android artifacts: debug 32,242,280 / `83AF5A0E5FD14E824CB73DC8A08ADA9221037E37503ACB9032FBBA3449C07E2F`; androidTest 11,173,354 / `147D47F27C8DF3CD488E29C1EA682B5F85E73837DB0FB084375314C968B4D358`; unsigned release 24,274,036 / `91E0185604BD4A365AB66BEEE1722C314964621F5FF38D0333C2A949963E7EAE`. Version `0.17.0` / code 27 and `manifest_tool=aapt2`; unsigned acceptance exits 0 and signed-required negative exits 1.
- Windows/SBOM: `ChopLab.exe` 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; Desktop JAR 391,090 / `80DD0AB3C92E956E7BD25A1458EB2FC0ABCD4971FD270C5783206D9B55E038FF`; app-image 405 files / 176,755,548 bytes / deterministic manifest `3BFA469B8E1D5A9AE1C6E1DC977ADC9139C6FAB20F9DF88FCEE09D7BC424C9F3`. CycloneDX 1.6 has 650 components / 651 dependencies; JSON 1,581,101 / `9FB3E143B2BF5A38C3BA0B16586AF308024B4ED9B5BCD238CA52179BFAA93D59`, XML 1,431,320 / `8E6311F678C261C7CF31E2331012CDA5B733165FF23BEEF6B222A1A0B9FB9A2B`.
- Review/gate: Standards and Spec unresolved findings are `0/0`; exact merged bytes establish `LOCAL_PASS`. Update only existing PR #78, require fresh exact-head Android/Windows/iOS/supply-chain success, clean mergeability and no unresolved review thread. Do not infer device/provider/signing/tag/Release/Human proof, and do not rewrite `v0.17.0`.

## Historical Waves 12–14 PR #78 integration candidate — 2026-08-27

- Product merge: `5495ed1780eea69e4a3f7d25c57cdda5714ac602`, tree `0ec045e93694dc5182e364ebc836e1c18e7bb69d`, parents existing PR #78 receipt `aab28763c8a44bee2e778578f17d11cf52075fb6` and clean Wave 14 closeout `924fb3c05697fb1ff801d3dba7fb1a77ee8c29dc`. Wave 14 product is `60068369e9c2820509a839c259693ae0ce2bac2d`.
- Manual conflict contract: preserve `PadTriggerOwnership`, the prior Desktop status-operation epoch, candidate-first replacement and atomic sibling publication while adding Wave 14 cancellation propagation and verified Android provider copy/read-back. No parent receipt substitutes for fresh validation of the merged bytes.
- Timing falsifier: the first unrestricted full run completed 140 Desktop tests with four 2-second autosave/close waits timing out while Android build work competed. The exact four tests then passed alone in 16 tasks, and the whole gate passed with one Gradle worker. No product or test byte changed between these runs; the initial timeout remains part of the receipt.
- Executable proof: focused `:jvm-core:test :app:testDebugUnitTest :shared:testAndroidHostTest :shared:desktopTest` passes in 51 tasks. The complete Android/shared/JVM/Desktop lint, assembly and Windows package set passes in 184 tasks (19 executed / 165 up-to-date). XML: Android 276 / 47 suites, shared Android/Desktop 74/74 / 15+15, JVM-core 87 / 9 and Desktop 140 / 23; total 651 / 109, failures/errors/skips 0. Lint debug/release each: fatal 0 / error 0 / warning 7.
- Policy: configured validation passes 18 Gradle tasks, six XML resources, executable modes, wrapper SHA and UTF-8. Python policy 64/64 passes. Current plus reachable-history public-surface accepts 449 candidates and finds no credential, signing or audio candidate. `git diff --check` passes.
- Android artifacts: debug 31,991,812 / `D87423C478565DDACB63D309CF9E758FD889419F15DA37C8C1049238CD9677AF`; androidTest 11,173,354 / `147D47F27C8DF3CD488E29C1EA682B5F85E73837DB0FB084375314C968B4D358`; unsigned release 24,274,036 / `1BAEA282186E7C4345B5FC8A62E33BDD6C93EC629CCB0C5B4E519E9A61781207`. Version `0.17.0` / code 27, `manifest_tool=aapt2`; unsigned policy exits 0 and signed-required negative exits 1.
- Windows/SBOM: `ChopLab.exe` 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; Desktop JAR 388,159 / `3982FF58D89F567CC7AC12D766FB9B311FBFF007264E231BEA4D044AC121C043`; app-image 405 files / 176,747,758 bytes / deterministic manifest `038A399DAF1779C90B0F74893C8BBC22A8554E3AD55C3561232D2145DFEE5AB5`. CycloneDX 1.6 has 650 components / 651 dependencies; JSON is 1,581,101 / `9FB3E143B2BF5A38C3BA0B16586AF308024B4ED9B5BCD238CA52179BFAA93D59`, XML is 1,431,320 / `8E6311F678C261C7CF31E2331012CDA5B733165FF23BEEF6B222A1A0B9FB9A2B`.
- Review/gate: Standards and Spec unresolved findings are `0/0`. The result is `LOCAL_PASS`; update only existing PR #78, require fresh exact-head PR/push Android, Windows, iOS and supply-chain success plus clean mergeability, and do not infer device/provider/signing/tag/Release/Human proof.

## Wave 12/13 post-PR #58 integration candidate — 2026-08-27

- Product anchor: `cc4199f0c17ededfd3847ba0ffc14f0c0bdbc4c8`, tree `0ab9a9ca90885b9d3e77f44e434d81da2aff22ad`, with parents merged `main@592b4ee777b32c9ce84d771166d04628cd104818` and integrated closeout `9043af2f582b4f8965ce973ab007d79de4d9324b`. The latter contains Wave 12 product `b8db4368511d8dd3578634bdd071be0da0f38b8a` and Wave 13 product `ffe1bbdf59d0645652c3f8556fb6f601e5218670`.
- Manual conflict contract: retain the dedicated export status epoch and exact voice-ownership token from main; retain candidate-first replacement, fatal-error propagation, injected Clip cleanup and atomic WAV publication from Waves 12/13. Both parent test families remain in the merged suite.
- Focused and full gates: `:shared:desktopTest :jvm-core:test :desktop:test` passes in 23 tasks. The full Android/shared/JVM/Desktop lint, test, assembly and Windows package gate passes in 184 tasks (17 executed / 167 up-to-date after focused compilation). XML read-back: Android 275 / 47 suites, shared Android 74 / 15, shared Desktop 74 / 15, JVM-core 74 / 8, Desktop 140 / 23; total 637 / 108 with failures/errors/skips 0. Lint debug/release each: fatal 0 / error 0 / warning 7.
- Policy: configured validation passes 18 Gradle tasks, six XML resources, executable modes, wrapper SHA and UTF-8. Python policy 64/64 passes. Current plus reachable-history public-surface scan accepts 446 candidates and finds no credential, signing or audio candidate. `git diff --check` and Git connectivity pass.
- Android artifacts: debug 32,900,764 / `F798F68CC7577883ADC2B309FCB95206E09EFB19321436FACA0A50B378FC022B`; androidTest 11,173,354 / `147D47F27C8DF3CD488E29C1EA682B5F85E73837DB0FB084375314C968B4D358`; unsigned release 24,274,036 / `09EBE02B71949DAF03AA22EF2968A2E7C58CF6170D419946451879ED8094CE4F`. Version `0.17.0` / code 27, `manifest_tool=aapt2`; unsigned policy exits 0 and signed-required negative exits 1.
- Windows/SBOM: `ChopLab.exe` 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; Desktop JAR 388,159 / `3982FF58D89F567CC7AC12D766FB9B311FBFF007264E231BEA4D044AC121C043`; app-image 405 files / 176,741,639 bytes / deterministic manifest `BD526293885A61EBC5AF00EF2263DF9D7F52C21633A17596F9EE68F7975084B2`. CycloneDX 1.6 has 650 components / 651 dependencies, 1,581,101 bytes / `9FB3E143B2BF5A38C3BA0B16586AF308024B4ED9B5BCD238CA52179BFAA93D59`.
- Review: Standards verifies repository rules, exact resource cleanup, recoverable/fatal separation, operation epochs, public-surface and dirty boundaries. Spec verifies candidate-first loop edit and atomic WAV success/failure/no-op paths while retaining per-voice ownership. Unresolved findings: `0/0`.
- Base hosted proof: PR #58 receipt `5ecd5ef` passed Android `32997081523`/`32997074816`, Windows `32997081446`/`32997074761`, iOS `32997081461`/`32997074776` and supply-chain `32997081538`/`32997074800`; Android finished 29 tests with 0 failures. It merged as `main@592b4ee`, whose push checks passed Android `32998169318`, Windows `32998169330`, iOS `32998169352` and supply-chain `32998169317`.
- Remaining gate: create one review-ready PR for the exact final receipt head, require fresh Android/Windows/iOS/supply-chain success and clean merge state, then merge. Parent receipts are inputs, not merged-head proof. No device, signing, tag, Release, provider or Human claim is inherited.

## Consolidated PR #58 integration candidate — 2026-08-27

- Integration parents: existing PR #58 head `d56244aa9c8208604341b08592db37dc3c3b816e`; PR #64 `9785d82c28bcc5c7d44de24b738f6bba0ed4f0a8`; PR #76 `330d57607fed062842e3a9df9920a01d95f505c1`; PR #77 `92db43402c5dad7b9b3a055f12728f5b2fa5ffeb`; Wave 10 closeout `600211fa20b0cb3f4e1fdcaf0878d0aecb7166a3`; Wave 11 closeout `7e8603c0e9a64ff6cb4b74d4d244985494a7256c`.
- Product anchor: `41a4bc37d2d84cadd5b5a787c62440bc76600e16`, tree `054db7e651c1eaeaa49aa523f3c58eda749bbff4`. Every integration parent and exact `main@4f56a693b86540d1be13a6eb2153a3cf96ef9396` is an ancestor; connectivity and `git diff --check` pass.
- Conflict controls: combined GATE token plus CHOKE transition, channel-aware ten-minute streaming limits, reverse cursor plus stereo render, transport readiness plus bar index, and Java Sound exact-once close plus clip factory. Older single-channel, stale-release, stop-failure, same-PAD and different-PAD negative controls are retained.
- Focused integration gate: `:shared:testAndroidHostTest :shared:desktopTest :jvm-core:test :desktop:test :app:testDebugUnitTest` completed successfully after one test-fixture repair pass. The first pass failed only because a pre-Song one-argument transport fixture and three renamed `readMono` calls had not adopted the merged interfaces.
- Full cross-platform gate after Wave 11 integration: `:shared:testAndroidHostTest :shared:desktopTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` completed successfully; 184 tasks. XML read-back: Android 275 / 47 suites, shared Android 74 / 15, shared Desktop 74 / 15, JVM-core 74 / 8, Desktop 125 / 20; total 622 / 105, failures/errors/skips 0. Lint debug and release each report fatal 0 / error 0 / warning 7.
- Policy and supply chain: configured validation passes public surface, executable modes, 18 Gradle tasks, six XML resources, wrapper digest and UTF-8 checks. Python policy 64 passes. Current-tree and reachable-history public-surface scans each accept 439 candidates. CycloneDX 1.6 verification reports 650 components / 651 dependencies; SBOM is 1,581,101 bytes / SHA-256 `9fb3e143b2bf5a38c3ba0b16586af308024b4ed9b5bcd238ca52179bfaa93d59`.
- Realtime release bytecode: `SamplerEngine.renderLoop` allocates its output array and three mutable stereo frames before the outer loop. The normal steady path has no monitor, `java.io` or `java.nio` reference; its only in-loop `new` is the fail-closed `IllegalStateException` branch for a negative `AudioTrack.write` result.
- Android artifacts after the hosted repairs: debug APK 32,900,764 bytes / `f798f68cc7577883adc2b309fcb95206e09efb19321436faca0a50b378fc022b`; androidTest APK 11,173,354 / `147d47f27c8df3cd488e29c1ea682b5f85e73837db0fb084375314c968b4d358`; unsigned release APK 24,274,036 / `09ebe02b71949daf03aa22ef2968a2e7c58cf6170d419946451879ed8094ce4f`. Release metadata is `0.17.0` / code 27. Manifest/security/alignment verification exits 0 as an unsigned candidate; the `--require-signed` negative control exits 1.
- Windows artifacts: `ChopLab.exe` 449,024 bytes / `05ba300784a2b98197200a7b5afcedd70b62913db71c1971b23a5e9785281630`, ProductVersion `0.17.0`; Desktop JAR 375,382 / `6f711f5b6990e696cda564f8a89e8e6caf42db68c64c3e6fbc23fe746f2ae5b8`; app-image 405 files / 176,728,862 bytes / deterministic manifest `2a9596546fac5a365d98889ae1cbdfb527947289f38daecd7225777a046d1b27` using slash-normalized relative path, decimal size and lowercase file SHA-256 with one LF-terminated record per file.
- Independent local review: Standards pass checked repository rules, realtime constraints, lifecycle/resource ownership, bounded inputs, public-surface policy and test/document discipline. Spec pass checked all four PR bodies plus the Wave 10 stereo/CHOKE/Song contracts and their negative paths. Unresolved findings are 0 / 0.
- First hosted integration attempt: supply-chain run `32988774942` passed on receipt `2a255690`. Android run `32988763732` reached the Linux `Offline project validation` step, then reproduced an integration-only smoke defect: the explicit standalone `kotlinc` source list compiled `PatternRenderer.kt` without its new direct `StereoPcm.kt` dependency. The source-list contract test failed first, the script now lists `StereoPcm.kt` before the renderer, and all 64 Python policy tests pass. Windows/iOS results from the superseded receipt are not used as proof; all four workflows must rerun on the repaired exact head.
- Wave 11 hosted feedback: exact receipt `8ee2f26` passed Windows `32993910045`, iOS `32993910073` and supply-chain `32993910026`. Android `32993909963` passed policy, offline validation, unit/lint/build and release inspection, then failed one of 29 API 36 tests because `largeTextCanReachDemoAndBothBeatSurfacesByScrolling` could not find the established Japanese `クイック` return action after opening `並べる詳細`. Product `41a4bc3` changes only `PADへ戻る / QUICK` to `クイックへ戻る / QUICK`; the focused 58-task compile/shared/unit gate and the full 184-task gate both pass after this repair, with XML 622 / 105 and failures/errors/skips 0. Exact receipt `eb384f8` then passed Windows `32995838663`, iOS `32995838174` and supply-chain `32995838455`; Android `32995838376` found the restored node but rejected the inherited `performScrollTo()` because the new focused surface intentionally has no scroll semantics. Test repair `59cfac9` removes only that invalid action and retains `assertIsDisplayed`; AndroidTest compile/package passes in 71 tasks.
- Hosted closure: exact receipt `5ecd5efc4357a76586ea5607413864c8b63989a1` passed the eight PR/push runs listed above and API 36 completed 29/29. PR #58 merged as `main@592b4ee777b32c9ce84d771166d04628cd104818`; its four main push workflows also passed. No tag, release, signing identity, device or provider boundary was touched. Existing `v0.17.0` cannot represent these later product bytes and must not be rewritten.

## Guided first-screen GitHub review repair — 2026-08-25

- Product source: reachable two-parent commit `3b5dd59b9271ab9a8a4abce9f03b80301b753861`, tree `4985a8ce8b30c4554e86c1695d2349d0b076024a`, advancing prior PR docs head `17a959a92fb3a08edf83662c8440d16c2de70178` and exact main `4f56a693b86540d1be13a6eb2153a3cf96ef9396`. GitHub recorded the product at `2026-08-24T15:10:27Z`; the documentation observation at `2026-08-25T00:10:38+09:00` occurred 11 seconds later. PR #52 and PRs #62, #65, #66, #67, #68, #70, #71, #73, #74 and #75 are integrated. The #65 Desktop readiness/step-zero, #67 8–192 kHz admission, #68 terminal-sample behavior/test, #71 import-name boundary, both recorder cancellation paths and #74 reverse renderer/test/force-loop behavior are retained.
- Review closure candidate: compact-landscape CAPTURE now opts into bounded scrolling; large-text CHOP plus BEAT quick/detailed bodies use stacked bounded-scroll compositions with explicit waveform and computed 48 dp-safe PAD-grid heights. CHOP and ONE SHOT commit on completed tap, including stationary holds on empty CHOP pads. GATE waits through a 120 ms scroll-classification window; initiating-pointer displacement beyond touch slop or leaving the PAD cancels before activation. Once activated, movement is consumed until that initiating pointer reaches physical up, even if another pointer remains. Every quick release receives at least 80 ms from the actual trigger. Ownership now covers deferred touch, normal-layout press-down touch, both stopped-source CHOP grid layouts and Desktop keyboard key-up. Android CHOP assigned GATE presses bypass token-discarding `capturePad` and call the ownership seam; admitted touch and sequencer voices receive per-voice tokens and conditional releases filter the exact voice. Desktop publishes ownership only after a successful Java Sound trigger and stores the token on that exact active voice. Parent/node cancellation, play-mode restart, one-finger vertical pass-through and horizontal/pinch/rotation claim behavior remain unchanged.
- Regression isolation: `FirstScreenFlowDeviceTest` renders deterministic in-memory CAPTURE/CHOP states and includes real normal-text GATE holds for BEAT and stopped-source CHOP. After a newer controller trigger, physical pointer-up issues the exact owned release; the newer voice remains separately owned. The proxy now models concurrent per-voice ownership instead of rejecting every stale generation. Existing real-pointer regressions retain PAD-origin parent scrolling with zero actions, completed-tap ordering, live ONE_SHOT→GATE routing, short-preview timing, overlapping touch retriggers with independent releases, post-activation ownership/no-trim, waveform scroll/no-seek, cancellation release, displaced/outside cancellation, initiating-pointer identity, pure rotation, empty CHOP hold and non-scroll press-down. `PadVoiceOwnershipTest` proves a full bounded mailbox does not publish a newer token and that an old pointer release finishes only its own voice while a newer sequencer voice stays active. Desktop tests bind key-up to the successful token, prove a failed retrigger leaves the older GATE voice releasable, and require an older physical release to close its exact Java Sound voice while a newer retrigger keeps a distinct token.
- Current-container checks: `git diff --check` PASS; Python policy suite 39/39 PASS; exact-head public-surface scan 399 candidates PASS; the manifest plus five Android resource XML files parse successfully. All six #71 product-test blobs, #73 recorder runtime/test, #74 renderer/test and #74 Desktop adapter test are retained through exact `main@4f56a69`. `JavaSoundWavPlayer.kt` retains #74's pure render/force-loop seam while adding exact active-voice ownership. #68 terminal-sample behavior and its exact parity test remain; `SamplerEngine.kt` adds reviewed per-voice ownership. #65 transport/runtime test remain exact while controller/test preserve step-zero and add failed-trigger plus stale-release ownership. #75's recorder runtime/test/session reducer blobs match exact main; merged ViewModel blob `3c289031d16ea823629dd3ee5ee4a2997386c4c4` combines #75 lifecycle behavior with PR #58 engine-owned tokens.
- Hosted feedback and repair: Android runs `32730801241` and `32732266959` exposed two earlier fixture-only timing/type gaps, repaired by `0659041` and `643546b`. Prior exact head `b79f49d` then passed all four workflows. Predecessor `17a959a` passed Windows `32741252127`, iOS `32741252051` and supply-chain `32741252093`; Android `32741252150` passed static policy, unit tests, lint, debug and release build/inspection, then failed instrumentation at `FirstScreenFlowDeviceTest.normalTextGatePointerUpCannotCutANewerControllerTrigger`. The helper unconditionally called `performScrollTo()` on the demo node although normal font has no scroll ancestor. Product fixture blob `e864d86c4492ecd88b5b0e049ab7fb6bae720c8c` scrolls only at font scale 1.2+ and preserves the large-text route. No predecessor receipt validates product `3b5dd59`; all four workflows and exact-head Codex review must rerun.
- #75 lifecycle preservation: exact-main blobs are `MicrophoneRecorder.kt@4fbbdab4f5e0b3aee01ed0edaffba5d623ef407f`, `RecorderWorkerStopTest.kt@4f29a59e3543997d200b563cb4b5fced5da8a942`, `RecordingSessionPolicyTest.kt@a91ccc9deb840aee739efd7ae932c5db15fd1cf3` and `RecordingSession.kt@3a91cbb5715e77641ac1b5c0fd9925d3b5891245`. Caller-thread admission returns after daemon startup; actual start advances only the exact STARTING session; pending STOP never calls framework `stop`, delegates `release`, checks the bounded wait and keeps replacement fail-closed until unwind. RESET and `onCleared` use `stopAsync`. Physical Android microphone timing, route loss and audio content remain unclaimed.
- Environment boundary: this Linux container has no Android SDK or cached Gradle distribution. Exact-head hosted `validate_project.sh` passed the 399-candidate public-surface and tracked executable-mode checks. The local stale checkout stopped before Kotlin execution because the default `/root/.gradle` lock parent is unavailable. An isolated `GRADLE_USER_HOME` reached wrapper acquisition but the Gradle 9.7.1 distribution network was unreachable. Fresh exact-product Kotlin/Gradle execution is not claimed. Hosted PR CI must pass before merge or gate promotion; the out-of-ancestry `43d8ace` report supplies no current proof.

## Reverse PAD resampling tail merged — 2026-08-24

- Product source: merged `main@029500ac63fe521814530acf4d70cab78365c9fd`; product `1b8b424036f76dabbb0671f4954ed079f32b9247` / tree `614c93f60c1e55e8df097302ef0e22250ddf7d37` remains the repair anchor. Renderer/test blobs are `ffd0be29db89d9ec75d553ab88c6d2d832e77e75` / `315ec9b078f9a8ed76f1783dce53877cbb430a53`; Desktop adapter/test blobs are `95a69a20e1e7c1667bcfa0f759d902eea73327fc` / `e4ccb83e52cdbde65f28d4cdf020cae7ffa72f2f`.
- Contract: reverse ONE_SHOT/GATE count/render follows realtime cursor order with count-before-allocation/reset. 48→60 kHz is 79 frames, 8→48 kHz / pitch −12 is 12 frames, and the two-frame pitch −5 fixture keeps ONE_SHOT at 2 while LOOP/forceLoop retains the merged three-element finite boundary. Forward playback and Desktop playhead mapping are unchanged.
- Gate: merged source only for this PR integration; fresh exact-head JVM/Windows execution and review remain required. Audible/physical Windows quality is unclaimed.

## Desktop recorder startup cancellation merged — 2026-08-24

- Product source: merged `main@a0b356c2e5820b7f9a8288ebcdd555c19e0cb6b5`; product repair `27283f8bc6ace63a27a9ea84e60db2abee5b4bd6` / tree `4ffc5f746f60cc67b71c29bb1f8a5b5db1a227ad` remains the runtime/test anchor.
- RED contract: while `TargetDataLine.start()` blocks, the previous recorder has not published `line`, `worker`, `outputFile` or `running=true`. Concurrent `stop()` therefore returns without affecting startup, after which start can launch a late capture worker.
- Repair contract: after `open()`, startup publishes a separate pending line under the recorder lifecycle lock. `stop()` latches cancellation and atomically claims/clears that reference, then closes it outside the lock to unblock `TargetDataLine.start()`. Startup cleanup closes only a pending line it still owns, preventing a duplicate close. Final active-resource publication and worker start remain in the same critical section, so whichever operation wins is observable to the other.
- Deterministic regression: a proxy line and two latches hold the starter thread inside `TargetDataLine.start()`, and proxy `close()` is the only normal gate release. After stop wins, that close must finish the starter with the exact cancellation error, zero payload reads, open/start/close counts of 1/1/1, `isRecording=false` and no output file.
- Local PASS: `./scripts/doctor.sh`; Python policy tests 39/39; public-surface current/history over 395 candidates; tracked executable policy; `git diff --check`. `./scripts/validate_project.sh` passed its public-surface/executable phases, then both it and focused `:shared:desktopTest :jvm-core:test :desktop:test` were blocked before Gradle execution because the uncached 9.7.1 distribution could not be downloaded in the network-restricted sandbox.
- Hosted PASS: exact head `a1cc5a7e832f2faf11b64c03ed1100453d1a9daa` received clean Codex review comment `5395404451`, had zero unresolved threads and passed Android `32728698800`, Windows `32728698763`, iOS `32728698759` and supply-chain `32728698801` before expected-head squash merge.
- Gate: merged source and hosted CI only. No physical capture timing, audio quality/content, device-removal, provider, public release or Human result is claimed.

## Shared Android/Desktop import-name latest-main integration — 2026-08-24

- Product source: reachable integration `e0d3fa1df5862bcfa038812bb12ecf6d2c45911e`, tree `1558c4a2331e8ef3a7b2809b38f264e671c188a6`, with parents prior exact PR head `d2c99fc2d4bb9c84bf6366a7f2568cca88294422` and merged `main@3072eedd84b357f4ccd22c611dcc7b7f22f92874`; PR #71 subsequently squash-merged as `main@dfcd9d8871f34ca5ed125c9a1113c6a4dd612887`, and current PR #58 product `3b5dd59b9271ab9a8a4abce9f03b80301b753861` retains its six shared/Android/Desktop product-test blobs exactly and integrates through `main@4f56a69`. The final follow-up is documentation-only.
- Main preservation: #65 `DesktopTransport.kt` / `DesktopTransportTest.kt` remain exact blobs `c92613c` / `073696e`; the controller and controller test retain #65 readiness/step-zero behavior while adding cross-input ownership. #68 terminal-sample behavior remains and its test stays exact `9d51556`; the runtime adds per-voice ownership. #70 Android provider/URI selection and its archive regression are preserved through the shared helper.
- Contract: Android and Desktop names published with decoded PCM are nonblank and at most `ProjectLimits.MAX_ASSET_NAME_CHARS` UTF-16 code units. Preferred candidates retain priority, a bounded whitespace-only prefix re-enters fallback selection, and a valid surrogate pair is never split at the boundary.
- Regression: shared common tests bind the portable rule. Desktop `readMono` receives a name whose first 240 units are whitespace, returns `sample`, and its `SamplerUiState` round trips through `ProjectArchiveCodec`; the existing Android surrogate/archive regression calls the same shared function.
- Prior exact-head provider evidence: `d2c99fc` passed clean Codex review, Android `32724477061`, Windows `32724477012`, iOS `32724476941` and supply-chain `32724476968`, with review threads 0. Main then advanced, so those receipts are not promoted to the integration head.
- Latest-main exact-head static PASS for the current follow-up: Python policy 39/39, public-surface 399 candidates, exact six #71 product-test blobs, retained #68 terminal behavior/exact test plus exact #65 transport/transport-test preservation, conflict-marker scan and `git diff --check`. Uncached Gradle 9.7.1 remains unreachable locally; no fresh Kotlin/Gradle PASS is claimed.
- Gate: source/static latest-main integration plus prior-head hosted evidence only. Fresh hosted Android/Windows/shared execution and clean exact-head review are required; filesystem/provider imports, recovery, playback, publication and Human evidence remain separate.

## Desktop transport step-zero ordering candidate — 2026-08-24

- Product source: reachable integration product `08fb123888fb840496d34e4ba7a586013e1305f6`, tree `dc9a9e8563e18168c5d20af9084ffaab01f0f742`, with parents prior exact PR head `5d630c8a769e4b840bba9914f59bc0ec1c705638` and merged `main@333088147cdc77932efc41b90a08eb37e1c1cf42`. PR #65 was subsequently squash-merged as `main@3072eedd84b357f4ccd22c611dcc7b7f22f92874`; current PR #58 product `3b5dd59b9271ab9a8a4abce9f03b80301b753861` retains `DesktopTransport.kt` / test plus merged #66/#67/#68/#71/#73/#74/#75 behavior. Its controller/test preserve readiness/step-zero while adding exact Java Sound voice ownership.
- Deterministic contract: `DesktopTransportTest.startBarrierPublishesStateBeforeStepZero` observes the readiness flag from the first worker callback and counts one step 0. `DesktopSamplerControllerTest.transportStartsWithEveryAudibleStepZeroHitExactlyOnce` reduces the pattern to one assigned drum at step 0, starts/stops transport, and requires one fake-engine hit plus stopped UI state. `failedTransportRestartAfterScratchRestoresRecordArm` injects a worker-start exception after the scratch-return readiness callback and requires stopped transport plus the original recording arm.
- Prior exact-head evidence: remote PR head `0a9def1816bffc903319b5358249f71b43f4c2cf` received a clean exact-head Codex re-review. Workflow runs `32720971504` (Android), `32720971498` (Windows), `32720971362` (iOS), and `32720971385` (supply chain) all completed successfully.
- Latest-main static gates: Python policy 39/39 PASS; public-surface 394 candidates PASS; six Android XML files parsed; wrapper SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` and wrapper text policy matched; all four Desktop product/test files equal the exact reviewed tree; `git diff --check` PASS. The local Gradle 9.7.1 distribution remains unavailable, so the integrated commit requires fresh hosted execution before merge.
- Gate: source/static latest-main integration plus revision-bound prior-head hosted evidence only. Physical Windows audio, real scheduling latency, device loss, packaging, provider, publication, and `HUMAN_GO` remain unclaimed.

## Android import-name persistence integrated candidate — 2026-08-24

- Product source: reachable integration commit `b7364eeab02cccdde260d65337cf9a403f9a6a5d`, tree `dae6252f28c70f56f817c1d5d4e18374de882ea4`, with parents prior reviewed head `caea87f823d96f091f8515dd0eb3e86f18d9d27e` and merged `main@2786c3722a9e56fa299d2a88f009d882545b0768`; the final follow-up is documentation-only.
- Main preservation: merged #68 `SamplerEngine.kt` / `SamplerEngineVoiceTest.kt` blobs remain exact `de686ba` / `9d51556`; reviewed import-name runtime/test blobs remain exact `2da4fa2` / `4ba02e5`.
- Contract: every display name published by Android decode is nonblank and no longer than `ProjectLimits.MAX_ASSET_NAME_CHARS` UTF-16 code units. Provider `DISPLAY_NAME` remains preferred; blank values and a whitespace-only bounded prefix fall back to the URI segment and then `sample`. Bounding does not split a valid surrogate pair.
- Regression: an overlong name whose first 240 units are whitespace falls back to `fallback.wav`; a supplementary character straddling code-unit 240 is removed intact, remains nonblank and round trips exactly through `ProjectArchiveCodec`.
- Prior-head provider evidence: exact `caea87f` received a clean Codex re-review; Android run `32721144140` and supply-chain run `32721144052` passed. The integrated head requires its own hosted read-back.
- Local evidence: Python policy 39/39, public-surface scan 394 candidates, exact four-blob preservation and `git diff --check` PASS. Uncached Gradle 9.7.1 is unavailable locally, so no Kotlin test pass is claimed for the integrated tree.
- Gate: source/static latest-main integration plus prior-head hosted evidence only; hosted Android CI must pass before merge. No device/provider import, autosave recovery, audible output, publication or Human result is claimed.

## Android realtime PAD terminal-sample retirement candidate — 2026-08-24

- Product source: reachable integration commit `5dd3d6613fcc99577996a28fabb06e7f7615b02f`, tree `c6a7e9b9cfdaa1f109da6fe0b568424c406e9170`, with parents prior reviewed PR head `72dbaaa1b79d1c0f92b4213c65f915908b3e894e` and merged `main@3de1cc5de2fc950ee7e24dfac29a2bc926cf1553`. The later evidence commit changes documentation only.
- Main preservation: `SamplerEngine.kt` and `SamplerEngineVoiceTest.kt` retain the exact original product blobs `de686ba` / `9d51556`; merged #66 timing and merged #67 Desktop import-boundary source/tests/docs are retained.
- Contract: for each active pooled PAD voice, the Android callback mixes the float returned by `Voice.render()` before deactivating a voice that became finished in that call. Existing loop-frame publication remains limited to a still-active monitored LOOP voice.
- Regression: `SamplerEngineVoiceTest.runtimeMixesFinalReturnedPadSampleBeforeRetirement` uses the established reverse/pitch/tone fixture and requires 403 frames, terminal limited PCM `-61`, then `active=false` and `finished=true`.
- Realtime review: `mixVoiceSampleAndRetire` uses only the existing `Voice`, primitive arguments and a primitive return. No collection, lambda, lock, I/O, log, UI call or native transition was added to the callback.
- Local PASS: `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` ran 39/39; `python3 scripts/check_public_surface.py` scanned 394 candidates; `git diff --check` passed. A separate deterministic numeric reproduction confirmed 403 frames and terminal PCM `-61`.
- Local prerequisite ceiling: `./scripts/validate_project.sh` passed its public-surface/executable checks, then could not create the Gradle distribution lock's parent directory under the default cache. With a writable task-local cache, `./gradlew :app:testDebugUnitTest --offline --no-daemon --max-workers=1 --no-watch-fs` could not fetch the uncached Gradle 9.7.1 distribution because the network is unreachable. No Kotlin/Gradle PASS is claimed locally.
- Gate: source/static candidate. Hosted Android CI is required before `LOCAL_PASS`; device listening, audio quality/latency, provider/public release and Human evidence remain separate.
## Desktop import sample-rate admission candidate — 2026-08-24

- Source: reachable product commit `3ad2bd9eda0561b0f1cf304b477ca726edd1becc`, tree `8272a51c4b537dd06ec02e0ff780e574babe4d46`, based directly on merged `main@3260f5cb560e2cbd2d245c7eee6f96ecb3540ddc`; squash-merged by PR #67 as `main@3de1cc5de2fc950ee7e24dfac29a2bc926cf1553`.
- Contract: Desktop WAV import accepts finite sample rates from 8,000 through `ProjectLimits.MAX_SAMPLE_RATE` (192,000 Hz), matching Android and the project archive. Unsupported rates fail before decoding, state replacement and autosave admission.
- Regression: exact 192 kHz validation succeeds. An `AudioInputStream` declaring 192,001 Hz over a fail-on-read payload throws `IllegalArgumentException`, and the payload read count remains zero.
- Local evidence: Python policy 39/39, public-surface scan 394 candidates, conflict-marker scan and `git diff --check` PASS. The uncached Gradle 9.7.1 distribution is unavailable in this container, so hosted `:desktop:test` was the executable gate before merge.
- Gate: merged source plus provider CI; no audible, physical Windows, archive recovery or Human result is claimed.

## Fractional pattern-frame timing repair merged via PR #66 — 2026-08-24

- Product source: reachable integration commit `0b75c71112cd004d9fa7ca34a6e916742c5d8825`, tree `18968b17b4c8a7d97e868dde4bc633e61e1da7c9`, joined the prior PR head with `main@6b645ca5005f905e93c572edfc1d375d4a6eeeb5`. Its four audio product/test files preserve the exact reviewed timing implementation; exact final PR #66 head `7e5130f77b9c1fdd481f8226cbb3c44b71eaa9c0` was squash-merged as `main@3260f5cb560e2cbd2d245c7eee6f96ecb3540ddc`.
- RED arithmetic: 48 kHz / 92 BPM / swing 54% gives exact step-1 deadline 8,452.1739 frames. Realtime's fractional countdown fires at 8,453; old offline truncation fired at 8,452. Old per-bar ceiling produced 500,872 frames for four bars versus `ceil(exact continuous duration)` = 500,870.
- Review RED arithmetic: absolute-deadline addition is not IEEE-equivalent to realtime's carried residual. At 48 kHz / 120 BPM / 55% swing, absolute accumulation schedules step 3 at 18,600 while realtime fires at 18,601. At 40 BPM / 56% swing, it reports a 288,000-frame bar while the next realtime step-0 boundary is 288,001.
- Contract/fix: shared `scheduledFrameAtOrAfter` applies ceiling to a non-negative finite countdown. `PatternRenderer` mirrors realtime's operation order by adding one step length to the carried residual, advancing by its ceiling, and subtracting that integer advance before the next step; bar boundaries do not reset the remainder.
- Regression sources: shared exact/fractional quantization test; end-to-end JVM-core WAV tests for 92 BPM / 54% event frames 8,453 / 133,670 / 258,887 / 384,105 and 500,870-frame length, 120 BPM / 55% step 3 at 18,601, and 40 BPM / 56% one-bar data/header length 288,001.
- Provider closeout: exact final PR head `7e5130f77b9c1fdd481f8226cbb3c44b71eaa9c0` received a clean Codex review. Workflow runs `32717689383` (Android), `32717689376` (Windows), `32717689369` (iOS), and `32717689375` (supply chain) all completed successfully before merge.
- Latest-main static gates: Python policy 39/39 PASS; public-surface 394 candidates PASS; six Android XML files parsed; wrapper SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` and wrapper text policy matched; residual-schedule arithmetic fixed step 3 at 18,601 and one bar at 288,001; all four audio product/test files equal the exact reviewed tree; `git diff --check` PASS. Gradle 9.7.1 is not cached, so the integrated commit requires fresh hosted execution before merge.
- Gate: merged source plus revision-bound hosted evidence. Physical audio timing/listening and Human acceptance remain separate.

## Constrained PAD gesture and semantics review repair — 2026-08-24

- Product/verification source: reachable commit `5b9592ff27608166a99fe77af0876ad1d6b917f5`, tree `97569b6a07fb74f9ee5b59101c0ea27059259a1b`; exact repaired PR #62 head `0e94698625f676573d42e74c52bf2394e1f24fd3` was reviewed and merged as `main@6b645ca5005f905e93c572edfc1d375d4a6eeeb5`.
- Review contract: large-text BEAT arbitrates PAD actions inside its vertical scroll body. A real pointer swipe from a PAD must move the body without selecting, triggering or releasing that PAD; a real ONE SHOT tap still dispatches `selectPlayablePad → triggerPad → releasePad`. A GATE hold activates only after the scroll-classification delay. From that point, pointer-up or gesture-node cancellation caused by opening TRIM releases the owned GATE exactly once. `pad.playMode` restarts pointer input after an in-place ONE SHOT/GATE change. Non-scroll performance remains press-down driven.
- Accessibility contract: assigned PAD semantics always expose play mode plus content kind. Focused assertions bind the compact visual indicators `LOOP`, `DRM` and `VOX`, while the large-text Compose fixture checks the actual DRM description and unchanged 48 dp PAD bounds.
- Static gates: `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` passed 39 tests; `python3 scripts/check_public_surface.py` passed 394 candidates; six Android XML files parsed and `git diff --check` passed.
- Provider correction and closeout: exact head `9a844c667e7372df970004aa1583ffbc3c6d6ceb`, Android run `32713574905` / job `97389990084`, exposed the missing `androidx.compose.ui.test.longClick` import. Exact repaired head `0e94698625f676573d42e74c52bf2394e1f24fd3` then received a clean Codex review and passed Android, Windows, iOS and supply-chain workflows before PR #62 was squash-merged as `main@6b645ca5005f905e93c572edfc1d375d4a6eeeb5`.
- Blocked executable gate: `./scripts/validate_project.sh` reached its Gradle phase but could not initialize the uncached Gradle 9.7.1 distribution in this environment. No APK, instrumentation, physical touch, TalkBack speech or audio evidence is claimed for this delta.
- Gate: merged source plus hosted Android/Windows/iOS/supply-chain CI. Physical device touch, TalkBack speech and listening remain unclaimed.

## Guided first-screen constrained-flow closeout — 2026-08-24

- Product source: `codex/choplab-screen-flow-closeout@07f8dcf3c2b0fe17c1e1d8ed3d135728c18f0c96`, tree `dcd5969bf72ceab1facbceb43c3fe63a9df99b4d`, base merged main `495ddc9` / tree `6a6ae6e`.
- Review repair: normal compact-landscape CAPTURE now scrolls; large-text BEAT quick/detail use explicit waveform/PAD heights and bounded body scroll. At 200%, compact bank/timeline labels stay complete, compact PADs retain one non-overlapping identity label while full role/state remains in semantics, and shortened visible EDIT keeps its full accessibility label.
- Deterministic instrumentation: `FirstScreenFlowDeviceTest` renders a pristine in-memory CAPTURE fixture, gives its proxy stable object methods, fixes starter-only launch semantics explicitly, measures the PAD button semantics rather than an inner text glyph and traverses demo → QUICK → STEPS without touching autosave or retained projects. These two tests validate shared deck composition/semantics only; they are not MainActivity, real controller or platform-adapter E2E.
- RED evidence: the first closeout device run failed both tests on proxy `equals`; the next exposed missing CAPTURE launch intent; the next correctly rejected inner-text width as a PAD target. All three test defects were repaired before acceptance.
- Local gates: clean 191-task gate plus final 184-task cross-platform gate PASS. Shared host 25/25, Android 234, JVM-core 52, Desktop 77; failures/errors/skips 0. Lint debug/release errors 0, warnings 7. Project validation, 23 Python policy tests, 389-candidate public-surface scan and release identity `0.17.0 (27)` PASS.
- Final artifacts: debug APK 32,452,040 / `F766D047F74BED45B5E44515230F2104403BCBFF1CA2936CBBBD23B354739EA3`; androidTest APK 10,960,156 / `BDD527A21A0D5F9B1A80D9D6330D7C750C03BA722DDB2AA582F7F9CC7324BD67`; unsigned release APK 24,061,044 / `7CDB6C80ED5B6FD62FA60FD8147841C5AA09FB2BA3DCFC8DC4842FB1107392D0`; Windows app-image 405 files / 176,497,058 / digest `0954c0cec3daf8df91c489cc542c2fc8f6e5ebaf306a601e3ab5d14561cfd6d4`.
- API 36 emulator: exact final APKs installed data-preservingly; full suite `OK (8 tests)`, fatal/ANR 0. Separately, the production MainActivity and real Android controller were cold-launched and manually navigated without recording: accepted closeout captures cover normal compact-landscape CAPTURE start/end, 200% BEAT initial/physical-scroll and 200% detailed STEPS. This manual slice proves rendered wiring/navigation, not audio behavior or a general automated E2E. Settings were restored; no uninstall, clear-data, recording or audio capture occurred.
- GitHub/product lineage: PR #52 merged to `main@495ddc9`; all four merged-main workflows passed. The closeout source is a new bounded follow-up and needs its own PR/main read-back before provider promotion.
- Gate: `LOCAL_PASS` plus scoped API 36 emulator runtime. Physical Pixel `DEVICE_PASS`, listening, recording, route loss, complete TalkBack speech, provider closeout artifact, binary Release and `HUMAN_GO` remain unclaimed.

## Sample-rate-bounded streaming decode merged-main receipt — 2026-08-24

- Observed at: `2026-08-24T20:39+09:00` through the GitHub PR, review and workflow read-back surfaces.
- Lineage: PR #61 exact head `ff8c0eda19dbef444f4ef4cd1f21587b4a4680f0`, base `main@a930da4cdaf1f5035b3ea21196f802801fa4c46f`, was squash-merged as `main@ae77cd92d3ee14baecc01f4862c639328bae43bb` at `2026-08-24T18:55:48+09:00`. Reachable pre-merge product `8279ea4f7e04cfec2c41440e65f4a40bc4d68451`, tree `f6a5bc3844317169edf1100e79da1ea08b46c524`, remains the immutable product anchor.
- Contract: imported mono PCM is bounded by `min(30,000,000, sampleRate × 600)` frames. Android reapplies the limit when the effective output rate becomes authoritative; Desktop applies it before known-length allocation, during unknown-length streaming and after decode.
- Negative boundaries: shared tests accept 8 kHz / 4,800,000 and 48 kHz / 28,800,000 frames and reject 4,800,001 / 28,800,001. Desktop fixtures reject a known oversized stream before payload read and an unknown-length stream at the builder boundary; the arithmetic fixtures do not allocate multi-million-frame buffers.
- Provider receipt: exact head `ff8c0eda19` received clean Codex review comment `5393291604`. Workflow runs `32711516254` (Android), `32711516241` (Windows), `32711516209` (iOS), and `32711516257` (supply chain) all completed successfully on that exact head before merge.
- Gate boundary: this receipt proves hosted compilation/tests and supply-chain policy only. Device import, codec/provider variance, physical memory pressure, audio quality, public binary release and Human acceptance remain unclaimed.

## Release checksum sidecar hardening candidate — 2026-08-24

- The release manifest writer now fails before attestation/publication unless the Android APK, iOS Simulator archive, Windows app-image archive, and CycloneDX SBOM each have a checksum sidecar that names the exact target and matches its bytes.
- Every discovered `.sha256` sidecar is validated, so malformed, mismatched, cross-named, and orphan sidecars cannot be published alongside the generated `SHA256SUMS`.
- Focused unit coverage includes the accepted four-asset set plus missing, digest-mismatch, filename-mismatch, and orphan-sidecar failures. Hosted PR CI remains the integration proof; no tag or Release is created by this candidate.
## Windows active-loop history transaction — 2026-08-27

- Product checkpoint: `70b31e949a9faa42d0f459f2d03218d16e6e30b7`, tree `ce9469cdf37b6620c8b5c428fa479e9361194d1b`, base `924fb3c05697fb1ff801d3dba7fb1a77ee8c29dc`.
- RED/GREEN history boundary: shared common tests first failed compilation on missing `planUndo`, `planRedo` and `restoredState`. GREEN previews without consuming, cancels without revision/frontier mutation, commits exactly once, previews Redo, rejects resolved/stale/cross-session plans and preserves the synchronous Android-facing API.
- Windows binding: a same assigned owner with no source/transport/scratch conflict either keeps an identical PAD without retrigger or starts the restored PAD candidate before history commit. Successful Undo→Redo preserves owner and avoids `stopPad`/`stopAll`; a removed owner uses the existing disruptive stop path.
- Failure controls: recoverable Undo and Redo startup failure cancel the plan and keep project, revision, frontier and current loop. Fatal `AssertionError` propagates without status rewrite or history consumption. Replacement success resets playhead to the restored forward start/reverse end; no-change keeps the runtime playhead.
- Review: source/master-pitch changes do not enter the continuity path, public plan surface exposes only the target needed by the platform adapter, history mutation remains behind owner/epoch/exact-once checks, and no realtime callback or persistence/schema code changed. Final Standards/Spec unresolved findings: `0/0`.
- Full clean gate: `BUILD SUCCESSFUL in 3m 55s`; 197 tasks (195 executed / 2 up-to-date). Android app 265 / 46 suites, shared Android/Desktop 69/69 / 13+13 suites, JVM-core 81 / 9 suites, Desktop 109 / 22 suites; total 593 tests / 103 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Configured/policy gates: explicit Git Bash `validate_project.sh` PASS (product-checkpoint public surface 444, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Closeout Python policy 59 PASS. Current plus reachable-history public surface 445 has no credential/signing/audio candidates. `git diff --check` PASS.
- Final unsigned Android candidate: 24,224,884 bytes / `B13EE2794AD8048A918DF6D7A7EC6CBB569E2151E0B373D2E520562CD73A81F8`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,721,586 / `8CD10DF4060A13F8EF7FE444A711FC040C02D6F53DC14431DC2AD40A28265AD6`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`; Desktop JAR 352,656 / `AE31D186132A4039DF0AB8831EE959D07AF681EB0D90FE16BD820D96D38A8F15`.
- Windows/SBOM: app-image 405 files / 176,664,783 bytes. The reproducible sorted path/size/file-hash manifest SHA-256 is `4EE29D2883A034B880ED88E5B63F2857448B40A8F568F8CF7CAF39E8DC0C62F1`. CycloneDX 1.6 JSON verifies `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies. JSON is 1,581,101 bytes / `A86D110C9B4DC5EE8FF624394D679FBA4CEF1B6A4A2F4DC55D1147B7D645B836`; XML is 1,431,320 bytes / `80F089913D2A51FF326468239861D824E860741E2F4E3998CCBEB5719F2366ED`. The verifier is JSON-only, so XML is not represented as a parser PASS.
- Gate: deterministic host contracts establish `LOCAL_PASS`, not physical click-free output or endpoint behavior. Actual Java Sound continuity, short overlap/click/latency, route removal, Bluetooth, sleep/resume, device/provider/public/signing and Human outcomes remain unclaimed.

## Verified Android document publication — 2026-08-27

- Product checkpoint: `60068369e9c2820509a839c259693ae0ce2bac2d`, tree `e7b7643ddade8d54aed421c4c01597d2d1722cf8`, base `9043af2f582b4f8965ce973ab007d79de4d9324b`.
- RED/GREEN streaming boundary: after adapting the new test to the module's JUnit4 harness, compilation failed only because `publishVerifiedDocument` did not exist. GREEN streams the validated source into a fake destination, closes output, reopens it and requires exact count plus SHA-256. It covers exact 150k and empty copies, silent truncation, same-size corruption, extra bytes, output/read-back null, missing source, write/read/close failures and a zero-progress bulk read.
- Android binding: WAV export and portable-project save use unique cache temporaries and the same verified publisher. Project codec read-back plus internal autosave stay before provider publication. A failed or unverifiable destination never emits success; shared Japanese copy distinguishes the potentially incomplete selected document from the retained in-app production/safety copy.
- Control review: missing source cannot open destination output; failed write/close cannot start read-back; output closes before read-back opens; cancellation is rethrown exactly; fatal `Error` is not caught; all owned temporaries are cleaned in `finally`. The unused public publication fingerprint was removed. Final Standards/Spec unresolved findings: `0/0`.
- Full clean gate: `BUILD SUCCESSFUL in 3m 50s`; 197 tasks (196 executed / 1 up-to-date). Android app 265 / 46 suites, shared Android/Desktop 66/66 / 13+13 suites, JVM-core 81 / 9 suites, Desktop 104 / 22 suites; total 582 tests / 103 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Configured/policy gates: explicit Git Bash `validate_project.sh` PASS (product-checkpoint public surface 443, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Closeout Python policy 59 PASS. Current plus reachable-history public surface 443 has no credential/signing/audio candidates. `git diff --check` PASS.
- Final unsigned Android candidate: 24,224,884 bytes / `D5CE2B5EE7051B880B55553B690196BB339D253C16BAC9AEB04D094DF588E692`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,721,586 / `51974214AEFF95BC7DE3D666308E53A9D3E52975074537D4EB2AD5680F5202F5`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`; Desktop JAR 349,674 / `C4B2F22F74F0FC4C1AE4126B80FAA92728898E9897213A71274008266EC32DC9`.
- Windows/SBOM: app-image 405 files / 176,657,157 bytes. The reproducible sorted path/size/file-hash manifest SHA-256 is `A941FD50C0BEDADA4A419F22F15AF0331D6EFDFD011B072DB6233CBE6B1A0AFC`. CycloneDX 1.6 JSON verifies `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies. JSON is 1,581,101 bytes / `AD8E0022CF0376B3DA972B821804122CE7C3665D064A04214F46DCB6416FA5FE`; XML build output is 1,431,320 bytes / `4BAD93472E047B342E4468A094EB2F7D3A64D2189C0B12038DDC431A9A113964`. `verify_sbom.py` is JSON-only, so XML was not represented as a parser PASS.
- Gate: exact-byte fake-provider contracts establish `LOCAL_PASS`, not provider atomicity or real URI behavior. Google Files/Drive/Dropbox reopen timing, partial existing-document state, network/offline behavior, process death, device/provider/public/signing and Human outcomes remain unclaimed.

## Overwrite-safe Windows WAV export — 2026-08-27

- Product checkpoint: `ffe1bbdf59d0645652c3f8556fb6f601e5218670`, tree `d3ae7a9bb18d48ae323e92aaabfe58e25acd975f`, base `6fb1c94f283019580d0cbf0fecfadeefa324675a`.
- RED/GREEN sibling boundary: an existing `beat.wav` sentinel plus a partial temporary writer first failed to compile because `replaceWithAtomicSibling` did not exist. GREEN proves byte-identical old-target preservation and zero temporary debris on failure, no target on failed first write, rejection when the writer removes its temporary, and completed replacement plus writer-result retention on success.
- RED/GREEN export adapter: the first test failed to compile without `DesktopBeatFiles` and its renderer seam. GREEN proves the renderer receives a sibling temporary rather than the user target, failure preserves the previous WAV, valid `WavFileWriter` output is published, and the production renderer produces RIFF/WAVE. A renderer returning success with an invalid header/length is rejected before replacement.
- Persistence/control review: manual `.choplab` save shares the helper and preserves a readable prior archive with no temporary debris on failed replacement. Existing target replacement fails closed when atomic move is unsupported; absent-target fallback does not replace a concurrent target. File sync precedes publication and directory sync is best effort.
- Error review: controller `runCatching` would have converted fatal `Error` into recoverable status, so it now catches only `Exception`. A post-move cleanup check could falsely report failure after publication, so explicit temporary ownership limits cleanup to pre-publication paths. Final Standards/Spec unresolved findings: `0/0`.
- Full clean gate: `BUILD SUCCESSFUL in 5m 18s`; 197 tasks (193 executed / 4 up-to-date). Android app 264 / 46 suites, shared Android/Desktop 66/66 / 13+13 suites, JVM-core 68 / 8 suites, Desktop 104 / 22 suites; total 568 tests / 102 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Configured/policy gates: explicit Git Bash `validate_project.sh` PASS (product-checkpoint public surface 440, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Closeout Python policy 59 PASS. Current plus reachable-history public surface 440 has no credential/signing/audio candidates. `git diff --check` PASS. The Windows PATH `bash.exe` was WSL routing without `/bin/bash`; this launcher mismatch was bypassed by the explicit installed Git Bash path and is not a project failure.
- Final unsigned Android candidate: 24,208,500 bytes / `E9DEB956D6F47FB24B89A26A8B6E70C5B941D7C93FDB9A3DAEB91FB78C2464BA`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,705,202 / `1F71851E832DAF3D5FE75A13E3E3900B17E71BCA2A8E7654998C1512BCFC1098`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`; Desktop JAR 349,674 / `C4B2F22F74F0FC4C1AE4126B80FAA92728898E9897213A71274008266EC32DC9`.
- Windows/SBOM: app-image 405 files / 176,651,039 bytes. The reproducible manifest is each slash-normalized relative path, decimal size and lowercase SHA-256 joined with `|`, sorted by path and LF-terminated; its SHA-256 is `53AB068CDDE342ECDB74E5C0C3D5A395AC9EAC9A8A8D3F86D3131EAC99D176E1`. CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies. JSON is 1,581,101 bytes / `98C5846FC6B5DC4F1A6AA4F1EF9B550AA37086380F90CFC2692DB9D22DB9D767`; XML is 1,431,320 bytes / `5D23934C738BF0920E84D3357A4CA3C7462A6EB6C9639547C3591D5C37D37483`.
- Review/gate: local parent Standards/Spec unresolved findings `0/0`. Result `LOCAL_PASS`; Android SAF provider-copy atomicity, filesystem/antivirus/network-share behavior, actual listening, device/provider/public/signing and Human outcomes remain unclaimed.

## Windows active-loop edit transaction — 2026-08-27

- Product checkpoint: `b8db4368511d8dd3578634bdd071be0da0f38b8a`, tree `a9b28c0d486cde7a7625258e10d97619d669fccf`, base `7e8603c0e9a64ff6cb4b74d4d244985494a7256c`.
- Controller RED/GREEN: `failedActiveLoopEditKeepsTheOldPadLoopAndHistoryFrontier` and `rejectedRecordingTimePadEditDoesNotRetriggerTheLoop` failed 2/2 on the old commit-first path. GREEN admits loading/recording before audio work, builds a pure candidate, skips no-op, starts an active-loop replacement before committing exactly one edit, and keeps PAD/history/loop truth unchanged on recoverable startup failure.
- Adapter RED/GREEN: missing `startReplacementBeforeRetiringConflicts` and `prepareCandidateOrAbandon` produced compile REDs. GREEN proves preparation failure closes the candidate, startup failure abandons only the candidate, and success orders candidate start before same-PAD/CHOKE conflict retirement. The actual Java Sound adapter uses both helpers.
- Review controls: successful replacement retriggers once without an explicit pre-stop, same-value edit does not retrigger, and one Undo restores the prior edit. A review RED showed `runCatching` converting fatal `AssertionError` into recoverable UI status; the controller now catches only `Exception`, rethrows fatal errors and leaves state uncommitted. Clip-open cleanup was also added. Final Standards/Spec unresolved findings: `0/0`.
- Full clean gate: `BUILD SUCCESSFUL in 5m 04s`; 197 tasks (193 executed / 4 up-to-date). Android app 264 / 46 suites, shared Android/Desktop 66/66 / 13+13 suites, JVM-core 68 / 8 suites, Desktop 96 / 20 suites; total 560 tests / 100 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Configured/policy gates: `validate_project.sh` PASS (product-checkpoint public surface 435, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Closeout Python policy 59 PASS. Current plus reachable-history public surface 435 has no credential/signing/audio candidates. `git diff --check` PASS.
- Final unsigned Android candidate: 24,208,500 bytes / `E9DEB956D6F47FB24B89A26A8B6E70C5B941D7C93FDB9A3DAEB91FB78C2464BA`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,705,202 / `1F71851E832DAF3D5FE75A13E3E3900B17E71BCA2A8E7654998C1512BCFC1098`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`; Desktop JAR 341,486 / `8D26F12F3C67862AED4D6D1482341DCA3C109603BF84B2A3CFD01CBA11E74BFD`.
- Windows/SBOM: app-image 405 files / 176,642,851 bytes. The reproducible manifest is each slash-normalized relative path, decimal size and lowercase SHA-256 joined with `|`, sorted by path and LF-terminated; its SHA-256 is `EE98847088B77942ECD98F0BF1DD27F336A8D0421A0E22C0AD1D8B09BCC3A479`. CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies. JSON is 1,581,101 bytes / `C0A45D14115A66C68D952AADE426664FEBC2F5543DFE4E49969009E7DC54007A`; XML is 1,431,320 bytes / `E6C526EC4257677C688AB3DED85BFE2EC62D6D10B8CCB12D7DBF02FC74155E13`.
- Review/gate: local parent Standards/Spec unresolved findings `0/0`. Result `LOCAL_PASS`; actual Windows endpoint/driver, audible continuity, click/pop, temporary overlap, latency, route loss, Narrator, device/provider/public/signing and Human outcomes remain unclaimed.

## Focused sixteen-step editor — 2026-08-27

- Product checkpoint: `96118949fc8d8ec766715a6eb110d76523ac1095`, tree `d4e9b929f2e250e784bd2eeeb4dc309044bdb00c`, base `600211fa20b0cb3f4e1fdcaf0878d0aecb7166a3`.
- RED/GREEN geometry: missing focused-layout/workspace-mode APIs failed shared test compilation. GREEN covers 360×640, 412×820, 640×360 and 1280×720 at font scale 1.0/1.3/2.0; portrait 4×4, landscape 8×2, exact row-major 1–16, minimum 48dp, and invalid/undersized fail-close. Review added 632×328 font 2.0; it failed at 47.5dp before a compact-landscape-only 2dp gap raised the minimum above 48dp.
- RED/GREEN interaction: missing cell-presentation API failed compilation. GREEN binds all sixteen selected-PAD step keys and descriptions, preserves active/playhead/disabled truth, rejects invalid columns, and round-trips QUICK → FOCUSED_STEPS → FINE_CONTROLS → FOCUSED_STEPS → QUICK. Compose keeps the original controller callback and production state.
- Full clean gate: `BUILD SUCCESSFUL in 4m 13s`; 197 tasks (196 executed / 1 up-to-date). Android app 264 / 46 suites, shared Android/Desktop 66/66 / 13+13 suites, JVM-core 68 / 8 suites, Desktop 89 / 19 suites; total 553 tests / 99 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Configured/policy gates: `validate_project.sh` PASS (public surface, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Python policy 59 PASS. Current plus reachable-history public surface 433 has no credential/signing/audio candidates. `git diff --check` PASS.
- Final unsigned Android candidate: 24,208,500 bytes / `E9DEB956D6F47FB24B89A26A8B6E70C5B941D7C93FDB9A3DAEB91FB78C2464BA`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,705,202 / `1F71851E832DAF3D5FE75A13E3E3900B17E71BCA2A8E7654998C1512BCFC1098`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`; Desktop JAR 336,656 / `ACDE462391B3D2BD006AAA68E72E96E8541CE1B3162948671913A8C361214D15`.
- Windows/SBOM: app-image 405 files / 176,638,021 bytes. The reproducible manifest is each slash-normalized relative path, decimal size and lowercase SHA-256 joined with `|`, sorted by path and LF-terminated; its SHA-256 is `E810F81D08ED4A3C6FBF33F987F9B1C0694EFA844447AF6208693301DD1EA050`. CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies, 1,581,101 bytes / `FF210B222F9CF2C12E6975A6AAA7DBCA5BAD663F1516A3598019BF7143953598`.
- Review/gate: local parent Standards/Spec unresolved findings `0/0`. Result `LOCAL_PASS`; physical touch, TalkBack/Narrator speech/order, one-hand comfort, device/provider/public/signing and Human outcomes remain unclaimed.
## Stereo channel identity tracer — 2026-08-27

- Product checkpoint: `66d3911f57dfb56baed682cf8c0ec9a0aed85164`, tree `e60216ab70ef540f48815524e0b645de16817007`, base `d6c22434f1bfd9fa5bc505717d0be4fa4a552a3d`.
- RED/GREEN domain/import: missing `channelCount`/frame APIs failed test compilation before `PcmAudio` became strict interleaved 1/2ch PCM. Android PCM float/8/16/24/32 and Windows PCM-16 preserve asymmetric stereo, keep mono, average 3–8ch to mono, enforce frame-based capacity/duration, reject partial frames and remove DC per channel.
- RED/GREEN persistence: schema-6 mono WAV interpretation rejected asymmetric interleaved bytes. Schema 7 now round-trips exact L/R samples and checks manifest channel identity against strict RIFF/WAV shape. Schema 1–6 mono fixtures, channel mismatch, duplicate-ID shape mismatch and resident-budget negatives pass.
- RED/GREEN playback/export: host PAD and Pattern summary initially had only mono shapes. Android source/PAD/scratch, Windows Clip/scratch, host PAD and Pattern/Song renderer now share frame coordinates and channel-aware output. Asymmetric full-bar Android realtime versus offline export stays within one PCM unit per channel. Mono-only control reads back WAV channel 1, block-align 2 and mono data length.
- RED/GREEN analysis: the old zero-crossing path reported stereo frame 100 as interleaved sample offset 200. Waveform/trim/PAD/timeline/zero-crossing/transient now use per-channel frame count with explicit L/R average for analysis.
- Adversarial closeout: review found a zero-size-EOS edge where a second codec format-change could change sample rate after PCM started without another data buffer. A pure negative control now rejects post-start sample-rate or stored channel-shape drift immediately; focused tests pass.
- Full clean gate: `BUILD SUCCESSFUL in 4m 31s`; 197 tasks (192 executed / 5 up-to-date). Android 264 / 46 suites, shared Android/Desktop 58/58 / 11+11 suites, JVM-core 68 / 8 suites, Desktop 89 / 19 suites; total 537 tests / 95 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Realtime bytecode: release `SamplerEngine.renderLoop` allocates one float array and three `MutableStereoFrame` objects before the outer loop. Its steady sample loop has zero `new`, the method has zero monitor instructions and zero `java.io`/`java.nio` references; the only later `new` is the write-failure exception path.
- Configured/policy gates: `validate_project.sh` PASS (public surface, executable modes, 18 Gradle tasks, six XML files, wrapper SHA/UTF-8). Python policy 59 PASS. Current plus reachable-history public surface 429 has no credential/signing/audio candidates. `git diff --check` PASS.
- Final unsigned Android candidate: 24,192,116 bytes / `7F180B3A48452179B3277D8FA3633820E6C093B8A759CD43E3B4464D6259016A`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy and alignment PASS. Unsigned candidate acceptance exits 0; `--require-signed` rejects with exit 1.
- Other artifacts: debug APK 31,672,434 / `F1C13E9A163BAB2652D1EE3703C359A564F2EFEC1DCB0FCBB5379AD87911AE67`; androidTest 10,878,659 / `19002E0EBB2B62B599FAE7CF1738AC275B7D4CBEC610F0348D3B9C871A90E4AC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`. App-image 405 files / 176,614,704 bytes / manifest `9F97268C312E570D5B051C5B5BB06F7A142EE42EE610BE2ADB3FE202A186CB2A`.
- Supply chain: CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies, 1,581,101 bytes / `EA59D6FD8BA9B87C206F35396A2B21B98673DDE637CFC55B8FACB8AD984BCD39`.
- Review/gate: local Standards/Spec unresolved findings 0/0. Result `LOCAL_PASS`; physical L/R output, audio quality, route/focus/Bluetooth/USB, latency/xRun, accessibility speech, device/provider/public/signing and Human outcomes remain unclaimed.

## Build-tools-only Android release verification — 2026-08-26

- Product checkpoint: `e5229075150bcfb219eb05c666f46fd9eba05ad8`, tree `514e5161dd73ba49436756afa53782eeb16c47d5`, base `a484a96dedb1c1b6c9025d332d22de602017ae64`.
- RED: exact Wave 8 unsigned APK stopped with `Cannot find Android SDK tool: apkanalyzer` / exit 1 while SDK build-tools `36.0.0` already contained `aapt2`, `zipalign` and `apksigner`. The new test seam then failed import before implementation.
- GREEN: `apkanalyzer` remains primary; only absence selects a strict `aapt2 dump xmltree` normalizer. A present-but-failing primary does not fallback. Both backends enter the existing one manifest policy; nonliteral `debuggable` / `exported` values now fail closed.
- Negative controls: malformed/unknown lines, orphan and duplicate attributes, multiple roots, missing package/application, version mismatch, unexpected permission/declaration/exported service, debuggable application, unprotected profile receiver, ambiguous security Boolean, both-tools-missing and unsigned-when-required all reject.
- Python release/public policy: 59 tests PASS. Configured project validation: public-surface 425, executable modes, 18 Gradle JVM-core/Desktop tasks, six Android XML files, wrapper SHA/UTF-8 PASS. Reachable-history public-surface 425 and `git diff --check` PASS.
- Exact APK read-back: 24,175,732 bytes / `09B43846CBEF6356089BA3B063E22C500B0F6484A2E2E5E42B313905FF6A8944`; package `com.choplab.sampler`, version `0.17.0` / code `27`, `manifest_tool=aapt2`, manifest policy, 16-KiB-aware alignment and intentionally unsigned candidate PASS. `--require-signed` exits 1 as expected.
- Unchanged product inputs: app/shared/jvm-core/desktop and root build tree objects match base, so Wave 8's 190-task / 511-test product gate and exact package bytes were not rerun. Existing CycloneDX 1.6 SBOM read-back passes at 650 components / 651 dependencies / 1,581,101 bytes / `23509E6C543E2C7B6E6F6FC49A6DDC7E5C463BCE4D72516AD8152697951B4FC8`.
- Gate: `LOCAL_PASS`. No signer identity, secret, key, workflow, GitHub/Release, device, provider, recording or Human action occurred.

## Android live terminal-sample parity — 2026-08-26

- Product checkpoint: `2948c6a59ab18ba18a0813e9033098b4e31e41a6`, tree `52059c3dce144ef3ad3a3f81b863709edbe8c9c6`, base `6a0649d80e3e1c62bb10742b0ec01765f0a2c45b`.
- RED: a behavior-preserving extraction of the actual PAD render/retire call site failed twice at the exact same values. Natural finish frame `402` expected `-0.0012556206` but mixed `0.0`; 48-frame release frame `47` expected `0.0040690107` but mixed `0.0`.
- GREEN: the call site captures the one render return, deactivates immediately if it also reports finished, then mixes the captured return once. Natural and release sequences match direct `Voice` output exactly; inactive follow-up returns zero. Existing `PatternMasterParityTest` and `PatternRendererTest` remain green.
- Realtime inspection: `renderPadVoiceFrameForMix` is inline. Release `SamplerEngine.renderLoop` bytecode has no helper invocation and the relevant block retains one `Voice.render`, followed by finished/deactivate and the captured float mix. No new allocation, blocking, I/O, logging or UI reference was introduced.
- Full gate: 190 tasks, exit `0`. Android 252 / 45 suites, shared Android/Desktop 40/40 / 8+8 suites, JVM-core 55 / 8 suites, Desktop 84 / 18 suites; 471 tests / 87 suites, failures/errors/skips 0. Lint debug/release each: fatal 0, errors 0, warnings 7.
- Artifacts: debug APK 31,590,514 / `5B2D88932F3F8AA1B79E4123585464EB35221AB99C130B2AB122D6565F4C978C`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,126,580 / `0AFAEEE1887DE5AD872D1F190D328E6710807A0B1F78E01F3B5097C1105D86DC`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`.
- Other gates: configured validation 18 tasks, Python policy 40, public-surface 418, `git diff --check` PASS. CycloneDX 1.6 / product `0.17.0` / 650 components / 651 dependencies / 1,581,101 bytes / `50B03C65FEC1B35E159FB539A446ED9957813D745F5A3E240E658823F59B35F2` parsed successfully. Every listed artifact is newer than the product commit.
- Review: parent PAD `work/PAD_CHOPLAB_GOAL_WAVE7_REVIEW_20260826.md`; Standards/Spec unresolved 0/0.
- Gate: `LOCAL_PASS`; actual output capture, physical click/pop/tails, latency, sustained polyphony, route/device, provider/public/signing and Human outcomes remain unclaimed.

## CHOKE live/export loop-session parity — 2026-08-26

- Product checkpoint: `b445c18a6bb50abfd878f95a2d2e6c3397cb3222`, tree `a650dae4c5bc7a18d321c303fdcad8c268f2888e`, base `611a58932fff6faf4ae6178acdc1f7c575cb0a7b`.
- RED: same-group full-bar offline export diverged from Android realtime `Voice` + shared limiter by maximum `9,262` PCM units at frame `49` (`offline=7,121`, `realtime=-2,141`).
- GREEN: offline frame-zero selection reuses `vocalCompanionPadIndicesForLoopStart` only for exactly one assigned loop owner. Same-group owner-only and other-group intentional-layer full bars match the realtime/master oracle within `<=1` at every frame.
- Negative controls: no-loop and multiple-assigned-loop inputs keep all historical non-loop vocals. Existing step timing, retrigger, polyphony, loop, vocal, final-sample, resource and non-finite paths remain green.
- Full gate: the unchanged product commit completed 190 tasks with exit `0`. Android 250, shared Android/Desktop 40/40, JVM-core 55, Desktop 84; 469 tests / 87 suites, failures/errors/skips 0. Lint errors 0/warnings 7.
- Artifacts: debug APK 31,590,514 / `9DAF4879CA8C2A3A0AE3A7AA448E1DE1E29C63383FEEC1715ED7E90E9E0B1789`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,126,580 / `F33392832B1A203FAF45D35BEF1E853868CB76EABB190525EEF7299FE09E266C`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Other gates at the product checkpoint: configured validation, Python policy 40, public-surface 417, Windows ProductVersion `0.17.0`, CycloneDX 1.6 / 650 components / 651 dependencies / `68E245F9C1D7B6003201BC4FAC28987EDD00F787EFD04FEBC4D7165624ED11EB`, and `git diff --check` PASS. The closeout-only tree passes Python policy 40, public-surface 418 and `git diff --check`; current byte read-back found no matching long-running process or crash log.
- Review: parent PAD `work/PAD_CHOPLAB_GOAL_WAVE6_REVIEW_20260826.md`; Standards/Spec unresolved 0/0.
- Gate: `LOCAL_PASS`; physical audio/fade/click/latency, route/device, provider/public and Human outcomes remain unclaimed.

## CHOKE loop-session ownership — 2026-08-26

- Product checkpoint: `1853659ef56d40117e9f61d1c7f01a752ed02f33`, tree `e07a07c46500f14aaa09619f4463682a07890eef`, base `639d5132c12bd3efe0d0346731cef9fbdaca15ec`.
- RED/GREEN: Desktop current code first left loop state after a matching CHOKE trigger. GREEN shared tests cover complete owner/companion stop plans, group-zero/different-group/same-owner/invalid controls and owner-wins-same-group-vocal selection. Desktop covers exact stops, requested trigger, ordinary polyphony and fail-closed stop failure.
- Android/Windows binding: controllers issue the shared stop plan before trigger and clear loop/playhead runtime truth; no history/autosave mutation. Android commands retain serial engine ordering; Desktop rejects the trigger if a stop throws.
- Full gate: 190 tasks PASS. Android 248, shared Android/Desktop 40/40, JVM-core 54, Desktop 84; 466 total, failures/errors/skips 0. Lint errors 0/warnings 7.
- Artifacts: debug APK 32,560,016 / `D36C4C21C02CFA384D76BE17F683DADFBE647F7951BF061BC0BF03766F77032A`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,126,580 / `E3EA26BDFEA2C4C4E0EF0CA6209095D88DCE8A7485B831653BD78C4D6C6AADE1`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Other gates: configured project validation, Python policy 40, product public-surface 416 / documentation-inclusive 417, CycloneDX and `git diff --check` PASS. Local parent Standards/Spec findings 0/0.
- Gate: `LOCAL_PASS`; physical audio/fade/click quality, device/provider/public and Human outcomes remain unclaimed.

## Wide first-entry integrated goal head — 2026-08-26

- Integrated product checkpoint: `b6eed97215bac6c27d3bef66b0f1c8c0e2e0b569`, tree `8f65157dae53050f48dac1c733bbdeb21689c523`; includes wide product `8b3751e`, shared Capture vocabulary `7d45164` and both closeout histories.
- RED/GREEN: wide-layout policy first failed compilation; follow-up shared-vocabulary test also failed compilation before `CaptureEntryActionPresentation`. Both pass on Desktop and Android host, and compact/portrait/large-text remain exact negative controls.
- Same-state visual: 1200×900 pristine Windows baseline/after under isolated app data. The split uses the available surface, retains all actions/NEXT state, has no clipping, and exact processes were closed.
- Full final gate: 190 tasks PASS. Android 248, shared Android/Desktop 37/37, JVM-core 54, Desktop 80; 456 total, failures/errors/skips 0. Lint errors 0/warnings 7; public-surface 415 PASS.
- Artifacts: debug APK 32,560,016 / `317ECA6F5E4ADAB34F20F60DB799FC3AE8F5BEE4B639C26C963258408EBA0B7E`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,126,580 / `E28538D965C3210E263B9D04E5FF8554D6ADE72A69D6C21BA17A791944A80FBE`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Local parent Standards/Spec findings 0/0. Evidence and portfolio receipts are in parent PAD `work/CHOPLAB_WIDE_CAPTURE_EVIDENCE_20260826/` and `work/PAD_CHOPLAB_GOAL_PORTFOLIO_WAVE4_20260826.md`.
- Gate: `LOCAL_PASS` plus scoped Windows visual. Compact/device/speech/audio/provider/public/Human gates remain unclaimed.

## Continuous goal UX integrated head — 2026-08-26

- Integrated checkpoint: `c660ce946764a5ef7d80e74fbbb8481d7f7b5d07`, tree `eb76832b79dec3491258744cc38584dcf7bc3955`; merge parents preserve the completed document-outcome and Finish-action branches.
- Combined focused tests after conflict resolution PASS. Conflicts were documentation ordering only; both revision-bound records were retained.
- Full integrated gate: 190 tasks PASS. Android 247, shared Android/Desktop 36/36, JVM-core 54, Desktop 80; 453 total, failures/errors/skips 0. Lint debug/release errors 0/warnings 7; public-surface 413 PASS.
- Integrated artifacts: debug APK 32,535,756 / `D21EF9D3A380D1CAA9781BB9F2C91E1E7E331C37B97BE7687B3F51A4A6D1153C`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,110,196 / `264FF124462B9F02E4E8D491DF435CAB3513EE05548025F758D415D4D8628C0E`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Gate: integrated `LOCAL_PASS`; the scoped Finish screenshot remains visual evidence only. Device, provider/public, physical audio/accessibility and Human gates remain unclaimed.

## Finish action truth UX — 2026-08-26

- Product checkpoint: `c4f0ca429b944f1b30ec5ce1d4452e037a1715f4`, tree `ff2a26e2d8e4c13016e277eb3428c97b569e599e`, base `bbd6850d1ed79dffadc402048ac3ae59cefe9f93`.
- RED/GREEN: shared presentation tests first failed compilation because `FinishClearActionPresentation` and its policy did not exist. GREEN fixes the ready title/guidance and the exact two-press clear label/confirmation; existing Android `GuidedWorkflowTest` agrees.
- UI/runtime: same self-created Quick Sketch production was captured before/after in a packaged Windows app with isolated app data. The title and `CLEAR STEPS` labels fit at 1200×900; `clearAllPattern`, readiness, document actions and Undo/Redo behavior are unchanged.
- Full gate: 197 tasks PASS. Android 244, shared Android/Desktop 36/36, JVM-core 54 and Desktop 80; failures/errors/skips 0. Lint debug/release errors 0/warnings 7.
- Artifacts: debug APK 31,541,362 / `AEE147DD589749A040BD6271E35C2F5783557980AB009919BD14081D2C99C2A9`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,093,812 / `D4CD3B59E022C1DEFAB9948E5A83FD18E447539885B081D4423C3FAD0236F822`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Other gates: CycloneDX 650 components / 651 dependencies; configured project validation PASS; Python policy 40; product public surface 412 / documentation-inclusive 413; `git diff --check` PASS. Local parent Standards/Spec findings 0/0.
- Evidence: parent PAD `work/CHOPLAB_GOAL_UX_AUDIT_20260826/` contains accepted screenshots, comparison and review notes.
- Gate: `LOCAL_PASS` plus scoped Windows visual evidence. Device, physical audio/touch, accessibility speech, provider/public and Human outcomes remain unclaimed.

## Wide first-entry UX — 2026-08-26

- Product checkpoint: `8b3751e5f56b9b2dd0b0c74f1003283064e45e5b`, tree `2dcbb42bc31c98778d1705182ecec5afbae01e90`, base `2bdf60d21252d490c4d50576375528e395b8f426`.
- RED/GREEN: `FocusedCaptureEntryLayout` was missing and focused test failed compilation. GREEN proves wide only for regular normal-text landscape; compact 640×360, large-text landscape and portrait remain stacked.
- Visual: current-run 3862×2110 baseline/after screenshots use isolated app data and the same pristine/cancel flow. After uses the full surface for 2×2 own-audio actions plus separate demo panel, retains WAV-only picker and cancel feedback, and closes exact runtime processes gracefully.
- `scripts/validate_project.sh`: public-surface baseline 411, executable modes, JVM-core/Desktop 18 tasks, XML/wrapper checks PASS. Documentation-inclusive final public-surface 412 PASS.
- Full gate: 190 tasks PASS. Android 248, shared Android/Desktop 34/34, JVM-core 54, Desktop 80; failures/errors/skips 0. Lint errors 0/warnings 7.
- Artifacts: debug APK 31,574,130 / `3BB275F43AADF26C34004170F56794CF9D51E97481799FE05DE01D60BE9CD369`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,110,196 / `D8FB290F6F5858D7322B559E42BCAE10C539836482CDC42F9E0BA961DB114F4A`.
- Windows verifier/ProductVersion, CycloneDX, Python 40 and `git diff --check` PASS.
- Gate: `LOCAL_PASS` plus scoped Windows screenshot evidence; compact/device/speech/audio/provider/public/Human gates remain unclaimed.

## Document operation outcome confidence — 2026-08-26

- Product checkpoint: `e2a76d80340dcad97856e5c39c1b74596cc2f42f`, tree `96c14bd39f82036bd8770e64628682a8f6c887aa`, base `bbd6850d1ed79dffadc402048ac3ae59cefe9f93`.
- RED/GREEN: shared cancel/completion contract first failed compilation. GREEN covers all four action cancellations, Android/no-name and Windows/leaf-name completion, external-file vs retained-project truth, full-path/control-character rejection and length bounds.
- Platform source: four Android result callbacks plus Windows WAV/project dialogs report cancellation. Android/Windows save/export success calls the same shared contract; existing I/O and failure code is unchanged.
- `scripts/validate_project.sh`: public-surface baseline 410, executable modes, JVM-core/Desktop 18 tasks, XML/wrapper checks PASS. Documentation-inclusive final public-surface 411 PASS.
- Full gate: 190 tasks PASS. Android 247, shared Android/Desktop 34/34, JVM-core 54, Desktop 80; failures/errors/skips 0. Lint errors 0/warnings 7.
- Artifacts: debug APK 31,541,362 / `05F90319795637C615A2AEC8FE500757FE11346A6A3674D7CEF479822E20F193`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,110,196 / `640F97E963BD1355B503952A4D2539DC2B8E38D78CD713713FF9BD4B920D8844`.
- Windows verifier/ProductVersion and CycloneDX PASS. Python 40 and `git diff --check` PASS.
- Gate: `LOCAL_PASS`; real provider/dialog destinations, device, screen-reader, public and Human gates remain unclaimed.

## Workflow NEXT and locked-stage reasons — 2026-08-26

- Product checkpoint: `a9f2245abd1673ce02b9a94f231b66d5fe87a4ea`, tree `05d84e34c62f83057ef28175ea2d4d3d9d7de96a`, base `8b9c00ba2a382705c3478c1ce8984225d30a6c8d`.
- RED/GREEN: new availability/next-action tests first failed compilation. GREEN covers empty, starter demo, loaded source, source+starter, source chop, PAD-only, LOOP, export-ready, loading, recording and stopping states; locked reasons and bounded copy lengths are exact assertions.
- UI integration: stage tabs retain their existing enabled Boolean and navigation behavior, adding state descriptions only. The fixed status strip now shows one NEXT action and uses merged semantics; no scroll/modal/screen was added.
- `scripts/validate_project.sh`: public-surface baseline 409 PASS; executable modes PASS; JVM-core/Desktop 18 tasks PASS; XML/wrapper checks PASS. Documentation-inclusive final public-surface 410 and all Python policy 40 PASS.
- Full gate: 190 tasks PASS. Android 244, shared Android/Desktop 34/34, JVM-core 54, Desktop 80; failures/errors/skips 0. Lint debug/release errors 0/warnings 7.
- Artifacts: debug APK 31,541,362 / `500B675B04A3D4DED7C88FB5F286AB6CBF2E571F99BB8DEAC7EED951FCCD21B4`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,093,812 / `3477E4CC631F2C1F8195FC388E4BD65216F6926638AA120E34F949BDFCA6A1CE`.
- Windows verifier: ProductVersion `0.17.0`, EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630` PASS. CycloneDX build and `git diff --check` PASS.
- Gate: `LOCAL_PASS`; physical visual/touch/speech/audio, provider/public and Human outcomes remain unclaimed.

## Android signer verifier recovery — 2026-08-26

- Product checkpoint: `807ef56d53eb99a8fcf4c8e779b4486136563f4e`, tree `482fdd141336a1528bce66b049891b44494b7c68`, base `4978c4c715fdc7116364e748f0a34cb1c2964e48`.
- RED/GREEN: the initial stderr-output contract could not import. The implemented parser now accepts one normalized digest from stdout and/or stderr, deduplicates identical lines, and rejects conflicting values. SDK-owned build-tools win over an ambient executable.
- Focused/full policy: verifier 13 tests PASS; all Python release/public tests 40 PASS.
- Exact artifact negative control: the existing local `v0.17.0` signed APK passed version `0.17.0 (27)`, manifest, permission/export, alignment, signature and expected-identity checks with identity output suppressed. No keystore or repository secret was read back or changed.
- Project validation: final public-surface 409 PASS; executable modes PASS; JVM-core/Desktop 18 Gradle tasks PASS; Android XML, wrapper SHA-256 and wrapper UTF-8 policy PASS. Fresh Android unit/Lint/release APK/CycloneDX gate: 111 tasks PASS; Android unit 239, failures/errors/skips 0; unsigned release APK 24,093,812 bytes / `911C43FF695562699D45F6F30E6806ABF6350DBA9933C7E65602CD07542EDD11`; SBOM 650 components / 651 dependencies PASS. `git diff --check` PASS.
- `doctor.sh` was not run because it invokes `adb devices`, which was explicitly outside this task's authority. JDK/SDK/tool resolution was exercised through validation and the exact-APK verifier without ADB.
- Gate: `LOCAL_PASS`; no OAuth, provider, GitHub, device, publication or Human gate was run or promoted.

## Monophonic PAD retrigger and loop-session ownership — 2026-08-25

- Product source: `be52047124cf502feec8275f8e74451d400872c8`, tree `6159ef8f08bd133ca23e0c9b6dddc7bfbc705da2`, parent `dfe9a223309cd4f439ffa348039428117161d2a1`.
- RED/GREEN: missing same-PAD retire and vocal-companion policies first failed compilation; the offline repeated-event fixture then failed behaviorally because the second step was louder; a Windows controller negative path failed because loop stop left its companion vocal active. All became GREEN after one ownership policy and symmetric loop-session start/stop.
- Negative controls: Android keeps a different PAD voice active; offline two-PAD same-frame energy remains greater than a single PAD; vocal companion selection includes another assigned vocal but excludes the loop owner and empty/non-vocal PADs.
- Full local gate: clean 191 tasks PASS; exact product commit final read-back 184 tasks PASS. Shared Android host 34, shared Desktop 34, Android 239, JVM-core 54 and Desktop 80; failures/errors/skips 0.
- Other checks: debug/release Lint errors 0/warnings 7; Python policy 36 PASS; public-surface 408 PASS; six XML files, executable modes, wrapper SHA-256/UTF-8 and `git diff --check` PASS. The configured shell script was not invoked because its no-`kotlinc` fallback lacks `--offline`; each component and the stronger full Gradle set were run separately without download.
- Realtime inspection: compiled Android `startVoice` delegates to the fixed-array retire helper; neither method contains a `new` bytecode instruction. No callback allocation, blocking, I/O or logging was introduced.
- Artifacts: debug APK 31,541,362 bytes / `14BE56FCFB703E38F1E7B44B3BE6AF22B398F7E3970D1EE4448D5B1F24D552FB`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,093,812 / `911C43FF695562699D45F6F30E6806ABF6350DBA9933C7E65602CD07542EDD11`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, app-image 405 files / 176,529,514 bytes.
- Gate: `LOCAL_PASS`; no install, device, recording, real audio capture/listening, Spotify/provider, publication or Human gate was attempted.

## Image-guided screen-fitting precision trim — 2026-08-25

- Product source: `6befe1193a91d099bc8ecd5f736eb4d2fea64d24`, tree `71cd3941580e80346549bbad4af0669bc5739112`, parent `25792b9`.
- Reference workflow: original screenshot opened at 570 × 1280; built-in ImageGen candidate saved under parent `outputs/`; 7-region contract and readable brief under `docs/ui/`; exact prompt and hashes in the parent receipt.
- RED/GREEN: missing initial-window policy failed compilation. Unit contracts then passed for 80% PAD fit, one-second floor, source-edge clamp, maximum zoom, overview semantics and compact-height threshold. Android instrumentation compiled and 2/2 focused tests passed on exact AVD serial `emulator-5592`.
- Runtime capture: synthetic four-second WAV only. A01 selected range `0:00.000–0:00.500`, initial viewport `0:00.000–0:01.000`, overview `表示 1.0秒`; fresh 1080 × 2400 capture and normalized three-way comparison saved in parent `outputs/`.
- Full local gate: clean 191 tasks PASS and final 184-task full read-back PASS. Shared hosts 32/32, Android 238, JVM-core 52, Desktop 79; failures/errors/skips 0. With two AVD instrumentation tests, total 435.
- Other checks: Lint errors 0/warnings 7; Python 36 PASS; final public-surface 406 PASS; UI contract validator PASS; `git diff --check` PASS; debug marker scan empty.
- Gate: `LOCAL_PASS` plus scoped AVD; physical device/audio/TalkBack/provider/public/Human gates not claimed.

## Supported-audio picker local candidate — 2026-08-25

- Product source: `a72d4ea485ff786072a9e6d9d9d75a4800422f41`, tree `a16f2bcc57226f58e872d8c7cf14a756e28bf7ef`, parent `47e5637`.
- RED: source-contract tests caught Android's unrestricted base MIME and Windows' ineffective AWT filename filter. Bundled AndroidX/JDK bytecode/source confirmed both root causes.
- GREEN: Android compiled bytecode emits only `OPEN_DOCUMENT`, `OPENABLE`, and `audio/*`; Android instrumentation contract test assembled. Windows policy test accepts real `.wav` files only; runtime chooser has one WAV filter and no All Files option.
- Clean Gradle gate: 191 actionable tasks PASS. Shared host 32/32, Android 234, JVM-core 52, Desktop 79; failures/errors/skips 0. Lint errors 0, warnings 7.
- Other gates: Python 36 PASS, public-surface 401 PASS, `git diff --check` PASS.
- Artifacts: debug APK `09997FF6…`, androidTest `BE647B4C…`, unsigned release `8C60AAAC…`; full size/hash and runtime evidence are in parent PAD `work/PAD_CHOPLAB_AUDIO_PICKER_LOCAL_RECEIPT_20260825.md`.
- Gate: `LOCAL_PASS`; connected Pixel/device/provider/public/Human boundaries were not touched or promoted.

## Reversible Quick Sketch local candidate — 2026-08-25

- Product source: `143a96919120273795805a6c1b95a203339cd4b9`, tree `707a40df56ebbdaff2f5c1688a4cd8dd13d20abc`, base `8fa1dac79b76f851e035cd8abaa5db8f9b1f5532`.
- TDD contract: fixed eight safe ranges, A01–A08 only, alternating steps `0,2,...,14`, all B/C/D pads and non-A steps preserved, strict no-op for existing A work/markers/short source/loading/recording, one ProductionSession Undo/Redo/persistence unit, and context-only four-slot dock action.
- Full Gradle gate: `:shared:testAndroidHostTest :shared:desktopTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — `BUILD SUCCESSFUL`, 184 actionable tasks on final read-back. Shared host 32/32, Android 234, JVM-core 52, Desktop 78; failures/errors/skips 0. Lint errors 0, warnings 7.
- Other gates: `scripts/validate_project.sh` PASS; Python policy 34 PASS; public-surface 397 candidates PASS; `git diff --check` PASS.
- Windows prototype: an original generated sine fixture produced the context button, seven markers, eight A pads and eight melody steps in isolated app data; starter-drum keys remained. Exact launcher/UI PIDs were stopped. No user/Spotify/third-party audio, recording or real project was used.
- Artifacts: debug APK `BB20B2C9…`, androidTest `77608CAD…`, unsigned release `6824CC90…`; exact size/hash and runtime evidence are in parent PAD `work/PAD_CHOPLAB_QUICK_SKETCH_LOCAL_RECEIPT_20260825.md`.
- Review: Spec P0–P3 none. Standards source findings none; one closeout-document P2 repaired.
- Gate: `LOCAL_PASS`. Device/audio/TalkBack/provider/public/Human boundaries remain unclaimed.

## Desktop recorder startup cleanup candidate — 2026-08-24

- Product source: branch commit `53f4bf5a62d23d9db63f538be3a06298eaf48936`, tree `d74f6314b4efd4a5604568e3c21395cfae42aaf6`, base `main@495ddc9dfac02a9e72160c637f65d2b53d6829ce`; integrated as PR #59 at `main@364ccde764b88f0bb79e10b8aaeb8284a5c069cc`.
- Regression contract: the injected `TargetDataLine` accepts `open`, throws from `start`, is closed exactly once even after later `stop` / `close`, leaves `isRecording=false`, deletes the owned partial WAV, and cannot return stale output. The fixture does not open audio hardware.
- Static gates: `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` passed 23 tests; `python3 scripts/check_public_surface.py` passed 390 candidates; six Android XML files parsed; wrapper SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d` and wrapper UTF-8 policy matched; `git diff --check` passed.
- Blocked local gate: `./gradlew :desktop:test --tests com.choplab.desktop.audio.DesktopAudioRecorderTest --no-daemon --max-workers=1 --no-watch-fs --console=plain` could not start because Gradle 9.7.1 is not cached and the distribution host is unreachable. `./scripts/validate_project.sh` reached and passed the public-surface phase, then stopped at the same Gradle prerequisite. Hosted evidence must remain bound to its exact provider revision.
- Gate: source/static candidate only. Physical Windows input, actual WAV content, route loss, audio quality, provider, publication, and `HUMAN_GO` remain unclaimed from this local receipt.

## Guided first screen and coherent workflow candidate — 2026-08-24

- Historical source label: `codex/choplab-screen-flow@43d8ace6aa43f3eb6e3b9dc01ea74604ee600705`, tree `798212c33d1dcc3eb52ea79fb20e13b87a9b2d9a`, base `3cc4cd5`. This source is not in the current PR/main ancestry, so the following receipts are externally unverifiable historical records rather than current evidence.
- Design contract: pristine CAPTURE is an explicit own-audio/project/recording choice surface with a named DUSTY JAZZ demo route. Font scale 1.2+ uses a simplified header, two-row stage strip and multi-line status. Loaded, loading and recording safety surfaces are unchanged.
- TDD: pure policy tests cover pristine/loaded/recording entry, SAVE-vs-WAV truth and 1.0/1.3/2.0 layout; Desktop regression proves playable-PAD selection moves PAD, bank and page together; Android instrumentation checks all first-entry CTAs, 48 dp bounds and demo transition to B DRUMS/B01.
- Full clean Gradle gate after the independent review's landscape finding: `:shared:testAndroidHostTest :shared:desktopTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — `BUILD SUCCESSFUL`, 191 tasks. The final scroll-end and demo-target deltas passed the same task set incrementally in 184 tasks. Shared host 25/25, Android 234, JVM-core 52 and Desktop 77; failures/errors/skips 0. Lint debug/release errors 0, warnings 7.
- Other local gates: configured `scripts/validate_project.sh` PASS; Python release/public policy 23 tests PASS; public-surface scan 389 candidates PASS; Android release identity `0.17.0 (27)` PASS and intentionally unsigned.
- Android artifacts: debug APK 32,447,992 bytes / SHA-256 `EAF275AC902D955410E3D9C6B9FB39BF28AA196D4E89FD97E8CB981619F354FA`; androidTest APK 10,874,825 / `77608CADDEBF300E0720E54E7537FD71FA081D40C4F94E9082A52E1DBFAC325B`; unsigned release APK 24,061,044 / `A21DDEB26A5DBF4B0BFB7105E99BB09BF979305C6B2442FFFEA07A7E91879F67`.
- Emulator runtime: exact final debug/test APKs installed data-preservingly on dedicated API 36 `emulator-5592`; full suite `OK (7 tests)`. Portrait font scale 1.0/2.0, 640 × 360 dp landscape 1.3/2.0, large-text scroll end and demo BEAT were captured from the exact APK; stage rows measured 49 dp and the final demo target 59 dp. System size/density/font scale/rotation restored. No fatal/ANR signature was observed.
- Windows artifact/runtime: app-image 405 files / 176,494,912 bytes / digest `2e8c568ec1746bae1a4500bd58e25b4d3751b5a0e1fbcc143e44e51639ecc773`; EXE 449,024 bytes / SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`, ProductVersion `0.17.0`. Isolated `LOCALAPPDATA` CAPTURE and demo BEAT windows responded; the demo showed B DRUMS/B-01/page 01–16 and the exact tracked process pair exited.
- Evidence: parent PAD `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/accepted/` and `work/CHOPLAB_SCREEN_FLOW_AUDIT_20260824/BASELINE_AUDIT.md`. Screenshots were visually inspected, not treated as test results by themselves.
- Historical gate report: `LOCAL_PASS` plus scoped emulator runtime was recorded for those bytes, but is not promoted into the current gate because its source is externally unverifiable from the current ancestry. Physical Pixel `DEVICE_PASS`, listening, recording, route loss, complete TalkBack speech, provider/public binary Release and `HUMAN_GO` remain unclaimed.

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
