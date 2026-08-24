# Single-event pattern/master parity oracle — 2026-08-24

## Purpose and user-visible outcome

Offline WAV export retains every realtime PAD sample for a single pattern event and applies the same master limiter across the entire bar. The test becomes an executable gate before shared Voice/event-kernel extraction or native-engine work.

## Current state

- Exact root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-pattern-master-oracle-20260824`.
- Branch/base: `codex/choplab-pattern-master-oracle` from public `main@5c56d844c86dc7dcdbd57e3f88154d99469e1a65`, tree `c38aea78a47dcba47b7998c9c8632db9a3324c91`.
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`; one checkout and GitHub writer. Pixel is used only after a product-code local pass.
- Dirty canonical and earlier phase worktrees are preserved and excluded.
- Shared numeric primitives and a direct PAD Voice/host oracle are already on main.
- Initial full-bar oracle was observed RED: maximum delta 61 at frame 402, offline 0 versus realtime -61.
- Root cause: `PatternRenderer` removed a voice that became finished during `render()` before mixing that final returned sample.

## Constraints and invariants

- One PAD, one step-0 event, one bar and one master limiter define this bounded oracle.
- Expected PCM uses `SamplerEngine.Voice` plus shared `softLimit`; actual PCM is read from `PatternRenderer` WAV.
- Every frame is compared with maximum tolerance one PCM integer unit, including the final energetic frame and silent tail.
- Fix mixing order only; do not change cursor, fade, limiter, timing, schema, UI or device/audio ownership.
- No recording, physical audio capture, provider, data deletion, tag or release work.

## Architecture and interfaces

- `PatternMasterParityTest`: cross-module full-bar oracle in Android host unit tests.
- `PatternRenderer`: mixes the value returned by a voice before removing a newly finished voice.
- `SamplerEngine.Voice` and `SamplerDspPrimitives.softLimit`: expected realtime path.
- `WavFileWriter`/PCM reader: actual offline path and exact frame count.
- ADR: `docs/architecture/ADR-0004-pattern-master-parity-gate.md`.

## Milestones

### Milestone 1: RED oracle and localization

- Render a nontrivial reverse/pitched/filtered PAD through both paths.
- Compare all frames and report maximum-delta frame/values.
- Acceptance: mismatch is reproducible and causally localized.

### Milestone 2: minimal product repair

- Mix the returned sample before removing a finished offline voice.
- Keep all existing lifecycle/math unchanged.
- Acceptance: full-bar oracle passes within one unit and last energetic frame matches.

### Milestone 3: local gate and review

- Run full tests/Lint/APKs/Windows package/policy.
- Run Standards/Spec review and update SSOT.
- Acceptance: zero failures/errors/skips and zero unresolved finding.

### Milestone 4: exact artifact boundary

- Because JVM core ships in Android/Windows, build exact artifacts and run non-recording Windows/Pixel cold-launch checks after local pass.
- Do not claim human listening parity.
- Acceptance: exact bytes and project preservation receipts.

### Milestone 5: GitHub integration

- Push, PR, all checks, squash merge and merged-main readback.
- No tag/Release.
- Acceptance: main contains oracle/fix with four workflow families green.

## Progress

- [x] 2026-08-24 05:37 JST — Fixed exact main, owner, scope, tolerance and native-engine stop boundary.
- [x] 2026-08-24 05:39 JST — Oracle RED: delta 61 at frame 402, offline 0 / realtime -61.
- [x] 2026-08-24 05:41 JST — One-line mix-before-remove repair makes oracle GREEN.
- [x] 2026-08-24 05:43 JST — Full 152-task local gate passed; Android 229 and all other suites zero failures/errors/skips.
- [x] 2026-08-24 05:43 JST — Final review unresolved Standards 0 / Spec 0; committed local SSOT.
- [x] 2026-08-24 05:46 JST — Exact Windows runtime and Pixel `DB08…` retained install/readback/cold launch passed.
- [x] 2026-08-24 06:03 JST — PR #49 merged as `main@ecc6c54`; PR/push 8/8 and merged-main Android/Windows/iOS/Supply-chain 4/4 PASS.
- [x] 2026-08-24 follow-up — reproduced fractional event timing drift, added the shared first-not-earlier frame contract, and changed offline scheduling to preserve exact accumulation across bars. Hosted CI remains required for the follow-up commit.
- [x] 2026-08-24 review follow-up — rebased onto `main@8fa1dac`, retained the count-resilient AVD result parser, and replaced absolute-deadline accumulation with realtime's carried-residual recurrence. Added early-event and terminal-WAV boundary regressions; remote exact head `786e2e7` then passed all hosted workflows and exact-head re-review.
- [x] 2026-08-24 latest-main integration — preserved the four reviewed audio product/test files exactly in reachable product `0b75c71` / tree `18968b1` on `main@6b645ca`; fresh hosted execution remains required for the integrated head.

