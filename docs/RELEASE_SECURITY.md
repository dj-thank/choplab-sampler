# Release security and repository controls

Updated: 2026-08-21

This document separates controls enforced by committed source from controls that require a GitHub repository administrator. Do not report an administrator control as complete until its setting has been read back from GitHub.

## Source-enforced release contract

`gradle.properties` is the only source for `choplabVersion` and `choplabBuildNumber`. A public tag must be exactly `v${choplabVersion}` and point to a commit already reachable from `main`.

A `v*` run of `.github/workflows/release.yml`:

1. scans the current tree and reachable history for secret-shaped content, signing material, and audio assets;
2. rejects an existing GitHub Release with the same tag;
3. requires a stable externally supplied Android keystore and expected certificate SHA-256;
4. runs the shared common-source contract on an Android JVM host, builds a non-debuggable release APK, and rejects unexpected permissions, permission declarations, exported components, debug/test tooling, version metadata, or signer identity;
5. compiles the declared Kotlin/Native iOS Simulator framework, runs the Swift tests, and verifies embedded iOS version/build metadata;
6. runs the shared common-source contract on a Desktop JVM host, tests and packages the Windows app-image, and verifies its embedded product version;
7. creates a CycloneDX dependency SBOM, source-bound release manifest, and SHA-256 files;
8. creates GitHub artifact provenance and SBOM attestations for the runnable files;
9. creates a new prerelease once, without `--clobber` or another asset-replacement path.

`workflow_dispatch` is deliberately build-only. It may produce an unsigned Android release candidate for inspection, but it cannot publish a GitHub Release.

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
- Require the Android, Windows, iOS, and `Supply-chain policy / History scan and dependency SBOM` checks.
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
gh attestation verify ChopLab-v0.16.2-android.apk --repo dj-thank/choplab-sampler
```

The attestation binds an artifact to a repository workflow and source revision. It does not prove the program has no vulnerabilities, and it does not replace platform code signing or user review of requested permissions.
