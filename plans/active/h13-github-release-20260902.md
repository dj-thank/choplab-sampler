# H13 GitHub integration and v0.17.2 release recovery

## Purpose

Keep the accepted Windows CHOP long-press correction on GitHub `main`, preserve H16/H19 and PR #83/#84 evidence, and recover the failed immutable `v0.17.1` tag workflow through a new `v0.17.2` revision whose signer verification and release contract pass end to end.

## Exact starting point and ownership

- Sole writer: the root integrator for this bounded task.
- Remote: `https://github.com/dj-thank/choplab-sampler.git`.
- Current base: `origin/main@012b131784394b2fd641d580aaf4cd2d56b907f4`, tree `b9946fedc8a875d78fe85acde52ebffb95aaff3b`. It contains merged PR #83–#86, including v0.17.2 signer recovery and Android audio fidelity.
- Current integration branch/worktree: `codex/choplab-pr69-review-fixes-20260902` in the dedicated PR #69 scanner worktree.
- Preserved boundary: the canonical `agent/gpt-pro-ui-integration@6033d85b` checkout remains dirty and must not be staged, reset, cleaned, or used for the merge.

## Change set

- Replay the three accepted H13 commits without changing their production/test intent.
- Record the exact H16 local package and H19 isolated-startup outcomes as lightweight Markdown only.
- Preserve the immutable failed `v0.17.1` tag and advance embedded identity to `0.17.2 (29)` for the corrected release attempt.
- Run the dedicated H13 input target in both Windows PR verification and immutable Release packaging.
- Add release notes that keep local component, package, startup, provider/public, device, and Human gates separate.

## Verification and stop conditions

1. Run `git diff --check`, the current/history public-surface scan, release-policy tests, and release-metadata validation.
2. Run the dedicated 24-test Desktop input target (12 product UI interactions + 12 controller tests) and the repository's Desktop/shared/JVM verification on the final local candidate.
3. Review the complete diff against the fixed base for both repository standards and the H13/release specification.
4. Push the branch, open a PR, require the Android, Windows, iOS, and supply-chain checks to finish successfully, and read back mergeability before merge.
5. After merge, create a new annotated `v0.17.2` tag only at the exact merged commit. Never move or replace `v0.17.0`, `v0.17.1`, or any published asset.
6. Require the tag workflow to verify Android, iOS and Windows, then attest and publish only the declared Android/iOS public assets. Windows remains a short-lived verification artifact. If signing, workflow, permissions, CI, or immutable-publication policy fails, stop at that exact gate without manually weakening or replacing it.

Rollback before merge is branch deletion or PR closure; after merge it is a new corrective PR. Published tags/assets are immutable and are never rolled back by rewriting history.

## Evidence ceiling

Local tests can establish only `LOCAL_PASS`. Successful PR/merge/read-back establishes the scoped GitHub provider result. A successful immutable tag workflow and anonymous asset/read-back establish the binary publication result. Physical audio, device behavior, screen-reader speech, Spotify provider behavior, and `HUMAN_GO` remain out of scope.

## Local checkpoint

Product/release candidate `a7f4b08a1c891a67dd8879bd2007e71f5224774d` / tree `54cb789c445f384bbd1c20d3e596411340b36ccf` passed the exact post-commit offline Windows workflow rerun: 27/27 executed tasks, 358 test executions (353 unique), failure/error/skip 0, app-image ProductVersion `0.17.1`, Python policy 66, history scan, and final Standards/Spec unresolved 0/0. The next frontier is branch push and hosted PR verification; no provider/public gate is inferred from this local checkpoint.

PR #83's first head passed all eight hosted checks but review opened four P2 threads, so merge was stopped. Exact repair `260ad5e82e2bd79dbe3e168d455ef5d4280637eb` / tree `c3d2633f775cf86f14cda44bcf99dfcbc55fef3d` closed the local REDs for managed LOOP, GATE ownership, output-failure TRIM continuity, fatal propagation, and XcodeGen metadata. Its post-commit offline rerun passed 27/27 tasks, 369 executions (359 unique), H13 20/20, policy 67/67, package `0.17.1`, and local review 0/0. The next frontier is hosted exact-head revalidation and resolving all four review threads.

The fresh review of `f438a51` found three more P2 paths, so merge remained stopped. Exact second repair `f16218ddc0eb1e7b8dbdcafbb01ad8b69f6fe6bc` / tree `1ac8db94bcfdaf46adf3cbc8156afec316e13e10` separates bounded LOOP PREVIEW from performance routing, uses per-gesture capture ownership for pointer/semantics on Android/Desktop, and abandons a started Java Sound candidate when prior retirement fails. Exact offline verification passes Windows 27/27 tasks and 377 executions, Android app 41/41 and 284 tests, shared Android host 8/8 and 86 tests, H13 24/24, policy 67/67, and local review 0/0. The next frontier is new-head hosted revalidation, exact replies/resolution for the three new threads, and another fresh review.

