# ChopLab public Android preview release

## Purpose and user-visible outcome

Publish the current ChopLab MVP as an open-source Android project and make a development-preview APK available through GitHub Releases. A user can download the APK, install it on Android 10 or newer, and understand which MVP features are verified versus still under development.

## Current state

- Repository: `https://github.com/dj-thank/choplab-sampler`
- Current implementation: `app/` AudioTrack-based MVP; `reference/pro-v0.2/` remains unverified reference material.
- Public preview changes are merged into `main` at merge commit `d427cd7a5f6af445703f6b964e6e862cf30a1d40`.
- MIT `LICENSE` is present; no secrets, signing keys, `local.properties`, or generated APKs are tracked.

## Constraints and invariants

- Android 10 / API 29 minimum; target and compile SDK 36.
- The first public APK is a GitHub Actions debug-signed development preview, not a production-signed release.
- Do not claim Pro features, complete audio workflow, latency, or release signing without direct evidence.
- Preserve the existing permission and capture-policy boundaries; do not bypass DRM or playback-capture restrictions.
- Keep generated APKs and machine-local SDK paths out of Git.

## Architecture and interfaces

- Public source remains the root Gradle project.
- `android.yml` verifies offline project checks, unit tests, lint, and debug APK assembly on pushes and pull requests.
- `release.yml` repeats those checks on `v*` tags, creates a debug APK plus SHA-256, and publishes them to a GitHub Release.
- README and CONTRIBUTING document installation, limitations, validation, and contribution boundaries.

## Milestones

### Milestone 1: Public repository preparation

- Scope: public README, contribution guide, pinned Actions, least-privilege release publishing, and tag-driven APK workflow.
- Acceptance evidence: public repository exists, PR checks pass, and no secret/path scan findings.

### Milestone 2: CI APK and initial device installation

- Scope: fix baseline compile/Lint issues exposed by the real GitHub Android environment; obtain the debug artifact; install on Pixel 9a.
- Acceptance evidence: GitHub run `31319111062` passed validation/tests/Lint/assemble; APK SHA-256 is recorded in `docs/PROJECT_STATE.md`; package version `0.1.0` is installed and `MainActivity` launches without an immediate fatal log.

### Milestone 3: Public tag release

- Scope: merge the public-preview branch, create tag `v0.1.0-preview.1`, and verify the release workflow publishes the APK and checksum.
- Acceptance evidence: GitHub Release URL, asset names, and asset checksum are recorded here and in the project state.

## Progress

- [x] 2026-08-09 — Added public README, CONTRIBUTING, pinned CI, and tag-driven release workflow.
- [x] 2026-08-09 — Fixed Compose layout imports exposed by the real CI compiler.
- [x] 2026-08-09 — Fixed microphone and playback-capture permission lint boundaries.
- [x] 2026-08-09 — GitHub Actions run `31319111062` passed tests, Lint, and debug APK assembly.
- [x] 2026-08-09 — Installed the CI APK on Pixel 9a and launched `MainActivity` without an immediate fatal exception.
- [x] 2026-08-09 — Merged PR #1 and published tag `v0.1.0-preview.1`.

## Discoveries

- The initial GitHub compile exposed four stale Compose extension imports; removing them restored Kotlin compilation.
- The first strict SDK-license pipeline treated the expected `yes` Broken pipe as a failure. The workflow now checks `sdkmanager`'s own `PIPESTATUS` entry.
- Android Lint exposed two permission-boundary errors in AudioRecord builders. The calling Activity/service already gate runtime permissions; the builder boundaries now carry explicit `MissingPermission` suppression.
- GitHub Actions debug signing is runner-specific enough that the public Release APK could not update the earlier CI APK in place. The phone had no saved project data, so uninstall/reinstall was safe for this MVP.

## Validation log

- `scripts/validate_project.sh` — PASS locally with the workspace JDK/Kotlin toolchain and in GitHub Actions.
- GitHub Actions run `31319111062` — PASS: unit tests, `lintDebug`, `assembleDebug`.
- CI artifact APK — SHA-256 `07A53C695D7A229816E0FC0F53C4B5C9F270C705228DE7320008B4074785FE67`.
- `adb install -r app-debug.apk` on physical Pixel 9a — `Success`.
- `adb shell am start -n com.choplab.sampler/.MainActivity` — launched; immediate fatal/crash log scan empty.
- GitHub Actions release run `31319529630` — PASS: build/package and public-release publish.
- Public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.0-preview.1`.
- Public Release APK — SHA-256 `4E6220484F5991B34792CBCFCC5B251460893D9433DD2BE06A6B4635BCBEA513`; downloaded `.sha256` sidecar matched.
- Public Release APK installed after removing the earlier differently signed CI preview; the pre-uninstall read-only data check found no project files.

## Risks and rollback

- The debug APK is not a stable production signing identity; future builds may require uninstalling the previous preview before updating.
- If the tag workflow fails, keep the source repository and CI artifact available; fix the workflow or code on a new branch and do not claim a Release asset until its checksum is verified.
- Roll back source changes by reverting the public-preview commits; do not force-push or rewrite the public branch.

## Remaining device validation

- Import, microphone recording, playback capture, chopping, pad triggering, sequencing, export, permission denial, rotation/lifecycle teardown, and long-session behavior on the physical device.
- Measured latency, xRun behavior, and audio quality.
- Production signing, Play distribution, and complete Pro feature parity.
