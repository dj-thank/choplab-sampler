# Scan bounded ZIP content at the exact artifact publication boundary

## Purpose and user-visible outcome

Prevent credentials, signing material and user audio from becoming reachable inside a safe-named ZIP artifact while keeping legitimate ChopLab source, Windows app-image and iOS Simulator archives buildable. The final bytes uploaded or published must be scanned after archive creation, not inferred safe from an earlier source-tree scan.

## Exact objects

- Existing PR lineage: PR #69 head `1abbd8ff004edceafd81fe5ffa6059e2d3692d85`.
- Current-main integration anchor: `42aa1a30eae9626ac90cac7f6f1a9f20219987a3`, tree `b0c4f9456ecf4e798c0c30f28f0ff29263e25c54`, parents review repair `8a5218e` and `main@d3291a5`.
- Product checkpoint: `3ed67101f362e61760c5ceb1839e71e91311aeb5`, tree `70e8f94f4e64cff4e13ea0e474e164632e500770`.
- Final review checkpoints: `e1306935312ce4e6bebe5600c972c6f0efd1c569` and bounded-inventory successor `181701beeaa9d02f737f284e3fc92ce33e438ff2`.
- Current-main integration: `a5cfeac20b7f1062beeecbf8579b15f1e6e0a5a1`, tree `2f55b89aafa7f78284337c3edda107f107c8a3d0`, second parent `main@012b131784394b2fd641d580aaf4cd2d56b907f4`.
- Product subtrees and build configuration match `main@d3291a5` exactly; the PR-specific delta is release policy/tests/workflow wiring and documentation.

## Initial findings

- An attacker-declared ZIP_LZMA dictionary reached `LZMADecompressor` before a memory cap.
- Compressed input was bounded per member but not across the archive, allowing aggregate decoder I/O/CPU amplification.
- Current workflows scanned source before creating the ZIPs they later uploaded or published.
- Initial exact-artifact testing showed legitimate iOS Mach-O and Windows JIMAGE/ct.sym/jvm.lib members would be misclassified as oversized text.

## Constraints and invariants

- Reject an oversized LZMA dictionary before decoder construction; Python's default ZIP_LZMA control continues to pass.
- Charge every decoded text candidate's declared compressed span to one archive-wide input budget before decoder work.
- Preserve complete layout, descriptor, local/central metadata, output-size, CRC, history, symlink and binary/audio exclusion controls from PR #69.
- Explicit ZIP/APK paths fail closed when missing or malformed and share the same parser as current/history candidates.
- Source snapshot and Android/Windows/iOS archives are scanned after creation/staging and before upload; the downloaded Android/iOS publication assets are rescanned before manifest, attestation or publication. Windows remains a verification-only artifact and is not downloaded by the publication job.
- Large unknown safe-named content remains rejected regardless of a spoofed magic prefix or basename. `.app`, Skiko ICU, non-APK binary, JIMAGE and APK binary bodies are fully verified and byte-secret/audio scanned under explicit member/aggregate limits; no binary body is accepted from a header probe alone.
- Current/explicit ZIP/APK roots, nested recursion, binary/JIMAGE bodies and APK binary bodies have separate shared count/input/output limits. Reachable-history ZIP change records, direct blob refs and non-commit tree listings are streamed or enumerated under explicit caps.
- APK Signing Block pair values and the exact CycloneDX SBOM text are scanned before first upload and again after final artifact download.
- Secret-shaped finding labels are redacted before CI output.
- No force push, duplicate PR, tag/Release, secret, device/provider or Human action in the local phase.

## Architecture