The third head resolved all seven review threads but one Windows PR-event H13 UI test reached its 15-second whole-test timeout; the same-head push-event Windows run passed, and no product assertion failed. Test-only successor `e1669eecbbba1d38ec28826ddda4c908898ddae9` / tree `126bc2e42a243ed3febc3832bc18c9ba5c20cfe5` raises the common offscreen harness budget to 30 seconds while preserving every input duration and assertion. Exact post-commit H13 rerun passes 16/16 tasks and 24/24 tests. The next frontier is another full hosted exact-head run; the failed old run remains evidence and is not replaced by a manual rerun.

That head passed all eight hosted checks, but the exact fresh review found one P1 and five P2 findings. Product/repository repair `7b22b19fcc10da7cc9371bf72a9a933f79701680` / tree `37b718efe193b5ea8b9d0de4f56618cda69ade93` adds exclusive-loop abort and closes snapshot/README/local-gate/H13-link/public-Windows contracts. Public-preview successor `acc13aa57dd8549f3f45180cef1136ddd8f6333e` / tree `de3922470ca40969c000c5d6a88b8e978fc11e7d` verifies but does not publish stable-signed Android or Windows, and publishes only an explicitly verified Android debug APK plus iOS Simulator archive. Policy 74/74, updated validator, focused loop tests, actual debug APK build/verification, history scan, and local review 0/0 pass. Host paging-file exhaustion prevents a complete local all-task rerun, so new-head hosted CI remains mandatory.

The next exact head also passed all eight hosted checks, but fresh review found three P2 staging/proxy/tooling paths. Repair `6070204f9175ae9f09613eedaebc8c7e7a2b17f5` / tree `732f516e3b5d820f7ee0ef9c56f50063f7f2b759` copies the declared debug array, handles capture ownership in the API 36 proxy, and permits only two named debug tooling components. Policy 75/75, AndroidTest Kotlin compile, actual debug APK verification, and local review 0/0 pass. The next frontier is exact-head hosted CI, three thread replies/resolution, and fresh review.

PR #83 exact head `13e41af589ee32018ae6857623b857b9c0356f21` completed its hosted gate and was merged by `dj-thank` as `2864117fe3c81b308033155dae337a6030165344`. Main then advanced through setup-java PR #82 and Compose 1.12.0 PR #80 to the current base above. The old PR #83 pending statements are retained chronology, not the current frontier.

PR #84 follows up two independent harness races without changing product behavior: Android framework accessibility readiness (`8b58e4b`), laid-out offscreen pointer target reacquisition/full stacks (`f34065e`), and the exact Compose 1.12 `RectManager.remove` → `ImageComposeScene.close` classifier (`df61ac4`). Local JDK 17 H13 is 25/25; policy is 75/75; current-tree public scan is 479 with credential/signing/audio candidates 0. Head `a1fdc80` completed hosted checks 8/8.

Post-`a1fdc80` test-only milestones `75025c51754f8c693b81ed0cef7dd6a852aef7c8` / tree `52b24bfd4a2c9a76fa8a3407b643d930adb730e5` and `e911b417ef1740f69b230731d25c07a158d2147a` / tree `8a28eccc521636f11065cf5f63feca5560687095` raise only one stale-recovery close join and the shared `awaitCondition` observation budget to five seconds. Other test waits and all production deadlines remain unchanged. Integrated head `a4030ea8973b278da1e9e04a37231b8178d32d4b` / tree `e2c36cd58008af6759fd2100bb04bdf529de5696` completed hosted checks 8/8; its fresh review found four documentation/SSOT consistency findings. The current frontier is this documentation successor's fresh checks and review closure, then PR #84 merge, merged-main checks, immutable `v0.17.1` tag workflow, and Release read-back.

Documentation successor `f47519f75a9e52e6358643c70dbd8487d2458eaf` reproduced the Compose 1.12 defect beyond teardown: Android PR run `33619352222`, job `100212539931`, first observed the known close failure and then failed from `RectManager.recalculateRectIfDirty` during `ImageComposeScene.render()`. The close-only filter therefore cannot qualify the candidate.

