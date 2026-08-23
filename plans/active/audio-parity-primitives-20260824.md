# Realtime/offline audio parity primitives — 2026-08-24

## Purpose and user-visible outcome

The same PAD parameters produce the same bounded numeric behavior in Android realtime playback, offline WAV export and Windows PAD rendering. Invalid NaN/Infinity controls cannot leak non-finite samples or destabilize timing. This establishes a small parity oracle before any native-engine replacement.

## Current state

- Exact root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-audio-parity-20260824`.
- Branch/base: `codex/choplab-audio-parity` from public `main@28bd388acef12dde96befac9774a1853831f82b0`, tree `3e3a90d3d12339c50f1c23614ef728c6f5fbcdf2`.
- Owner: root task `01a02e46-4c8f-7120-a7be-a4d202c713d0`; one checkout, Pixel/ADB and GitHub writer.
- Dirty canonical and earlier phase worktrees remain preserved and excluded.
- Baseline duplication: Android `SamplerEngine` is about 935 lines, offline `PatternRenderer` about 264, and `PadPcmRenderer` about 55. Pitch step, tone coefficient, boundary fade, gain policy, swing timing and limiting are separately implemented.
- Baseline divergence: realtime tone maps non-finite input to bypass, while offline/desktop formulas can propagate NaN; offline tone recalculates `exp()` for every rendered sample.
- Initial common primitives and focused parity tests compile locally; full local/device/GitHub gates are not yet claimed.

## Constraints and invariants

- Realtime callback remains allocation-free and performs no I/O, locks, logging or heavy JNI.
- Project schema, UI, recording, capture, provider and native stream ownership do not change.
- Explicit non-finite policy: pitch 0 st, tone bypass, gain silence, BPM 92, swing straight.
- Finite values retain documented clamp ranges: pitch -24..24, tone 0..1, gain 0..1.5, BPM 40..240, swing 50..75.
- Boundary fade is start/end symmetric in forward and reverse directions.
- Soft limiter always returns finite output.
- One even/odd swing pair preserves the straight two-step duration.
- Native engine integration remains blocked until this and later event/voice equivalence harnesses pass.

## Architecture and interfaces

- `SamplerDspPrimitives` in shared commonMain: allocation-free normalization, source step, tone alpha, boundary envelope, soft limiter and step duration.
- Android `SamplerEngine`: delegates control-boundary snapshot sanitation, voice math, transport timing and master limiter.
- JVM `PatternRenderer`: delegates the same policies and computes tone alpha once per voice instead of once per sample.
- JVM `PadPcmRenderer`: serves as the host PAD oracle with the same source/tone/gain/fade math.
- Realtime/host oracle: renders the same PAD through `SamplerEngine.Voice` and `PadPcmRenderer` and compares PCM within one integer unit.
- ADR: `docs/architecture/ADR-0003-audio-parity-primitives.md`.

## Milestones

### Milestone 1: numeric policy contract

- Add shared finite/clamp/timing/fade/limiter primitives and common tests.
- Run on Desktop JVM and Android host.
- Acceptance: all invalid inputs produce explicit finite policy values.

### Milestone 2: three-path integration

- Replace duplicate math in realtime Voice, PatternRenderer and PadPcmRenderer.
- Add realtime/host PCM oracle and offline non-finite regression.
- Acceptance: focused shared/JVM/Android tests pass and offline inner-loop exponential work is removed.

### Milestone 3: full local gate and review

- Run all tests, Lint, APKs, Windows package, public-surface/policy and SBOM where inputs require it.
- Review Standards and Spec independently from main.
- Update state, feature matrix and this plan.
- Acceptance: zero failures/errors/skips and zero unresolved review finding.

### Milestone 4: runtime and scoped device

- Launch exact Windows package with isolated data and preserve real project digest.
- Signer-admit/data-preserving install exact Android candidate, cold launch and fatal/ANR negative only; do not claim audio quality from launch.
- Acceptance: exact LOCAL and scoped DEVICE receipts.

### Milestone 5: GitHub integration

- Push, PR, require all checks, squash merge, verify tree and merged-main workflows.
- No tag or Release for an internal parity tracer.
- Acceptance: public main contains the reviewed tree with all four workflow families green.

## Progress

- [x] 2026-08-24 04:58 JST — Fixed main, owner, rollback, target gates and native-engine stop boundary.
- [x] 2026-08-24 05:00 JST — Audited realtime/offline/desktop duplication and selected shared primitives over engine replacement.
- [x] 2026-08-24 05:05 JST — Implemented initial primitive policy and three-path integration.
- [x] 2026-08-24 05:08 JST — Focused shared/JVM/Android parity and non-finite tests pass.
- [x] 2026-08-24 05:12 JST — Full 152-task local gate passed; shared hosts 24 each, Android 228, JVM-core 52 and Desktop 76 with zero failures/errors/skips.
- [x] 2026-08-24 05:10 JST — Review fixes bound sample-rate/finite clamps and removed the Android middle man; final full gate shared hosts 25 each.
- [x] 2026-08-24 05:10 JST — Final two-axis re-review unresolved Standards 0 / Spec 0; committed local SSOT.
- [x] 2026-08-24 05:14 JST — Exact Windows isolated runtime and Pixel `F9CD…` retained install/readback/cold launch passed.
- [ ] Complete PR/merge/main readback.

## Discoveries

- Realtime tone alpha already sanitized NaN, but pitch/gain snapshots and both JVM renderers did not share that policy.
- Offline voice recalculated cutoff/exponential per sample despite tone being immutable for that voice.
- Realtime and offline already share `VoicePlaybackCursor` and equivalent formulas, making a primitive-first oracle lower risk than a full voice rewrite.
- Realtime master and offline master both use the same saturating limiter shape, but it existed as duplicated inline arithmetic.

## Decision log

- 2026-08-24 — Use shared commonMain pure functions so Android, JVM and future iOS can consume the same policy.
- 2026-08-24 — Treat all non-finite controls as invalid and use explicit safe neutral values rather than platform-specific `coerceIn` behavior.
- 2026-08-24 — Keep Voice/cursor ownership in existing engines; this slice centralizes math, not lifecycle.
- 2026-08-24 — Require a realtime/host PCM oracle before extracting a shared voice kernel.

## Validation log

- `:shared:desktopTest :shared:testAndroidHostTest :jvm-core:test :app:testDebugUnitTest` — PASS after initial primitive integration and parity/non-finite tests.
- Full `:shared:desktopTest :shared:testAndroidHostTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :jvm-core:test :desktop:test :desktop:packageWindows` — PASS, 152 tasks; shared 24/24, Android 228, JVM-core 52, Desktop 76; zero failures/errors/skips.
- Python policy 22 and public current/reachable-history surface 382 — PASS.
- Review-fix full gate — PASS, 152 tasks; shared 25/25, Android 228, JVM-core 52 and Desktop 76 with zero failures/errors/skips.
- Windows packaged runtime — responding launcher/UI, exact process stop and real-project digest preservation PASS.
- Pixel — exact SHA `f9cd14e0…`, signer match, retained install/readback, projects 7 / 62,592 KiB preserved, cold launch/navigation/fatal negative PASS. Receipt: parent PAD `work/PAD_CHOPLAB_AUDIO_PARITY_3CCD414_DEVICE_RECEIPT_20260824.json`.

## Risks and rollback

- Risk: one-sample rounding differences. Mitigation: direct PCM oracle with a one-unit tolerance and existing waveform fixtures.
- Risk: changing invalid-input output. Mitigation: only NaN/Infinity behavior changes; finite control clamps remain identical and are tested.
- Risk: shared function call overhead in callback. Mitigation: allocation-free constant-time math; tone exponential remains control-boundary only.
- Rollback: revert the parity commits; schema and user data are unchanged.
- Stop before recording, user-audio extraction, native stream replacement, provider auth, data deletion, force push, tag or binary publication.

## Remaining device validation

- Exact APK signer/hash retained install and cold launch.
- Physical audio quality/latency and realtime/offline listening comparison require a separate Human/audio task.
- Route loss, recording and TalkBack remain out of this numeric parity tracer.