- The existing bounded EOCD/central/local/data-descriptor parser remains the single current/history/explicit archive boundary.
- ZIP_LZMA dictionary properties are checked against 16 MiB before constructing the raw decoder.
- Decoded text uses separate 512 KiB member-output, 4 MiB archive metadata/output, 4 MiB aggregate compressed-input and 100:1 ratio budgets.
- A main `.app` executable up to 4 MiB and root `icudtl.dat` inside `skiko-awt-runtime-windows-*.jar` up to 16 MiB are fully scanned. Non-APK binaries use 32 MiB/member under a recursive 384 MiB budget. Exact JDK `runtime/lib/modules` is fully verified/scanned up to 128 MiB after JIMAGE validation; `ct.sym` receives 20,000-entry/128 MiB recursive compatibility rather than exclusion. Root ZIP/APK work is capped at 128 roots/512 MiB and nested work at depth 3/64 archives/256 MiB.
- Finding labels are redacted and bounded to 512 characters; recursive diagnostics share a 256-finding/64 KiB output budget. Release publication enumerates and scans every file in `dist` both after artifact download and again after manifest/checksum generation, before attestation or `dist/*` publication.
- Neutral-name DER sequences carrying standard key OIDs are rejected; conventional APK `META-INF/*.RSA|*.DSA|*.EC` public signature certificates remain compatible, while the PKCS#8 private-key shape is still rejected there.
- Repeatable `--archive` arguments scan exact post-build bytes. Producer workflows and the final publication job place the scan between archive creation/download and upload/manifest/attestation/publication. Android v2/v3-style signing gaps are accepted only as a bounded pair-structured `APK Sig Block 42`; other interior gaps remain rejected.

## Progress

- [x] RED: six focused controls failed on decoder construction, missing aggregate input API, missing explicit CLI and missing workflow order.
- [x] GREEN: dictionary and aggregate budgets, explicit archive CLI and all producer/final consumer calls implemented.
- [x] Compatibility: exact current-main source, Windows and iOS archives first exposed four legitimate large binaries; narrow bounded classification fixed them while a large text control still fails.
- [x] Security review: broad `.sym` and two-byte `MZ` exemptions were removed; secret-shaped labels are redacted. Hosted review then reproduced magic-prefix spoofing; generic magic exemptions were removed and the malicious ELF-prefix/app-token controls now fail closed.
- [x] Final review follow-up: nested archives, BOM-marked UTF-16/32, safe-named audio payloads and non-commit tree refs received direct regressions. Nested recursion and resource caps preserve the exact source/Windows/iOS controls.
- [x] Final-final review follow-up: nested archive content signatures now defeat filename aliases, and recursively decoded outputs consume the same expanded-work budget across every depth.
- [x] Late hosted review follow-up: prefixed ZIPs, renamed unsupported archives, direct blob refs, ID3-less MP3, ICU basename aliases, cross-root work amplification and unbounded raw history enumeration have direct regressions and fail-closed bounds.
- [x] Post-CI hosted follow-up: binary-suffix audio is probed before exclusion, Android APK bytes are scanned before both upload and publication, and raw ZIP backslash separators are rejected before platform path interpretation.
- [x] Final hosted follow-up: APK signing pair values, every bounded binary/JIMAGE body and exact SBOM text are secret-scanned; active registry next action now points to PR #69.
- [x] PR #69 review follow-up: prefixed historical ZIPs, BOM-less UTF-16 metadata, bounded recursive findings, neutral-name DER signing material, annotated-tag messages, direct non-ZIP blobs, every release-glob asset, and lazy ISO brand probing now have focused regressions.
- [x] Post-hosted Windows compatibility: valid PE Authenticode certificates and trusted-certificate-only JDK JKS stores remain publishable, while embedded private-key material still fails closed.
- [x] Late review closure: SEC1 EC keys, full annotated-tag message policy, non-commit-tree symlink targets, neutral-name PEM certificates and bounded DER candidate work now have direct regressions.
- [x] Final review closure: AMR-NB/WB, classic GitHub token families, PuTTY PPK, extended-size ISO-BMFF `ftyp`, validated Authenticode certificate tables, NUL-safe historical filenames, and traversal/absolute/drive-root ZIP paths have direct regressions.
- [x] Current-main integration preserves the v0.17.2 signer verifier and avoids scanner self-detection by constructing PEM fixture markers at runtime.
- [x] Fresh integrated review closure: reachable commit messages and neutral historical binary blobs share bounded content policy; JKS exemptions validate X.509 DER bodies; archive-valued ZIP metadata is rejected; Windows post-download wording matches the actual Android/iOS-only publication job.
- [x] Configured validation and repo SSOT closeout completed at `LOCAL_PASS`.

## Validation

