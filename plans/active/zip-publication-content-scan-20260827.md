# Scan bounded ZIP content at the exact artifact publication boundary

## Purpose and user-visible outcome

Prevent credentials, signing material and user audio from becoming reachable inside a safe-named ZIP artifact while keeping legitimate ChopLab source, Windows app-image and iOS Simulator archives buildable. The final bytes uploaded or published must be scanned after archive creation, not inferred safe from an earlier source-tree scan.

## Exact current state

- Existing PR lineage: PR #69 head `1abbd8ff004edceafd81fe5ffa6059e2d3692d85`.
- Latest-main integration: `fa4e6d2646e44039ce41662ba4c6ae6970ae9dd6`, tree `fafdc23a12c9e5f1e414d8aa2d57da676c382aff`, parents PR #69 head and `main@0f5b672`.
- Product subtrees match main exactly; the isolated delta remains public-surface policy/tests/docs plus the workflow wiring selected here.
- Current unresolved findings: attacker-declared ZIP LZMA dictionary can allocate before a cap; compressed input is limited per member but not per archive. Current workflows scan source before creating the ZIPs they later upload or publish.

## Constraints and invariants

- Reject an oversized LZMA dictionary before `LZMADecompressor` construction; Python's default ZIP_LZMA control must continue to pass.
- Charge every decoded text candidate's declared compressed span to one archive-wide input budget before decoder work.
- Preserve complete layout, descriptor, local/central metadata, output-size, CRC, history, symlink and binary/audio exclusion controls from PR #69.
- Explicit archive paths fail closed when missing or malformed and share the same parser as current/history candidates.
- Source snapshot and Windows/iOS archives are scanned after creation and before upload; final release Windows/iOS archives are scanned after artifact download and before manifest, attestation or publication.
- No secret value, signing identity, user audio or private path is printed or persisted.
- No force push, duplicate PR, tag/Release, device/provider or Human action in the local phase.

## Milestones

### 1. RED resource and consumer controls

- Add a forged LZMA dictionary fixture whose decoder constructor must never be called.
- Add a multi-entry archive-wide compressed-input budget control plus valid supported-method control.
- Add explicit archive CLI malicious/missing/safe controls and workflow order contracts.

### 2. Shared-boundary repair

- Enforce the dictionary and aggregate compressed-input bounds before decoder creation.
- Expose repeatable `--archive` inputs without weakening current/history scans.
- Wire exact produced/downloaded archives into the policy before upload/publication.

### 3. Review and closeout

- Run the original and alternate malicious cases, legitimate ZIP controls, complete Python policy, current/history scan and exact generated source snapshot.
- Perform separate security-boundary and bypass/regression passes; resolve only confirmed issues.
- Run relevant cross-platform/configured gates, close repo/PAD SSOT and update existing PR #69 only after local evidence is clean.

## Stop and rollback

- Stop if legitimate current archives cannot fit a defensible bound, a known bypass remains, or resolving it requires excluding safe-named text.
- Rollback is the isolated branch/worktree. Remote PR/tag/Release remain unchanged until separately gated.
- Physical audio, device, provider account, next binary publication and `HUMAN_GO` remain outside this plan.
