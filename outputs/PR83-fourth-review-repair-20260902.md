# PR #83 fourth review repair receipt — 2026-09-02

## Review input and stop

Head `9cc5b17215f813adc1aca2f60477023d76c68b1a` passed all eight hosted checks and had 13 resolved review threads. Fresh exact-head review found three further paths, so merge stopped:

1. Android public staging still copied removed variable `${apks[0]}` instead of `${debug_apks[0]}`;
2. the API 36 instrumentation proxy returned null for new primitive `capturePadWithOwnership`, causing Long unboxing failure in the existing CHOP GATE ownership test;
3. explicit debug-preview mode skipped the entire tooling check, so an unknown non-exported Compose tooling component could pass verification.

## Repair

Exact repair: `6070204f9175ae9f09613eedaebc8c7e7a2b17f5` / tree `732f516e3b5d820f7ee0ef9c56f50063f7f2b759`.

- The release workflow copies only `${debug_apks[0]}` to the public `android-debug.apk` path. Policy requires that exact source and forbids the removed `${apks[0]}` token.
- `FirstScreenFlowDeviceTest` handles `capturePadWithOwnership` identically to its existing trigger ownership path, recording `capturePad` and returning a real Long token.
- Debug verifier subtracts only `PreviewActivity` and `ComponentActivity` from the tooling denylist. Unknown exported or non-exported `androidx.compose.ui.tooling.*` components still fail closed.

## Verification

- Python repository/release policy: 75 tests / 0 failures.
- Release workflow YAML parse: PASS.
- Current plus history public-surface scan: 477 candidates; credential, signing, audio candidates 0.
- AndroidTest Kotlin compile: `BUILD SUCCESSFUL in 39s`; 42 tasks (11 executed / 31 up-to-date). No device, install, or ADB operation was performed.
- Actual local Android debug APK re-read: `0.17.1 (28)`, explicit debug-preview tooling/permission/export/alignment/signature verification PASS.
- `git diff --check`: PASS.
- Parent Standards/Spec unresolved local findings: 0/0.

## Gate

Ceiling remains `LOCAL_PASS`. New-head hosted Android/Windows/iOS/supply-chain checks, three exact replies/resolutions, another fresh no-finding review, merge, and tag/public read-back remain mandatory.