Current repair `deed143171806f282768a82d0b9e4fb1fea2a4f8` / tree `bb01345e8bc2c29e5e530229565fb33ea94660ff` pins Compose plugin/runtime/foundation/UI to the previously verified 1.11.1 line, removes the 1.12-only runtime exclusions, and removes the exception filter/classifier. Pointer-target and Android framework-tree readiness remain. JDK 17 local verification passes H13 24/24, Desktop 180/180, `installDist`, AndroidTest Kotlin compile (50 tasks), policy 75/75, public scan 479/0, and `git diff --check`. The current frontier is exact-head hosted CI and fresh review, then PR #84 merge, merged-main checks, immutable `v0.17.1` tag workflow, and Release read-back.

PR #84 exact head `51238b1f29dc0c9bfb904d569fdf2081b23e56e3` passed checks 8/8, no-finding review and six resolved threads, then merged as `f6cbfdcc65584264ca7fd1cf7c450e9cab284b14`; merged-main checks passed 4/4. Annotated `v0.17.1` tag object `20f1beeaf68bc88da9913bf059bcb2aff9a5dcd4` peels to that merge and remains immutable. Release run `33622584694` built/tests/lint/SBOM successfully but Android job `100222855379` stopped before staging because the verifier could not read a textual signer-certificate SHA-256 label. No Release was published.

Signer recovery `cb7ba1fe6b8f40f0fc849d25d0953fd32e422bde` / tree `66b5cfd1acad5b05fa7ab956754db183d5b3d8a0` requests PEM signer evidence, hashes DER bytes, cross-checks any text digest, and rejects missing/malformed/multiple/conflicting evidence. Embedded identity is `0.17.2 (29)`. PR #85 head `a7bf79f` merged as `e71e0fde2e8a7ef82020cfc905d07473b95c073b`; merged-main Android `33625076385`, Windows `33625076392`, iOS `33625076368`, and supply-chain `33625076363` all succeeded. Publication stays held until the product-convergence milestones below finish.

PR #86 follow-up `231da39e4c42b26d135bb3f5d7ce366fc6a540af` / tree `6ee130640e6c02228208b944627fcca5d8ff1605` adds a RED→GREEN duplicate-PEM case and rejects more than one PEM block even when identical certificates would collapse to one digest. Verifier 39/39, policy 80/80, actual debug APK verification, public scan and `git diff --check` pass locally.

## Product convergence extension — Android fidelity and final main

### Purpose and user-visible outcome

Ship one current `main` where Android no longer applies audible nonlinear shaping to every normal sample, all accepted UI/security/release changes coexist, and a new `v0.17.2` is built only from the final verified merge.

### Current state

- PR #84 head `51238b1f29dc0c9bfb904d569fdf2081b23e56e3` was normally merged as `f6cbfdcc65584264ca7fd1cf7c450e9cab284b14`; its four merged-main workflows passed.
- PR #85 head `a7bf79fe790adff178e9dc3c0ed840ba2b489168` was normally merged as `e71e0fde2e8a7ef82020cfc905d07473b95c073b`; it advances release identity to `0.17.2 (29)` and repairs PEM-backed signer verification. Publication remains held for product convergence.
- Audio product checkpoint `b63ed650e47c5555f4a328171c222ca4888a88ae`; decoder/master hardening `9dc71a4652c943ded89c62bb50d9512270182d20`; dense invariants `7e7c7ae5a5b30f7f3e526ba887825fe60b400fd1`; final review repair `2b0da00c7c117c0b188683739c6a495f13958664` / tree `7cc6a11584b89a165a855db67015207925202c53`.
- Exact working branch is `codex/choplab-android-clipping-20260902`; the dirty canonical checkout and all existing tags/releases remain untouched.

### Constraints and invariants

- Android realtime callback remains allocation/lock/I/O/log-free. Realtime and offline output share one limiter.
- Default gain 0.9 is linear; overload remains finite, monotonic, symmetric and below 0.98.
- Decoder never reinterprets already emitted bytes under a changed PCM encoding and never creates clipping while removing DC.
- Evidence remains separated as `LOCAL_PASS -> DEVICE_PASS -> PROVIDER_PASS -> PUBLIC_PASS -> HUMAN_GO`.
- No force push, tag rewrite, user-data clear, credential exposure, or speculative Pro-v0.2 implementation.

### Architecture and interfaces

`SamplerDspPrimitives.softLimit` owns the shared arithmetic. `masterSampleForAudioTrack` is the exact Android output call seam. `PatternRenderer` uses the same primitive. `AudioDecoder` validates PCM encoding alongside sample rate/channel stability, quantizes finite decoded values, and performs peak-safe whole-signal DC correction on its I/O worker.

### Milestones

