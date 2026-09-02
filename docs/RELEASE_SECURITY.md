# Release security and repository controls

Updated: 2026-08-21

This document separates controls enforced by committed source from controls that require a GitHub repository administrator. Do not report an administrator control as complete until its setting has been read back from GitHub.

## Source-enforced release contract

`gradle.properties` is the only source for `choplabVersion` and `choplabBuildNumber`. A public tag must be exactly `v${choplabVersion}` and point to a commit already reachable from `main`.

A `v*` run of `.github/workflows/release.yml`:

1. scans the current tree and reachable history for secret-shaped content, signing material, and audio assets; ZIP policy first bounds and parses central/local records, requires one contiguous ownership chain through every compressed span and validated signed/signatureless descriptor, verifies both metadata copies, and independently decodes the exact stored/deflate/BZIP2/LZMA span under hard input/output limits so trailing input, undeclared output or CRC disagreement cannot hide content; it scans current symlink targets without following them, enumerates regular historical ZIP blobs through NUL-delimited per-parent merge history, scans Windows/iOS archives after creation, and rescans only the downloaded Android/iOS publication assets before publication;
2. rejects an existing GitHub Release with the same tag;
3. requires a stable externally supplied Android keystore and expected certificate SHA-256 for a non-public continuity candidate;
4. runs the shared common-source contract on an Android JVM host, verifies the stable-signed non-debuggable candidate without uploading it, and separately builds the declared public debug preview. The preview verifier requires debuggable=true, the default debug signature, exact version/build metadata, and only the known Compose debug components while retaining permission/export/alignment checks;
5. compiles the declared Kotlin/Native iOS Simulator framework, runs the Swift tests, and verifies embedded iOS version/build metadata;
6. runs the shared common-source contract on a Desktop JVM host, tests and packages the Windows app-image, verifies its embedded product version, and retains it only as a short-lived Actions verification artifact;
7. creates a CycloneDX dependency SBOM, source-bound release manifest, and SHA-256 files for the declared Android/iOS public surface;
8. creates GitHub artifact provenance and SBOM attestations for the Android debug/iOS public files;
9. creates a new Android/iOS prerelease once, without `--clobber` or another asset-replacement path. Windows bytes are never downloaded into the publication job.

### Bounded ZIP content policy

Safe-named ZIP member text, filenames, comments and local/central extra fields are scanned without extraction. BOM-marked UTF-16/32 is normalized before matching, and supported audio-container signatures are rejected even under a safe text name. The parser validates one contiguous ownership chain across local records, compressed spans, optional descriptors and the central directory before trusting entry metadata. Stored, deflate, BZIP2 and LZMA text must consume their declared input exactly and match declared output size/CRC.

Resource limits are fail-closed: 4,096 entries, 512 KiB ordinary text output per member, 4 MiB metadata/output per archive, 4 MiB aggregate compressed input for decoded text, 100:1 expansion and a 16 MiB LZMA dictionary checked before decoder construction. A bounded `.app` main executable is fully decoded and scanned. Arbitrary oversized safe-named entries are rejected regardless of magic prefix; only exact JDK `runtime/lib/modules` may be fully decoded up to 128 MiB per JIMAGE under the shared 384 MiB binary-secret body budget, and it must validate as a structurally consistent JIMAGE before its full body is scanned. Findings redact secret-shaped labels before writing CI logs.

The same parser handles current candidates, bounded reachable historical ZIP blobs and explicit post-build `--archive` paths. Final publication scans are placed after artifact download and before release manifest creation, checksums, attestations or `gh release create`.

ZIP-compatible nested members are detected by structure as well as suffix and recursively scanned to depth 3 under a 64-archive count, 16 MiB/member bound, and shared 256 MiB compressed-container and 256 MiB expanded-work limits. Current/explicit root candidates also share separate 128-archive and 512 MiB compressed/expanded aggregate budgets; historical roots use their stricter 128-archive and 64 MiB container/decoded aggregate budgets. Unsupported nested formats are rejected. Reachable non-commit refs are enumerated with NUL-safe commands, bounded before peeling, and annotated tags share a 512-operation peel budget so a tag chain cannot hide a historical ZIP or cause unbounded `cat-file` work.

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
gh attestation verify ChopLab-v0.17.1-android-debug.apk --repo dj-thank/choplab-sampler
```

The attestation binds an artifact to a repository workflow and source revision. It does not prove the program has no vulnerabilities, and it does not replace platform code signing or user review of requested permissions.
