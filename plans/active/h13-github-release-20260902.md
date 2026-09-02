# H13 GitHub integration and v0.17.1 release

## Purpose

Integrate the accepted Windows CHOP long-press correction onto the current GitHub `main`, retain the lightweight H16/H19 provenance without committing local binaries or profiles, and publish immutable `v0.17.1` artifacts only after the merged revision passes the repository's release contract.

## Exact starting point and ownership

- Sole writer: the root integrator for this bounded task.
- Remote: `https://github.com/dj-thank/choplab-sampler.git`.
- Current base: `origin/main@d3291a522699633d5b7426e4fff66ca4f7b55c09`, tree `c664812a0f213bd33532db6f900ebd62685044e4`. It contains merged PR #83, setup-java PR #82, and Compose Multiplatform 1.12.0 PR #80.
- Current integration branch/worktree: `codex/choplab-a11y-stability-20260902` in the dedicated clean H13/v0.17.1 worktree.
- Preserved boundary: the canonical `agent/gpt-pro-ui-integration@6033d85b` checkout remains dirty and must not be staged, reset, cleaned, or used for the merge.

## Change set

- Replay the three accepted H13 commits without changing their production/test intent.
- Record the exact H16 local package and H19 isolated-startup outcomes as lightweight Markdown only.
- Advance `choplabVersion` from `0.17.0` to `0.17.1` and `choplabBuildNumber` from 27 to 28.
- Run the dedicated H13 input target in both Windows PR verification and immutable Release packaging.
- Add release notes that keep local component, package, startup, provider/public, device, and Human gates separate.

## Verification and stop conditions

1. Run `git diff --check`, the current/history public-surface scan, release-policy tests, and release-metadata validation.
2. Run the dedicated 25-test Desktop input target (12 product UI interactions + 1 offscreen teardown-classifier harness test + 12 controller tests) and the repository's Desktop/shared/JVM verification on the final local candidate.
3. Review the complete diff against the fixed base for both repository standards and the H13/release specification.
4. Push the branch, open a PR, require the Android, Windows, iOS, and supply-chain checks to finish successfully, and read back mergeability before merge.
5. After merge, create a new annotated `v0.17.1` tag only at the exact merged commit. Never move or replace `v0.17.0` or any published asset.
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
