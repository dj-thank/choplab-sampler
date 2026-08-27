# Scan bounded ZIP content at the exact artifact publication boundary

## Purpose and user-visible outcome

Prevent credentials, signing material and user audio from becoming reachable inside a safe-named ZIP artifact while keeping legitimate ChopLab source, Windows app-image and iOS Simulator archives buildable. The final bytes uploaded or published must be scanned after archive creation, not inferred safe from an earlier source-tree scan.

## Exact objects

- Existing PR lineage: PR #69 head `1abbd8ff004edceafd81fe5ffa6059e2d3692d85`.
- Latest-main integration: `fa4e6d2646e44039ce41662ba4c6ae6970ae9dd6`, tree `fafdc23a12c9e5f1e414d8aa2d57da676c382aff`, parents PR #69 head and `main@0f5b672`.
- Product checkpoint: `3ed67101f362e61760c5ceb1839e71e91311aeb5`, tree `70e8f94f4e64cff4e13ea0e474e164632e500770`.
- Product subtrees and build configuration match `main@0f5b672` exactly; the delta is release policy/tests/workflow wiring and documentation.

## Initial findings

- An attacker-declared ZIP_LZMA dictionary reached `LZMADecompressor` before a memory cap.
- Compressed input was bounded per member but not across the archive, allowing aggregate decoder I/O/CPU amplification.
- Current workflows scanned source before creating the ZIPs they later uploaded or published.
- Initial exact-artifact testing showed legitimate iOS Mach-O and Windows JIMAGE/ct.sym/jvm.lib members would be misclassified as oversized text.

## Constraints and invariants

- Reject an oversized LZMA dictionary before decoder construction; Python's default ZIP_LZMA control continues to pass.
- Charge every decoded text candidate's declared compressed span to one archive-wide input budget before decoder work.
- Preserve complete layout, descriptor, local/central metadata, output-size, CRC, history, symlink and binary/audio exclusion controls from PR #69.
- Explicit archive paths fail closed when missing or malformed and share the same parser as current/history candidates.
- Source snapshot and Windows/iOS archives are scanned after creation and before upload; final release Windows/iOS archives are scanned after artifact download and before manifest, attestation or publication.
- Large unknown safe-named content remains rejected. Known binary compatibility uses bounded magic input rather than a broad filename exemption.
- Secret-shaped finding labels are redacted before CI output.
- No force push, duplicate PR, tag/Release, secret, device/provider or Human action in the local phase.

## Architecture

- The existing bounded EOCD/central/local/data-descriptor parser remains the single current/history/explicit archive boundary.
- ZIP_LZMA dictionary properties are checked against 16 MiB before constructing the raw decoder.
- Decoded text uses separate 512 KiB member-output, 4 MiB archive metadata/output, 4 MiB aggregate compressed-input and 100:1 ratio budgets.
- Large extensionless members use only a stored/deflate prefix probe capped at 64 KiB/member and 256 KiB/archive. Mach-O, ELF, JIMAGE and ar magic are recognized; exact JDK `runtime/lib/ct.sym` and `.lib` remain bounded binary exclusions.
- Repeatable `--archive` arguments scan exact post-build bytes. Producer workflows and the final publication job place the scan between archive creation/download and upload/manifest/attestation/publication.

## Progress

- [x] RED: six focused controls failed on decoder construction, missing aggregate input API, missing explicit CLI and missing workflow order.
- [x] GREEN: dictionary and aggregate budgets, explicit archive CLI and all producer/final consumer calls implemented.
- [x] Compatibility: exact current-main source, Windows and iOS archives first exposed four legitimate large binaries; narrow bounded classification fixed them while a large text control still fails.
- [x] Security review: broad `.sym` and two-byte `MZ` exemptions were removed; secret-shaped labels are redacted. One separate bypass/regression cycle found no remaining concrete issue.
- [x] Configured validation and repo SSOT closeout completed at `LOCAL_PASS`.

## Validation

- Focused RED: 1 failure / 5 errors at the intended six boundaries.
- Focused GREEN: dictionary, aggregate input, binary/text compatibility, explicit CLI and workflow order controls pass.
- Complete Python policy: 100 tests, failure/error 0; one local skip only because this Windows host lacks symlink creation privilege. Capable CI hosts still run that test.
- Current and reachable-history public scans: 465 candidates, PASS. `py_compile` and `git diff --check`: PASS.
- Configured validator: 18 tasks PASS; JVM 88 / 9 suites and Desktop 165 / 24 suites, zero failure/error/skip; XML, executable modes, wrapper SHA-256 and UTF-8 policy PASS.
- Exact archive scan: candidate source snapshot 1,542,548 / `F9B63B84A85A5D6336BE5C52FED5878DC6350AD20D09C3B3049015DA35C9B6A0`; current-main Windows ZIP 89,156,340 / `7619DDE24822CC5CF6B38893382AC46DF8752AE777F046844E6322713F42AAA2`; current-main iOS ZIP 318,236 / `5D17C8BD5E3DC6C359FED40F1B79B38CD901D53343735566201AA454BB72475C`; combined PASS.
- Product byte preservation: `app/`, `desktop/`, `shared/`, `jvm-core/`, `ios/` and build configuration are identical to `main@0f5b672`, whose four exact merged-main workflows passed in Wave 19.
- YAML parser unavailable locally; workflow source-order contracts pass, and syntax/execution remains an explicit hosted-CI gate.

## Review

Execution: local parent two-pass; no substitute child model used because the task contract forbids new subagents.

### Standards

The patch follows repository fail-closed/resource-bound rules, uses standard-library decoders only, keeps one scanner boundary and preserves executable/script/workflow conventions. The separate binary-prefix decoder duplicates a small deflate loop but has a distinct prefix-only resource contract and avoids weakening the full CRC/EOF verifier. No documented-standard breach or actionable smell remains. Unresolved findings: `0`.

### Spec

All six portfolio acceptance items are implemented: pre-constructor dictionary cap, archive aggregate input cap, supported-method controls, explicit safe/malicious/missing archive CLI, create/download→scan→upload/publish ordering, latest-main preservation and relevant checks. Bounded binary classification and label redaction are necessary compatibility/safety repairs, not unrelated scope. Android APK content scanning remains outside this Windows/iOS ZIP experiment and retains its separate manifest/signature verifier. Unresolved findings: `0`.

## Stop, rollback and remaining gates

- Rollback is the isolated branch/worktree. Remote PR #69 remains unchanged at its old head until provider authorization/check gates are exercised.
- PR #69 has 27 unresolved review threads; the prior 25 require fix/read-back confirmation and the final two require replies against this product before resolution.
- Exact-head hosted workflow syntax/execution, clean review, mergeability, normal merge and merged-main read-back remain provider gates.
- Physical audio, device, provider account, next binary publication and `HUMAN_GO` remain outside this plan.
