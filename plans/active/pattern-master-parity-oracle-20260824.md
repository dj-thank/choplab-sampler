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
- [ ] Complete full local gate and review.
- [ ] Complete exact artifact checks.
- [ ] Complete PR/merge/main readback.

## Discoveries

- Primitive-level parity did not catch event-loop ordering; a voice can return a nonzero final sample and mark itself finished in the same call.
- Boundary fade does not guarantee the cursor lands exactly on an envelope-zero frame for non-unity pitch ratios.
- The existing offline loop treated `finished` as “returned no sample,” while realtime treats it as “this returned sample is last.”

## Decision log

- 2026-08-24 — Make realtime Voice + shared limiter the compatibility oracle for this fixture.
- 2026-08-24 — Repair ordering in PatternRenderer rather than weakening tolerance or dropping the final realtime sample.
- 2026-08-24 — Stop at single-event/master parity; polyphony/choke/multi-event/stereo remain separate extensions.

## Validation log

- Focused oracle before fix — RED, maximum delta 61 at frame 402 (`offline=0`, `realtime=-61`).
- Focused oracle after mix-before-remove — PASS.

## Risks and rollback

- Risk: one extra terminal sample changes existing export bytes. Mitigation: it restores the realtime-returned sample and is bound by an exact oracle.
- Risk: polyphonic ordering differs. Mitigation: this fixture claims one event only; future oracles extend dimensions explicitly.
- Rollback: revert test and one-line renderer change; no schema/data migration.
- Stop before native stream/Voice extraction, recording, user-audio extraction, provider auth, data deletion, force push, tag or publication.

## Remaining device validation

- Exact new APK/app-image cold launch and project preservation only.
- Physical listening, latency, full-pattern polyphony, stereo and Human equivalence remain separate.
