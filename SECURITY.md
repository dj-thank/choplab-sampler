# Security policy

## Supported code

Security fixes are made on the current `main` branch and the most recent public preview release. Older preview binaries are not maintained after a replacement release is published.

## Reporting a vulnerability

Do not disclose an unpatched vulnerability in a public issue, pull request, discussion, log, recording, or sample project.

Use GitHub's private vulnerability reporting form on this repository when it is available. If the private form is not visible, contact the maintainer through a private contact method shown on the maintainer's GitHub profile and provide only enough information to establish a private channel. Do not send credentials, user audio, signing keys, OAuth tokens, or other third-party personal data.

A useful report contains:

- affected revision, release tag, platform, and OS version;
- minimal reproduction steps and observed impact;
- whether microphone, system-audio capture, project archives, OAuth, or release signing is involved;
- a proof of concept that uses synthetic data and contains no secrets or copyrighted audio;
- any suggested mitigation or regression test.

The maintainer should acknowledge a private report, reproduce it, classify impact, prepare a regression test and fix, and coordinate disclosure. Exact response or remediation times are not guaranteed for this volunteer project.

## Release and secret boundary

Public tag releases fail closed unless Android stable-signing secrets are present and the embedded certificate, version, permissions, exported components, Windows metadata, and iOS metadata pass inspection. User audio, provider credentials, signing material, Apple provisioning profiles, and generated keystores must never be committed or uploaded as workflow artifacts.

The source-controlled scanner covers current candidates and reachable Git history. It is an additional guard, not permission to paste secrets into a local worktree. Rotate any credential immediately if it is exposed, even when a later commit removes it.
