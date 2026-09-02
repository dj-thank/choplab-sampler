# Release security and repository controls

Updated: 2026-09-02

This document separates controls enforced by committed source from controls that require a GitHub repository administrator. Do not report an administrator control as complete until its setting has been read back from GitHub.

## Source-enforced release contract

`gradle.properties` is the only source for `choplabVersion` and `choplabBuildNumber`. A public tag must be exactly `v${choplabVersion}`, be an annotated tag object peeling to a commit, and its peeled target must equal the exact `GITHUB_SHA` at both the metadata and publication boundaries. Release metadata also compares every reachable historical `v*` tag (including legacy tags whose build number is read from the old Android source) and fails closed unless both version ordering and Android build number are monotonic. The source-bound public manifest allows exactly one Android debug APK, one iOS Simulator app archive, and one CycloneDX SBOM for that version; checksum sidecars and `SHA256SUMS` are the only accompanying publication records.

A `v*` run of `.github/workflows/release.yml`:

1. scans the current tree and reachable history for secret-shaped content, signing material, and audio assets;
2. rejects an existing GitHub Release with the same tag;
3. requires a stable externally supplied Android keystore and expected certificate SHA-256 for a non-public continuity candidate;
4. runs the shared common-source contract on an Android JVM host, verifies the stable-signed non-debuggable candidate without uploading it, and separately builds the declared public debug preview. Before any Android build, CI captures the debug keystore's certificate SHA-256 through `keytool` without exporting or logging private-key material. The preview verifier compares the final APK signer to that pre-build identity (an artifact self-digest is not sufficient), requires debuggable=true, exact version/build metadata, and only the known Compose debug components while retaining permission/export/alignment checks;
5. compiles the declared Kotlin/Native iOS Simulator framework, runs the Swift tests, and verifies embedded iOS version/build metadata;
6. runs the shared common-source contract on a Desktop JVM host, tests and packages the Windows app-image, verifies its embedded product version, and retains it only as a short-lived Actions verification artifact;
7. creates a CycloneDX dependency SBOM, source-bound release manifest, and SHA-256 files for the declared Android/iOS public surface;
8. creates GitHub artifact provenance and SBOM attestations for the Android debug/iOS public files;
9. creates a new Android/iOS prerelease once, without `--clobber` or another asset-replacement path. Windows bytes are never downloaded into the publication job.

`workflow_dispatch` is deliberately build-only. It may produce an unsigned Android release candidate and a debug preview for inspection, but it cannot publish a GitHub Release.

## Required GitHub Actions secrets

The values below belong in repository or protected-environment secrets. Never commit them, print them, include them in evidence archives, or upload them as artifacts.

| Secret | Meaning |
|---|---|
| `CHOPLAB_ANDROID_KEYSTORE_BASE64` | Base64 of the stable Android release keystore |
| `CHOPLAB_ANDROID_STORE_PASSWORD` | Keystore password |
| `CHOPLAB_ANDROID_KEY_ALIAS` | Release-key alias |
| `CHOPLAB_ANDROID_KEY_PASSWORD` | Release-key password |
| `CHOPLAB_ANDROID_CERT_SHA256` | Expected release certificate SHA-256 fingerprint |

Keep an offline encrypted backup of the keystore and its recovery information. Losing the key prevents in-place updates to users who installed an APK signed by it. Rotating the certificate is a product migration, not a routine CI change.

## Administrator controls that must be enabled and read back

### `main` ruleset

- Require changes through a pull request.
- Require at least one approval and require review from CODEOWNERS.
- Dismiss stale approvals when the head changes.
- Require conversation resolution.
- Require the unique `Android verification`, `iOS verification`, `Windows desktop verification`, and `Supply-chain policy` check runs. The Windows workflow intentionally has no pull-request path filter, so its required check is created even when a change does not touch desktop files; its push trigger remains limited to `main` to avoid duplicate PR push runs.
- Require the branch to be current before merge.
- Block force-push and deletion.
- Limit bypass to an explicit emergency maintainer role and record each bypass.

### `v*` tag ruleset

- Restrict creation to release maintainers or the release automation identity.
- Block tag update and deletion.
- Do not reuse a version after a failed or withdrawn publication; increment the version/build number.

### Repository security settings

- Enable private vulnerability reporting.
- Enable Dependabot alerts and security updates.
- Enable secret scanning and push protection where the repository plan permits it.
- Keep workflow permissions read-only by default and allow write scopes only in the publication job.

## Read-back commands

An administrator can record the actual settings without exposing secret values:

```bash
gh api repos/dj-thank/choplab-sampler/rulesets
gh api repos/dj-thank/choplab-sampler/branches/main/protection
gh api repos/dj-thank/choplab-sampler/actions/permissions/workflow
```

A 404/403 is not evidence that protection exists. It means the caller cannot establish the control and must leave the administrator gate open.

## Release verification by a user

After downloading a runnable file and `SHA256SUMS`, verify the digest and GitHub attestation:

```bash
sha256sum --check SHA256SUMS
gh attestation verify ChopLab-v0.17.1-android-debug.apk --repo dj-thank/choplab-sampler
```

The attestation binds an artifact to a repository workflow and source revision. It does not prove the program has no vulnerabilities, and it does not replace platform code signing or user review of requested permissions.
