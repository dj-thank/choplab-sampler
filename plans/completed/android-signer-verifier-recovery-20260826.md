# Android signer verifier recovery

## Outcome

`v0.17.0` の署名済みAPK生成後に自動公開が停止し、手動のclean-worktree recoveryが必要になった検査境界を閉じる。署名要件やcertificate一致判定は弱めず、実行するAndroid SDK toolと`apksigner`出力の読取を決定的にする。

## Boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-release-verifier-recovery-20260826` / current root task
- Baseline: `codex/choplab-creative-improvement-20260825@4978c4c715fdc7116364e748f0a34cb1c2964e48`
- Product checkpoint: `807ef56d53eb99a8fcf4c8e779b4486136563f4e`
- Target gate: `LOCAL_PASS`
- No signer/secret mutation or disclosure; no OAuth, GitHub, ADB/device, publication, push, PR, or Human action

## Reconciled input

- Local `v0.17.0` recovery assets remain bound to tag commit `ab68d2d9eaf2e5b9021a131f9ecc34d5063825bf`; manifest, sidecars, APK signature verification, Windows metadata, iOS archive and SBOM are locally readable and consistent.
- Historical run attempt 2 passed signing setup, tests, Lint, build and SBOM, then stopped at `Could not read signer certificate SHA-256 from apksigner`. This was a parser/tool-selection failure lead, not proof of a bad signature.
- Windows v0.17 receipts remain local/historical evidence for package/install/runtime scope. Spotify remains metadata/control-only and provider-blocked before authorization. Pixel receipts remain revision-bound scoped history; no fresh device action occurred.

## Repair

1. Resolve Android SDK-owned `cmdline-tools` / `build-tools` before any ambient `PATH` executable. A configured workflow that installs a pinned build-tools version no longer silently verifies with an unrelated preinstalled binary.
2. Read the signer digest from both captured stdout and stderr. Repeated identical output is accepted; missing or conflicting values fail closed.
3. Keep the existing expected-certificate comparison, manifest allowlist, exported-component checks, alignment check and signed-release requirement unchanged.

## Validation

- RED: the new stderr/output-channel contract could not import before implementation.
- Focused Python: 13 tests PASS, including SDK-over-PATH selection, stderr parsing, duplicate equality and conflicting-digest rejection.
- Full Python release/public policy: 40 tests PASS.
- Exact local `v0.17.0` Android asset: version `0.17.0 (27)`, alignment, signature and expected-identity comparison PASS with identity output suppressed; APK bytes/hash match the local recovery manifest.
- `scripts/validate_project.sh`: public-surface baseline 408 candidates PASS, executable modes PASS, JVM-core/Desktop Gradle 18 tasks PASS, Android XML parse PASS, wrapper checksum/UTF-8 PASS. Final documentation-inclusive public-surface scan: 409 candidates PASS.
- Fresh release-prep gate: Android unit/Lint/release APK/CycloneDX `BUILD SUCCESSFUL` with 111 tasks; Android unit 239, failures/errors/skips 0. Unsigned local APK `24,093,812` bytes / SHA-256 `911C43FF695562699D45F6F30E6806ABF6350DBA9933C7E65602CD07542EDD11`; verifier confirms `0.17.0 (27)` and explicit unsigned-candidate state. SBOM identity `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies PASS.
- `git diff --check`: PASS.

## Gate and next action

This is `LOCAL_PASS` for the release verifier and exact existing local APK. It is not a rerun of hosted Actions and does not establish a new provider/public/device/Human observation. The next release owner should integrate this checkpoint into the chosen current-source release branch, then use a new version/tag for the newer product bytes; do not rewrite `v0.17.0`.

## Rollback

The repair is isolated in commit `807ef56`; reverting or declining that commit restores the previous verifier without touching product audio, project schema, release assets or signing material.
