# Security policy

## Supported code and reporting

Security fixes target current `main` and the latest supported release for Android and Windows. Archived previews are historical source, not maintained distributions. A release's support and known limitations belong in its release notes.

Do not disclose an unpatched vulnerability in a public issue, PR, recording or sample project. Use this repository's GitHub private vulnerability reporting form when available. If it is unavailable, use a private contact method published by the maintainer to establish a channel. Do not send credentials, user audio, OAuth tokens, signing keys or third-party personal data.

Include the revision/release, platform/OS, minimal synthetic reproduction, impact and affected boundary (capture, archive, provider, persistence or distribution). Suggested mitigations and meaningful regression tests help. Response times are not guaranteed.

## Controls

Imports and archives must have explicit path, entry, size, expansion and memory limits. Validate decoded content and hashes before publishing it as usable project/library state. A failed or cancelled job must not commit stale output. Rendering performs no file or network I/O.

Never commit or publish private signing material, provider credentials, user audio, models or local configuration. Scan source/current candidates, reachable history and final distribution contents separately. Rotation is required after credential exposure; deleting a later copy does not undo exposure.

[RELEASE](docs/RELEASE.md) defines signing, identity, archive and publication checks. Stage1C is replacing the archived release workflow; until its checks pass, do not claim the new release process is enforced. Administrator settings and provider behavior require live readback. A green source test is not proof of those controls.
