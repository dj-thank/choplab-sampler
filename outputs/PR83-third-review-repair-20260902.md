# PR #83 third review repair receipt — 2026-09-02

## Review input and stop

PR [#83](https://github.com/dj-thank/choplab-sampler/pull/83) reached head `f9f1cb0f85c258e11b6f84cca6666ffea7aefba7`. Push and pull-request Android, Windows, iOS, and supply-chain checks all passed. The fresh exact-head review then opened one P1 and five P2 threads, so merge was stopped again:

1. a managed LOOP candidate could remain in `candidateVoices` after exclusive handoff retirement failed;
2. the top `PROJECT_STATE` block was not named `Current snapshot`, letting readers select stale 14-test evidence;
3. README's Android verifier command still expected `0.17.0 (27)`;
4. `validate_project.sh` did not run the dedicated H13 input suite;
5. the original H13 receipt linked to local-only `work/h13-local` files absent from a clean clone;
6. the plan/workflow could publish Windows despite the repository's Android-debug/iOS-only public boundary.

## RED feedback loops

- `RepositoryReleaseContractTest` first ran five tests and all five failed against the exact snapshot/README/validator/receipt/publication mismatches.
- The focused Desktop compilation failed because `DesktopStartedLoopSession` did not expose the required `abandonCandidates` rollback operation. The controller and Java Sound tests could not compile until the missing lifecycle seam existed.
- The public-preview follow-up tests then failed because the workflow/manifest still named `android.apk`, and the Android verifier had no explicit mode that could accept only the declared debug surface.

## Repair objects

- Product/repository repair: `7b22b19fcc10da7cc9371bf72a9a933f79701680` / tree `37b718efe193b5ea8b9d0de4f56618cda69ade93`.
- Public Android debug-preview successor: `acc13aa57dd8549f3f45180cef1136ddd8f6333e` / tree `de3922470ca40969c000c5d6a88b8e978fc11e7d`.

### Exclusive LOOP cleanup

- `DesktopStartedLoopSession` now resolves exactly once through successful `retirePriorPlayback` or fail-closed `abandonCandidates`.
- On handoff failure the controller cancels the production plan, abandons the started candidates, attaches any cleanup failure as suppressed evidence, and propagates the original retirement failure.
- Java Sound leaves a candidate owned for `stopAll()` retry if its first abort close fails.
- Focused controller/Java Sound regressions passed in 16 Gradle tasks / 34 seconds.

### Repository/current-state contracts

- `docs/PROJECT_STATE.md` has one top `Current snapshot — 2026-09-02`; every older current-labeled block is historical.
- README verifier metadata is `0.17.1 (28)` and its distribution copy separates public Android/iOS from Windows verification.
- `validate_project.sh` always invokes `:desktop:desktopLongPressUiTest` after its host-logic lane.
- The original H13 receipt marks raw PNG/XML/process evidence local-only, removes broken links, and points clean clones to committed tests and successor receipts.

### Public release boundary

- Tag jobs still build and verify the stable-signed non-debuggable Android continuity candidate, but do not upload it.
- The public Android file is `ChopLab-v0.17.1-android-debug.apk`. Its explicit verifier mode requires debuggable=true, the normal debug signature, exact version/build, known Compose debug components only, permission/export allowlists, and 16 KiB alignment.
- Windows builds/tests/install checks remain required by the tag workflow, but their artifact is named `choplab-windows-verification-assets` and cannot match the publication download pattern.
- The release manifest permits exactly one Android debug APK and one iOS Simulator archive, rejects Windows public files, and the attestation list contains only Android debug, iOS, manifest, and SBOM subjects.

## Local verification

- Python repository/release policy: 74 tests / 0 failures.
- `release.yml` YAML parse: PASS.
- Current plus reachable-history public-surface scan: 476 candidates; credential, signing, and audio candidates 0.
- Release metadata: `0.17.1 (28)` / `v0.17.1` PASS.
- `git diff --check`: PASS.
- Updated `scripts/validate_project.sh`: PASS. It ran public-surface/mode checks, JVM/Desktop tests, the dedicated H13 24/24 target, six Android XML parses, wrapper SHA-256, and UTF-8 policy.
- Android debug assembly: `BUILD SUCCESSFUL in 1m 21s`; 55 tasks (23 executed / 32 up-to-date). APK: 31,836,274 bytes / SHA-256 `3DB3649E733547AA5F0A309212580D9CDB984AD4B6DCCC442A6136A06E6AD0C6`; explicit debug-preview manifest/alignment/signature verification PASS.

### Resource-limited full rerun

The post-commit all-Windows `--rerun-tasks` command was attempted with 4 GiB, 2 GiB, and a bounded 768 MiB heap. The host had about 3.0 GiB free physical memory but only 1.8 GiB free virtual memory. The first two daemons failed during configuration/compile native allocation; the 768 MiB run reached shared tests and then its forked JVM-core executors failed with Windows paging-file error 1455. No product assertion failed, and these attempts are not reported as a test result.

Thirteen crash/replay logs created by these owned attempts (about 4.2 MiB) were removed from the isolated clean worktree after their paths and failure class were read back. No user file, existing artifact, process, or global setting was changed. The complete hosted Windows/Android/iOS checks on the new exact head therefore remain a mandatory merge gate rather than being inferred from partial local execution.

## Review and gate

Final parent Standards pass checked exact-once session resolution, cleanup suppression, current-snapshot selection, public-surface asset filtering, manifest/checksum behavior, verifier fail-closed defaults, local-gate reachability, and clean-clone evidence links. Final parent Spec pass replayed each of the six review findings. Unresolved local findings: Standards 0 / Spec 0.

Ceiling: `LOCAL_PASS` for the focused exclusive-loop fix, repository contracts, updated local validator, and actual debug-preview verification. Hosted exact-head CI, review replies/resolution, fresh no-finding review, merge, tag publication, and public read-back remain pending.