## Discoveries

- Primitive-level parity did not catch event-loop ordering; a voice can return a nonzero final sample and mark itself finished in the same call.
- Boundary fade does not guarantee the cursor lands exactly on an envelope-zero frame for non-unity pitch ratios.
- The existing offline loop treated `finished` as “returned no sample,” while realtime treats it as “this returned sample is last.”
- Event timing had a second independent mismatch: realtime retained fractional countdown remainder, while offline truncated event deadlines and rounded each bar separately. At 48 kHz / 92 BPM / 54% swing this moved the first step-1 hit one frame early and extended four bars by two frames.
- Applying `ceil` to a continuously growing absolute `Double` still differs from the realtime countdown's floating-point operation order. The mismatch is observable in the first bar at 48 kHz / 120 BPM / 55% swing and can also change the terminal WAV length at 40 BPM / 56% swing.

## Decision log

- 2026-08-24 — Make realtime Voice + shared limiter the compatibility oracle for this fixture.
- 2026-08-24 — Repair ordering in PatternRenderer rather than weakening tolerance or dropping the final realtime sample.
- 2026-08-24 — Stop at single-event/master parity; polyphony/choke/multi-event/stereo remain separate extensions.
- 2026-08-24 — Quantize the carried realtime countdown at each first-not-earlier output-frame boundary; subtract the integer advance and retain its residual across steps and bars. Do not accumulate an independently rounded absolute deadline.

## Validation log

- Focused oracle before fix — RED, maximum delta 61 at frame 402 (`offline=0`, `realtime=-61`).
- Focused oracle after mix-before-remove — PASS.
- Full `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — PASS, 152 tasks; Android 229, shared 25/25, JVM-core 52, Desktop 76.
- Python policy 23 and public current/reachable-history surface 386 — PASS.
- Windows packaged runtime and project-digest preservation — PASS.
- Pixel exact SHA `db089f70…`, signer/installed bytes/project shape/cold launch/navigation/fatal negative — PASS. Receipt: parent PAD `work/PAD_CHOPLAB_PATTERN_MASTER_7B04728_DEVICE_RECEIPT_20260824.json`.
- GitHub — parent PAD `work/PAD_CHOPLAB_PATTERN_MASTER_GITHUB_RECEIPT_20260824.md`; PR #49, exact tree and merged-main runs `32665966662`, `32665966566`, `32665966531`, `32665966589` PASS.
- Windows daily install — provider artifact id `9500051082`, main `ecc6c54`, app-image digest `802a667d…`, installed at `%LOCALAPPDATA%/Programs/ChopLab/0.17.0-802a667d39cb`; shortcut/readback/runtime/project preservation PASS.
- Rebased follow-up `f0a36d9` / tree `850cf9c` on `main@5430d0d` — arithmetic RED reproduced old 8,452 vs realtime 8,453 first event and old 500,872 vs continuous 500,870 four-bar length. New shared/JVM-core regressions are committed; Python 26, public-surface 392, diff and no-iOS-delta checks PASS. Gradle execution awaits hosted CI.
- Latest-main product `0b75c71` / tree `18968b1` on `main@6b645ca` preserves the reviewed runtime/test blobs from reachable remote product `9ba2019` / tree `4b57329`. The residual arithmetic fixes 18,600 vs 18,601 early event and 288,000 vs 288,001 one-bar boundary cases; fresh static checks are recorded in `docs/VALIDATION.md` and Gradle remains unavailable locally.

## Risks and rollback

- Risk: one extra terminal sample changes existing export bytes. Mitigation: it restores the realtime-returned sample and is bound by an exact oracle.
- Risk: polyphonic ordering differs. Mitigation: this fixture claims one event only; future oracles extend dimensions explicitly.
- Rollback: revert test and one-line renderer change; no schema/data migration.
- Stop before native stream/Voice extraction, recording, user-audio extraction, provider auth, data deletion, force push, tag or publication.

## Remaining device validation

- Exact new APK/app-image cold launch and project preservation only.
- Physical listening, latency, full-pattern polyphony, stereo and Human equivalence remain separate.