- Focused RED: 1 failure / 5 errors at the intended six boundaries.
- Focused GREEN: dictionary, aggregate input, binary/text compatibility, explicit CLI and workflow order controls pass.
- Exact current-tree policy suite: 195 tests at `baf70085eb572511df200d3b30b764c464e6e887` via `python -m unittest discover -s scripts/tests -p 'test_*.py'`, failure/error 0; one local skip only because this Windows host lacks symlink creation privilege. Focused scanner is 119. Earlier counts remain historical evidence for predecessor trees; capable CI hosts still run the skipped symlink test.
- Current and reachable-history public scans: 483 candidates, PASS. `py_compile`/import execution and `git diff --check`: PASS.
- Windows compatibility regression: preserved v0.17.0 app-image ZIP `AC0552B51EA0C614AFC4B41C7B5FEC2C40247B641177385CB8AC777F26A17435` passes as one explicit archive after the PE/JKS refinement.
- Configured validator: 18 tasks PASS; JVM 88 / 9 suites and Desktop 165 / 24 suites, zero failure/error/skip; XML, executable modes, wrapper SHA-256 and UTF-8 policy PASS.
- Exact artifact scan: candidate source snapshot 1,542,548 / `F9B63B84A85A5D6336BE5C52FED5878DC6350AD20D09C3B3049015DA35C9B6A0`; signed Android APK 24,035,572 / `F8DCDBF5E7B13AF567F0388A5EFD885E61CFEA306F74AF590650DE677766772C`; Windows ZIP 89,156,340 / `7619DDE24822CC5CF6B38893382AC46DF8752AE777F046844E6322713F42AAA2`; iOS ZIP 318,236 / `5D17C8BD5E3DC6C359FED40F1B79B38CD901D53343735566201AA454BB72475C`; CycloneDX SBOM 1,581,101 / `413688DEDDBED53F235D311B7BE7B9472D6202B72ABD9F531D2EFA9D86A63DF2`; combined PASS.
- Exact current-main Gradle rerun: 111/111 tasks executed; shared Desktop 87, shared Android host 87, Android 289, JVM core 88, Desktop 180 and H13 24, failure/error/skip 0. Android lint/APK/AndroidTest compile and Windows package pass.
- Product inheritance: runtime subtrees are inherited from `main@012b131`; PR #69's product delta remains scanner/policy/workflow/evidence only. Exact-head hosted workflows remain a separate provider gate.
- YAML parser unavailable locally; workflow source-order contracts pass, and syntax/execution remains an explicit hosted-CI gate.

## Review

Execution: local parent two-pass; no substitute child model used because the task contract forbids new subagents.

### Standards

The patch follows repository fail-closed/resource-bound rules, uses standard-library decoders only, keeps one ZIP/APK/text scanner boundary and preserves executable/script/workflow conventions. Git, recursive, root, binary/JIMAGE, APK binary and explicit text work are separately bounded. Every accepted binary body and APK signing pair value receives byte-level secret scanning; compatibility paths retain full CRC/size/EOF verification. No documented-standard breach or actionable smell remains. Unresolved findings: `0`.

### Spec

All six portfolio acceptance items are implemented: pre-constructor dictionary cap, archive/cross-root/binary input-output caps, supported-method controls, explicit safe/malicious/missing ZIP/APK/text CLI, create/download→scan→upload/publish ordering, latest-main preservation and relevant checks. Full bounded app/Skiko/binary/JIMAGE/APK scanning, signing-pair/SBOM inspection, nested aliases, BOM/audio, Windows separators, non-commit refs and label redaction are completeness/compatibility repairs, not unrelated scope. The Android manifest/signature and SBOM identity verifiers remain complementary identity gates rather than substitutes for content scanning. Unresolved findings: `0`.

## Stop, rollback and remaining gates

- Rollback is the isolated branch/worktree. Existing PR #69 remains the only remote target; its exact head/check/review state must be refreshed after this local repair is committed and pushed normally.
- Exact-head hosted workflow syntax/execution, clean review, mergeability, normal merge and merged-main read-back remain provider gates.
- Physical audio, device, provider account, next binary publication and `HUMAN_GO` remain outside this plan.
