# H13 GitHub integration and v0.17.1 release

## Purpose

Integrate the accepted Windows CHOP long-press correction onto the current GitHub `main`, retain the lightweight H16/H19 provenance without committing local binaries or profiles, and publish immutable `v0.17.1` artifacts only after the merged revision passes the repository's release contract.

## Exact starting point and ownership

- Sole writer: the root integrator for this bounded task.
- Remote: `https://github.com/dj-thank/choplab-sampler.git`.
- Base: `origin/main@41be4ffda904e3317e0ef3802bff90b3887f99b2`, tree `12852e6b918834b91cf79f25bf4152fcaf7f0319`.
- Integration branch/worktree: `codex/choplab-h13-v0171-20260902` in a dedicated clean worktree.
- Preserved boundary: the canonical `agent/gpt-pro-ui-integration@6033d85b` checkout remains dirty and must not be staged, reset, cleaned, or used for the merge.

## Change set

- Replay the three accepted H13 commits without changing their production/test intent.
- Record the exact H16 local package and H19 isolated-startup outcomes as lightweight Markdown only.
- Advance `choplabVersion` from `0.17.0` to `0.17.1` and `choplabBuildNumber` from 27 to 28.
- Run the dedicated H13 input target in both Windows PR verification and immutable Release packaging.
- Add release notes that keep local component, package, startup, provider/public, device, and Human gates separate.

## Verification and stop conditions

1. Run `git diff --check`, the current/history public-surface scan, release-policy tests, and release-metadata validation.
2. Run the dedicated 14-test Desktop input target and the repository's Desktop/shared/JVM verification on the final local candidate.
3. Review the complete diff against the fixed base for both repository standards and the H13/release specification.
4. Push the branch, open a PR, require the Android, Windows, iOS, and supply-chain checks to finish successfully, and read back mergeability before merge.
5. After merge, create a new annotated `v0.17.1` tag only at the exact merged commit. Never move or replace `v0.17.0` or any published asset.
6. Require the tag workflow to build, attest, and publish every platform asset. If signing, workflow, permissions, CI, or immutable-publication policy fails, stop at that exact gate without manually weakening or replacing it.

Rollback before merge is branch deletion or PR closure; after merge it is a new corrective PR. Published tags/assets are immutable and are never rolled back by rewriting history.

## Evidence ceiling

Local tests can establish only `LOCAL_PASS`. Successful PR/merge/read-back establishes the scoped GitHub provider result. A successful immutable tag workflow and anonymous asset/read-back establish the binary publication result. Physical audio, device behavior, screen-reader speech, Spotify provider behavior, and `HUMAN_GO` remain out of scope.