1. Android fidelity: RED/GREEN primitive, realtime master, offline WAV, encoding-drift, non-finite and DC-clipping tests; full relevant Gradle gate; docs and receipt.
2. Normal PR integration: push without rewriting history, fresh review, all exact-head checks, threads 0, normal merge, merged-main 4/4 read-back.
3. Merge the reviewed PR #69 scanner, CI/release hardening, and Windows/UI brush-up in dependency order; rerun each successor against current main.
4. Reconcile definition-of-done and feature matrix to the production MVP, prove open PR/TODO zero for admitted scope, configure final branch/tag protections, and run exact-main Windows/Android/iOS/supply-chain gates.
5. Run available Android AVD and Windows package/audio E2E on exact final bytes. Keep physical-device, Spotify-account, screen-reader speech and Human listening claims unpromoted unless actually observed.
6. Create only a new annotated `v0.17.2` tag at final main, allow the hardened workflow to rebuild/publish, then reverse-download and verify manifests, hashes, attestations and anonymous access. Never move the failed `v0.17.1` tag.

### Progress

- [x] 2026-09-02 — PR #84 merged normally; exact merged-main workflows 4/4 succeeded.
- [x] 2026-09-02 — PR #85 signer recovery merged normally as `e71e0fd`; merged-main workflows 4/4 succeeded.
- [x] 2026-09-02 — Duplicate-identical signer PEM evidence rejected at `231da39`; verifier 39 and policy 80 pass.
- [x] 2026-09-02 — Old master distortion reproduced and repaired at `b63ed65`; shared Android/Desktop hosts and Android/offline parity passed.
- [x] 2026-09-02 — Decoder/master negative paths repaired at `9dc71a4`; focused RED then bounded-memory GREEN.
- [x] 2026-09-02 — Whole-signal DC confirmation and explicit/dense invariants completed at `7e7c7ae`.
- [x] 2026-09-02 — Raw PCM_FLOAT Infinity review RED fixed before clamping at `2b0da00`.
- [x] 2026-09-02 — Exact final-code full gate: shared 87+87, Android 289, JVM core 88, lint/APK/AndroidTest compile; 99 selected tasks executed, failure/error/skip 0.
- [x] 2026-09-02 — Audio PR #86 exact head passed 8/8 checks, threads 0 and fresh review, then merged normally as `012b131`.
- [x] 2026-09-02 — PR #69 merged current main locally at `a5cfeac`; policy 192, scanner 116, current/history 483, Gradle 111/111 and Windows package pass.
- [ ] PR #86 merged-main read-back; PR #69 exact-head checks/review/merge; release hardening, UI brush-up, final exact-main gates and publication.

### Discoveries

- The prior limiter was not merely an overload guard: it altered every non-zero sample and halved full-scale amplitude.
- A decoder output-format event could change PCM encoding after bytes had already populated a builder while rate/channel validation still passed.
- Float PCM's Android nominal range is `[-1,1]`; NaN handling is undefined, so non-finite decoder output is rejected before quantization.
- The host had only about 2.1 GiB free virtual memory when a 4 GiB Gradle daemon failed. One worker, 1.5 GiB heap and in-process compilation passed the exact source.

### Decision log

- 2026-09-02 — Use a transparent stateless knee instead of a stateful lookahead limiter. It fixes normal-level distortion without adding callback state/latency or breaking offline parity; intentional overload remains bounded saturation.
- 2026-09-02 — Preserve Android's documented nominal over-range clamp, but reject NaN/Infinity and forbid format reinterpretation.
- 2026-09-02 — Calculate DC offset from the whole signal and reduce/skip it when the requested shift would clip a peak.

### Validation log

- `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :jvm-core:test --rerun-tasks` — 2026-09-02, exact `2b0da00`, JDK 17, one worker, bounded heap/in-process compiler, serial 256 MiB test forks — PASS, 99/99 tasks executed.
- Test XML totals — shared Desktop 87, shared Android host 87, Android unit 289, JVM core 88; failure/error/skip 0.
- Android API source assumption — `AudioFormat.ENCODING_PCM_FLOAT` nominal range and finite warning: https://developer.android.com/reference/android/media/AudioFormat

### Risks and rollback

Before merge, the branch can be closed without touching main. After merge, corrections use a new PR. The limiter threshold/ceiling are centralized constants; rollback never rewrites a published tag. AVD or host audio cannot substitute for physical route listening.

### Remaining device validation

Install the exact final APK without clearing data, exercise imported clean tone/source/PAD/polyphony/export and route interruption, inspect fatal/ANR/AudioTrack logs, and compare exported PCM. Physical speaker, wired/Bluetooth route quality, TalkBack speech, and Human listening require an attached device/person and remain explicitly separate until observed.
